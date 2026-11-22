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
- **Mika 虚拟屏底层已存在。** `phone/virtualdisplay/mika/` 包含 ADB、helper/Binder、VirtualDisplay 和 Phone Agent execution environment；`PhoneAgentPage.kt` 接入实时帧悬浮预览及触控输入。当前浮窗仍有“Phone Agent / 展开 / 收起”标题栏；点击标题栏折叠/展开，双击画面缩放，画面点击执行触控，右下角拖动区域调整尺寸。与用户指定的“无按钮/角标/手柄、点击窗口放大/缩小”的交互不一致；纵向拖动目前正向位移增大 previewScale（下拉放大、上拉缩小），需保留该方向并调整入口/显示元素。
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

