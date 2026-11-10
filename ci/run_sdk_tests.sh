#!/usr/bin/env bash
# Compile the WHOLE adapter (including VsanHostMetricsAdapter.java) against the
# SDK jar and the framework jar with JDK 25, then run the SDK-dependent tests
# in tests-sdk/. Exit non-zero on any compile or test failure.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk-amd64}"
SDK="${SDK_JAR:-/usr/lib/vmware-vcops/common/lib/vrops-adapters-sdk.jar}"
FW="${FRAMEWORK_JAR:?set FRAMEWORK_JAR to vcfcf-adapter-base.jar from the content factory}"
for j in "$SDK" "$FW"; do [ -f "$j" ] || { echo "missing jar: $j" >&2; exit 1; }; done
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mapfile -t main < <(find src -name '*.java' | sort)
mapfile -t tsdk < <(find tests-sdk -name '*.java' | sort)
mapfile -t helpers < <(find tests -name 'Check.java')
"$JAVA_HOME/bin/javac" --release 11 -Xlint:all -Werror -cp "$SDK:$FW" -d "$work" "${main[@]}" "${tsdk[@]}" "${helpers[@]}"
echo "compiled ${#main[@]} adapter sources against the SDK"
for t in "${tsdk[@]}"; do
  class="${t#tests-sdk/}"; class="${class%.java}"
  "$JAVA_HOME/bin/java" -cp "$work:$SDK:$FW" "${class//\//.}"
done
