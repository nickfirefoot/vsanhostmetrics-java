#!/usr/bin/env bash
# Everything that can be checked without the SDK jar or a lab: generated files
# are current, no relationship API is used, unit tests pass. Exit 0 = all pass.
set -euo pipefail
cd "$(dirname "$0")/.."
# PYTHON_REPO: checkout of the earlier Python 2.x pack. SDK_JAR, FRAMEWORK_JAR and
# VCFCF_SRC: see ci/run_sdk_tests.sh and tools/check_view_instancing.py.
PY="${PYTHON_REPO:?set PYTHON_REPO to the earlier Python 2.x pack checkout}"
python3 tools/gen_metric_tree.py --python-repo "$PY" --check
python3 tools/gen_describe.py --python-repo "$PY" --check
python3 tools/check_view_instancing.py
ci/no_relationships.sh
ci/run_java_tests.sh
ci/no_relationships_selftest.sh
ci/run_sdk_tests.sh
