#!/usr/bin/env bash
# Prove ci/no_relationships.sh can fail: copy src/, inject one bad line at a
# time, and require the gate to exit non-zero on each; require it to PASS on
# the untouched copy and on a comment that merely mentions a banned name.
set -uo pipefail
cd "$(dirname "$0")/.."
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
ad=com/cptvsan/adapter
status=0
try() { # name file injected-line expect(fail|pass)
  rm -rf "$work/src"; cp -r src "$work/src"
  if [ -n "$3" ]; then
    # inject before the final closing brace of the file
    f="$work/src/$ad/$2"
    head -n -1 "$f" > "$f.new"; printf '%s\n}\n' "$3" >> "$f.new"; mv "$f.new" "$f"
  fi
  if ci/no_relationships.sh "$work/src" >"$work/out" 2>&1; then got=pass; else got=fail; fi
  if [ "$got" = "$4" ]; then echo "ok   [$got as required] $1"; else echo "BAD  [$got, wanted $4] $1"; cat "$work/out"; status=1; fi
}
try "untouched copy passes"            Collector.java ""                                                   pass
try "comment mentioning a name passes" Collector.java "    // collectResult.addNewResource(x) is never used" pass
try "addNewResource injected"          Collector.java "    void bad(Object collectResult, Object x) { collectResult.addNewResource(x); }" fail
try "addMetricData injected"           Pusher.java    "    void bad(Object r) { r.addMetricData(null); }"      fail
try "reflection (Class.forName)"       Collector.java "    static Object bad() throws Exception { return Class.forName(\"x\"); }" fail
try "reflection (getDeclaredMethod)"   Collector.java "    static Object bad() throws Exception { return Object.class.getDeclaredMethod(\"x\"); }" fail
try "reflection (setAccessible)"       Collector.java "    static void bad(java.lang.reflect.Method m) { m.setAccessible(true); }" fail
try "addRelationshipsToCurrentCycle as a call" VsanHostMetricsAdapter.java "    void bad(Relationships r) { addRelationshipsToCurrentCycle(r); }" fail
try "DiscoveryResult use"              Collector.java "    Object bad(DiscoveryResult d) { return d; }"        fail
try "stitcher on a Pusher line"        Pusher.java    "    Object bad = SuiteApiStitcher.class; // Pusher" fail
try "second suite.post site"           Collector.java "    void bad(SuiteApi suite) throws Exception { suite.post(\"api/resources/stats\", \"{}\"); }" fail
try "second Net.call(suiteOpener()"    VsanHostMetricsAdapter.java "    void bad() throws Exception { Net.call(suiteOpener(), \"POST\", \"https://localhost/suite-api/api/resources/x/relationships\", \"{}\", null, false, 1); }" fail
try "second Net.call anywhere"         Collector.java "    void bad(Net.Opener o) throws Exception { Net.call(o, \"GET\", \"u\", null, null, false, 1); }" fail
exit $status
