package com.cptvsan.adapter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Writes planned targets onto the VMWARE objects they describe through the
 * Suite API stats and properties endpoints, and nothing else:
 *
 * <pre>
 *   POST api/resources/stats        {"resource-stat-content": [...]}   (batch)
 *   POST api/resources/properties   {"values": [...]}                  (batch)
 *   POST api/resources/{id}/stats       (per-resource fallback only)
 *   POST api/resources/{id}/properties  (per-resource fallback only)
 * </pre>
 *
 * None carries a relationship, so none can replace a child list. The
 * adapter-kind push variants ({@code api/resources/adapterkinds/...}) are never
 * used: they create the target as an OPENAPI-kind object instead of writing
 * onto the real one ({@link #guardUrl}). The Python pack's
 * {@code push.BatchPusher}.
 *
 * <p>The batch form drops an unknown id silently and answers 200, so a 2xx
 * says "accepted", not "landed": ids are resolved before sending, the
 * unresolved are counted and never sent, and a sample of pushed ids is
 * re-read every cycle ({@link #verifySample}).
 */
final class Pusher {

    static final String BATCH_STATS_URL = "api/resources/stats";
    static final String BATCH_PROPS_URL = "api/resources/properties";

    /** Counters of one push. */
    static final class PushStats {
        int targets;
        int resolved;
        int unresolved;
        int ok;
        int failed;
        long stats;
        long props;
        int httpCalls;
        int batches;
        int batchFailed;
        int fallbackPosts;
        int notFound;
        int skipped;
        long bytesSent;

        void merge(PushStats o) {
            targets += o.targets;
            resolved += o.resolved;
            unresolved += o.unresolved;
            ok += o.ok;
            failed += o.failed;
            stats += o.stats;
            props += o.props;
            httpCalls += o.httpCalls;
            batches += o.batches;
            batchFailed += o.batchFailed;
            fallbackPosts += o.fallbackPosts;
            notFound += o.notFound;
            skipped += o.skipped;
            bytesSent += o.bytesSent;
        }
    }

    static final class Entry {
        final String rid;
        final String json;
        final int values;
        final Planner.Target target;

        Entry(String rid, String json, int values, Planner.Target target) {
            this.rid = rid;
            this.json = json;
            this.values = values;
            this.target = target;
        }
    }

    private static final class ChunkResult {
        final Set<String> bad = new HashSet<>();
        long values;
        int httpCalls;
        int batches;
        int batchFailed;
        int fallbackPosts;
        int notFound;
        int skipped;
        long bytesSent;
    }

    private final SuiteApi api;
    private final IdCache ids;
    private final ExecutorService executor;
    private final Semaphore outstanding;
    private final int maxObjects;
    private final int maxBytes;
    private final long retryPauseMs;
    private final Log log;
    private final AtomicBoolean stopped = new AtomicBoolean();

    Pusher(SuiteApi api, IdCache ids, ExecutorService executor, int maxObjects, int maxBytes,
            long retryPauseMs, int maxOutstanding, Log log) {
        this.api = api;
        this.ids = ids;
        this.executor = executor;
        this.outstanding = new Semaphore(Math.max(1, maxOutstanding));
        this.maxObjects = Math.max(1, maxObjects);
        this.maxBytes = Math.max(1, maxBytes);
        this.retryPauseMs = retryPauseMs;
        this.log = log;
    }

    // ------------------------------------------------------------------
    // Content
    // ------------------------------------------------------------------

    static void guardUrl(String url) {
        String u = url.toLowerCase(java.util.Locale.ROOT);
        for (String bad : new String[] {"adapterkinds", "relationships"}) {
            if (u.contains(bad)) {
                throw new IllegalStateException("refusing to POST " + url + ": relationship endpoints are never written");
            }
        }
    }

    static String jkey(String key) {
        StatKeys.requirePrefix(key);
        return Json.quote(key);
    }

    /** The stat-content array for one resource, and its value count (index 1 as a holder). */
    static String statContent(Map<String, TreeMap<Long, Double>> stats, int[] count) {
        StringBuilder b = new StringBuilder("[");
        int n = 0;
        boolean first = true;
        for (Map.Entry<String, TreeMap<Long, Double>> e : new TreeMap<>(stats).entrySet()) {
            StringBuilder ts = new StringBuilder();
            StringBuilder data = new StringBuilder();
            int k = 0;
            for (Map.Entry<Long, Double> s : e.getValue().entrySet()) {
                double v = s.getValue();
                if (Double.isNaN(v) || Double.isInfinite(v)) {
                    continue; // would make the whole body invalid JSON
                }
                ts.append(k == 0 ? "" : ",").append(s.getKey());
                data.append(k == 0 ? "" : ",").append(Double.toString(v));
                k++;
            }
            if (k == 0) {
                continue;
            }
            n += k;
            b.append(first ? "" : ",").append("{\"statKey\":").append(jkey(e.getKey()))
                    .append(",\"timestamps\":[").append(ts).append("],\"data\":[").append(data)
                    .append("]}");
            first = false;
        }
        count[0] = n;
        return b.append(']').toString();
    }

    static String propertyContent(Map<String, String> props, long tsMs) {
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(props).entrySet()) {
            b.append(first ? "" : ",").append("{\"statKey\":").append(jkey(e.getKey()))
                    .append(",\"timestamps\":[").append(tsMs).append("],\"values\":[")
                    .append(Json.quote(e.getValue())).append("]}");
            first = false;
        }
        return b.append(']').toString();
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    private Integer post(String url, String body) {
        guardUrl(url);
        try {
            return api.post(url, body).status;
        } catch (IOException e) {
            log.warn("POST " + url + " failed: " + e.getClass().getSimpleName());
            return null;
        }
    }

    private ChunkResult sendChunk(boolean isStats, List<Entry> chunk, long tsMs) {
        ChunkResult r = new ChunkResult();
        Set<String> rids = new HashSet<>();
        for (Entry e : chunk) {
            rids.add(e.rid);
        }
        if (stopped.get()) {
            r.skipped += chunk.size();
            r.bad.addAll(rids);
            return r;
        }
        String url = isStats ? BATCH_STATS_URL : BATCH_PROPS_URL;
        StringBuilder body = new StringBuilder(isStats ? "{\"resource-stat-content\":["
                : "{\"values\":[");
        for (int i = 0; i < chunk.size(); i++) {
            body.append(i == 0 ? "" : ",").append(chunk.get(i).json);
        }
        body.append("]}");
        Integer status = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            r.httpCalls++;
            r.batches++;
            r.bytesSent += body.length();
            status = post(url, body.toString());
            if (status != null && status < 300) {
                for (Entry e : chunk) {
                    r.values += e.values;
                }
                return r;
            }
            r.batchFailed++;
            if (status != null && status >= 400 && status < 500 && status != 401
                    && status != 429) {
                break; // isolate per resource below
            }
            if (attempt == 0) {
                sleep(retryPauseMs);
            }
        }
        if (status == null || status >= 500 || status == 401 || status == 429) {
            log.warn("batch " + url + " of " + chunk.size() + " resources failed twice (HTTP "
                    + status + "); nothing more is sent this cycle");
            stopped.set(true);
            r.skipped += chunk.size();
            r.bad.addAll(rids);
            return r;
        }
        log.warn("batch " + url + " of " + chunk.size() + " resources rejected (HTTP " + status
                + "); re-sending each on the per-resource path");
        for (Entry e : chunk) {
            String one;
            String oneUrl;
            if (isStats) {
                int[] c = new int[1];
                one = "{\"stat-content\":" + statContent(e.target.stats, c) + "}";
                oneUrl = "api/resources/" + e.rid + "/stats";
            } else {
                one = "{\"property-content\":" + propertyContent(e.target.props, tsMs) + "}";
                oneUrl = "api/resources/" + e.rid + "/properties";
            }
            r.httpCalls++;
            r.fallbackPosts++;
            Integer st = post(oneUrl, one);
            if (st != null && st < 300) {
                r.values += e.values;
            } else {
                r.bad.add(e.rid);
                if (st != null && st == 404) {
                    r.notFound++;
                    ids.invalidate(e.rid);
                }
            }
        }
        return r;
    }

    private static void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    List<List<Entry>> chunks(List<Entry> entries) {
        List<List<Entry>> out = new ArrayList<>();
        List<Entry> cur = new ArrayList<>();
        int size = 32;
        for (Entry e : entries) {
            int n = e.json.length() + 1;
            if (!cur.isEmpty() && (cur.size() >= maxObjects || size + n > maxBytes)) {
                out.add(cur);
                cur = new ArrayList<>();
                size = 32;
            }
            cur.add(e);
            size += n;
        }
        if (!cur.isEmpty()) {
            out.add(cur);
        }
        return out;
    }

    /** One push in flight; {@link #result()} waits and folds it. */
    final class Pending {
        private final PushStats ps;
        private final List<Object[]> futures = new ArrayList<>(); // {Boolean isStats, Future|ChunkResult}

        Pending(PushStats ps) {
            this.ps = ps;
        }

        PushStats result() throws InterruptedException {
            Set<String> bad = new HashSet<>();
            for (Object[] f : futures) {
                ChunkResult r;
                try {
                    r = f[1] instanceof Future ? ((ChunkResult) ((Future<?>) f[1]).get())
                            : (ChunkResult) f[1];
                } catch (ExecutionException e) {
                    throw new IllegalStateException(e.getCause());
                }
                if ((Boolean) f[0]) {
                    ps.stats += r.values;
                } else {
                    ps.props += r.values;
                }
                ps.httpCalls += r.httpCalls;
                ps.batches += r.batches;
                ps.batchFailed += r.batchFailed;
                ps.fallbackPosts += r.fallbackPosts;
                ps.notFound += r.notFound;
                ps.skipped += r.skipped;
                ps.bytesSent += r.bytesSent;
                bad.addAll(r.bad);
            }
            futures.clear();
            ps.failed = bad.size();
            ps.ok = ps.resolved - ps.failed;
            return ps;
        }
    }

    /**
     * Resolve every target from the id cache and send its stats and
     * properties. The freshness key goes onto every target as a property, so a
     * stale target is visible from its own properties. With an executor the
     * chunks run in parallel and this blocks only while the outstanding is full
     * (backpressure); without one they run here.
     */
    Pending push(Map<String, Planner.Target> targets, long freshTs, Set<String> resolvedOut)
            throws InterruptedException {
        PushStats ps = new PushStats();
        ps.targets = targets.size();
        List<Entry> statEntries = new ArrayList<>();
        List<Entry> propEntries = new ArrayList<>();
        for (Planner.Target t : targets.values()) {
            String rid = ids.lookup(t.kind.resourceKind, t.moid);
            if (rid == null) {
                ps.unresolved++;
                continue;
            }
            ps.resolved++;
            if (resolvedOut != null) {
                resolvedOut.add(rid);
            }
            if (!t.stats.isEmpty()) {
                int[] n = new int[1];
                String arr = statContent(t.stats, n);
                if (n[0] > 0) {
                    statEntries.add(new Entry(rid, "{\"id\":" + Json.quote(rid)
                            + ",\"stat-contents\":" + arr + "}", n[0], t));
                }
            }
            Planner.Target withFresh = new Planner.Target(t.kind, t.moid);
            withFresh.props.putAll(t.props);
            withFresh.props.put(StatKeys.LAST_UPDATED, String.valueOf(freshTs));
            propEntries.add(new Entry(rid, "{\"resourceId\":" + Json.quote(rid)
                    + ",\"property-contents\":{\"property-content\":"
                    + propertyContent(withFresh.props, freshTs) + "}}", withFresh.props.size(),
                    withFresh));
        }
        Pending pending = new Pending(ps);
        List<Object[]> jobs = new ArrayList<>();
        for (List<Entry> c : chunks(propEntries)) {
            jobs.add(new Object[] {Boolean.FALSE, c});
        }
        for (List<Entry> c : chunks(statEntries)) {
            jobs.add(new Object[] {Boolean.TRUE, c});
        }
        for (Object[] j : jobs) {
            final boolean isStats = (Boolean) j[0];
            @SuppressWarnings("unchecked")
            final List<Entry> chunk = (List<Entry>) j[1];
            if (executor != null) {
                outstanding.acquire();
                Callable<ChunkResult> task = () -> {
                    try {
                        return sendChunk(isStats, chunk, freshTs);
                    } finally {
                        outstanding.release();
                    }
                };
                pending.futures.add(new Object[] {isStats, executor.submit(task)});
            } else {
                pending.futures.add(new Object[] {isStats, sendChunk(isStats, chunk, freshTs)});
            }
        }
        return pending;
    }

    /**
     * GET api/resources/{id} for up to {@code k} of {@code rids}. The batch
     * endpoints drop a vanished id with a 200, so this is the only place a
     * deleted-and-recreated VM shows up; a 404 invalidates the entry. Returns
     * {checked, missing}.
     */
    int[] verifySample(List<String> rids, int k, java.util.Random rng) {
        if (k <= 0 || rids.isEmpty()) {
            return new int[] {0, 0};
        }
        List<String> pool = new ArrayList<>(rids);
        java.util.Collections.shuffle(pool, rng);
        List<String> pick = pool.subList(0, Math.min(k, pool.size()));
        int missing = 0;
        for (String rid : pick) {
            try {
                if (api.get("api/resources/" + rid).status == 404) {
                    missing++;
                    ids.invalidate(rid);
                }
            } catch (IOException e) {
                log.info("verify " + rid + ": " + e.getClass().getSimpleName());
            }
        }
        return new int[] {pick.size(), missing};
    }
}
