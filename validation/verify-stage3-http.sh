#!/usr/bin/env sh
# Runs the V2 Generate -> real /v1/update contract against the packaged JAR.
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
port=${STAGE3_PORT:-18080}
workspace=$(mktemp -d "${TMPDIR:-/tmp}/xcodeagent-stage3-http.XXXXXX")
pid=
cleanup() {
  if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; fi
  rm -rf "$workspace"
}
trap cleanup EXIT HUP INT TERM

mvn -f "$root/template-engine/pom.xml" -pl engine-service -am package
STAGE3_SOURCE_ROOT="$root/template-source" java -jar "$root/template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar" \
  --server.port="$port" --spring.config.additional-location="file:$root/validation/stage3/" >"$workspace/service.log" 2>&1 &
pid=$!

attempt=0
until curl -fsS -o /dev/null -X POST "http://127.0.0.1:$port/v1/generate" \
  -H 'Authorization: Bearer stage3-demo-token' -H 'Content-Type: application/json' \
  --data '{"requestedConfig":{"capabilities":{}}}'; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then cat "$workspace/service.log" >&2; exit 1; fi
  sleep 1
done

curl -fsS -D "$workspace/generate.headers" -o "$workspace/generate.zip" \
  -X POST "http://127.0.0.1:$port/v1/generate" \
  -H 'Authorization: Bearer stage3-demo-token' -H 'Content-Type: application/json' \
  --data '{"requestedConfig":{"capabilities":{}}}'
rg -q '^Content-Type: application/zip' "$workspace/generate.headers"
unzip -p "$workspace/generate.zip" .xcodeagent/template-state.json > "$workspace/state.json"

printf '{"protocolVersion":"2","currentTemplateState":%s,"requestedConfig":{"capabilities":{"login":{"enabled":true,"config":{}}}},"mode":"APPLY"}' \
  "$(<"$workspace/state.json")" > "$workspace/update-request.json"
curl -fsS -D "$workspace/update.headers" -o "$workspace/update.zip" \
  -X POST "http://127.0.0.1:$port/v1/update" \
  -H 'Authorization: Bearer stage3-demo-token' -H 'Content-Type: application/json' \
  --data-binary "@$workspace/update-request.json"
rg -q '^Content-Type: application/zip' "$workspace/update.headers"
unzip -t "$workspace/update.zip" >/dev/null
unzip -p "$workspace/update.zip" strategy-update-package.json | rg -q '"protocolVersion":"2"'
unzip -p "$workspace/update.zip" strategy-update-package.json | rg -q '"nextTemplateState"'
