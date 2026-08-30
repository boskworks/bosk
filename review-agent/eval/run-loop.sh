#!/usr/bin/env bash
# Run the measurement loop in one command: fetch, classify, evaluate, and report.
#
# Usage: run-loop.sh [--prs N,M] [--calibrate]
#   --prs        restrict the eval to these PRs
#   --calibrate  also run measure-agreement and validate-matcher on PRs with archived reviews
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."
REPO="$ROOT/.."

PRS=""
CALIBRATE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prs) PRS="$2"; shift 2;;
    --calibrate) CALIBRATE=1; shift;;
    -h|--help) echo "Usage: run-loop.sh [--prs N,M] [--calibrate]"; exit 0;;
    *) echo "unknown option: $1" >&2; exit 2;;
  esac
done

echo "== fetch"
"$ROOT/corpus/fetch-prs.sh"
echo "== classify"
python3 "$ROOT/corpus/classify-comments.py"
echo "== eval"
if [[ -n "$PRS" ]]; then
  python3 "$ROOT/eval/eval.py" --prs "$PRS" --repo-dir "$REPO"
else
  python3 "$ROOT/eval/eval.py" --repo-dir "$REPO"
fi
echo "== report"
python3 "$ROOT/eval/write-report.py"

if [[ -n "$CALIBRATE" ]]; then
  echo "== calibrate"
  for review in "$ROOT"/data/reviews/*.json; do
    [ -e "$review" ] || continue
    pr=$(basename "$review" .json)
    echo "-- PR $pr: judge agreement"
    python3 "$ROOT/calibrate/measure-agreement.py" "$pr" --repo-dir "$REPO" || true
    echo "-- PR $pr: matcher validation"
    python3 "$ROOT/calibrate/validate-matcher.py" "$pr" --repo-dir "$REPO" || true
  done
fi
echo "done"
