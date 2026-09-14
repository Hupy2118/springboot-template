#!/usr/bin/env sh
# Fails CI when a runtime-managed Template Release input changes without a revision bump.
set -eu

base_ref=${1:?usage: verify-template-release-revision.sh <base-ref>}
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

managed_changes=$(git diff --name-only "$base_ref"...HEAD -- \
  template-source/base \
  template-source/capabilities \
  template-source/strategy-registry-v2.yaml)

if [ -n "$managed_changes" ] && git diff --quiet "$base_ref"...HEAD -- template-source/template-revision.txt; then
  echo "Template Release managed inputs changed without a template-revision.txt bump:" >&2
  echo "$managed_changes" >&2
  exit 1
fi
