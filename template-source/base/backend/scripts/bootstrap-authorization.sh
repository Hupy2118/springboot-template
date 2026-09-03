#!/usr/bin/env bash
set -euo pipefail

backend_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$backend_dir"

if [[ -f "$backend_dir/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$backend_dir/.env"
  set +a
fi

maven_cmd="${MAVEN_CMD:-mvn}"
if ! command -v "$maven_cmd" >/dev/null 2>&1; then
  sdkman_maven="${HOME}/.sdkman/candidates/maven/current/bin/mvn"
  if [[ -x "$sdkman_maven" ]]; then
    maven_cmd="$sdkman_maven"
  else
    echo "未找到 Maven，请安装 Maven 或通过 MAVEN_CMD 指定路径" >&2
    exit 127
  fi
fi

exec "$maven_cmd" -q -DskipTests compile exec:java \
  -Dexec.args="$*"
