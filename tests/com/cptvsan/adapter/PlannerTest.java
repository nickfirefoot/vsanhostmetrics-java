package com.cptvsan.adapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Plan: ownership, derived metrics, the disk guard, properties. */
public final class PlannerTest {

    private static RowSink.Series series(Object... kv) {
        RowSink.Series s = new RowSink.Series();
        for (int i = 0; i < kv.length; i += 2) {
            TreeMap<Long, Double> m = new TreeMap<>();
            double[] v = (double[]) kv[i + 1];
            for (int k = 0; k < v.length; k++) {
                m.put(1000L * (k + 1), v[k]);
            }
            s.samples.put((String) kv[i], m);
        }
        return s;
    }

    public static void main(String[] args) {
        Map<String, Map<String, String>> parents = new HashMap<>();
        parents.put("hosts", new HashMap<>());
        parents.put("clusters", new HashMap<>());
        parents.put("disks", new HashMap<>());
        parents.put("vms", new HashMap<>());
        parents.put("vdisks", new HashMap<>());
        parents.get("hosts").put("node1", "host-1");
        parents.get("clusters").put("cl1", "domain-c1");
        parents.get("disks").put("disk-a", "host-1");

        // derived: rxLossRatio at the timestamps where both inputs exist
        Map<EntityRef, RowSink.Series> objs = new java.util.LinkedHashMap<>();
        objs.put(EntityRef.parse("vsan-pnic-net:node1|vmnic0"), series(
                "rxMissErrRaw", new double[] {2, 4}, "rxPktRaw", new double[] {100, 0}));
        objs.put(EntityRef.parse("host-domowner:node1"), series(
                "numNetSchedLowBandSec", new double[] {1}, "numNetSchedMidBandSec", new double[] {3},
                "numNetSchedHighBandSec", new double[] {1}));
        objs.put(EntityRef.parse("cluster-domowner:cl1"), series("readIops", new double[] {5}));
        objs.put(EntityRef.parse("cluster-domowner:unknown"), series("readIops", new double[] {5}));
        Planner.PlanStats st = new Planner.PlanStats();
        Map<String, Planner.Target> t = Planner.plan(objs, parents, new HashMap<String, String>(),
                new HashMap<String, Map<String, String>>(), new HashSet<String>(), st);
        Planner.Target host = t.get("HostSystem\u0000host-1");
        Check.eq(2.0, host.stats.get("cptvsan|Network|Physical NIC:vmnic0|rxLossRatio").get(1000L), "rxLossRatio 100*2/100");
        Check.yes(!host.stats.get("cptvsan|Network|Physical NIC:vmnic0|rxLossRatio")
                .containsKey(2000L), "zero denominator: skipped, never zero-filled");
        Check.eq(20.0, host.stats.get("cptvsan|Storage I/O:dom-owner|netSchedLowBandPct").get(1000L),
                "netSchedLowBandPct = 100*1/(1+3+1)");
        Check.eq(2, st.derived, "derived samples counted (1 + 1)");
        Check.eq(1, st.unmapped, "an entity with no owner is counted, not pushed");
        Check.yes(t.get("ClusterComputeResource\u0000domain-c1") != null, "cluster target");
        Check.eq(2, t.size(), "no target for the unmapped entity");

        // derive() without inputs
        Check.eq(0, Planner.derive("vsan-pnic-net", new HashMap<String, Double>()).size(),
                "no inputs, no derived value");
        Map<String, Double> g = new HashMap<>();
        g.put("numNetSchedLowBandSec", 1.0);
        Check.eq(0, Planner.derive("host-domowner", g).size(), "one band missing: skipped");
        Check.eq(0, Planner.derive("cluster-domowner", g).size(), "other types derive nothing");

        // disk guard: the same key from two entity types: first type kept
        objs = new java.util.LinkedHashMap<>();
        objs.put(EntityRef.parse("cache-disk:disk-a"), series("readIops", new double[] {1}));
        objs.put(EntityRef.parse("capacity-disk:disk-a"), series("readIops", new double[] {99}));
        st = new Planner.PlanStats();
        Map<String, String> names = new HashMap<>();
        names.put("disk-a", "Micron 7450 (esx1)");
        Map<String, Map<String, String>> hw = new HashMap<>();
        Map<String, String> h = new HashMap<>();
        h.put("firmware", "E2MU200");
        hw.put("disk-a", h);
        t = Planner.plan(objs, parents, names, hw,
                new HashSet<>(MetricTree.DISK_HARDWARE_ENTITIES), st);
        Planner.Target tg = t.get("HostSystem\u0000host-1");
        Check.eq(1, st.diskGuard, "one disk-guard drop");
        Check.eq(1.0, tg.stats.get("cptvsan|Disk:disk-a|readIops").get(1000L), "first type kept");
        Check.eq("Micron 7450 (esx1)", tg.props.get("cptvsan|Disk:disk-a|name"), "name property");
        Check.eq("E2MU200", tg.props.get("cptvsan|Disk:disk-a|firmware"), "hardware property");
        // every key and property key is under the prefix
        for (String k : Planner.allKeys(t)) {
            Check.yes(k.startsWith("cptvsan|"), "prefix: " + k);
        }
        List<String> unused = new ArrayList<>();
        Check.yes(unused.isEmpty(), "ok");
        Check.done("PlannerTest");
    }
}
