package com.cptvsan.adapter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Rule 1: a collect result never contains a foreign object or a relationship.
 *
 * <p>The adapter hands the platform exactly one thing: Collector|* metrics on
 * the adapter instance (VsanHostMetricsAdapter.runCycle calls pushMetric only
 * with the instance resource and only with {@link Collector.Report#metrics}
 * keys). The SDK jar is not available to a plain-main test, so the result
 * itself cannot be built here; what IS checked is everything that can put
 * something else in it: the report's keys, the declared kinds, and the source.
 * The scan is run against known-bad input first, to show it can fail.
 */
public final class NoForeignTest {

    static final Pattern FORBIDDEN = Pattern.compile("SuiteApiStitcher|SuiteApiStitchClient|"
            + "addChild|addParent|RelationshipBuilder|ForeignResourceResolver|new Relationships|"
            + "setRelationships|collectRelationships|registerNewResource|MetricPusher|"
            + "addNewResource|addResource|addMetricData|DiscoveryResult|changeResourceState|"
            + "addRelationships|Class\\.forName|getDeclaredMethod|setAccessible|"
            + "adapterkinds/|/relationships");

    static final String OVERRIDE_DECL =
            "public void addRelationshipsToCurrentCycle(Relationships rels) {";

    /** Lines of {@code source} that use a forbidden facility (comments excluded). */
    static List<String> violations(String source) {
        List<String> out = new ArrayList<>();
        for (String line : source.split("\n")) {
            String t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) {
                continue;
            }
            Matcher m = FORBIDDEN.matcher(line);
            if (t.equals(OVERRIDE_DECL)) {
                continue; // the one allowed occurrence, matched exactly, not by name
            }
            if (m.find()) { // no line is excused for mentioning anything, "Pusher" included
                out.add(line.trim());
            }
        }
        return out;
    }

    public static void main(String[] args) throws IOException {
        // the gate must fail on bad input
        Check.yes(!violations("SuiteApiStitcher s = SuiteApiStitcher.create(this, log);").isEmpty(),
                "scan flags the framework stitcher");
        Check.yes(!violations("rels.addChild(vmwareHost, vm);").isEmpty(), "scan flags addChild");
        Check.yes(!violations("api.post(\"api/resources/1/relationships\", b);").isEmpty(),
                "scan flags a relationship endpoint");
        Check.yes(!violations("registerNewResource(rc);").isEmpty(), "scan flags new resources");
        Check.yes(violations("// SuiteApiStitcher is never used").isEmpty(), "comments are not code");
        // the MetricPusher pattern fires, on a line that also says Pusher
        Check.yes(!violations("MetricPusher p = new MetricPusher(rc);").isEmpty(),
                "scan flags MetricPusher");
        Check.yes(!violations("Pusher pusher = new MetricPusher(api); // Pusher").isEmpty(),
                "a line mentioning Pusher is still scanned");
        Check.yes(!violations("addRelationshipsToCurrentCycle(rels);").isEmpty(),
                "a CALL of addRelationshipsToCurrentCycle is flagged");
        Check.yes(!violations("x.addRelationshipsToCurrentCycle(rels); // public void addRelationshipsToCurrentCycle(Relationships rels) {").isEmpty(),
                "mentioning the declaration on a call line does not excuse it");
        Check.yes(!violations("collectResult.addNewResource(x);").isEmpty(), "scan flags addNewResource");
        Check.yes(!violations("Class.forName(\"x\")").isEmpty(), "scan flags reflection");
        // the project's own Pusher is not excused but is clean
        Check.yes(violations("Pusher pusher = new Pusher(suite, ids, pool, 1, 1, null);").isEmpty(),
                "the project's own Pusher class is not a violation");

        // the source is clean
        List<Path> files;
        try (Stream<Path> w = Files.walk(Paths.get("src"))) {
            files = w.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
        Check.yes(files.size() > 10, "sources found");
        for (Path p : files) {
            List<String> v = violations(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            Check.eq(new ArrayList<String>(), v, "forbidden use in " + p);
        }
        String adapter = new String(Files.readAllBytes(Paths.get("src", "com", "cptvsan", "adapter",
                "VsanHostMetricsAdapter.java")), StandardCharsets.UTF_8);
        Check.yes(adapter.contains("public void addRelationshipsToCurrentCycle")
                && adapter.contains("never writes a relationship"),
                "the adapter overrides addRelationshipsToCurrentCycle to throw");
        Check.yes(!adapter.contains("discoverOnCollect()") && !adapter.contains("enumerateResources"),
                "the adapter discovers no resource");
        // pushMetric is called once, on the instance, with the report's keys
        Matcher pm = Pattern.compile("pushMetric\\(").matcher(adapter);
        int n = 0;
        while (pm.find()) {
            n++;
        }
        Check.eq(1, n, "one pushMetric call site");
        Check.yes(adapter.contains("pushMetric(inst, m.getKey(), m.getValue(), now)"),
                "self-metrics go to the adapter instance");

        // every key the report can emit is a declared Collector|* attribute
        String describe = new String(Files.readAllBytes(Paths.get("describe.xml")), StandardCharsets.UTF_8);
        Collector.Report r = new Collector.Report();
        for (String k : r.metrics().keySet()) {
            Check.yes(k.startsWith("Collector|"), "self-metric key: " + k);
            Check.yes(describe.contains("key=\"" + k.substring("Collector|".length()) + "\""),
                    "declared in describe.xml: " + k);
        }
        // describe.xml declares exactly one resource kind, the adapter instance (type 7)
        Matcher kinds = Pattern.compile("<ResourceKind ").matcher(describe);
        int kc = 0;
        while (kinds.find()) {
            kc++;
        }
        Check.eq(1, kc, "one ResourceKind");
        Check.yes(describe.contains("<ResourceKind key=\"vsanhostmetrics\" nameKey=\"4\" type=\"7\""),
                "and it is the adapter instance");
        Check.yes(!describe.contains("TraversalSpec") && !describe.contains("Relationship"),
                "no traversal and no relationship declared");
        Check.yes(!describe.contains("key=\"World\""), "no World");
        // describe.xml's declared self-metrics equal the Python pack's
        Matcher attrs = Pattern.compile("<ResourceAttribute key=\"([^\"]+)\"").matcher(describe);
        List<String> declared = new ArrayList<>();
        while (attrs.find()) {
            declared.add(attrs.group(1));
        }
        Check.eq(new ArrayList<>(CollectorKeys.METRIC_KEYS.keySet()), declared,
                "declared attributes are the Python Collector keys, in order");
        Check.eq(46, declared.size(), "46 self-metrics");
        Check.done("NoForeignTest");
    }
}
