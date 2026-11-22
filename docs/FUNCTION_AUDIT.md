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
- **未发现 TRAVEL/导航场景分类、地图路线规划步骤或“虚拟屏预览→主屏导航”专用交接动作。** `PhoneAgentExecutor.Action` 当前只有 OpenUrl/LaunchApp/DownloadAsset/ClickText/EnterText/Scroll/Wait/VerifyText/Back/ConfirmSensitive；`PhoneAgentService.openUrl()` 走普通 ACTION_VIEW，不能替代带路线预览、主屏目标确认和单独授权的导航交接流程。
- 当前风险策略能阻止支付/提交等高风险点击并要求用户接手，但这不等于已实现“切换到主屏导航前展示目的地并授权”。该场景是明确缺口，后续应在现有 Phone Agent/统一权限路由中增加专用 handoff 能力，不能用普通 openUrl 变相实现。
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

