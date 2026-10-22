#!/usr/bin/env bash
set -euo pipefail
backend_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$backend_dir"
# Pass arguments without a second shell/Maven command-line parse (including spaces and quotes).
export AUTHORIZATION_BOOTSTRAP_ARGUMENT_COUNT="$#"
index=0
for argument in "$@"; do
  export "AUTHORIZATION_BOOTSTRAP_ARGUMENT_${index}=$argument"
  index=$((index + 1))
done
maven_cmd="${MAVEN_CMD:-mvn}"
if ! command -v "$maven_cmd" >/dev/null 2>&1; then
  echo 'Maven is required; set MAVEN_CMD to its executable path.' >&2
  exit 127
fi
exec "$maven_cmd" -q -DskipTests compile org.codehaus.mojo:exec-maven-plugin:3.1.0:java \
  -Dexec.mainClass=com.cmbchina.backend.auth.bootstrap.AuthorizationBootstrapCommand
