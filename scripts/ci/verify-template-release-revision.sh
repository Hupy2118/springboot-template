#!/usr/bin/env sh
# Enforces Template Release revision governance independently of the caller's cwd.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
exec python3 "$root/scripts/ci/verify-template-release-revision.py" "$@"
