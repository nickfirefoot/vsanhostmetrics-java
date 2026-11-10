package com.cptvsan.adapter;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * vCenter over the vSphere JSON API, the only transport (vCenter 8.0 Update 3
 * or later, or 9.0 or later). One session per client, reused across cycles
 * and renewed on {@code NotAuthenticated}; the session id goes in
 * {@code vmware-api-session-id}. The Python pack's {@code app/vcenter.py}.
 *
 * <p>Ids of objects a session creates (PropertyCollector, ContainerView) are
 * {@code session[<uuid>]<uuid>} and are sent with the brackets RAW: vCenter
 * does not decode {@code %5B}/{@code %5D} ({@link #moSeg}).
 *
 * <p>Third-party notice: the request and response shapes follow Scott Bowe's
 * vcf-content-factory-sdk-vsanperf (MIT), as the Python pack's do; see NOTICE.
 */
final class VcClient {

    static final String PINNED_RELEASE = "8.0.3.0";
    static final String SESSION_HEADER = "vmware-api-session-id";
    static final String FLOOR_TEXT = "vCenter 8.0 Update 3 or later is required (or 9.0 or later)";

    static final String[] CLUSTER_PATHS = {"name", "host"};
    static final String[] HOST_PATHS = {"name", "parent", "configManager.vsanSystem",
        "config.vsanHostConfig.enabled", "config.vsanHostConfig.clusterInfo.uuid",
        "config.vsanHostConfig.clusterInfo.nodeUuid"};
    static final String[] VM_PATHS = {"name", "config.uuid", "config.instanceUuid",
        "config.hardware.device", "runtime.host"};

    private final String base;
    private final String user;
    private final String password;
    private final Net.Opener opener;
    private final boolean gzip;
    private final int timeoutMs;
    private final Object loginLock = new Object();

    private volatile String release = PINNED_RELEASE;
    private volatile String token;
    private volatile Map<String, Object> content = Collections.emptyMap();

    /** Increases on every login: state tied to a session (a PropertyCollector) dies with it. */
    final AtomicInteger generation = new AtomicInteger();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicLong wireBytes = new AtomicLong();

    VcClient(String base, String user, String password, Net.Opener opener, boolean gzip,
            int timeoutMs) {
        this.base = base;
        this.user = user;
        this.password = password;
        this.opener = opener;
        this.gzip = gzip;
        this.timeoutMs = timeoutMs;
    }

    // ------------------------------------------------------------------
    // Paths
    // ------------------------------------------------------------------

    /** A managed-object id as a path segment: percent-encoded, brackets left raw. */
    static String moSeg(String moid) {
        StringBuilder b = new StringBuilder();
        for (byte x : moid.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '~' || c == '[' || c == ']') {
                b.append((char) c);
            } else {
                b.append('%').append(String.format("%02X", c));
            }
        }
        return b.toString();
    }

    static String moref(String type, String value) {
        return "{\"_typeName\":\"ManagedObjectReference\",\"type\":" + Json.quote(type)
                + ",\"value\":" + Json.quote(value) + "}";
    }

    private String url(String tail) {
        return base + "/sdk/vim25/" + release + "/" + tail;
    }

    // ------------------------------------------------------------------
    // Transport
    // ------------------------------------------------------------------

    private Net.Resp raw(String method, String url, String body, String tok) throws IOException {
        calls.incrementAndGet();
        List<String[]> h = tok == null ? Collections.<String[]>emptyList()
                : Net.headers(SESSION_HEADER, tok);
        Net.Resp r = Net.call(opener, method, url, body, h, gzip, timeoutMs);
        wireBytes.addAndGet(r.wire);
        return r;
    }

    static VsanFault fault(Net.Resp r) {
        VsanFault f = VsanFault.parse(r.text(), r.status);
        return f != null ? f : new VsanFault("HTTP", "HTTP " + r.status,
                Collections.<String>emptyList(), r.status);
    }

    /** A successful call's response (relogin once on NotAuthenticated); faults throw. */
    Net.Resp call(String method, String tail, String body) throws IOException {
        return call(method, tail, body, true);
    }

    Net.Resp call(String method, String tail, String body, boolean relogin) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            String used = token;
            Net.Resp r = raw(method, url(tail), body, used);
            if (r.status == 200 || r.status == 204) {
                return r;
            }
            VsanFault f = fault(r);
            if (f.is(VsanFault.NOT_AUTHENTICATED) && relogin && attempt == 0) {
                synchronized (loginLock) {
                    if (token == null ? used == null : token.equals(used)) {
                        login();
                    }
                }
                continue;
            }
            throw f;
        }
        throw new VsanFault("NotAuthenticated", "refused right after a fresh login",
                Collections.<String>emptyList(), 401);
    }

    /** The parsed JSON of a call; {@code null} for an empty or {@code null} body. */
    Object callTree(String method, String tail, String body) throws IOException {
        Net.Resp r = call(method, tail, body);
        String t = r.text().trim();
        return t.isEmpty() || t.equals("null") ? null : JsonReader.parse(t);
    }

    void login() throws IOException {
        String body = "{\"userName\":" + Json.quote(user) + ",\"password\":" + Json.quote(password)
                + "}";
        Net.Resp r = raw("POST", url("SessionManager/SessionManager/Login"), body, null);
        if (r.status != 200) {
            VsanFault f = fault(r);
            if (f.is(VsanFault.INVALID_REQUEST) && PINNED_RELEASE.equals(release)) {
                String own = serverRelease();
                if (own != null && !own.equals(release)) {
                    release = own;
                    login();
                    return;
                }
            }
            if ("HTTP".equals(f.faultClass) && (r.status == 400 || r.status == 404
                    || r.status == 405)) {
                throw new VsanFault("NoJsonApi", "Login returned HTTP " + r.status
                        + ": this vCenter does not serve the vSphere JSON API. " + FLOOR_TEXT + ".",
                        Collections.<String>emptyList(), r.status);
            }
            throw f;
        }
        String t = r.header(SESSION_HEADER);
        if (t == null || t.isEmpty()) {
            throw new VsanFault("NoJsonApi", "Login answered without a " + SESSION_HEADER
                    + " header. " + FLOOR_TEXT + ".", Collections.<String>emptyList(), 200);
        }
        token = t;
        generation.incrementAndGet();
    }

    private String serverRelease() throws IOException {
        Net.Resp r = raw("GET", base + "/sdk/vimServiceVersions.xml", null, null);
        Matcher m = Pattern.compile(
                "<name>\\s*urn:vim25\\s*</name>\\s*<version>\\s*([0-9.]+)\\s*</version>")
                .matcher(r.text());
        return m.find() ? m.group(1) : null;
    }

    /** Log in and read the service content. */
    @SuppressWarnings("unchecked")
    void open() throws IOException {
        login();
        Object c = callTree("GET", "ServiceInstance/ServiceInstance/content", null);
        content = c instanceof Map ? (Map<String, Object>) c : Collections.<String, Object>emptyMap();
    }

    void logout() {
        try {
            call("POST", "SessionManager/SessionManager/Logout", "{}", false);
        } catch (IOException e) {
            // the session expires on its own
        }
        token = null;
    }

    // ------------------------------------------------------------------
    // Identity
    // ------------------------------------------------------------------

    String about(String field) {
        String v = JsonReader.str(content, "about", field);
        return v == null ? "" : v;
    }

    String instanceUuid() {
        return about("instanceUuid");
    }

    private String mo(String key) {
        String v = JsonReader.str(content, key, "value");
        return v == null ? "" : moSeg(v);
    }

    /** about.version against the floor: 8.0.3+ or 9+. */
    static boolean versionOk(String version) {
        int[] n = {0, 0, 0};
        Matcher m = Pattern.compile("\\d+").matcher(version == null ? "" : version);
        for (int i = 0; i < 3 && m.find(); i++) {
            n[i] = Integer.parseInt(m.group());
        }
        return n[0] >= 9 || (n[0] == 8 && (n[1] > 0 || (n[1] == 0 && n[2] >= 3)));
    }

    void checkVersion() throws IOException {
        String v = about("version");
        if (!versionOk(v)) {
            String full = about("fullName");
            throw new IOException((full.isEmpty() ? "This vCenter" : full) + " (version "
                    + (v.isEmpty() ? "?" : v) + ", build " + (about("build").isEmpty() ? "?"
                    : about("build")) + ") is not supported: " + FLOOR_TEXT + ".");
        }
    }

    // ------------------------------------------------------------------
    // Inventory
    // ------------------------------------------------------------------

    private String view(String... types) throws IOException {
        StringBuilder t = new StringBuilder("[");
        for (int i = 0; i < types.length; i++) {
            t.append(i == 0 ? "" : ",").append(Json.quote(types[i]));
        }
        t.append(']');
        Object v = callTree("POST", "ViewManager/" + mo("viewManager") + "/CreateContainerView",
                "{\"container\":" + moref("Folder", JsonReader.str(content, "rootFolder", "value"))
                + ",\"type\":" + t + ",\"recursive\":true}");
        return JsonReader.str(v, "value");
    }

    private static String propSet(String type, String[] paths) {
        StringBuilder b = new StringBuilder("{\"_typeName\":\"PropertySpec\",\"type\":")
                .append(Json.quote(type)).append(",\"pathSet\":[");
        for (int i = 0; i < paths.length; i++) {
            b.append(i == 0 ? "" : ",").append(Json.quote(paths[i]));
        }
        return b.append("]}").toString();
    }

    private static String filterSpec(String view, String propSets, String innerTraversal) {
        return "{\"_typeName\":\"PropertyFilterSpec\",\"propSet\":[" + propSets
                + "],\"objectSet\":[{\"_typeName\":\"ObjectSpec\",\"obj\":"
                + moref("ContainerView", view) + ",\"skip\":true,\"selectSet\":["
                + "{\"_typeName\":\"TraversalSpec\",\"name\":\"v\",\"type\":\"ContainerView\","
                + "\"path\":\"view\",\"skip\":false" + innerTraversal + "}]}]}";
    }

    /** A private PropertyCollector with one filter over clusters, hosts and VMs: {pc, view}. */
    String[] pcCreate() throws IOException {
        Object pc = callTree("POST", "PropertyCollector/" + mo("propertyCollector")
                + "/CreatePropertyCollector", "{}");
        String pcId = JsonReader.str(pc, "value");
        String view = view("ClusterComputeResource", "HostSystem", "VirtualMachine");
        String spec = filterSpec(view,
                propSet("ClusterComputeResource", CLUSTER_PATHS) + ","
                + propSet("HostSystem", HOST_PATHS) + "," + propSet("VirtualMachine", VM_PATHS), "");
        call("POST", "PropertyCollector/" + moSeg(pcId) + "/CreateFilter",
                "{\"spec\":" + spec + ",\"partialUpdates\":false}");
        return new String[] {pcId, view};
    }

    void pcDestroy(String pc) throws IOException {
        call("POST", "PropertyCollector/" + moSeg(pc) + "/DestroyPropertyCollector", "{}");
    }

    /** One WaitForUpdatesEx page, or {@code null} when nothing changed since {@code version}. */
    Object pcWait(String pc, String version, int page) throws IOException {
        return callTree("POST", "PropertyCollector/" + moSeg(pc) + "/WaitForUpdatesEx",
                "{\"version\":" + Json.quote(version) + ",\"options\":{\"_typeName\":"
                + "\"WaitOptions\",\"maxWaitSeconds\":0,\"maxObjectUpdates\":" + page + "}}");
    }

    /** Clusters and their hosts, one RetrievePropertiesEx over a throwaway view (Test Connection). */
    Inventory listClusters() throws IOException {
        String v = view("ClusterComputeResource");
        try {
            String spec = filterSpec(v, propSet("ClusterComputeResource", CLUSTER_PATHS) + ","
                    + propSet("HostSystem", HOST_PATHS),
                    ",\"selectSet\":[{\"_typeName\":\"TraversalSpec\",\"name\":\"ch\","
                    + "\"type\":\"ComputeResource\",\"path\":\"host\",\"skip\":false}]");
            Object res = callTree("POST", "PropertyCollector/" + mo("propertyCollector")
                    + "/RetrievePropertiesEx", "{\"specSet\":[" + spec
                    + "],\"options\":{\"_typeName\":\"RetrieveOptions\"}}");
            Inventory inv = new Inventory();
            for (Object oc : JsonReader.list(res, "objects")) {
                String type = JsonReader.str(oc, "obj", "type");
                String moid = JsonReader.str(oc, "obj", "value");
                String kind = Inventory.kindOfType(type);
                if (kind == null || moid == null || "v".equals(kind)) {
                    continue;
                }
                Map<String, Object> rec = new LinkedHashMap<>();
                for (Object p : JsonReader.list(oc, "propSet")) {
                    Inventory.applyChange(kind, rec, JsonReader.str(p, "name"),
                            Inventory.unbox(JsonReader.at(p, "val")));
                }
                inv.table(kind).put(moid, rec);
            }
            return inv;
        } finally {
            try {
                call("POST", "ContainerView/" + moSeg(v) + "/DestroyView", "{}");
            } catch (IOException e) {
                // best effort
            }
        }
    }

    // ------------------------------------------------------------------
    // Disks (best effort callers)
    // ------------------------------------------------------------------

    /** QueryDisksForVsan: one map per claimed disk: uuid, key, revision, model, vendor. */
    List<Map<String, String>> disksForVsan(String vsys) throws IOException {
        Object r = Inventory.unbox(callTree("POST", "HostVsanSystem/" + moSeg(vsys)
                + "/QueryDisksForVsan", "{}"));
        List<Map<String, String>> out = new ArrayList<>();
        if (r instanceof List) {
            for (Object e : (List<?>) r) {
                Object d = JsonReader.at(e, "disk");
                Map<String, String> m = new LinkedHashMap<>();
                String u = JsonReader.str(e, "vsanUuid");
                m.put("uuid", u == null ? "" : u.trim());
                m.put("key", nz(JsonReader.str(d, "key")));
                m.put("revision", nz(JsonReader.str(d, "revision")));
                m.put("model", nz(JsonReader.str(d, "model")));
                m.put("vendor", nz(JsonReader.str(d, "vendor")));
                out.add(m);
            }
        }
        return out;
    }

    /** QueryVsanManagedDisks (ESA storage pools): {vsanUuid, disk map} pairs. */
    List<Object[]> managedDisks(String host) throws IOException {
        Object info = callTree("POST", "vsan/VsanVcDiskManagementSystem/"
                + "vsan-disk-management-system/QueryVsanManagedDisks",
                "{\"host\":" + moref("HostSystem", host) + "}");
        List<Object[]> out = new ArrayList<>();
        Object pools = Inventory.unbox(JsonReader.at(info, "storagePools"));
        if (pools instanceof List) {
            for (Object p : (List<?>) pools) {
                Object disks = Inventory.unbox(JsonReader.at(p, "storagePoolDisks"));
                if (disks instanceof List) {
                    for (Object e : (List<?>) disks) {
                        out.add(new Object[] {JsonReader.str(e, "vsanUuid"),
                            JsonReader.at(e, "disk")});
                    }
                }
            }
        }
        return out;
    }

    /** hostBusAdapter and scsiTopology of the hosts: moid to {path: value}. */
    Map<String, Map<String, Object>> hostStorage(List<String> hosts) throws IOException {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (hosts.isEmpty()) {
            return out;
        }
        StringBuilder objs = new StringBuilder();
        for (String h : hosts) {
            objs.append(objs.length() == 0 ? "" : ",")
                    .append("{\"_typeName\":\"ObjectSpec\",\"obj\":")
                    .append(moref("HostSystem", h)).append('}');
        }
        Object res = callTree("POST", "PropertyCollector/" + mo("propertyCollector")
                + "/RetrievePropertiesEx", "{\"specSet\":[{\"_typeName\":\"PropertyFilterSpec\","
                + "\"propSet\":[" + propSet("HostSystem", new String[] {
                    "config.storageDevice.hostBusAdapter", "config.storageDevice.scsiTopology"})
                + "],\"objectSet\":[" + objs + "]}],\"options\":{\"_typeName\":"
                + "\"RetrieveOptions\"}}");
        for (Object oc : JsonReader.list(res, "objects")) {
            Map<String, Object> props = new LinkedHashMap<>();
            for (Object p : JsonReader.list(oc, "propSet")) {
                props.put(JsonReader.str(p, "name"), Inventory.unbox(JsonReader.at(p, "val")));
            }
            out.put(JsonReader.str(oc, "obj", "value"), props);
        }
        return out;
    }

    /** Controller device name to driver version, from the HCL part of the cluster health summary. */
    Map<String, String> hclVersions(String cluster) throws IOException {
        Object s = callTree("POST", "vsan/VsanVcClusterHealthSystem/"
                + "vsan-cluster-health-system/VsanQueryVcClusterHealthSummary",
                "{\"cluster\":" + moref("ClusterComputeResource", cluster)
                + ",\"includeObjUuids\":false,\"fields\":[\"hclInfo\"]}");
        Map<String, String> out = new LinkedHashMap<>();
        Object hrs = Inventory.unbox(JsonReader.at(s, "hclInfo", "hostResults"));
        if (hrs instanceof List) {
            for (Object hr : (List<?>) hrs) {
                Object ctrls = Inventory.unbox(JsonReader.at(hr, "controllers"));
                if (ctrls instanceof List) {
                    for (Object c : (List<?>) ctrls) {
                        String dn = JsonReader.str(c, "deviceName");
                        String dv = JsonReader.str(c, "driverVersion");
                        if (dn != null && !dn.isEmpty() && dv != null && !dv.isEmpty()) {
                            out.put(dn, dv);
                        }
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Performance
    // ------------------------------------------------------------------

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    static String utc(long ms) {
        return ISO.format(Instant.ofEpochMilli(ms));
    }

    /** Receives one entity result: its ref, its sampleInfo and (label, values) pairs. */
    interface PerfSink {
        void add(String ref, String sampleInfo, List<String[]> pairs);
    }

    /** The request body of one VsanPerfQueryPerf, one wildcard spec. */
    static String perfBody(String cluster, String entity, long startMs, long endMs,
            String[] labels) {
        StringBuilder spec = new StringBuilder("{\"_typeName\":\"VsanPerfQuerySpec\","
                + "\"entityRefId\":").append(Json.quote(entity + ":*")).append(",\"startTime\":")
                .append(Json.quote(utc(startMs))).append(",\"endTime\":")
                .append(Json.quote(utc(endMs)));
        if (labels != null && labels.length > 0) {
            spec.append(",\"labels\":[");
            for (int i = 0; i < labels.length; i++) {
                spec.append(i == 0 ? "" : ",").append(Json.quote(labels[i]));
            }
            spec.append(']');
        }
        spec.append('}');
        return "{\"querySpecs\":[" + spec + "],\"cluster\":"
                + moref("ClusterComputeResource", cluster) + "}";
    }

    /**
     * One VsanPerfQueryPerf for one entity type; every entity result goes to
     * {@code sink}. Returns the response bytes on the wire.
     *
     * @throws VsanFault a fault (see {@link VsanFault#isUnsupported})
     */
    int queryPerf(String cluster, String entity, long startMs, long endMs, String[] labels,
            PerfSink sink) throws IOException {
        Net.Resp r = call("POST", "vsan/VsanPerformanceManager/vsan-performance-manager/"
                + "VsanPerfQueryPerf", perfBody(cluster, entity, startMs, endMs, labels));
        JsonReader jr = new JsonReader(new StringReader(r.text()));
        if (jr.peek() == JsonReader.Token.NULL || jr.peek() == JsonReader.Token.END) {
            return r.wire;
        }
        if (jr.peek() != JsonReader.Token.BEGIN_ARRAY) {
            throw new Unreadable("VsanPerfQueryPerf: the response is not an array");
        }
        jr.beginArray();
        while (jr.hasNext()) {
            Object ent = jr.readValue();
            List<String[]> pairs = new ArrayList<>();
            for (Object v : JsonReader.list(ent, "value")) {
                String label = JsonReader.str(v, "metricId", "label");
                String values = JsonReader.str(v, "values");
                pairs.add(new String[] {label, values});
            }
            String ref = JsonReader.str(ent, "entityRefId");
            String info = JsonReader.str(ent, "sampleInfo");
            sink.add(ref == null ? "" : ref, info == null ? "" : info, pairs);
        }
        jr.endArray();
        return r.wire;
    }

    /**
     * One query for Test Connection: proves the session, the cluster's
     * visibility and a Performance Service that answers. Returns null on
     * success (an unsupported type still proves the service is there), else
     * the problem text.
     */
    String probe(String cluster, String entity, long nowMs) {
        try {
            queryPerf(cluster, entity, nowMs - 15 * 60_000L, nowMs, null,
                    (ref, info, pairs) -> { });
            return null;
        } catch (VsanFault f) {
            return f.isUnsupported() ? null : f.getMessage();
        } catch (IOException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
