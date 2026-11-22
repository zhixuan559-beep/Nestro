# NESTRO 十九模块统一功能核对（第一轮）

核对分支：`integration/19-modules`  
初轮源码核对基线：`6083f6ad026d73c0e5ad5a04d50d98c7f3baf9e3`；后续专项核对以文末各节记录的整合分支 HEAD 为准。  
日期：2026-11-22

> 本文是源码与现有设计文档的第一轮静态功能核对，不代表 Kotlin 编译、单元测试、Android instrumentation、模拟器、真机或外部 API 验收已通过。代码已存在、接线已存在、运行验证通过是三个不同状态。

## 统一判断

十九个模块的主要源码已汇入统一分支，但不能据此认定十九模块所有设计功能均已实现。当前存在三类事项：

1. **代码缺口/不完整闭环**：需要继续开发。
2. **代码已有但未验证**：需要后续统一编译、测试和设备验收。
3. **外部配置/服务商依赖**：需要部署、密钥、正式信任根或服务商实际授权，源码本身无法替代。

## 十九模块第一轮状态

| 模块 | 第一轮判断 | 已确认的边界或缺口 |
|---|---|---|
| 1 专家系统 | 主体已实现，协作产品能力不完整 | 自动组队、DAG 工作包、并行执行和成果交接已有代码；并行专家团队新增成员级持久检查点与未完成成员恢复，DAG 工作包按结果持久化并恢复；主导交接模式已增加成员结果、下一成员和委派上下文检查点，恢复时从下一位专家继续。双向自由交流、专家私聊、可编辑任务图、暂停/恢复/返工控制、完整历史分页、临时团队保存/解散、结构化验收/返工协议仍未形成完整闭环。 |
| 2 模型管理 | 主体已实现，外部联调待完成 | OpenAI/DeepSeek/Anthropic/Gemini/硅基流动/魔搭等服务商协议与模型注册表代码已存在；魔搭 OAuth 服务部署、第三方应用注册、真实授权范围与推理权限确认仍依赖外部配置；账户余额/免费额度动态同步未实现。 |
| 3 聊天系统 | 主体已实现，模型上下文适配不完整 | 多会话、流式请求、编辑、重新生成、失败重试、版本与进程重建恢复代码已存在；上下文预算目前使用固定默认窗口估算，未按每个模型的真实上下文窗口动态适配。 |
| 4 数据与备份 | 备份恢复主体已实现，资源覆盖不完整 | JSON 备份有校验、事务日志和中断恢复，并覆盖 Room、项目、知识、记忆、技能、作品和日历；媒体编辑器新增独立 ZIP 工程备份/恢复，包含时间线与原始素材、逐文件 SHA-256 校验和替换中断恢复日志，但尚未并入通用设备备份/项目包；附件管理和完整设备迁移仍需补齐。 |
| 5 项目空间 | 主体已实现，资源归属仍需全链路核对 | 项目隔离、归档、资源范围与项目包已有代码；媒体二进制、所有资源类型复制/删除/迁移的一致性需结合模块17/4继续核对。 |
| 6 知识库 | 主要功能代码已实现 | 多格式解析、PDF OCR、FTS/BM25、本地 BGE 向量与混合检索已存在；Room 12→13 迁移及对应仪器测试源码已存在。统一构建与设备性能仍待验证。 |
| 7 我的记忆 | 主体已实现 | 自动提取、项目隔离、召回、导入导出和来源关联已有代码；需在统一验收中验证隐私过滤、跨项目授权、删除级联和备份恢复一致性。 |
| 8 Phone Agent | 有受控执行闭环，通用目标能力仍有限 | 观察—规划—执行—页面核验、风险暂停、用户接管、持久任务和虚拟屏代码已存在；search_web 已支持 DuckDuckGo/Google/Bing 三种 HTTPS 搜索入口，公开媒体直链可下载并导入项目；公开 API/Intent 优先路由、复杂登录/验证码接管和不同 App 的通用兼容仍未形成可保证的完整闭环。 |
| 9 语音系统 | 主要功能代码已实现 | Android 识别、本机 TTS、远程 STT/TTS、实时双向语音代码已存在；真实服务商密钥、协议兼容和真机音频路由尚未统一验证。 |
| 10 我的作品 | 主体已实现 | Markdown 作品保存、版本、预览、分享、导出、删除与备份恢复已有代码；需统一验证项目隔离、文件丢失恢复和备份回滚。 |
| 11 专家模板 | 功能代码已实现，测试未执行 | 内置/自定义模板、编辑、复制、删除、搜索、套用和 JSON 导入导出已有代码；JVM/UI 回归与 CI 尚未验证。 |
| 12 双模型对比 | 功能代码已实现，测试未执行 | 双模型并行请求、独立失败、停止、复制和响应布局已有代码；Kotlin 编译/JVM/设备验收尚未完成。 |
| 13 MCP 工具中心 | 受控远程 MCP 已实现，范围有限 | HTTPS Streamable HTTP、工具发现、逐工具授权、参数预览、审计和撤销已有代码；本地 stdio Server 与模型原生 tool-call schema 适配不在当前范围；统一测试未执行。 |
| 14 GitHub 工作台 | 主体已实现，测试未执行 | GitHub/GitLab/Gitee PAT、仓库操作、手机端编辑、Diff、冲突、历史和回滚已有代码；索引并发、删除恢复、凭据回滚与 Push 结果保护已补强；编译/JVM/设备验收待执行。 |
| 15 AI 共建工作台 | 源码已整合，验收未执行 | 补丁审查、逐文件批准、事务回滚、静态扫描、Actions Runner、JUnit/SBOM 解析、NestroApp 聊天回流与 CI 依赖证据已接入；真实 GitHub Actions、SBOM 产物与测试尚未运行。 |
| 16 统一能力中心/插件工坊 | 基础插件工坊已实现，安全能力不完整 | 本地声明式插件生成、预览、安装/启停、版本替换、回滚和卸载已有代码；本轮新增工具能力契约 SHA-256 指纹锁定，安装/升级/启用/恢复时校验工具 ID、权限、确认要求和描述是否变化。插件发布者数字签名/可信来源验证、独立进程资源沙箱、完整审批审计视图和远程插件目录仍未完成；本轮新增本地 JSON 包导入/导出入口，导入默认关闭并重新校验依赖。当前不是开放式插件市场，也不执行任意代码。 |
| 17 智能剪辑 | 基础编辑/渲染代码已整合，范围有限 | 素材库、单视频轨+单音频轨、拆分/移动/裁剪、撤销重做、预览、MP4 导出、SRT 管理和后台任务已有代码；新增 ZIP 工程备份/恢复入口，恢复会替换当前项目的时间线、素材索引、原始素材和导出文件，并阻止在媒体任务运行时覆盖。多视频轨/PIP、转场、速度曲线、字幕烧录仍未实现，媒体二进制仍未并入通用备份。Media3 1.9.0 依赖仍需编译/设备验证。 |
| 18 日历 | 功能代码已整合，验收未执行 | 独立 Room 日程库、月/周/日视图、重复规则、WorkManager 提醒、系统日历读写/导入导出、备份恢复已有代码；WorkManager 提醒可能受系统省电策略延迟，不等同精确闹钟；需统一测试和真机验证。 |
| 19 多模态服务与社区生态 | 核心代码已整合，外部配置与测试未完成 | OpenAI 转写/兼容视觉、媒体 AI 持久任务、SemVer 2.0 依赖求解、原子安装、独立信任根与签名撤销已有代码；真实 API Key、服务商专属端点、正式社区目录/信任根/撤销 URL 需要外部配置，测试未执行。 |

## 已确认的高优先级问题

### A. 工作流执行链路

源码位置：
- `app/src/main/java/app/nestro/data/WorkflowEngine.kt`
- `app/src/main/java/app/nestro/data/WorkflowExecutionCoordinator.kt`
- `app/src/main/java/app/nestro/ui/NestroApp.kt`
- `app/src/main/java/app/nestro/ui/WorkflowUiState.kt`

本轮已补入统一分支：
- UI 对需审批步骤提供“授权一次 / 当前任务全部授权 / 拒绝”三种明确选择；“当前任务全部授权”只在本次工作流执行中生效，不写入全局或项目永久权限。
- 审批弹窗不可通过点击外部或返回键隐式关闭；未选择时任务持续等待，不自动批准或拒绝。用户主动停止任务会结束等待。
- UI 执行路径显式提供审批等待回调；引擎/协调器在未提供审批回调的非 UI 调用路径仍 fail-closed，避免无界面调用意外放行。
- 编辑器提供 condition、approvalRequired、parallelGroup 配置；并行组限制为 2–4 个启用步骤。
- 引擎实际按连续并行组并发执行，汇总结果按原步骤顺序回传。
- 并行波次采用 supervisor 语义收集各成员结果；一个成员失败时，已成功成员先写入检查点，失败成员不写入成功检查点，恢复时只重跑未完成成员。
- 每个完成步骤写入持久检查点；恢复时校验工作流定义指纹与原始输入指纹，拒绝在定义或输入变化后错误续跑。
- AgentTaskCoordinator 的 inline handler 使用稳定任务标识；用户重新打开工作流并继续时可重新注册 handler 并恢复已有检查点。
- 并行波次中途恢复会保留已完成同组成员成果，不把同组成果提前泄漏给尚未完成的并行成员。
- 新增工作流并行、审批选择、配置编解码、顺序恢复、并行波次恢复及并行部分失败恢复回归测试源码。

仍需处理/验证：
- 暂停门闩仍是当前进程内 UI 状态；进程被杀后需要用户重新进入工作流并点击继续，不是后台自动续跑。
- 恢复检查点中的旧步骤输出为受限摘要/当前并行组结果，历史长输出不保证完整回填。
- 以上改动尚未运行 Gradle 编译或测试，不能标记为编译通过或验收通过。

### B. 专家团队闭环

源码位置：
- `app/src/main/java/app/nestro/data/ExpertProjectPlanExecutor.kt`
- `app/src/main/java/app/nestro/data/ExpertTeamExecutionCoordinator.kt`
- `app/src/main/java/app/nestro/data/ExpertTeamEngine.kt`

本轮已补入统一分支：
- 并行专家团队保存已完成成员的输出、状态与尝试次数；恢复时跳过成功成员，仅重新执行未完成/失败成员。
- 主导交接模式保存成员成果、已访问成员、委派次数、下一成员、下一子任务和交接内容；中断后从下一位专家继续，失败成员可按检查点重试。
- 恢复前核对团队、项目、任务目标、调度模式及成员角色/分工配置，配置变化时拒绝错误恢复。
- 新增成员级恢复回归测试源码。
- 自主专家项目 DAG 已改为每个工作包完成即持久化检查点，不再等待整波结束；检查点写入在协程取消时仍会完成，并行波次使用已完成依赖快照，避免并发读写共享结果表。本轮修正了检查点误写字段，统一写入 `planFingerprint`，与恢复前校验字段一致，并新增项目计划中断恢复、并行成员未结束时先保存已完成成员的回归测试源码。

仍缺：
- 完整双向自由交流、专家私聊、任务图编辑、用户可控暂停/恢复/返工和独立项目工作区仍未完成。
- 结构化验收/返工协议、任务图编辑、暂停/恢复/重做控制、双向自由交流、专家私聊和完整历史分页仍未完成。

### C. Phone Agent 目标闭环

源码位置：
- `app/src/main/java/app/nestro/phone/PhoneAgentPlanner.kt`
- `app/src/main/java/app/nestro/phone/PhoneAgentAgent.kt`
- `app/src/main/java/app/nestro/phone/PhoneAgentActionParser.kt`
- `app/src/main/java/app/nestro/phone/PhoneAgentModelClient.kt`
- `app/src/main/java/app/nestro/phone/PhoneAgentExecutor.kt`
- `app/src/main/java/app/nestro/phone/PhoneAgentService.kt`

已确认：
- 有分类规划、有限轮次观察—规划—执行、页面证据核验、风险接管与任务暂停。
- 模型动作协议已有 `search_web`，支持 DuckDuckGo、Google、Bing 三个公开 HTTPS 搜索入口，并对搜索内容做敏感信息拦截；相应 parser 测试源码已存在。
- 有公开媒体直链下载、来源页/授权标记记录、下载后导入当前项目素材库的动作链路。
- 登录、验证码、人机验证、风控和支付/最终提交等高风险节点有用户接管约束。

仍缺：
- 当前以网页搜索和无障碍 UI 执行为主，尚未形成公开 API/Android Intent 优先、无障碍 UI 兜底的统一路由。
- 不同 App 的深链、登录态、验证码、动态页面和私有媒体访问兼容仍不能保证；通用长期任务仍受单轮次数和系统调度限制。
- 不能把搜索摘要当成实时库存、交易或预订已完成证据。

### D. 数据与素材备份

源码位置：
- `app/src/main/java/app/nestro/data/LocalBackupRepository.kt`
- `app/src/main/java/app/nestro/data/ProjectPackageCodec.kt`
- `app/src/main/java/app/nestro/media/MediaProjectStore.kt`
- `app/src/main/java/app/nestro/media/MediaEditorPage.kt`

本轮已补入统一分支：
- 媒体编辑器可导出 ZIP 工程包，包含 project manifest、时间线、撤销/重做历史和原始二进制素材；导出时校验素材大小及 SHA-256。
- 媒体编辑器可导入工程包，校验文件路径、项目归属、素材数量/大小/SHA-256 和时间线引用；导入可选择替换当前项目，替换过程带恢复日志，进程中断后可回滚或完成清理。
- 导入前阻止覆盖仍有排队、运行或暂停中的渲染任务，以及排队/运行中的媒体 AI 任务。
- 新增 Android instrumentation 工程包往返、原始素材字节完整性、拒绝默认覆盖和显式替换测试源码。

仍缺：
- 媒体工程 ZIP 目前是独立备份入口，尚未并入通用 JSON 设备备份和项目包。
- 附件管理及全量设备迁移仍需补齐；导出视频文件属于可再生成产物，当前不纳入媒体工程包。

## 下一步核对顺序

1. 工作流审批、并行执行和并行部分失败恢复已补入代码，继续核对暂停/恢复、审批状态和任务生命周期边界。
2. 核对专家团队任务 DAG、事件、主模型最终验收和任务中断恢复。
3. 核对 Phone Agent 统一入口、工具目录、执行/核验和媒体素材导入闭环。
4. 核对附件、媒体二进制、项目包和全量备份恢复边界。
5. 逐模块回读实现与测试源码，清除路线图/模块清单中的过期状态。
6. 完成源码核对后，再进入统一编译、JVM/Python、Android instrumentation、模拟器和真机验收。

## 本轮明确未执行

- 已新增工作流、专家团队和媒体工程备份恢复的回归测试源码，但未执行这些测试。
- 未运行 Gradle/Kotlin 编译。
- 未运行 JVM/Python/Android 自动化测试。
- 未触发 GitHub Actions Workflow。
- 未运行模拟器或真机测试。
- 未把未验证功能标记为验收通过。

## 2026-11-22 最近对话需求补录与当前源码抽查

### 更新前历史报表漏记的需求

原第一轮审计没有完整记录以下用户明确要求；现已同步到 `IMPLEMENTATION_ROADMAP.md`，并纳入本审计后续验收：
- 创作型专家任务与 Phone Agent 执行任务混合调度：创作需要网络素材时，由主模型调用手机 Agent 搜索/下载，文件自动导入当前项目素材库，素材 ID/元信息回交创作任务；允许创作与手机执行按依赖多轮交替、串并行组合，直至最终交付。
- Mika 虚拟屏具体移植和交互要求：竖屏、缩小悬浮、实时画面和交互接管；不显示角标/按钮/手柄；点击窗口放大/缩小；角部上拉缩小、下拉放大；仅缩放预览尺寸，不破坏虚拟显示分辨率/DPI和输入坐标。
- 工作流审批三项选择：授权一次、当前任务全部授权（仅本次任务）、拒绝；未选择保持等待；关闭弹窗不产生授权/拒绝结果。
- 专家团双入口：用户手动组队操作，以及主模型根据项目目标自主拆解、选专家、分派 DAG、并行执行、汇总验收；主模型可按需协调专家与 Phone Agent，不把两种能力设成互斥模式。
- 普通聊天按需调度、专家入口加载必需 Skill/插件，以及与记忆生命周期关联的本地缓存（全局/项目/会话范围、记忆变更失效/更新）；不得混同 BGE embedding cache 或 Prompt Cache。

### 已抽查的实现证据

- **混合调度主链路已存在，不应重建第二套调度器。** `ModelLedToolDispatcher.kt` 的主模型系统提示明确规定创作/专家/手机执行可组合并多轮交替；`NestroApplication.kt` 注册 `phone_agent.execute`、`phone_agent.wait`、`media.project.assets`；`PhoneAgentExecutor.kt` 包含 `DownloadAsset` 动作；`PhoneAgentService.downloadAsset()` 使用系统 DownloadManager 下载后调用 `MediaProjectStore.importAsset()`，并返回实际素材 ID、文件信息和来源；素材目录工具可供创作任务查询项目素材。当前判断：源码主链路已落地；端到端多轮协作、任务入队/等待/取消、素材可读性和专家实际消费文件仍需测试/逐调用点核验。
- **Mika 虚拟屏底层和 Shell 启动授权链路已存在。** `MikaAdbManager` 支持 Android 无线调试 TLS 配对、发现连接端口并通过本地 ADB 启动/连接 shell-UID helper；`MikaHelperHandoffProvider` 校验 UID 2000、安装路径、签名证书 pin 和一次性 nonce；`PhoneAgentPage.kt` 提供端口/六位配对码输入、连接、创建虚拟屏和实时帧悬浮预览。该链路属于代码已实现、设备兼容性待测，不应再列为从零缺失。当前浮窗仍有“Phone Agent · 执行中 / 展开 / 收起”标题栏和折叠按钮；双击画面在 0.68/1.4 倍间切换，单击画面会向虚拟屏注入触控；右下角 26dp 缩放热区是透明的，没有可见手柄；拖动方向已符合要求（下拉放大、上拉缩小），且只改变预览尺寸、不改变虚拟屏分辨率。与用户要求相比，需移除标题/展开收起按钮，并把窗口缩放手势调整为用户指定的点击行为，同时保留正常触控接管和透明角部拖动。
- **工作流审批已有三种显式决策。** `NestroApp.kt` 显示“授权一次 / 当前任务全部授权 / 拒绝”；任务级授权变量限定在一次工作流运行作用域；未选择时等待 `CompletableDeferred`。当前弹窗 `onDismissRequest = {}`，因此返回键/点击外部不能关闭；若用户要求“关闭但保持等待”，当前缺少独立关闭入口。另需核查等待期间持久任务状态是否写成 `WAITING_FOR_USER`，而非 UI 协程挂起时仍显示 RUNNING。
- **专家自主项目与团队检查点已有代码。** `ExpertProjectPlanExecutor.kt`、`ExpertTeamExecutionCoordinator.kt` 已包含 DAG/依赖/并行工作包、结构化交接、成员结果检查点和恢复逻辑；`NestroApp.kt` 已接入项目计划执行。仍需核对手动组队入口、自动组队入口、工具调用及恢复是否全链路接通。
- **记忆关联本地缓存尚未确认。** 目前仅确认 BGE 本地语义检索/embedding 相关实现；这不等于用户要求的记忆缓存。需继续搜索是否存在按全局/项目/会话作用域缓存，以及记忆新增、修改、删除、清空后的失效/刷新。

### 分支核对补充

- `feature/module-18-calendar`、`feature/module-17-smart-editing` 相对 `integration/19-modules` 没有领先提交；抽查的日历提醒调度器与媒体渲染器文件 blob 一致。
- `feature/module-9-voice-system`、`feature/module-15-ai-coworkbench` 抽查关键源码与整合分支 blob 一致。
- 模块6/13/14 分支虽然提交图分叉，但抽查发现整合分支含有更晚的源码修正：BGE masked mean pooling、MCP 损坏审计原文隔离、Git 凭据保存失败安全回滚。因此不能将分支 ahead/behind 数量直接当成功能缺失数量。
- PR #1 和 PR #3 仍是开放 Draft；PR #2 已关闭但未合并。PR/分支状态与 integration 内容是否吸收必须分开记录。

### 当前首轮审计的明确边界

本节是定向源码抽查，不代表十九模块全量逐项完成。仍需：
1. 逐模块把每项功能映射到实现文件、UI 入口、服务调用、持久化、异常/恢复和测试。
2. 逐功能分支比较文件内容，排除未吸收的独有修复，同时识别整合分支上的更新版本。
3. 专项核验混合调度、Mika UI 手势、审批等待状态、专家工具能力和记忆缓存。
4. 全面检查重复实现、死代码、旧入口、重复状态源和历史垃圾代码。
5. 按用户既定顺序，源码收口后才设计完整 APP 操作界面，之后统一构建和测试。


## 2026-11-22 主模型调度多模态与视频模型专项初查

### 用户需求补录

用户在 2026-11-21 明确要求：文本主模型按需识别并调用多模态/视频模型；无 API 时提供提示词或在用户明确要求且 Phone Agent 可用时尝试已授权工具；视频生成后可继续对话返工；补齐后统一合并验收。此项现纳入 `IMPLEMENTATION_ROADMAP.md` 的 G 节。

### 当前源码已确认

- `ModelLedToolDispatcher.kt` 的主模型决策提示已包含视频生成路由规则：先调用 `media.provider.list`，选择已配置视频模型，再调用 `media.video.generate`；返工通过 `media.video.status` 查询任务并传入 `previous_task_id`，不覆盖旧视频。主模型可同时使用文本聊天模型与独立媒体视频模型。
- `NestroApplication.kt` 已注册 `media.provider.list`、`media.video.generate`、`media.video.status`。视频生成工具具备项目范围检查、配置读取、异步任务提交、有限等待、后台任务状态查询及结果素材 ID 回传。
- `MediaAiTaskStore.kt` + `MediaAiTaskWorker` 使用 WorkManager 持久化异步任务；生成文件通过 `MediaProjectStore.importGeneratedFile()` 导入当前项目；版本任务要求前一任务完成并保存 parentTaskId。
- `MediaVideoGenerationClient.kt` 已实现配置化 JSON 提交/轮询/结果下载，包含 HTTPS、拒绝重定向、内网地址拦截、MIME 检查和文件大小限制。
- `MediaProviderConfigStore.kt` / `MediaIntelligenceProvider` 还具备图像理解、音频转写、场景识别和音频处理的媒体 AI 能力；`MediaEditorPage.kt` 能创建并调度这些任务。

### 当前明确缺口/待核验

- **主模型可调用视频生成，但不能据此认定主模型已能调用所有多模态分析能力。** 当前 `NestroApplication.kt` 的媒体工具注册仅见 `media.provider.list`、`media.video.generate`、`media.video.status` 和项目素材查询；图像理解、音频转写/处理、场景识别目前确认有媒体编辑器内任务入口，尚未发现对应的聊天主模型工具注册。并且 `media.provider.list` 只返回 `chat_model` 与 `video_model`，没有返回 `transcriptionModel` 或结构化 capability 列表。需要先确定主模型要调用的多模态任务范围，再复用现有 MediaAiTaskStore/Worker/Provider 补最小工具接线，避免另造重复媒体任务体系。
- 当前通用媒体连接测试验证的是聊天/视觉 `/chat/completions` 路径，不验证视频提交/轮询端点；视频端点真实联调尚未确认。
- 当前媒体测试目录没有 `MediaVideoGenerationClientTest`；`MediaAiTaskStoreTest` 覆盖视频任务无源素材、已完成父任务版本关联等持久化规则，但没有覆盖 HTTP 请求/轮询/下载/错误/取消全链路。
- `docs/MULTIMODAL_VIDEO_GENERATION_PIPELINE.md` 明确记载：视频播放由聊天结果按钮跳转媒体编辑器，不是消息气泡内联播放器；通用连接测试不验证视频端点；当前实现未完成编译、测试、Lint、模拟器或真机验收。
- 通用视频适配器只覆盖可配置的异步 JSON submit/status/result URL 协议；签名、multipart、特殊轮询/响应格式的服务商仍需专用 adapter。是否需要支持更多特定视频模型协议，按用户后续确认的服务商范围检查，不先造抽象层。

### 首轮结论

视频生成主链路已有代码，不能重复建设；多模态媒体分析已有编辑器内任务，但聊天主模型的按需调度入口存在可确认缺口。下一步应复用现有 `MediaAiTaskStore/Worker/Provider` 体系，补主模型工具注册与参数/权限/结果交接，先确认是否已有通用实现可复用，再做最小必要修改。HTTP 协议测试和统一验证放入后续审计/验收，不把代码存在等同于已通过。

## 2026-11-22 功能分支差异复核（整合分支 HEAD: cf5d0cb84f9bea748d9d805be7d3a4ade7e32e53）

本轮对仓库可见的 28 个分支逐一与 `integration/19-modules` 比较。以下为比较结果的正确解释与关键文件抽查，不以 ahead/behind 数量直接判定功能是否合并：

- `feature/module-17-smart-editing`、`feature/module-18-calendar`、`feature/phase2-optimization`、`feature/modelscope-oauth`、`feature/module-5-project-space`、`feature/module-7-memory`、`feature/module-10-artifacts`、`feature/module-11-expert-templates`、`feature/module-12-model-comparison` 等旧分支相对整合分支没有独有领先提交（behind）；不能把它们标记为缺失功能。
- `feature/module-6-knowledge-base` / `wip/module-6-finalize` 虽存在分叉提交，但抽查发现整合分支的 BGE 输出使用 attention-mask masked mean pooling；旧分支仍取 CLS token。整合版本更新，不能把旧分支版本合并回来。
- `wip/module-6-knowledge-base` 的旧 `KnowledgeChunker` 对 chunkSize/overlap 的约束较弱；整合分支新增最小长度与 UTF-16 surrogate 边界约束，优先保留整合分支版本。
- `wip/module-5-project-space` 的 `ProjectAccessControlStore` 未强制 WRITE 必须包含 READ；整合分支当前文件比该 WIP 版本更严格。其 `ProjectResourceScopeStore` 也有更完整的 key 校验 helper，不能用 WIP 旧版覆盖。
- `fix/module-13-audit-reliability` 的 MCP 源码和测试 blob 与整合分支完全一致；其独有差异只在旧版模块状态文档，不存在需要再合入的代码修复。
- `feature/module-13-mcp-tool-center`、`feature/module-14-github-workbench`、`feature/module-15-ai-coworkbench` 分支仍有分叉文件，但这些是历史分支版本差异；必须按当前实现逐文件核对，不能整分支合并。已抽查模块13/14发现整合分支含额外安全修正。
- `main` 分支保留 `UnifiedAgentDispatcher.kt`、`ChatNetworkPolicy.kt`、`TtsCapability.kt` 等文件，但它们未出现在当前整合分支树中；其中 `UnifiedAgentDispatcher` 是早期关键词路由，与当前 `ModelLedToolDispatcher` 的主模型原生工具调用架构不同，不应直接恢复旧路由。其余两个 helper 需与整合分支现有网络重试和 TTS 能力逐项对照，确认有无独有行为后再决定是否吸收。
- `feature/module-8-phone-agent` 的分叉差异集中在 CI/Gradle 配置文件；需在构建审计阶段核对这些配置差异，不将旧分支 CI 文件直接覆盖当前配置。
- `feature/knowledge-retrieval-ranking`、`feature/module-9-voice-system` 等历史分支含较多分叉文件，但当前整合分支抽查的核心实现文件存在更新版本；后续仍需逐文件审查其余独有差异，不能仅凭提交数做 merge。

### 当前整合基线验证状态

- HEAD：`cf5d0cb84f9bea748d9d805be7d3a4ade7e32e53`。
- GitHub Combined Status 返回空状态列表；关联 PR workflow runs 也为空。当前不能声称该 HEAD 的 CI 已通过。
- 当前代码树为 354 个文件；媒体模块专项检查发现有多组媒体 AI 测试，但没有独立的 `MediaVideoGenerationClientTest`。
- 本节只完成分支结构与代表性文件核对；剩余分叉分支的逐文件语义审查仍在进行。任何清理/删除都必须等到全量引用扫描和调用链确认后再做。


## 2026-11-22 记忆关联缓存专项核对

- 当前整合分支代码树中没有独立命名的 MemoryCache/ConversationCache/ContextCache 文件。
- `UserMemoryRepository` 内存在一个容量上限 400 的 LRU `embeddingCache`，key 为记忆 fingerprint，存放的是 BGE 向量；它是 embedding 计算缓存，不是用户要求的记忆内容/上下文缓存。
- `clearProject()` 会清空该 embedding cache；`edit()`、`delete()`、`importOne()`、自动保存/裁剪记忆目前没有直接触发 cache 失效。由于 key 是内容 fingerprint，编辑后旧 fingerprint 不会被新记忆命中，删除后旧条目只会残留至 LRU 淘汰；这主要是缓存清理/内存占用问题，不应描述为已确认的错误记忆召回。
- 当前未发现按全局/项目/会话作用域缓存记忆上下文并依据记忆版本自动失效的独立实现。用户提出的记忆关联本地缓存仍应列为待补/待明确验收项；不能用 embeddingCache、Prompt Cache 或 WorkManager 任务缓存顶替。
- 后续若实现，必须先确认是否已有可复用的 repository/context assembler 缓存边界，避免另造一套缓存数据库；缓存 key 至少需覆盖项目/会话/记忆版本与上下文策略，记忆增删改、项目切换/删除、导入和恢复时必须正确失效。


### 旧 main 分支独有辅助类的去留判断

- `main` 分支独有的 `UnifiedAgentDispatcher.kt` 是关键词计数路由（PHONE/KNOWLEDGE/EXPERT/GENERAL），与当前整合分支的 `ModelLedToolDispatcher` 原生工具调用和主模型自主选择能力重复且能力更弱；不恢复旧文件。
- `main` 分支独有的 `ChatNetworkPolicy.kt` 中重试判定调用的正是当前整合分支已有的 `ChatRequestPolicy.shouldRetry()`；超时参数由当前 `ChatExecutionService/OpenAiClient` 请求入口管理。未发现必须恢复该类的独有功能，避免增加第二套网络策略入口。
- `main` 分支独有的 `TtsCapability.kt` 提供系统中文 Voice 质量/延迟排序；当前 `AndroidTextToSpeechController` 已提供设备可用语言并负责 setLanguage/语速/音调。当前用户确认的需求中尚无“按 TTS Voice 质量排序选择”的明确要求，暂不恢复该 helper，后续如界面设计确认需要具体音色选择，再基于现有控制器补入口。
- 上述判断基于旧 main 分支文件与整合分支当前实现的对照，不是对 main 分支执行删除；当前开发基线仍只有 `integration/19-modules`。


## 2026-11-22 工作流审批持久状态复核

- `WorkflowUiState` 保存当前审批弹窗与 `CompletableDeferred<WorkflowApprovalChoice>`；`NestroApp.kt` 的审批回调在用户选择前停留于 `decision.await()`，授权一次/当前任务全部授权/拒绝三个选择都已接通。
- 当前审批弹窗 `onDismissRequest = {}`，不会因点击外部或返回键意外关闭；停止工作流按钮才会显式结束当前任务。用户已确认的是未选择时等待及三种明确决策，并未明确要求增加“关闭但继续等待”按钮，因此本轮不把缺少关闭按钮列为功能缺口，也不擅自增加新入口。
- 更重要的是：`WorkflowExecutionCoordinator.execute()` 将执行交给 `AgentTaskCoordinator.runInline()`，但在审批回调等待 `CompletableDeferred` 时没有调用 `taskContext.waitForUser()`。目前可见代码会让 UI 协程等待，但没有把持久任务状态显式改为 `WAITING_FOR_USER`；需要补齐任务状态与审计事件，避免后台任务中心/进程恢复将等待审批误显示为普通 RUNNING。
- `AgentTaskExecutionContext.waitForUser()` 已存在并会持久化 `AgentTaskStatus.WAITING_FOR_USER`，说明应复用现有能力，而不是再建一套审批状态存储。
- 本项已确认是具体接线缺口；尚未修改代码，待完成十九模块全量审计后按最小改动修复，并补充“等待时持久状态、批准后恢复、拒绝后终止、停止时取消等待”的测试。


## 2026-11-22 模块 1–4 源码定向复核

### 模块1：专家系统
- 已核实自然语言主模型自动组织团队的调用链：`ModelLedToolDispatcher` 提供 `expert_team.run`；仅当当前项目有至少两位启用专家时开放；`NestroApp.kt` 优先使用用户选定团队/手动成员，否则使用主模型从当前专家目录选出的 2–4 位成员。
- 主模型生成的 `plan` 会解析为 `ExpertProjectPlan` / `ExpertWorkPackage`，交给 `ExpertTeamExecutionCoordinator.executeProjectPlan()` 执行；独立工作包可并行，依赖包按 DAG 等待，结果回传主模型复核。专家会按配置加载关联 Skill 上下文。
- `ExpertProjectPlanExecutorTest`、`ExpertTeamExecutionCoordinatorTest`、`ExpertTeamEngineTest` 等测试源码存在。此前记录的检查点/恢复实现确实在当前分支。
- 当前仍不完整的产品交互：专家双向自由交流/私聊、可编辑任务图、用户可见的暂停/恢复/返工控制、完整历史分页及结构化验收返工协议。不能把“专家可自主分工”扩大解释为上述交互均已完成。

### 模块2：模型管理
- `ModelRegistry` / `ModelConfigurationRepository` / `ModelRequestConfigurationSelector` 将服务商、模型、API Key ID 分离；实际密钥由 `LocalVault` 按调用时读取，注册表不保存明文 Key。当前 registry 的 `ModelDefinition` 未记录每个模型的 context-window token 数。
- 魔搭 OAuth Android 回调由 `MainActivity` 在 `Dispatchers.IO` 调用；`ModelScopeOAuthCallback` 校验 scheme/host/path、handoff 格式与 app_state，再向后端兑换 token 并写入 LocalVault/模型注册表。
- 后端 `services/modelscope-oauth/src/server.mjs` 已存在：服务端保存 Client Secret、校验一次性 OAuth state、交换授权码、获取 userinfo、生成短时一次性 handoff；README 明确多实例部署前需将内存 TTL store 换成共享 Redis。Node 静态安全测试源码存在，但尚未运行；服务部署、第三方应用注册和 API-Inference 权限仍待真实确认。
- 当前没有发现 ModelScope OAuth Android 回调/端到端服务联调测试；不能将 OAuth 代码已写入描述为 OAuth 已真实可用。

### 模块3：聊天系统与主模型调度
- `ChatRequestExecutionCoordinator` 负责单次聊天上下文、主模型工具调度、流式输出与最终落库；`ModelLedToolDispatcher` 使用原生工具调用，并只在供应商明确不支持原生 function calling 时回退兼容文本协议。
- `ChatRequestContextAssembler` 并行获取被策略选中的会话记忆和用户记忆；`ChatRequestRecoveryCoordinator` 保存/恢复中断请求；对应 JVM 与 Android persistence 测试源码存在。
- 明确缺口：`ChatRequestPolicy.boundedHistory()` 默认固定 16,384 context tokens / 2,048 output reserve；`ModelDefinition` 未提供真实 context window 元数据，尚未实现按当前模型真实上下文窗口动态预算。不能把固定估算称为模型自适应上下文管理。
- 主模型视频生成工具已接入；多模态分析工具缺口另见本文件专项记录。

### 模块4：数据与备份
- `LocalBackupRepository` 是全局 JSON 备份主入口，`ProjectPackageCodec` 是项目包边界；媒体编辑器另有独立 ZIP 工程备份/恢复，覆盖时间线、素材原文件和校验。
- 媒体 ZIP 仍未并入通用 JSON 设备备份/项目包；这不是同一功能重复实现，而是当前数据覆盖边界分离。后续需判断是统一备份格式还是明确保留独立媒体工程包并提供完整恢复说明，不能简单删除其中任一实现。
- 已有 `LocalBackupRoundTripTest`、`LocalBackupCompatibilityTest`、`ProjectPackageCodecTest` 和 ArtifactStore 备份验证测试源码；全量 Android 往返/升级/媒体素材恢复尚待统一执行。


## 2026-11-22 产品边界源码抽查

- 当前源码有 `LocalVault`，使用 AndroidKeyStore AES-GCM 加密本地模型 API Key；GitHub/GitLab/Gitee 账号由 `GitAccountStore/GitCredentialVault` 保存用户 PAT，未发现 Nestro 自建账号/登录流程。
- OAuth 服务 `services/modelscope-oauth` 是用户/运营方自行部署的第三方授权桥接服务，不是 Nestro 用户账号系统；服务端 Client Secret 不应进入 APK，Android 回调 token 写入本地凭据库。
- 当前 Git 工作台支持 GitHub/GitLab/Gitee 的远程仓库操作，因此“本地优先”没有被实现成禁止云端仓库同步。
- 仓库 README 已说明本地优先和凭据不托管，但“免 Nestro 账号、第三方账号由用户自行登录、Nestro 私人记忆/技能与公开项目严格隔离”此前没有完整写入统一产品基线；现已补入路线图 H 节。
- 这项仍需后续全量检查：公开仓库/构建产物/日志/备份中不得出现真实 API Key、OAuth Client Secret、PAT 或灵犀私人记忆文件；当前仅确认本地凭据存储实现，未执行秘密扫描与发布包检查。


## 2026-11-22 模块 5–9 源码定向复核

### 模块5：项目空间与跨项目资源
- 当前 `ProjectSpaceRepository` 负责 Room 项目数据关系、空项目删除、复制和失败回滚；`ProjectAccessControlStore` 实施方向性 READ/WRITE 授权和审计；`ProjectResourceScopeStore` 负责专家/团队/技能/工作流/文件等资源归属范围。
- 与旧 `wip/module-5-project-space` 对照，当前整合版对 WRITE 必须包含 READ 的授权规则、资源 key 校验和范围去重更严格；不应将 WIP 旧版覆盖当前实现。
- 仍需统一验证跨项目授权撤销是否即时影响知识召回、专家/技能/工作流调用、媒体素材和资源删除/迁移；项目复制/删除的全资源类型一致性仍需和模块4/17联测。

### 模块6：知识库
- 当前 `KnowledgeRepository` 统一文档导入、项目检索、全文重组和向量索引重建；`KnowledgeSearchEngine` 提供本地词法/BM25，`KnowledgeVectorSearch` 提供向量相似度排序，`KnowledgeEmbeddingModelManager` 使用 BGE ONNX 模型。
- 整合分支的 BGE token 级向量采用 attention-mask masked mean pooling；旧 WIP 版本采用 CLS 向量，保留整合分支当前版本。
- 文档导入、PDF OCR、chunking、词法检索和向量检索有 JVM/Android 测试源码；真实模型下载、Android ONNX Runtime 性能、Room 迁移和设备耗电尚未验证。

### 模块7：我的记忆
- `UserMemoryRepository`、`UserMemoryStore`、`AutomaticMemoryExtractionCoordinator`、`ConversationMemoryStore` 已形成项目记忆保存、编辑、删除、语义/关键词召回、会话记忆和来源关联。
- 本地 BGE embeddingCache 与用户提出的记忆上下文缓存不同；目前没有独立全局/项目/会话记忆上下文缓存，详见“记忆关联缓存专项核对”。
- 编辑/删除记忆后 embeddingCache 不会即时清除旧 fingerprint 项，但新内容使用新 fingerprint，不会命中旧向量；旧条目会留至 LRU 淘汰。后续可在同一 repository 中做精确失效，不另建缓存服务。

### 模块8：Phone Agent
- `PhoneAgentTaskService` 创建、排队和取消任务；`PhoneAgentWorker` 通过 WorkManager 与持久任务协调器执行，并区分用户取消与系统中断：用户取消进入终态，后台中断暂停并要求核对后继续，不自动重放不确定动作。
- `PhoneAgentService.downloadAsset()` 使用 Android DownloadManager 下载，随后调用 `MediaProjectStore.importAsset()` 将真实文件导入当前项目；主模型通过任务结果与 `media.project.assets` 获取素材 ID/元信息。
- 已有 parser、planner、risk、screen safety、URL policy、cancellation、task store 等多组测试源码；真实设备上不同 App、后台限制、登录/验证码和虚拟屏稳定性仍待真机矩阵验证。
- Mika 虚拟屏的具体交互差异已在前文记录；底层移植不应重写，后续只改 UI 手势/显示元素和接管状态接线。

### 模块9：语音系统
- 当前 `SpeechProviders` 抽象本机/远程 STT、TTS；`OpenAiCompatibleSpeechProviders` 提供 HTTP 音频输入/输出；`OpenAiRealtimeVoiceProvider` 使用 WebSocket 事件流；`AndroidTextToSpeechController` 负责本机 TTS。
- 语音 Provider 配置和接口测试源码存在；当前旧 main 分支的 `TtsCapability` 不在整合分支中，整合版控制器已有设备语言枚举与 setLanguage 能力，暂不恢复旧 helper。
- 真实音频设备路由、厂商系统行为、实时会话稳定性、耳机/扬声器切换与功耗仍待设备验收。


## 2026-11-22 UI 入口与历史代码清理风险初查

- 当前 `NestroApp.kt` 仍约 4,505 行、331 KB，是最大的 UI 聚合入口；已有部分状态/协调逻辑拆到 `ChatExecutionUiState`、`WorkflowUiState`、`ProjectSpaceUiState`、各类 coordinator/repository，但主要页面编排仍集中在该文件。
- 对该文件的函数声明扫描未发现重复函数名；这不代表没有重复业务逻辑或未使用分支。后续需要按页面入口、状态 owner、事件回调和 coordinator 调用链识别可安全抽取的部分，不能仅因文件很大就机械拆分。
- 当前未进行完整 Kotlin 引用图/未使用符号扫描；由于无法仅凭文件名或旧分支存在与否判定死代码，本轮没有删除任何源码、资源或依赖。
- 清理阶段的准入条件：先确认全仓引用、反射/序列化/Compose route/Manifest/WorkManager/备份迁移等隐式入口，再删除；每批清理保留可回滚提交并做差异核对。


## 2026-11-22 模块 10–15 源码定向复核

### 模块10：作品/产物库
- `ArtifactStore` 有项目范围的产物记录、文件校验、版本和备份验证；对应 `ArtifactStoreBackupTest`、`ArtifactStoreBackupValidationTest` 等测试源码存在。
- 需继续联测：作品文件和项目包/全局备份的恢复一致性、删除/索引缺失后的恢复与跨项目隔离；不能把文件索引存在当作文件字节一定存在。

### 模块11：专家模板
- `ExpertTemplateCatalog`、`ExpertRevisionAndTemplate` 和相关 parser/codec 覆盖内置模板、自定义模板、版本记录、导入导出与分类搜索；JVM 测试源码存在。
- 需继续核对模板引用的专家/技能被项目范围限制或删除时，是否正确阻止破坏性操作；测试尚未执行。

### 模块12：双模型对比
- `ModelComparisonCoordinator` 负责两个不同模型的并行请求；`ModelComparisonPolicy` 校验模型不同、可用和输入非空；独立错误结果与停止路径已有代码。
- 对应 policy 测试源码存在；并发取消、两个模型一方失败/另一方完成、模型切换和费用归属仍需统一执行测试。

### 模块13：MCP 工具中心
- 当前 `McpToolCenter` 负责远程 HTTPS Streamable HTTP MCP 服务、工具发现、参数 schema 校验、凭据保护、调用授权和审计；当前范围不启动任意本地 stdio Server。
- `fix/module-13-audit-reliability` 分支抽查的 MCP 源码与测试 blob 与整合分支完全一致，没有待再次合并的代码修复。
- 需验证服务端断连、超时/取消、schema 边界、凭据撤销、审计损坏恢复及主模型工具调用端到端；对应测试源码存在但未执行。

### 模块14：GitHub 工作台
- `GitWorkbench` 中的 `GitAccountStore/GitCredentialVault` 使用 AndroidKeyStore AES-GCM 保护用户 Git 访问令牌；支持 GitHub/GitLab/Gitee 用户授权和 JGit 本地仓库操作。
- 旧模块14分支有分叉，但整合分支抽查已包含凭据保存失败安全回滚和失败显式提示；不得将旧分支版本覆盖当前实现。
- 需继续检查 clone/pull/push/commit/branch、冲突、回滚、凭据轮换、仓库删除与本地文件系统异常；JVM/Android 测试源码存在但未执行。

### 模块15：AI 共建工作台
- `AiCoworkbenchService` 复用 `GitWorkbenchRepository`，提供变更审查、补丁应用/回滚和构建/测试工作流；相关静态分析、报告解析、上下文策略与策略测试源码存在。
- 需确认远程 GitHub Actions 只在用户主动启动后执行、逐文件批准与事务回滚在异常/进程中断下保持一致；真实 Actions、SBOM/JUnit 产物和完整测试尚未运行。


## 2026-11-22 模块18统一时间调度引擎专项审计

### 用户确认的真实定位
用户于 2026-11-21 明确纠正：模块18主要辅助自动定时，不是独立日历产品页；应把时间事件接入统一调度基座，支持 Agent/任务/工作流分发、状态回写、失败重试、持久化、重启恢复和取消。

### 当前源码实际能力
- `CalendarDatabase/CalendarRepository` 已实现本地日程 CRUD 和 Room 持久化。
- `CalendarRules` 支持 NONE/DAILY/WEEKLY/MONTHLY/YEARLY 的重复计算、到期 occurrence 判断和日期范围展开。
- `CalendarReminderScheduler` 只创建 `CalendarReminderWorker` 的唯一 WorkManager 单次任务；`CalendarReminderWorker` 到期后发系统通知，再安排下一次重复提醒。
- `CalendarProviderBridge` 支持系统日历导入/导出和关联事件更新/删除。
- `CalendarPage` 当前是完整月历/周历页面，包含日程 CRUD、系统日历导入导出和提醒配置。

### 与用户要求的差距
- **未发现时间触发后向 `AgentTaskCoordinator` / `AgentToolCatalog` / 工作流分发的代码链路。** 当前到期动作是发通知，不会自动创建 Agent/任务/工作流执行。
- 未发现调度触发任务的关联 ID、执行状态回写、失败重试策略或幂等执行记录；当前 `completed` 是日程完成标记，不是任务执行状态。
- 当前仅支持事件开始前的提醒分钟数及周期重复；没有独立的延迟触发、时间条件判断、按任务目标选择执行器等统一调度模型。
- WorkManager 能持久化通知工作并处理设备重启，但当前没有“未完成 Agent 任务恢复/执行状态恢复”的关联链路。不能把 WorkManager 提醒恢复等同于统一调度恢复。
- 当前只找到 `CalendarRulesTest`；没有发现 Scheduler/Worker/Repository 的自动化测试，也没有 Agent/Workflow dispatch 的集成测试。
- 当前完整月历/周历页面与用户“不是独立页面”的产品定位不一致；后续 UI 设计应将它收敛为轻量计划/触发管理入口，而不是继续扩展独立日历功能。

### 当前判定
模块18的“日程 CRUD、周期计算、通知提醒、系统日历互操作”代码存在；但用户真正要求的“统一自动时间调度引擎”尚未实现。此项是明确的功能缺口，不是待验收状态。后续应复用现有统一任务基座和 WorkManager，补齐最小执行接线，不另建第二套任务系统。尚未修改代码。


## 2026-11-22 场景路由与主屏导航交接专项审计

- `ModelLedToolDispatcher` 已按主模型决策“直接回答或调用已声明能力”，并有创作/专家/手机执行组合规则；专家团工具仅在当前项目至少两位专家启用时开放。场景路由不是每轮强制调用工具或专家。
- `PhoneAgentPlanner` 当前分类包含 SHOPPING、FOOD_ORDER、BOOKING、WEB_RESEARCH、FORM、CREATIVE_ASSET、GENERAL；购物/点餐计划已包含多来源比价、最终到手价比较和付款前用户确认；创作素材计划包含下载、导入当前项目素材库。
- **通用虚拟屏/主屏交接已经实现，但导航专用决策链缺失。** `MikaPhoneAgentExecutionEnvironment.handoffToUser()` 会先持久化 taskId→Android taskId，再把虚拟屏任务移动到主屏；`resumeAfterUserHandoff()` 可将任务移回虚拟屏；`PhoneAgentExecutor` 在登录/验证码/风控或 `ConfirmSensitive` 时将任务置为 `WAITING_USER` 并触发交接。因此不能再把“虚拟屏/真实屏交接”整体列为未实现。当前缺少的是 `TRAVEL` 任务分类、地图路线规划工具/步骤，以及“展示目的地与路线→单独请求主屏导航授权→启动主屏导航”的专用流程。
- 当前风险策略能阻止支付/提交等高风险点击并要求用户接手；通用 handoff 机制可复用。仍需在现有 Phone Agent/统一权限路由中增加导航专用的目的地/路线预览与主屏导航授权，不要新建第二套屏幕交接机制，也不能用普通 openUrl 变相实现。
- 响应式方面，`NestroApp.kt` 已有 `adaptiveDrawerWidthDp` / `adaptiveContentPaddingDp`，`ChatRequestContextAssembler` 并行执行独立记忆检索；这是代码层面的适配/并行优化证据，手机/平板/大屏真实布局、帧率、内存和耗电仍待设备验证。
- 用户要求的场景最短路径与主屏交接已补入路线图 J 节；本轮未修改代码。


## 2026-11-22 模块 16–19 源码定向复核

### 模块16：统一能力中心 / 插件工厂
- `GeneratedPluginFactory` 以声明式插件包组合已登记的 `toolId`、输入 schema、参数映射和结果字段；它不是执行任意生成代码的沙箱。用户要求的“模型自主创造插件”当前可理解为生成可审查、受现有工具目录约束的组合插件。
- 插件创建、草稿审查、工具指纹、执行前重新校验、已有工具权限复用等源码与 JVM/Android 测试存在；仍需确认撤销工具/插件后已安装组合插件是否即时失效，以及跨项目引用的权限是否持续有效。
- 任意代码型插件独立沙箱仍未实现；不能把声明式工具组合描述为任意代码沙箱。

### 模块17：智能剪辑
- `MediaProjectStore` 管理项目素材、时间线、文件校验、导入导出和 ZIP 工程归档；`MediaRenderEngine` 使用 Media3 Transformer 构建本地渲染 composition；媒体任务由 WorkManager 持久化执行。
- 创作素材混合调度所需的下载文件导入入口已存在；媒体项目 ZIP 归档与通用设备 JSON 备份是不同边界，不应重复实现或删除其一。
- 当前需要统一验证真实视频编解码器兼容性、长视频内存/磁盘空间、渲染取消与进程恢复、字幕/音轨/多轨时间线、导出文件完整性；相关 policy/codec/archive/Android tests 源码存在但未执行。

### 模块19：多模态服务与社区生态
- `MediaProviderConfigStore` 分别保存聊天/视觉模型、转写模型和视频生成模型配置；`MediaIntelligenceProvider` 有图像理解、音频转写、场景识别/音频处理入口；`MediaAiTaskStore/Worker` 负责持久任务、进度、取消和恢复。
- `MediaVideoGenerationClient` 是可配置的异步 JSON 提交/轮询/结果下载适配器；不同签名、multipart、特殊响应协议的服务商必须单独适配。视频生成任务会导入项目素材库并通过工具结果回传主模型。
- `SkillSemVer`、`SkillDependencyResolver`、`SkillCommunityLifecycleStore`、`SkillTrustStore` 已覆盖版本约束、递归依赖求解、原子安装/回滚和独立信任根撤销。
- 相关媒体、技能和信任根 JVM/Android 测试源码存在；但没有独立 `MediaVideoGenerationClientTest`，真实视频服务商端点、音频/视觉 API、社区签名撤销源与设备性能仍待测试。
- 多模态分析尚未接入聊天主模型工具目录，视频生成已接入；详见多模态专项审计。


## 2026-11-22 统一任务执行基座接入审计

### 当前基座
- `AgentTaskCoordinator` 已提供统一任务状态（QUEUED/RUNNING/WAITING_FOR_USER/PAUSED/FAILED/COMPLETED/CANCELLED）、幂等 enqueue、resume、checkpoint、trace、process-restart recovery 和外部 scheduler requeue。
- Phone Agent 通过 `PhoneAgentWorker` 接入 `AgentTaskCoordinator`，同时保留 `PhoneAgentTaskStore` 的领域任务计划、动作结果和用户可读日志；两份状态需要验证同步一致性，但不应简单删除领域记录。

### 已发现的平行任务状态实现
- 媒体 AI：`MediaAiTaskStore` 使用每项目 JSON 文件持久化 QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED、progress、resultJson、parentTaskId；`MediaAiTaskWorker` 直接由 WorkManager 排队/恢复/取消，没有接入 `AgentTaskCoordinator` 的通用生命周期状态。
- 日历：`CalendarRepository` + `CalendarReminderScheduler/Worker` 只持久化日程和 WorkManager 提醒；到期后直接发通知，没有统一任务 ID 或 AgentTaskCoordinator dispatch。
- 专家项目/工作流使用 `AgentTaskCoordinator`；因此目前不同领域任务的生命周期、恢复、取消、日志来源并不统一。

### 当前判断
这不是要求删除媒体任务或 Phone Agent 领域数据；媒体任务需要保留视频版本、进度、素材 ID 等领域字段，Phone Agent 需要保留动作计划和逐步结果。真正的重复/断链是通用任务生命周期分别由领域 Store 与 WorkManager 管理，未共享统一状态源。
后续应让领域任务记录关联统一 taskId，由 AgentTaskCoordinator 管理生命周期；WorkManager 只负责唤醒/后台约束。改造前先做状态迁移与双写/回读方案，避免丢失正在运行的媒体任务或手机任务。当前未修改代码。


## 2026-11-22 模型配置双轨结构去留判断

- 当前模型配置存在新旧两层：`ModelRegistryStore/ModelRegistry` 管理服务商、模型和 Key ID；`ModelProfileCatalog` 与 `LocalVault` 保留旧版 profile/active-model/key 兼容入口。
- 这不是可以直接删除的死代码：`ModelRegistryStore.load()/rebuildFromLegacy()`、`ModelRegistry.reconcileLegacyProfiles()`、`ModelConfigurationRepository` 仍会读取旧 profile、检查旧密钥并迁移/回写 registry；`ModelManagerPage` 仍为旧 provider/model 配置提供兼容编辑入口。
- 清理前必须确认：旧版用户升级迁移、已有密钥解密/重写、active model 选择、故障切换、导入备份恢复和旧配置删除均不再依赖旧格式；在没有迁移回归测试与版本数据验证前，不删除旧字段/codec/vault 兼容方法。
- 后续可把 UI 和运行时逐步收敛到新 registry，但必须先保留迁移兼容，避免升级后丢失用户 API Key 或当前活动模型。


### 专家团群内过程可见性补充核对
- 当前 `NestroApp.kt` 已把 `ExpertTeamCollaborationEvent` 持久化，并在专家团队区域显示“最近协作过程”，最多展示最近 16 条事件及消息摘要；因此不是完全没有过程查看能力。
- 当前实现更接近只读事件时间线，不是独立群聊窗口；尚未确认支持完整历史分页、成员间连续对话视图、实时消息流和用户插话/调整任务。后续界面设计时应保留已有事件记录，不要重复造第二套群聊消息存储。


## 2026-11-22 重复实现候选清单（仅标记，不提前删除）

- **通用工作流与专家项目 DAG：** `WorkflowEngine` 实现用户配置步骤、条件、连续 parallelGroup、步骤 checkpoint；`ExpertProjectPlanExecutor` 实现模型生成的任意依赖 DAG、topological waves 和并发工作包。两者有重复的并行/依赖调度算法，但领域数据、失败策略和交接语义不同。应先做行为对照测试，再判断是否只抽取纯调度算法；不能直接合并两套执行器或删除任一模块。
- **专家团队执行器：** `ExpertTeamEngine` 的成员协作执行与 `ExpertProjectPlanExecutor` 的项目工作包执行由 `ExpertTeamExecutionCoordinator` 统一登记任务、保存 checkpoint 和取消；需检查是否存在可复用的并发/结果收集逻辑，保留两种用户模式的差异。
- **模型配置：** 旧 profile/active-model 与新 ModelRegistry 是迁移兼容双轨，不能当死代码删除；见“模型配置双轨结构去留判断”。
- **任务状态：** PhoneAgentTaskStore 与 AgentTaskCoordinator 是领域动作日志/计划和通用生命周期的双层状态；MediaAiTaskStore 则有独立生命周期，后者是统一基座接入缺口。先统一状态映射，不删领域数据。
- **媒体服务商配置：** MediaProviderConfigStore 与 ModelRegistry 都保存 provider/base URL/Key ID 类元数据，但媒体配置还包含独立转写/视觉/视频模型、multipart 路径、异步任务字段映射；两者共用 LocalVault 保存 API Key。属于配置边界重叠候选，不是重复加密库；需先确认媒体 API 与聊天模型是否要统一服务商实体，再决定迁移。
- **旧 main 分支：** UnifiedAgentDispatcher、ChatNetworkPolicy、TtsCapability 不在当前 integration 代码树；前两者与当前 ModelLedToolDispatcher/ChatRequestPolicy 有重叠或被替代，不应复制回来。TtsCapability 仅有额外音色排序能力，尚无用户确认需求。
- **大型 UI 聚合入口：** NestroApp.kt 约 4,505 行，尚未发现重复函数名；必须按状态 owner/路由/调用链抽取，不得只按文件体积拆分。


### Mika 虚拟屏 Shell/交接测试覆盖补充
- 当前树仅发现 `VirtualDisplayGeometryTest`；未发现 ADB TLS pairing、helper 启动、UID/签名/nonce Binder handoff、虚拟屏创建/销毁、主屏任务移动/恢复和 UI 手势的专项自动化测试。
- 因此 Shell 授权/启动和通用屏幕交接属于“代码已实现、自动化/真机验证缺失”，不能误报为代码未实现；也不能在六项阶段完成前提前运行测试。


### 模块15 AI 共建工作台安全边界补充核对
- `AiCoworkbenchService.prepareReview()` 已实现补丁解析、路径安全校验、密钥/签名材料保护、静态分析、变更影响分析和逐文件 diff 预览。
- `applyApproved()` 仅应用用户选定文件，先持久化快照和审批记录；写入失败会尝试恢复，恢复失败保留 `RECOVERY_REQUIRED` 状态；支持单快照/整批补丁回滚、审批历史和进程中断恢复。
- `CoworkbenchStaticAnalyzer` 检查 Manifest 权限、导出组件、依赖变更、动态版本和敏感凭据；相关测试源码存在。
- `GitHubActionsCoworkbenchRunner` 使用 GitHub-hosted Actions runner 执行用户主动触发的 `workflow_dispatch`；仓库代码不会在 Android 进程内执行。当前只支持 GitHub Actions，GitLab/Gitee runner 尚未接入；真实 Workflow、权限和取消联调仍待后续验收。
- 因此，旧优化文档中“补丁预览、影响分析、明确应用、快照/回滚、隔离构建测试整体尚未实现”的状态已过期；当前已有代码实现。仍需确认远端 workflow 本身的权限最小化和实际运行结果，不能把 runner 代码存在当作真实 Actions 已验证。


### 统一任务基座重试语义补充核对
- `AgentTaskCoordinator.resume(taskId)` 支持用户/上层显式恢复 PAUSED、FAILED、WAITING_FOR_USER 任务；当前没有统一的 retryCount、最大尝试次数、退避策略或按错误类型判定可自动重试的策略。
- `PhoneAgentExecutor` 对动作中断采取先核对页面、再由用户决定是否重试，避免重复支付/提交等不确定动作；这一安全边界必须保留。
- `MediaAiTaskWorker` 的失败路径当前写入 FAILED，取消写入 CANCELLED；没有统一接入 AgentTaskCoordinator 的自动重试策略。
- 后续调度引擎需区分可安全重试的幂等任务与必须等待用户核验的设备/外部写入任务；不能对所有失败任务无条件自动重放。


## 2026-11-22 生成式插件执行链路权限与任务状态专项审计

- `GeneratedPluginWorkflowExecutor` 会按声明步骤调用 `AgentToolRuntime`，遇到 `requiresUser` 会停止后续步骤并返回等待用户的结果；当前没有保存当前 step/result 的可恢复 checkpoint，也没有返回一个可恢复任务句柄供用户确认后继续。
- `NestroApplication` 的生成插件执行入口自行创建 `plugin-${plugin.id}-${UUID}` taskId，并直接写 `RoomAgentTaskStateStore`；没有通过 `AgentTaskCoordinator.runInline/enqueue` 管理任务生命周期，也没有为 `generated-plugin:${plugin.id}` 注册可恢复 handler。进程中断后即使统一恢复器把 RUNNING 标记为 PAUSED，也没有插件步骤恢复入口。
- **权限确认存在明确绕过：** 生成插件的自定义 `AgentPermissionRouter` 对 `READ_ONLY` 和 `EXTERNAL_WRITE` 直接返回 Allow，没有检查 `tool.requiresConfirmation`。而 `media.video.generate` 已声明 `EXTERNAL_WRITE + requiresConfirmation=true`，因此生成插件可以绕过工具级确认直接触发该外部写入/可能计费操作。MCP 工具仍单独检查授权；DEVICE_ACTION/AUTHENTICATION/FINANCIAL 被拒绝。
- 该问题不是删除插件功能，而是把插件执行接回统一任务基座和统一审批：每步复用 `AgentPermissionRouter` 的确认语义，记录当前步骤 checkpoint；等待用户时持久化 WAITING_FOR_USER；批准后从该步骤恢复，拒绝后终止并记录审计。此项属于高优先级安全/一致性修复。
- 当前未修改代码；待全量审计完成后以最小补丁修复并补充“requiresConfirmation 不可绕过、批准后续跑、拒绝终止、进程中断恢复”测试。


### MCP 持久授权与任务级审批语义待统一
- MCP 工具注册时统一标记 `EXTERNAL_WRITE + requiresConfirmation=true`，并且每次调用前检查 `repository.isToolGranted(toolId)`；MCP 工具中心提供持久的单工具授权开关。
- 但 `NestroApplication` 主模型权限路由和 `McpToolCenterService.invoke()` 对已持久授权的 MCP 工具直接返回 Allow，因此不会再走通用的任务级一次授权/当前任务全部授权弹窗。
- 这意味着当前 MCP “已授权”实质是跨任务持续授权；撤销后立即阻断。它与工作流的任务级授权范围不同。后续需在权限设计中明确两层含义：MCP 工具中心的长期授权是否代表永久信任；任务执行时是否仍需一次性/本任务确认。不能让 `requiresConfirmation` 字段继续处于“声明为 true、路由却忽略”的含混状态。


### 模块18调度记录字段与幂等键缺口
- 当前 `CalendarEvent` 只记录日程 ID、标题/描述、开始结束时间、地点、重复规则、提醒分钟数、完成标记和系统日历关联；没有 nextRunAt/lastRunAt、触发 occurrence ID、关联 AgentTaskId、执行状态、重试计数或最后错误。
- 未来周期触发不能直接复用 scheduleId 作为 AgentTaskId，否则 `AgentTaskCoordinator.enqueue()` 的幂等规则会把后续 occurrence 视为同一个已完成任务。应以 scheduleId + plannedAt 生成确定性 occurrence/task ID，并持久化每次触发状态，确保重启/重复唤醒不重复执行。
- 这进一步确认当前模块18是通知提醒系统，不是统一任务调度引擎。


### 多模态能力范围补充核对
- 当前媒体 AI 任务 action 仅有 `transcribe`、`scenes`、`audio-clean`、`image`、`video-generate`；`MediaIntelligenceProvider` 提供图片理解 `analyzeImage`，没有 `analyzeVideo` 接口。
- 当前没有图像生成 action，也没有基于整段视频帧/音轨的多模态视频理解任务；`MediaPromptFactory.summarize()` 只是基于已提供字幕/转写生成摘要，不能冒充视频画面理解。
- 因此当前能力边界是：视频生成已由主模型工具调用；图片理解/音频转写/场景检测/音频处理由媒体编辑器任务触发；图像生成和完整视频理解尚无实现。后续按用户确认的模型服务商与目标能力接入现有 MediaAiTaskStore/Worker，不另造媒体任务系统。


### 工作流与主模型工具调度的接线边界
- 当前 `WorkflowStepKind` 仅包含 PROMPT、EXPERT、SKILL；`NestroApp.kt` 的工作流 `invokeModel` 直接调用 `ChatExecutionService.completeWithFallback()`，没有经过 `ChatRequestExecutionCoordinator/ModelLedToolDispatcher`，也没有为工作流步骤开放 AgentToolCatalog 工具租约。
- 因此现有用户工作流可串联提示词、专家和 Skill，但不能在工作流内部直接调用 Phone Agent、媒体视频/多模态工具或 MCP 工具；不能把它描述为完整的工具型自动化工作流。
- 日历未来若只调度专家/Skill 工作流，可复用当前 WorkflowExecutionCoordinator；若需要定时触发“创作+下载素材+视频生成”等混合任务，则应把该触发交给主模型工具调度器，或为工作流增加显式、权限受控的 TOOL/PHONE_AGENT/MEDIA 步骤。需先确认边界，再复用现有 ModelLedToolDispatcher，不能让工作流绕开统一权限/审批。


### Android 系统助手入口补充核对
- `AndroidManifest.xml` 已声明 `ACTION_ASSIST`；`AndroidAssistantRoleSupport` 在 Android Q+ 检查 `ROLE_ASSISTANT` 可用性/持有状态，并返回系统授权 Intent；`MainActivity` 处理助手入口路由到 Nestro 主聊天。
- 当前是系统助手调用入口，不是完整 `VoiceInteractionService/VoiceInteractionSessionService`；常驻热词、锁屏语音会话和屏幕上下文理解未实现。该功能属于已实现的基础入口与明确未实现的高级助手能力，不能混为一谈。


### 创作/手机混合调度的并行语义补充核对
- `ModelLedToolDispatcher` 已支持模型在多轮中交替调用专家、Phone Agent、媒体工具；Phone Agent/视频任务可快速入队后由 WorkManager 后台执行，因此存在跨轮交错的基础。
- 但 `OpenAiClient.nativeToolLoop()` 对同一轮模型返回的多个 tool calls 使用普通 `for` 顺序 await `onToolCall()`；兼容文本协议更明确限制每轮只输出一个动作。也就是说，当前是“可多轮交错 + 后台任务并行”，不是“同一轮独立工具调用并发执行”。
- 用户明确要求混合调度非串行；后续需加入并发安全策略：只有声明为并行安全且无数据依赖的调用才并发，写同一项目/文件/设备的动作仍需串行或显式依赖；结果按原 callId 顺序回注，取消/失败/权限等待要有一致语义。不能简单把所有工具调用改成 `async` 并发。


## 2026-11-22 PR #1 / memory 分支差异发现的真实回归候选

- `feature/knowledge-retrieval-ranking` 的 `KnowledgeTextDecoder.kt` 与测试文件在当前整合分支中 blob 完全一致；当前整合版的 `KnowledgePdfImportTest`、`LocalBackupRoundTripTest` 反而更大/覆盖更多，不能把 PR #1 的测试简单覆盖回来。
- 但旧分支 `UserMemoryStore` 曾有 `mutationVersion`、每项目 `Mutex`、`invalidateAndAwaitPendingExtractions()` 和 `saveCandidates(expectedMutationVersion)`。当前整合版删除了这些保护：自动提取直接调用 `saveCandidates()`，不校验项目仍存在或提取开始时的 mutation version；`edit/delete/clearProject` 也不等待并使旧提取失效。**存在已删除记忆被并发中的旧自动提取重新写回的竞态风险**，这是应恢复的正确性保护，不是新增业务功能。
- 旧分支的 `buildRecallQuery(recentUserTurns,currentPrompt)` 会合并最近用户轮次与当前问题，并保证当前 prompt 在 token budget 中优先保留；当前整合版把两类召回拆开：conversation recall query 只取 requestHistory，user-memory recall 只传当前 prompt，没有形成“近期用户上下文 + 当前问题”的统一 query。应核对是否造成跨轮约束漏召回。
- 旧分支 `prepareExtractionInput/shouldExtractFrom` 会对最近多条用户消息做安全筛选和信号判断；当前自动提取仅检查当前 prompt 并只传当前 prompt，可能丢失跨轮表达的稳定偏好/项目约束。
- 旧分支还支持 `setShareAcrossProjects` 与显式共享记忆；当前 `UserMemoryEntity` 没有共享字段，`UserMemoryRepository.recall()` 只按当前 projectId 查询，`ProjectResourceScopeStore` 也未包含 MEMORY。根据用户 2026-11-15 明确决策：全局个人长期记忆应跨聊天窗口共享；项目资料默认隔离，不自动串项目，但用户可以主动发起跨项目检索。当前实现缺少全局记忆范围和用户主动跨项目检索/授权/撤销/来源标识，属于明确功能缺口，不再作为待确认事项。
- PR #1 的 `CapabilityRegistryStore/CapabilityTaskEngine/CapabilityCenterPage` 是旧版独立能力注册/任务队列体系；当前整合版已使用 `AgentToolCatalog/AgentRuntime/AgentTaskCoordinator`。不应整套合并回去，但旧版的 retry/backoff/idempotency、项目能力目录/审计 UI 可能包含未迁移的产品能力，需逐项映射到新基座。


### 模块16 用户可见能力中心/任务总览差异
- 当前 `CapabilityDirectoryPage` 主要展示内置能力说明、Skill 包、社区目录签名/信任根和安装状态；它没有旧版 `CapabilityCenterPage` 的通用能力逐项目授权开关、统一能力审计记录和跨模块任务队列。
- 当前 `AgentToolCatalog` 是进程内工具定义目录，`AgentToolLeaseRegistry/AgentCapabilityRegistry` 是执行期临时租约；它们不等于持久化的用户级/项目级能力注册表。MCP 有独立逐工具持久授权，但其他内置能力没有同一套通用开关/审计 UI。
- 旧 PR #1 的 `CapabilityCenterPage` 依赖旧 `CapabilityRegistryStore/CapabilityTaskEngine` 和独立 capability_tasks 表；这些旧类/表当前未进入 integration。不能整套合并回来造成第二套工具目录和任务队列。
- 后续应在现有 `AgentToolCatalog/AgentTaskCoordinator` 上设计新的能力总览页：统一展示能力可用性/权限/来源、项目授权、任务状态/最近事件和审计；任务数据直接读取统一状态源，不重新建 CapabilityTaskEngine。



## 2026-11-22 用户确认的统一产品能力验收基准

权威产品能力边界已写入 `IMPLEMENTATION_ROADMAP.md` 的“NESTRO 核心能力边界”章节。本审计后续所有模块以此作为跨模块核对基准：听得懂（音频识别/理解）、看得懂（图片/视频理解）、能创作（专家团和媒体生成）、能执行（Phone Agent/项目工具）、会协作（主模型按依赖混合调度）。

特别要求：专家团、Phone Agent、媒体模型不得各自建立重复调度系统；应复用 AgentRuntime/AgentToolCatalog、任务队列、权限路由、项目范围、事件检查点和取消/恢复。创作与执行允许多轮交替、串并行组合；工具/模型必须回传真实状态与产物，主模型复核后交付。

后续审计必须分别核验每项能力的源码、入口、端到端调用、状态/产物回传、持久化/恢复和测试覆盖。只有 UI 或 Provider 类存在，不等于功能闭环。

### Phone Agent 终态任务可被旧写入复活：历史修复已回收
- 对照 PR #1 的旧版 `NestroDatabase.kt` 与整合版，确认曾丢失两条 SQL 终态保护。
- 已在整合提交 `31fedead85e036a870c4eea6e0fbad862cf908d8` 恢复：`updatePhoneAgentTaskProgress` 禁止将 completed/cancelled 改写为其他状态，但允许同终态更新；`updatePhoneAgentTaskPlan` 禁止重置 completed/cancelled 任务。
- 同一提交从历史分支回收 `NestroDatabaseMigrationTest.cancelledPhoneAgentTaskCannotBeRevivedByProgressOrPlanWrites()`，覆盖迟到的进度与计划写入不能复活已取消任务。
- 本次只恢复已存在的历史安全实现和对应回归测试，没有改动任务调度架构；DAO 目前仍返回 Unit，尚未把受影响行数传回调用方。本项 SQL 保护已整合，DAO 结果反馈不属于本次历史代码回收范围。



## 2026-11-22 多模态理解能力主模型入口核查（补充）

### 已存在实现（复用，不重写）
- 实时/录音语音输入：`AndroidSpeechRecognitionController`、`SpeechRecordingController`、`SpeechProviderConfigStore`、`OpenAiCompatibleSpeechProviders`、`OpenAiRealtimeVoiceProvider` 已存在；`NestroApp` 有语音输入/语音系统页面接入。
- 音频/视频文件转写、视频场景识别、音频处理、图片理解：`MediaIntelligenceProvider` 和 `OpenAiCompatibleMediaIntelligenceProvider` 已实现；`MediaAiTaskStore`、`MediaAiTaskWorker`、`MediaAiTaskScheduler` 提供持久后台任务、进度、取消、恢复和结果保存；`MediaEditorPage` 有四类操作入口。
- 视频生成：独立视频模型配置、异步生成客户端、后台任务和素材导入已存在，主模型可经 `media.provider.list`、`media.video.generate`、`media.video.status` 调用。
- 图片理解、转写、场景识别、音频处理均已有媒体编辑器调用闭环的主要代码，不应另建第二套 Provider/Worker/TaskStore。

### 已确认的主模型入口缺口
- `NestroApplication.agentToolCatalog` 当前注册了 Phone Agent、专家团队、项目素材查询、媒体 Provider 列表和视频生成/状态工具；**未注册图片理解、音频文件转写、视频场景理解/分析、音频处理的聊天工具入口**。
- `ModelLedToolDispatcher` 的主模型指令也只明确了视频模型路由；未定义主模型如何基于附件/项目素材选择并调用上述媒体理解能力。
- 因此，语音按钮能把用户说的话转成聊天文本，不等于主模型能够分析用户上传的音频文件；媒体编辑器里有“理解图片/AI 转字幕/识别场景/音频处理”，也不等于普通聊天或专家项目能按需调度它们。
- 当前准确状态：媒体分析底层与编辑器入口已有；主模型可调度入口缺失/未发现。后续应优先复用现有 `MediaAiTaskStore/Worker/Scheduler` 与项目素材库，补统一媒体分析工具 schema、任务创建/等待/状态查询、结果回传；先核查是否有可复用的通用工具执行封装，再作最小改动，不得复制 Provider/Worker。

### 主模型媒体工具应覆盖的能力边界
- `image_understanding`：图片素材/附件 → 视觉模型 → 可读分析结果回主模型。
- `audio_transcription` / `audio_understanding`：音频素材/附件 → 转写模型或支持音频理解的模型 → 文本/结构化结果回主模型；语音识别输入和文件分析分别验收。
- `video_understanding`：视频素材 → 已配置场景/视频理解服务 → 时间段、场景、摘要等结果回主模型。现有 scene detection 是结构化场景识别能力，不得夸大为任意视频问答，能力声明需区分。
- `audio_processing`：仅在用户要求降噪/处理等操作时调用；结果音频导入当前项目并回传素材 ID。
- 视频生成继续复用现有独立生成工具，不与视频理解合并成同一个能力声明。
- 对每类能力按 Provider 实际能力/配置逐项显示可用性；不能只凭共享的 `model` 字段假设支持音频/视觉/场景接口。没有服务商端点、API Key 或能力声明时，主模型应明确提示缺失并提供可执行替代方案。

## 2026-11-22 历史分支差异语义复核（代表性文件）

- 模块9旧分支 `feature/module-9-voice-system` 的核心 Speech Provider/Realtime/TTS 文件与整合分支 blob 一致；其 Manifest 仍缺整合分支现有的日历权限、`FOREGROUND_SERVICE_MEDIA_PROCESSING`、Mika Helper Provider 等配置；旧 `LocalVault` 也缺 `ModelApiProtocol` 保存/恢复。不能把旧 Manifest、LocalVault 或旧版巨大 `NestroApp.kt` 覆盖到整合分支。
- 模块13旧分支 `feature/module-13-mcp-tool-center` 的 `McpToolCenter.kt` 缺少当前整合分支的同步审计恢复策略：当前版本在损坏审计隔离失败时明确报错，且工具不存在时审计关联失败；旧版会静默返回空列表/忽略审计，可靠性更弱。当前整合分支版本应保留。
- 模块14旧分支 `feature/module-14-github-workbench` 的 `GitWorkbench.kt` 在账号元数据保存/删除失败时只做 best-effort 恢复旧密文；当前整合分支会验证恢复结果，恢复失败时明确抛出严重错误。保留整合分支增强版，不合并旧版。
- 模块15旧分支 `feature/module-15-ai-coworkbench` 的核心 Service/Page 文件与整合分支 blob 一致；其差异不构成需重建的功能缺口。
- 模块8旧分支差异集中于旧版 workflow/instrumentation 脚本与 Gradle 配置；整合分支配置更新，不能覆盖。需要在统一构建阶段审查当前 CI 配置有效性，而不是把旧 CI 文件当功能代码合并。
- `main` 分支独有的三个旧 helper 已在前文说明：旧关键词路由与当前主模型原生工具调度重复；网络策略逻辑已由现有 ChatRequestPolicy/请求执行层承接；TTS Voice 排序暂未形成用户确认需求。均不直接恢复。
- 以上是逐文件内容对照，不是整分支合并。分支提交图上的 ahead/behind 只反映提交图差异，不能证明功能是否缺失。



## 2026-11-22 PR #1 / #3 与统一能力中心功能去向核对

### PR 状态（GitHub 实时元数据）
- PR #1 `WIP: Improve knowledge retrieval ranking`：Open + Draft，head `feature/knowledge-retrieval-ranking`，base `main`；未合并。
- PR #3 `[Phase 2] Refactor feature state ownership — Batch A`：Open + Draft，head `refactor/phase-2-architecture`，base `feature/voice-system`；未合并。
- 两个 PR 的 base 都不是当前 `integration/19-modules`。不能因为 PR 仍开着就把整个旧分支合入当前基线，也不能因为当前分支已有较新功能就不核对 PR 中的用户可见功能。

### PR #1 中的旧统一能力中心
旧分支包含 `CapabilityRegistryStore`、`CapabilityTaskEngine`、`CapabilityCenterPage` 及对应 Room 任务实体/事件表。当前整合分支树中不存在这些同名实现；当前抽查的 `CapabilityDirectoryPage` 实际承载 Skills/社区目录，`GeneratedPluginCenterPage` 承载声明式插件生命周期，二者均不能直接等同于旧 PR 的统一能力中心总览。

**已确认需要纳入功能完整性审查的用户可见能力：**
- 汇总显示模型、专家/团队、Skills、工作流、知识库、记忆、Phone Agent、MCP、GitHub、媒体等能力的可用/配置状态；
- 能力搜索、授权/撤销、项目范围、确认要求与调用审计的统一查看入口；
- 任务状态、最近事件、暂停/继续/取消的统一观察入口。

**实现处理边界：**
- 旧 `CapabilityTaskEngine` 不直接合并：当前 `AgentRuntime` + `AgentToolCatalog` + `AgentPermissionRouter` + `AgentTaskCoordinator` + Room 状态/Trace Store 已承担工具注册、按任务租约、逐次授权、持久任务状态、检查点、等待用户、暂停/继续/取消和进程恢复。复制旧任务引擎会产生第二套调度系统。
- 但当前共享任务内核不等于旧能力中心的完整 UI/持久化授权目录；需以当前工具目录、权限路由、项目授权和任务 Trace 为数据源，评估是否缺少统一可视化/可管理入口。旧 PR 中的能力元数据/授权/审计数据模型只能作为需求参考，不能直接照搬。
- 旧 `CapabilityCenterPage` 不能整页直接复制：它依赖旧 CapabilityRegistryStore、旧 capability task 表及旧路由；应复用现有 Nestro 页面导航、AgentToolCatalog、权限路由、MCP/插件/技能现有数据源，若确认缺口，再做最小 UI 接入。

### PR #3 状态所有权重构
- PR #3 的主要目标是从大型 Compose 根页面拆分模型管理 UI 状态；当前整合分支已存在 `ModelManagementUiState`、`ConversationUiState`、`ProjectSpaceUiState`、`WorkflowUiState`、`ExpertWorkspaceUiState` 等分离状态文件。
- PR #3 以旧 `feature/voice-system` 为 base，当前 `integration/19-modules` 相对该旧分支已大幅演进；不得整分支合并或用其旧版 `NestroApp.kt` 覆盖当前根页面。后续应以当前文件继续审查剩余状态是否还有可安全拆分的部分。



## 2026-11-22 聊天输入多模态边界与代码基线快照

### 直接聊天输入的源码证据
- `ChatMessage.kt` 的消息模型只有 `role` 与 `content` 两个字段，没有 attachment/media payload、MIME、文件引用或多模态 part。
- `OpenAiClient.kt` 中 OpenAI Chat Completions、Anthropic Messages、Gemini Generate Content 的普通消息序列化均将 `message.content` 作为纯文本发送；未发现 image_url、input_audio、视频帧/视频文件等消息块序列化。
- 因此当前主聊天不能直接把用户上传的图片/音频/视频作为多模态消息发给模型。语音输入按钮的系统识别结果转成文本后再聊天，不等于音频文件理解；媒体编辑器可以对已导入素材调用 Provider，也不等于普通聊天能直接理解附件。
- 现有 `MediaProviderConfig` 只有聊天/视觉 model、transcriptionModel、scenePath、audioProcessPath、videoGenerationModel 等字段；没有独立的通用 video-understanding model/能力声明。场景识别通过专属 scenePath 接口返回分段，不等于支持任意视频内容问答。
- 这是模块3/19的真实功能缺口，后续应先确定用户输入附件的存储与项目素材引用方式，再扩展 ChatMessage/Provider 能力适配与主模型工具调用；不能在 OpenAiClient 里简单把文件路径拼进 prompt，也不能假定所有 Provider 协议都接受相同多模态格式。

### 当前整合基线快照
- 分支：`integration/19-modules`
- 本轮文档更新后的 HEAD：`6ac8588069ae824b280d92809dfb8baca68c83f3`
- 当前 Git 树：354 个文件；其中 317 个 Kotlin 文件，199 个 app 主源码 Kotlin 文件、87 个 JVM 测试源码、31 个 Android instrumentation 测试源码。
- HEAD Combined Status 返回空状态列表，关联 PR workflow runs 返回空列表；不能宣称当前 HEAD CI 已通过。
- 以上统计是源码文件数量，不是完成率；测试源码存在不等于测试执行通过。



## 2026-11-22 重复实现、旧代码与调用边界初判

本节只记录已取得源码证据的初判；不凭文件名相似就删除。最终删除前仍需全仓引用扫描、构建依赖核对和行为替代验证。

### 明确不应重新合入的旧实现
- 旧 `UnifiedAgentDispatcher.kt`：旧 main 分支中的关键词计数路由，与当前 `ModelLedToolDispatcher` + provider-native tool calling 重复且能力更弱；不恢复旧文件。
- 旧 `CapabilityTaskEngine`：PR #1 的独立 CapabilityTaskStore/Engine 与当前 Room-backed `AgentTaskCoordinator` 任务状态、trace、checkpoint、等待用户、暂停/恢复/取消和进程恢复重叠；不复制第二个任务队列。旧能力中心 UI/能力授权总览是另一回事，不能因此一起丢掉。
- 旧 `ChatNetworkPolicy.kt`：其重试策略与当前 `ChatRequestPolicy`/请求执行层重叠；不恢复第二套网络策略入口。
- 旧 `TtsCapability.kt`：仅为设备 Voice 排序 helper，当前整合分支未包含；现有 `AndroidTextToSpeechController` 负责真实设备语言能力。暂不恢复，除非后续 UI 明确要求质量/延迟排序。
- 旧模块9分支 Manifest、LocalVault、NestroApp、模块13 MCP 和模块14 Git 版本已比当前整合分支弱；前文已列出具体差异，禁止整分支/整文件覆盖。

### 当前看似相似、但职责不同，不应直接删除
- `ChatExecutionService`：底层模型请求/取消传输；`ChatStreamingCoordinator`：流式增量批处理和部分回答持久化；`ChatRequestExecutionCoordinator`：一次聊天请求的上下文、工具调度和最终响应。三者是分层调用，不是三套并行聊天管线。
- `ConversationContextRepository`：会话历史/知识检索边界；`ConversationMemoryStore`：具体会话检索实现；`ChatRequestContextAssembler`：并行选择并组装上下文。当前 NestroApplication 的 assembler 通过 repository 方法引用调用它，不能误判为未使用。
- `PhoneAgentTaskService`：设备任务应用层；`PhoneAgentWorkScheduler/Worker`：WorkManager 持久后台执行桥；`AgentTaskCoordinator`：跨功能共享任务状态/trace/生命周期。PhoneAgentTaskService 已持有共享 coordinator，不应仅因都出现“Task”就删掉其中一层。
- `MediaAiTaskStore/Worker` 与 `MediaRenderTaskStore/Worker`：分别管理媒体 AI 分析/生成和时间线渲染，结果类型、恢复/取消和资源边界不同；目前没有证据证明是重复实现。
- `CalendarReminderScheduler`：当前只调度日历提醒通知；`AgentTaskCoordinator` 管理可执行任务。二者不是重复代码，问题是日历到期触发尚未分发到 Agent/Workflow。

### 需要做最小整合/修复的真实缺口
- **专家团工具描述冲突**：`NestroApplication` 注册的 `expert_team.run` 描述仍说“用户明确选择的专家团队”，但 `ModelLedToolDispatcher` 系统提示和 `NestroApp.runExpertTeam` 回调已支持主模型从项目专家目录自动选 2–4 人。描述过时会误导模型，应统一为“优先使用用户已选团队，否则主模型可自主选择可用专家”。
- **插件审批粒度**：插件总工具 `plugin.*` 本身标记 `requiresConfirmation=true`，其描述会列出工作流步骤并标记哪些底层工具自身要求确认；内部执行器随后对 READ_ONLY/EXTERNAL_WRITE 直接 Allow，因此当前语义是“用户先整体确认插件工作流”，而不是逐步骤再次弹窗。不能直接称为未授权执行，但必须核验审批 UI 是否清楚展示完整步骤、权限和实际参数；若只展示插件名称/摘要，则需补逐步确认与 checkpoint/resume，不能把整体确认误当永久授权。
- **插件任务生命周期**：插件 handler 直接写 RUNNING/COMPLETED/FAILED/PAUSED 状态并写入 Room trace，但逐步执行未调用 `AgentTaskCoordinator.enqueue/runInline/checkpoint/resume`；取消后标为 PAUSED，未见可恢复的步骤检查点/handler 恢复链路。应复用共享 coordinator 补 checkpoint/恢复，不新建第三套任务引擎。
- **统一能力中心 UI**：当前 `CapabilityDirectoryPage` 是 Skills/社区目录，不等于模型/专家/工作流/知识/记忆/设备/MCP/GitHub/媒体的总览、项目授权、可用状态和任务审计中心。旧 PR #1 的页面不可直接复制，但该产品入口缺口仍需基于现有目录/权限/trace 数据源补齐。
- **混合调度**：主模型系统提示已有专家/手机/素材按依赖穿插调用的规则，Phone Agent 的任务创建/等待/素材核验代码已接入；仍需联测多轮交替、等待用户接管、失败重试和专家真正读取素材。不能再造新的混合调度器。
- **多模态理解**：聊天 transport 纯文本、媒体分析工具缺失，已在前文记录；复用现有 Media AI 任务链路补主模型入口。
- **日历自动调度**：当前提醒只发通知；需要把到期事件关联 Agent/Workflow/Phone Agent、状态回写、审批、失败重试和幂等恢复接通，复用共享任务内核。

### 测试源码覆盖缺口（只统计文件存在，不代表已执行）
- 当前 87 个 JVM 与 31 个 Android instrumentation 测试文件。
- 已有 AgentRuntime/AgentTaskCoordinator、专家 DAG/团队、WorkflowEngine/WorkflowExecutionCoordinator、Phone Agent 策略、知识库、记忆、MCP、Git、插件工厂、媒体 Provider/AI Task Store、日历规则等测试源码。
- 未发现 `ModelLedToolDispatcher` 端到端调度测试；未发现 `GeneratedPluginWorkflowExecutor` 的权限/确认/恢复测试；未发现 `MediaVideoGenerationClientTest` 或媒体 AI Worker 完整 HTTP/结果导入测试；未发现 Calendar Reminder → Agent/Workflow 分发测试。
- 对应缺口补测应随最小代码修复一起补，不要先创建与现有逻辑脱节的空测试。



## 2026-11-22 PR #1 历史能力回收：确认存在的功能不能因架构替换而丢失

PR #1 虽然基于旧 `main` 且整体不适合合并，但源码对照确认其中包含若干当前整合分支尚未保留的用户可见/数据能力。不能将其一概归类为旧垃圾代码。

### 记忆系统：明确缺失、应纳入回收
旧 PR #1 的 `UserMemoryStore` 与 `NestroDatabase` 包含以下实现，当前 integration 版本缺失：
- 每项目 mutationVersion + Mutex；自动记忆提取开始时捕获版本，保存候选前重新核对版本，防止用户删除/编辑/清空记忆后，较早启动的异步提取又把旧内容写回来。
- `invalidateAndAwaitPendingExtractions(projectId)` / 全局版本失效并等待在途写入；在会话/项目删除、自动记忆关闭和完整备份恢复前调用。
- 记忆显式跨项目共享开关 `shareAcrossProjects`，用户主动开启/撤销；检索时只允许目标项目读取明确共享的来源项目记忆。
- 数据库迁移为 `user_memories.shareAcrossProjects` 新增字段；记忆备份/恢复保留该授权状态；UI 展示授权开关和撤销反馈。
- 旧版本的 `saveCandidates(expectedMutationVersion=...)`、`setShareAcrossProjects`、`edit/delete/clearProject` 均受项目级锁保护。

当前 integration 的 `UserMemoryStore.kt`（8.7 KB）不包含上述 mutationVersion/锁/等待失效/跨项目分享；`UserMemoryEntity` 也无 shareAcrossProjects 字段；`AutomaticMemoryExtractionCoordinator` 调用保存回调时没有传入提取开始时的版本。当前 `ProjectAccessControlStore` 是项目资源 READ/WRITE 授权，不等于记忆条目级跨项目共享；不可拿它当成这项功能已经实现。

处理方式：将旧 PR 的记忆生命周期/共享能力作为**需要回收的已实现功能**，按当前 UserMemoryRepository/AutomaticMemoryExtractionCoordinator/Room/备份结构移植；不得直接覆盖旧版 NestroApp 或整套 UserMemoryStore。迁移必须保留旧备份兼容、删除级联、共享撤销即时生效和并发提取测试。

### 统一任务内核：回收语义，不复制旧引擎
旧 PR #1 的 `CapabilityTaskEntity/CapabilityTaskStore` 还具备当前 `AgentTaskCoordinator` 未完全覆盖的任务语义：
- 基于 `projectId + kind + callerKey` 的持久业务幂等键（当前 AgentTaskCoordinator 主要按 taskId 幂等，不能自动去重不同 taskId 的同一业务请求）。
- `attempts/maxRetries/availableAt` 与 RETRY_WAIT 延迟重试/退避。
- 任务记录中的 projectId、conversationId、kind、capabilityId、title、input、resultSummary、requiresConfirmation、startedAt/finishedAt 等可检索元数据。
- 有 from/to 状态迁移、事件名称/详情和任务事件分页/裁剪。
当前 `AgentTaskStateEntity` 只有 taskId、owner、status、currentStep、checkpoint、lastError、updatedAt；`AgentTaskCoordinator` 已覆盖共享队列、状态、trace、checkpoint、等待用户、暂停/恢复/取消和进程恢复，但没有同等的业务幂等键/统一重试计划/完整任务元数据。
处理方式：保留 AgentTaskCoordinator 为唯一执行内核，将必要的业务幂等、重试调度、项目/会话/能力元数据和状态事件补入它或其持久化模型；不恢复 `CapabilityTaskEngine` 第二套执行器。

### 统一能力中心：回收用户可见功能
旧 PR #1 的 `CapabilityRegistryStore/CapabilityCenterPage` 提供持久能力元数据、项目级模块授权/撤销、状态搜索、全局路由守卫、授权前确认、调用审计和任务总览。当前 integration 没有同名实现，`CapabilityDirectoryPage` 只负责 Skills/社区目录，`ProjectAccessControlStore` 只负责跨项目资源授权。
处理方式：恢复统一能力总览和项目能力授权/撤销/审计的产品功能，但以当前 AgentToolCatalog、AgentRuntime、ProjectAccessControlStore、MCP 授权、Skill/插件配置和 AgentTaskTrace 为数据源；不直接合入旧 CapabilityRegistryStore/CapabilityCenterPage，以免重复注册和旧路由覆盖。

### PR #1 旧分支整体处置
- PR #1 继续保持 Draft/未合并；其旧代码作为功能来源和差异参考。
- 知识库文本解码等已确认与当前文件 blob 一致的功能无需再合并。
- 仅把上面确认缺失的能力逐项移植到当前架构，并为每项保留对应迁移、备份兼容、并发/撤销/恢复测试。
- 不得以“旧架构过时”为由把已实现的用户功能直接丢弃，也不得把整条旧分支直接 merge 进来。



### PR #1 知识库代码的去留复核
- `KnowledgeTextDecoder.kt` 与当前整合分支 blob 完全一致，无需再次合并。
- PR #1 的 `KnowledgeChunker.kt` 对 chunkSize/overlap 和 Unicode surrogate 边界约束较弱；当前 integration 增加偶数 overlap、最小新增内容和 UTF-16 code-point 安全边界，保留当前版本。
- PR #1 的 `KnowledgeSearchEngine.kt` 与当前 integration blob 完全一致。
- PR #1 的 `KnowledgeStore.kt` 旧版使用 PDFBox 文本抽取，明确没有扫描 PDF OCR，且没有当前版本的 BGE 向量混合检索/检索模式元数据；当前 integration 已有 `KnowledgePdfOcr`、BGE、BM25/FTS 混合排名和来源片段标签。保留当前版本，不回合旧版知识库实现。
- 因此 PR #1 的知识库核心功能不是整体丢失；真正需要回收的是前述记忆生命周期/共享与统一能力中心功能，不应把旧知识库代码整体移植回来。



## 2026-11-22 已落地的最小代码修复（未执行构建/测试）

### 专家团自动选择描述对齐
- `NestroApplication.kt` 中 `expert_team.run` 的工具描述已从“必须使用用户明确选择的团队”修正为：优先用户已选团队；否则主模型可从当前项目可用专家目录自主选择 2–4 位并行分工汇总。
- 对应提交：`81a4e513516aec74b46a946df1d9e5880da8b423`。

### 自动记忆过期写回保护已回收到当前架构
- `UserMemoryStore` 新增项目级 mutation token（项目版本 + 全局恢复版本）、项目 Mutex、项目/全局 invalidate-and-await。
- 自动提取在调用模型前捕获 token；保存时在项目锁内核验版本，不接受已被编辑/删除/清空/关闭自动记忆/备份恢复操作失效的旧候选。
- 手动导入、自动保存、编辑、删除、清空均经过同一项目锁并更新版本；记忆向量 embedding cache 在编辑/导入/删除/清空后清理。
- 项目/会话清理路径在删除前等待并失效相关项目提取；完整备份/项目包恢复在数据库替换前失效全局提取；关闭当前项目自动记忆时也失效在途提取。
- 新增测试源码：验证项目级/全局版本失效、自动提取在模型调用前捕获 token 并把旧 token 传给保存层。
- 主要代码提交：`3f34e5897d333974017bba6be74bc77d5e1059de`、`b1f02f2c8e47ff3e0e3cfeeb4ffea07e3ea6cd02`、`740f1de0f7533b6738b2fe5e1585f2b94798c701`、`4b907f910758a6ebb0a440bd7118ac270d1038c7`、`8a31098269abaf207c5dde51534e50c67b5f6beb`、`86e7695d90bc3d494d99a7441ce3540a1701e464`、`02a4d61fea73c134615ccffe4e13d0a363a3f2de`、`e9c161bb06ea184d3b7ba5e1c8dd4d5ce08b3460`。
- 测试源码提交：`ce0952ee2ba0225cbd74a14547a2362724ee6207`、`08d87d6ebe363050430f75639661d926f0f648ed`。
- **限制**：尚未运行 Kotlin 编译、JVM/Android 测试；这只是代码与测试源码已提交，不能报告验证通过。跨项目记忆共享字段/UI/备份兼容仍未移植；独立上下文缓存也仍未实现。



## 2026-11-22 记忆跨项目显式共享功能已回收（代码已提交，未执行测试）

- Room `UserMemoryEntity` 新增默认关闭的 `shareAcrossProjects` 字段；数据库升级到 v14，新增 13→14 migration，旧数据默认 false，不会自动扩大可见范围。
- DAO 新增只读取当前项目记忆 + 用户明确开启共享且当前项目已获得来源项目 READ 授权的记忆；两道权限缺一不可。默认 list 管理页面仍只展示记忆所属项目，不复制共享数据。
- 用户可在记忆卡片逐条开启/撤销共享；目标项目还必须在 ProjectAccessControlStore 中获得来源项目 READ 授权；任一权限撤销后，目标项目下一次召回立即不再返回该条目。召回上下文会注明来源项目 ID。
- 自动提取刷新同一 fingerprint 时保留旧条目的共享授权，不会因后台刷新而意外撤销或重新开启。
- 完整备份导出/恢复保留共享授权；旧备份没有该字段时按 false 处理；字段类型错误会拒绝恢复。单条记忆导出/导入不继承共享授权，避免跨设备导入时意外扩大权限。
- 新增 Android instrumentation 测试源码：跨项目默认隔离、明确开启后可召回、撤销立即生效、自动刷新保留授权、v1→v14 迁移默认关闭、完整备份往返保留共享授权、损坏共享字段拒绝恢复。
- 主要提交：`7b7f6e5bc690ca43a4d852001c63519e8bdf002b`、`7177f28f08b7aa60d47436b621cb31a732dad3ac`、`aaea141d6311b28e855ccefad0ef40d2fd7ce90e`、`f4ebb5b6cdcd7d7b1c0b2aa3d3be51be43b41473`、`05b58dc9f92f6d1925dc3a154142947d347a9d7b`、`833497a0fd599910c58997b28b0c2629952ed99c`、`7f3c245684a6ae4beea50766983d33b89e4c99c4`、`2d1df77bcd8d4345e41132eacb4b5685b9ebd9e8`、`04be6c368f26e53c25565b22be4056f600e86ad3`、`f31eb0eaba7f8967c8aa00827972cb4784012250`。
- 该功能现已从 PR #1 的旧架构移植到当前 UserMemoryStore/Repository/Room/备份体系；不是整分支合并。
- **验证状态**：仅代码与测试源码已提交，尚未执行编译、迁移 instrumentation、跨项目召回测试或备份恢复测试。




- 二次核对发现：仅凭 `shareAcrossProjects` 会绕过项目级读取授权。已修正 `UserMemoryRepository.recall`、DAO 查询与 `NestroApplication.ChatRequestContextAssembler`：只有“条目显式共享 + 目标项目持有来源项目 READ 授权”同时成立才可召回；UI 文案同步说明双重条件。回归测试源码已覆盖无 READ 授权时不可见、有授权时可见、撤销条目共享后不可见；测试仍待执行。

## 2026-11-22 主模型媒体理解入口已接通（代码已提交，未执行测试）

- `NestroApplication.agentToolCatalog` 新增 `media.ai.run` 与 `media.ai.status`；`media.provider.list` 现在列出配置实际具备的图片理解、音频转写、视频场景分析、音频处理和视频生成能力。
- `ModelLedToolDispatcher.nativeToolSchema` 已补齐媒体分析工具参数 schema；主模型系统提示明确先列出当前项目素材、检查 Provider 能力，再按素材类型选择 image/transcribe/scenes/audio-clean；超时后使用 task_id 查询，不得虚构结果。
- 工具复用原有 `MediaAiTaskStore`、`MediaAiTaskWorker`、`MediaAiTaskScheduler`、`MediaProviderConfigStore` 和 `MediaProjectStore`；未新建 Provider、任务表或 Worker。媒体上传到第三方服务前要求确认。
- 图片理解调用现有视觉模型；音频/视频转写返回带时间戳文本；视频场景分析返回时间段/场景描述；音频降噪生成新素材并导入当前项目。
- **能力边界**：只处理当前项目已导入素材；当前 ChatMessage/OpenAiClient 仍是纯文本，不能直接读取未导入的聊天附件。视频场景分析不等于通用视频问答；音频转写不等于非语音声音事件理解。
- 主要提交：`04dfa43f6fdd6f663ed2843433c4e6c03ecfef53`、`55640f9f218916ca16a582fb154847ea464fc51e`。
- **验证状态**：代码已提交；尚未执行 Kotlin 编译、主模型原生工具调用、第三方 API 联调或设备验收；尚无专门的 ModelLedToolDispatcher 媒体工具端到端测试。



### 聊天附件到媒体模型的桥接已补
- 聊天输入区新增多选图片/音频/视频文件导入；文件通过 `MediaProjectStore.importAsset` 复制到当前项目私有素材库，不把原始文件路径直接塞进 prompt。
- 导入成功后，草稿附加真实 `asset_id/name/kind/mime` 元数据；主模型按既有提示先查询 `media.project.assets`，再用 `media.ai.run` 调用图片理解/音频转写/视频场景分析/音频处理。
- 导入最多 10 个文件；若导入期间切换项目或会话，不会把素材 ID 错贴到新会话；会明确提示素材留在原项目且未附加到当前草稿。
- 这是“聊天文件导入 → 项目素材 → 主模型工具 → 专业媒体 Provider”的可用桥接；`ChatMessage` 和聊天 Provider 仍未实现原生多模态 content parts，因此不能描述为 OpenAI/Anthropic/Gemini 原生附件消息。
- 提交：`c7e69c215e94a391a95a776b231245a706524c47`（清单）；聊天导入代码提交为当前 NestroApp 的附件导入修复提交，待回读确认。
- **验证状态**：尚未运行 Android 编译、文件选择器 UI 测试、图片/音频/视频真实导入和主模型调用链测试。



### 后台任务穿插调度已调整为立即返回 task_id
- `media.ai.run` 现在只负责校验当前项目素材/Provider、创建 MediaAiTask 并排入 WorkManager，立即返回 task_id，不在主模型工具调用中阻塞 45 秒。
- `media.video.generate` 同样提交后台任务后立即返回 task_id，不再阻塞等待 150 秒；结果由 `media.video.status` 查询。
- Phone Agent 本身也是创建任务并进入 WorkManager 队列后返回 task_id；`expert_team.run` 内部仍按 DAG 并行专家工作包。
- `ModelLedToolDispatcher` 的调度提示已明确：先启动长任务，再穿插处理与其无依赖的专家创作/资料整理；依赖素材的下游步骤必须等任务完成并确认素材已导入项目后再执行；无其他并行工作时轮询状态，未完成不得冒充完成。
- 这使主模型可以在一个任务中先启动 Phone Agent 下载、启动媒体分析/视频生成，再调用专家团处理其他工作，后台 Worker 并行推进；不是新建混合调度器。
- 相关代码提交：`723d7d62ab128ab09a86c233d8a3d005026159f1`、`4d9456f5418eae70580c568f09680fe03758fd51`、`83cd34e6268bb277c35d61399f2a57e2026c6039`。
- **验证状态**：未执行 WorkManager 并行运行、取消/恢复、真实下载/媒体 API 和专家 DAG 联合测试；仍需补对应集成测试。



## 2026-11-22 PR #1/#2/#3 与整合分支的最终关系核对

- **PR #2（feature/voice-system）**：GitHub 状态为 closed、merged=false；但该分支相对 `integration/19-modules` 为 ahead=0、behind>0，即它没有任何当前整合分支尚未包含的独有提交。语音输入、WorkManager 背景任务、Artifacts、社区 Skill 目录等代码已经通过其他整合提交进入当前分支；不需要再合 PR #2。
- **PR #3（refactor/phase-2-architecture）**：仍 open + Draft，base 为旧 `feature/voice-system`；相对当前整合分支 ahead=0、behind=881，没有独有领先提交。抽查 `ModelManagementUiState.kt` 与当前整合分支 blob 完全一致；当前 Android workflow 和代码地图是更新版本。PR #3 的代码已被当前整合分支包含/超越，不需要再合 PR #3。
- **PR #1（feature/knowledge-retrieval-ranking）**：仍 open + Draft，base 为旧 `main`，相对当前整合分支存在独有领先提交。不得整分支合并；知识库核心已由当前 BGE/OCR/检索实现替代，但旧 PR 中的记忆生命周期/跨项目共享和统一能力中心功能曾被架构替换而丢失，前文已逐项列出并开始移植。
- 所以“PR 是否显示 merged”和“代码是否已经进入 integration”必须分开判断。PR #2/#3 虽不是 GitHub merged 状态，但其提交已在 integration 历史中；PR #1 的一部分功能仍需有选择地回收。



## 2026-11-22 PR #1 选择性整合后的十九模块差异扫描

核对分支：`integration/19-modules`  
本轮源码 HEAD：`9bcae15e8ec373834d01c0e2ad3c0adae5158ced`  
对照检查点：`f5cbffb9f1a06acb5f53cccc275541e5d9f43de0`；GitHub compare 为 81 commits ahead / 0 behind，25 个文件发生变化。  
当前 Git tree：360 个文件；321 个 Kotlin 文件，其中主源码 200、JVM 测试源码 88、Android instrumentation 测试源码 33。

本轮以已有十九模块审计为基线，重新对照整合后的全部模块状态，并逐项回读新增/修改的源文件、测试、调用入口和数据持久化。代码差异集中影响模块 1、3、4、7、13、16；其余模块未发现相对上述检查点的源码变更，原有模块审计结论继续有效。该扫描是静态源码/调用链核对，不代表构建或测试执行通过。

### 已完成的功能回收

- **模块1 专家系统 / 工作流**：专家团队与工作流调用新增项目/任务范围业务幂等键，复用 AgentTaskCoordinator 的持久状态、重试退避与检查点，不引入第二套队列。
- **模块3 聊天系统**：聊天工具调度持久化项目、会话、能力、标题/输入摘要和请求幂等信息；插件调用传递稳定请求 ID，防止同一请求重复执行。记忆召回 query 现在合并最近用户轮次与当前 prompt。
- **模块4 数据与备份**：Room 任务状态增加项目/会话/能力/结果摘要/幂等键/尝试次数/最大重试/可执行时间/生命周期时间戳；唯一索引和事务写入防止幂等键冲突覆盖其他任务。JSON 备份/恢复包含任务状态与 Trace。
- **模块7 我的记忆**：接入 BM25/CJK n-gram 召回；自动提取使用最近用户消息和当前 prompt，向模型提供当前项目已有记忆，只有明确更正才可返回既有记忆 ID 作为 `supersedes_id`；解析层校验该 ID 属于输入的现有记忆，保存层再做项目/分类/指纹冲突检查。手动项目记忆入口与手动/自动来源标签已接入。记忆召回和自动提取均受项目 `memory` 能力授权控制；跨项目召回仍要求单条记忆共享与来源项目 READ 双重授权。
- **模块13 MCP**：增加项目级工具授权开关和审计；项目授权不能越过 MCP 工具中心的全局启用状态。全局禁用时项目开关不可用，执行时再次校验全局授权与项目授权。
- **模块16 统一能力中心**：持久能力注册表、项目级授权/撤销、MCP 项目授权、能力调用审计与工具审计已接入现有 `AgentToolCatalog/AgentRuntime/AgentTaskCoordinator`。现有 `CapabilityDirectoryPage` 已承载项目授权开关和审计入口。任务元数据、幂等键、重试退避与备份恢复语义已进入现有 Room 任务内核；旧 `CapabilityTaskEngine` 没有合并。

### 当前确认的剩余缺口 / 风险

1. **生成式插件仍未形成共享任务内核的完整恢复闭环**：插件执行会写入 Room 任务状态、幂等键和结果摘要，并对“全部已尝试步骤均为 READ_ONLY”的失败才允许整条工作流重试；一旦非只读步骤开始则禁止整条重放，避免不确定外部写入重复。但 `GeneratedPluginWorkflowExecutor` 的步骤结果仍保存在本次执行的内存集合中，没有逐步持久化 checkpoint，也没有通过 `AgentTaskCoordinator.runInline/checkpoint/resume` 重新注册处理器的进程重启恢复链路。应用中断后只能留下可识别的暂停/失败状态，不能声称可从插件中断步骤自动续跑。
2. **能力中心任务总览仍不完整**：当前目录页已能管理已登记工具的项目授权并查看授权/调用审计，但没有旧 PR #1 任务队列的完整统一视图（跨模块任务列表、最近 Trace、暂停/继续/取消入口）。不能把能力授权页称为完整任务中心。
3. **通用重试需要操作安全约束**：AgentTaskCoordinator 对可重试网络异常统一执行退避重试，但当前策略没有由任务/步骤声明外部副作用是否可安全重放。对已产生外部写入、设备动作或不确定结果的步骤，单纯按 Socket/timeout 异常重试仍有重复副作用风险。插件路径已有只读整条重放保护，其他任务处理器仍需统一安全重试契约。
4. **能力注册表容量上限（代码修复已提交，待测试）**：原 100 条上限低于“最多 100 个本地插件 + 内置工具 + MCP 工具”的组合规模，已将注册表上限提高到 512，并新增 160 条能力注册/授权回归测试源码。提交：`ff92676094ee7ed673e845b37990ccdfc549652f`、`5df158385709cc3a3af9a7a45b313a56aaa7e57c`。尚未运行 Android instrumentation，不能标记测试通过。
5. **记忆写入边界仍需加强**：`saveCandidates` 已校验 mutation token、项目 ID 格式、来源 ID 格式、项目内目标记忆、分类和指纹冲突；但 DAO 写入前没有独立确认项目仍存在、来源 conversation/message 仍属于该项目。需在后续修复阶段补持久层归属校验，避免只依赖上层失效门闩。
6. **旧记忆更正候选范围（代码修复已提交，待测试）**：自动提取上下文现改为按本轮用户消息使用 BM25/CJK 召回最多 20 条相关旧记忆，再补最近 10 条作为非词面上下文，去重后最多提供 30 条；避免只给最近 30 条而漏掉较早但高度相关的记忆。新增 JVM 回归测试覆盖“旧相关记忆 + 最近上下文”。提交：`04204c76192b00d77f6d2992c96d82bb96ed8a9c`、`e1d7d7786ed05b18940da834f69f3399ac557995`、`a6cf0e23a0c95b0bc2fe8e32a93a8880c4544775`、`f21598ae526a070b27b41bdae9a31b4016251f85`。尚未运行 JVM 测试。
7. **任务重试与插件恢复尚未验收**：本轮新增了回归测试源码，但未运行 Kotlin 编译、JVM 测试、Android instrumentation、模拟器/真机测试；不能把源码存在写成测试通过。

### 十九模块本轮扫描结论

- 模块 1、3、4、7、13、16：本轮有源码变更，已对新增实现和直接调用链做静态复核；以上列出的插件恢复、重试安全、能力任务视图和记忆持久层归属检查是当前真实待办。
- 模块 2、5、6、8、9、10、11、12、14、15、17、18、19：相对本轮对照检查点没有源码差异，保留前文各专项审计结论。主要跨模块缺口仍包括模块17媒体任务与模块18日历触发未统一接入 AgentTaskCoordinator、聊天原生多模态 parts/通用视频问答/非语音音频理解缺失、Phone Agent 通用兼容与导航授权流程未闭环、专家团双向协作与可编辑任务图未完成。
- 本轮未执行全量 Kotlin 编译、JVM/Android 测试、模拟器、真机或真实服务商联调；这些统一验证仍留在功能修复和第二轮扫描之后。



## 2026-11-22 第一阶段历史分支整合记录（进行中）

整合目标固定为 `integration/19-modules`。本轮对仓库当前可见的 29 个其他分支逐一核对分支关系，并对分叉分支比较 Git tree 路径；分支 ahead/behind 数量只用于定位差异，不作为代码优劣判断。

### 已确认已包含或无需直接迁入的分支

- `feature/modelscope-oauth`、`feature/module-5-project-space`、`feature/module-7-memory`、`feature/module-10-artifacts`、`feature/module-11-expert-templates`、`feature/module-12-model-comparison`、`feature/module-17-smart-editing`、`feature/module-18-calendar`、`feature/phase2-optimization`、`feature/voice-system`、`refactor/phase-2-architecture` 及四个 `staging/phase2-*` 分支的分支提交已被当前整合分支包含；不重复合并。
- `feature/module-6-knowledge-base`、`feature/module-8-phone-agent`、`feature/module-9-voice-system`、`feature/module-13-mcp-tool-center`、`feature/module-14-github-workbench`、`feature/module-15-ai-coworkbench`、`fix/module-13-audit-reliability`、`wip/module-6-finalize`、`wip/module-6-knowledge-base`、`feature/phase2-optimization` 和 staging 分支的文件路径均已在当前整合树中找到。对照现有审计记录，当前整合版本包含后续安全/兼容修正；不以旧分支文件覆盖当前版本。
- `fix/module-13-audit-reliability` 的 MCP 核心文件和测试与整合分支一致；无需重复迁入。
- `test/github-write-access-20261122` 的全部文件路径均已在整合分支中存在；该分支没有额外文件需要迁入。

### 历史分叉分支的选择性回收

- `feature/knowledge-retrieval-ranking`：不整分支合并。旧 `CapabilityTaskEngine` 与当前 `AgentTaskCoordinator` 使用不同任务表和状态所有者，直接迁入会形成第二套任务架构，因此旧引擎及其测试不迁入。旧 `CapabilityCenterPage` 的能力目录/项目授权部分已由当前 `CapabilityDirectoryPage` 承接；其任务列表面板绑定旧 `CapabilityTaskEntity`，没有直接复制或接线，也不宣称统一任务总览 UI 已整合。该 UI 只能在后续界面阶段基于现有 `AgentTaskStateEntity` / `AgentTaskCoordinator` 适配，当前不启动 UI 改造。旧 `CapabilityRegistryStoreTest` 依赖当前已改为私有的旧 codec API，不直接复制；其适用断言已按当前公开 API 改写并追加到整合分支原有的 Android instrumentation 文件 `app/src/androidTest/java/app/nestro/data/CapabilityRegistryStoreTest.kt`。最终保留原有 8 项测试，并新增 4 项描述符/安全边界测试；提交 `48240bb24ede064db9d295c1e770bce7be60fc7f` 已回读确认旧用例未丢失。其数据库终态保护是有效且曾遗漏的实现，已选择性恢复，见提交 `31fedead85e036a870c4eea6e0fbad862cf908d8`。
- `feature/module-7-skill-library`：旧 `SkillCommunityInstallPlanner`、`SkillInstallPolicy` 和相关测试不整套迁入。当前版本使用更新的 SemVer 2.0、依赖约束回溯求解、签名信任根/撤销与安装计划 API；旧测试面向不同 API。当前 `SkillSemVerTest`、`SkillDependencyRuntimePolicyTest`、`SkillCommunityClientTest` 已覆盖对应核心规则。
- `wip/module-5-project-space`：唯一缺失路径是详细设计文档 `docs/MODULE_5_PROJECT_SPACE_DESIGN.md`。已将其保留到整合分支，并明确标注为 2026-11-19 历史设计归档，当前实现以功能审计/代码地图/路线图为准；提交 `e84a2fcade2a0a7cb4efc33e38dbeeaf14a9de22`。
- `main`：独有 `UnifiedAgentDispatcher`、`ChatNetworkPolicy`、`TtsCapability` 及其旧测试不恢复。前者是较弱的关键词路由，后两者分别与现有请求策略和 Android TTS 控制器重复；具体判断见本审计“旧 main 分支独有辅助类的去留判断”。

### 本轮实际变更

- `31fedead85e036a870c4eea6e0fbad862cf908d8`：恢复 Phone Agent 终态任务 SQL 保护，并回收数据库回归测试。
- `e84a2fcade2a0a7cb4efc33e38dbeeaf14a9de22`：保留模块5历史设计文档并标注历史状态。
- `cf64ff7f83c6548db8163b9770c746835c3629e3`：同步本审计记录。
- `48240bb24ede064db9d295c1e770bce7be60fc7f`：保留既有 CapabilityRegistryStore instrumentation 用例，并追加适配后的历史安全断言。

### 当前边界

- 未删除任何源码、资源、依赖或历史分支。
- 旧 PR #1 的任务总览 UI 尚未适配到当前统一任务模型；旧 UI/任务引擎属于未合并 Draft 实现，不直接并入以免引入重复架构，后续界面阶段再决定是否基于现有任务模型复用其交互。
- 未执行历史遗留/死代码扫描；该工作严格留待用户确认第一阶段整合完成后再开始。
- 未运行 Gradle 编译、JVM/Android 测试、模拟器或真机验证；按既定顺序留待后续统一验收。
- 当前记录是历史分支整合进度，不代表第一阶段已经完成；仍需继续核对分叉分支中共享路径的独有语义，确认没有遗漏有效实现。


## 2026-11-22 历史分支共享路径语义复核补记（续）

本轮以最新整合分支 HEAD \`e0226cc7d58e41082eb5ec49dbf88dba744b7c02\` 为基准，针对仍有分叉提交的模块分支，进一步比较共享路径内容，而非仅依据文件路径判断。

### 逐项复核结果

- **模块6 知识库**：\`KnowledgeChunker.kt\`、\`KnowledgePdfOcr.kt\`、\`KnowledgeRepository.kt\`、\`KnowledgeSearchEngine.kt\`、\`KnowledgeStore.kt\`、\`KnowledgeTextDecoder.kt\`、\`KnowledgeVectorSearch.kt\`、\`KnowledgePage.kt\` 与模块6历史分支对应实现逐行内容一致。\`KnowledgeEmbeddingModelManager.kt\` 当前版本增加 attention-mask-aware mean pooling，对有效 token 向量按 attention mask 求均值，而非旧分支只取 CLS 向量；保留当前较新实现。PDF OCR、文本解码、向量搜索、嵌入模型管理及对应测试均已在当前树中。历史模块 CI 不覆盖当前统一 Android workflow。\n- **模块7 技能库**：旧 \`SkillCommunityInstallPlanner\`、\`SkillInstallPolicy\` 和旧依赖报告 API不整套迁入；当前使用回溯求解器、安装计划与依赖闭包检查。当前 \`SkillBundleParser\` / \`SkillCommunityClient\` 比旧分支增加严格 SemVer 2.0、签名者指纹、社区 HTTPS URL 结构约束、存储元数据校验及更完整的签名/信任管理。旧 \`CapabilityDirectoryPage\` / \`NestroApp\` 属早期页面和路由，当前页面已承接统一能力注册表、MCP、审计及其他模块接线，不以旧 UI 覆盖。\n- **模块8 Phone Agent**：分支差异集中在 instrumentation 脚本、Android workflow 和 Gradle 配置，没有新增 Phone Agent 主源码路径；当前主源码及专项策略/测试已在整合树中。旧 CI 配置不覆盖当前工作流。\n- **模块9 语音**：语音 Provider、Realtime、录音控制器、TTS 控制器及语音设置页共享源码逐行一致；不需重复迁入。\n- **模块13 MCP**：历史 MCP 客户端、工具中心、页面及调用入口已在整合树中；此前专项对照确认核心文件与审计可靠性修复分支一致。当前版本还包含项目授权与全局授权双重校验，不用历史页面覆盖。\n- **模块14 GitHub Workbench**：页面源码逐行一致；当前 \`GitWorkbench.kt\` 比历史分支多出凭据保存/删除失败后的旧凭据恢复校验，以及本地仓库索引同步锁等保护；保留当前版本。\n- **模块15 AI Coworkbench**：当前页面、服务、数据流分析器、报告解析器和静态分析器与历史分支逐行一致；不重复迁入。SBOM 脚本/测试和 workflow 变更以当前仓库文件及统一 CI 配置为准，不覆盖当前 workflow。\n- **main**：独有旧关键词路由、网络策略和 TTS 能力类已判定被当前架构替代，不整分支合并。\n\n以上是 GitHub 远程文件内容/树差异的静态比较，不代表 Gradle 编译、JVM 测试、Android instrumentation 或设备运行通过。没有删除历史分支，也没有开展全项目死代码扫描。仍需完成所有可见分支最终清单核对，并回读审计与分支 HEAD 后，才能标记第一阶段整合结束。\n

## 2026-11-22 整合分支十九模块二次审计（HEAD 6b331842）

审计基线：`integration/19-modules`，HEAD `6b33184220f1dbaf2174bee3218e530d38414eca`；递归 Git tree `truncated=false`，361 个文件。  
本轮重新读取 `NESTRO_19_MODULES.md`、`IMPLEMENTATION_ROADMAP.md`、`CURRENT_CODE_MAP.md` 与本审计文件，并按当前分支核对模块源码、主要调用入口和测试源码。此记录为源码静态审计，不代表编译、自动化测试、模拟器或真机通过。

### 十九模块二次核对摘要

| 模块 | 当前判断 | 关键缺口 / 验收边界 |
|---|---|---|
| 1 专家系统 | 自动组队、手动组队、DAG 工作包、并行执行和检查点恢复有源码 | 双向自由交流/私聊、可编辑任务图、用户可控暂停/恢复/返工、完整历史分页和结构化验收协议仍不完整 |
| 2 模型管理 | Provider/模型/API Key 分离、加密凭据和多协议配置有源码 | `ModelDefinition` 未提供模型真实 context-window 元数据；魔搭 OAuth 仍待部署、注册应用和真实授权联调；动态余额/额度同步未实现 |
| 3 聊天与主模型调度 | 流式聊天、原生工具调用、上下文组装、恢复和媒体任务工具入口有源码 | `ChatMessage` 仍只有字符串 `content`，没有原生多模态消息 parts；上下文预算仍使用固定估算；同一轮多个 tool calls 在 `OpenAiClient.nativeToolLoop` 中逐个 await，不支持无依赖工具并发 |
| 4 数据与备份 | Room 快照、版本化 JSON 备份、项目包和中断恢复有源码 | 媒体工程 ZIP 与通用 JSON 备份分离；媒体原文件不在通用项目包中，需明确恢复边界并做完整往返验证 |
| 5 项目空间 | 项目 CRUD、READ/WRITE 授权、资源范围和审计有源码 | 跨项目授权撤销对知识/记忆/专家/技能/工作流/媒体资源的即时影响，以及复制/删除全资源生命周期仍需联测 |
| 6 知识库 | 文档导入、PDF OCR、BM25/FTS、BGE ONNX 向量检索和索引重建有源码 | 真实模型下载、设备内存/耗电/长文档性能及 Room 迁移尚未执行验证 |
| 7 我的记忆 | 自动提取版本保护、项目记忆、来源关联、编辑删除、跨项目显式共享有源码 | 未发现独立的全局/项目/会话记忆上下文缓存；embeddingCache 只是向量缓存；会话召回 query 由最近历史构造，未包含当前 prompt |
| 8 Phone Agent | WorkManager 任务、虚拟屏执行、页面节点桥接、下载后导入项目素材有源码 | 当前浮动预览固定在右下角，非角部拖动被解释为屏幕 swipe，未实现预览窗口拖动；主屏导航/授权交接和地图路线规划未形成已确认闭环；具体虚拟屏 UI 手势仍需按用户要求调整 |
| 9 语音系统 | 本机识别/TTS、远程 STT/TTS、Realtime WebSocket 有源码 | 真实设备音频路由、回声、耳机/扬声器切换、厂商兼容和网络恢复待设备验收 |
| 10 我的作品 | ArtifactStore 的项目范围、文件校验、版本和备份路径有源码 | 文件字节丢失、索引不一致、项目删除/恢复及跨版本完整恢复仍需集成验证 |
| 11 专家模板 | 内置/自定义模板、编辑、复制、搜索、导入导出有源码和测试源码 | Kotlin 编译、JVM/UI 测试尚未执行 |
| 12 双模型对比 | 双模型并行、独立失败/取消和响应布局有源码和策略测试源码 | 编译、JVM、并发取消和设备验收尚未执行 |
| 13 MCP 工具中心 | 远程 HTTPS Streamable HTTP、发现、逐工具授权、参数校验、审计和撤销有源码 | 持久工具授权与任务级一次/当前任务授权语义仍未统一；测试源码未执行；stdio/OAuth 交互式授权不属于当前范围 |
| 14 GitHub 工作台 | GitHub/GitLab/Gitee 账号凭据、本地仓库操作、Diff/回滚等有源码 | 完整远程操作、异常恢复、凭据轮换和文件系统故障测试未执行 |
| 15 AI 共建工作台 | 补丁审查、逐文件批准、快照/回滚、静态分析和 Actions 结果解析有源码 | 真实 GitHub Actions、SBOM/JUnit 产物链和设备验证尚未执行 |
| 16 统一能力中心 | AgentRuntime/AgentTaskCoordinator、工具租约、凭据租约、声明式插件工厂有源码 | 生成式插件执行现已通过 `AgentTaskCoordinator.runInline` 并逐步写 checkpoint（修正旧审计中“完全绕开统一任务基座”的过期判断）；但其自定义权限路由对 `EXTERNAL_WRITE` 直接 Allow，未检查 `requiresConfirmation`，可绕过工具确认；统一能力总览/项目授权/任务审计 UI、任意代码沙箱仍缺失 |
| 17 智能剪辑 | 项目素材、单主视频轨/单音轨时间线、撤销重做、Media3 预览/导出、字幕和后台渲染有源码 | 多视频轨/PIP、转场、速度曲线、关键帧、字幕烧录和高级混音未实现；媒体渲染任务仍由独立文件状态与 WorkManager 管理；长视频性能待验证 |
| 18 日历调度 | 日程 CRUD、重复规则、系统日历互操作和 WorkManager 提醒通知有源码 | 当前 Worker 到期只发通知并重排提醒；没有 Agent/Workflow/Phone Agent 分发、任务状态回写、occurrence 幂等 ID、重试/暂停/恢复/取消和 nextRunAt/lastRunAt 等调度记录；尚不是统一自动时间调度引擎 |
| 19 多模态与社区生态 | 图片理解、音频/视频语音转写、视频场景检测、音频处理、异步视频生成、社区依赖求解与签名撤销有源码 | 没有通用视频理解接口/整段视频问答，也没有图像生成 action；媒体任务与统一 AgentTaskCoordinator 状态尚未统一；真实服务商凭据、专属端点和正式社区信任根待配置/联调 |

### 跨模块集成问题（优先级高）

1. **生成式插件权限绕过：** `NestroApplication` 为插件步骤创建的自定义 `AgentPermissionRouter` 对 `READ_ONLY` 和 `EXTERNAL_WRITE` 直接返回 Allow，没有尊重工具的 `requiresConfirmation`。例如 `media.video.generate` 声明为 `EXTERNAL_WRITE + requiresConfirmation=true`，生成插件路径仍可能绕过确认。必须先修复并增加“外部写入不能绕过确认、批准后续跑、拒绝终止”的测试。
2. **工作流能力边界：** `WorkflowStepKind` 当前只有 PROMPT/EXPERT/SKILL；`NestroApp` 的工作流模型调用走 `completeWithFallback`，没有传入 `invokeModelWithTools`，因此工作流步骤不能直接调 Phone Agent、媒体或 MCP 工具。当前审批弹窗已有“授权一次 / 当前任务全部授权 / 拒绝”且不可随意关闭，但 `WorkflowExecutionCoordinator` 未把审批等待显式写入统一任务的 WAITING_FOR_USER 状态，也未见对应持久审计闭环。
3. **统一任务状态未覆盖全部领域任务：** Phone Agent 已与统一协调器协作；生成式插件已使用 `runInline` 和 checkpoint。媒体 AI、媒体渲染仍各自使用文件任务状态 + WorkManager；日历目前仅 WorkManager 通知。需复用统一任务基座并保持领域数据，不新建第二套队列。
4. **工具并发语义不足：** `OpenAiClient.nativeToolLoop` 对同轮 tool calls 使用顺序循环。应只并发无数据依赖且声明并行安全的调用；共享项目/文件/设备的写操作必须串行或显式依赖，并按 callId 稳定回注结果。
5. **多模态入口不等于原生多模态聊天：** 聊天附件已导入项目素材库并可通过 `media.ai.run/status` 调用专业能力，但聊天消息传输仍是文本结构；图片理解/转写/场景分析与通用视频理解、图像生成必须分别验收。
6. **MCP 授权范围：** MCP 服务/工具持久授权被运行时直接视为 Allow，任务级一次授权/当前任务授权尚未统一，需明确长期信任和单次任务审批的层级。

### 测试与执行边界

- 当前已找到大量 JVM/Android 测试源码，包括模块1–19的策略、持久化、页面、媒体、Phone Agent、工作流和插件相关测试；源码存在不代表测试已执行。
- 本轮没有运行 Kotlin 编译、JVM 测试、Android instrumentation、模拟器、真机或真实 API 联调；本轮没有修改生产代码。
- 下一步按依赖关系先处理安全/状态/调度级阻塞，再处理功能缺口；所有修复先查现有实现和调用链，采用最小补丁，十九模块代码全部完成后再统一执行构建与测试。


## 2026-11-22 插件审批与 Phone Agent 统一任务机收敛记录（当前 HEAD `816e60660757549770d30c817450235b48696132`）

### 插件权限审批修复

- 新增 `GeneratedPluginAuthorizationPolicy`：只有 `READ_ONLY` 且未声明 `requiresConfirmation` 的工具可以直接 Allow；`EXTERNAL_WRITE` 或显式要求确认的工具统一返回 `RequireConfirmation`；其他高风险能力拒绝。
- 生成式插件的 MCP 步骤仍先经过全局服务授权和当前项目授权，授权通过后再执行上述确认策略。
- 插件内 `DefaultAgentRuntime` 已注入当前 `AgentTaskExecutionContext`；插件执行结果通过上下文写入 WAITING_FOR_USER / FAILED / COMPLETED，不再由插件回调直接改任务状态。
- 已增加策略测试，覆盖只读放行、外部写入要求确认、显式确认和设备高风险能力拒绝。
- **尚未闭环：** 当前修复保证外部写入不会静默 Allow，并持久进入 WAITING_FOR_USER；生成插件审批 UI、批准后恢复执行和拒绝后的完整用户交互仍需与统一审批机制打通。不能把“安全阻断”写成“审批体验已完成”。

### Phone Agent 统一任务机收敛

之前“Phone Agent 已与统一协调器协作”的结论只覆盖 Worker 触发执行的部分，不能代表生命周期已完全统一。本轮进一步完成：

1. `PhoneAgentTaskService.createAndScheduleIfAuthorized` 在调度前先向 `AgentTaskCoordinator` 注册正式任务状态；旧安装中尚无统一状态的任务会在 Worker 入口按既有任务记录迁移注册。
2. 新增外部调度任务的注册、排队准备和派发接口。Phone Agent 任务先进入统一状态表，再由 WorkManager 到期唤醒并调用统一协调器派发。
3. `PhoneAgentWorker` 保留为 Android WorkManager 适配器，负责前台服务、进程唤醒、通知、系统级重试和持久唤醒；它不再直接写统一任务状态。统一队列、状态转换和执行生命周期由 `AgentTaskCoordinator` 管理。
4. `PhoneAgentAgent`、`PhoneAgentExecutor`、Phone Agent 专用 `DefaultAgentRuntime` 与应用 Handler 改为通过 `AgentTaskExecutionContext` 保存检查点、暂停、等待用户、失败、取消和完成状态；删除了这些执行层直接写 `RoomAgentTaskStateStore` 的重复状态转换。
5. `PhoneAgentTaskService.loadTasks` 从统一任务状态读取当前步骤；领域任务表仍保留计划、动作日志和状态字段。该领域状态目前仍承担“动作执行中断后必须人工核对，避免重复点击/重复提交”的安全标记，暂不删除；它不是第二个队列，但与统一状态的投影/回写关系仍需继续收敛。
6. Worker 的旧任务迁移、暂停/取消/异常和 RETRY_WAIT 派发路径均改由统一协调器处理；Phone Agent 页面调用服务层入队，不再直接调度 Worker。

### 本轮代码与测试变化

- 生产代码：插件权限策略、统一任务上下文、Phone Agent 服务/Worker/Agent/Executor/应用接线。
- 测试源码：新增外部调度任务“先登记、后派发”测试；新增执行上下文持久化用户接管状态测试；新增生成式插件权限策略测试。
- WorkManager 与统一协调器职责不同：WorkManager 是 Android 持久唤醒/后台执行适配器，统一协调器是应用任务队列和生命周期权威；保留前者不是保留第二套业务任务引擎。
- 本轮未执行 Gradle 编译、JVM 测试、Android instrumentation、模拟器或真机验收。测试源码已补，执行仍按十九模块全部补齐后统一验收的既定顺序进行。
- 当前尚未完成的审批事项：生成式插件批准/拒绝 UI 恢复闭环、MCP 长期授权与任务级确认语义、工作流 WAITING_FOR_USER 持久恢复。


### 开源参考与复用取舍

本轮按“优先复用成熟实现，不另造第二套任务机”检查了以下方案：

- [Android Now in Android](https://github.com/android/nowinandroid)：约 22.1k Stars，Apache-2.0，2026-11-22 有更新。其 `WorkManagerSyncManager` 使用唯一 Work、由 Worker 执行、通过 WorkInfo Flow 暴露状态。借鉴职责分离与唯一调度，不移植其同步业务。
- [Google WorkManager Kotlin Compose 示例](https://github.com/google-developer-training/basic-android-kotlin-compose-training-workmanager)：49 Stars，Apache-2.0，2026-11-20 有推送更新。其 Repository 负责提交唯一 Work 链，Worker 负责具体后台步骤；作为 WorkManager 接线参考，不替代 NESTRO 的任务协调器。
- [Android Background Work Guide](https://github.com/prakashsrv/Android_Background_Work_Guide)：0 Stars，未声明 SPDX 许可证；不作为生产代码来源。

官方 [Android WorkManager 文档](https://developer.android.com/develop/background-work/background-tasks/persistent)明确建议把 WorkManager 用于需要跨应用进程重启和设备重启持久执行的后台任务；长任务可由 CoroutineWorker 配合前台通知运行。

**选型结论：** 不搬入第三方自定义任务队列。NESTRO 已有 `AgentTaskCoordinator`，负责业务任务状态、权限、检查点、恢复和审计；Jetpack WorkManager 负责 Android 系统级持久唤醒与后台运行。保留现有 Worker/WorkScheduler 作为薄适配层，移除它们对业务生命周期的独立写入。这比复制另一套队列更少重复，也更符合 NESTRO 当前架构。

## 2026-11-22 工作流审批统一任务状态接线修复

- `WorkflowExecutionCoordinator.execute()` 在调用现有审批回调前，先通过 `AgentTaskExecutionContext.waitForUser()` 将统一任务持久状态切换为 `WAITING_FOR_USER`；用户批准后切回 `RUNNING`，拒绝则将任务标记为 `FAILED` 并由工作流引擎终止当前步骤。
- 复用既有 `AgentTaskCoordinator`、`AgentTaskExecutionContext` 和 `CompletableDeferred` 审批回调，不新增审批数据库或第二套任务状态机。
- 新增回归测试源码：审批等待期间状态为 `WAITING_FOR_USER`、批准后恢复并完成；拒绝后任务进入 `FAILED`。
- 当前只完成代码修改与 GitHub 回读；Gradle 编译、JVM 测试、Android instrumentation、模拟器和真机验收均未执行，不标记为通过。


## 2026-11-22 统一审批与跨模块调度第二阶段核对（HEAD e52aa778）

本节基于当前分支递归 Git tree（361 个文件，truncated=false）及对应源码回读，修正前述审计中已过期的判断，并记录下一阶段真实缺口。仍为静态源码核对，不代表编译或运行验收。

### 已存在、不得重复开发的能力

- `WorkflowExecutionCoordinator` 已通过 `AgentTaskCoordinator.runInline` 管理工作流任务，并在执行审批回调前写入 `WAITING_FOR_USER`；批准后恢复为 `RUNNING`，拒绝则写入 `FAILED`。此前“工作流审批未显式写入统一任务状态”的旧结论已过期。当前仍需补齐审批请求的持久化展示/进程重建后的 UI 恢复、审批审计和统一授权范围。
- 工作流执行已有持久检查点、已完成步骤恢复、工具租约接口和并行步骤组；但当前定义仍只有 `PROMPT/EXPERT/SKILL`。NestroApp 实际调用未提供 `invokeModelWithTools`，因此工作流步骤尚不能直接编排工具、MCP 或 Phone Agent。
- 生成式插件已使用统一任务机 `runInline` 和步骤 checkpoint；`GeneratedPluginAuthorizationPolicy` 已阻止外部写入被直接 Allow。当前待补的是批准后从安全检查点继续、拒绝/取消的完整用户交互，以及与 MCP/聊天共用的审批服务。
- Phone Agent 已通过统一任务机与 WorkManager 适配层协作；保留 PhoneAgentTaskStore 的动作中断标记属于执行安全保护，不应误删为“重复队列”。

### 当前确认的跨模块缺口

1. **审批闭环仍分散。** 工作流审批由 UI 的 `CompletableDeferred` 交互；生成式插件在工具权限层返回 `RequireConfirmation`；MCP 工具中心自身有逐工具持久授权，但其直接 `invoke` 路径使用临时 `InMemoryAgentTaskStateStore` 和独立 Runtime。尚无统一审批记录模型将“能力长期信任、当前任务授权一次、当前任务剩余步骤授权、拒绝、撤销、恢复”统一绑定到同一 taskId/projectId/toolId。
2. **媒体 AI 任务未接入统一任务机。** `MediaAiTaskStore` 使用项目目录下 JSON 文件维护 QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED；`MediaAiTaskWorker` 直接更新领域状态；`MediaAiTaskScheduler` 通过 WorkManager 唤醒。该领域记录承载素材 ID、服务商、动作、结果和消费状态，应保留，但业务生命周期需要投影/接入 `AgentTaskCoordinator`。
3. **媒体渲染任务未接入统一任务机。** `MediaRenderTaskStore` 维护独立 JSON 状态，`MediaRenderWorker` 直接推进状态与进度，`MediaRenderScheduler` 负责 WorkManager 唤醒、暂停、恢复、取消。需保留渲染领域元数据与文件清理/中断恢复逻辑，将统一任务机作为业务生命周期权威。
4. **日历仍是提醒，不是任务调度。** `CalendarReminderWorker` 到期只发通知并重新安排下一次提醒；`CalendarEvent` 当前没有自动执行目标、任务输入、occurrence 幂等键或执行策略字段，也没有分发给 Workflow/Agent/Phone Agent 的入口。不能只给提醒 Worker 加一个调用就宣称完成，需先定义用户可配置的自动执行动作与权限边界，再由到期 occurrence 创建统一任务。
5. **原生工具调用仍顺序执行。** `OpenAiClient.nativeToolLoop` 解析同轮多个 tool calls 后，仍以顺序循环逐个 await `onToolCall`。并发必须以显式 parallel-safe 能力声明为前提；只读且无数据依赖的调用可并行，设备操作、外部写入、同一资源写入默认串行，并按原 callId 顺序回注结果。
6. **跨模块协作缺统一交接协议。** 当前已有 Phone Agent 下载素材后导入项目素材库、专家团队与媒体素材工具等局部能力；但“专家/工作流提出素材需求→调度任务机安排 Phone Agent→素材入库→回传 artifact/asset 引用→原创作任务恢复”的父子任务关系、等待依赖、失败返工和最终验收尚未形成通用闭环。

### 下一阶段实施顺序

- A. 统一审批域：先定义持久审批请求、授权范围与决策记录，复用现有 AgentTaskState、CapabilityRegistryStore、MCP 工具授权及工作流审批 UI；禁止另建一套任务队列。
- B. 媒体 AI / 渲染接入：沿用现有领域 JSON 记录和 WorkManager Worker，统一任务机负责业务状态、检查点、恢复、取消和审计；Worker 仅做系统唤醒/后台执行适配。
- C. 日历自动调度：扩展事件的自动执行配置与 occurrence 记录，按 occurrence 创建幂等统一任务，接入 Workflow/Agent/Phone Agent，并保留独立提醒功能。
- D. 工具并发与跨模块依赖：先给工具声明并发安全级别与资源冲突键，再并发执行安全调用；建立父子任务/依赖/产物引用协议，支持创作与执行交替。
- E. 完成其余模块缺口后再统一构建、JVM 测试、Android instrumentation、模拟器/真机验收。

### 验证边界

本轮已回读当前分支源码和递归树；未修改生产代码，未运行 Gradle、JVM 测试、Android instrumentation、模拟器或真机。下一轮生产代码修改必须逐项回读依赖与调用点，完成后核对 diff、提交并回读 GitHub。

## 2026-11-22 MCP 长期授权与任务审批边界修正

- `NestroApplication` 的主模型 MCP 权限路由此前在“服务全局授权 + 当前项目授权”通过后直接返回 `Allow`，绕过工具定义中的 `requiresConfirmation=true`。
- 已改为：全局授权与项目授权只决定 MCP 能力是否可用；通过后仍交由既有 `DefaultAgentPermissionRouter` 判断任务级确认。声明为外部写入/需要确认的 MCP 工具不再因长期授权而静默放行。
- 这是安全边界修正，不代表审批交互已完成。当前聊天工具调度器能识别 `requiresUser` 并停止执行，但通用“授权一次 / 当前任务授权 / 拒绝”弹窗与批准后恢复仍需统一接入；在此之前，需确认的 MCP 外部写入会被安全阻断，不会自动执行。
- 本次修改已提交并回读；未运行构建或测试。


## 2026-11-22 统一审批、媒体任务、日历与工具并发接线更新

本节修正上文中部分已过期的“媒体/日历尚未接入”与“MCP 直接调用仍使用临时任务状态”等判断。当前代码阶段 HEAD：`f424847bdc263501654ec1e4c328b860c47497f8`。

### 已落地的代码接线

- **工作流审批 UI：** 工作流审批等待写入统一任务状态；批准恢复 `RUNNING`，拒绝写入 `FAILED`。弹窗关闭或点“稍后处理”只隐藏界面，不等于拒绝；待审批横幅可重新打开同一请求。用户停止工作流仍是显式停止。
- **生成式插件与 MCP：** 生成式插件通过 `AgentTaskCoordinator.runInline`、步骤 checkpoint 和 `AgentTaskExecutionContext` 管理生命周期；MCP 全局服务授权与项目授权只决定能力是否可用，任务级写入仍走统一审批。MCP 管理页直接调用也改为 `runInline`，任务状态由统一任务机持有，并识别 `AUTHORIZE_ONCE/AUTHORIZE_TASK/REJECT/null` 决策，释放任务级授权 scope。
- **媒体 AI / 媒体渲染：** 两类 Scheduler 在 WorkManager 入队前登记 inline 任务，Worker 通过 `AgentTaskCoordinator.runInline` 执行；WorkManager 只负责可靠唤醒。原有 JSON 记录保留素材 ID、参数、进度、结果等领域数据。系统停止与显式取消分开处理。修正 inline 预登记状态为 `CREATED`，由 `runInline` 真正转入队列，避免已登记 `QUEUED` 被误判为无需派发。
- **日历自动调度：** CalendarEvent 数据库升级到 v2，增加可选自动执行目标和项目范围；编辑入口可填写执行目标，留空仍只提醒。WorkManager 在 occurrence 到期时创建确定性任务 ID，并以 `eventId + occurrenceStart` 幂等键交给 `AgentTaskCoordinator`；`calendar_automation` handler 通过 `ChatRequestExecutionCoordinator` 调用主模型和现有工具路由，支持 Phone Agent 工具路径；子工具审批状态同步到父日历任务。系统日历导入保留自动执行配置，备份/恢复包含新增字段。
- **工具并发：** 新增 `AgentToolSpec.parallelSafe`，默认关闭，并强制只有只读且无需确认的工具才能声明安全。原生 tool loop 只并发连续的显式安全调用，使用结构化协程并发，按原调用顺序回注结果；其他工具仍串行。当前仅知识库检索和项目素材列表声明安全；MCP、写入、设备操作、需审批工具默认不并发。共享结果缓冲和调用计数已做并发保护。
- **父子审批状态：** `ChatRequestExecutionRequest` 支持父任务 ID；日历自动任务调用主模型工具时，子工具等待审批/获批/拒绝会同步更新父任务状态。

### 仍未完成，不能标记为验收通过

- **审批恢复：** 统一任务状态可持久化为 `WAITING_FOR_USER`，当前进程内关闭弹窗后可重新打开；但进程被杀后，审批请求 UI 的完整重建、插件/MCP/工作流执行闭包的安全续跑，以及批准后从原步骤恢复，尚未形成完整持久审批记录与恢复协议。
- **日历完整性：** 已有按 occurrence 的确定性任务 ID 和幂等键，但 `nextRunAt/lastRunAt`、最后执行结果/错误/重试次数尚未作为日历调度记录完整持久化；日历自动执行目前关闭专家团自动组队，模型失败也尚未按错误类型实现完整退避重试。
- **并发与协作：** 已实现受限的只读工具并发；通用资源冲突键、动态依赖图、跨模块父子任务/产物引用和创作—执行交替的统一协作协议仍需继续补齐。
- **验证：** 已新增/调整 inline 预登记、并发安全声明、日历无通知自动触发等测试源码；本轮没有运行 Gradle 编译、JVM 测试、Android instrumentation、模拟器或真机验收。不能据此宣称构建通过。



## 2026-11-22 第二阶段续作：审批恢复、日历 occurrence 与受限并发

本轮代码改动已落入 `integration/19-modules`；代码更新后的 HEAD 为 `2f2bed758100ec2c1430d302ded710d73868020d`，审计记录已随后更新到当前分支。

- **MCP 审批恢复入口：** `McpToolCenterService` 增加按原 taskId 恢复能力，从统一任务状态读取 capabilityId 与参数，再用同一 `runInline` owner 重新注册执行；MCP 页面轮询并显示 WAITING_FOR_USER/PAUSED 任务，恢复时重新走审批。显式拒绝的 FAILED 任务不提供自动重放入口。
- **日历调度记录：** `CalendarDatabase` 从 v2 升至 v3，新增 `nextRunAt`、`lastRunAt`、`lastTaskId`、`lastIdempotencyKey`；Worker 在向统一任务机提交 occurrence 前写入调度记录，再以 `eventId + occurrenceStart` 幂等键 enqueue。系统日历导入和编辑保存会保留这些字段。
- **日历恢复：** 自动执行目标与项目范围仍保存在 CalendarEvent；任务通过 `calendar_automation` handler 进入统一任务机，主模型执行请求带父任务 ID，子工具审批状态同步父任务。日历页展示等待审批/依赖/暂停/失败的任务，并允许显式恢复。
- **媒体 AI / 渲染：** 两类 Worker 已通过 `AgentTaskCoordinator.runInline` 执行，Scheduler 入队前预登记稳定任务 ID，系统唤醒由 WorkManager 承担；领域 JSON 仍保存素材、参数、进度、产物及领域状态，尚需统一状态投影的一致性测试，不能视为领域状态已经删除。
- **工具并发：** 只允许显式标记的只读工具并发；当前安全名单收敛为知识检索和项目素材列表。MCP、写入、设备操作、需审批工具仍串行。
- **新增测试源码：** 新增日历自动触发规则测试，覆盖无提醒自动执行、重复 occurrence 和项目范围校验；现有任务协调器测试已覆盖 WorkManager inline 任务预登记、恢复和跨任务并发。
- **未完成/未验证：** 生成插件跨进程后从原审批步骤自动续跑的统一 UI 入口；MCP/插件/工作流审批请求的持久审计记录与统一决策模型；日历任务完成结果/错误/重试次数回写；媒体领域状态与统一任务状态冲突恢复；通用资源冲突键与跨模块 DAG 交接。当前没有执行 Gradle 编译、JVM 测试、Android instrumentation 或真机验收。


## 2026-11-22 断网后源码回读与审批/媒体/日历/并发续作（HEAD `54c7160876d63864c829c76863271bf93f10f2da`）

本节依据断网后重新读取的 `integration/19-modules` 最新源码记录。此次为代码续作和静态回读，不代表编译或运行验收。

### 已补齐并回读的功能代码

- **工作流审批恢复：** 工作流任务 ID、工作流 ID 和原始输入继续保存在本地偏好中；重新进入工作流页会读取统一任务状态，等待审批/子任务/暂停的同一任务显示“继续未完成工作流”，点击后沿用原 taskId 和原输入，从现有工作流检查点继续；新增取消待恢复任务入口，并在终止后释放任务级审批 scope。工作流页面打开期间每 1.5 秒刷新一次恢复状态。批准仍会重新经过现有审批处理，不把进程内 `CompletableDeferred` 冒充成独立持久审批记录。
- **生成式插件审批恢复：** 插件任务输入现在持久保存原始 requestId 与完整输入 JSON；待恢复查询不再只取最近 10 条审计，而是将最多 200 条待恢复插件任务与最近审计合并。WAITING_FOR_USER、WAITING_FOR_TASK、PAUSED 均可见；恢复时沿用原 requestId/幂等键，回到原插件任务及步骤 checkpoint，并重新经过审批；另有明确终止入口，终止后释放审批 scope。插件在审批异常发生于工具执行前时清除该步的 in-progress 标记，避免恢复时把尚未执行的步骤误判为已执行。
- **MCP 审批恢复与撤销：** MCP 调用参数完整写入统一任务状态，单次输入限制为 32 KB，避免旧逻辑截断 4 KB 后无法恢复；同一 taskId 可从 WAITING_FOR_USER、WAITING_FOR_TASK、PAUSED 重新执行并再次经过任务审批。MCP 页面展示上述等待状态，提供恢复和终止入口；撤销单工具授权、禁用服务或删除服务时，会取消关联的待审批/待恢复任务并释放审批 scope。MCP 持久工具授权仍只代表能力可用，不等同于本次任务的外部写入审批。
- **统一任务恢复查询：** Room 的 recoverable 状态查询补入 CREATED、WAITING_FOR_TASK；增加专门的待恢复生成式插件查询，避免较早的审批任务被最近审计条数挤出恢复界面。
- **媒体 AI：** Worker 执行成功/失败、暂停/取消状态由 AgentTaskCoordinator 决定；媒体 JSON 记录保留素材、Provider、参数、进度、结果和结果消费标记，不再由 Worker/Scheduler 另行推进 RUNNING/COMPLETED/FAILED/PAUSED/CANCELLED 作为第二套生命周期。Scheduler 的 read/list 将统一任务状态投影给界面；恢复只按统一状态判断是否需要唤醒。暂停/恢复/取消改为先操作统一任务机；Provider 结果导入前再次核验任务仍处于 RUNNING，避免暂停或取消后仍导入产物。
- **媒体渲染：** 渲染 Worker 只向领域记录写进度、错误和输出信息；任务状态、暂停、恢复、取消、成功和失败均由 AgentTaskCoordinator 管理。Scheduler 的 read/list 映射统一状态；删除导出记录前也检查统一任务终态。保留损坏任务记录的清理入口；修正清理失败时错误参数名不匹配。
- **日历自动任务：** 提醒 occurrence 与自动任务 occurrence 分开计算。自动任务不再受提醒提前量影响，避免“提前 30 分钟提醒、只提前 10 分钟创建日程”时跳过本次自动执行；保存时的 nextRunAt 和 Worker 的下一次 occurrence 也使用自动任务专用规则。Worker 对确定性 taskId 先查统一任务状态，复用既有任务结果而不重复创建；入队异常按有限次数重试，超过次数写入失败结果。日历事件编辑自动执行目标/项目/时间/重复规则、标记完成或删除时，会取消旧的活动任务。事件记录包含状态、结果、错误、lastTaskId、lastRunAt、nextRunAt 和幂等键；UI 展示实际执行状态。
- **工具并发：** 保留显式 parallelSafe 约束，仅无审批的只读工具可并发；OpenAI 原生工具循环最多并发 4 个安全调用，检查用户停止状态并按原调用顺序回注结果。外部写入、MCP、设备操作和需审批工具仍串行。

### 本轮静态回读发现并修正的问题

1. 自动任务曾复用提醒的 nextOccurrenceStart，导致提醒 lead time 已经过期时跳过实际自动执行；现已拆分规则并更新两个日历规则测试源码。
2. 统一任务恢复 DAO 曾漏掉 WAITING_FOR_TASK，导致等待子任务的任务无法出现在恢复入口；现已补入持久查询。
3. MCP 参数持久化曾截断，现改为完整保存并限制 32 KB。
4. 媒体 Worker 曾把领域 JSON status 当作执行依据并与统一状态双写；现已将生命周期判断收敛到统一任务机，领域 JSON 仅保存媒体领域数据与进度/结果。
5. 媒体渲染清理失败路径存在命名参数不匹配，已修正为 `errorMessage`。

### 仍需继续完成/验证的边界

- 统一审批现已由 AgentApprovalCoordinator 负责，并新增 Room 持久审计实体，记录 PENDING、AUTHORIZED_ONCE、AUTHORIZED_TASK、REJECTED；每次审批尝试使用独立 ID，保留重复审批、重试和进程恢复后的历史记录。审计记录目前已有 DAO 查询接口，但尚未制作独立的审批记录浏览页面；授权本身仍在进程重启后重新确认，不从历史授权记录自动恢复。
- 工作流目前沿用既有步骤模型与检查点恢复；PROMPT/EXPERT/SKILL 以外的通用工具步骤、跨模块 DAG 产物交接仍需继续实现。
- 媒体领域 JSON 仍保留兼容性 status 字段及旧任务回退值；新执行路径以统一任务状态为准，需在最终验收时覆盖旧版本迁移、进程被杀、Worker 重试、暂停/取消竞态和结果消费。
- 日历自动任务仍需在最终验收中验证重复 occurrence、设备重启、Worker 重试、事件编辑/删除与正在执行任务的取消竞态。
- 本轮没有运行 Gradle 构建、JVM 测试、Android instrumentation、模拟器或真机测试。已修改/新增测试源码但尚未执行；严格遵守“十九模块代码全部补齐整合后统一构建测试”的顺序。


## 2026-11-22 第二次静态回读补记（代码 HEAD `45d5e0451d248e2ad933fa7d08bda0033fc73aa7`）

- **统一任务机 WAITING_FOR_TASK 状态修复：** 发现任务处理器正常返回时，队列消费者的成功收尾逻辑会把 `WAITING_FOR_TASK` 覆盖为 `COMPLETED`；`runInline` 的取消收尾也未完整保留该状态，外部调度恢复入口也不接受它。现已在队列正常结束、inline 取消保留、外部调度重新派发和 trace 记录路径统一保留/恢复 `WAITING_FOR_TASK`。新增回归测试覆盖 inline handler 在等待子任务时返回，任务不能被错误完成。
- **审批异常状态：** `DefaultAgentRuntime` 现在区分 `AgentApprovalPendingException` 与普通取消；前者保留 `WAITING_FOR_USER` 和审批原因，普通取消仍进入暂停恢复路径。新增运行时回归测试源码。
- **工作流恢复安全边界：** 恢复同一 taskId 前，校验原输入和保存的工作流定义指纹；定义或输入变化时不续跑旧检查点，界面提示并允许取消旧任务。任务处于 CREATED/QUEUED/RUNNING/RETRY_WAIT 时禁止重复启动；仅对确实处于等待/暂停的同一任务开放续跑。
- **日历事件修改取消：** 事件修改、完成或删除时，按持久任务输入中的 eventId 查找并取消该事件所有仍活动的自动任务，而不只取消最近一次 lastTaskId，避免重复日程发生重叠时遗留旧任务。
- 上述均为源码静态核对和代码/测试源码更新；没有运行 Gradle、JVM、Android instrumentation、模拟器或真机测试。


## 2026-11-22 MCP 外部调用结果不确定性保护（代码 HEAD `d9f478dc316c3ee49e35ef58079cffffb607efc4`）

- MCP 任务在审批前写入 `mcp:v1:approval` 检查点；批准后、进入外部调用前写入 `mcp:v1:executing`；外部调用成功返回后先持久化 `mcp:v1:completed` 与受限结果摘要，再完成统一任务状态。
- 进程在外部调用中途终止、恢复时仍停留在 executing 检查点：任务转为 FAILED 并明确提示外部结果不确定，不自动重放，避免重复外部写入。
- 若成功结果检查点已保存、但进程在统一任务完成状态写入前退出：恢复时从 completed 检查点完成任务并返回已保存结果，不再次调用 MCP 服务。
- 若停在 approval 检查点：恢复时沿用原 taskId 和参数，重新经过审批后再进入 executing。
- 此保护是保守的 at-most-once 策略：在“已写入 executing、但尚未真正发出请求”这一极短窗口被杀时，也会要求用户核对后手动重新发起；优先避免重复外部副作用。
- 仅完成源码静态回读和代码/测试源码修改；未运行构建或测试。


## 2026-11-22 模型驱动 MCP 调用的防重复恢复（代码 HEAD `834e40d1226334bee86b9e0b9be5466c8e9ec0b5`）

- 除 MCP 中心的直接调用外，模型原生工具循环和兼容回退工具循环也接入 MCP 重放账本。
- 每个 MCP 调用在真正进入工具 handler 前持久化 EXECUTING 标记；成功返回后持久化 COMPLETED 和受限输出。账本以工具 ID、规范化参数和本次请求内 MCP 调用序号建立签名，支持同一请求中多个调用。
- 任务恢复时，已完成调用从账本返回缓存结果，不再重复请求 MCP 服务；存在未完成 EXECUTING 记录时，任务转为失败并提示核对外部结果，不自动重放。
- 完整模型工具循环结束后保存最终答复并清理临时账本；若只完成部分 MCP 调用后中断，恢复时会复用已完成调用结果，再继续模型流程。
- 相同 requestId 的任务若已完成，返回持久最终答复；若仍在运行/排队，拒绝重复启动。
- 该保护只覆盖当前模型工具调度器的 MCP 调用路径；外部系统若提供原生幂等键，后续仍应优先传递服务端幂等键。此处没有声称实现分布式 exactly-once。
- 本轮仅做静态源码回读与代码/测试源码更新，未运行任何构建或测试。


## 2026-11-22 MCP 重放账本补充约束（代码 HEAD `bccfedeea058516fff080b88a537beeb74f92396`）

- 模型驱动 MCP 调用的重放账本已覆盖原生工具调用与兼容回退两条执行路径。
- 每条记录增加本次请求内 MCP 调用序号；恢复时除校验工具和参数签名外，还校验同序号调用是否一致。若模型重放生成了不同工具/参数，不会把它当成新调用自动执行，而是阻断并要求人工核对。
- 相同 requestId 的聊天工具任务已完成时返回持久最终答复；仍在运行/排队时拒绝重复启动；已取消任务不自动重放。
- 这属于持久检查点加保守重放保护，不等同于远端服务端幂等事务或分布式 exactly-once。
- 未运行构建、测试或设备验收。


## 2026-11-22 最终静态核对补记（代码 HEAD `e65da962bce709bf4a7a32c421908b406f39cef8`）

- 模型驱动 MCP 的重放账本在原生与回退路径均已接入；账本写入失败时 fail-closed，恢复时工具/参数签名与调用序号不一致则阻止执行；已完成请求返回持久最终答复，运行中的同 requestId 请求拒绝重复启动。
- 直接 MCP 调用在已取消但存在完成检查点时，不再向 UI 返回虚假的成功状态。
- 工作流输入或定义变化时，旧待恢复任务必须先取消，避免覆盖其 taskId 后失去恢复/终止入口。
- 以上为代码静态回读；没有运行构建或测试。


## 2026-11-22 统一审批审计持久化（代码 HEAD `9ed2b825b68d15b4709809d8ba64bb5a8b761b98`）

- NestroDatabase 升级至 version 16，新增 `agent_approval_audits` 表及 scope/status 索引，记录审批范围、工具、原因、PENDING 和最终决策。
- AgentApprovalCoordinator 是工作流、插件、MCP 与模型工具授权共用的审批入口；审批前先持久化 PENDING，用户决定后写入 AUTHORIZED_ONCE、AUTHORIZED_TASK 或 REJECTED。审批审计写入失败时不放行外部工具执行。
- 每次审批尝试使用独立 ID，保留同一 scope 下重复审批、重试和进程恢复后的历史决策；新增 `endScopeAndAudit`，在工作流完成/取消、插件恢复/取消、MCP 任务完成/失败/撤销和聊天工具审批范围结束时，将遗留 PENDING 记录更新为 CLOSED/CANCELLED/FAILED。
- 授权决定不跨进程自动继承；应用重启后仍需重新确认，历史记录只用于审计，不作为自动授权凭据。
- DAO 已提供最近记录和待处理记录查询；独立审批记录浏览 UI 尚未制作。
- 已新增审批记录与 scope 关闭的测试源码；未运行构建或测试。


## 2026-11-22 17:04 UTC — 构建前总审计基线刷新与过期结论纠正

审计源码基线：`b394e60f023fc8354d55bbec046cecac30e46ca1`。后续仅有审计文档提交，源码未改。GitHub 中控：Issue #21。

### 当前源码已证明、此前审计/模块清单已过期的判断

- **模块3工具并发：** `AgentToolSpec.parallelSafe` 与 `OpenAiClient.nativeToolLoop` 已支持显式声明的安全只读工具并发，最多 4 个，并按原调用顺序回注。MCP、外部写入、设备操作、需审批工具仍串行；兼容文本协议仍每轮单动作。因此状态为“受限部分实现”，不是“完全没有并发”。
- **模块3上下文预算：** `ModelDefinition` 当前未声明模型 context window；`ChatRequestPolicy.boundedHistory` 仍使用默认 16,384 context / 2,048 output reserve。按模型真实上下文窗口动态预算仍是实际缺口。
- **模块7召回 query：** `ChatRequestContextAssembler` 已将最近 7 条用户消息与当前 prompt 合并为 user-memory query；会话 recall 使用最近 8 条历史。旧清单中“只使用单一 prompt/未合并最近轮次”的描述过期。
- **模块7记忆范围：** `UserMemoryEntity` 仍以 projectId 作为归属；单条记忆可设置 shareAcrossProjects，并且读取其他项目记忆还需要显式 READ grant。当前未发现独立的全局个人记忆作用域，也未发现按全局/项目/会话作用域的 memory-context cache；embedding cache 不等于上下文缓存。
- **模块7持久层写入边界：** `UserMemoryStore.saveCandidates()` 在 DAO 写入前检查输入 ID 格式、内容安全、mutation token 与同项目指纹冲突，但尚未在同一事务内验证项目仍存在、来源 conversation 属于该项目、source message 确实属于该 conversation。需要沿 Room DAO/事务补归属校验，并确认调用时消息已先持久化。
- **模块8 Phone Agent 终态保护：** `NestroDatabase.kt` 当前 DAO 更新语句已禁止迟到 plan/progress 写入将 COMPLETED/CANCELLED 任务复活；对应历史回归测试源码已回收。模块清单中该项未勾选属于过期。
- **模块8 Mika 浮窗交互：** 当前 `PhoneAgentPage.kt` 已移除标题栏和可见按钮；单击窗口切换预览尺寸，双击/长按向虚拟屏注入点击，透明四角拖动调整预览大小，普通拖动注入滑动。缩放只修改 Compose 预览容器尺寸，不修改 VirtualDisplay 分辨率/DPI。旧审计中“仍有标题栏/双击缩放/无点击缩放”的描述已过期；ADB/Helper/虚拟屏真机验证仍待后续统一验收。
- **模块8 Phone Agent 分类：** `PhoneAgentPlanner.Category` 目前只有 SHOPPING、FOOD_ORDER、BOOKING、WEB_RESEARCH、FORM、CREATIVE_ASSET、GENERAL，没有 TRAVEL；导航路线规划/主屏导航前展示路线并单独授权仍是代码缺口。
- **模块16插件与能力中心：** 生成式插件已由 `AgentTaskCoordinator.runInline` 管理状态/幂等/步骤 checkpoint；外部写入与显式确认进入统一审批；`GeneratedPluginCenterPage` 已显示待恢复插件任务并提供“恢复插件任务并重新审批”和终止入口；`CapabilityDirectoryPage` 已有项目级能力开关、MCP 项目授权和调用审计。旧清单 L276-L280 的“完全绕开任务机/EXTERNAL_WRITE 直接 Allow/能力总览 UI 不存在”过期。任意代码型插件独立进程沙箱仍未实现，当前产品是声明式工具工作流，不能宣传成任意代码插件沙箱。
- **模块18日历自动调度：** 当前 `CalendarReminderScheduler.kt` 已为每个 occurrence 生成确定性 taskId/idempotencyKey 并 enqueue 到 AgentTaskCoordinator；`NestroApplication.kt` 注册 `calendar_automation` handler，经 ChatRequestExecutionCoordinator 进入主模型工具链；CalendarEvent 持久化 nextRunAt/lastRunAt/lastTaskId/lastIdempotencyKey、状态、结果和错误；事件编辑/完成/删除会取消关联活动任务；应用启动恢复进程中断的 calendar_automation 任务。旧清单 L325-L328“仅通知/无任务机/无状态回写/无幂等/无重启恢复”过期。
- **模块18剩余边界：** 当前 CalendarRules 支持 NONE/DAILY/WEEKLY/MONTHLY/YEARLY occurrence；没有独立延迟触发和任意时间条件模型；CalendarEvent 未见独立 retryCount 字段，入队重试主要依赖 WorkManager runAttemptCount，主模型业务失败的错误分类退避仍需核对/补齐。月/周/日页面是否应从主导航收敛为轻量配置入口，按用户最终产品决策实施。
- **模块17剪辑：** `MediaTimelinePolicy.addTrack` 明确限制每项目一条视频轨、一条音频轨；`validate` 也强制单视频轨/最多单音频轨，`MediaRenderEngine` 使用 single video track。多视频轨/PIP、转场、速度曲线、关键帧、高级音频混音和字幕烧录仍是真实代码缺口，不只是未验收。

### 其他仍需逐项核对的需求

- 模块1双向自由群聊/成员私聊、可编辑 DAG、暂停/恢复/返工、结构化验收协议和临时团队生命周期。
- 模块4媒体 ZIP 与通用设备备份的整合边界；模块5项目复制/删除时所有资源/权限/引用的一致性及新资源 scope 覆盖保护。
- 模块7全局记忆作用域、memory-context cache、DAO 项目/会话/消息归属校验。
- 模块8登录态/私有文件/受限下载源接管边界与 TRAVEL 路由。
- 模块17多轨/PIP/转场/速度/关键帧/字幕烧录/混音。
- 模块18延迟/时间条件触发、retryCount/错误分类退避和轻量入口形态。
- 模块19需按当前实际 Provider 能力分别区分图片理解、音频转写/理解、视频场景检测/通用视频理解、视频生成；不能将 scene detection 描述成任意视频问答。
- 模块2魔搭 OAuth 的第三方注册/部署/API-Inference 权限、动态余额/价格仍属外部配置或官方接口缺失，不可由源码虚构。

### 审计边界

本节为当前源码定向核验补记，不是十九模块总审计结束。未修改业务代码；未运行 Gradle、JVM/Android 测试、模拟器、真机或外部 API 联调。后续按 Issue #21 继续完成全部需求映射和静态修复。


## 2026-11-22 17:20 UTC — 静态调用符号核查与首个编译阻塞修复

- 在 `NestroApp.kt` 发现 `ConversationSnapshotPersistenceEffect(...)` 调用，但整合分支完整 Git tree 中不存在定义/导入/生成来源；GitHub 代码搜索也没有定义。该符号无法由当前仓库源码解析，属于确定的编译阻塞，不能等到统一构建时才发现。
- 已新增 `app/src/main/java/app/nestro/ui/ConversationSnapshotPersistenceEffect.kt`：使用 Compose `LaunchedEffect` 对 Room 快照写入做 300ms debounce，使用最新 callback，区分协程取消与持久化异常；持久化实现仍委托给现有 `ConversationPersistenceService`，未另建存储层。
- 已修正 `NestroApp.kt` 持久化回调：成功写入后同步 `persistedConversationVersions`，并清理已移除会话的版本 marker，避免每次快照变动都把所有会话重复写入。
- 提交顺序：`478c2e386ae793da2e479b72f01dbcbcc17d84e4`（新增 effect）、`7b1eadf0f62b419cb97fd48712786044f544c1ab`（回写版本 marker）、`f7b36a32653f582a582fcc0677276f8d03161737`（异常边界收窄为 Exception）。已回读两个文件并确认调用点与定义存在。
- 该修复尚未 Gradle 编译或运行测试；不能标记编译通过。后续仍需继续静态扫描其他 unresolved references/遗漏接线。


## 2026-11-22 17:25 UTC — 自动记忆来源归属校验修复

- `UserMemoryStore.saveCandidates()` 原先只校验 projectId/conversationId/messageId 的非空/长度、mutation token、候选内容和指纹冲突；未验证项目存在、来源会话归属项目、来源消息真实存在且为 user 角色。
- 已补充写入前校验：`dao.projectExists(projectId)`、`dao.conversationWithMessages(conversationId)`、conversation.projectId 相等、source message 存在且 role=user；不符合时不写入。
- 为避免自动提取与 300ms debounce 快照写入竞争，新增 `ConversationPersistenceService.persistConversationMessages()`，在自动记忆提取前以 Room transaction 持久化当前会话完整消息，并拒绝跨项目覆盖；NestroApp 在该事务成功后才调用自动记忆提取。
- 新增/更新 instrumentation 覆盖：来源会话/消息必须存在且归属同项目；不存在项目、跨项目会话、缺失消息均拒绝；合法来源成功保存；会话持久化可创建来源记录且拒绝跨项目覆盖。
- 相关源码提交：`bfe9771a8ac5c9cfe0e08b2014eb47a6b7b1a8b5`（会话事务持久化）、`f5917446812a194b832c0b65cfb0528cd07d4347`（自动提取前先持久化）、`a9f398a65ef5917b6a112173ebc8837ba47875ce`（记忆来源归属校验）、`e465323f18977ccf6ee52fa98f7ea5f22f9c3aa3`（来源验证测试）、`91a75bdd22f5d5db70b074ad1c3c0779ec4ae63f`（会话事务测试）、`bcc3b346935e545cec50e0d8d165995e2f3f2833`（持久化版本 marker 同步）。
- 已回读源码与测试源码，未运行 instrumentation/Gradle；此项标记为“代码已补、测试源码已补、运行验证待后续”。


## 2026-11-22 17:32 UTC — 项目资源新建归属覆盖保护修复

- 历史 WIP module5 分支存在 `assignNewResourceToProject` 语义，当前整合版仅保留通用 `setProjectScope`。后者允许覆盖已有 key；若新建资源 ID 命中残留 scope key，可能改写旧资源归属。
- 已在 `ProjectResourceScopeStore` 恢复 create-only `assignNewResourceToProject`：新建时要求 scope key 完全不存在，否则拒绝覆盖；已有资源的共享/全局切换仍使用 `setProjectScope/setGlobal`，两种操作分离。
- 已把 NestroApp 中新建团队、导入新技能、创建技能、新建团队成员专家、导入新团队、新专家导入、创建工作流、新建专家等 8 个入口切换到 create-only API；现有资源的范围开关仍使用 update API。
- 新增 `ProjectResourceScopeStoreTest.newResourceAssignmentCannotOverwriteAnExistingScopeKey`，验证新建成功、已存在 scope 拒绝覆盖、原归属不变。
- 提交：`6460ddd0cccc00c07f709b170eed5ebc59ce58d3`（store）、`34fac631299f2a0d2751b6775e99f6e8b69b3eac`（test）、`9a4c1fbcd757017243da823f9758b7569c834713`（8 个 UI 创建入口接线）。已回读并静态确认 8 个创建入口均使用 create-only API，4 个范围开关仍使用 update API；未运行测试。
