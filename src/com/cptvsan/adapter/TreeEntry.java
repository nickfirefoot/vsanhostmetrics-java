package com.cptvsan.adapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One row of the metric tree ({@link MetricTree}): a vSAN Performance Service
 * entity type, the VMWARE kind its data lands on, the group path under
 * {@code cptvsan|}, and the instance template naming identity parts in braces
 * ({@code "core-{cpu}"}), or {@code null} for a non-instanced group.
 */
final class TreeEntry {

    final String id;
    final TargetKind target;
    final String group;
    /** {@code null}: not instanced. */
    final String instanceTemplate;
    /** Identity part names, in {@code entityRefId} order after the type. */
    final List<String> identity;
    /** HEAVY cadence: queried every {@link Schedule#HEAVY_EVERY}th cycle with backfill. */
    final boolean heavy;
    /** Returns data only when an optional vSAN feature is in use. */
    final boolean featureGated;
    /** {@code <x>Actual} labels whose {@code <x>} is also a metric: same value, dropped. */
    final Set<String> actualTwins;

    /** Parsed template: literal text and part indexes, alternating. */
    private final List<Object> segments;

    TreeEntry(String id, TargetKind target, String group, String instanceTemplate,
            String[] identity, boolean heavy, boolean featureGated, String[] actualTwins) {
        this.id = id;
        this.target = target;
        this.group = group;
        this.instanceTemplate = instanceTemplate;
        this.identity = Collections.unmodifiableList(Arrays.asList(identity));
        this.heavy = heavy;
        this.featureGated = featureGated;
        this.actualTwins = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(actualTwins)));
        this.segments = instanceTemplate == null ? null : parse(id, instanceTemplate, this.identity);
    }

    private static List<Object> parse(String id, String template, List<String> identity) {
        List<Object> out = new ArrayList<>();
        int i = 0;
        while (i < template.length()) {
            int a = template.indexOf('{', i);
            if (a < 0) {
                out.add(template.substring(i));
                break;
            }
            if (a > i) {
                out.add(template.substring(i, a));
            }
            int b = template.indexOf('}', a);
            if (b < 0) {
                throw new IllegalStateException(id + ": unclosed '{' in " + template);
            }
            int part = identity.indexOf(template.substring(a + 1, b));
            if (part < 0) {
                throw new IllegalStateException(id + ": template part "
                        + template.substring(a + 1, b) + " is not an identity part");
            }
            out.add(part);
            i = b + 1;
        }
        return out;
    }

    boolean instanced() {
        return segments != null;
    }

    /** Whether the template names identity part {@code name}. */
    boolean templateUses(String name) {
        return instanceTemplate != null && instanceTemplate.contains("{" + name + "}");
    }

    /**
     * The instance name before sanitizing, from the parsed identity values
     * ({@link EntityRef#parts}); {@code null} when not instanced.
     */
    String rawInstance(List<String> parts) {
        if (segments == null) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        for (Object s : segments) {
            if (s instanceof Integer) {
                b.append(parts.get((Integer) s));
            } else {
                b.append((String) s);
            }
        }
        return b.toString();
    }

    @Override
    public String toString() {
        return id;
    }
}
