package com.cptvsan.adapter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Turns one entity result at a time into {@link Series}, every sample with
 * the timestamp {@code sampleInfo} gives it: the Python pack's
 * {@code perfsvc.RowSink}. The service reduces metrics into fixed 5-minute
 * buckets, so there is no delta to compute and no baseline to keep.
 *
 * <p>{@code after} is {entity type: epoch ms}: samples at or before it were
 * pushed by an earlier cycle and are skipped. A value list whose length
 * differs from sampleInfo is aligned on the NEWEST end and reported once.
 * {@code "None"} (the API's missing sample), blanks and junk are absent,
 * never a sentinel and never 0. Per-mille labels are converted to percent
 * ({@code * 0.1}, exactly as Python's RowSink). Thread safety is the caller's.
 */
final class RowSink {

    /** All samples of one object: metric to {epoch ms: value}. */
    static final class Series {
        final Map<String, TreeMap<Long, Double>> samples = new LinkedHashMap<>();
    }

    private final Map<String, Long> after;
    private final Long fallbackTs;
    final Map<EntityRef, Series> objects = new LinkedHashMap<>();
    final List<String> problems = new ArrayList<>();
    /** entity type to the newest sample time kept. */
    final Map<String, Long> newest = new HashMap<>();
    int rows;
    int values;
    int stale;
    private int misaligned;
    private final Map<String, Set<String>> allowed = new HashMap<>();

    RowSink(Map<String, Long> after, Long fallbackTs) {
        this.after = after == null ? new HashMap<String, Long>() : after;
        this.fallbackTs = fallbackTs;
    }

    private Set<String> allowedFor(TreeEntry e) {
        Set<String> a = allowed.get(e.id);
        if (a == null) {
            a = new HashSet<>();
            for (String m : MetricNames.of(e.id)) {
                a.add(m);
            }
            allowed.put(e.id, a);
        }
        return a;
    }

    private static final Pattern NUMBER =
            Pattern.compile("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$");

    /** Parse one value token; null for anything Python's float() would reject or that is not finite. */
    static Double number(String tok) {
        String t = tok.trim();
        if (!NUMBER.matcher(t).matches()) {
            return null;
        }
        double v = Double.parseDouble(t);
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    synchronized void add(String ref, String info, List<String[]> pairs) {
        if (ref == null || ref.isEmpty() || ref.indexOf('*') >= 0) {
            return; // an entity type with no objects echoes the query back: "disk-group:*"
        }
        EntityRef key = EntityRef.parse(ref);
        if (key == null) {
            problems.add("unmodeled entityRefId: " + ref);
            return;
        }
        TreeEntry entry = key.entry;
        String entity = entry.id;
        rows++;
        Set<String> ok = allowedFor(entry);
        long cut = after.containsKey(entity) ? after.get(entity) : -1L;
        List<Long> stamps = null;
        if (info != null && !info.isEmpty()) {
            stamps = new ArrayList<>();
            for (String t : info.split(",", -1)) {
                stamps.add(sampleTs(t));
            }
        }
        Series series = objects.get(key);
        if (series == null) {
            series = new Series();
            objects.put(key, series);
        }
        long nw = newest.containsKey(entity) ? newest.get(entity) : -1L;
        for (String[] p : pairs) {
            String label = p[0];
            String raw = p[1];
            if (label == null || label.isEmpty() || raw == null || raw.isEmpty()
                    || entry.actualTwins.contains(label) || !ok.contains(label)) {
                continue;
            }
            double scale = MetricTree.PERMILLE.contains(label) ? 0.1 : 1.0;
            TreeMap<Long, Double> got = new TreeMap<>();
            if (stamps == null) {
                Double last = lastNumber(raw);
                if (last != null && fallbackTs != null && fallbackTs > cut) {
                    got.put(fallbackTs, last * scale);
                }
            } else {
                String[] toks = raw.split(",", -1);
                List<Long> ts = stamps;
                if (toks.length != stamps.size()) {
                    misaligned++;
                    int n = Math.min(toks.length, stamps.size());
                    String[] t2 = new String[n];
                    System.arraycopy(toks, toks.length - n, t2, 0, n);
                    toks = t2;
                    ts = stamps.subList(stamps.size() - n, stamps.size());
                }
                for (int i = 0; i < toks.length; i++) {
                    Long t = ts.get(i);
                    if (t == null) {
                        continue;
                    }
                    if (t <= cut) {
                        stale++;
                        continue;
                    }
                    Double v = number(toks[i]);
                    if (v == null) {
                        continue; // "None": absent, never a sentinel
                    }
                    got.put(t, v * scale);
                }
            }
            if (!got.isEmpty()) {
                TreeMap<Long, Double> dst = series.samples.get(label);
                if (dst == null) {
                    dst = new TreeMap<>();
                    series.samples.put(label, dst);
                }
                dst.putAll(got);
                values += got.size();
                long m = got.lastKey();
                if (m > nw) {
                    nw = m;
                }
            }
        }
        if (nw >= 0) {
            newest.put(entity, nw);
        }
    }

    /** Report problems that were counted, once. */
    synchronized void finish() {
        if (misaligned > 0) {
            problems.add(misaligned + " series had a different number of values than sampleInfo"
                    + " timestamps (aligned on the newest sample)");
            misaligned = 0;
        }
    }

    static Double lastNumber(String csv) {
        String[] t = csv.split(",", -1);
        for (int i = t.length - 1; i >= 0; i--) {
            if (t[i].trim().isEmpty()) {
                continue;
            }
            Double v = number(t[i]);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static final Map<String, Long> TS_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final DateTimeFormatter[] FORMATS = {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"),
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
    };

    /**
     * One sampleInfo entry to epoch ms (UTC); null if unparseable. The service
     * writes {@code YYYY-MM-DD HH:MM:SS} with no zone, in UTC; ISO-8601 with a
     * {@code T} is accepted too.
     */
    static Long sampleTs(String text) {
        if (text == null) {
            return null;
        }
        Long hit = TS_CACHE.get(text);
        if (hit != null) {
            return hit == Long.MIN_VALUE ? null : hit;
        }
        String s = text.trim().replace('T', ' ');
        while (s.endsWith("Z")) {
            s = s.substring(0, s.length() - 1);
        }
        Long v = null;
        for (DateTimeFormatter f : FORMATS) {
            try {
                v = LocalDateTime.parse(s, f).toInstant(ZoneOffset.UTC).toEpochMilli();
                break;
            } catch (java.time.format.DateTimeParseException e) {
                // next format
            }
        }
        if (TS_CACHE.size() > 100_000) {
            TS_CACHE.clear();
        }
        TS_CACHE.put(text, v == null ? Long.MIN_VALUE : v);
        return v;
    }
}
