#!/usr/bin/env bash
# Fail the build if the adapter source can create, remove or replace an object
# or a relationship, reaches the platform by reflection, or writes to the Suite
# API anywhere but the one expected place (relationship writes replace an object's whole child list).
# Usage: ci/no_relationships.sh [source-dir]   (default: src; the tests point
# it at a doctored copy to prove it fails). Exit 0 = clean.
#
# Comments (block comments, and lines that start with //) are removed before
# matching. NOTHING else is excused: no file and no line is skipped because it
# mentions a name. The only allowed occurrence of a banned name is listed in
# EXACT SITES below and is counted, so a second one fails.
set -euo pipefail
cd "$(dirname "$0")/.."
src="${1:-src}"
tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

# path:line:code, comments blanked, for every source file
find "$src" -name '*.java' | sort | while read -r f; do
  awk -v F="$f" '
    { line = $0; out = ""
      while (length(line) > 0) {
        if (inblk) { i = index(line, "*/"); if (i == 0) { line = "" } else { line = substr(line, i + 2); inblk = 0 } }
        else { i = index(line, "/*"); if (i == 0) { out = out line; line = "" } else { out = out substr(line, 1, i - 1); line = substr(line, i + 2); inblk = 1 } }
      }
      t = out; sub(/^[ \t]+/, "", t)
      if (t ~ /^\/\//) out = ""
      if (out ~ /[^ \t]/) print F ":" FNR ":" out }' "$f"
done > "$tmp"

fail=0
# 1. banned names, anywhere in code
BANNED='addNewResource|addResource|addMetricData|DiscoveryResult|changeResourceState|addRelationships|SuiteApiStitcher|SuiteApiStitchClient|addChild|addParent|addChildren|RelationshipBuilder|ForeignResourceResolver|new Relationships|setRelationships|collectRelationships|registerNewResource|MetricPusher|Class\.forName|getDeclaredMethod|getDeclaredField|getMethod\(|setAccessible|java\.lang\.reflect|MethodHandle|adapterkinds/|/relationships'
bad=$(grep -E "$BANNED" "$tmp" \
  | grep -vE '^[^:]+VsanHostMetricsAdapter\.java:[0-9]+:\s*public void addRelationshipsToCurrentCycle\(Relationships rels\) \{\s*$' || true)
if [ -n "$bad" ]; then echo "FAIL: forbidden object/relationship/reflection use:"; echo "$bad"; fail=1; fi

# EXACT SITES: the throwing override's declaration, once, in the adapter
n=$(grep -cE 'addRelationshipsToCurrentCycle' "$tmp" || true)
[ "$n" -eq 1 ] || { echo "FAIL: addRelationshipsToCurrentCycle must occur exactly once (the throwing override's declaration), found $n"; fail=1; }
grep -E 'VsanHostMetricsAdapter\.java:[0-9]+:\s*public void addRelationshipsToCurrentCycle\(Relationships rels\) \{' "$tmp" >/dev/null \
  || { echo "FAIL: the adapter must override addRelationshipsToCurrentCycle (and it must throw)"; fail=1; }
if [ -f "$src/com/cptvsan/adapter/VsanHostMetricsAdapter.java" ]; then
  grep -q 'never writes a relationship' "$src/com/cptvsan/adapter/VsanHostMetricsAdapter.java" \
    || { echo "FAIL: the override must throw 'never writes a relationship'"; fail=1; }
fi

# 2. every Suite API write path, each counted and located
count() { # pattern expected-count expected-file-regex description
  local hits c
  hits=$(grep -E "$1" "$tmp" || true)
  c=$(printf '%s' "$hits" | grep -c . || true)
  if [ "$c" -ne "$2" ]; then echo "FAIL: expected $2 $4, found $c:"; printf '%s\n' "$hits"; fail=1; return; fi
  if [ -n "$hits" ] && printf '%s\n' "$hits" | grep -vqE "$3"; then
    echo "FAIL: $4 outside its expected file:"; printf '%s\n' "$hits" | grep -vE "$3"; fail=1
  fi
}
count '[A-Za-z_]\w*\.post\(' 1 '^[^:]+/Pusher\.java:' "'.post(' call (Pusher -> SuiteApi.post)"
count 'Net\.call\(' 3 '^[^:]+/(SuiteApi|VcClient)\.java:' "Net.call( sites (SuiteApi x2, VcClient x1)"
count 'Net\.call\(suiteOpener|suiteOpener\(\)' 2 '^[^:]+/VsanHostMetricsAdapter\.java:' "suiteOpener() uses (declaration + SuiteApi construction)"
count 'openPlatformConnection' 1 '^[^:]+/VsanHostMetricsAdapter\.java:' "openPlatformConnection calls"
count 'new SuiteApi\(' 1 '^[^:]+/VsanHostMetricsAdapter\.java:' "SuiteApi constructions"
count 'setRequestMethod' 1 '^[^:]+/Net\.java:' "setRequestMethod calls"
count '"(POST|PUT|PATCH|DELETE)"' 16 '^[^:]+/(SuiteApi|VcClient)\.java:' "HTTP write-verb literals (2 SuiteApi, 14 VcClient)"
count 'request\("POST"' 1 '^[^:]+/SuiteApi\.java:' "SuiteApi POST requests"

[ "$fail" -eq 0 ] || exit 1
echo "PASS: no object or relationship API, no reflection, no SuiteApiStitcher, Suite API writes only via Pusher -> SuiteApi.post"
