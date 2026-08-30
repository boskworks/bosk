#!/usr/bin/env bash
# Run the measurement loop in one command: fetch, classify, evaluate, and report.
#
# Usage: run-loop.sh [--prs N,M]
#   --prs        restrict the eval to these PRs
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$SCRIPT_DIR/.."
REPO="$ROOT/.."

PRS=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prs) PRS="$2"; shift 2;;
    -h|--help) echo "Usage: run-loop.sh [--prs N,M]"; exit 0;;
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
echo "done"
