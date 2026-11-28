#!/usr/bin/env bash
# Usage: tools/ci-tool-tests.sh <python|shell> <i>/<n>   (run by the tool-tests parts in .github/workflows/ci.yml)
#
# Runs part i of n of the tool tests (tools/ci_tool_parts.py picks the files by weight): the Python unit tests of the part in
# one unittest run, or each shell test of the part, stopping at the first that fails. Locally the whole set is `tools/py -m unittest
# discover -s tools/tests -p 'test_*.py'` and `for t in tools/tests/*.test.sh; do bash "$t"; done`.
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: tools/ci-tool-tests.sh <python|shell> <i>/<n>" >&2
  exit 2
fi
kind=$1
slice=$2
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$root"

mapfile -t files < <(tools/py tools/ci_tool_parts.py "$kind" "$slice")
echo "$kind tool tests, part $slice: ${#files[@]} files"

case $kind in
  python)
    modules=()
    for f in "${files[@]}"; do
      f=${f##*/}
      modules+=("${f%.py}")
    done
    # -I (tools/py) ignores PYTHONPATH, so the tests directory goes on sys.path here, as `discover -s tools/tests` puts it.
    tools/py -c 'import sys, unittest; sys.path.insert(0, "tools/tests"); unittest.main(module=None, argv=["unittest", *sys.argv[1:]])' "${modules[@]}"
    ;;
  shell)
    for t in "${files[@]}"; do
      echo "::group::$t"
      bash "$t" || { echo "::error::$t failed"; exit 1; }
      echo "::endgroup::"
    done
    ;;
  *)
    echo "usage: tools/ci-tool-tests.sh <python|shell> <i>/<n>" >&2
    exit 2
    ;;
esac
