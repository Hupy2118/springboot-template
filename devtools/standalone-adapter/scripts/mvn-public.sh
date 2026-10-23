#!/bin/sh
set -eu
ADAPTER=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
REPO=$(CDPATH= cd -- "$ADAPTER/../.." && pwd -P)
exec mvn -s "$ADAPTER/settings-public.xml" -gs "$ADAPTER/settings-public.xml" "-Dmaven.repo.local=$REPO/.standalone-build/m2" "$@"
