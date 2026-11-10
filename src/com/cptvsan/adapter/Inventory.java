package com.cptvsan.adapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The vCenter inventory the push needs (vSAN node uuid to host, VM uuids and
 * virtual-disk paths to VM, vSAN cluster uuid to cluster), as a mirror kept
 * current by WaitForUpdatesEx on a private PropertyCollector: after the first
 * read only CHANGED objects cross the wire. The Python pack's
 * {@code app/inventory.py} and the mirror half of {@code app/vcenter.py}.
 *
 * <p>Records are plain maps: cluster {@code name, hosts}; host {@code name,
 * parent, vsys, node, vsan, enabled}; VM {@code name, uuid, iuuid, disks, host}.
 */
final class Inventory {

    static final int UPDATE_PAGE = 1000;

    private final Map<String, Map<String, Object>> clusters = new TreeMap<>();
    private final Map<String, Map<String, Object>> hosts = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> vms = new LinkedHashMap<>();

    Map<String, Map<String, Object>> table(String kind) {
        switch (kind) {
            case "c": return clusters;
            case "h": return hosts;
            default: return vms;
        }
    }

    Map<String, Map<String, Object>> hosts() {
        return hosts;
    }

    Map<String, Map<String, Object>> vms() {
        return vms;
    }

    static String kindOfType(String type) {
        if ("ClusterComputeResource".equals(type)) {
            return "c";
        }
        if ("HostSystem".equals(type)) {
            return "h";
        }
        return "VirtualMachine".equals(type) ? "v" : null;
    }

    // ------------------------------------------------------------------
    // Values
    // ------------------------------------------------------------------

    /**
     * JSON API value to plain data: boxed primitives and arrays unboxed, a
     * MoRef to its moid, a virtual device to {type, key, fileName, label}.
     */
    static Object unbox(Object v) {
        if (v instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) v;
            Object tn = m.get("_typeName");
            String t = tn instanceof String ? (String) tn : "";
            if (t.equals("ManagedObjectReference")) {
                return m.get("value");
            }
            if (m.containsKey("_value")) {
                return unbox(m.get("_value"));
            }
            if (t.startsWith("Virtual") && m.containsKey("key")) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("type", t);
                d.put("key", m.get("key"));
                d.put("fileName", JsonReader.str(m, "backing", "fileName"));
                d.put("label", JsonReader.str(m, "deviceInfo", "label"));
                return d;
            }
            return v;
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object x : (List<?>) v) {
                out.add(unbox(x));
            }
            return out;
        }
        return v;
    }

    private static final Pattern DS_PREFIX = Pattern.compile("^\\[[^\\]]+\\]\\s*");

    /** {@code [datastore] path} to {@code path}. */
    static String stripDatastore(String f) {
        return DS_PREFIX.matcher(f).replaceFirst("");
    }

    /** One (unboxed) property value into the record. */
    static void applyChange(String kind, Map<String, Object> rec, String path, Object val) {
        if ("name".equals(path)) {
            rec.put("name", val);
        } else if ("c".equals(kind)) {
            if ("host".equals(path)) {
                List<String> hs = new ArrayList<>();
                if (val instanceof List) {
                    for (Object h : (List<?>) val) {
                        if (h instanceof String && !((String) h).isEmpty()) {
                            hs.add((String) h);
                        }
                    }
                }
                Collections.sort(hs);
                rec.put("hosts", hs);
            }
        } else if ("h".equals(kind)) {
            if ("parent".equals(path)) {
                rec.put("parent", val);
            } else if ("configManager.vsanSystem".equals(path)) {
                rec.put("vsys", val);
            } else if (path.endsWith("clusterInfo.nodeUuid")) {
                rec.put("node", val == null ? "" : String.valueOf(val));
            } else if (path.endsWith("clusterInfo.uuid")) {
                rec.put("vsan", val == null ? "" : String.valueOf(val));
            } else if (path.endsWith("vsanHostConfig.enabled")) {
                rec.put("enabled", Boolean.TRUE.equals(val) || "true".equals(val));
            }
        } else if ("v".equals(kind)) {
            if ("config.uuid".equals(path)) {
                rec.put("uuid", val == null ? "" : String.valueOf(val));
            } else if ("config.instanceUuid".equals(path)) {
                rec.put("iuuid", val == null ? "" : String.valueOf(val));
            } else if ("config.hardware.device".equals(path)) {
                List<String[]> disks = new ArrayList<>();
                if (val instanceof List) {
                    for (Object d : (List<?>) val) {
                        if (d instanceof Map && "VirtualDisk".equals(((Map<?, ?>) d).get("type"))) {
                            String f = JsonReader.str(d, "fileName");
                            if (f != null && !f.isEmpty()) {
                                String label = JsonReader.str(d, "label");
                                disks.add(new String[] {stripDatastore(f),
                                    label == null || label.isEmpty()
                                    ? "key" + JsonReader.str(d, "key") : label});
                            }
                        }
                    }
                }
                rec.put("disks", disks);
            } else if ("runtime.host".equals(path)) {
                rec.put("host", val);
            }
        }
    }

    /** Apply one UpdateSet's objectSet; returns the number of objects touched. */
    int applyUpdates(Object updateSet) {
        int touched = 0;
        for (Object fu : JsonReader.list(updateSet, "filterSet")) {
            for (Object ou : JsonReader.list(fu, "objectSet")) {
                String kind = kindOfType(JsonReader.str(ou, "obj", "type"));
                String moid = JsonReader.str(ou, "obj", "value");
                if (kind == null || moid == null) {
                    continue;
                }
                Map<String, Map<String, Object>> tbl = table(kind);
                touched++;
                String op = JsonReader.str(ou, "kind");
                if ("leave".equals(op)) {
                    tbl.remove(moid);
                    continue;
                }
                Map<String, Object> rec;
                if ("enter".equals(op)) {
                    rec = new LinkedHashMap<>();
                    tbl.put(moid, rec);
                } else {
                    rec = tbl.get(moid);
                    if (rec == null) {
                        rec = new LinkedHashMap<>();
                        tbl.put(moid, rec);
                    }
                }
                for (Object ch : JsonReader.list(ou, "changeSet")) {
                    String path = JsonReader.str(ch, "name");
                    Object val = "remove".equals(JsonReader.str(ch, "op")) ? null
                            : unbox(JsonReader.at(ch, "val"));
                    if (path != null) {
                        applyChange(kind, rec, path, val);
                    }
                }
            }
        }
        return touched;
    }

    // ------------------------------------------------------------------
    // Clusters
    // ------------------------------------------------------------------

    /** One vCenter cluster as the mirror knows it. */
    static final class ClusterRef {
        final String moid;
        final String name;
        final List<String> hosts;
        final String vsanUuid;

        ClusterRef(String moid, String name, List<String> hosts, String vsanUuid) {
            this.moid = moid;
            this.name = name;
            this.hosts = hosts;
            this.vsanUuid = vsanUuid;
        }
    }

    /**
     * A cluster's vSAN uuid: the clusterInfo.uuid of its vSAN-enabled hosts,
     * the majority when they disagree, ties to the first seen. Read from the
     * hosts because the cluster's own vSAN config faults InvalidProperty.
     */
    static String clusterVsanUuid(List<Map<String, Object>> hs) {
        Map<String, Integer> votes = new LinkedHashMap<>();
        for (Map<String, Object> h : hs) {
            Object u = h.get("vsan");
            if (Boolean.TRUE.equals(h.get("enabled")) && u instanceof String
                    && !((String) u).isEmpty()) {
                votes.merge((String) u, 1, Integer::sum);
            }
        }
        String best = "";
        int n = 0;
        for (Map.Entry<String, Integer> e : votes.entrySet()) {
            if (e.getValue() > n) {
                best = e.getKey();
                n = e.getValue();
            }
        }
        return best;
    }

    @SuppressWarnings("unchecked")
    List<ClusterRef> clusterRefs() {
        List<ClusterRef> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : clusters.entrySet()) {
            Object hs = e.getValue().get("hosts");
            List<String> members = hs instanceof List ? (List<String>) hs
                    : new ArrayList<String>();
            List<Map<String, Object>> recs = new ArrayList<>();
            for (String h : members) {
                Map<String, Object> r = hosts.get(h);
                recs.add(r == null ? new HashMap<String, Object>() : r);
            }
            Object nm = e.getValue().get("name");
            out.add(new ClusterRef(e.getKey(), nm instanceof String && !((String) nm).isEmpty()
                    ? (String) nm : e.getKey(), new ArrayList<>(members), clusterVsanUuid(recs)));
        }
        return out;
    }

    /**
     * The include and exclude lists: an entry matches a cluster NAME
     * (case-insensitive, shell wildcards) or its MoRef exactly. An empty
     * include list means every cluster; exclude wins.
     */
    static List<ClusterRef> filterClusters(List<ClusterRef> in, List<String> include,
            List<String> exclude) {
        List<ClusterRef> out = new ArrayList<>();
        for (ClusterRef c : in) {
            if ((include.isEmpty() || hit(c, include)) && !hit(c, exclude)) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean hit(ClusterRef c, List<String> pats) {
        for (String p : pats) {
            if (p.equals(c.moid) || glob(p.toLowerCase(java.util.Locale.ROOT))
                    .matcher(c.name.toLowerCase(java.util.Locale.ROOT)).matches()) {
                return true;
            }
        }
        return false;
    }

    /** fnmatch: {@code *}, {@code ?}, {@code [seq]}. */
    static Pattern glob(String g) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < g.length(); i++) {
            char c = g.charAt(i);
            if (c == '*') {
                b.append(".*");
            } else if (c == '?') {
                b.append('.');
            } else if (c == '[') {
                int j = g.indexOf(']', i + 2);
                if (j < 0) {
                    b.append("\\[");
                } else {
                    String body = g.substring(i + 1, j);
                    if (body.startsWith("!")) {
                        body = "^" + body.substring(1);
                    }
                    b.append('[').append(body.replace("\\", "\\\\")).append(']');
                    i = j;
                }
            } else {
                b.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(b.toString(), Pattern.DOTALL);
    }

    // ------------------------------------------------------------------
    // Join
    // ------------------------------------------------------------------

    /** The maps the plan needs, for the chosen clusters. */
    static final class Joined {
        /** bucket name (clusters, hosts, vms, vdisks, disks) to identity-to-MoRef. */
        final Map<String, Map<String, String>> parents = new HashMap<>();
        /** Identity (or MoRef) to a readable name. */
        final Map<String, String> names = new HashMap<>();
        /** Target kind to the MoRefs in the chosen clusters. */
        final Map<String, Set<String>> morefs = new LinkedHashMap<>();

        Joined() {
            for (String b : new String[] {"clusters", "hosts", "vms", "vdisks", "disks"}) {
                parents.put(b, new HashMap<String, String>());
            }
        }
    }

    @SuppressWarnings("unchecked")
    Joined join(List<ClusterRef> chosen) {
        Joined j = new Joined();
        Set<String> inScope = new HashSet<>();
        Set<String> clusterMorefs = new HashSet<>();
        for (ClusterRef cl : chosen) {
            clusterMorefs.add(cl.moid);
            j.names.put(cl.name, cl.name);
            if (!cl.vsanUuid.isEmpty()) {
                j.parents.get("clusters").put(cl.vsanUuid, cl.moid);
                j.names.put(cl.vsanUuid, cl.name);
            }
            inScope.addAll(cl.hosts);
        }
        for (String moid : inScope) {
            Map<String, Object> rec = hosts.get(moid);
            Object nm = rec == null ? null : rec.get("name");
            String name = nm instanceof String && !((String) nm).isEmpty() ? (String) nm : moid;
            Object node = rec == null ? null : rec.get("node");
            if (node instanceof String && !((String) node).isEmpty()) {
                j.parents.get("hosts").put((String) node, moid);
                j.names.put((String) node, name);
            }
        }
        Set<String> vmScope = new HashSet<>();
        for (Map.Entry<String, Map<String, Object>> e : vms.entrySet()) {
            Map<String, Object> rec = e.getValue();
            Object host = rec.get("host");
            if (!(host instanceof String) || !inScope.contains(host)) {
                continue;
            }
            vmScope.add(e.getKey());
            Object nm = rec.get("name");
            String name = nm instanceof String && !((String) nm).isEmpty() ? (String) nm
                    : e.getKey();
            for (String attr : new String[] {"uuid", "iuuid"}) { // instanceUuid last: it wins
                Object u = rec.get(attr);
                if (u instanceof String && !((String) u).isEmpty()) {
                    j.parents.get("vms").put((String) u, e.getKey());
                    j.names.put((String) u, name);
                }
            }
            Object ds = rec.get("disks");
            if (ds instanceof List) {
                for (Object d : (List<?>) ds) {
                    String[] pl = (String[]) d;
                    String slash = "/" + pl[0].replaceFirst("^/+", "");
                    for (String form : new String[] {pl[0], slash}) {
                        j.parents.get("vdisks").put(form, e.getKey());
                        j.names.put(form, name + " [" + pl[1] + "]");
                    }
                }
            }
        }
        j.morefs.put("ClusterComputeResource", clusterMorefs);
        j.morefs.put("HostSystem", inScope);
        j.morefs.put("VirtualMachine", vmScope);
        return j;
    }

    /** {moid, name, HostVsanSystem moid or null} per host of the cluster, for the disk calls. */
    List<String[]> hostRecords(ClusterRef cl) {
        List<String[]> out = new ArrayList<>();
        for (String h : cl.hosts) {
            Map<String, Object> r = hosts.get(h);
            Object nm = r == null ? null : r.get("name");
            Object vs = r == null ? null : r.get("vsys");
            out.add(new String[] {h, nm instanceof String && !((String) nm).isEmpty()
                ? (String) nm : h, vs instanceof String ? (String) vs : null});
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Tracker
    // ------------------------------------------------------------------

    /** What one sync did. */
    static final class SyncStats {
        boolean full;
        String reason = "";
        int objects;
    }

    private String pc;
    private String version = "";
    private long fullAtMs;
    private int sessionGeneration = -1;
    private boolean primed;

    /**
     * Bring the mirror up to date: an incremental read, or a full one on the
     * first run, after a new vCenter session (the old collector died with the
     * old session), when the incremental call faults, and every
     * {@code fullRefreshMin} minutes as a safety net.
     */
    SyncStats sync(VcClient vc, long nowMs, double fullRefreshMin) throws IOException {
        SyncStats st = new SyncStats();
        String reason = "";
        if (!primed || pc == null) {
            reason = "first run";
        } else if (sessionGeneration != vc.generation.get()) {
            reason = "new vCenter session";
        } else if (nowMs - fullAtMs >= fullRefreshMin * 60_000) {
            reason = "periodic safety refresh";
        }
        if (reason.isEmpty()) {
            try {
                st.objects = drain(vc, pc, version);
            } catch (IOException e) {
                reason = "incremental read failed (" + e.getClass().getSimpleName() + ")";
            }
        }
        if (!reason.isEmpty()) {
            st.full = true;
            st.reason = reason;
            if (pc != null && sessionGeneration == vc.generation.get()) {
                try {
                    vc.pcDestroy(pc);
                } catch (IOException e) {
                    // best effort; its filter goes with it
                }
            }
            clusters.clear();
            hosts.clear();
            vms.clear();
            primed = false;
            String[] created = vc.pcCreate();
            pc = created[0];
            sessionGeneration = vc.generation.get();
            version = "";
            fullAtMs = nowMs;
            st.objects = drain(vc, pc, "");
            primed = true;
        }
        return st;
    }

    private int drain(VcClient vc, String collector, String from) throws IOException {
        int touched = 0;
        String v = from;
        while (true) {
            Object us = vc.pcWait(collector, v, UPDATE_PAGE);
            if (us == null) {
                break;
            }
            touched += applyUpdates(us);
            String nv = JsonReader.str(us, "version");
            if (nv != null) {
                v = nv;
            }
            boolean truncated = Boolean.TRUE.equals(JsonReader.at(us, "truncated"));
            if (!truncated) {
                break;
            }
        }
        version = v;
        return touched;
    }

    /** Forget everything (another vCenter, or a changed configuration). */
    void reset() {
        clusters.clear();
        hosts.clear();
        vms.clear();
        pc = null;
        primed = false;
        version = "";
    }
}
