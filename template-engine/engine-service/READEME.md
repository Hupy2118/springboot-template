在仓库根目录执行：
```
mvn -f template-engine/pom.xml -pl engine-service -am package

export STAGE3_SOURCE_ROOT="$(pwd)/template-source"

java -jar template-engine/engine-service/target/engine-service-*.jar \
  --spring.config.additional-location="file:$(pwd)/validation/stage3/"
```
服务将监听 http://127.0.0.1:18080。启动后可验证模板下载：
```
curl -sS -D /private/tmp/stage3-generate.headers \
  -o /private/tmp/stage3-generate.zip \
  -X POST http://127.0.0.1:18080/v1/generate \
  -H 'Authorization: Bearer stage3-demo-token' \
  -H 'Content-Type: application/json' \
  --data '{"requestedConfig":{"capabilities":{"authorization":{"enabled":true,"config":{}}}}}'

unzip -t /private/tmp/stage3-generate.zip
unzip -Z1 /private/tmp/stage3-generate.zip
```
预期返回 200、Content-Type: application/zip，ZIP 中包含 frontend/、backend/ 和 .xcodeagent/template-state.json。