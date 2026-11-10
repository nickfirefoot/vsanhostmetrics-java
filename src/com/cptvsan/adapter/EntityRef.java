package com.cptvsan.adapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One parsed {@code entityRefId}, {@code <type>:<part>[|<part>...]}, the
 * Python pack's {@code perfsvc.parse_ref}: parts are named by the tree row's
 * identity; a ref with fewer parts than the identity is padded with
 * {@link #AGGREGATE} (host-cpu's per-host aggregate arrives without a cpu
 * part), and parts beyond the identity are kept, unnamed.
 */
final class EntityRef {

    /** Placeholder for an identity part the ref does not carry. */
    static final String AGGREGATE = "(all)";

    final TreeEntry entry;
    /** The ref as the service sent it (the watermark key). */
    final String refId;
    /** Identity values, at least {@code entry.identity.size()} of them. */
    final List<String> parts;

    private EntityRef(TreeEntry entry, String refId, List<String> parts) {
        this.entry = entry;
        this.refId = refId;
        this.parts = parts;
    }

    /**
     * Parse {@code ref} as an entity of {@code expected}; {@code null} for a
     * wildcard echo ({@code <type>:*}, an empty result), a ref of another type
     * or no type, or an empty first part.
     */
    static EntityRef parse(String ref, TreeEntry expected) {
        if (ref == null || ref.indexOf('*') >= 0) {
            return null;
        }
        int colon = ref.indexOf(':');
        if (colon <= 0 || !expected.id.equals(ref.substring(0, colon))) {
            return null;
        }
        String[] raw = ref.substring(colon + 1).split("\\|", -1);
        if (raw[0].isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>(Math.max(raw.length, expected.identity.size()));
        Collections.addAll(parts, raw);
        while (parts.size() < expected.identity.size()) {
            parts.add(AGGREGATE);
        }
        return new EntityRef(expected, ref, Collections.unmodifiableList(parts));
    }

    /** {@link #parse(String, TreeEntry)} with the type taken from the ref; null if unmodelled. */
    static EntityRef parse(String ref) {
        if (ref == null) {
            return null;
        }
        int colon = ref.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        TreeEntry e = StatKeys.entry(ref.substring(0, colon));
        return e == null ? null : parse(ref, e);
    }

    /** The value of identity part {@code name}, or {@code null}. */
    String part(String name) {
        int i = entry.identity.indexOf(name);
        return i < 0 ? null : parts.get(i);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EntityRef && ((EntityRef) o).refId.equals(refId);
    }

    @Override
    public int hashCode() {
        return refId.hashCode();
    }

    @Override
    public String toString() {
        return refId;
    }
}
