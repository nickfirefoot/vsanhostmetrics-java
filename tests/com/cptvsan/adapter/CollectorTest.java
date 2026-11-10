package com.cptvsan.adapter;

import java.util.Set;
import java.util.TreeSet;

/**
 * One cycle against the fake vCenter and Suite API: what is pushed, where,
 * and how the empty-type cache, the unsupported memory, the watermarks and the
 * id cache behave across cycles.
 */
public final class CollectorTest {

    public static void main(String[] args) throws Exception {
        try (FakeOps fake = new FakeOps(2, 1)) {
            fake.unsupported.add("cluster-pmem");
            Harness h = new Harness(fake);
            Collector.Report r = h.cycle();

            // --- what landed, and where
            Check.yes(fake.statKeys.containsKey("rid-h1") && fake.statKeys.containsKey("rid-h2"),
                    "both hosts received stats");
            Check.yes(fake.statKeys.containsKey("rid-c1"), "the cluster received stats");
            Check.yes(fake.statKeys.get("rid-c1").contains("cptvsan|Storage I/O:dom-owner|"
                    + MetricNames.of("cluster-domowner")[0]), "cluster key");
            for (String k : fake.allStatKeys()) {
                Check.yes(k.startsWith("cptvsan|"), "stat key under the prefix: " + k);
            }
            for (String k : fake.allPropKeys()) {
                Check.yes(k.startsWith("cptvsan|"), "property key under the prefix: " + k);
            }
            Check.yes(fake.propKeys.get("rid-h1").contains("cptvsan|Collector|lastUpdated"),
                    "freshness property on a host");
            Check.yes(fake.propKeys.get("rid-c1").contains("cptvsan|Collector|lastUpdated"),
                    "freshness property on a cluster");
            Check.yes(fake.statKeys.get("rid-h1").contains("cptvsan|CPU:cores-total|"
                    + MetricNames.of("host-cpu")[0]) || r.bucket != 2,
                    "aggregate cpu row keyed cores-total when host-cpu is due");
            Check.yes(fake.allStatKeys().stream().anyMatch(k -> k.endsWith("|rxLossRatio"))
                    || fake.allStatKeys().stream().anyMatch(k -> k.contains("Physical NIC")),
                    "pnic metrics pushed");
            Check.yes(fake.allStatKeys().stream().anyMatch(k -> k.endsWith("|netSchedLowBandPct")),
                    "derived netSchedLowBandPct pushed");
            Check.yes(fake.allStatKeys().stream().anyMatch(k -> k.endsWith("|rxLossRatio")),
                    "derived rxLossRatio pushed");

            // nothing but stats and properties was ever POSTed, nothing foreign was touched
            for (String p : fake.suitePaths) {
                Check.yes(!p.toLowerCase().contains("adapterkinds") && !p.contains("relationships"),
                        "no forbidden endpoint: " + p);
                if (p.startsWith("POST")) {
                    Check.yes(p.matches("POST api/(auth/token/acquire|resources/(stats|properties))"),
                            "only stats/properties POSTs: " + p);
                }
            }
            // foreign look-alikes (adapterKind OTHERKIND, same moid) are never pushed to
            for (String rid : fake.statKeys.keySet()) {
                Check.yes(!rid.startsWith("other-"), "not pushed to a look-alike: " + rid);
            }
            Check.eq(0, (int) r.c("clustersFailed"), "no cluster failed");
            Check.yes(r.failure() == null, "a clean cycle has no failure: " + r.failure());
            // a failed cluster must surface: named, even though the others succeeded
            Collector.Report part = new Collector.Report();
            part.failedClusters.add("prod-a");
            part.failedClusters.add("prod-b");
            part.problems.add("prod-a: IOException: boom");
            Check.yes(part.failure() != null && part.failure().contains("prod-a")
                    && part.failure().contains("prod-b") && part.failure().startsWith("2 cluster"),
                    "partial failure names the clusters: " + part.failure());
            // entities planned but no target to push to
            Collector.Report none = new Collector.Report();
            none.plan.entities = 5;
            Check.yes(none.failure() != null && none.failure().contains("0 targets"),
                    "entities with no targets fail: " + none.failure());
            none.push.targets = 1;
            Check.yes(none.failure() == null, "entities with a target do not trip it");
            Check.eq(3, r.push.targets, "targets: cluster + 2 hosts (VMs have no rows)");
            Check.eq(3, r.push.resolved, "all resolved");
            Check.eq(0, r.push.unresolved, "none unresolved");
            Check.yes(r.push.stats > 0 && r.push.props >= 3, "values accepted");
            Check.yes(h.state.unsupported.containsKey("domain-c1|cluster-pmem"),
                    "an unsupported type is remembered");
            Check.yes(h.state.unsupported.get("domain-c1|cluster-pmem") - h.now + 300_000
                    >= 359 * 60_000L, "unsupported recheck is ~6 hours");

            // --- empty cache
            int emptyN = h.state.empty.size();
            Check.yes(emptyN > 20, "types that answered with no entity are remembered: " + emptyN);
            Check.yes(!h.state.empty.containsKey("domain-c1|cluster-resync"),
                    "cluster-resync is NEVER remembered empty (it is always probed)");
            Check.yes(!h.state.empty.containsKey("domain-c1|cluster-domowner"),
                    "a type that returned an entity is not remembered empty");
            int resyncBefore = fake.perfCalls.get("cluster-resync").get();
            int pmemBefore = fake.perfCalls.get("cluster-pmem").get();
            fake.present.remove("cluster-resync"); // resync finished: the type is now empty
            Collector.Report r2 = h.cycle();
            Check.eq(resyncBefore + 1, fake.perfCalls.get("cluster-resync").get(),
                    "cluster-resync is probed every cycle even when empty");
            Check.eq(pmemBefore, fake.perfCalls.get("cluster-pmem").get(),
                    "an unsupported type is not asked again within its recheck");
            Check.yes(r2.c("callsSkippedEmpty") > 20, "empty types skipped on the next cycle: "
                    + r2.c("callsSkippedEmpty"));
            Check.yes(r2.c("entityTypesQueried") < r.c("entityTypesQueried"),
                    "fewer types queried the second time");
            fake.present.get("cluster-domowner");
            // a feature switches on: the type starts answering once re-probed
            fake.addPresent("cluster-zdom-top-stats", "cluster-zdom-top-stats:" + fake.clusterUuid());
            Check.yes(h.state.empty.containsKey("domain-c1|cluster-zdom-top-stats"), "was empty");
            // an hour later it is re-probed and forgotten as empty
            h.now += 61 * 60_000L;
            Collector.Report r3 = h.cycle();
            Check.yes(r3.c("emptyReprobes") > 0, "remembered-empty types probed again after the hour");
            Check.yes(!h.state.empty.containsKey("domain-c1|cluster-zdom-top-stats"),
                    "a type that now answers is forgotten as empty");

            // a new host (signature change) forgets what was remembered empty
            h.state.empty.put("domain-c1|cluster-pmem-x", h.now + 3_600_000L);
            String sig = h.state.sigs.get("domain-c1");
            h.state.sigs.put("domain-c1", "stale-signature");
            h.state.empty.put("domain-c1|host-pmem", h.now + 3_600_000L);
            long n4 = h.now;
            Collector.Report r4 = h.cycle();
            Check.yes(r4.c("emptyReprobes") > 0 && h.state.sigs.get("domain-c1").equals(sig),
                    "a changed host/disk signature re-probes remembered-empty types");

            // --- watermark: the second cycle pushes only newer samples
            Check.yes(h.state.last.containsKey("domain-c1|cluster-domowner"), "watermark recorded");
            fake.forget();
            h.now = n4 + 1_000; // same 5-minute bucket as the previous cycle: nothing newer exists
            Collector.Report r5 = h.cycle();
            Check.eq(0, (int) r5.push.stats, "no sample is pushed twice: " + r5.push.stats);

            // --- id cache: filtered listings, no re-list inside the TTL
            int listings = (int) fake.suitePaths.stream().filter(p -> p.startsWith("GET api/resources?")).count();
            h.cycle();
            int listings2 = (int) fake.suitePaths.stream().filter(p -> p.startsWith("GET api/resources?")).count();
            Check.eq(listings, listings2, "no listing within the id cache TTL");

            // --- self-metrics: only Collector|* keys
            for (String k : r.metrics().keySet()) {
                Check.yes(k.startsWith("Collector|"), "self-metric key: " + k);
            }
            Check.eq(CollectorKeys.METRIC_KEYS.size(), r.metrics().size(), "all self-metrics reported");
            Set<String> logs = new TreeSet<>(h.logs);
            for (String l : logs) {
                Check.yes(!l.contains("\"p\"") && !l.contains("pw"), "no credential in logs");
            }
        }

        // --- a vCenter below the floor is refused before anything is read or written
        try (FakeOps fake = new FakeOps(1, 0)) {
            fake.version = "8.0.2";
            Harness h = new Harness(fake);
            Check.fails(java.io.IOException.class, h::cycle, "version floor enforced");
            Check.eq(0, fake.statKeys.size(), "nothing pushed");
            Check.eq(0, fake.perfCalls.size(), "no perf query");
        }
        // --- every cluster failing is loud
        try (FakeOps fake = new FakeOps(1, 0)) {
            for (TreeEntry e : MetricTree.ENTRIES) {
                fake.broken.add(e.id);
            }
            Harness h = new Harness(fake);
            Collector.Report r = h.cycle();
            Check.yes(r.problems.size() > 0, "per-type failures are reported");
            Check.eq(0, (int) r.push.stats, "nothing pushed when every query failed");
        }
        // --- the session is renewed once on NotAuthenticated
        try (FakeOps fake = new FakeOps(1, 0)) {
            Harness h = new Harness(fake);
            h.cycle();
            int logins = fake.logins.get();
            fake.notAuthenticatedOnce = true;
            h.cycle();
            Check.eq(logins + 1, fake.logins.get(), "one fresh login after NotAuthenticated");
        }
        Check.done("CollectorTest");
    }
}
