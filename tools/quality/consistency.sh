#!/bin/bash
# "Every gate passes, on three runs in a row" (docs/quality/RUBRIC.md, "What 9.5 means"): runs
# tools/quality/collect.sh N times in a row (default 3) at one commit, keeps each run's step exits and
# measure results, and writes build/quality/consistency.json — each run, and whether all were green
# (collect.sh and every step exited 0, every scripted measure passed, all at the first run's commit with
# nothing but docs/quality/SCORECARD.md uncommitted). Then it writes the scorecard again, which reports it.
#
#   tools/quality/consistency.sh [--runs N] [collect.sh options: --ios --mutation --simulator <udid>]
#
# Takes hours with --ios --mutation. Exits 0 only when every run was green.
set -u
cd "$(dirname "$0")/../.."
RUNS=3
if [ "${1:-}" = "--runs" ]; then
  RUNS=$2
  shift 2
fi
# Outside build/quality/, which each collect.sh run empties at its start.
KEEP=build/quality-consistency
rm -rf "$KEEP"
for run in $(seq 1 "$RUNS"); do
  echo "=== Run $run of $RUNS"
  mkdir -p "$KEEP/run-$run"
  tools/quality/collect.sh "$@"
  echo $? > "$KEEP/run-$run/exit"
  cp build/quality/scorecard.json "$KEEP/run-$run/" 2> /dev/null
done
python3 tools/quality/scorecard.py --consistency "$KEEP" > build/quality/consistency.json
GREEN=$?
python3 tools/quality/scorecard.py
echo "Consistency: $(pwd)/build/quality/consistency.json"
exit $GREEN
