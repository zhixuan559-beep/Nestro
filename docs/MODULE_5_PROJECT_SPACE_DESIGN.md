# Nestro 模块5：项目空间整体设计与验收规范

> 状态：整体设计基线（2026-11-19）；当前实现仍不完整。本文区分已核实事实与目标设计，不能把设计条目当成已交付能力。

## 1. 当前工程基线

- 产品仓库：`zhixuan559-beep/Nestro`
- 开发分支：`feature/module-5-project-space`
- PR #4：Draft，未合并。
- 当前 head：`cbb736485e94b413ba048fbb9fb37ea2223b895f`
- 基线：`refactor/phase-2-architecture`，head `e3585fcb9fd4cd5ec24dba6afef3268b06e16b71`
- 内部同步 PR #5 已将最新基线合入模块5分支；当前 ahead 84 / behind 0。
- 同步后 CI #23：run `44596534790`，正在运行，未出最终结果。
- PR #4 旧 head 的 CI #22 只执行生产编译、JVM 单测、Lint 和 Android instrumentation 测试源码编译；没有实际执行 emulator instrumentation，不能作为模块验收。

## 2. 模块目标与边界

项目空间不是单纯的项目列表，而是 Nestro 的数据隔离、资源归属、跨项目授权和可迁移单元。每个项目应拥有清晰的数据边界；用户可以显式共享资源或授权其他项目读取/写入，但不能因默认值、UI 过滤或数据存储分散而意外越权。

本模块交付范围：

- 项目生命周期：创建、重命名、切换、最近使用排序、归档、恢复、复制、删除。
- 项目数据归属：会话、消息、项目指令、知识文档/片段、项目记忆、产物、项目设置、资源关联。
- 资源归属：专家、专家团、技能、工作流支持项目级归属、显式全局共享及授权共享。
- 跨项目权限：方向明确的 READ / WRITE 授权、即时撤权、可审计。
- 项目迁移：独立项目导出/导入包，不依赖全设备备份。
- 生命周期一致性：Room、文件、SharedPreferences 之间的复制/删除/导入可恢复，进程中断后不留下半成品。
- 测试与验收：单元、Room、存储恢复、权限、UI、Android emulator instrumentation 和现有系统回归。

不在本模块内实现：云端同步、多用户/团队账号、服务端权限管理、任意第三方代码执行。

## 3. 已有代码与已证实缺口

### 3.1 已有实现（代码存在，不等于已验收）

- `ProjectEntity` 保存在 Room；包含 id、name、instructions、createdAt、archivedAt。
- 项目会话/消息按 projectId 过滤。
- 项目归档/恢复通过更新 archivedAt 实现。
- `NestroDao.duplicateProject()` 使用 Room transaction 复制项目、会话/消息、知识文档/片段和项目记忆，并重建关联 ID。
- `ArtifactStore` 在独立文件目录保存项目产物；现有 UI 在 Room 复制前先复制产物。
- `ProjectResourceScopeStore` 用 SharedPreferences 保存专家/团队/技能/工作流的资源范围；缺少 scope 的旧资源默认视为全局。
- `ProjectMemorySettingsStore` 保存项目自动记忆开关。
- `ProjectAccessControlStore` 保存 source→target 的 READ/WRITE 授权与审计记录。
- READ 初版已接入知识库列表/检索、聊天知识与项目记忆上下文，以及部分专家/团队/技能/工作流可见性过滤。
- 删除项目有 pending marker，启动时由 `ConversationPersistenceService.restore()` 尝试恢复未完成删除。

### 3.2 已核实缺口（必须修复）

1. **单项目导出/导入缺失。** 全设备 JSON 备份不等于单项目迁移包。
2. **WRITE 权限不完整。** 当前只在 KnowledgePage 跨项目删除知识文档时调用 `authorize(..., WRITE)`；专家、团队、技能、工作流编辑/覆盖/删除没有统一写入门禁。
3. **新资源默认归属缺失。** 当前仅有资源“全局/当前项目”切换入口；新建资源没有统一设置当前项目 scope。由于缺少 scope 被解释为全局，新建资源可能意外对所有项目可见。
4. **跨存储复制非原子。** 产物先复制到文件系统，再执行 Room transaction，之后才写资源 scope 与项目记忆设置。Room 成功后 Preferences 写入失败或进程退出，会留下部分副本。
5. **删除恢复清理不完整。** 启动恢复路径清理 Room/项目列表/草稿，但没有统一清理 resource scopes、project memory settings 和 cross-project grants。
6. **授权残留。** 正常删除项目时未清理 source/target 涉及该项目的授权关系；授权审计的保留策略也未定义。
7. **空 scope 孤儿。** 删除项目时从资源范围中移除 projectId；如果这是最后一个范围，资源会留下空 scope，`isVisible` 对所有项目都返回 false，造成资源不可见。
8. **归档任务策略缺失。** 当前归档规则只检查默认项目/当前项目，没有检查运行中、暂停中或等待用户的 Phone Agent / 工作流任务。
9. **复制任务策略缺失。** 当前项目复制复制内容和产物，不复制 Phone Agent task rows；需要明确该行为是有意的安全边界，并覆盖工作流运行记录。
10. **全设备备份权限语义不一致。** 备份包含 resource scopes 与项目记忆设置，但没有显式包含 cross-project grants/audit；恢复时如果不清理目标设备原有 grant，可能保留指向被替换项目 ID 的旧授权。
11. **测试未实际运行。** PR #4 的 module-5-ci workflow 只编译 Android instrumentation test sources，不执行测试；`ProjectAccessControlStoreTest`、`ProjectSpaceLifecycleTest` 和 `ArtifactStoreTest` 均位于 androidTest，CI #22 没有运行它们。
12. **分支基线风险已处理。** 原模块5分支曾落后基线 28 commits；已通过内部 PR #5 同步，当前落后 0。后续不能再从旧 head 继续开发。

## 4. 目标架构

### 4.1 单一项目生命周期协调入口

新增 `ProjectSpaceCoordinator`（或等价应用层协调器），作为 UI 和数据层之间的唯一项目生命周期入口，统一承接：

- create / rename / switch / archive / restore
- duplicate / delete
- export / import
- startup recovery

UI 不直接串联 Room、ArtifactStore 和 Preferences 的写操作。Repository 负责单一存储的读写；Coordinator 负责跨存储流程、权限验证、事务日志、回滚/恢复和状态返回。

### 4.2 数据归属清单

| 数据 | 权威存储 | 项目边界 |
|---|---|---|
| 项目元数据、会话、消息 | Room | projectId / conversationId |
| 知识文档、片段、FTS | Room | projectId，文档删除需同步索引 |
| 项目记忆 | Room | projectId |
| Phone Agent 任务与轨迹 | Room | projectId / taskId |
| Markdown 产物与文件索引 | filesDir + ArtifactStore index | projectId |
| 专家/团队/技能/工作流定义 | 当前为 SharedPreferences | 由显式资源 scope 绑定项目 |
| 资源 scope、项目记忆设置、跨项目授权 | 当前为 SharedPreferences | 严格 codec + 单次 commit；由 Coordinator 统一维护 |
| 最近打开时间 | SharedPreferences | 每项目独立 |

Room 是结构化业务数据的权威源；SharedPreferences 仅保存轻量配置/授权元数据；文件系统保存产物正文。禁止 UI 直接跨层拼接写操作。

### 4.3 资源归属规则

- 新建专家、团队、技能、工作流默认归属当前活动项目。
- 资源“设为全局共享”必须是用户显式动作；全局共享影响所有项目，操作时要明确提示。
- 旧版本缺少 scope 的资源为兼容性考虑，迁移时明确标记为 legacy-global；不得让新建资源继续沿用“缺少 scope = 全局”的默认路径。
- 项目范围资源可显式授权其他项目 READ；WRITE 授权必须独立校验。
- 删除项目时，如果资源仅属于该项目，必须先要求用户选择：转为全局、迁移到其他项目或删除资源；不能静默生成空 scope。
- `FILE` scope 若不承载真实文件资源则移除；ArtifactStore 继续使用明确的 projectId 归属。

## 5. 跨项目权限模型

### 5.1 方向定义

授权记录 `sourceProjectId → targetProjectId` 表示：目标项目可以访问来源项目的资源。

- READ：查看/检索来源项目允许共享的知识、记忆、专家、团队、技能、工作流和产物（具体暴露范围由产品 UI 确定）。
- WRITE：在 READ 基础上，允许目标项目修改来源项目被授权资源。
- WRITE 必须蕴含 READ；codec 和数据层都要强制。
- 默认无跨项目权限；授权需用户明确确认；撤权立即阻断后续操作。

### 5.2 全路径强制

权限不能只在按钮显隐时检查。所有数据层写入口必须接收调用项目 ID，并验证目标资源所属项目及 WRITE grant。UI 同步禁用无权限操作并标注只读来源。

至少覆盖：

- Knowledge：导入、覆盖、重命名、删除、索引重建。
- Experts：提示词、profile、revision、导入覆盖、删除。
- Teams：成员、分工、保存、删除。
- Skills：prompt、metadata、启停、导入覆盖、删除。
- Workflows：定义、节点、引用、保存、删除。
- Artifacts：保存新版本、覆盖、删除、导出。
- Memory：新增、编辑、删除。
- Project metadata：指令、归档、恢复、删除。

本地项目内操作不需要跨项目 grant，但仍需校验项目存在且未归档。全局资源修改需明确提示“此修改影响所有项目”。

### 5.3 授权生命周期

- source 或 target 项目归档后，涉及该项目的 grant 不得继续生效。
- 归档时撤销或冻结相关 grant，并写审计；恢复后默认不自动恢复授权，需用户重新确认。
- 删除项目时清理所有 source/target 涉及该项目的 grant；审计记录可保留为 tombstone，不得残留可用授权。
- 审计记录记录授权、撤销、允许/拒绝的 READ/WRITE 操作与原因；明确保留上限和滚动策略。
- 全设备备份默认不恢复跨项目授权；恢复后清除本机旧 grant，用户重新授权。审计记录按设备本地安全日志处理，不随单项目包导出。

## 6. 项目生命周期与跨存储一致性

### 6.1 统一操作日志

在应用私有目录建立 AtomicFile 项目操作日志，包含 operationId、operationType、source/target project IDs、阶段、数据版本、必要快照引用和校验和。阶段至少包含：

`PREPARED → ROOM_APPLIED → FILES_APPLIED → PREFS_APPLIED → COMMITTED`

日志写入必须先于跨存储修改。每一步可重复执行且具备幂等性。成功后清理 staging 与日志；异常/进程重启后由 Coordinator 恢复。

### 6.2 复制项目

1. 校验源项目存在、未归档、无正在运行的项目写操作；目标 ID 新建、名称唯一、项目数量未超限。
2. 建立 journal，创建目标项目 staging。
3. Room transaction 复制会话/消息、知识文档/片段/FTS、记忆；所有实体 ID 重映射，memory sourceConversationId/sourceMessageId 同步映射。
4. ArtifactStore 复制产物正文与索引，失败时可回滚。
5. 克隆项目私有资源定义并重映射专家/团队/技能/工作流引用；全局资源继续全局共享，不重复复制。
6. 复制项目指令和自动记忆设置。
7. 不复制运行中/可重放的 Phone Agent 任务、工作流执行实例和临时授权；是否导入完成任务历史另行定义为只读审计数据。
8. 所有步骤完成后提交 journal；任一步失败都回滚目标项目及 staging，不能留下半成品。

### 6.3 删除项目

- 默认项目和当前活动项目不可删除。
- 有会话/消息、知识、记忆、产物、任务、未保存草稿、运行请求或项目私有资源时，不允许直接删除；先归档或处理数据。
- 删除前列出关联数据和资源 scope；用户必须先迁移/转全局/删除私有资源。
- journal 先落盘，再执行 Room、文件、scope、memory settings、grants、drafts 清理。
- 启动恢复必须完成所有存储的清理，不得只删除 Room 和项目列表。
- 删除授权关系，审计可保留 tombstone；校验没有悬空外键/索引/文件/资源 scope。

### 6.4 归档与恢复

- 默认项目不可归档；当前活动项目不可归档。
- 存在运行中、暂停中或等待用户的 Agent/工作流任务时，不允许归档，或先要求用户明确处理任务。
- 归档后保留所有项目数据，但禁止创建新会话、执行任务、导入资源和跨项目访问。
- 归档撤销涉及该项目的 grant；恢复后不自动恢复授权。
- 恢复需重新校验项目名称、项目数量和数据完整性。

## 7. 单项目导出/导入包

### 7.1 包格式

建议 ZIP 容器，至少包含：

- `manifest.json`：格式名、schemaVersion、最低应用版本、项目元数据、资源清单、记录数量、总大小和 SHA-256。
- `project.json`：项目指令、会话/消息/版本、知识文档/片段、记忆、项目设置、资源定义和依赖映射。
- `artifacts/<artifact-id>.*`：产物正文。
- 可选 `resources/`：项目私有专家/团队/技能/工作流定义。

### 7.2 导出边界

- 只导出当前项目拥有或显式关联的内容。
- 对全局资源：将项目实际使用的资源作为包内副本导出，导入后变成目标项目私有资源，不改变目标设备的全局资源。
- 不导出 API Key、Keystore 数据、跨项目 grant、授权审计、设备临时权限、运行中的 Agent 动作和工作流执行状态。
- 导出前做一致性快照；文件不存在、索引损坏、ID 引用缺失或 checksum 不匹配时中止，不生成半包。

### 7.3 导入边界

- 默认创建新项目，不覆盖现有项目。
- 导入预览显示项目名、记录数量、资源数量、文件大小和冲突。
- 生成新 projectId，并重映射 conversation/message/document/chunk/memory/artifact/expert/team/skill/workflow IDs。
- 校验所有引用闭合、数量/大小限制、文件类型、checksum、schemaVersion 和权限声明。
- 使用同一项目操作 journal 原子导入；失败完整回滚。
- 不自动恢复跨项目授权；导入后由用户重新授权。
- 资源重名采用预览阶段的显式处理策略，不能静默覆盖。

## 8. 迁移与兼容

- 旧版 SharedPreferences 项目列表严格解析；损坏时保留原始值并进入只读，不静默截断或重建覆盖。
- 旧资源缺少 scope 的一次性迁移为 legacy-global；迁移后所有新资源必须写入 activeProjectId。
- 项目记忆旧版全局开关仅迁移为 default 项目设置；其他项目默认关闭。
- 数据库新增表/索引时提供 Room migration，并测试真实旧版本数据库迁移，不仅测试 in-memory 新建库。
- 导入包 schemaVersion 与应用版本解耦，提供向前兼容策略和明确拒绝条件。

## 9. 必须新增/补齐的测试

### JVM 单元测试
- 项目名称/ID/数量/归档规则、scope codec、grant codec、WRITE⇒READ、资源 ID 映射、manifest/checksum、包大小限制。
- 旧资源 legacy-global 迁移；新资源默认 active-project。
- 空 scope、重复 grant、悬空 projectId、损坏审计日志全部 fail-closed。

### Room / 存储测试
- 复制项目完整复制会话/消息/知识/记忆并重新映射所有关联 ID。
- 产物文件与索引复制/删除；文件写失败和 index 写失败回滚。
- 项目删除、归档、恢复、导入各阶段注入故障；进程重启后 journal 恢复结果一致。
- 删除项目后没有 scope/grant/memory setting/draft/file/task trace 悬空引用。
- Room 旧版本迁移真实数据库测试。

### 权限集成测试
- READ-only 可以读取/执行，不可以修改/覆盖/删除。
- WRITE 可执行被授权写操作；撤权后每条写路径立即拒绝。
- 直接调用 repository/service 绕过 UI 仍被拒绝。
- archived/deleted source 或 target 项目不能被授权访问。
- 全局资源修改有明确确认路径。

### Android instrumentation / UI
- 创建、重命名、切换、最近使用排序、归档/恢复、复制、删除。
- 新建资源默认项目私有；显式全局共享后跨项目可见。
- READ/WRITE 授权、撤权和只读 UI 状态。
- 项目导出/导入完整往返、冲突预览、取消与失败回滚。
- 手机/折叠屏/平板窗口尺寸下项目空间主要交互可用。

## 10. CI 与验收门槛

当前 module-5-ci workflow 不足以验收模块，因为它没有实际执行 androidTest。完成模块前必须：

1. 保留生产 Kotlin 编译、JVM unit tests、Android lint、instrumentation test source compile。
2. 增加 emulator job，真正执行 AndroidJUnitRunner；使用明确 timeout，并上传原始测试报告和失败日志。
3. emulator 测试至少覆盖项目生命周期、ACL、资源 scope、ArtifactStore、项目导入/导出和 crash recovery。
4. 同时运行产品 Android workflow 的完整回归；所有 job 必须在同一 commit SHA 上成功。
5. 下载并检查原始测试报告，核对实际测试数量与结果；只看绿色徽标不算验收。
6. PR #4 在全部功能、设计清单、测试与 CI 通过前保持 Draft；不合并、不锁定。
7. APK 和真机验收继续按全项目阶段安排，不在模块5开发中途提前要求真机验收。

## 11. 实施顺序

- A. 完成设计门禁与代码地图：逐项核对 PR #4 既有实现、最新基线和所有调用方。
- B. 建立 ProjectSpaceCoordinator、统一 operation journal 和 startup recovery。
- C. 修复新资源默认归属、scope 生命周期、跨项目 READ/WRITE 全路径强制与授权清理。
- D. 统一项目复制/删除/归档/恢复的跨存储事务。
- E. 实现单项目 ZIP 导出/导入、预览、校验、ID 重映射和原子回滚。
- F. 补齐 JVM/Room/instrumentation/UI/crash recovery 测试与 CI emulator job。
- G. 对照本规范逐条验收；全部通过后更新总控台并锁定模块5。

**完成标准：** 不以 UI 存在、代码提交、JVM 测试通过或单个 CI job 绿色作为模块完成。必须完成全量功能、数据/权限/恢复边界、项目迁移、跨存储一致性和 Android emulator 完整回归。
