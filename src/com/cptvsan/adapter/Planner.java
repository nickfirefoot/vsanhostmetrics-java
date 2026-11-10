package com.cptvsan.adapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Perf objects to per-VMWARE-object payloads: the plan half of the Python
 * pack's {@code app/push.py}. Pure data in, pure data out: no object and no
 * relationship is created here, only {@code cptvsan|} stat and property keys
 * for objects the vCenter adapter already owns.
 */
final class Planner {

    private Planner() {
    }

    /** Everything one VMWARE object receives this cycle. */
    static final class Target {
        final TargetKind kind;
        final String moid;
        /** key to {epoch ms: value}. */
        final Map<String, TreeMap<Long, Double>> stats = new TreeMap<>();
        final Map<String, String> props = new TreeMap<>();

        Target(TargetKind kind, String moid) {
            this.kind = kind;
            this.moid = moid;
        }

        int values() {
            int n = 0;
            for (TreeMap<Long, Double> s : stats.values()) {
                n += s.size();
            }
            return n;
        }
    }

    /** Counters of one plan. */
    static final class PlanStats {
        int entities;
        int mapped;
        int unmapped;
        int collisions;
        int diskGuard;
        String diskGuardExample = "";
        int derived;
        int values;

        void merge(PlanStats o) {
            entities += o.entities;
            mapped += o.mapped;
            unmapped += o.unmapped;
            collisions += o.collisions;
            diskGuard += o.diskGuard;
            derived += o.derived;
            values += o.values;
            if (diskGuardExample.isEmpty()) {
                diskGuardExample = o.diskGuardExample;
            }
        }
    }

    // ------------------------------------------------------------------
    // Derived metrics
    // ------------------------------------------------------------------

    static final String[] RX_LOSS_INPUTS = {"rxMissErrRaw", "rxPktRaw"};
    static final String[] NET_SCHED_INPUTS = {"numNetSchedLowBandSec", "numNetSchedMidBandSec",
        "numNetSchedHighBandSec"};

    /**
     * Derived metric to value for one object at one instant, from inputs of
     * the SAME object at the SAME timestamp. Additive; skipped (never zero)
     * when an input is missing or the denominator is zero.
     * rxLossRatio is the lifetime missed-packet share, 100 * rxMissErrRaw / rxPktRaw.
     */
    static Map<String, Double> derive(String entity, Map<String, Double> g) {
        Map<String, Double> out = new LinkedHashMap<>();
        if ("vsan-pnic-net".equals(entity)) {
            Double num = g.get(RX_LOSS_INPUTS[0]);
            Double den = g.get(RX_LOSS_INPUTS[1]);
            if (num != null && den != null && den != 0.0) {
                out.put("rxLossRatio", 100.0 * num / den);
            }
        }
        if ("host-domowner".equals(entity)) {
            Double low = g.get(NET_SCHED_INPUTS[0]);
            Double mid = g.get(NET_SCHED_INPUTS[1]);
            Double high = g.get(NET_SCHED_INPUTS[2]); // optional third band
            if (low != null && mid != null) {
                double den = low + mid + (high == null ? 0.0 : high);
                if (den != 0.0) {
                    out.put("netSchedLowBandPct", 100.0 * low / den);
                }
            }
        }
        return out;
    }

    /** {@link #derive} at every sample timestamp of the inputs. */
    static Map<String, TreeMap<Long, Double>> deriveSeries(String entity,
            Map<String, TreeMap<Long, Double>> samples) {
        String[] inputs = "vsan-pnic-net".equals(entity) ? RX_LOSS_INPUTS
                : "host-domowner".equals(entity) ? NET_SCHED_INPUTS : null;
        Map<String, TreeMap<Long, Double>> out = new LinkedHashMap<>();
        if (inputs == null) {
            return out;
        }
        Set<Long> stamps = new TreeSet<>();
        for (String m : inputs) {
            if (samples.containsKey(m)) {
                stamps.addAll(samples.get(m).keySet());
            }
        }
        for (long ts : stamps) {
            Map<String, Double> at = new HashMap<>();
            for (String m : inputs) {
                TreeMap<Long, Double> s = samples.get(m);
                if (s != null && s.containsKey(ts)) {
                    at.put(m, s.get(ts));
                }
            }
            for (Map.Entry<String, Double> e : derive(entity, at).entrySet()) {
                TreeMap<Long, Double> dst = out.get(e.getKey());
                if (dst == null) {
                    dst = new TreeMap<>();
                    out.put(e.getKey(), dst);
                }
                dst.put(ts, e.getValue());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Plan
    // ------------------------------------------------------------------

    /** Which map resolves which identity part, per target kind; the first part present wins. */
    static String[][] buckets(TargetKind k) {
        switch (k) {
            case CLUSTER:
                return new String[][] {{"cluster_uuid", "clusters"}};
            case HOST:
                return new String[][] {{"host_uuid", "hosts"}, {"disk_uuid", "disks"}};
            default:
                return new String[][] {{"vm_uuid", "vms"}, {"vdisk_uuid", "vdisks"},
                    {"disk_uuid", "vdisks"}};
        }
    }

    /** Instance parts whose value is an opaque id; a readable name, when known, becomes a "name" property. */
    static final String[] NAMED_INSTANCE_PARTS = {"disk_uuid", "vdisk_uuid"};

    static String moref(EntityRef ref, Map<String, Map<String, String>> parents) {
        for (String[] pb : buckets(ref.entry.target)) {
            String v = ref.part(pb[0]);
            if (v != null && !v.isEmpty()) {
                Map<String, String> m = parents.get(pb[1]);
                return m == null ? null : m.get(v);
            }
        }
        return null;
    }

    /**
     * {@code {(kind, moid): Target}} from the parsed objects. Objects are
     * visited in a fixed order (entity type, then identity) so "first" means
     * the same thing on every run.
     *
     * <p>DISK GUARD: one disk instance can receive the same key from two
     * entity types (OSA cache-disk / capacity-disk and ESA vsan-esa-disk-*
     * share metric names under Disk:&lt;uuid&gt;). The first entity type's
     * series is kept and the other's dropped.
     */
    static Map<String, Target> plan(Map<EntityRef, RowSink.Series> objects,
            Map<String, Map<String, String>> parents, Map<String, String> names,
            Map<String, Map<String, String>> diskHw, Set<String> diskHwEntities,
            PlanStats st) {
        Map<String, Target> targets = new TreeMap<>();
        Map<String, Object[]> owner = new HashMap<>();
        List<EntityRef> order = new ArrayList<>(objects.keySet());
        Collections.sort(order, (a, b) -> {
            int c = a.entry.id.compareTo(b.entry.id);
            if (c != 0) {
                return c;
            }
            for (int i = 0; i < Math.min(a.parts.size(), b.parts.size()); i++) {
                c = a.parts.get(i).compareTo(b.parts.get(i));
                if (c != 0) {
                    return c;
                }
            }
            return Integer.compare(a.parts.size(), b.parts.size());
        });
        for (EntityRef okey : order) {
            TreeEntry entry = okey.entry;
            st.entities++;
            String moid = moref(okey, parents);
            if (moid == null || moid.isEmpty()) {
                st.unmapped++;
                continue;
            }
            TargetKind kind = entry.target;
            String prefix = StatKeys.PREFIX + StatKeys.groupPath(entry, okey.parts) + "|";
            st.mapped++;
            String tkey = kind.resourceKind + "\u0000" + moid;
            Target tgt = targets.get(tkey);
            if (tgt == null) {
                tgt = new Target(kind, moid);
                targets.put(tkey, tgt);
            }
            RowSink.Series obj = objects.get(okey);
            for (Map.Entry<String, TreeMap<Long, Double>> m : obj.samples.entrySet()) {
                if (m.getValue().isEmpty()) {
                    continue;
                }
                String key = prefix + m.getKey();
                if (claim(owner, st, tkey, key, entry.id, okey)) {
                    tgt.stats.put(key, new TreeMap<>(m.getValue()));
                    st.values += m.getValue().size();
                }
            }
            for (Map.Entry<String, TreeMap<Long, Double>> m
                    : deriveSeries(entry.id, obj.samples).entrySet()) {
                String key = prefix + m.getKey();
                if (claim(owner, st, tkey, key, entry.id, okey)) {
                    tgt.stats.put(key, m.getValue());
                    st.derived += m.getValue().size();
                    st.values += m.getValue().size();
                }
            }
            // Properties describe the INSTANCE (its name, its disk hardware),
            // so two entity types of one disk carry the same values: first wins.
            if (entry.instanced()) {
                for (String part : NAMED_INSTANCE_PARTS) {
                    String raw = okey.part(part);
                    String label = raw == null ? null : names.get(raw);
                    if (raw != null && label != null && !label.equals(raw)
                            && entry.templateUses(part)) {
                        if (!tgt.props.containsKey(prefix + "name")) {
                            tgt.props.put(prefix + "name", label);
                        }
                    }
                }
            }
            if (diskHwEntities.contains(entry.id)) {
                String du = okey.part("disk_uuid");
                Map<String, String> hw = du == null ? null : diskHw.get(du);
                if (hw != null) {
                    for (Map.Entry<String, String> p : hw.entrySet()) {
                        if (p.getValue() != null && !p.getValue().isEmpty()
                                && !tgt.props.containsKey(prefix + p.getKey())) {
                            tgt.props.put(prefix + p.getKey(), p.getValue());
                        }
                    }
                }
            }
        }
        return targets;
    }

    private static boolean claim(Map<String, Object[]> owner, PlanStats st, String tkey,
            String key, String entity, EntityRef okey) {
        String slot = tkey + "\u0001" + key;
        Object[] prev = owner.get(slot);
        if (prev != null && !prev[1].equals(okey)) {
            if (!prev[0].equals(entity)) {
                st.diskGuard++;
                if (st.diskGuardExample.isEmpty()) {
                    st.diskGuardExample = key + " on " + tkey.replace('\u0000', ' ') + ": kept "
                            + prev[0] + ", dropped " + entity;
                }
                return false;
            }
            st.collisions++;
        }
        owner.put(slot, new Object[] {entity, okey});
        return true;
    }

    /** Every key the targets carry, sorted. */
    static Set<String> allKeys(Map<String, Target> targets) {
        Set<String> out = new TreeSet<>();
        for (Target t : targets.values()) {
            out.addAll(t.stats.keySet());
            out.addAll(t.props.keySet());
        }
        return out;
    }

    static Set<String> emptySet() {
        return new HashSet<>();
    }
}
