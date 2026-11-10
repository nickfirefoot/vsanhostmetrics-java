package com.cptvsan.adapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collection cadence: which entity types are queried in which 5-minute
 * cycle, and over what window. The Python pack's {@code app/cadence.py}.
 *
 * <p>CORE types are queried every cycle. HEAVY types, whose row count scales
 * with worlds, cores or devices rather than hosts, are queried every
 * {@link #BUCKETS}th cycle, spread over the buckets by longest-processing-
 * time-first so each cycle carries about the same load. Nothing is lost by
 * rotating: each query's window reaches back to that type's last successful
 * collection and every sample is pushed with its own timestamp.
 */
final class Cadence {

    static final Set<String> HEAVY = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "dom-world-cpu", "lsom-world-cpu", "host-cpu", "vscsi", "remote-vscsi",
            "heap-memory", "slab-memory", "clom-host", "clom-disk", "cmmds-workload",
            "vsan-distribution", "ddh-disk", "ioinsight")));

    static final int BUCKETS = 3;
    static final int CYCLE_MINUTES = 5;

    /** Nominal values per HOST per collection, used only to balance the buckets. */
    static final Map<String, Integer> NOMINAL_WEIGHT = new HashMap<>();

    static {
        NOMINAL_WEIGHT.put("dom-world-cpu", 240);
        NOMINAL_WEIGHT.put("vscsi", 240);
        NOMINAL_WEIGHT.put("host-cpu", 195);
        NOMINAL_WEIGHT.put("ddh-disk", 60);
        NOMINAL_WEIGHT.put("lsom-world-cpu", 40);
        NOMINAL_WEIGHT.put("slab-memory", 30);
        NOMINAL_WEIGHT.put("cmmds-workload", 23);
        NOMINAL_WEIGHT.put("heap-memory", 20);
        NOMINAL_WEIGHT.put("clom-disk", 18);
        NOMINAL_WEIGHT.put("vsan-distribution", 5);
        NOMINAL_WEIGHT.put("clom-host", 2);
        NOMINAL_WEIGHT.put("ioinsight", 0);
        NOMINAL_WEIGHT.put("remote-vscsi", 0);
    }

    private Cadence() {
    }

    private static int weight(String e) {
        Integer w = NOMINAL_WEIGHT.get(e);
        return w == null ? 1 : w;
    }

    /** {heavy type: bucket}: heaviest type to the lightest bucket; ties by name and index. */
    static Map<String, Integer> assignBuckets(Set<String> heavy, int buckets) {
        int[] load = new int[Math.max(1, buckets)];
        List<String> order = new ArrayList<>(heavy);
        Collections.sort(order, (a, b) -> {
            int c = Integer.compare(weight(b), weight(a));
            return c != 0 ? c : a.compareTo(b);
        });
        Map<String, Integer> out = new HashMap<>();
        for (String e : order) {
            int best = 0;
            for (int i = 1; i < load.length; i++) {
                if (load[i] < load[best]) {
                    best = i;
                }
            }
            out.put(e, best);
            load[best] += weight(e);
        }
        return out;
    }

    /** Entity types to query in {@code cycle}: every CORE type plus the HEAVY ones whose bucket is due. */
    static List<String> due(Iterable<String> entities, int cycle, Set<String> heavy, int buckets,
            Map<String, Integer> assignment) {
        int b = Math.floorMod(cycle, Math.max(1, buckets));
        List<String> out = new ArrayList<>();
        for (String e : entities) {
            if (!heavy.contains(e)) {
                out.add(e);
            } else {
                Integer a = assignment.get(e);
                if ((a == null ? 0 : a) == b) {
                    out.add(e);
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    /**
     * {start, end} epoch ms for one entity type's query. Starts AT the newest
     * sample already pushed (the parser drops anything at or before it). With
     * no history it reaches back one period plus one bucket of slack: 10 min
     * for CORE, 20 min for HEAVY. Never further back than {@code maxBackfillMin}.
     */
    static long[] window(String entity, long nowMs, Long lastMs, Set<String> heavy, int buckets,
            int maxBackfillMin) {
        int period = CYCLE_MINUTES * (heavy.contains(entity) ? buckets : 1);
        long start = lastMs != null && lastMs != 0 ? lastMs
                : nowMs - (period + CYCLE_MINUTES) * 60_000L;
        start = Math.max(start, nowMs - maxBackfillMin * 60_000L);
        return new long[] {start, nowMs};
    }
}
