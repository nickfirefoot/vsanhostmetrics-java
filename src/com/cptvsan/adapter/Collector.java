package com.cptvsan.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * One collection cycle: inventory, id resolution, the per-cluster
 * query / parse / plan / push pipeline, accounting. The Python pack's
 * {@code app/collector.py}. SDK-free, so the whole cycle runs offline against
 * a fake vCenter and a fake Suite API through exactly these code paths.
 *
 * <p>Nothing here creates an object or a relationship (relationship writes replace an object's whole child list):
 * the only writes are {@link Pusher}'s stats/properties POSTs onto VMWARE
 * objects. State (the newest pushed sample per cluster and type, what is
 * remembered unsupported or empty, the id cache, the disk cache, the
 * inventory mirror) lives in memory for the life of the adapter instance; a
 * restart costs one cold cycle and a small gap, never wrong data.
 */
final class Collector {

    /** Types whose entities exist only while something is happening: always probed. */
    static final Set<String> ALWAYS_PROBE = Collections.singleton("cluster-resync");

    /** Everything that survives from one cycle to the next. */
    static final class State {
        int cycle = -1;
        String vcid;
        Inventory inventory = new Inventory();
        /** "cluster|type" to the newest pushed sample (epoch ms). */
        final Map<String, Long> last = new HashMap<>();
        /** "cluster|type" to the time (ms) it is asked again. */
        final Map<String, Long> unsupported = new HashMap<>();
        final Map<String, Long> empty = new HashMap<>();
        /** cluster to its hosts+disks signature at the last full probe. */
        final Map<String, String> sigs = new HashMap<>();
        final Map<String, DiskInventory> disks = new HashMap<>();
        IdCache ids;
        List<String> verifyNext = new ArrayList<>();
    }

    /** The outcome of one cycle. */
    static final class Report {
        int cycle;
        int bucket;
        final Map<String, Double> timings = new HashMap<>();
        final Map<String, Double> counts = new HashMap<>();
        final Planner.PlanStats plan = new Planner.PlanStats();
        final Pusher.PushStats push = new Pusher.PushStats();
        final List<String> problems = new ArrayList<>();
        final Map<String, Double> perClusterQuery = new HashMap<>();
        /** Names of the clusters whose collection or push threw this cycle. */
        final List<String> failedClusters = new ArrayList<>();

        /**
         * Why this cycle must show as failed on the adapter instance, or null when it
         * need not. A partial result is not a success: what did succeed is already pushed,
         * but the failure must not be hidden behind it.
         */
        String failure() {
            if (!failedClusters.isEmpty()) {
                return failedClusters.size() + " cluster(s) failed: "
                        + String.join(", ", failedClusters) + "; first problems: "
                        + String.join("; ", problems.subList(0, Math.min(3, problems.size())));
            }
            if (plan.entities > 0 && push.targets == 0) {
                return "Collected " + plan.entities + " vSAN entities but none could be mapped to a"
                        + " VMware object to push to (0 targets). Check that the VMware vSphere"
                        + " adapter collects the same vCenter.";
            }
            return null;
        }

        double t(String k) {
            return timings.containsKey(k) ? timings.get(k) : 0.0;
        }

        double c(String k) {
            return counts.containsKey(k) ? counts.get(k) : 0.0;
        }

        void add(String k, double v) {
            counts.merge(k, v, Double::sum);
        }

        /** {@code Collector|<name>} to value, for the adapter instance. */
        Map<String, Double> metrics() {
            Map<String, Double> m = new LinkedHashMap<>();
            Map<String, Double> named = new HashMap<>();
            named.put("cycleSeconds", t("cycle"));
            named.put("sessionSeconds", t("session"));
            named.put("inventorySeconds", t("inventory"));
            named.put("diskInventorySeconds", t("disk_inventory"));
            named.put("querySeconds", t("query"));
            named.put("queryMaxClusterSeconds", t("query_max"));
            named.put("planSeconds", t("plan"));
            named.put("resolveSeconds", t("resolve"));
            named.put("pushSeconds", t("push"));
            named.put("rotationBucket", (double) bucket);
            named.put("valuesPushed", (double) push.stats);
            named.put("propertiesPushed", (double) push.props);
            named.put("objectsTargeted", (double) push.targets);
            named.put("objectsResolved", (double) push.resolved);
            named.put("objectsUnresolved", (double) push.unresolved);
            named.put("batchRequests", (double) push.batches);
            named.put("batchFailures", (double) push.batchFailed);
            named.put("fallbackPosts", (double) push.fallbackPosts);
            named.put("skippedResources", (double) push.skipped);
            named.put("suiteApiBytesSent", (double) push.bytesSent);
            named.put("diskGuardDrops", (double) plan.diskGuard);
            named.put("keyCollisions", (double) plan.collisions);
            named.put("entitiesUnmatched", (double) plan.unmapped);
            named.put("problems", (double) problems.size());
            for (String k : CollectorKeys.METRIC_KEYS.keySet()) {
                m.put("Collector|" + k, named.containsKey(k) ? named.get(k) : c(k));
            }
            return m;
        }

        String summary() {
            return String.format("cptvsan cycle %d (bucket %d): %.2fs [session %.2f inventory %.2f%s"
                    + " resolve %.2f clusters %.2f (summed: disk %.2f query+parse %.2f max %.2f"
                    + " plan %.2f) push %.2f]; %d clusters (%d failed), %d type queries (%d skipped"
                    + " as empty, %d re-probed, %d remembered empty), %d rows, %d samples;"
                    + " %d targets, %d resolved, %d unresolved, %d stat + %d property values"
                    + " accepted; vCenter %d calls, Suite API %d (%d batches, %d failed,"
                    + " %d fallback, %d skipped); %d unmatched, %d disk-guard drops,"
                    + " %d collisions, %d ids invalidated, %d problems", cycle, bucket,
                    t("cycle"), t("session"), t("inventory"), c("inventoryFull") > 0 ? " FULL" : "",
                    t("resolve"), t("clusters_wall"), t("disk_inventory"), t("query"),
                    t("query_max"), t("plan"), t("push"), (int) c("clusters"),
                    (int) c("clustersFailed"), (int) c("entityTypesQueried"),
                    (int) c("callsSkippedEmpty"), (int) c("emptyReprobes"),
                    (int) c("emptyTypesRemembered"), (int) c("rows"), (int) c("valuesCollected"),
                    push.targets, push.resolved, push.unresolved, push.stats, push.props,
                    (int) c("vcenterCalls"), (int) c("suiteApiCalls"), push.batches,
                    push.batchFailed, push.fallbackPosts, push.skipped, plan.unmapped,
                    plan.diskGuard, plan.collisions, (int) c("idsInvalidated"), problems.size());
        }
    }

    /** Raised when the whole source is unusable: loud, never an empty success. */
    static final class CycleException extends IOException {
        private static final long serialVersionUID = 1L;

        CycleException(String m) {
            super(m);
        }
    }

    private static final class ClusterResult {
        final String moid;
        String name;
        boolean ok = true;
        String error = "";
        double tDisk;
        double tQuery;
        double tPlan;
        int typesQueried;
        int rows;
        int values;
        long wire;
        Map<String, Long> newest = new HashMap<>();
        final Set<String> unsupported = new HashSet<>();
        final Set<String> empty = new HashSet<>();
        final Set<String> answered = new HashSet<>();
        final Set<String> failedTypes = new HashSet<>();
        int skippedEmpty;
        int reprobes;
        String sig = "";
        DiskInventory diskCache;
        Planner.PlanStats plan = new Planner.PlanStats();
        Pusher.Pending pending;
        final List<String> problems = new ArrayList<>();
        List<String> sampleRids = new ArrayList<>();

        ClusterResult(String moid) {
            this.moid = moid;
        }
    }

    private final VcClient vc;
    private final SuiteApi suite;
    private final VhmConfig cfg;
    private final State state;
    private final Log log;

    Collector(VcClient vc, SuiteApi suite, VhmConfig cfg, State state, Log log) {
        this.vc = vc;
        this.suite = suite;
        this.cfg = cfg;
        this.state = state;
        this.log = log;
    }

    private static double sec(long t0) {
        return (System.nanoTime() - t0) / 1e9;
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) {
                b.append(String.format("%02x", x & 0xff));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Disk-cache TTL with a per-cluster offset (up to +25%) so caches do not all expire together. */
    static long diskTtlMs(VhmConfig cfg, String moid) {
        double base = cfg.diskCacheTtlMin * 60_000;
        double jitter = (Integer.parseInt(sha1(moid).substring(0, 6), 16) % 1000) / 4000.0;
        return (long) (base * (1 + jitter));
    }

    /** A fixed per-vCenter order (by hash of the MoRef), so instances splitting one vCenter do not start together. */
    static List<Inventory.ClusterRef> staggerOrder(List<Inventory.ClusterRef> in) {
        List<Inventory.ClusterRef> out = new ArrayList<>(in);
        Collections.sort(out, (a, b) -> sha1(a.moid).compareTo(sha1(b.moid)));
        return out;
    }

    /** The cluster's hosts and disks as one short string; it changes when either is added or removed. */
    static String clusterSig(List<String[]> hosts, DiskInventory disks) {
        TreeSet<String> hs = new TreeSet<>();
        for (String[] h : hosts) {
            hs.add(h[0]);
        }
        return sha1(hs + "|" + (disks == null ? "[]" : disks.signature())).substring(0, 16);
    }

    /** All entity types the pack knows, sorted, minus {@code skip}. */
    static List<String> wantedEntities(Set<String> skip) {
        List<String> out = new ArrayList<>();
        for (TreeEntry e : MetricTree.ENTRIES) {
            if (skip == null || !skip.contains(e.id)) {
                out.add(e.id);
            }
        }
        Collections.sort(out);
        return out;
    }

    /** Everything one collect does. Mutates the state. */
    Report run(long nowMs, Integer cycleNumber, Random rng) throws IOException,
            InterruptedException {
        long t0 = System.nanoTime();
        Report rep = new Report();
        int genBefore = vc.generation.get();
        int cycle = cycleNumber != null ? cycleNumber : state.cycle + 1;
        state.cycle = cycle;
        rep.cycle = cycle;
        rep.bucket = Math.floorMod(cycle, Math.max(1, cfg.buckets));
        int vcCallsBefore = vc.calls.get();
        int suiteCallsBefore = suite.calls.get();
        long wireBefore = vc.wireBytes.get();
        vc.checkVersion();

        Semaphore gate = new Semaphore(Math.max(1, cfg.vcenterConcurrency));

        // --- inventory ---------------------------------------------------
        long t = System.nanoTime();
        String vcid = vc.instanceUuid();
        if (vcid.isEmpty()) {
            throw new CycleException("vCenter instanceUuid is unreadable, so no vSAN metric can be"
                    + " matched to a VMware object (VMEntityVCID). Nothing pushed.");
        }
        if (state.vcid != null && !state.vcid.equals(vcid)) {
            log.info("state was written for another vCenter; starting fresh");
            state.last.clear();
            state.unsupported.clear();
            state.empty.clear();
            state.sigs.clear();
            state.disks.clear();
            state.ids = null;
            state.inventory.reset();
        }
        state.vcid = vcid;
        Inventory.SyncStats sync = state.inventory.sync(vc, nowMs, cfg.inventoryFullRefreshMin);
        List<Inventory.ClusterRef> clusters = state.inventory.clusterRefs();
        List<Inventory.ClusterRef> chosen = Inventory.filterClusters(clusters, cfg.clusterInclude,
                cfg.clusterExclude);
        if (!clusters.isEmpty() && chosen.isEmpty()) {
            throw new CycleException("The cluster filter (include "
                    + (cfg.clusterInclude.isEmpty() ? "all" : cfg.clusterInclude.toString())
                    + ", exclude " + (cfg.clusterExclude.isEmpty() ? "none"
                    : cfg.clusterExclude.toString()) + ") matches none of the " + clusters.size()
                    + " clusters in this vCenter.");
        }
        Inventory.Joined joined = state.inventory.join(chosen);
        rep.timings.put("inventory", sec(t));
        rep.counts.put("inventoryFull", sync.full ? 1.0 : 0.0);
        rep.counts.put("inventoryObjects", (double) sync.objects);
        if (sync.full) {
            log.info("inventory read in full (" + sync.reason + "): " + sync.objects + " objects");
        }

        Set<String> keep = new HashSet<>();
        for (Inventory.ClusterRef c : chosen) {
            keep.add(c.moid);
        }
        for (Map<String, ?> table : java.util.Arrays.asList(state.last, state.unsupported,
                state.empty, state.sigs)) {
            for (Iterator<String> it = table.keySet().iterator(); it.hasNext();) {
                String k = it.next();
                int bar = k.indexOf('|');
                if (!keep.contains(bar < 0 ? k : k.substring(0, bar))) {
                    it.remove();
                }
            }
        }
        state.disks.keySet().retainAll(keep);

        // --- resolve -----------------------------------------------------
        t = System.nanoTime();
        if (state.ids == null) {
            state.ids = new IdCache(vcid, cfg.idCacheTtlMin * 60, cfg.idNegativeTtlMin * 60,
                    IdCache.PAGE_SIZE);
        }
        final IdCache ids = state.ids;
        ids.beginCycle(nowMs / 1000.0);
        ids.refreshExpired(suite, log);
        ids.resolve(suite, joined.morefs, log);
        rep.timings.put("resolve", sec(t));

        // Verify a sample of ids pushed LAST cycle, while the clusters run: the batch form
        // drops a vanished id with a 200, so this is where a deleted-and-recreated VM shows up.
        final int[][] verifyOut = {{0, 0}};
        ExecutorService pushPool = Executors.newFixedThreadPool(Math.max(1, cfg.pushConcurrency));
        ExecutorService queryPool = Executors.newFixedThreadPool(Math.max(1,
                cfg.vcenterConcurrency));
        Pusher pusher = new Pusher(suite, ids, pushPool, cfg.batchMaxObjects, cfg.batchMaxBytes,
                cfg.retryPauseMs, 2 * Math.max(1, cfg.pushConcurrency), log);
        final List<String> verifyIds = new ArrayList<>(state.verifyNext);
        Thread verifyThread = new Thread(() -> verifyOut[0] = pusher.verifySample(verifyIds,
                cfg.verifySample, rng), "vhm-verify");
        verifyThread.setDaemon(true);
        verifyThread.start();

        // --- clusters ----------------------------------------------------
        Map<String, Integer> assignment = Cadence.assignBuckets(cfg.heavy, cfg.buckets);
        List<String> due = Cadence.due(wantedEntities(cfg.skip), cycle, cfg.heavy, cfg.buckets,
                assignment);
        List<Inventory.ClusterRef> order = staggerOrder(chosen);
        long tClusters0 = System.nanoTime();
        final Semaphore pool = new Semaphore(Math.max(1, cfg.clusterConcurrency));
        List<Object[]> work = new ArrayList<>(); // {cluster, future}
        ExecutorService clusterPool = Executors.newFixedThreadPool(Math.max(1,
                cfg.clusterConcurrency));
        List<ClusterResult> results = new ArrayList<>();
        final long[] pushFirst = {0};
        try {
            for (int i = 0; i < order.size(); i++) {
                final Inventory.ClusterRef cl = order.get(i);
                final double delayS = i * cfg.staggerS / order.size();
                work.add(new Object[] {cl, clusterPool.submit(() -> clusterWork(cl, delayS,
                        tClusters0, due, nowMs, gate, queryPool, pusher, joined, rng, pushFirst))});
            }
            for (Object[] w : work) {
                try {
                    results.add((ClusterResult) ((Future<?>) w[1]).get());
                } catch (ExecutionException e) {
                    ClusterResult r = new ClusterResult(((Inventory.ClusterRef) w[0]).moid);
                    r.ok = false;
                    r.error = ((Inventory.ClusterRef) w[0]).name + ": " + e.getCause();
                    results.add(r);
                }
            }
            rep.timings.put("clusters_wall", sec(tClusters0));
            Map<String, Pusher.PushStats> pushes = new HashMap<>();
            for (ClusterResult r : results) {
                pushes.put(r.moid, r.pending == null ? new Pusher.PushStats()
                        : r.pending.result());
            }
            accounting(rep, results, pushes, chosen, nowMs, rng);
        } finally {
            clusterPool.shutdown();
            pushPool.shutdown();
            queryPool.shutdown();
            pushPool.awaitTermination(5, TimeUnit.MINUTES);
        }
        rep.timings.put("push", pushFirst[0] != 0 ? sec(pushFirst[0]) : 0.0);

        verifyThread.join(30_000);
        state.ids = ids;
        rep.counts.put("vcenterQueryBytes", rep.c("vcenterQueryBytes"));
        rep.counts.put("emptyTypesRemembered", (double) state.empty.size());
        rep.counts.put("clusters", (double) chosen.size());
        rep.counts.put("vcenterCalls", (double) (vc.calls.get() - vcCallsBefore));
        rep.counts.put("vcenterWireBytes", (double) (vc.wireBytes.get() - wireBefore));
        rep.counts.put("suiteApiCalls", (double) (suite.calls.get() - suiteCallsBefore));
        rep.counts.put("idsInvalidated", (double) ids.stats.invalidated);
        rep.counts.put("verifyMissing", (double) verifyOut[0][1]);
        rep.counts.put("verifyChecked", (double) verifyOut[0][0]);
        rep.counts.put("idCacheSize", (double) ids.size());
        rep.counts.put("vcSessionReused", genBefore > 0 && genBefore == vc.generation.get()
                ? 1.0 : 0.0);
        rep.counts.put("vcConcurrency", (double) cfg.vcenterConcurrency);
        rep.counts.put("vcConcurrencyLowest", (double) cfg.vcenterConcurrency);
        if (rep.plan.diskGuard > 0) {
            log.warn("disk guard: " + rep.plan.diskGuard + " key(s) arrived from two entity types"
                    + " for one disk this cycle; the first type's series was kept. Example: "
                    + rep.plan.diskGuardExample);
        }
        rep.timings.put("cycle", sec(t0));
        if (!chosen.isEmpty() && rep.c("clustersFailed") == chosen.size()) {
            throw new CycleException("Every cluster failed; first problems: "
                    + String.join("; ", rep.problems.subList(0, Math.min(3, rep.problems.size()))));
        }
        return rep;
    }

    private ClusterResult clusterWork(Inventory.ClusterRef cl, double delayS, long tClusters0,
            List<String> due, long nowMs, Semaphore gate, ExecutorService queryPool,
            Pusher pusher, Inventory.Joined joined, Random rng, long[] pushFirst) {
        ClusterResult r = new ClusterResult(cl.moid);
        r.name = cl.name;
        try {
            long wait = (long) (delayS * 1000) - (long) ((System.nanoTime() - tClusters0) / 1e6);
            if (wait > 0) {
                Thread.sleep(wait);
            }
            long t0 = System.nanoTime();
            DiskInventory cached = state.disks.get(cl.moid);
            List<String[]> hosts = state.inventory.hostRecords(cl);
            if (cached == null || nowMs - cached.atMs >= diskTtlMs(cfg, cl.moid)) {
                gate.acquire();
                try {
                    cached = DiskInventory.read(vc, cl.moid, cl.name, hosts, log);
                } finally {
                    gate.release();
                }
                cached.atMs = nowMs;
                r.diskCache = cached;
            }
            Map<String, Map<String, String>> parents = new HashMap<>(joined.parents);
            parents.put("disks", cached.disks);
            Map<String, String> names = joined.names;
            if (!cached.names.isEmpty()) {
                names = new HashMap<>(names);
                names.putAll(cached.names);
            }
            long t1 = System.nanoTime();
            r.tDisk = (t1 - t0) / 1e9;
            // A new host or disk may switch a feature on: forget what was remembered empty
            // for this cluster and probe everything once.
            r.sig = clusterSig(hosts, cached);
            boolean sigChanged = state.sigs.containsKey(cl.moid)
                    && !state.sigs.get(cl.moid).equals(r.sig);

            Map<String, long[]> windows = new LinkedHashMap<>();
            Map<String, Long> after = new HashMap<>();
            for (String ent : due) {
                String key = cl.moid + "|" + ent;
                Long u = state.unsupported.get(key);
                if (u != null && u > nowMs) {
                    continue;
                }
                if (state.empty.containsKey(key)) {
                    if (state.empty.get(key) > nowMs && !sigChanged) {
                        r.skippedEmpty++;
                        continue;
                    }
                    r.reprobes++;
                }
                windows.put(ent, Cadence.window(ent, nowMs, state.last.get(key), cfg.heavy,
                        cfg.buckets, cfg.maxBackfillMin));
                if (state.last.containsKey(key)) {
                    after.put(ent, state.last.get(key));
                }
            }
            r.typesQueried = windows.size();
            RowSink sink = new RowSink(after, nowMs);
            query(cl, windows, sink, gate, queryPool, r);
            sink.finish();
            r.answered.addAll(windows.keySet());
            r.answered.removeAll(r.empty);
            r.answered.removeAll(r.unsupported);
            r.answered.removeAll(r.failedTypes);
            r.rows = sink.rows;
            r.values = sink.values;
            r.newest = sink.newest;
            for (String p : sink.problems) {
                r.problems.add(cl.name + ": " + p);
            }
            long t2 = System.nanoTime();
            r.tQuery = (t2 - t1) / 1e9;

            Map<String, Planner.Target> targets = Planner.plan(sink.objects, parents, names,
                    cached.hw, new HashSet<>(MetricTree.DISK_HARDWARE_ENTITIES), r.plan);
            List<String> resolved = new ArrayList<>();
            for (Planner.Target tg : targets.values()) {
                String rid = state.ids.lookup(tg.kind.resourceKind, tg.moid);
                if (rid != null) {
                    resolved.add(rid);
                }
            }
            Collections.shuffle(resolved, rng);
            r.sampleRids = new ArrayList<>(resolved.subList(0, Math.min(20, resolved.size())));
            long t3 = System.nanoTime();
            r.tPlan = (t3 - t2) / 1e9;
            synchronized (pushFirst) {
                if (pushFirst[0] == 0) {
                    pushFirst[0] = t3;
                }
            }
            r.pending = pusher.push(targets, nowMs, null);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            r.ok = false;
            r.error = cl.name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("cluster " + cl.name + " failed: " + r.error);
        }
        return r;
    }

    /** One call per entity type, up to the gate's width in flight. */
    private void query(Inventory.ClusterRef cl, Map<String, long[]> windows, RowSink sink,
            Semaphore gate, ExecutorService queryPool, ClusterResult r)
            throws InterruptedException {
        Map<String, Future<Object[]>> futs = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> w : windows.entrySet()) {
            final String ent = w.getKey();
            final long[] win = w.getValue();
            futs.put(ent, queryPool.submit(() -> {
                gate.acquire();
                try {
                    final boolean[] seen = {false};
                    String[] labels = cfg.queryLabels ? MetricNames.of(ent) : null;
                    int wire = vc.queryPerf(cl.moid, ent, win[0], win[1], labels,
                            (ref, info, pairs) -> {
                                if (ref != null && !ref.isEmpty() && ref.indexOf('*') < 0
                                        && ref.indexOf(':') >= 0) {
                                    seen[0] = true;
                                }
                                sink.add(ref, info, pairs);
                            });
                    return new Object[] {null, wire, seen[0]};
                } catch (Exception e) {
                    return new Object[] {e, 0, false};
                } finally {
                    gate.release();
                }
            }));
        }
        for (Map.Entry<String, Future<Object[]>> f : futs.entrySet()) {
            Object[] res;
            try {
                res = f.getValue().get();
            } catch (ExecutionException e) {
                res = new Object[] {e.getCause(), 0, false};
            }
            Exception err = (Exception) res[0];
            r.wire += (Integer) res[1];
            if (err == null) {
                if (!(Boolean) res[2]) {
                    r.empty.add(f.getKey());
                }
            } else if (err instanceof VsanFault && ((VsanFault) err).isUnsupported()) {
                r.unsupported.add(f.getKey());
            } else {
                r.failedTypes.add(f.getKey());
                r.problems.add(cl.name + ": " + f.getKey() + ":*: "
                        + err.getClass().getSimpleName() + ": " + err.getMessage());
            }
        }
    }

    private void accounting(Report rep, List<ClusterResult> results,
            Map<String, Pusher.PushStats> pushes, List<Inventory.ClusterRef> chosen, long nowMs,
            Random rng) {
        List<String> sample = new ArrayList<>();
        long wire = 0;
        for (ClusterResult r : results) {
            rep.plan.merge(r.plan);
            rep.push.merge(pushes.get(r.moid));
            rep.problems.addAll(r.problems);
            if (!r.ok) {
                rep.problems.add(r.error);
                rep.add("clustersFailed", 1);
                rep.failedClusters.add(r.name == null ? r.moid : r.name);
                continue;
            }
            rep.perClusterQuery.put(r.moid, r.tQuery);
            rep.timings.merge("disk_inventory", r.tDisk, Double::sum);
            rep.timings.merge("query", r.tQuery, Double::sum);
            rep.timings.merge("plan", r.tPlan, Double::sum);
            rep.add("entityTypesQueried", r.typesQueried);
            rep.add("rows", r.rows);
            rep.add("valuesCollected", r.values);
            wire += r.wire;
            if (r.diskCache != null) {
                state.disks.put(r.moid, r.diskCache);
            }
            for (String ent : r.unsupported) {
                state.unsupported.put(r.moid + "|" + ent,
                        nowMs + (long) (cfg.unsupportedRecheckMin * 60_000));
            }
            if (cfg.emptyRecheckMin > 0) {
                state.sigs.put(r.moid, r.sig);
                for (String ent : r.empty) {
                    if (!ALWAYS_PROBE.contains(ent)) {
                        state.empty.put(r.moid + "|" + ent,
                                nowMs + (long) (cfg.emptyRecheckMin * 60_000));
                    }
                }
                for (String ent : r.answered) {
                    state.empty.remove(r.moid + "|" + ent);
                }
            }
            rep.add("callsSkippedEmpty", r.skippedEmpty);
            rep.add("emptyReprobes", r.reprobes);
            // Advance the backfill marks only when nothing was left unsent: a cluster whose
            // push stopped is re-read from the old marks next cycle.
            if (pushes.get(r.moid).skipped == 0) {
                for (Map.Entry<String, Long> e : r.newest.entrySet()) {
                    String key = r.moid + "|" + e.getKey();
                    Long prev = state.last.get(key);
                    if (prev == null || e.getValue() > prev) {
                        state.last.put(key, e.getValue());
                    }
                }
            }
            sample.addAll(r.sampleRids);
        }
        rep.timings.put("query_max", Collections.max(
                rep.perClusterQuery.isEmpty() ? Collections.singletonList(0.0)
                : rep.perClusterQuery.values()));
        rep.counts.put("vcenterQueryBytes", (double) wire);
        Collections.shuffle(sample, rng);
        state.verifyNext = new ArrayList<>(sample.subList(0, Math.min(50, sample.size())));
    }
}
