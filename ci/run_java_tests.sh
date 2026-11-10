#!/usr/bin/env bash
# Compile and run the plain-main tests: every tests/**/*Test.java is run with
# `java <class>`; other files under tests/ are helpers. The classes under test
# are compiled from src/ on demand (-sourcepath), so they must not need the
# VCF Ops SDK or framework jar. Exits non-zero on any compile or test failure.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

mapfile -t all < <(find tests -name '*.java' | sort)
mapfile -t tests < <(find tests -name '*Test.java' | sort)
[ "${#tests[@]}" -gt 0 ] || { echo "no tests found under tests/" >&2; exit 1; }

javac --release 11 -Xlint:all -Werror -d "$work" -sourcepath src:tests "${all[@]}"
for t in "${tests[@]}"; do
    class="${t#tests/}"
    class="${class%.java}"
    java -cp "$work" "${class//\//.}"
done
echo "all ${#tests[@]} test classes passed"
