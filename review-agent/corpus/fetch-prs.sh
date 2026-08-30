#!/usr/bin/env bash
# Fetch closed agent PRs lacking corpus records.
#
# Fetches GitHub data for each closed PR authored by --author that has no record
# in --corpus/<number> yet: PR metadata, reviews, review comments (with
# html_url, original_commit_id and reaction rollups), and per-user reactions.
# The corpus is a gitignored cache: everything here is reconstructable from
# GitHub. See PLAN.md.
#
# Use --pr N to fetch (or re-fetch) a single PR regardless of state or existing
# record; handy for a still-open PR whose review the maintainer has finished
# engaging with.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="boskworks/bosk"
PR_AUTHOR="prdoyle-agent"
CORPUS_DIR="$SCRIPT_DIR/../data/corpus"
SINGLE_PR=""

usage() {
  cat <<'EOF'
Usage: fetch-prs.sh [--repo OWNER/REPO] [--author LOGIN] [--corpus DIR] [--pr N]
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2;;
    --author) PR_AUTHOR="$2"; shift 2;;
    --corpus) CORPUS_DIR="$2"; shift 2;;
    --pr) SINGLE_PR="$2"; shift 2;;
    -h|--help) usage; exit 0;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2;;
  esac
done

mkdir -p "$CORPUS_DIR"

fetch_pr() {
  local n="$1"
  if gh api "repos/$REPO/issues/$n/timeline" --paginate --jq '.[].event' 2>/dev/null | grep -qx 'base_ref_changed'; then
    echo "== PR $n: EXCLUDED (base branch was changed)"
    return
  fi
  echo "== fetching PR $n"
  mkdir -p "$CORPUS_DIR/$n/reactions"
  gh api "repos/$REPO/pulls/$n" \
    --jq '{number,title,body,url,state,mergedAt,merged,merge_commit_sha,base:{ref:.base.ref,sha:.base.sha},head:{ref:.head.ref,sha:.head.sha},author:{login:.user.login},merged_by:(.merged_by.login // null)}' \
    > "$CORPUS_DIR/$n/pr.json"
  gh api "repos/$REPO/pulls/$n/reviews" > "$CORPUS_DIR/$n/reviews.json"
  gh api "repos/$REPO/pulls/$n/comments" > "$CORPUS_DIR/$n/comments.json"
  for id in $(jq -r '.[].id' "$CORPUS_DIR/$n/comments.json"); do
    gh api "repos/$REPO/pulls/comments/$id/reactions" > "$CORPUS_DIR/$n/reactions/$id.json"
  done
}

if [[ -n "$SINGLE_PR" ]]; then
  fetch_pr "$SINGLE_PR"
  echo "done"
  exit 0
fi

mapfile -t PRS < <(gh pr list -R "$REPO" --author "$PR_AUTHOR" --state closed --limit 200 --json number --jq '.[].number')
echo "Found ${#PRS[@]} closed PRs by $PR_AUTHOR; checking corpus records..."

for n in "${PRS[@]}"; do
  if [[ -d "$CORPUS_DIR/$n" ]]; then
    continue
  fi
  fetch_pr "$n"
done

echo "done"
