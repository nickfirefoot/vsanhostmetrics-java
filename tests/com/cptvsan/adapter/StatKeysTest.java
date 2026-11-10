package com.cptvsan.adapter;

import java.util.Arrays;

/** Instance sanitising, the aggregate instance and the prefix guard. */
public final class StatKeysTest {

    public static void main(String[] args) {
        Check.eq("a-b-c", StatKeys.sanitizeInstance("a:b|c"), "':' and '|' become '-'");
        Check.eq("vmnic0", StatKeys.sanitizeInstance("vmnic0"), "plain unchanged");
        Check.eq("a b/c", StatKeys.sanitizeInstance("a b/c"), "nothing else changes");
        Check.fails(IllegalArgumentException.class, () -> StatKeys.sanitizeInstance(""),
                "empty instance refused");

        TreeEntry pnic = StatKeys.entry("vsan-pnic-net");
        Check.eq("cptvsan|Network|Physical NIC:vmnic0|rxThroughput",
                StatKeys.key(pnic, Arrays.asList("n1", "vmnic0"), "rxThroughput"), "pnic key");
        Check.eq("cptvsan|Network|Physical NIC:vm-nic0|rxThroughput",
                StatKeys.key(pnic, Arrays.asList("n1", "vm:nic0"), "rxThroughput"),
                "instance with ':' is sanitised");
        Check.eq("cptvsan|Network|Physical NIC:vm-nic0|x",
                StatKeys.key(pnic, Arrays.asList("n1", "vm|nic0"), "x"), "instance with '|'");

        TreeEntry cpu = StatKeys.entry("host-cpu");
        Check.eq("cptvsan|CPU:core-3|coreUtilPct",
                StatKeys.key(cpu, Arrays.asList("h", "3"), "coreUtilPct"), "per-core instance");
        Check.eq("cptvsan|CPU:cores-total|coreUtilPct",
                StatKeys.key(cpu, Arrays.asList("h", EntityRef.AGGREGATE), "coreUtilPct"),
                "the host-wide aggregate row is cores-total");
        TreeEntry dom = StatKeys.entry("cluster-domowner");
        Check.eq("cptvsan|Storage I/O:dom-owner|m", StatKeys.key(dom, Arrays.asList("c"), "m"),
                "fixed instance");
        TreeEntry flat = StatKeys.entry("cluster-resync");
        Check.eq("cptvsan|Resync|m", StatKeys.key(flat, Arrays.asList("c"), "m"), "no instance");

        Check.fails(IllegalArgumentException.class,
                () -> StatKeys.key(flat, Arrays.asList("c"), "a|b"), "metric with '|' refused");
        Check.fails(IllegalArgumentException.class,
                () -> StatKeys.key(flat, Arrays.asList("c"), "a:b"), "metric with ':' refused");
        Check.fails(IllegalStateException.class, () -> StatKeys.requirePrefix("other|x|y"),
                "a key outside the prefix is refused");
        Check.fails(IllegalStateException.class, () -> StatKeys.requirePrefix("cptvsanx|y"),
                "a look-alike prefix is refused");
        Check.fails(IllegalStateException.class, () -> StatKeys.requirePrefix("cptvsan|"),
                "an empty remainder is refused");
        Check.fails(IllegalStateException.class, () -> Pusher.jkey("VMWARE|x"),
                "the pusher refuses a foreign key");
        Check.done("StatKeysTest");
    }
}
