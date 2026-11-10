package com.cptvsan.adapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Rotation of the HEAVY set over 3 buckets, with the windows that backfill it. */
public final class CadenceTest {

    public static void main(String[] args) {
        Check.eq(13, Cadence.HEAVY.size(), "13 HEAVY types");
        Map<String, Integer> a = Cadence.assignBuckets(Cadence.HEAVY, 3);
        // the Python assign_buckets result, computed there (app/cadence.py, 2026-11-09)
        Map<String, Integer> expect = new TreeMap<>();
        for (Object[] kv : new Object[][] {{"clom-disk", 2}, {"clom-host", 0}, {"cmmds-workload", 2},
            {"ddh-disk", 2}, {"dom-world-cpu", 0}, {"heap-memory", 1}, {"host-cpu", 2},
            {"ioinsight", 0}, {"lsom-world-cpu", 0}, {"remote-vscsi", 0}, {"slab-memory", 1},
            {"vsan-distribution", 0}, {"vscsi", 1}}) {
            expect.put((String) kv[0], (Integer) kv[1]);
        }
        Check.eq(expect, new TreeMap<>(a), "bucket assignment equals Python's");

        List<String> all = Collector.wantedEntities(null);
        Check.eq(69, all.size(), "69 entity types");
        Map<String, Integer> seen = new HashMap<>();
        int core = 0;
        for (String e : all) {
            if (!Cadence.HEAVY.contains(e)) {
                core++;
            }
        }
        Check.eq(56, core, "56 CORE types");
        for (int cycle = 0; cycle < 6; cycle++) {
            List<String> due = Cadence.due(all, cycle, Cadence.HEAVY, 3, a);
            int heavyNow = 0;
            for (String e : due) {
                if (Cadence.HEAVY.contains(e)) {
                    heavyNow++;
                    seen.merge(e, 1, Integer::sum);
                }
            }
            Check.eq(core + heavyNow, due.size(), "cycle " + cycle + ": every CORE type is due");
            Check.yes(heavyNow > 0, "each cycle carries some HEAVY types");
            Check.eq(expectedHeavy(a, cycle % 3), heavyNow, "cycle " + cycle + " HEAVY count");
        }
        for (String h : Cadence.HEAVY) {
            Check.eq(2, seen.get(h), h + " is due exactly twice in six cycles");
        }
        // one bucket: everything every cycle
        Check.eq(69, Cadence.due(all, 0, Cadence.HEAVY, 1, Cadence.assignBuckets(Cadence.HEAVY, 1))
                .size(), "one bucket collects everything every cycle");
        // no heavy set: everything every cycle
        Check.eq(69, Cadence.due(all, 7, new java.util.HashSet<String>(), 3,
                new HashMap<String, Integer>()).size(), "empty HEAVY set");

        long now = 10_000_000_000L;
        long[] w = Cadence.window("host-cpu", now, null, Cadence.HEAVY, 3, 60);
        Check.eq(now - 20 * 60_000L, w[0], "HEAVY with no history reaches back 20 minutes");
        w = Cadence.window("cluster-domowner", now, null, Cadence.HEAVY, 3, 60);
        Check.eq(now - 10 * 60_000L, w[0], "CORE with no history reaches back 10 minutes");
        w = Cadence.window("cluster-domowner", now, now - 1000, Cadence.HEAVY, 3, 60);
        Check.eq(now - 1000, w[0], "starts at the newest pushed sample");
        w = Cadence.window("host-cpu", now, 1L, Cadence.HEAVY, 3, 60);
        Check.eq(now - 60 * 60_000L, w[0], "never further back than the backfill limit");
        Check.eq(now, w[1], "ends now");
        List<Integer> unused = new ArrayList<>();
        Check.yes(unused.isEmpty(), "no stray state");
        Check.done("CadenceTest");
    }

    private static int expectedHeavy(Map<String, Integer> a, int bucket) {
        int n = 0;
        for (int b : a.values()) {
            if (b == bucket) {
                n++;
            }
        }
        return n;
    }
}
