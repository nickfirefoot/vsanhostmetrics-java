package com.cptvsan.adapter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A fake vCenter (vSphere JSON API) and a fake Operations Suite API in one
 * in-process HTTP server, for the tests. Offline: binds 127.0.0.1 only. The
 * Java counterpart of the Python pack's tools/fake_vsphere.py, much smaller.
 */
final class FakeOps implements AutoCloseable {

    final HttpServer server;
    final int port;
    final String vcid = "vc-uuid-1";
    String version = "9.1.1";

    /** One cluster "domain-c1" with {@code hosts} hosts and {@code vmsPerHost} VMs each. */
    final int hosts;
    final int vmsPerHost;

    /** Entity types that return rows; every other type echoes {@code <type>:*}. */
    final Map<String, List<String>> present = new LinkedHashMap<>();
    /** Entity types that fault NotSupported. */
    final Set<String> unsupported = new TreeSet<>();
    /** Entity types that fail with a server error. */
    final Set<String> broken = new TreeSet<>();

    // Counters and records
    final AtomicInteger logins = new AtomicInteger();
    final AtomicInteger waits = new AtomicInteger();
    final AtomicInteger creates = new AtomicInteger();
    final Map<String, AtomicInteger> perfCalls = new ConcurrentHashMap<>();
    final List<String> rawPaths = new CopyOnWriteArrayList<>();
    final List<String> suitePaths = new CopyOnWriteArrayList<>();
    /** rid to the stat keys written (batch or per-resource). */
    final Map<String, Set<String>> statKeys = new ConcurrentHashMap<>();
    final Map<String, Set<String>> propKeys = new ConcurrentHashMap<>();
    final AtomicInteger batchStatsPosts = new AtomicInteger();
    final AtomicInteger batchPropsPosts = new AtomicInteger();
    final AtomicInteger perResourcePosts = new AtomicInteger();
    final AtomicInteger tokenAcquires = new AtomicInteger();
    /** Status the next batch POSTs answer with (consumed one per call); empty = 200. */
    final List<Integer> batchStatuses = new CopyOnWriteArrayList<>();
    /** Per-resource POST status for this rid (default 200). */
    final Map<String, Integer> perResourceStatus = new ConcurrentHashMap<>();
    /** Ids the Suite API knows. */
    final Map<String, String> ridByKey = new ConcurrentHashMap<>(); // kind|moid to rid
    volatile int updateVersion = 1;
    volatile boolean notAuthenticatedOnce;

    FakeOps(int hosts, int vmsPerHost) throws IOException {
        this.hosts = hosts;
        this.vmsPerHost = vmsPerHost;
        ridByKey.put("ClusterComputeResource|domain-c1", "rid-c1");
        for (int h = 1; h <= hosts; h++) {
            ridByKey.put("HostSystem|host-" + h, "rid-h" + h);
            for (int v = 1; v <= vmsPerHost; v++) {
                ridByKey.put("VirtualMachine|vm-" + h + "-" + v, "rid-v" + h + "-" + v);
            }
        }
        for (int h = 1; h <= hosts; h++) {
            addPresent("vsan-pnic-net", "vsan-pnic-net:" + node(h) + "|vmnic0");
            addPresent("host-domowner", "host-domowner:" + node(h));
            addPresent("host-cpu", "host-cpu:" + node(h));
            addPresent("host-cpu", "host-cpu:" + node(h) + "|0");
        }
        addPresent("cluster-domowner", "cluster-domowner:" + clusterUuid());
        addPresent("cluster-resync", "cluster-resync:" + clusterUuid());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "fake-ops");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
        port = server.getAddress().getPort();
    }

    void addPresent(String type, String ref) {
        present.computeIfAbsent(type, k -> new ArrayList<>()).add(ref);
    }

    String node(int h) {
        return "node-uuid-" + h;
    }

    String clusterUuid() {
        return "cluster-uuid-1";
    }

    String vcBase() {
        return "http://127.0.0.1:" + port;
    }

    String suiteBase() {
        return "http://127.0.0.1:" + port + "/suite-api";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ------------------------------------------------------------------

    private static void send(HttpExchange x, int status, String body, String... headers)
            throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i + 1 < headers.length; i += 2) {
            x.getResponseHeaders().add(headers[i], headers[i + 1]);
        }
        x.getResponseHeaders().add("Content-Type", "application/json");
        if (b.length == 0) {
            x.sendResponseHeaders(status, -1);
        } else {
            x.sendResponseHeaders(status, b.length);
            try (OutputStream o = x.getResponseBody()) {
                o.write(b);
            }
        }
        x.close();
    }

    private static String body(HttpExchange x) throws IOException {
        return new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    static String fault(String type, String key, String msg) {
        return "{\"_typeName\":\"" + type + "\",\"faultMessage\":[{\"_typeName\":"
                + "\"LocalizableMessage\",\"key\":\"" + key + "\",\"message\":\"" + msg
                + "\"}],\"faultstring\":\"fault\"}";
    }

    private void handle(HttpExchange x) throws IOException {
        String raw = x.getRequestURI().getRawPath();
        String q = x.getRequestURI().getRawQuery();
        try {
            if (raw.startsWith("/suite-api/")) {
                suite(x, raw.substring("/suite-api/".length()), q);
            } else if (raw.equals("/sdk/vimServiceVersions.xml")) {
                send(x, 200, "<x><name>urn:vim25</name><version>8.0.3.0</version></x>");
            } else if (raw.startsWith("/sdk/vim25/")) {
                rawPaths.add(raw);
                String[] parts = raw.substring("/sdk/vim25/".length()).split("/", 2);
                vim(x, parts.length > 1 ? parts[1] : "");
            } else {
                send(x, 404, "");
            }
        } catch (RuntimeException e) {
            send(x, 500, fault("RuntimeFault", "k", String.valueOf(e)));
        }
    }

    static String moref(String type, String v) {
        return "{\"_typeName\":\"ManagedObjectReference\",\"type\":\"" + type + "\",\"value\":\""
                + v + "\"}";
    }

    private static String boxed(String type, String v) {
        return "{\"_typeName\":\"" + type + "\",\"_value\":" + v + "}";
    }

    private static String s(String v) {
        return boxed("string", Json.quote(v));
    }

    private String ps(String name, String val) {
        return "{\"name\":\"" + name + "\",\"val\":" + val + "}";
    }

    private String change(String name, String val) {
        return "{\"name\":\"" + name + "\",\"op\":\"assign\",\"val\":" + val + "}";
    }

    private String obj(String kind, String type, String moid, String... changes) {
        return "{\"kind\":\"" + kind + "\",\"obj\":" + moref(type, moid) + ",\"changeSet\":["
                + String.join(",", changes) + "]}";
    }

    String fullUpdate() {
        List<String> objs = new ArrayList<>();
        List<String> hostRefs = new ArrayList<>();
        for (int h = 1; h <= hosts; h++) {
            hostRefs.add(moref("HostSystem", "host-" + h));
        }
        objs.add(obj("enter", "ClusterComputeResource", "domain-c1", change("name", s("c1")),
                change("host", boxed("ArrayOfManagedObjectReference", "[" + String.join(",",
                        hostRefs) + "]"))));
        for (int h = 1; h <= hosts; h++) {
            objs.add(obj("enter", "HostSystem", "host-" + h, change("name", s("esx" + h + ".lab")),
                    change("parent", moref("ClusterComputeResource", "domain-c1")),
                    change("configManager.vsanSystem", moref("HostVsanSystem", "vsys-" + h)),
                    change("config.vsanHostConfig.enabled", boxed("boolean", "true")),
                    change("config.vsanHostConfig.clusterInfo.uuid", s(clusterUuid())),
                    change("config.vsanHostConfig.clusterInfo.nodeUuid", s(node(h)))));
            for (int v = 1; v <= vmsPerHost; v++) {
                String vm = "vm-" + h + "-" + v;
                objs.add(obj("enter", "VirtualMachine", vm, change("name", s(vm)),
                        change("config.uuid", s("uuid-" + vm)),
                        change("config.instanceUuid", s("iuuid-" + vm)),
                        change("config.hardware.device", boxed("ArrayOfVirtualDevice",
                                "[{\"_typeName\":\"VirtualDisk\",\"key\":2000,\"backing\":{"
                                + "\"fileName\":\"[ds1] " + vm + "/" + vm + ".vmdk\"},"
                                + "\"deviceInfo\":{\"label\":\"Hard disk 1\"}}]")),
                        change("runtime.host", moref("HostSystem", "host-" + h))));
            }
        }
        return "{\"version\":\"" + updateVersion + "\",\"truncated\":false,\"filterSet\":["
                + "{\"objectSet\":[" + String.join(",", objs) + "]}]}";
    }

    private void vim(HttpExchange x, String tail) throws IOException {
        String b = x.getRequestMethod().equals("POST") ? body(x) : "";
        String tok = x.getRequestHeaders().getFirst("vmware-api-session-id");
        if (tail.equals("SessionManager/SessionManager/Login")) {
            logins.incrementAndGet();
            send(x, 200, "{\"_typeName\":\"UserSession\"}", "vmware-api-session-id",
                    "tok-" + logins.get());
            return;
        }
        if (tok == null || notAuthenticatedOnce) {
            notAuthenticatedOnce = false;
            send(x, 500, fault("NotAuthenticated", "k", "no session"));
            return;
        }
        if (tail.equals("ServiceInstance/ServiceInstance/content")) {
            send(x, 200, "{\"about\":{\"version\":\"" + version + "\",\"build\":\"1\","
                    + "\"fullName\":\"Fake vCenter\",\"instanceUuid\":\"" + vcid + "\"},"
                    + "\"rootFolder\":" + moref("Folder", "group-d1") + ",\"propertyCollector\":"
                    + moref("PropertyCollector", "propertyCollector") + ",\"viewManager\":"
                    + moref("ViewManager", "ViewManager") + "}");
        } else if (tail.endsWith("/CreatePropertyCollector")) {
            send(x, 200, moref("PropertyCollector", "pc" + creates.incrementAndGet()));
        } else if (tail.endsWith("/CreateContainerView")) {
            send(x, 200, moref("ContainerView", "view" + creates.incrementAndGet()));
        } else if (tail.endsWith("/CreateFilter") || tail.endsWith("/DestroyView")
                || tail.endsWith("/DestroyPropertyCollector") || tail.endsWith("/Logout")) {
            send(x, 200, "{}");
        } else if (tail.endsWith("/WaitForUpdatesEx")) {
            waits.incrementAndGet();
            Object req = JsonReaderTestAccess.parse(b);
            String version = JsonReader.str(req, "version");
            if (version == null || version.isEmpty()) {
                send(x, 200, fullUpdate());
            } else {
                send(x, 204, "");
            }
        } else if (tail.endsWith("/RetrievePropertiesEx")) {
            if (!b.contains("\"ch\"")) {
                send(x, 200, "{\"objects\":[]}");
                return;
            }
            List<String> hostRefs = new ArrayList<>();
            List<String> objs = new ArrayList<>();
            for (int h = 1; h <= hosts; h++) {
                hostRefs.add(moref("HostSystem", "host-" + h));
                objs.add("{\"obj\":" + moref("HostSystem", "host-" + h) + ",\"propSet\":["
                        + ps("name", s("esx" + h)) + "," + ps("config.vsanHostConfig.enabled",
                        boxed("boolean", "true")) + "," + ps("config.vsanHostConfig.clusterInfo.uuid",
                        s(clusterUuid())) + "," + ps("config.vsanHostConfig.clusterInfo.nodeUuid",
                        s(node(h))) + "]}");
            }
            objs.add(0, "{\"obj\":" + moref("ClusterComputeResource", "domain-c1") + ",\"propSet\":["
                    + ps("name", s("c1")) + "," + ps("host", boxed("ArrayOfManagedObjectReference",
                    "[" + String.join(",", hostRefs) + "]")) + "]}");
            send(x, 200, "{\"objects\":[" + String.join(",", objs) + "]}");
        } else if (tail.endsWith("/QueryDisksForVsan")) {
            send(x, 200, "[]");
        } else if (tail.contains("QueryVsanManagedDisks") || tail.contains("VsanQueryVcCluster")) {
            send(x, 500, fault("NotSupported", "k", "not supported"));
        } else if (tail.endsWith("/VsanPerfQueryPerf")) {
            perf(x, b);
        } else {
            send(x, 404, "{}");
        }
    }

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private void perf(HttpExchange x, String b) throws IOException {
        Object req = JsonReaderTestAccess.parse(b);
        List<?> specs = JsonReader.list(req, "querySpecs");
        if (specs.size() != 1) {
            send(x, 500, fault("InvalidArgument", "com.vmware.vsan.perfsvc.fault.queryperf."
                    + "wildcardlimit", "Only one wildcard query allowed in query specs."));
            return;
        }
        Object spec = specs.get(0);
        String ref = JsonReader.str(spec, "entityRefId");
        String type = ref.substring(0, ref.indexOf(':'));
        perfCalls.computeIfAbsent(type, k -> new AtomicInteger()).incrementAndGet();
        if (unsupported.contains(type)) {
            send(x, 500, fault("NotSupported", "k", "not supported"));
            return;
        }
        if (broken.contains(type)) {
            send(x, 500, fault("RuntimeFault", "k", "boom"));
            return;
        }
        long start = Instant.parse(JsonReader.str(spec, "startTime")).toEpochMilli();
        long end = Instant.parse(JsonReader.str(spec, "endTime")).toEpochMilli();
        List<String> info = new ArrayList<>();
        List<Long> buckets = new ArrayList<>();
        for (long t = (start / 300_000 + 1) * 300_000; t <= end; t += 300_000) {
            info.add(TS.format(Instant.ofEpochMilli(t)));
            buckets.add(t);
        }
        List<String> labels = new ArrayList<>();
        for (Object l : JsonReader.list(spec, "labels")) {
            labels.add((String) l);
        }
        if (labels.isEmpty()) {
            Collections.addAll(labels, MetricNames.of(type));
        }
        List<String> rows = new ArrayList<>();
        List<String> refs = present.get(type);
        if (refs == null) {
            rows.add("{\"entityRefId\":" + Json.quote(type + ":*") + ",\"sampleInfo\":\"\","
                    + "\"value\":[]}");
        } else {
            for (String r : refs) {
                StringBuilder vals = new StringBuilder();
                for (String l : labels) {
                    StringBuilder v = new StringBuilder();
                    for (int i = 0; i < info.size(); i++) {
                        long bk = buckets.get(i) / 300_000;
                        v.append(i == 0 ? "" : ",").append(bk % 2 == 1 ? "None" : String.valueOf(3 + bk % 100));
                    }
                    vals.append(vals.length() == 0 ? "" : ",").append("{\"metricId\":{\"label\":")
                            .append(Json.quote(l)).append("},\"values\":").append(Json.quote(
                            v.toString())).append('}');
                }
                rows.add("{\"entityRefId\":" + Json.quote(r) + ",\"sampleInfo\":"
                        + Json.quote(String.join(",", info)) + ",\"value\":[" + vals + "]}");
            }
        }
        send(x, 200, "[" + String.join(",", rows) + "]");
    }

    // ------------------------------------------------------------------
    // Suite API
    // ------------------------------------------------------------------

    private String resourceJson(String kind, String moid, String rid, String adapterKind) {
        return "{\"identifier\":\"" + rid + "\",\"resourceKey\":{\"adapterKindKey\":\""
                + adapterKind + "\",\"resourceKindKey\":\"" + kind + "\",\"resourceIdentifiers\":["
                + "{\"identifierType\":{\"name\":\"VMEntityObjectID\"},\"value\":\"" + moid + "\"},"
                + "{\"identifierType\":{\"name\":\"VMEntityVCID\"},\"value\":\"" + vcid + "\"}]}}";
    }

    private void suite(HttpExchange x, String p, String q) throws IOException {
        String body = x.getRequestMethod().equals("POST") ? body(x) : "";
        suitePaths.add(x.getRequestMethod() + " " + p);
        if (p.equals("api/auth/token/acquire")) {
            tokenAcquires.incrementAndGet();
            send(x, 200, "{\"token\":\"t" + tokenAcquires.get() + "\",\"validity\":"
                    + (System.currentTimeMillis() + 6 * 3600_000L) + "}");
            return;
        }
        if (x.getRequestHeaders().getFirst("Authorization") == null) {
            send(x, 401, "{}");
            return;
        }
        if (p.equals("api/resources") && x.getRequestMethod().equals("GET")) {
            Map<String, String> qs = new HashMap<>();
            for (String kv : q.split("&")) {
                String[] a = kv.split("=", 2);
                qs.put(a[0], a.length > 1 ? a[1] : "");
            }
            String kind = qs.get("resourceKind");
            int page = Integer.parseInt(qs.get("page"));
            int size = Integer.parseInt(qs.get("pageSize"));
            List<String> items = new ArrayList<>();
            for (Map.Entry<String, String> e : new java.util.TreeMap<>(ridByKey).entrySet()) {
                String[] k = e.getKey().split("\\|");
                if (k[0].equals(kind)) {
                    items.add(resourceJson(kind, k[1], e.getValue(), "VMWARE"));
                    // 8.18 ignores the adapterKind filter: foreign look-alikes come back too
                    items.add(resourceJson(kind, k[1], "other-" + e.getValue(), "OTHERKIND"));
                }
            }
            List<String> sl = items.subList(Math.min(items.size(), page * size),
                    Math.min(items.size(), (page + 1) * size));
            send(x, 200, "{\"pageInfo\":{\"totalCount\":" + items.size() + "},\"resourceList\":["
                    + String.join(",", sl) + "]}");
            return;
        }
        if (p.startsWith("api/resources/") && x.getRequestMethod().equals("GET")) {
            String rid = p.substring("api/resources/".length());
            send(x, ridByKey.containsValue(rid) ? 200 : 404, "{}");
            return;
        }
        boolean batchStats = p.equals("api/resources/stats");
        boolean batchProps = p.equals("api/resources/properties");
        if (x.getRequestMethod().equals("POST") && (batchStats || batchProps)) {
            (batchStats ? batchStatsPosts : batchPropsPosts).incrementAndGet();
            if (!batchStatuses.isEmpty()) {
                int st = batchStatuses.remove(0);
                if (st >= 300) {
                    send(x, st, "{}");
                    return;
                }
            }
            Object doc = JsonReaderTestAccess.parse(body);
            for (Object e : JsonReader.list(doc, batchStats ? "resource-stat-content" : "values")) {
                String rid = JsonReader.str(e, batchStats ? "id" : "resourceId");
                List<?> contents = batchStats ? JsonReader.list(e, "stat-contents")
                        : JsonReader.list(e, "property-contents", "property-content");
                land(rid, contents, batchStats);
            }
            send(x, 200, "");
            return;
        }
        if (x.getRequestMethod().equals("POST") && p.matches("api/resources/[^/]+/(stats|properties)")) {
            perResourcePosts.incrementAndGet();
            String[] a = p.split("/");
            Integer st = perResourceStatus.get(a[2]);
            if (st != null && st >= 300) {
                send(x, st, "{}");
                return;
            }
            Object doc = JsonReaderTestAccess.parse(body);
            boolean stats = a[3].equals("stats");
            land(a[2], JsonReader.list(doc, stats ? "stat-content" : "property-content"), stats);
            send(x, 200, "");
            return;
        }
        send(x, 404, "{}");
    }

    private void land(String rid, List<?> contents, boolean stats) {
        Map<String, Set<String>> m = stats ? statKeys : propKeys;
        for (Object c : contents) {
            m.computeIfAbsent(rid, k -> Collections.synchronizedSet(new TreeSet<>()))
                    .add(JsonReader.str(c, "statKey"));
        }
    }

    Set<String> allStatKeys() {
        Set<String> out = new TreeSet<>();
        statKeys.values().forEach(out::addAll);
        return out;
    }

    Set<String> allPropKeys() {
        Set<String> out = new TreeSet<>();
        propKeys.values().forEach(out::addAll);
        return out;
    }

    void forget() {
        statKeys.clear();
        propKeys.clear();
    }
}
