在仓库根目录执行：
```
mvn -f template-engine/pom.xml -pl engine-service -am package

export TEMPLATE_ENGINE_SOURCE_ROOT="$(cd template-source && pwd)"
export TEMPLATE_ENGINE_LOCAL_FULL_TOKEN_SHA256="$(printf %s 'local-full-token' | shasum -a 256 | awk '{print $1}')"
export TEMPLATE_ENGINE_LOCAL_PLAN_TOKEN_SHA256="$(printf %s 'local-plan-token' | shasum -a 256 | awk '{print $1}')"

java -jar template-engine/engine-service/target/engine-service-*.jar \
  --spring.config.additional-location="file:$(pwd)/template-engine/engine-service/config/application-local.yml"
```
服务将监听 http://127.0.0.1:18080。启动后可验证模板下载：
```
curl -sS -D /private/tmp/engine-service-generate.headers \
  -o /private/tmp/engine-service-generate.zip \
  -X POST http://127.0.0.1:18080/v1/generate \
  -H 'Authorization: Bearer local-full-token' \
  -H 'Content-Type: application/json' \
  --data '{"requestedConfig":{"capabilities":{"authorization":{"enabled":true,"config":{}}}}}'

unzip -t /private/tmp/engine-service-generate.zip
unzip -Z1 /private/tmp/engine-service-generate.zip
```
预期返回 200、Content-Type: application/zip，ZIP 中包含 frontend/、backend/ 和 .xcodeagent/template-state.json。
