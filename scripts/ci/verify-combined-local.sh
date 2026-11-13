#!/usr/bin/env bash
# Run against a disposable MySQL instance when enabling writes.
set +x
set -euo pipefail
umask 077

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
jar=${ENGINE_SERVICE_JAR:-"$root/template-engine/engine-service/target/engine-service-1.0.0-SNAPSHOT.jar"}
mode=${1:-combined}
port=${TEMPLATE_ENGINE_PORT:-18080}
base="http://127.0.0.1:$port"
export TEMPLATE_ENGINE_PORT="$port"
export TEMPLATE_ENGINE_SOURCE_ROOT="${TEMPLATE_ENGINE_SOURCE_ROOT:-$root/template-source}"

die() { echo "FAIL: $*" >&2; exit 1; }
[[ $mode == combined || $mode == default ]] || die "usage: $0 [combined|default]"
for tool in java curl jq unzip; do
    command -v "$tool" >/dev/null || die "missing tool: $tool"
done
tmp=$(mktemp -d)
pid=
stop() {
    if [[ -n $pid ]]; then
        kill "$pid" 2>/dev/null || true
        wait "$pid" 2>/dev/null || true
        pid=
    fi
}
finish() {
    rc=$?
    stop
    rm -rf "$tmp"
    if [[ $rc != 0 ]]; then
        echo "Verification stopped before remaining checks could run." >&2
    fi
}
trap finish EXIT

[[ -f $jar ]] || die "build the $mode JAR first"
unzip -p "$jar" META-INF/MANIFEST.MF | tr -d '\r' > "$tmp/manifest"
grep -qx 'Start-Class: com.devagentstudio.template.engine.EngineServiceApplication' "$tmp/manifest" ||
    die "unexpected Start-Class"
unzip -Z1 "$jar" > "$tmp/entries"
! grep -Eq 'engine/service/EngineServiceApplication.class|BOOT-INF/lib/(bee-starter|tdsql)' "$tmp/entries" ||
    die "unexpected boot entry point or enterprise dependency"
profiles=dev
if [[ $mode == combined ]]; then
    for lib in bee-compat managed-database-core mysql-connector-j; do
        grep -q "^BOOT-INF/lib/$lib-" "$tmp/entries" || die "missing $lib"
    done
    : "${MANAGED_DATABASE_SERVICE_KEY:?set the local service key}"
    [[ $MANAGED_DATABASE_SERVICE_KEY != *$'\r'* && $MANAGED_DATABASE_SERVICE_KEY != *$'\n'* ]] ||
        die "service key contains a newline"
    printf 'Authorization: Bearer %s\n' "$MANAGED_DATABASE_SERVICE_KEY" > "$tmp/auth"
    profiles=dev,managed-db
else
    ! grep -Eq '^BOOT-INF/lib/(bee-compat|managed-database-core|mysql-connector-j|spring-jdbc|HikariCP)-' "$tmp/entries" ||
        die "database dependency in default JAR"
fi
echo "PASS $mode JAR entry point and dependencies"

start() {
    if curl -s --max-time 1 "$base/" -o /dev/null; then
        die "port $port already serves HTTP"
    fi
    MANAGED_DATABASE_SERVICE_KEY="$1" java -jar "$jar" --spring.profiles.active="$profiles" > "$tmp/server.log" 2>&1 &
    pid=$!
    for ((attempt=0; attempt<60; attempt++)); do
        kill -0 "$pid" 2>/dev/null || die "JAR exited during startup; check local configuration and port $port"
        if grep -q 'Started EngineServiceApplication' "$tmp/server.log" &&
            curl -s --max-time 1 "$base/" -o /dev/null; then
            return
        fi
        sleep 1
    done
    die "JAR startup timed out"
}

request() {
    local expected=$1 method=$2 path=$3
    shift 3
    local actual
    actual=$(curl --silent --show-error --max-time 40 -X "$method" -D "$tmp/headers" \
        -o "$tmp/body" -w '%{http_code}' "$@" "$base$path") || die "$method $path request failed"
    [[ $actual == "$expected" ]] || die "$method $path HTTP $actual (expected $expected)"
    printf 'PASS %s %s HTTP %s\n' "$method" "$path" "$actual"
}

bee() {
    local code=$1
    grep -qi '^Content-Type: application/json' "$tmp/headers" || die "expected Bee JSON"
    jq -e --arg code "$code" '
      keys == ["body","errorMsg","returnCode"] and .returnCode == $code
      and (if $code == "SUC0000" then .errorMsg == "" else
        .body == null and (.errorMsg | type == "string" and length > 0) end)' "$tmp/body" >/dev/null ||
        die "invalid Bee envelope, expected $code"
    echo "  returnCode=$code, three fields"
}

json() { request "$1" "$2" "$3" -H "@$tmp/auth" -H 'Content-Type: application/json' "${@:4}"; }

start "${MANAGED_DATABASE_SERVICE_KEY:-}"
request 200 POST /v1/generate -H 'Content-Type: application/json' \
    --data '{"requestedConfig":{"capabilities":{}}}'
grep -qi '^Content-Type: application/zip' "$tmp/headers" || die "generate did not return a ZIP"
unzip -tq "$tmp/body"
if [[ $mode == default ]]; then
    request 404 GET /managed-database/health
    exit
fi

unzip -p "$tmp/body" .devagentstudio/template-state.json | jq -c \
    '{protocolVersion:"4",currentTemplateState:.,requestedConfig:{capabilities:{}},mode:"APPLY"}' > "$tmp/update"
request 204 POST /v1/update -H 'Accept: application/zip, application/json' -H 'Content-Type: application/json' --data-binary "@$tmp/update"
jq '.requestedConfig.capabilities.login = {enabled:true,config:{}}' "$tmp/update" > "$tmp/update-login"
request 200 POST /v1/update -H 'Accept: application/zip, application/json' \
    -H 'Content-Type: application/json' --data-binary "@$tmp/update-login"
grep -qi '^Content-Type: application/zip' "$tmp/headers" || die "update did not return a ZIP"
unzip -tq "$tmp/body"
request 400 POST /v1/generate -H 'Accept: application/zip, application/json' -H 'Content-Type: application/json' --data '{'
jq -e 'keys == ["code","details","message","traceId"] and .code == "BAD_REQUEST"' "$tmp/body" >/dev/null
request 401 GET /managed-database/health
bee UNAUTHENTICATED
json 200 GET /managed-database/health
bee SUC0000
jq -e '.body.status == "UP"' "$tmp/body" >/dev/null
json 400 POST /managed-database/datasources --data '{'
bee INVALID_ARGUMENT
json 405 PUT /managed-database/health
bee INVALID_ARGUMENT
request 415 POST /managed-database/datasources/000000000000/tables/check -H "@$tmp/auth" -H 'Content-Type: text/plain' --data 'invalid'
bee INVALID_ARGUMENT
json 404 GET /managed-database/datasources/000000000000/tables
bee RESOURCE_NOT_FOUND

poll() {
    local path=$1 state
    for ((attempt=0; attempt<30; attempt++)); do
        json 200 GET "$path"
        bee SUC0000
        state=$(jq -er '.body.status' "$tmp/body")
        case "$state" in
            SUCCEEDED) echo "  final state=$state"; return ;;
            RUNNING) sleep 1 ;;
            *) die "$path final state=$state" ;;
        esac
    done
    die "$path did not finish"
}

sse() {
    local path=$1 props=$2 terminal=$3 event=$4
    local payload
    payload=$(jq -cn --argjson props "$props" '{threadId:"combined-local",runId:"combined-local",forwardedProps:$props}')
    json 200 POST "$path" -H 'Accept: text/event-stream' --data "$payload"
    grep -qi '^Content-Type: text/event-stream' "$tmp/headers" || die "$path did not return SSE"
    sed -n 's/^data: *//p' "$tmp/body" > "$tmp/events"
    jq -se --arg terminal "$terminal" --arg event "$event" '
      .[0].type == "RUN_STARTED" and .[-1].type == $terminal
      and (if $terminal == "RUN_ERROR" then .[-1].code == $event
           else any(.[]; .type == "CUSTOM" and .name == $event) end)
      and all(.[]; has("returnCode") | not)' "$tmp/events" >/dev/null || die "$path invalid SSE events"
    echo "  Content-Type=text/event-stream, event=$event, types=$(jq -rs 'map(.type) | join(",")' "$tmp/events")"
}

if [[ ${RUN_MUTATING_INTEGRATION_TESTS:-false} == true ]]; then
    command -v mysql >/dev/null || die "missing tool: mysql"
    command -v python3 >/dev/null || die "missing tool: python3"
    meta() {
        MYSQL_PWD="$MANAGED_DB_ADMIN_PASSWORD" mysql --no-defaults --ssl-mode="${MANAGED_DB_SSL_MODE:-REQUIRED}" -h "${MANAGED_DB_HOST:-127.0.0.1}" \
            -P "${MANAGED_DB_PORT:-3306}" -u "$MANAGED_DB_ADMIN_USER" -Nse "$1" "${MANAGED_DB_ADMIN_SCHEMA:-managed_meta}"
    }
    business() {
        MYSQL_PWD="$MANAGED_DB_RUNTIME_PASSWORD" mysql --no-defaults --ssl-mode="${MANAGED_DB_RUNTIME_SSL_MODE:-${MANAGED_DB_SSL_MODE:-REQUIRED}}" \
            -h "${MANAGED_DB_MOUNT_HOST:-${MANAGED_DB_HOST:-127.0.0.1}}" -P "${MANAGED_DB_MOUNT_PORT:-${MANAGED_DB_PORT:-3306}}" \
            -u "$MANAGED_DB_RUNTIME_USER" -Nse "$1" "${MANAGED_DB_MOUNT_SCHEMA:-managed_business}"
    }
    [[ $(meta "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('md_datasource_resource','md_datasource_table','md_database_operation','md_data_operation')") == 4 ]] ||
        die "expected four management tables"
    echo "PASS four management tables"
    run_id="combined-local-$(date +%s)-$$"
    json 200 POST /managed-database/datasources --data "$(jq -cn --arg run "$run_id" '{appKey:$run,appName:"Combined local verification"}')"
    bee SUC0000
    key=$(jq -er '.body.datasourceKey | select(test("^[0-9a-f]{12}$"))' "$tmp/body")
    resource="/managed-database/datasources/$key"
    [[ $(meta "SELECT COUNT(*) FROM md_datasource_resource WHERE datasource_key='$key' AND app_key='$run_id'") == 1 ]] ||
        die "registration not persisted"
    sql="CREATE TABLE ${key}_orders(id bigint NOT NULL, title varchar(64), PRIMARY KEY(id))"
    create_table() {
        local ddl=$1
        json 200 POST "$resource/table-operations" --data "$(jq -cn --arg sql "$ddl" '{mode:"CREATE",sql:$sql,allowDataLoss:false}')"
        bee SUC0000
        operation=$(jq -er '.body.operationId | select(test("^[0-9]+$"))' "$tmp/body")
        poll "$resource/table-operations/$operation"
        [[ $(meta "SELECT status FROM md_database_operation WHERE datasource_key='$key' AND id=$operation") == SUCCEEDED ]] ||
            die "DDL status not persisted"
    }
    create_table "$sql"
    orders_operation=$operation
    [[ $(business "SELECT COUNT(*) FROM ${key}_orders") == 0 ]] || die "unexpected initial table contents"
    [[ $(meta "SELECT status FROM md_datasource_table WHERE datasource_key='$key' AND table_name='${key}_orders'") == CREATED ]] ||
        die "table binding not persisted"
    json 200 GET "$resource/tables"
    bee SUC0000
    jq -e --arg table "${key}_orders" 'any(.body.tables[]; .tableName == $table)' "$tmp/body" >/dev/null
    json 200 GET "$resource/tables/${key}_orders/columns"
    bee SUC0000
    jq -e 'any(.body.columns[]; .name == "id" and .primaryKey)' "$tmp/body" >/dev/null
    json 200 POST "$resource/tables/check" --data "$(jq -cn --arg sql "$sql" '{sqls:[$sql]}')"
    bee SUC0000
    jq -e '.body.ready' "$tmp/body" >/dev/null

    # The authorization DDL is the same Extension asset delivered by generation.
    python3 - "$key" "$root/template-source/code/backend/extensions/authorization/sql/authorization/schema.sql" <<'PY' > "$tmp/authorization-ddl"
import re
import sys
from pathlib import Path

sql = Path(sys.argv[2]).read_text()
statements = [statement.strip() for statement in sql.split(";") if statement.strip()]
assert len(statements) == 4
for statement in statements:
    print(re.sub(r"^CREATE TABLE `", "CREATE TABLE `" + sys.argv[1] + "_", statement).replace("\n", " "))
PY
    while IFS= read -r ddl; do
        create_table "$ddl"
    done < "$tmp/authorization-ddl"

    seed="$root/scripts/ci/fixtures/managed-authorization-seed.json"
    jq --arg key "$key" '.tables[].tableName |= sub("^12ab34cd56ef"; $key)' "$seed" > "$tmp/seed"
    json 200 POST "$resource/data-operations/batch" --data-binary "@$tmp/seed"
    bee SUC0000
    seed_operation=$(jq -er '.body.operationId | select(test("^[0-9]+$"))' "$tmp/body")
    poll "$resource/data-operations/$seed_operation"
    jq -e '.body.verified == true and .body.createdCount == 5' "$tmp/body" >/dev/null
    [[ $(meta "SELECT status FROM md_data_operation WHERE datasource_key='$key' AND id=$seed_operation") == SUCCEEDED ]] ||
        die "Seed status not persisted"
    [[ $(business "SELECT COUNT(*) FROM ${key}_role_member WHERE role_id='SYSTEM_ADMIN' AND member_id='fixture-subject'") == 1 ]] ||
        die "Seed member not persisted"
    jq '.tables[3].rows[0].member_name = "Changed fixture"' "$tmp/seed" > "$tmp/conflict"
    json 409 POST "$resource/data-operations/batch" --data-binary "@$tmp/conflict"
    bee IDEMPOTENCY_CONFLICT

    json 200 GET "$resource/runtime-connection"
    bee SUC0000
    grep -qi '^Cache-Control: no-store' "$tmp/headers" || die "runtime credentials are cacheable"
    jq -e --arg key "$key" '.body.datasourceKey == $key and (.body.password | type == "string" and length > 0)' "$tmp/body" >/dev/null
    sse "$resource/tables/${key}_orders/data/run" '{"action":"QUERY"}' RUN_FINISHED TABLE_DATA_RESULT
    sse "$resource/tables/${key}_orders/data/run" '{"action":"INVALID"}' RUN_FINISHED TABLE_DATA_ERROR
    sse "$resource/table-operations/$orders_operation/recovery" '{"action":"PREVIEW"}' RUN_FINISHED ABANDON_CONFIRMATION_REQUIRED
    sse "$resource/table-operations/$orders_operation/recovery" '{"action":"INVALID"}' RUN_ERROR INVALID_ARGUMENT
    sse "$resource/cleanup/run" '{"action":"PREVIEW","scope":"DATASOURCE"}' RUN_FINISHED RESOURCE_CLEANUP_PREVIEW
    preview=$(jq -ser 'map(select(.name == "RESOURCE_CLEANUP_PREVIEW"))[0].value.previewHash' "$tmp/events")
    sse "$resource/cleanup/run" "$(jq -cn --arg hash "$preview" \
        '{action:"CONFIRM",scope:"DATASOURCE",previewHash:$hash,confirmed:true,allowDataLoss:true}')" RUN_FINISHED RESOURCE_CLEANUP_ACCEPTED
    cleanup=$(jq -ser 'map(select(.name == "RESOURCE_CLEANUP_ACCEPTED"))[0].value.operationId' "$tmp/events")
    poll "$resource/table-operations/$cleanup"
    [[ $(meta "SELECT status FROM md_datasource_resource WHERE datasource_key='$key'") == RELEASED ]] ||
        die "test resource not released"
    [[ $(business "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND LEFT(table_name,13)='${key}_'") == 0 ]] ||
        die "test tables still exist"
    echo "PASS fixture cleanup"
else
    echo "SKIP registration, DDL, Seed, HTTP 409, runtime credentials, SSE, cleanup (RUN_MUTATING_INTEGRATION_TESTS is disabled)"
fi

stop
start ""
request 503 GET /managed-database/health
bee RESOURCE_SERVICE_NOT_CONFIGURED
