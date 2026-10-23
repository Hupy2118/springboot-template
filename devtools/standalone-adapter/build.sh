#!/bin/sh
set -eu
ADAPTER=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
COMMAND=${1:-help}
[ "$#" -eq 0 ] || shift
case "$COMMAND" in
  prepare|audit) exec python3 -B "$ADAPTER/scripts/adapter.py" "$COMMAND" "$@" ;;
  test) exec python3 -B -m unittest discover -s "$ADAPTER/tests" -v ;;
  verify-base|verify-full|verify-engine|verify-all|verify-http-only) exec "$ADAPTER/scripts/verify.sh" "$COMMAND" "$@" ;;
  *) echo 'Usage: build.sh prepare | audit | test | verify-base | verify-full | verify-engine | verify-http-only | verify-all' >&2; exit 2 ;;
esac
