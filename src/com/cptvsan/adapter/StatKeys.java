package com.cptvsan.adapter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds every attribute key this pack writes, from {@link MetricTree} only:
 * {@code cptvsan|<group>[:<instance>]|<metric>}, the same keys as the Python
 * pack's {@code app/metric_tree.py build_key}.
 *
 * <p>The prefix is the pack's whole footprint on objects it does not own, so
 * it is checked at the one place keys leave ({@link #requirePrefix}) rather
 * than trusted. No SDK dependency.
 */
final class StatKeys {

    static final String PREFIX = "cptvsan|";

    /** Freshness property, epoch ms of the cycle that wrote the target. */
    static final String LAST_UPDATED = PREFIX + "Collector|lastUpdated";

    private static final Map<String, TreeEntry> BY_ID = new HashMap<>();

    static {
        for (TreeEntry e : MetricTree.ENTRIES) {
            BY_ID.put(e.id, e);
        }
    }

    /**
     * An entity row that carries no value for an identity part (host-cpu's
     * host-wide aggregate next to the per-core rows) is keyed under a
     * readable instance, as the Python pack's {@code AGGREGATE_INSTANCE}.
     */
    static final Map<String, String> AGGREGATE_INSTANCE = new HashMap<>();

    static {
        AGGREGATE_INSTANCE.put("host-cpu", "cores-total");
    }

    private StatKeys() {
    }

    /** The tree row for an entity type, or {@code null} when not in the tree. */
    static TreeEntry entry(String entityType) {
        return BY_ID.get(entityType);
    }

    /** {@code ':'} and {@code '|'} become {@code '-'}; nothing else changes. */
    static String sanitizeInstance(String instance) {
        if (instance == null || instance.isEmpty()) {
            throw new IllegalArgumentException("instance name is empty");
        }
        return instance.replace(':', '-').replace('|', '-');
    }

    /**
     * The key group with its instance, e.g. {@code Storage I/O:dom-owner}:
     * everything between the prefix and the metric. Built once per entity and
     * reused for each of its metrics.
     */
    static String groupPath(TreeEntry e, List<String> parts) {
        if (e.instanced() && AGGREGATE_INSTANCE.containsKey(e.id)
                && parts.contains(EntityRef.AGGREGATE)) {
            return e.group + ":" + sanitizeInstance(AGGREGATE_INSTANCE.get(e.id));
        }
        String raw = e.rawInstance(parts);
        return raw == null ? e.group : e.group + ":" + sanitizeInstance(raw);
    }

    /** The full key for one metric; {@code metric} must not contain ':' or '|'. */
    static String key(String groupPath, String metric) {
        if (metric == null || metric.isEmpty()) {
            throw new IllegalArgumentException("metric label is empty");
        }
        if (metric.indexOf(':') >= 0 || metric.indexOf('|') >= 0) {
            throw new IllegalArgumentException("metric label contains a key separator: " + metric);
        }
        return requirePrefix(PREFIX + groupPath + "|" + metric);
    }

    /** {@link #groupPath} then {@link #key}. */
    static String key(TreeEntry e, List<String> parts, String metric) {
        return key(groupPath(e, parts), metric);
    }

    /**
     * {@code key} unchanged if it lies inside the {@code cptvsan|} namespace
     * and its first segment is exactly {@code cptvsan}; otherwise throw. Every
     * key handed to the Suite API passes through here.
     */
    static String requirePrefix(String key) {
        if (key == null || !key.startsWith(PREFIX) || key.length() == PREFIX.length()) {
            throw new IllegalStateException("refusing to write key outside the "
                    + PREFIX + " namespace: " + key);
        }
        return key;
    }
}
