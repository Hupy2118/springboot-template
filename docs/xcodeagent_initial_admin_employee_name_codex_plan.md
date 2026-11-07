# XCodeAgent + 模板工程：初始管理员“8位员工号/姓名”改造实施方案（Codex）

> 适用仓库：
> - XCodeAgent：<https://github.com/YYYWYF/XCodeAgent>，`dev_agent`；分析基线 `29c3cc8e090d071378ffb10416b1ca9ed60b3ecc`（2026-11-07）。
> - springboot-template：<https://github.com/Hupy2118/springboot-template>，`dev`；分析基线 `67f677db5e2f10322725e99705d50dcb0ed4a718`（2026-11-04）。
> - SaaS `managed-database-service` **不在本次修改范围**。开始实施时以各分支最新代码复核本文件的路径与函数名。
>
> **目标：最小侵入、保持旧配置可读取、禁止自动回写历史权限成员数据。**

## 1. 目标、范围与禁止事项

### 1.1 需求

权限启用时，初始管理员新增输入采用 `8位员工号/姓名`，如：`00123456/张三`。支持多个管理员（沿用 `Select mode="tags"`）。首次权限数据初始化时：

| 输入 | `role_member.role_id` | `member_id` | `member_name` | `created_by` |
| --- | --- | --- | --- | --- |
| `00123456/张三` | `SYSTEM_ADMIN` | `00123456` | `张三` | `SYSTEM` |
| `00876543/李四` | `SYSTEM_ADMIN` | `00876543` | `李四` | `SYSTEM` |

- **初始管理员与当前登录用户没有关联限制。** 不要求等于当前用户的 `SapId`；禁止调用当前用户接口推断/覆盖初始管理员。
- 员工号必须保留为**字符串**，不得转整数，避免丢失前导 0。
- 沿用 `.devagentstudio/application.json` 中的 `authorization.initialAdministratorSubjects: string[]`，只改变**新增项**的值格式；本次不引入新字段、不提升 `schemaVersion`、不调整模板生成接口。
- 已存储的旧 `initialAdministratorSubjects` 原样允许读取与保持；任何**新增或替换**值必须符合新格式。
- 已初始化的 `role_member` 不自动 `UPDATE`/`DELETE`/重建，修改 `application.json` **不代表**成员表已经同步。

### 1.2 不改

- 不修改 `role_member`/`role`/`resource`/`role_resource` DDL，现有 `member_id varchar(32)` / `member_name varchar(32)` 足够。
- 不修改模板项目的 `RoleMember`、`RoleMemberPO`、`RoleMemberMapper.xml`、权限管理 REST 接口。
- 不修改 SaaS Seed 接口、Payload、幂等/冲突规则或数据库操作接口。
- 不修改登录身份获取、权限查询与 `ResourcePermissionInterceptor#getSapId()` 逻辑。
- 不修改已有 Seed 任务的 `operationId`、`idempotencyKey`、`fingerprint` 或重试/恢复身份；不得为“纠正姓名”清空运行状态并重新 Seed。
- 不针对历史应用自动迁移 `application.json` 或修改历史表数据。

## 2. 两端统一输入合同

### 2.1 新格式（**所有新建、首次启用和新修改值必须遵守**）

- 形式：`^[0-9]{8}/[^/]+$`（基本形状；解析后还须进行详细校验）。
- 员工号：**恰好 8 位半角数字** `[0-9]{8}`，即使全 0 也按格式处理；不转数字、不丢前导零。
- 分隔符：只接受**一个半角 `/`**；不接受全角斜线、额外 `/`、没有 `/` 的新输入。
- 姓名：去除输入项首尾空白后，姓名自身不可首尾有空白、不得为空、不得含 `/`、控制字符或 `,`/`，`/换行等标签分隔符；按 Unicode 码点计数 `1..32`，与 `member_name varchar(32)` 一致；允许合法中文、少数民族姓名中点和正常中间空格。
- 输入项总体必须是字符串，去除**整个输入项**的首尾空白后进行判定并保存稳定格式；不得自行改变姓名内部字符。
- 数量上限：继承现有 Builtin Seed `MAX_ADMINS=100`；对超限明确报错。
- 幂等身份与去重：以**员工号**为主键；同员工号且姓名相同可确定性去重，同员工号不同姓名**报错**（不取第一条、不覆盖）。输入列表的逻辑顺序可沿用原有顺序，Seed 继续确定性排序。
- `current-user` 等现有禁止占位符继续拒绝；不能作为合法新增值。

新格式解析示意：

```json
{
  "raw": "00123456/张三",
  "memberId": "00123456",
  "memberName": "张三",
  "legacy": false
}
```

### 2.2 历史读取兼容（关键）

- `validate_application_snapshot()` / 工作区打开、恢复、列表、配置详情等**读取路径**：允许已保存的旧字符串值（例如 `00123456`、`ops@example.com`、`test-admin`）和新格式共存；仍拒绝非字符串、空白和 `current-user`。不要把历史配置直接规范化重写。
- 老值**原样不动**时，即使用户保存“主题色”等无关配置，也不得报初始管理员格式错误、不得清空旧值或修改其排序。
- **写入路径**：通过读取原有已保存数组 `before` 和待写数组 `after` 做增量校验；`after` 中与 `before` **完全一致的原始项**可作为历史项保留；其余新增/替换项强制新格式。与历史项对应的原字符串不应因为渲染/表单规范化被悄悄改写。
- 历史 `00123456` 与新 `00123456/张三` 若在同一数组中并存，识别为同一员工身份冲突，**拒绝保存**。通过替换旧值而非追加处理；已初始化库中的姓名不会因此自动更新。
- 历史无工号语义的 Subject（如 `ops@example.com`）保持原身份字符串；不能猜测其员工号或姓名。原则上只按原始 subject 作为身份识别，缺乏可靠映射时不合并。
- 对**完全相同**的历史配置读入不得强制去重或整理；只在显式管理员修改/新建时按上述规则校验与规范化。
- 防止利用“历史兼容”接收非法新输入：**不能**将任意不符合新格式的字符串统称为 legacy 并放行新增。后端所有正式写入入口必须以已保存基线做判断；前端校验仅是辅助。

> 注意：单纯的 `parse(value)` 无法判定某个不合规值是历史值还是用户新输入；请把 **read-compatible**（旧值读取/初始化）与 **write-strict + baseline diff**（新增/修改）设计成不同的调用上下文。

### 2.3 初始化解析合同（新旧两种）

两种初始化器须具有相同的确定性行为：

| 已保存值 | 首次初始化时 `member_id` | 首次初始化时 `member_name` |
| --- | --- | --- |
| `00123456/张三` | `00123456` | `张三` |
| `00123456`（历史） | `00123456` | `00123456`（保持旧行为） |
| `ops@example.com`（历史） | `ops@example.com` | `ops@example.com`（保持旧行为） |

- 对**历史**字符串回退到 `member_id=原值, member_name=原值`，仅用于兼容历史配置在**尚未初始化**时首次初始化；不允许把此回退作为新输入的合法校验。
- 保持现有权限角色、系统资源授权范围、创建人、自然键及幂等行为。
- 新格式 `member_id` 的查询/授权匹配按原有 `role_member` 逻辑执行，不与当前操作者绑定。
- 解析器不输出或日志打印全部管理员身份数据；错误建议使用无个人信息的分类和索引。

## 3. 当前调用链与根因（核对结果）

```text
新建 / 应用设置 / 对话配置卡
  └─ ApplicationConfigEditor.tsx (Select mode="tags")
       └─ application.json.authorization.initialAdministratorSubjects
            ├─ Builtin: XCodeAgent builtin_authorization_seed.py
            │     当前 member_id = subject, member_name = subject
            │     └─ SaaS /data-operations/batch → role_member
            └─ 非 Builtin: template AuthorizationBootstrapCommand.java
                  当前 member_id = subject, member_name = subject
                  └─ JDBC INSERT role_member
```

已经有完整的角色成员字段和固定 DDL，实际需要变更的是**输入校验、配置写入边界、两条初始化载荷编译/解析**。

## 4. PR1：XCodeAgent（Frontend + Backend）

### 4.1 Frontend：一个共享输入组件，不开发第二套表单

**改造文件**：

1. `Frontend/src/renderer/src/components/Welcome/ApplicationConfigEditor.tsx`
   - 保留现有 `<Select mode="tags">`，不重做输入组件、应用设置页或表单样式。
   - 标签改为 `初始管理员`；占位符改为 `例如：00123456/张三，回车添加`。
   - 提示 `每位管理员按“8位员工号/姓名”输入；支持多个。初始管理员无需是当前登录用户。`。
   - 复用现有 Form.Item 校验，调用公共 `validateAdminChange(before, after)`；默认 create 基线为空，settings/conversation 由父组件传真实 `initialAdministratorSubjects` 基线。修改时旧标签可保留，不要在输入框打开时强制转化旧值。
   - 如果有旧格式，使用主题内原有 `Alert` 或轻量说明：`历史管理员配置可继续保留。新增或修改请使用“8位员工号/姓名”格式。`。不自动替换旧标签。
   - 员工号重复、姓名缺失、分隔符非法时在 Form.Item 就地报错；禁止仅依靠 placeholder 限制格式。
2. 推荐新增 `Frontend/src/renderer/src/utils/initialAdministrators.ts`（路径可依项目现有 utils 约定调整）：
   - `parseNewInitialAdministrator()`：只解析新格式。
   - `validateAdminChange(previous: string[], next: string[])`：允许完全保留的历史旧项、检查新增项、员工号冲突/数量限制；返回归一化但不修改旧项。
   - 与 Backend 用同一测试向量，不能单独推导出另一套语义。
3. `Frontend/src/renderer/src/components/Welcome/utils.ts`：`buildApplicationSchema()` 在新建场景调用**严格**验证，避免历史兼容被新建入口滥用；仍保存 `string[]`。
4. `Frontend/src/renderer/src/components/Welcome/ApplicationForm.tsx`、`Frontend/src/renderer/src/components/AiChatPanel/components/WorkflowRunCard/ApplicationConfigPrerequisiteCard.tsx`、相关应用设置表单入口：仅在必要时透传**实际已保存**数组作为 baseline。初始应用无 baseline。`SettingsPage/settingsConfigChanges.ts` 维持现有差异比较语义；不能因规范化写回整份老管理员数组。
5. `Frontend/src/main/managedWorkspace.ts`：保持工作区**读取兼容**，不可对旧值套新格式正则拒绝打开；继续检查字段类型与权限依赖。
6. `Frontend/src/renderer/src/components/AiChatPanel/components/WorkflowRunCard/ConfigCardBoundary.tsx` 与其他配置卡预检：不得对历史只读快照做新增格式强校验；对**真正提交的管理员变更**用 baseline 严格校验。
7. 文案同步：`Frontend/src/renderer/src/service/applicationPagePlanning.ts` 等仅需更改“纯 subjectId”措辞，不修改正常规划/Build 流程。

**特别防回归**：`ApplicationConfigEditor` 是新建、设置、对话卡的共享组件；不得新增 `InitialAdminModal` 或另一套“工号+姓名”表单。

### 4.2 Backend：校验函数与写入入口分离

建议新增 `Backend/app/services/application_config/initial_administrators.py`（名称可按项目规范调整），至少暴露：

```python
parse_for_initialization(value: str) -> AdminMember   # new -> split; legacy -> 原值/原值
validate_admin_change(before: list[str], after: list[str]) -> list[str]
validate_new_admins(values: list[str]) -> list[str]       # 新建/首次启用，严格新格式
```

`AdminMember` 内部字段为 `member_id`, `member_name`, `legacy`，`value` 不因解析而修改；无须改变 `application.json` schema。

**修改文件及职责**：

| 文件 | 改造要求 |
| --- | --- |
| `Backend/app/services/application_config/schema.py` | `validate_application_snapshot()` 仍采用历史兼容；`validate_application_configuration()` 保留完整能力依赖校验，但**不要仅凭当前完整快照判断“新增还是历史”**。在有 baseline 的提交路径增加新值严格校验，避免无关改动被旧值阻断。 |
| `Backend/app/services/application_config/service.py` | `_normalize_initial_administrator_subjects` 和 `preview/apply` 按原始配置 `before` 校验新增项；未传 `initial_administrator_subjects` 的变更不清理历史值；首次启用必须严格校验所有管理员。 |
| `Backend/app/services/application_config_orchestrator.py` | `_merge(current, changes)` 对明确包含 `authorization.initialAdministratorSubjects` 的更改使用 `before/after` 校验；即使传入与当前相同数组，也不可产生改变；首次从关闭到开启权限时强制全部新格式；保留原有原子提交、`configRevision`、事务恢复机制。 |
| `Backend/app/services/authorization_manifest.py` | 验证配置具有至少一个合法且可解析的初始管理员；既有历史配置可以使用，不把初始管理员写进 RequirementSpec/Manifest 的业务角色。 |
| `Backend/app/services/builtin_authorization_seed.py` | 以 `parse_for_initialization` 投影 `role_member`：`member_id=employeeId/legacySubject`, `member_name=name/legacySubject`；按 `member_id` 冲突检测，避免前缀相同/姓名不同的重复行；保留现有 Seed `MAX_ADMINS`、Payload、fingerprint 与 idempotencyKey 计算方式。 |
| `Backend/app/services/builtin_authorization_orchestrator.py` / Seed runtime | **原则上不改编排逻辑**；回归验证：配置变更造成的指纹变化不得自动重播已完成或不确定的 Seed。若发现历史流程会自动触发 POST，仅修复该安全边界，不扩大重构。 |
| `Frontend/src/renderer/src/typings/application.ts` | 保留 `string[]` 类型，可补充注释说明“新增项为复合格式，旧值可读取”；不改名称和字段契约。 |

**写入校验推荐次序**：

```text
读取原应用配置及 configRevision
  → 解析变更字段是否涉及初始管理员/首次启用权限
  → 未涉及：保持原始数组（兼容历史）
  → 涉及：根据原始 before + after 逐项判断保留值、新值与员工号冲突
  → 基于最终配置继续现有依赖与配置结构验证
  → 原子持久化 / configRevision 更新
```

禁止通过放宽全局写入校验使恶意客户端绕过新格式。

### 4.3 Builtin Seed 的具体预期

原代码（需改）：

```python
"role_member": [
    {"role_id": ADMIN, "created_by": "SYSTEM", "member_id": subject, "member_name": subject}
    for subject in sorted(admins)
]
```

目标伪代码：

```python
admins = parse_admins_for_initialization(subjects)
# member_id 去重/冲突检查；新格式与历史值混合时也不允许自然键冲突
"role_member": [
    {"role_id": ADMIN, "created_by": "SYSTEM",
     "member_id": admin.member_id, "member_name": admin.member_name}
    for admin in sorted(admins, key=lambda item: item.member_id)
]
```

**不可变性要求**：对历史旧配置使用旧算法产生的既有 Seed 行与请求指纹应保持不变（列表顺序、字段、格式都不变化）。对新格式则新生成行确定性参与 Seed Fingerprint 和 Idempotency Key。不得单独编造稳定幂等键或重用旧身份提交不同 Payload。

## 5. PR2：模板工程（非 Builtin / JDBC Bootstrap）

**主要文件**：

`template-source/code/backend/extensions/authorization/src/main/java/com/cmbchina/backend/auth/bootstrap/AuthorizationBootstrapCommand.java`

实施步骤：

1. 保留 `application.authorization.initialAdministratorSubjects` 为 JSON **字符串数组**，不改变文件路径或启动脚本参数。
2. 增加 Java 内部的 `AdminMember`（`memberId`, `memberName`）解析结构/方法；新格式拆分，历史格式兼容为原值/原值。与 Python 保持相同姓名长度、员工号、斜线、字符与身份去重规则。
3. 在 `BootstrapInput` 内将 `administratorSubjects: List<String>` 调整为内部 `List<AdminMember>`（若保留旧成员变量也必须集中解析一次），相应调整 `dryRun` 数量统计；不要在 SQL 插入时重复 `split("/")`。
4. `ensureInitialAdministrators()`：查询条件维持 `role_id + member_id`；INSERT 分别绑定 `admin.memberId`、`admin.memberName`；保留 `created_by='SYSTEM'`。
5. 已存在同 `role_id/member_id` 时继续 **skip**，**不更新 `member_name`**，不清空 `role_member`，不修改资源/角色行。禁止改成 UPSERT/`ON DUPLICATE KEY UPDATE`。
6. 保持既有事务、建表核验与严格回滚；错误不打印原始姓名、员工号等个人数据。
7. 若有 `devtools/standalone-adapter` 的替换源码，仅在确认其确实覆盖此 Bootstrap 代码时同步适配；不改 generate/update API 或代码组装契约。

**无需修改**：

- `template-source/code/backend/extensions/authorization/sql/authorization/schema.sql`。
- `RoleMember.java`、`RoleMemberPO.java`、`RoleMemberMapper.xml`、Repository/Controller。
- `bootstrap-authorization.sh` / `.ps1` 的执行流程（除非测试发现参数读取受影响；禁止无关重写）。

## 6. 已初始化应用的行为（必须通过回归测试）

### 6.1 已存在成员时

设表中已有 `role_id=SYSTEM_ADMIN, member_id=00123456, member_name=00123456`：

- 后续用户把配置改成 `00123456/张三`：允许提交合法新配置，但需显示**明确提示**“本次配置变更不会自动修改已初始化的权限成员表；请通过权限成员管理或受控数据修复处理。”。
- **不执行** `UPDATE role_member SET member_name=...`、`DELETE` 或重置自动 Seed 状态。
- Java Bootstrap 再运行时已有行跳过，姓名仍为 `00123456`。
- SaaS Seed 不是成员编辑接口：其 `ROLE_MEMBER` 自然键是 `role_id+member_id`；身份核对字段当前不包含 `member_name`。因此**不能把重新 Seed 的 SUCCEEDED 当作姓名已更新**。

### 6.2 Builtin Orchestrator 安全状态

- 原 Seed 已 `seed_ready` 时不能因为姓名变更自动提交新 Seed POST；保持旧操作证据与输入变化保护。
- 原操作处于 `RUNNING/UNKNOWN`：不因管理员输入改变而发第二次 POST；进入既有 `inputs_changed` / 待核对分支。
- 全新工作区、尚未发起权限 Seed：使用当前**已保存且符合合法合同**的复合值初始化。
- 历史工作区尚未完成首次初始化：旧格式仍按老规则初始化（`member_id=member_name=旧 subject`），保证兼容性。
- 如现有配置更新会触发权限 bootstrap 重新进入，仅允许已有操作 GET 核对，不能因此增加重放动作。

### 6.3 与当前登录用户的边界

- 任何初始管理员输入不需要 `getSapId()`、不需要与登录用户相等，不调用额外的员工查询/校验 API。
- 保留运行态权限查询通过当前登录者身份与 `role_member.member_id` 精确匹配的机制；这是将来该管理员本人登录时的正常判权逻辑，而非本次初始化的输入前置条件。

## 7. 测试清单（Codex 必须补齐）

### 7.1 共享测试向量（TS / Python / Java 同步）

| 输入 | 新增/修改 | 历史读取 | 初始化投影 |
| --- | --- | --- | --- |
| `00123456/张三` | 通过 | 通过 | `00123456`, `张三` |
| `00000001/欧阳娜娜` | 通过 | 通过 | `00000001`, `欧阳娜娜` |
| `1234567/张三` | 拒绝 | 旧值若原样存在则兼容 | 旧值/旧值 |
| `123456789/张三` | 拒绝 | 同上 | 旧值/旧值 |
| `00123456` | 拒绝新增 | 通过 | `00123456`, `00123456` |
| `ops@example.com` | 拒绝新增 | 通过 | 原值/原值 |
| `00123456/` | 拒绝新增 | 若历史原样存在则仅兼容读取 | 原值/原值（仅历史） |
| `00123456/张三/别名` | 拒绝新增 | 若历史原样存在则仅兼容读取 | 原值/原值（仅历史） |
| `00123456／张三` | 拒绝新增 | 历史原样保留 | 旧值/旧值 |
| `00123456/张三`, `00123456/李四` | 员工号冲突，拒绝 | 历史原样读取 | 不得产生两条同自然键记录 |
| `00123456`（历史）+ `00123456/张三`（新增） | 冲突，拒绝并存 | 旧值允许只读 | 禁止重复自然键 |
| `current-user` | 拒绝 | 维持原有禁止规则 | 拒绝 |
| 姓名 33 码点 | 拒绝 | 历史原样读取 | 不突破 SQL 长度 |

> 注：历史兼容资格来自**已保存的原始值**，而不是它看起来像不像合法 Subject；先前若已保存包含斜线的原始字符串，读取/首次初始化仍按原值兼容。严格格式只作用于新增/替换的值，后端必须依据 before/after 差异判定，不可凭字符串本身宣称“这是历史值”。

### 7.2 XCodeAgent 前端

- 新建：合法值提交 `application.json` 后仍为 `string[]`，新格式逐项保留；8 位员工号首零不丢。
- 应用设置：原历史值展示不报错；仅改主题色可保存，管理员数组字节/顺序保持；添加旧格式新值被拒绝；替换为新格式成功且提示不自动修改已初始化数据库。
- 对话配置卡：含已有 legacy 时仅改其他配置不被 Form.Item 误拦；非法新值前端和后端双重拒绝。
- 重复员工号、不同姓名、多个管理员；不引入重复的配置表单。
- 工程启动/恢复：`managedWorkspace.ts` 读取旧数组成功。

### 7.3 XCodeAgent Backend

建议增改：

- `Backend/tests/test_application_config_mutation.py`
- `Backend/tests/test_application_config_change_resolver.py`
- `Backend/tests/test_builtin_authorization_seed.py`
- `Backend/tests/test_builtin_authorization_bootstrap.py`（如涵盖 Orchestrator，可增加其独立测试）
- 新增 `Backend/tests/test_initial_administrators.py`。

测试需覆盖：`ApplicationConfigService.preview/apply`，配置编排器 `_merge` 与冲突，快照历史读取，seed Payload 四表中的 role_member 行，按 member_id 去重，旧格式输出 fingerprint 稳定，输入变化不重复 POST，历史已初始化不 UPDATE。

### 7.4 springboot-template

建议增改：

- `scripts/ci/authorization-bootstrap/src/test/java/com/cmbchina/backend/auth/bootstrap/BootstrapContractTest.java`
- 现有权限 Bootstrap 集成测试与相关测试 fixture。

测试需验证：合法新输入 `member_id/member_name` 分列；纯旧 Subject 行保持旧写法；重复工号异常；已存在成员不更新姓名；SQL 仍只创建/使用原 4 张表；`dryRun` 不真实写入。

## 8. 实施顺序、交付物与验收

### PR1 — 先修改 XCodeAgent

1. 公共解析与写入边界校验（Backend + TS 共用测试向量）。
2. 共用前端管理员输入与三入口校验；保持旧工作区读取。
3. Builtin Seed 行投影；验证历史 Fingerprint 不变及新值投影正确。
4. 回归已完成/运行中 Seed 操作保护。

### PR2 — 再修改模板工程

1. Java Bootstrap 内部解析与 `role_member` INSERT 改造。
2. 保留已存在成员 skip 行为，添加测试。
3. 验证模板组装、generate/update 不变；不修改 DDL 与 Mapper。

### PR3 — 跨项目联调验收

- **新建 Builtin**：输入 `00123456/张三` → Schema → Seed → `role_member(member_id=00123456, member_name=张三)`。
- **新建 DBID/直连**：同一输入 → 原 Java Bootstrap → 同样两列。
- **历史未初始化**：旧 `ops@example.com` 不因新版阻断，首次初始化产生原值/原值。
- **历史已初始化**：打开应用、修改主题正常；修改姓名不自动更新已存在行、不重播 Seed、不清空历史操作身份。
- **失败/UNKNOWN**：既有手动核对和安全恢复逻辑不失效；不得为“修正姓名”绕开幂等或直接重试 INSERT。

**Codex 交付报告必须包含**：

1. 两仓库实际修改文件清单及模块归属；明确哪些文件未改（SaaS、DDL、PO、Mapper、登录判权）。
2. TS/Python/Java 核心解析与校验规则对照，说明新值如何严格校验、历史值如何获得保留资格。
3. 新旧格式的 Builtin 与 JDBC 初始化 SQL/载荷测试证据（使用虚构员工号/姓名）。
4. 测试命令、通过/失败结果；如仓库依赖无法安装，说明未运行原因，不得宣称通过。
5. 显式证明不会自动更新/删除既有 `role_member`，不会为历史 Seed 产生新的不安全 POST。

## 9. 可直接交给 Codex 的提示词

```text
请基于两个仓库最新代码实施附件《XCodeAgent + 模板工程：初始管理员“8位员工号/姓名”改造实施方案》：

XCodeAgent: https://github.com/YYYWYF/XCodeAgent.git (dev_agent)
模板: https://github.com/Hupy2118/springboot-template.git (dev)

目标：新建和新修改的 initialAdministratorSubjects 元素必须是“8位员工号/姓名”；首次初始化写入 role_member.member_id=工号、member_name=姓名。兼容历史已存储的纯 Subject 格式原样读取和无关配置修改，历史尚未初始化仍沿用 member_id=member_name=原值。初始管理员不必是当前登录用户。

约束：保留 application.json 字段结构；复用 ApplicationConfigEditor，不另做输入组件；不修改 SaaS/DDL/PO/Mapper/认证判权；不自动 UPDATE/DELETE 已初始化成员数据，不清除 Seed operationId/幂等键、不自动重播 Seed。严禁以“历史兼容”放行新增非法管理员。

按 PR1 → PR2 → PR3 实施；逐项补齐 TS/Python/Java 测试，提交修改文件清单、测试命令与结果，并确认现有 Build 和权限初始化流程不回归。
```
