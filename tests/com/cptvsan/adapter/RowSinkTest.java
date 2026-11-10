package com.cptvsan.adapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Sample parsing: timestamps, per-mille, twins, "None", alignment, watermarks. */
public final class RowSinkTest {

    private static List<String[]> pairs(String... kv) {
        List<String[]> l = new ArrayList<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            l.add(new String[] {kv[i], kv[i + 1]});
        }
        return l;
    }

    public static void main(String[] args) {
        String ref = "vsan-pnic-net:node1|vmnic0";
        long t1 = RowSink.sampleTs("2026-11-09 10:00:00");
        long t2 = RowSink.sampleTs("2026-11-09 10:05:00");
        Check.eq(300_000L, t2 - t1, "5 minutes apart");
        Check.eq(t1, (long) RowSink.sampleTs("2026-11-09T10:00:00Z"), "ISO form accepted");
        Check.yes(RowSink.sampleTs("garbage") == null, "unparseable timestamp is null");

        RowSink s = new RowSink(null, null);
        // rxThroughput plain; rxMissErr is per-mille (x0.1); the second sample is "None"
        s.add(ref, "2026-11-09 10:00:00,2026-11-09 10:05:00",
                pairs("rxThroughput", "7,None", "rxMissErr", "3,5"));
        EntityRef key = EntityRef.parse(ref);
        RowSink.Series ser = s.objects.get(key);
        Check.eq(1, ser.samples.get("rxThroughput").size(), "None is absent, never a sentinel");
        Check.eq(7.0, ser.samples.get("rxThroughput").get(t1), "plain value");
        Check.eq(3 * 0.1, ser.samples.get("rxMissErr").get(t1), "per-mille times 0.1 exactly");
        Check.eq(5 * 0.1, ser.samples.get("rxMissErr").get(t2), "per-mille, second sample");
        Check.eq(t2, s.newest.get("vsan-pnic-net"), "newest sample kept");
        Check.eq(1, s.rows, "one row");

        // a label outside the model is dropped
        s.add(ref, "2026-11-09 10:00:00", pairs("notAMetric", "1"));
        Check.yes(!s.objects.get(key).samples.containsKey("notAMetric"), "unknown label dropped");

        // *Actual twin dropped when its base exists
        TreeEntry any = StatKeys.entry("vsan-pnic-net");
        String twin = null;
        for (String t : any.actualTwins) {
            twin = t;
        }
        if (twin != null) {
            RowSink s2 = new RowSink(null, null);
            s2.add(ref, "2026-11-09 10:00:00", pairs(twin, "1"));
            Check.yes(s2.objects.get(key).samples.isEmpty(), "twin " + twin + " dropped");
        }

        // watermark: samples at or before it are stale
        Map<String, Long> after = new HashMap<>();
        after.put("vsan-pnic-net", t1);
        RowSink w = new RowSink(after, null);
        w.add(ref, "2026-11-09 10:00:00,2026-11-09 10:05:00", pairs("rxThroughput", "1,2"));
        Check.eq(1, w.stale, "one stale sample");
        Check.eq(1, w.objects.get(key).samples.get("rxThroughput").size(), "only the newer kept");

        // misaligned lengths: aligned on the newest end, reported once
        RowSink m = new RowSink(null, null);
        m.add(ref, "2026-11-09 10:00:00,2026-11-09 10:05:00", pairs("rxThroughput", "9"));
        m.finish();
        Check.eq(9.0, m.objects.get(key).samples.get("rxThroughput").get(t2),
                "aligned on the newest sample");
        Check.eq(1, m.problems.size(), "reported");

        // no sampleInfo: last number at the fallback timestamp
        RowSink f = new RowSink(null, 12345L);
        f.add(ref, "", pairs("rxThroughput", "1,2,None"));
        Check.eq(2.0, f.objects.get(key).samples.get("rxThroughput").get(12345L), "fallback ts");

        // echo of an empty entity type is not an object; unmodelled refs are problems
        RowSink e = new RowSink(null, null);
        e.add("disk-group:*", "", pairs());
        Check.eq(0, e.objects.size(), "wildcard echo ignored");
        e.add("no-such-type:abc", "", pairs("x", "1"));
        Check.eq(1, e.problems.size(), "unmodelled entity reported");

        // number parsing: nothing Python float() would reject, nothing non-finite
        Check.yes(RowSink.number("NaN") == null, "NaN");
        Check.yes(RowSink.number("Infinity") == null, "Infinity");
        Check.yes(RowSink.number("None") == null, "None");
        Check.yes(RowSink.number("1d") == null, "Java-only suffix");
        Check.yes(RowSink.number("0x10") == null, "hex");
        Check.eq(1.5e-5, RowSink.number(" 1.5e-5 "), "exponent and whitespace");
        Check.eq(Arrays.asList(1.0), Arrays.asList(RowSink.number("1")), "integer");
        Check.done("RowSinkTest");
    }
}
