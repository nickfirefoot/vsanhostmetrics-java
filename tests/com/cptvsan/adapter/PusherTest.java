package com.cptvsan.adapter;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Push batching, retry, per-resource fallback, and what the writer will not send. */
public final class PusherTest {

    static Map<String, Planner.Target> targets(int n) {
        Map<String, Planner.Target> t = new java.util.TreeMap<>();
        for (int h = 1; h <= n; h++) {
            Planner.Target tg = new Planner.Target(TargetKind.HOST, "host-" + h);
            java.util.TreeMap<Long, Double> s = new java.util.TreeMap<>();
            s.put(1000L, 1.0 * h);
            s.put(2000L, Double.NaN); // never sent: would make the body invalid JSON
            s.put(3000L, 3.0);
            tg.stats.put("cptvsan|Network|Host|m1", s);
            tg.props.put("cptvsan|Disk:d|name", "disk " + h);
            t.put("HostSystem\u0000host-" + h, tg);
        }
        return t;
    }

    static Pusher pusher(FakeOps fake, IdCache ids, int maxObjects, int maxBytes) {
        SuiteApi api = new SuiteApi(fake.suiteBase(), "ops", "pw", Net.PLAIN, 20_000);
        try {
            ids.beginCycle(1000);
            ids.refreshExpired(api, Log.NONE);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new Pusher(api, ids, null, maxObjects, maxBytes, 0, 4, Log.NONE);
    }

    public static void main(String[] args) throws Exception {
        // --- batching by object count
        try (FakeOps fake = new FakeOps(25, 0)) {
            IdCache ids = new IdCache(fake.vcid, 1800, 600, 1000);
            Pusher p = pusher(fake, ids, 10, 1_000_000);
            Pusher.PushStats st = p.push(targets(25), 5000L, null).result();
            Check.eq(25, st.resolved, "all resolved");
            Check.eq(3, fake.batchStatsPosts.get(), "25 objects at 10 per request = 3 stat batches");
            Check.eq(3, fake.batchPropsPosts.get(), "3 property batches");
            Check.eq(6, st.batches, "6 batch requests");
            Check.eq(0, st.failed, "none failed");
            Check.eq(50L, st.stats, "NaN dropped: 2 values per object accepted");
            Check.eq(50L, st.props, "property values: name + freshness per object");
            Check.eq(25, fake.statKeys.size(), "every host written");
            Check.yes(fake.propKeys.get("rid-h1").contains("cptvsan|Collector|lastUpdated"),
                    "freshness property");
        }
        // --- batching by bytes: a tiny byte limit gives one object per request
        try (FakeOps fake = new FakeOps(5, 0)) {
            IdCache ids = new IdCache(fake.vcid, 1800, 600, 1000);
            Pusher p = pusher(fake, ids, 100, 50);
            p.push(targets(5), 5000L, null).result();
            Check.eq(5, fake.batchStatsPosts.get(), "byte limit: one object per stat request");
        }
        // --- unresolved targets are counted and never sent
        try (FakeOps fake = new FakeOps(2, 0)) {
            IdCache ids = new IdCache(fake.vcid, 1800, 600, 1000);
            Pusher p = pusher(fake, ids, 10, 1_000_000);
            Pusher.PushStats st = p.push(targets(5), 5000L, null).result(); // host-3..5 unknown
            Check.eq(3, st.unresolved, "unknown MoRefs unresolved");
            Check.eq(2, fake.statKeys.size(), "only known objects written");
        }
        // --- the listing is filtered client-side (look-alikes of another adapter kind)
        try (FakeOps fake = new FakeOps(2, 0)) {
            IdCache ids = new IdCache(fake.vcid, 1800, 600, 1000);
            pusher(fake, ids, 10, 1000);
            Check.eq("rid-h1", ids.lookup("HostSystem", "host-1"), "the VMWARE resource, not other-rid-h1");
        }
        // --- 4xx on a batch: re-sent per resource; a 404 invalidates the id
        try (FakeOps fake = new FakeOps(3, 0)) {
            IdCache ids = new IdCache(fake.vcid, 1800, 600, 1000);
            Pusher p = pusher(fake, ids, 10, 1_000_000);
            fake.batchStatuses.add(400); // first batch (properties) rejected
            fake.perResourceStatus.put("rid-h2", 404);
            Pusher.PushStats st = p.push(targets(3), 5000L, null).result();
            Check.eq(3, st.fallbackPosts, "each resource of the rejected batch re-sent once");
            Check.eq(1, st.notFound, "a 404 is counted");
            Check.yes(ids.lookup("HostSystem", "host-2") == null, "and invalidates the cached id");
            Check.eq(1, st.failed, "that resource failed");
            Check.yes(fake.propKeys.containsKey("rid-h1") && fake.propKeys.containsKey("rid-h3"),
                    "the others landed via the per-resource path");
        }
        // --- 5xx twice: nothing more is sent this cycle, marks must not advance
        try (FakeOps fake = new FakeOps(25, 0)) {
            IdCache ids = new IdCache(fake.vcid, 1800, 600, 1000);
            Pusher p = pusher(fake, ids, 10, 1_000_000);
            fake.batchStatuses.add(503);
            fake.batchStatuses.add(503);
            Pusher.PushStats st = p.push(targets(25), 5000L, null).result();
            Check.yes(st.skipped > 0, "the rest is counted as skipped: " + st.skipped);
            Check.yes(st.batchFailed >= 2, "two failures");
            Check.yes(st.ok < st.resolved, "not everything landed");
        }
        // --- parallel pushes through an executor give the same totals
        try (FakeOps fake = new FakeOps(25, 0)) {
            IdCache ids = new IdCache(fake.vcid, 1800, 600, 1000);
            SuiteApi api = new SuiteApi(fake.suiteBase(), "ops", "pw", Net.PLAIN, 20_000);
            ids.beginCycle(1000);
            ids.refreshExpired(api, Log.NONE);
            ExecutorService ex = Executors.newFixedThreadPool(4);
            Pusher p = new Pusher(api, ids, ex, 10, 1_000_000, 0, 4, Log.NONE);
            Pusher.PushStats st = p.push(targets(25), 5000L, null).result();
            ex.shutdown();
            Check.eq(6, st.batches, "same batches in parallel");
            Check.eq(25, fake.statKeys.size(), "every host written");
        }
        // --- what the writer will not send
        Check.fails(IllegalStateException.class, () -> Pusher.guardUrl(
                "api/resources/adapterkinds/VMWARE/resourcekinds/X/stats"), "adapter-kind push refused");
        Check.fails(IllegalStateException.class, () -> Pusher.guardUrl("api/resources/x/relationships"),
                "relationship endpoint refused");
        try (FakeOps fake = new FakeOps(1, 0)) {
            SuiteApi api = new SuiteApi(fake.suiteBase(), "ops", "pw", Net.PLAIN, 20_000);
            for (String bad : new String[] {"api/resources/x/relationships",
                "api/resources/adapterkinds/VMWARE/stats", "api/resources", "api/adapters",
                "api/resources/x/relationships/CHILD", "api/resources/bulk/relationships"}) {
                Check.fails(IllegalStateException.class, () -> api.post(bad, "{}"), "POST " + bad);
            }
            for (String bad : new String[] {"api/adapters", "api/credentials", "api/resources/x/stats"}) {
                Check.fails(IllegalStateException.class, () -> api.get(bad), "GET " + bad);
            }
            Check.eq(0L, fake.suitePaths.stream().filter(s -> s.startsWith("POST api/resources")).count(),
                    "nothing reached the server");
            Check.eq(200, api.get("api/resources?adapterKind=VMWARE&resourceKind=HostSystem&page=0&pageSize=10").status,
                    "a resource listing is allowed");
            Check.eq(200, api.get("api/resources/rid-h1").status, "reading one resource is allowed");
            Check.eq(200, api.post("api/resources/stats", "{\"resource-stat-content\":[]}").status,
                    "batch stats allowed");
            Check.eq(200, api.post("api/resources/rid-h1/properties", "{\"property-content\":[]}").status,
                    "per-resource properties allowed");
        }
        Map<String, String> unused = new HashMap<>();
        Check.yes(unused.isEmpty(), "ok");
        Check.done("PusherTest");
    }
}
