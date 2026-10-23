#!/usr/bin/env python3
"""Generate reviewable upstream dependency inventory; never print credentials."""
from pathlib import Path
import re
from adapter import ADAPTER, REPO, runtime_files, inventory, json_read, encode, git_sha

code = REPO / 'template-source/code'
files = runtime_files(code)
report = ADAPTER / 'reports'
refs = []
for key, data in sorted(files.items()):
    if not key.startswith('backend/'):
        continue
    for number, line in enumerate(data.decode().splitlines(), 1):
        if re.search(r'^import com\.cmb\.|com\.tencentcloud\.|jdbc:tdsql-mysql|bee-starter-|<groupId>(?:ZA21|LT99\.09)', line):
            refs.append('`%s:%d` — `%s`' % (key, number, line.strip().replace('|', '\\|')))
(report / 'upstream-inventory.json').write_bytes(encode({'upstreamGitSha':git_sha(REPO),'runtimeFiles':inventory(files),'privateReferences':refs}))
(report / 'dependency-map.md').write_text('''# Dependency map

Upstream: `%s` (`1016`), source revision `%s`.

| Upstream dependency / provider | Local replacement | Verification |
| --- | --- | --- |
| ZA21:bee-starter-parent:6.0.7; implicit dependency versions | overlays/backend/base/pom.xml, Spring Boot 2.7.2 + explicit MyBatis/MapStruct/Lombok processors | Public settings, isolated empty Maven cache, generated Base package/start |
| ZA21:bee-starter-core:parent-managed; MVC, exceptions, JDBC defaults | Standard web/validation/JDBC starters; limited Bee response/error/exception classes | JSON/exception tests, Spring MVC and JDBC integration |
| LT99.09:tdsql-mysql-connector-java8:1.5.0 | com.mysql:mysql-connector-j:8.2.0; standard datasource YAML | Actual local MySQL connection; Bootstrap remains a later gate |
| ZA21:bee-starter-auth:parent-managed; EnableAuthClient and token context | Login manifest removes internal contributions; Mock identity implementation is a later P1 gate | Login/RBAC not yet accepted |
| TokenContextHolder.get / LocalToken.getSapId | P1 local identity compatibility classes | Thread cleanup / authentication / RBAC tests required |
| Bee ds1 / TDSQL Bootstrap loader and URL validation | MySQL BootstrapDataSource overlay + exact URL check patch | Manifest-v3 logic and SQL unchanged; real Bootstrap acceptance required |
| MyBatis mapper / pagination / transaction defaults | Public MyBatis-Plus starter; existing MybatisPlusConfiguration and @Mapper annotations retained | Mapper XML + Spring transaction/JDBC tests |

`LT54.06:xcode-agent-backend` is the generated application's own coordinate, not a downloadable private dependency. `com.cmbchina.backend` is its legitimate application package.

The template_refactor POM confirms the public Boot 2.7.2 / MyBatis-Plus 3.5.2 / MySQL 8.2.0 combination. Its changed application coordinates, transmittable-thread-local and older auth architecture are not copied. Its JWT service is a design reference only. Parent-managed enterprise dependency versions cannot be resolved without the private parent; no invented versions are recorded.

## Exact runtime references

%s

## Maintenance boundary

backend/assembly uses public SnakeYAML 1.31 / Jackson 2.13.4.2 and is maintenance-only. Workspace, profiles and assembly are not copied by the adapter.
''' % (git_sha(REPO), files['template-revision.txt'].decode().strip(), '\n'.join('- '+r for r in refs)), encoding='utf-8')
ops = json_read(ADAPTER / 'manifest.yaml')['operations']
(report / 'overlay-diff.md').write_text('# Overlay operations\n\n| Operation | Target | Upstream SHA256 | Reason |\n| --- | --- | --- | --- |\n'+''.join('| %s | `%s` | `%s` | %s |\n' % (o['type'],o['target'],o.get('expectedSourceSha256','absent (add)'),o['reason']) for o in ops),encoding='utf-8')
print('Recorded upstream inventory and %d exact overlay operations' % len(ops))
