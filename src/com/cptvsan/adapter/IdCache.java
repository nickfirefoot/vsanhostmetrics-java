package com.cptvsan.adapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VMWARE resource ids by (kind, MoRef) for ONE vCenter (VMEntityVCID). The
 * Python pack's {@code push.IdCache}.
 *
 * <p>Matches on BOTH identifier values, VMEntityObjectID AND VMEntityVCID,
 * because MoRefs repeat across vCenters. The listing is filtered by adapter
 * kind and resource kind CLIENT-side: 8.18 ignores the server filter. A
 * (MoRef, vCenter) pair held by two resources is ambiguous and dropped, so
 * nothing is ever pushed onto a guess.
 *
 * <ul>
 *   <li>a full listing of a kind when its entry is older than the TTL (30
 *       minutes), or after a 404 invalidated part of it;</li>
 *   <li>a MoRef seen in vCenter but absent from the cache triggers ONE delta
 *       listing per kind per cycle (recentlyAdded);</li>
 *   <li>a MoRef still absent goes into a negative cache for 10 minutes;</li>
 *   <li>{@link #invalidate} on a 404 removes the entry and schedules a full
 *       listing next cycle.</li>
 * </ul>
 */
final class IdCache {

    static final String VC_ADAPTER = "VMWARE";
    static final String MOREF_ID = "VMEntityObjectID";
    static final String VCID_ID = "VMEntityVCID";
    static final int PAGE_SIZE = 1000;
    static final long DELTA_MARGIN_S = 600;

    /** Counters of one cycle. */
    static final class Stats {
        int fullListings;
        int deltaListings;
        int httpCalls;
        int invalidated;
    }

    private final String vcid;
    private final double ttlS;
    private final double negTtlS;
    private final int pageSize;
    private double now;
    private final Map<String, Double> built = new HashMap<>();
    private final Map<String, Double> listed = new HashMap<>();
    private final Map<String, Map<String, String>> map = new HashMap<>();
    private final Map<String, Map<String, Double>> neg = new HashMap<>();
    private final Set<String> deltaDone = new HashSet<>();
    private Map<String, String[]> rev;
    Stats stats = new Stats();

    IdCache(String vcid, double ttlS, double negTtlS, int pageSize) {
        this.vcid = vcid;
        this.ttlS = ttlS;
        this.negTtlS = negTtlS;
        this.pageSize = pageSize;
    }

    String vcid() {
        return vcid;
    }

    /** Start a cycle at {@code nowS} (epoch seconds): counters and the one-delta-per-kind rule reset. */
    synchronized void beginCycle(double nowS) {
        now = nowS;
        deltaDone.clear();
        stats = new Stats();
    }

    /** (MoRef, vCenter uuid) pairs to resource ids of one VMWARE kind; the ambiguous are dropped. */
    static Map<String, String> index(SuiteApi api, String kind, int pageSize,
            String recentlyAdded, Stats stats, Log log) throws IOException {
        Map<String, String> out = new HashMap<>(); // key: moid + "\u0000" + vcid
        Set<String> dupes = new HashSet<>();
        int page = 0;
        int seen = 0;
        while (true) {
            String path = "api/resources?adapterKind=" + VC_ADAPTER + "&resourceKind=" + kind
                    + "&page=" + page + "&pageSize=" + pageSize
                    + (recentlyAdded == null ? "" : "&recentlyAdded=" + recentlyAdded);
            if (stats != null) {
                stats.httpCalls++;
            }
            Net.Resp r = api.get(path);
            if (r.status >= 300) {
                throw new SuiteApiException("listing " + VC_ADAPTER + "/" + kind + " page "
                        + page + ": HTTP " + r.status);
            }
            Object body = JsonReader.parse(r.text());
            List<?> found = JsonReader.list(body, "resourceList");
            long total = 0;
            String tc = JsonReader.str(body, "pageInfo", "totalCount");
            if (tc != null) {
                try {
                    total = Long.parseLong(tc);
                } catch (NumberFormatException e) {
                    total = 0;
                }
            }
            for (Object res : found) {
                String ak = JsonReader.str(res, "resourceKey", "adapterKindKey");
                String rk = JsonReader.str(res, "resourceKey", "resourceKindKey");
                if ((ak != null && !ak.equals(VC_ADAPTER)) || (rk != null && !rk.equals(kind))) {
                    continue;
                }
                String moid = null;
                String vc = null;
                for (Object i : JsonReader.list(res, "resourceKey", "resourceIdentifiers")) {
                    String n = JsonReader.str(i, "identifierType", "name");
                    String v = JsonReader.str(i, "value");
                    if (MOREF_ID.equals(n)) {
                        moid = v;
                    } else if (VCID_ID.equals(n)) {
                        vc = v;
                    }
                }
                String rid = JsonReader.str(res, "identifier");
                if (moid == null || moid.isEmpty() || vc == null || vc.isEmpty() || rid == null
                        || rid.isEmpty()) {
                    continue;
                }
                String k = moid + "\u0000" + vc;
                String prev = out.get(k);
                if (prev != null && !prev.equals(rid)) {
                    dupes.add(k);
                }
                out.put(k, rid);
            }
            seen += found.size();
            page++;
            if (found.isEmpty() || seen >= total || found.size() < pageSize) {
                break;
            }
        }
        for (String d : dupes) {
            if (log != null) {
                log.warn(kind + " " + d.replace('\u0000', ' ') + " matches more than one"
                        + " Operations resource; not pushing to it");
            }
            out.remove(d);
        }
        return out;
    }

    private void admit(String kind, Map<String, String> found, boolean full) {
        Map<String, String> mine = new HashMap<>();
        for (Map.Entry<String, String> e : found.entrySet()) {
            int z = e.getKey().indexOf('\u0000');
            if (e.getKey().substring(z + 1).equals(vcid)) {
                mine.put(e.getKey().substring(0, z), e.getValue());
            }
        }
        if (full) {
            map.put(kind, mine);
        } else {
            Map<String, String> cur = map.get(kind);
            if (cur == null) {
                cur = new HashMap<>();
                map.put(kind, cur);
            }
            cur.putAll(mine);
        }
        Map<String, Double> n = neg.get(kind);
        if (n != null) {
            for (String m : mine.keySet()) {
                n.remove(m);
            }
        }
        rev = null;
    }

    /** Full listing of every kind whose cache is older than the TTL. */
    synchronized void refreshExpired(SuiteApi api, Log log) throws IOException {
        for (TargetKind k : TargetKind.values()) {
            String kind = k.resourceKind;
            Double b = built.get(kind);
            if (b != null && now - b < ttlS && map.containsKey(kind)) {
                continue;
            }
            Map<String, String> found = index(api, kind, pageSize, null, stats, log);
            admit(kind, found, true);
            built.put(kind, now);
            listed.put(kind, now);
            stats.fullListings++;
            deltaDone.add(kind);
        }
    }

    private void delta(SuiteApi api, String kind, Log log) throws IOException {
        long since = (long) (listed.containsKey(kind) ? listed.get(kind) - DELTA_MARGIN_S
                : now - DELTA_MARGIN_S);
        try {
            admit(kind, index(api, kind, pageSize, String.valueOf(since), stats, log), false);
            stats.deltaListings++;
        } catch (SuiteApiException e) {
            if (log != null) {
                log.info("delta listing of " + kind + " refused (" + e.getMessage()
                        + "); listing in full");
            }
            admit(kind, index(api, kind, pageSize, null, stats, log), true);
            built.put(kind, now);
            stats.fullListings++;
        }
        listed.put(kind, now);
    }

    /**
     * Make sure every MoRef in {@code wanted} (kind to MoRefs) has been looked
     * for: one delta listing per kind per cycle for MoRefs never seen, then
     * the negative cache for the rest.
     */
    synchronized void resolve(SuiteApi api, Map<String, Set<String>> wanted, Log log)
            throws IOException {
        for (Map.Entry<String, Set<String>> w : wanted.entrySet()) {
            String kind = w.getKey();
            Map<String, String> have = map.get(kind);
            if (have == null) {
                have = new HashMap<>();
            }
            Map<String, Double> n = neg.get(kind);
            if (n == null) {
                n = new HashMap<>();
                neg.put(kind, n);
            }
            List<String> fresh = new ArrayList<>();
            for (String m : w.getValue()) {
                Double t = n.get(m);
                if (!have.containsKey(m) && (t == null || now - t >= negTtlS)) {
                    fresh.add(m);
                }
            }
            if (!fresh.isEmpty() && !deltaDone.contains(kind)) {
                deltaDone.add(kind);
                delta(api, kind, log);
                have = map.get(kind);
                if (have == null) {
                    have = new HashMap<>();
                }
            }
            for (String m : fresh) {
                if (!have.containsKey(m)) {
                    n.put(m, now);
                }
            }
        }
    }

    synchronized String lookup(String kind, String moid) {
        Map<String, String> m = map.get(kind);
        return m == null ? null : m.get(moid);
    }

    /** A 404 on {@code rid}: drop it and schedule a full listing of its kind. */
    synchronized boolean invalidate(String rid) {
        if (rev == null) {
            rev = new HashMap<>();
            for (Map.Entry<String, Map<String, String>> k : map.entrySet()) {
                for (Map.Entry<String, String> e : k.getValue().entrySet()) {
                    rev.put(e.getValue(), new String[] {k.getKey(), e.getKey()});
                }
            }
        }
        String[] hit = rev.remove(rid);
        if (hit == null) {
            return false;
        }
        map.get(hit[0]).remove(hit[1]);
        built.put(hit[0], 0.0);
        stats.invalidated++;
        return true;
    }

    synchronized int size() {
        int n = 0;
        for (Map<String, String> m : map.values()) {
            n += m.size();
        }
        return n;
    }

    /** Suite API answered, but not with what was asked for. */
    static final class SuiteApiException extends IOException {
        private static final long serialVersionUID = 1L;

        SuiteApiException(String m) {
            super(m);
        }
    }
}
