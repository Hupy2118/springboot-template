#!/bin/sh
set -eu
ADAPTER=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
REPO=$(CDPATH= cd -- "$ADAPTER/../.." && pwd -P)
MODE=${TEMPLATE_ENGINE_STANDALONE_ENABLED:-false}
case "$MODE" in
  false) SOURCE="$REPO/template-source" ;;
  true) SOURCE="$REPO/.standalone-build/template-source" ;;
  *) echo 'TEMPLATE_ENGINE_STANDALONE_ENABLED accepts only true/false' >&2; exit 1 ;;
esac
if [ -n "${TEMPLATE_ENGINE_SOURCE_ROOT:-}" ] && [ "$TEMPLATE_ENGINE_SOURCE_ROOT" != "$SOURCE" ]; then
  echo 'TEMPLATE_ENGINE_SOURCE_ROOT conflicts with selected mode' >&2
  exit 1
fi
JAR="$REPO/template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar"
[ -f "$JAR" ] || { echo 'Build engine-service JAR first (mvn -f template-engine/pom.xml -pl engine-service -am package)' >&2; exit 1; }
export TEMPLATE_ENGINE_SOURCE_ROOT="$SOURCE"
if [ "$MODE" = false ]; then
  # Intentionally no Python, adapter manifest, prepare/audit, or temporary directory access.
  REVISION=$(cat "$SOURCE/code/template-revision.txt")
  SHA=$(git -C "$REPO" rev-parse HEAD)
  echo "mode=native source=$SOURCE revision=$REVISION upstream=$SHA manifest=not-used"
  exec java -jar "$JAR" "--spring.config.additional-location=file:$REPO/template-engine/engine-service/config/application-local.yml"
fi
exec python3 -B "$ADAPTER/scripts/adapter.py" run
