#!/usr/bin/env sh
# Verifies the V2 empty-capability baseline without relying on a pre-existing node_modules.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
workspace=$(mktemp -d "${TMPDIR:-/tmp}/xcodeagent-base-frontend.XXXXXX")
trap 'rm -rf "$workspace"' EXIT HUP INT TERM

# The empty V2 project is exactly the declared Base. Do not copy ignored local modules.
tar -C "$root/template-source/base/frontend" --exclude='./node_modules' -cf - . | tar -C "$workspace" -xf -
cd "$workspace"
pnpm install --frozen-lockfile --ignore-scripts
pnpm run build
