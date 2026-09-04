#!/usr/bin/env sh
set -eu

if [ "$#" -ne 2 ]; then
  echo "usage: $0 <requested-config.yaml> <empty-workspace-directory>" >&2
  exit 64
fi

mvn -f template-engine/pom.xml -pl engine-core -am -Pstage2-verification \
  -Dit.test=Stage2GenerateIT \
  -Dfailsafe.failIfNoSpecifiedTests=false \
  -Dstage2.config="$1" \
  -Dstage2.workspace="$2" \
  verify
