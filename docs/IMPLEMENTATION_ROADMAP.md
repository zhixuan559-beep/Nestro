# 2026-11-23 当前工作基线（续接总审计）

- 当前分支：`integration/19-modules`
- 当前源码回读基线：`af39255062d623db8d82adfb21030565eac82361`（专家团暂停/继续、停止中断及协调器集成测试源码已提交；本次文档更新后的实际 HEAD 以 Issue #21 和 GitHub 分支为准）。
- 当前工作：继续 integration/19-modules 分支的真实源码收口。本轮完成模块18延迟分钟触发的端到端接线、定义版本隔离、跨重启补排程与备份兼容；不重做已有日历任务机。
- 本轮已核实并补齐：统一系统返回键（关闭抽屉/二级页面回聊天）；修正新版导航对应的旧 UI 冒烟测试；补齐工具目录 14 个入口测试；专家团协作事件按项目分页查看（每页16条、当前保留上限100条）；修正抽屉宽度算法的冲突测试预期；专家团项目任务新增批次边界暂停/继续控制，并补充执行协调器 PAUSED→RUNNING→COMPLETED 集成测试源码。
- 当前 UI 静态核验：14 个工具目录 destination 均有页面路由；主聊天及二级页面黑白主题字面量扫描未发现硬编码彩色主题；审批交互、Phone Agent 浮窗手势、专家私聊/群聊生命周期及动效已有对应源码。静态核验不等于编译或设备验收。
- 代码地图基线：445 个 Git tree 条目、333 个 Kotlin 文件（主源码205、JVM测试95、Android instrumentation测试33）。
- 当前 UI/功能遗留：专家团可编辑任务图、失败工作包返工、结构化验收协议、应用进程退出后的暂停任务恢复入口仍需补齐；会话内暂停可在批次边界恢复，停止按钮可中断暂停等待并写入 CANCELLED；Logo 原始双环图片资产未找到，像素级还原和 Launcher 集成未完成。
- 验证边界：尚未完成十九模块全量静态核对；没有运行 Gradle/Kotlin 编译、JVM/Android 测试、模拟器、真机或 GitHub Actions；没有构建 APK。全部留到代码收口后的最后阶段。
- 下一步：继续逐项核对专家团执行计划控制与剩余模块源码/调用链；只修复证实缺失的链路；最终二次静态扫描后停在“可编译候选”状态，交由用户进行最后编译调整。

- 2026-11-23 真实源码复核：检查对象 HEAD 为 `d1105ebd74249f2f31fb0745128e6fe76d1e1a4b`；与 `3a02646778fcb0135bdb866ca5ebb5f62950cfe2` 比较后确认后续 3 个提交只有审计文档变化，源码基线仍为 3a。
- 本轮纠正文档误判：模块16能力授权/审计 UI、插件逐步骤审批已存在；模块18日历 Worker 已接统一任务机；模块8导航与主屏交接代码已存在；模块7已有 64 项/60 秒最终上下文缓存。详见 `docs/FUNCTION_AUDIT.md` 最新复核段和 `docs/NESTRO_19_MODULES.md` 对应行。
- 本轮确认的真实代码缺口：自动记忆来源删除后的引用完整性与缓存失效；日历任意时间条件、日历专属语义型业务错误映射及专家/工作流分发（通用任务机瞬时网络/HTTP 重试与副作用保护已存在）；Phone Agent、媒体多轨与聊天原生多模态的剩余边界。专家/工作流工具感知入口、任务元数据及 Phone Agent 浮窗拖动已有源码，不能重复列为未实现。
- 当前验证状态：仅完成静态源码/调用链/测试源码核对；本轮未修改业务源码，未运行编译、测试、模拟器或真机。当前 HEAD 没有 CI check runs，Android CI 为手动 workflow_dispatch；不得称为可编译或验收通过。
- 下一步：继续核对记忆来源删除后的引用清理与缓存失效、日历任意时间条件/业务失败重试/专家工作流分发，以及媒体多轨和原生多模态缺口。每项修复先核实实际调用关系，静态回读并补测试源码；全部缺口收口后再统一编译验收。

---

# 2026-11-22 16:49 — 构建前总审计防断档检查点

> 中控记录：GitHub Issue #21（构建前总审计与十九模块收口）。每次阶段结束必须更新该 Issue 与本文件，并回读验证。

- 当前整合分支：`integration/19-modules`
- 初始源码核对基线：`b394e60f023fc8354d55bbec046cecac30e46ca1`；最新源码修复基线：`9a4c1fbcd757017243da823f9758b7569c834713`
- 当前状态：总审计进行中；尚未完成十九模块逐项对照，不能宣布代码收口或可构建。
- 已确认的文档冲突：`CURRENT_CODE_MAP.md` 原记录 HEAD 为 `fdb1220125e608f4353902c5fc365853cf30e3a2`；`NESTRO_19_MODULES.md`、`FUNCTION_AUDIT.md`、本路线图的模块状态描述存在互相矛盾，尤其模块18日历和统一任务机接入状态。旧记录保留作历史，不得当作当前结论。
- 当前工作范围：只做源码/调用链/数据状态/权限/异常恢复/测试源码审计，确认缺口后成组修复并静态复核；暂不构建 APK，不运行 Gradle、JVM/Android 测试、模拟器、真机或 API 联调。
- 审计口径：每个功能分开标记需求登记、源码实现、入口接通、持久化/恢复、测试源码、运行验证；任何一项不能由另一项替代。
- 防重复规则：先确认已有实现和调用关系，优先修复接线/边界，不重建 AgentTaskCoordinator、Provider、Worker、MCP 工具目录或第二套任务队列。
- 防断档规则：每阶段记录基线 SHA、审计文件/符号、证据、修改提交、剩余缺口、下一步；新会话先读 Issue #21、路线图、代码地图和最新 HEAD。
- 本次已完成：重新读取 HEAD、审计文档、十九模块清单、代码地图和路线图；创建 GitHub Issue #21 作为持久中控检查点。
- 本次已新增源码修复：补齐缺失的 ConversationSnapshotPersistenceEffect，修正会话快照持久化版本 marker 回写，并补齐自动记忆来源项目/会话/消息归属校验及提取前事务持久化、项目资源新建 scope 防覆盖；测试源码已补，未运行构建/测试。
- 本次未完成：全量模块源码核验、分支候选逐文件差异复核、重复/死代码审查、剩余缺口修复及最终二次扫描。

---

# 2026-11-22 当前进度基线同步（先同步报表，后做代码审计）

> 本节是本轮对话与仓库状态的同步记录，不把聊天中的需求自动认定为代码已实现。下列“已有代码线索”必须在后续逐项对照当前整合分支源码、UI入口、调用链和测试；未经核验的项目标记为待审计。历史报表中旧状态若与当前源码冲突，以当前整合分支实际代码和可复现验证结果为准，并在审计后修订，不直接删掉历史记录。

## 一、当前工程基线与报表口径

- 当前工作基线：`integration/19-modules`。本轮已从该分支读取本文件与 README；后续全量审计以该分支最新 HEAD 为准。
- 仓库不是“十九个 Gradle 子工程”；十九项是产品功能模块，工程当前以 Android app module 为主体。
- 本报表同时记录三种状态，禁止混写：
  1. **需求已登记**：用户明确提出，已写入本报表。
  2. **源码待核验/源码已核验**：必须检查实现、入口、调用链、持久化、异常分支及测试。
  3. **验证状态**：编译、单测、模拟器、真机分别记录；不能用某一项代替其他项。
- 现有路线图的模块功能清单继续保留，作为历史需求与功能基线；本节补录最近新增/细化的跨模块需求，避免只扫描单一模块或遗漏前几轮对话。

## 二、最近明确新增/细化的功能需求（全部纳入十九模块整体审计）

### A. 创作型任务 × Phone Agent 执行型任务混合调度

- 创作型任务由专家团/创作能力负责内容生产；当创作需要外部网络素材时，主调度必须识别素材依赖，并按需调用 Phone Agent 执行型能力。
- Phone Agent 可在用户授权范围内打开其他 App 或网页，搜索、下载所需图片/视频/音频/文本等素材；素材下载完成后，必须将实际文件及必要的来源/路径/元信息交还原创作任务和专家团，不能只返回“已下载”的文字描述。
- 创作与执行能力支持按任务依赖进行串行、并行及混合编排；两类 Agent 可以多次往返：创作提出素材需求 → 手机执行下载/整理 → 素材回交 → 专家继续创作 → 再提出下一轮执行需求，直到任务完成。
- 不能把创作任务强制改成 Phone Agent 操作，也不能把需要手机操作的步骤假设为专家团可直接完成；由主调度按步骤能力和依赖关系分派。
- 任务链路必须传递任务 ID、子任务/依赖关系、执行状态、进度、产物引用、文件交接结果、失败原因和取消状态；支持中断、恢复、重试与人工接管，避免丢失已下载素材或重复执行不确定动作。
- 混合任务的完成条件是创作结果与所需素材均实际交付，并通过任务核验；不能只以某一侧 Agent 返回成功作为整体成功。

### B. Mika 虚拟屏能力移植与 Phone Agent 交互接管

- 用户要求复用 Mika 中有用的虚拟屏实现，采用代码移植并适配现有 Nestro 接口/依赖；不得借此重构或替换既有 Nestro 系统，不得复制无关功能。
- 虚拟屏以竖屏手机比例呈现，采用缩小的悬浮窗口；窗口可拖动、放大、缩小并保持可交互。
- 交互规则：不显示角标、按钮或额外手柄；点击窗口进行放大/缩小；拖动角部时，上拉缩小、下拉放大。窗口缩放只改变预览显示尺寸，不应擅自改变虚拟设备分辨率/DPI或破坏任务坐标映射。
- 需要支持用户查看实时画面、与虚拟屏交互，以及在需要时接管操作；接管与 Agent 执行之间要有明确状态切换和授权边界。
- 后台执行、虚拟屏画面、Phone Agent 任务状态和执行结果应关联同一任务生命周期；异常、连接断开、进程回收时应进入可恢复/待接管状态，不得伪报成功或自动重放不确定动作。
- 这部分需审查现有 Mika 派生隔离虚拟屏、无线 ADB/TLS、shell helper、Binder 安全交接、VirtualDisplay、指定 display 的 AccessibilityService、浮动预览等代码是否完整接线；不可因为路线图有勾选就跳过 UI 和真实调用链核查。

### C. 工作流审批语义修正（2026-11-22）

- 审批弹窗不是“默认拒绝”。必须提供三项明确选择：
  1. **授权一次**；
  2. **当前任务全部授权/永久授权（仅当前任务范围）**；
  3. **拒绝**。
- 用户尚未选择时，任务进入等待审批状态并保持暂停：不前进、不后退；关闭弹窗不能被解释为拒绝或授权。
- “永久授权”只在当前任务范围内有效，不得扩展成全局、跨会话或跨项目授权。
- 授权结果必须进入任务状态/审计记录，并由实际工具调用链执行；不能只做弹窗 UI 或保存一个未被运行时读取的标记。

### D. 专家团自主组织与用户手动操作两种模式

- 专家团保留用户手动选择/操作模式。
- 当用户提出项目型目标时，主模型可以判断需要自主组织专家团：拆分任务、分派给合适专家、按依赖串并行执行、汇总各专家成果。
- 专家之间需要有结构化任务交接和结果回收；如过程中需要手机执行、网络素材或其他工具，必须由统一调度按需调用对应执行能力，而不是专家团虚构已完成的外部动作。
- 应核对现有 Expert DAG、工作包、并行波次、检查点、结果交接和恢复实现是否真正接入自然语言入口及混合调度。

### E. 统一自然语言入口与按需调度原则

- 普通聊天不应机械启用工具/Skill；明确需要执行外部操作时才进入工具/Agent 调度并按授权规则处理。
- 普通对话仍可按上下文使用记忆、知识库和绘画能力；不能将“普通聊天不调用执行工具”误解成禁用所有辅助能力。
- 从专家入口进入时，按该专家实际需要加载其必备 Skill/插件；其余工具按任务需求动态调用，不能一刀切预加载或全部启用。
- 缓存需求是与记忆挂钩的本地缓存，应检查是否存在全局/项目/会话作用域、记忆增删改后的失效/更新机制；它不是 Prompt Cache，也不是任务调度缓存。若源码不存在，应在缺失项中标注，不得声称已实现。

### L. 记忆提取并发安全与召回一致性

- 自动记忆提取必须绑定项目 mutationVersion；记忆被编辑、删除、清空或备份恢复时，先递增版本并等待旧提取退出，旧版本候选不得写回。
- `saveCandidates` 必须再次校验项目存在、提取版本仍有效、来源会话/消息仍属于该项目；不能只依赖提取开始时的检查。
- 会话记忆召回 query 应包含当前用户 prompt 和最近有效用户轮次；当前整合版只从 requestHistory 构造 conversation recall query。
- embeddingCache 与记忆上下文缓存是两件事：前者只缓存向量；后者需按全局/项目/会话范围和记忆版本失效，不能复用旧上下文。
- 全局个人长期记忆必须跨聊天窗口共享；项目资料默认隔离，不能自动串项目。用户可以主动发起跨项目检索，系统需按用户选择的项目范围执行，并提供授权/撤销、来源项目标识和审计记录。

### K. 统一任务执行基座与模块接入边界

- 主模型负责理解用户目标、决定直接回复还是调用能力；`ModelLedToolDispatcher` 负责工具白名单、权限路由、执行调用和结果回传。
- `AgentTaskCoordinator` 是跨模块任务生命周期的统一基座：任务 ID 幂等、排队/运行/等待用户/暂停/失败/完成/取消、checkpoint、事件追踪、重启恢复和重新入队由同一状态机管理；当前仅支持上层显式 resume，统一的最大重试次数/退避/错误分类/幂等安全重试策略仍待补齐。
- 模块可保留领域数据（例如 Phone Agent 动作日志、媒体任务参数/进度、日历触发规则），但不得各自再造一套互不关联的通用任务状态机；领域状态需与统一任务 ID/状态/取消/恢复同步。
- WorkManager 负责可靠的后台唤醒与系统约束执行，不应替代统一任务状态源；时间调度触发后应将任务交给统一基座。
- 当前审计已发现：Phone Agent 同时使用领域任务表和 AgentTaskCoordinator；媒体 AI 任务使用独立 JSON 文件状态 + WorkManager，尚未接入 AgentTaskCoordinator；日历提醒仅 WorkManager 通知，也未接入统一任务基座；生成式插件执行自行写 RoomAgentTaskStateStore，没有通过 AgentTaskCoordinator，也没有可恢复步骤 handler。工作流已接入 AgentTaskCoordinator，但审批等待状态未显式写入 WAITING_FOR_USER。后续需先做状态映射与边界设计，再最小改造接线，不要重复建设新的队列或任务数据库。
- 验收必须验证跨模块同一任务 ID 的状态一致性、重复触发幂等、等待审批、暂停/恢复、取消、异常重试、进程重启恢复和结果回传。

### J. 主模型场景路由与虚拟屏/主屏交接（2026-11-21 用户明确举例）

- 主模型按场景选择最短可行路径：简单问答直接回答；资料检索调用必要工具；创作/项目任务按需组织模型与专家；手机代办才启动 Phone Agent；不能每轮强制组专家团或强制调用工具。
- 购物/点餐场景可由 Phone Agent 在虚拟屏检索多个可用来源、比较最终到手价/配送费/优惠/时效，再选择候选；支付/最终下单等不可逆步骤必须停下并由用户明确确认。
- 出行/回家场景由模型调用地图规划路线；若用户要求实际切换到手机主屏并启动导航，必须先展示目的地/路线并请求用户授权，确认后再从虚拟屏交接到主屏导航。虚拟屏内预览/操作与主屏接管是两种不同权限边界。
- Phone Agent 的任务计划、主模型工具调用、审批与执行状态必须回到统一调度基座；不允许通过普通 `openUrl` 偷偷绕过主屏交接授权。 独立且无数据依赖的工具调用应能安全并发；同一项目/文件/设备的冲突写操作必须串行或显式声明依赖，结果按调用 ID 稳定回注。
- 适配要求：手机、平板和大屏自适应；高响应、轻量化；检索与任务准备可并行，但 UI 状态更新和执行结果保持确定性。
- 上述场景是明确的验收用例，不代表必须预设固定 App 或固定流程；主模型应根据实际可用工具/页面和用户目标动态决策。

### I. 模块18定位：统一自动时间调度引擎（2026-11-21 用户明确纠正）

- 模块18不是独立日历产品页；它主要辅助整个 Nestro 的自动定时与时间触发。日历视图最多作为轻量管理入口，不应把月历/周历 UI 当作模块18的核心交付。
- 需要支持单次定时、周期触发、延迟触发和时间条件判断；到期后把事件分发给统一任务/Agent/工作流执行链，按任务类型由主模型协调工具、专家团、Phone Agent 或其他模块。注意当前 WorkflowStepKind 仅支持 PROMPT/EXPERT/SKILL，不能直接执行 Phone Agent/媒体/MCP 工具；混合任务应交给主模型工具调度器，或在审计后增加受统一权限控制的工具步骤。
- 调度层负责触发执行、权限/审批衔接、任务状态跟踪、结果回传、失败重试、暂停/恢复和取消；任务结束/失败/等待用户时写回真实状态，不能只发通知。
- 调度记录、触发计划和执行关联必须持久化；应用/进程/设备重启后恢复未完成计划，已执行任务必须幂等，避免重复触发或重复扣费/执行。每次周期 occurrence 需有确定性唯一 ID（例如 scheduleId + plannedAt），并记录 nextRunAt、lastRunAt、关联 AgentTaskId、重试次数和最终状态，不能只用日程 ID 作为所有周期任务的同一个 taskId。
- 统一基座优先复用现有 `AgentTaskCoordinator`、`AgentToolCatalog`、`ModelLedToolDispatcher` 与 WorkManager；WorkManager 只承担可靠时间唤醒，触发后交给统一任务执行链，不新建平行任务数据库/第二套 Agent 队列。
- 保留系统日历导入/导出作为可选互操作能力；不要为此把模块18重新设计成完整个人日历产品。

### H. 产品开源、本地数据与账号边界（2026-11-15 已确认的产品约束）

- Nestro 必须保持开源、免 Nestro 账号、免 Nestro 登录；不依赖 Nestro 自建用户账户体系才能使用本地核心功能。
- 用户数据、知识库、记忆、项目、模型配置和应用数据默认留在用户设备本地；Nestro 不托管用户的第三方账户、密码或云端数据。
- 第三方模型服务、MCP 服务、GitHub/GitLab/Gitee 等账号由用户在对应服务中自行登录/授权；Nestro 保存用户明确配置的凭据时必须使用本地安全存储，不能将密钥写入公开仓库、日志或普通聊天文本。
- 本地优先不等于禁止云端仓库操作：用户仍可主动浏览、Clone、Pull、编辑、Commit、Push 和同步 GitHub 等云端仓库。
- Nestro 公开开发仓库与灵犀私人记忆/技能/个人规则严格隔离；不得把私人记忆文件或用户私有凭据打包进公开 Nestro 项目。
- OAuth 的服务端 Client Secret 只能放在用户/运营方部署的服务端环境变量中，不进入 APK 或公开仓库；Android 端只保存用户授权后返回的 token 到本地安全存储。
- 上述是产品边界，不是额外新增账号/云同步功能；后续模块审计和界面设计不得把 Nestro 设计成强制云账号产品。

### G. 主模型调度多模态模型与视频模型（2026-11-21 对话补录）

- 文本主模型作为统一调度者，按用户任务识别需要文本、图片理解、音频转写/处理、视频理解/分析或视频生成中的哪一种专业能力；不能把文本模型自身的回答冒充专业模型已执行。
- 多模态/视频模型可独立于主聊天模型配置，主模型应读取可用服务商、模型能力与当前配置状态，再选择合适模型/服务商并调用；同一任务允许文本主模型组织提示词、调用专业模型、收回真实结果、继续对话返工。
- 视频生成必须支持异步提交、任务状态/进度查询、取消/失败反馈、生成文件下载并导入当前项目素材库、结果回传聊天；用户要求修改上一版时创建关联的新版本，不覆盖旧产物。
- 如果没有可用的专业模型配置，应明确告知缺少能力并提供可执行提示词；只有用户明确要求使用已安装 App 且本轮 Phone Agent 能力实际可用时，才尝试手机应用兜底，不得虚构安装/登录/执行结果。
- 视频服务商的聊天/视觉连接测试不能冒充视频接口测试；应对视频提交、轮询、鉴权、错误响应、结果下载、文件导入、超时/取消和返工版本分别验证。不同签名、multipart 或不同任务协议的服务商需要专用适配器，不能靠猜测通用 JSON 接口。
- 多模态分析与视频生成是不同能力：需分别检查图像理解、音频转写/处理、视频生成/分析是否能被主模型按需调用，不能因媒体编辑器页面有 AI 按钮就认为聊天主模型已接通所有能力。

### F. 对照更新前报表发现的漏记项

以下要求在本次基线同步前没有以完整需求形式进入原路线图；本次已补入第二部分，后续审计按需求逐条验收：
- 创作与 Phone Agent 执行型能力混合调度，包含网络素材下载、文件自动导入当前项目、素材 ID 回传创作任务，以及创作/执行多轮串并行往返。
- Mika 虚拟屏的明确移植边界、竖屏缩小悬浮、实时画面/交互接管，以及“不显示角标/按钮/手柄、点击缩放、角部上拉缩小下拉放大”的具体交互规则。
- 工作流审批的三项选择、“授权一次 / 当前任务全部授权 / 拒绝”、未选择时保持等待、永久授权仅限当前任务范围。
- 专家团手动模式与主模型自主拆解项目、分派、并行执行、汇总模式之间的明确关系，以及专家与手机执行能力的协作边界。
- 普通聊天按需调用、专家入口按需加载必备 Skill/插件，以及“与记忆挂钩的本地缓存”这一独立需求和失效规则。
- 上述需求已写进本报告，但“已登记”不等于实现已通过审计；后续必须给出对应文件、调用点和测试证据。

## 三、最近模块开发/状态线索（须以源码复核）

- 模块2模型生态：OpenAI、DeepSeek、Claude、硅基流动、魔搭、Gemini、百炼/通义千问等预置 Provider；协议元数据持久化并进入运行时；Anthropic Messages 与 Gemini 原生请求/流式适配、模型目录发现及快捷添加。魔搭 OAuth 与硅基流动动态额度/价格同步的实际状态，以本节顶部 2026-11-22 记录和真实代码为准，不得把官方网页快捷入口说成 OAuth 已实现。
- 模块17智能剪辑：需审查媒体项目、素材导入、时间线、预览、导出、字幕等既有代码；明确核查多视频轨/PiP、转场、速度曲线、字幕烧录等功能是否已实现，不因旧路线图清单直接判缺。
- 模块18日历：现有路线图已记录独立日历 Room、月/周视图、事件 CRUD、重复规则、WorkManager 提醒、系统日历 Provider 同步、备份恢复等；用户最新要求的工作流审批语义必须跨模块检查，不能误认为日历本身承担独立任务调度 UI。
- 模块19媒体服务与社区生态：现有路线图已记录 gpt-transcribe/视觉模型配置、WorkManager 媒体任务、SemVer 2.0 依赖求解、原子安装、独立信任根与撤销清单；需检查与模块17、模块16、混合调度的真实连接。

## 四、按用户确认的六项固定顺序推进

1. **Mika 虚拟屏移植与启动链路核对**：复核 Mika 适配代码、无线 ADB 配对、Shell helper/Binder 安全交接、VirtualDisplay 创建/复用/释放；已有实现只修接线和兼容问题，不重写。
2. **整体审计与集成**：以最新 `integration/19-modules` 为唯一整合基线，核对十九模块、主模型调度、权限、状态、数据和各模块调用链，确认真实功能都已进入同一基线。
3. **创作 × Phone Agent 混合调度**：核对双向调用、多轮交接和非串行协作；下载素材必须自动导入当前项目并回传素材 ID，创作和手机执行可按依赖反复交替。
4. **虚拟屏执行体验闭环**：核对后台执行、竖屏预览、悬浮窗、手势缩放、实时触控、用户接管，以及虚拟屏/主屏任务交接；按用户要求修正浮窗 UI 与手势，不重写底层。
5. **补齐确认缺口并清理历史代码**：先补断链/缺失功能，再通过引用图、调用链和隐式入口确认重复功能、死代码、废弃入口、历史遗留；只删除已证明无用的实现，保留迁移/备份/兼容路径，确保不破坏现有功能。
6. **全面优化**：统一任务状态与调度、性能、响应速度、内存/功耗、手机/平板/大屏适配和异常恢复；已有实现优先复用，禁止为“优化”再造平行模块。

**六项完成后的顺序：** 先基于真实代码和最终功能制作完整 APP 操作界面预览（聊天、专家团、项目工作台、手机执行、虚拟屏悬浮窗、素材管理、模型/API、日历调度等），由用户查看确认；界面确认后再统一构建 APK，并进入编译、自动化测试、模拟器、真机、升级/权限/后台/性能验收。

> 六项完成前不提前启动统一构建、测试或验收；本阶段以源码审计、必要代码修复和清理为主。任何新增代码必须先证明现有实现缺失或无法接通，并优先复用现有接口；不允许以重复造功能充当进度。


---

> **2026-11-22 模块2服务商生态增强记录（状态以本节后续 OAuth 补充为准）：** 硅基流动与魔搭统一快捷接入：服务商卡片可直接打开官方 API Key / Access Token 页面，预置地址和协议，添加密钥后自动发现实时模型目录；新增 OpenAI、DeepSeek、Claude、硅基流动、魔搭、Gemini、百炼/通义千问预置；Provider 协议元数据进入持久化注册表并传入运行时；Anthropic Messages 与 Gemini 原生非流式/流式调用适配、三类协议模型目录自动发现及 UI 快捷添加已写入。魔搭 OAuth 后端与 Android 授权回调代码已在 2026-11-22 后续补入，见文末“魔搭 OAuth 链路代码补齐”；仍需部署服务、注册 OAuth 应用、配置 Client ID/Secret/Redirect URI，并真实联调确认 access_token 的 API-Inference 权限。不得将 client secret 嵌入 APK。硅基流动旧 `/user/info` 余额接口已公告停服，不能继续依赖；动态价格/额度同步尚未实现，需等待官方稳定接口或使用官方账户页。新增回归测试源码尚未执行；未编译、未联调、未锁定。

# Nestro 实现状态与开发路线图

> **2026-11-22 模块19四项遗留代码补齐记录：** 媒体服务商新增 OpenAI 官方 gpt-transcribe 转写与独立视觉模型配置，真实端点能力按实际配置声明；媒体 AI 任务改为 WorkManager 持久化任务，支持进度、取消和进程恢复；社区技能新增 SemVer 2.0、递归依赖、版本约束回溯求解与原子安装；新增独立信任根签名撤销清单、签名者指纹绑定及已撤销来源技能自动停用。测试源码已补充但未执行。真实 API Key、正式信任根/撤销源配置属于外部凭据与运营输入；此前“模块19仍在 feature/module-18-calendar、未合并”的描述已过期：当前 `feature/module-18-calendar` 相对 `integration/19-modules` 没有独有领先提交，且日历/媒体关键文件抽查一致；当前整合分支已包含这些实现。统一编译、自动化、模拟器和真机验收仍待后续阶段。

> **2026-11-21 模块13代码阶段记录：** MCP 工具中心已实现远程 Streamable HTTP（2026-07-28 stateless 优先，并兼容旧版 initialize/session）、服务与工具发现、逐工具授权、参数预览/调用、Keystore 凭据加密、审计和撤销。后续静态复核补齐了损坏审计记录隔离及对应回归测试；代码已合并锁定至 `refactor/phase-2-architecture`（初始锁定 commit `5d805a9`，审计修复提交 `f2dd742` / `4284a37`）。Kotlin 编译/JVM 测试未执行，模拟器与真机验收留待十九模块统一阶段。Android 端仅连接 HTTPS 远程 MCP，不启动任意本地 stdio Server。

> 状态口径：只有代码已落地且对应构建通过，才标记为「已实现」。UI 占位、设计说明、接口草稿不计入完成。实体设备相关行为在真机验收前仍标记为「待真机验证」。

> **2026-11-20 模块7锁定记录：** PR #7 已合并至 `refactor/phase-2-architecture`，合并提交 `c2c2e18b7e5e383cb550a2ed7ac2fdb67584739d`。对应 CI Run #72 的 `verify` 与 `instrumentation` 均通过；编译、JVM 单元测试、Lint、APK/测试包构建及 Android 模拟器测试全部成功。模块7代码与云端验证锁定完成。

> **2026-11-21 模块8状态更正：** PR #8 已合并至 `refactor/phase-2-architecture`，合并提交 `ad48068efc97d99b37af851ef7c884539144c5ed`。提交 `af3d5a7ee2057f546a88ed7f96e80b73eca4a32a` 的 `verify` Job 全部通过，但 `instrumented` Job 被取消；后续合并提交对应的 `verify` 与 `instrumented` 也被取消。因此模块8代码已合并、静态/单元验证通过，但模拟器验收尚未完成，不得标记为云端完整锁定。真实设备兼容性、后台限制和功耗验收同样待完成。

> **2026-11-21 模块9锁定记录：** PR #9 已合并至 `refactor/phase-2-architecture`，合并提交 `5191ba81859914926652b52d3c19281256ff7d30`。CI Run #1190 对应提交 `f16d3ed9eba1c59b888ffea47aa198d18c973567`，`verify` 与 Android emulator `instrumented` 两个 Job 均成功；编译、JVM 单元测试、Lint、instrumentation test sources 与模拟器测试通过。模块9云端代码与自动化验收锁定；真实设备音频路由、回声、厂商兼容及网络恢复留待十九模块完成后的统一设备验收。

## 模块18：日历（代码阶段）

- [x] 独立 Room 日历数据库，避免改动既有主数据库 schema 与历史迁移。
- [x] 月历/周历网格、选日 agenda、创建/编辑/删除（二次确认）、完成与恢复、全天事件、地点和备注。
- [x] 单次、每日、每周、每月、每年重复；基于本地时区的日期递进，处理夏令时/月份长度变化。
- [x] 提前 0/5/10/15/30/60/1440 分钟提醒，支持关闭；WorkManager 唯一任务、保存后重排、完成/删除后取消、启动时补偿重排。
- [x] Android 13+ 通知权限请求入口；提醒通知回到聊天主页，日历作为辅助调度能力，不要求用户进入独立日历页。
- [x] Android Calendar Provider 读取/写入权限；按当前月份导入系统日历实例，向首个可写系统日历导出本机日程，支持已导出事件更新和关联删除；导入事件标记为本机副本，避免误覆盖系统原始事件。
- [x] 全天事件在系统日历 UTC 日期与本地时区之间做日期归一，避免跨时区偏移；导入重复事件按系统实例展开，导出重复事件写入 RRULE。
- [x] 提醒采用 WorkManager 持久化后台调度；系统省电策略下可能延迟，不承诺精确闹钟级准点。
- [x] 本机全量备份与恢复纳入日历记录；旧版备份缺失日历字段时按空日历兼容。
- [x] 新增重复规则专项 JVM 测试源码。
- [ ] 编译、JVM 测试、模拟器和真机验收：按用户要求留待十九模块代码全部完成后统一执行。

## 已实现

### 核心与本地安全
- [x] Room 数据库存储会话、消息、项目空间及本地知识库文档/片段。
- [x] Room 1→2 schema 迁移，为知识库建立独立表和索引。
- [x] Room 2→3 schema 迁移，为项目内历史召回增加项目/消息组合索引。
- [x] 首次启动从旧版 SharedPreferences/JSON 会话数据迁移。
- [x] 会话及消息更新使用 Room 事务写入；旧偏好存储保留为备份兼容镜像。
- [x] Kotlin + Jetpack Compose Android 应用。
- [x] OpenAI Chat Completions 兼容模型配置与连接测试。
- [x] Android Keystore AES-GCM API Key 加密。
- [x] 模型网络请求运行于 IO dispatcher。
- [x] 本地 JSON 备份/恢复：纳入 Room 会话/消息/回答版本、项目、知识库索引与文本块、全局及项目记忆、模型配置元数据、专家/团队、技能、工作流、Phone Agent 任务与日志；API Key 不进入备份。
- [x] 备份采用 Room 一致性事务快照、SHA-256 完整性校验、版本兼容检查、导入字段/数量/大小校验、恢复前二次确认及事务式数据替换；不再静默截断任务/日志。

### 对话与跨会话记忆
- [x] 多会话本地持久化、创建、切换、删除。
- [x] 会话标题/消息内容搜索与手动重命名。
- [x] 旧版单一 messages 历史迁移为默认会话。
- [x] 手动全局记忆编辑与跨会话 system context 注入。
- [x] 请求绑定发起时的 conversation ID，切换会话不把迟到回复写入错误窗口。
- [x] SSE 流式回复与停止生成。
- [x] 最近 40 条/48,000 字符的请求历史限制与 16,000 字符系统提示词上限。
- [x] 每个会话独立保存输入草稿，重新进入会话时恢复。
- [x] 历史召回来源可追溯：展示来源会话/消息 ID，并可从引用面板跳转原会话。
- [x] 项目级自动记忆候选提取初版：默认关闭，用户可主动开启；额外请求前执行基础本地敏感内容预检，候选返回后再过滤，支持本地保存、去重、关键词召回、单条编辑/删除。
- [x] 本地 BGE 语义向量召回、记忆分类/来源/时间/置信度管理、项目级自动提取开关、单条记忆编辑/删除/导入/导出、按项目清空及跨项目显式 READ 授权；敏感内容预检规则持续迭代。
- [x] 当前项目跨会话关键词召回初版：按问题检索其他会话消息，注入会话/消息 ID、标题与角色；限制单会话召回数量；完整聊天记录仍留在本机。

### 专家与技能
- [x] 内置专家入口与自定义专家提示词。
- [x] 本地内置/自定义技能创建、编辑、删除、启停、试用、导入/导出及本地持久化；停用技能不会注入聊天上下文，也不能被工作流执行。
- [x] 自定义专家配置 JSON 导入/导出、导入预览与同名更新；专家团队包可连同成员角色一起导入/导出。
- [x] 专家团队 2–4 名成员并发独立分析，内置/自定义角色指令、主模型汇总、单成员 45 秒超时、一次有限重试、失败隔离与部分结果、用户停止传播、最近 30 次执行轨迹及本地备份恢复。
- [ ] 动态任务规划器与按成员自动拆分子任务、精确 Token/费用统计、进程被杀后的团队任务断点恢复。
- [x] 本地 JSON/Markdown 技能包解析、版本/依赖/权限声明校验、安装前预览、同名更新及导入导出。
- [ ] GitHub 等远程技能仓库拉取、依赖解析、权限授权与沙箱隔离执行。

### 语音与交互
- [x] Android 系统语音识别入口，按住录音、松开停止并发送。
- [x] 麦克风运行时权限请求与识别结束后的资源释放。
- [x] 录音状态缩放/颜色动画与触觉反馈。
- [x] adjustResize 与 IME inset 输入区避让。
- [x] 新消息列表平滑滚动。
- [x] 语音识别部分结果实时回填输入框，松开发起最终识别。
- [x] 语音识别状态反馈、权限提示、错误提示和识别资源释放。
- [x] 语音服务扩展契约与 OpenAI-compatible HTTPS Provider：实现远程语音识别（/audio/transcriptions）和语音合成（/audio/speech），分别配置识别、合成模型。
- [x] 本机语音合成：接入 Android TextToSpeech，支持中英文选择、语速/音调调节、朗读状态反馈与停止；本机合成不由 Nestro 上传文本。
- [x] 远程语音配置：服务地址、识别/合成/实时模型及音色本地保存；API Key 使用 Android Keystore AES-GCM 加密。
- [x] 实时双向语音：OpenAI Realtime-compatible WebSocket、PCM16 麦克风采集/扬声器播放、实时转写与文本发送；远程录音转写与远程 TTS 预览由用户主动触发。
- [x] 构建与自动化测试（CI Run #1190 的 verify 与 Android emulator instrumentation 均通过）；[ ] 实体设备音频路由/回声消除、服务商协议差异、网络中断恢复和交互流畅度验收。

### Phone Agent
- [x] AccessibilityService 注册与用户手动授权入口。
- [x] 用户主动读取当前窗口可访问文本，跳过密码字段。
- [x] 基础可见文本点击能力。
- [x] 临时单次/任务授权、撤销和本地审计。
- [x] 自然语言任务规划与动作编排：模型驱动多轮观察-规划-执行-核验；严格有界 JSON 响应解析；Google、Bing、DuckDuckGo 三路网页检索与多来源交叉核验提示；真机稳定性单独验收。
- [x] 动作前目标校验、动作后结果验证、停止控制和异常恢复：点击/输入目标必须唯一、可见、启用；动作结果逐步核验；停止标记按 taskId 隔离；WorkManager 持久队列、进程恢复和不确定动作人工核对；真机适配单独验收。
- [x] 风险分类与敏感内容保护：统一模型解析、持久化计划恢复和 Android 执行端策略；覆盖高风险提交、登录/验证码/风控、同意条款、系统权限、敏感字段与个人信息值、公开 URL 安全校验、任务目标/进度/日志脱敏；真机适配单独验收。
- [x] Mika 派生隔离虚拟屏代码链路：无线 ADB 配对/TLS、shell helper、Binder 安全交接、独立 VirtualDisplay、指定 display 的 AccessibilityService 窗口读取、应用启动/输入注入、用户主屏接管与任务恢复；源码阶段完成，OEM/目标应用行为仍待统一设备验收。
- [x] Phone Agent 页面内浮动预览与任务胶囊：可拖动窗口、折叠/展开、双击缩放、右下角上拖缩小/下拖放大；预览缩放不改变虚拟屏分辨率/DPI。
- [ ] Phone Agent 真机专项验收：不同 Android 版本、主流浏览器/目标应用、登录与权限弹窗、系统进程回收、厂商后台限制、连续任务稳定性和功耗。

### 工程交付
- [x] GitHub Actions PR 自动化测试；第一版 APK 构建与 artifact 上传延后至十九个模块全部完成后的统一交付阶段。
- [x] GitHub Actions 自动运行 JVM 单元测试与 Android 模拟器测试；当前覆盖 SSE 解码、Phone Agent 计划/动作校验、知识检索排序与文档分块。APK 构建/上传不在模块开发阶段执行。
- [ ] 数据库迁移、集成和 Compose UI 测试。
- [ ] 实体设备安装、升级、数据迁移、权限和功耗验收。

## 未实现能力清单

> **状态口径更正（2026-11-22）：** 本清单最初形成于较早代码阶段，以下旧条目不是当前实时缺口列表；其中部分能力已在后续提交补齐。请以本文件前部最新模块记录及 `docs/FUNCTION_AUDIT.md` 的源码审计为准。只有经当前整合分支逐项复核后仍缺失的项目，才保留为待开发项；已实现项保留历史记录但更新勾选与状态。


### 1. 数据层与本地知识
- Room 数据库 schema 版本升级、迁移测试与异常恢复策略。
- 数据库 schema 版本升级、可回滚迁移、崩溃恢复与备份一致性测试。
- 附件管理、文件引用、完整备份校验和设备迁移。
- [x] TXT、Markdown、HTML、CSV、JSON、XML 等文本导入与本地解析（单文件 2 MB）。
- [x] DOCX、XLSX、PPTX Office 文档文本解析；[x] PDF 文本层提取与扫描页中文本地 OCR（逐页处理、加密/页数/文本量限制）。
- [x] 文档分块、项目内关键词检索与 RAG 上下文来源标注初版。
- [x] SQLite FTS4 全文召回、项目级 BM25 排序、中文 n-gram/子串召回、稀有词权重、长查询覆盖、来源标注与项目隔离。
- [x] SQLite FTS4 + 项目级 BM25 混合词法检索；[x] 本地 BGE-small-zh-v1.5 INT8 ONNX 向量索引与混合排序源码已整合（模型下载后离线运行，不上传知识库内容）；统一 CI/设备验证待执行。
- [x] Phase 3 首项：知识库加入 Room FTS4 索引与 10→11 数据库迁移；检索优先走 FTS，失败或无命中时安全回退关键词检索；迁移测试覆盖旧库升级与 FTS 命中。
- [x] Phase 3 知识库继续补齐：Apache PDFBox 提取文本层；无文本层页面由内置 ML Kit 中文识别器在本机逐页 OCR，进入现有分块/去重/FTS/BM25 索引链路。
- [x] 删除文档时通过 Room 外键级联清理全部知识片段索引。
- [x] Room 12→13 迁移新增项目知识片段向量表；项目隔离查询、文档删除级联和迁移测试源码已补。CI/设备验证仍待统一验收。

### 2. 对话与记忆
- [x] OpenAI 兼容 SSE 流式响应解码：按事件边界合并 data 字段、处理 `[DONE]`、兼容文本分片与 EOF flush，并有单元测试。
- [x] 请求级取消（仅终止对应请求）、180 秒总超时与 90 秒读取超时；网络/HTTP 408、425、429、5xx 最多重试一次，已有流式输出后不重试，避免重复内容。
- [x] 消息编辑、重新生成、失败重试；重新生成保留同一消息 ID 的最多 20 个回答版本，可在版本间切换；每会话草稿持久化。
- [x] 动态上下文预算：最多 40 条历史、48,000 字符；按系统提示词与输出 Token 预留动态裁剪，使用保守 16K 上下文估算与约 4 字符/Token 估算。
- [x] 请求状态持久化：保存会话、输入和已接收分片；应用/进程重建后恢复草稿及中断内容，避免静默丢失。
- [x] 基于本地 Room 消息的项目内跨会话关键词召回初版。
- [x] 自动记忆语义检索、结构化记忆来源/时间/置信度管理与跨项目显式授权。
- [x] 单条记忆编辑、删除、导入/导出、项目级隔离及按项目清空。
- 后台请求状态持久化，切换窗口或 Activity 重建不丢任务状态。

### 3. 项目空间
- [x] 项目创建、重命名与切换。
- [x] 项目会话隔离，历史列表按当前项目过滤。
- [x] 项目级指令仅注入当前项目请求上下文。
- [x] 项目与会话关联数据纳入本地 JSON 备份/恢复。
- [x] 项目删除前统一检查会话、知识文档、记忆与 Agent 任务关联；仅允许删除无关联数据的非默认空间。
- [x] 项目归档与恢复：已实现归档/恢复及启动恢复保护；对应回归测试已提交，待当前 head CI 核验。
- [x] 项目复制：Room 事务复制会话/消息、知识文档/片段、项目记忆、全部产物版本、资源归属和项目自动记忆设置；回归测试已提交。
- [x] 专家/团队/Skills/工作流项目归属：显式全局/项目范围、管理入口、聊天与工作流调用过滤、变更权限校验及损坏时 fail-closed 已接入。
- [x] 项目自动记忆开关独立于全局记忆；旧版全局开关迁移兼容默认空间，并纳入复制及备份/恢复。
- [x] 最近使用项目置顶：按最近使用时间排序。
- [x] 项目级 JSON 导出/导入：覆盖会话/消息/草稿、知识文档/片段、记忆、产物、专家/团队/技能/工作流、项目指令及自动记忆设置；导入新建项目、重映射 ID、知识去重、校验 checksum、限制体积/数量并阻止资源名称冲突。项目包不包含 API Key 和跨项目授权，导入保留本机模型设置与现有授权。
- [x] 跨项目访问授权：方向性 READ/WRITE 独立授权、用户确认入口、即时撤销、持久化审计；知识检索/聊天上下文/工作流调用及资源变更入口按授权执行，默认拒绝。

> 2026-11-22：模块5已完成并锁定（PR #4 / CI #149）。模块6知识库与本地 BGE-small-zh-v1.5 INT8 ONNX 向量检索源码已进入 `integration/19-modules`；Room 12→13 迁移及测试源码存在，统一编译/JVM/模拟器/真机验收仍待执行。

### 4. 专家与多智能体
- [x] 自定义专家配置导入/导出、专家版本历史与差异比较。
- [x] 团队角色指令、2–4 成员并行执行、主模型汇总、单成员超时/重试/失败隔离、停止控制、调用次数上限与执行轨迹。
- [x] 主模型动态生成专家工作包 DAG、成员派发、依赖排序、并行波次与成果交接已实现。\n- [x] 逐工作包持久化 checkpoint、并行成员结果持久化及项目计划恢复代码已接入；编译/测试/进程杀停后恢复仍待统一验证。\n- [ ] 精确 Token/费用预算。
- [x] 专家模板安全导入导出：自定义模板创建/编辑/复制/删除确认、内置模板复制、分类搜索、套用、JSON 批量导入预览/导出；限制文件体积、模板数量、名称/ID 唯一性与技能项目范围，导入持久化采用提交确认。

### 5. Skills 与工作流
- [x] 本地技能 CRUD、启停、试用入口、导入导出和权限声明校验。
- [x] 已验签社区目录拉取、SemVer 2.0 递归依赖求解、冲突回溯、原子安装/更新/卸载/回退已实现。\n- [ ] 可执行代码型 Skill 的独立沙箱执行不在当前提示词型 Skill 运行模型中；插件/代码隔离需单独设计，不能把提示词注入当作沙箱。
- 依赖声明、权限清单、风险提示、隔离验证。
- [x] 两步工作流编辑器初版：支持提示词/专家/技能节点、保存、编辑、删除和顺序执行，前序结果传入下一步。
- [x] 工作流步骤动态添加、删除、上下移动；引擎具备简单条件、跳过条件、单步重复、重试和超时。\n- [x] 条件/审批配置 UI、真实用户审批、parallelGroup 并发执行及步骤顺序恢复代码已接入；并行成员部分失败时保留成功成员检查点。
- [x] 工作流逐步骤持久化 checkpoint 与定义/输入指纹校验已实现；审批弹窗具备授权一次/当前任务全部授权/拒绝三种决策。\n- [ ] 可视化 DAG 连线、进程重启后的自动续跑、审批等待时持久任务状态 `WAITING_FOR_USER` 接线仍待补齐；当前暂停/继续门闩仅在当前进程内有效。

### 6. MCP 与模型路由
- 远程 HTTPS Streamable HTTP MCP 服务发现、工具 schema、逐工具授权、调用预览、审计与撤销已实现；Android 不启动任意本地 stdio MCP Server。
- 每个工具独立授权、调用预览、审计与撤销。
- 不假定 Android 可直接运行任意本地 MCP Server。
- 多模型服务商、多 Key、路由、故障切换。
- Token、费用、预算与速率限制统计。

### 7. Git 与 AI 开发工作台
- GitHub/GitLab/Gitee 用户授权与凭据安全存储。
- 仓库浏览、搜索、clone、pull、push、commit、branch。
- 手机端代码编辑、diff、冲突处理、提交历史与回滚。
- AI 补丁预览、变更影响说明、权限检查。
- 隔离构建/测试、用户明确批准、快照和回滚。
- 不允许 AI 静默扩大权限或直接执行未审查任意代码。

### 8. Phone Agent 完整化
- [x] 风险规则统一：模型动作解析与 Accessibility 执行边界共用高风险动作/敏感字段/敏感值策略；敏感值在执行端再次拦截。
- [x] 点击目标执行前唯一性校验：同名目标不唯一、目标不可见或不可用时拒绝点击，避免误操作。
- [x] 停止请求按 taskId 隔离，停止一个任务不会污染后续任务；入队恢复时清理该任务的旧停止标记。
- 单一对话入口解析目标、规划步骤并选择可用动作。
- 普通低风险动作连续执行，不为每一步弹窗。
- 用户单次/任务授权、随时停止、授权过期和撤销。
- 新出现的高风险动作必须重新交还用户确认。
- 执行前检查目标，执行后检查结果，异常/偏离目标自动暂停。
- 支持 Android Intent 和公开 API 优先，Accessibility 作为受限补充。
- 日志、可撤销操作和敏感数据本地保护。
- **目标导向的线上代办闭环**：用户只描述目标，Agent 自行拆解搜索、比较、选择、操作、核验，不要求用户逐步指定点击路径。
- **购物/订购任务示例**：收到“买一杯咖啡”等请求后，先识别商品、规格、预算、位置/配送要求；在可访问的多个平台或网页检索同款/相近商品，比较商品价格、配送费、优惠、预计送达时间、库存和商家可信度，按最终到手价与用户偏好排序。
- **跨平台决策**：优先使用可用的公开 API、网页搜索/网页操作和 Android Intent；必要时再打开对应 App，通过无障碍补足界面操作。不能假设每个平台都开放 API，也不能伪造价格、库存或优惠。
- **执行闭环**：选定方案后进入对应 App/网页，核对商品与规格，完成加入购物车、地址/配送方式选择等低风险步骤；每一步检查页面状态和实际结果，失败时重试或换路径。
- **付款边界**：提交订单、支付、转账及任何不可逆或高风险动作前，必须展示商家、商品、规格、数量、优惠、配送费、最终金额和收货信息，由用户明确确认；不得代替用户输入支付密码、验证码或绕过平台安全验证。
- **任务透明度**：向用户展示关键比较依据、候选项、最终选择理由、已完成步骤和待用户处理步骤；用户可随时暂停/取消，任务状态可在切换会话或应用重启后恢复。
- **能力边界与降级**：登录、验证码、风控、人机验证、页面不可访问或信息无法核实时，明确暂停并请求用户接手，不猜测、不绕过、不声称已完成。
- **其他同类任务**：订餐、订票、预约、商品搜索与比价、资料查找和网页表单等复用同一套“目标理解→信息收集→决策→执行→核验→用户确认”任务引擎，而不是各做一套硬编码脚本。

### 9. 后台、语音与产物
- [x] WorkManager 按任务类型持久化执行，避免无意义常驻（已有串行任务队列、进度持久化、中断状态保留与恢复入口；任务状态通知和用户主动任务前台服务；异常后保留待核验状态，避免自动重放不确定动作；进程重启后的真机恢复与厂商后台适配待验收）。
- [x] 通知、任务恢复、失败重试和用户取消（通知栏任务状态、通知权限引导、通知栏停止请求和取消状态回写；任务页支持人工核对后继续/重试/取消；完整任务中心仍属后续产品增强）。
- [x] 原生语音输入、本机 Android TTS、可选 OpenAI-compatible HTTPS 语音识别/合成 Provider、Android Keystore 加密配置，以及 Realtime WebSocket + PCM16 双向音频链路；云端服务均由用户主动配置并触发。
- [x] 作品/产物库、文件导出、版本和分享（已实现按项目隔离的本地 Markdown 作品库、助手回复一键保存、手动创建、名称搜索、版本递增与历史版本恢复为新版本、预览、分享、SAF 导出、二次确认删除、项目复制保留版本号/时间、项目包导入校验及完整备份/恢复；索引缺失/损坏、文件缺失/大小不符、跨项目伪造引用均失败关闭；单元与 Android UI 回归测试已补充，待全部模块完成后统一执行）。
- [x] 能力目录/社区、安装来源和权限透明（HTTPS 目录读取、固定 RSA 公钥验签、技能包 SHA-256/名称/版本核验、权限/依赖预览、用户确认安装、防降级；新增原子安装/更新、同名本地技能防覆盖、最多5份历史快照、卸载与回退、依赖存在/精确及基础范围版本检查、循环依赖拒绝和更新兼容性检查；正式可信社区源、公钥撤销、递归依赖自动安装和统一 CI/设备验收仍待完成）。

### 10. 质量、性能与适配
- 单元测试：已有模型请求策略、SSE 解码、技能/专家/工作流、Phone Agent、知识分块/检索与 tokenizer/vector 排序 JVM 覆盖；Android 自动化补充 Room 迁移、PDF OCR、DOCX/XLSX/PPTX/XML 解析、向量项目隔离/级联及 BGE ONNX 端侧推理 smoke test。当前模块6最终 CI 待执行完毕，不能提前标记为通过。
- Compose UI：已编写主界面、会话历史、项目空间、语音说明页、知识库、工作流、数据设置、模型管理入口及返回工作台的导航冒烟测试；覆盖无模型发送提示、模型配置必填校验、连接测试禁用态、横屏导航回归，以及语音输入按钮的角色和启用/禁用语义；新增 320/393/673/840/1200dp 窗口宽度下主导航、项目空间、能力目录、知识库和产物库页面测试。知识库、产物库、能力目录页面使用单一纵向滚动容器，避免窄屏内容被截断及嵌套 LazyColumn 的无限高度测量问题。以上 Android UI 测试尚待实际执行；复杂业务交互、网络失败/权限异常态仍待补齐。
- 性能基线：已编写 Room 会话列表基准测试，固定 250 个会话/3,000 条消息并记录分页与内容搜索耗时；基准尚未实际运行。冷启动、滚动帧率、内存、CPU、网络唤醒和耗电指标留待最终设备阶段采集。
- 大会话列表分页/惰性加载：历史页按项目分页、数据库侧搜索、摘要限制 240 字符；启动仅加载会话摘要与项目，当前会话按需读取完整消息；元数据更新不清空未加载消息；Room 初始化后保留旧版完整快照，避免摘要缓存覆盖历史；停止每次状态变化时重复序列化整份历史。
- 手机、平板、折叠屏：导航抽屉按窗口宽度采用紧凑/标准/展开宽度；窄屏手机改为覆盖式抽屉，避免抽屉打开时主内容被压缩至不可用宽度；主导航、项目空间、能力目录、知识库和产物库均新增 320/393/673/840/1200dp 窗口宽度回归。知识库、产物库、能力目录采用单一纵向滚动容器；其余页面的完整布局检查及中英文资源化仍待补齐。
- 无障碍：语音输入按钮增加状态、角色和禁用态语义，并新增按钮角色及启用/禁用语义回归测试；系统语音识别、无障碍权限及厂商后台策略仍需最终专项验证。

## 产品级交互约束

1. **一个自然语言入口**：专家、团队、Skills、工作流、MCP、项目记忆与 Phone Agent 最终都从统一对话入口调度。
2. **项目默认隔离**：跨项目调用必须由用户主动允许。
3. **任务不随窗口切换而停止**：对话 UI 与任务生命周期分离。
4. **用户自选模型**：不建立 Nestro 托管推理后端或强制账号体系。
5. **本地优先**：API Key、记忆和文件默认留在设备；向外发送内容前应清楚知道目标服务。
6. **安全边界**：AI 可以自主规划，但不能自行扩大权限。支付、转账、密码、验证码、敏感数据披露等高风险操作必须用户确认。
7. **性能约束**：不为未使用的能力启动常驻轮询；网络与文件任务不占主线程；用实测数据验证功耗和流畅度。

## 接下来怎么做：从代码初版走向第一版产品

> 目标不是只修底座，也不是修完底座才开始做功能。采用「底座门槛 + 模块完整开发 + 集成验收」并行推进。每个模块都要从数据结构、业务逻辑、界面交互、权限安全、异常恢复到测试形成闭环。

### 阶段 0：底座达到可承载标准

这是持续工程任务，不是一个做完就永远结束的阶段。

- [ ] 数据库：逐项审计 Room Entity/DAO/外键/索引/迁移，补齐迁移测试、事务边界、损坏与恢复策略。
- [ ] 数据安全：API Key、记忆、附件、备份、日志的敏感信息边界；默认最小权限；导入数据校验。
- [ ] 任务引擎：统一任务状态机、队列、暂停/恢复/取消、超时、重试、进程重启恢复与幂等保护。
- [x] 网络层策略：连接/读取/总超时边界、取消不重试、瞬态错误有限重试与指数退避、HTTP 错误分类；SSE 解码与请求取消已由现有实现覆盖。
- [ ] 稳定性：统一异常处理、日志分级、崩溃信息、状态一致性和资源释放。
- [ ] 测试基础：建立可重复运行的单元测试、数据库迁移测试和 CI 验证基线。

**阶段门槛：** 核心数据不能因普通异常或应用重启而静默丢失；任务状态不能被误报为完成；敏感权限与外发行为有明确边界。

### 阶段 1：把现有核心模块做完整

#### 1A. 对话与模型层
- [ ] 消息编辑、重新生成、重试、超时取消、失败后恢复。
- [ ] Token 预算、模型上下文窗口适配、动态历史裁剪。
- [ ] 后台请求与 UI 生命周期解耦；切换会话、旋转或重建页面不丢请求状态。
- [x] 多组模型服务商配置与多 Key 本地管理、手动切换；每组 Key 独立使用 Android Keystore AES-GCM 加密。
- [x] 用户主动开启的按配置顺序故障切换；流式请求仅在尚未输出内容时切换，避免重复输出。
- [x] 本地按模型配置统计成功请求次数与估算 Token（按字符数估算，不代表服务商账单精确用量）；配置元数据与估算统计纳入备份，API Key 不进入备份。

#### 1B. 项目、记忆与知识库
- [x] 项目创建、复制、归档、恢复与安全删除；模块5已完成验收并锁定（PR #4，合并提交 `dba49675`，CI instrumentation 90/90）。
- [x] 记忆/知识库/专家/技能/工作流/产物的项目级关联与跨项目 READ/WRITE 授权；项目资源按作用域隔离，授权可审计、可撤销。
- [x] DOCX/XLSX/PPTX Office 文档文本解析；[ ] PDF 文本解析。
- [x] SQLite FTS4/BM25 全文检索、项目级本地 BGE 向量索引与混合检索排序（最终 CI 待确认）。
- [ ] 记忆来源、时间、置信度和可追溯管理；跨项目访问授权。
- [ ] 附件管理、文件引用、完整备份校验、版本兼容和设备迁移。

#### 1C. 专家、团队与 Skills
- [x] 专家创建、编辑、版本、启停、导入导出和生命周期管理（含依赖工作流/团队处理、项目范围校验；统一任务轨迹仍待后续完善）。
- [x] 团队角色配置、并发协作、部分失败容错、有限重试、超时隔离、主模型汇总、运行历史和备份恢复。
- [ ] 动态子任务规划/派发、精确 Token/费用统计、进程级断点恢复。
- [x] 本地技能创建/编辑/删除/启停/试用、技能包导入导出、声明权限与依赖校验、备份恢复。
- [ ] GitHub 远程仓库导入、依赖解析、真实权限授权、沙箱执行与安全隔离测试。
- [ ] 所有专家/技能调用都进入统一任务轨迹，并遵守项目隔离与权限边界。

#### 1D. 工作流引擎
- [x] 工作流编辑器与顺序执行引擎初版（两步节点）；动态节点编排待补。
- [ ] 条件、循环、变量、并行、审批和人工接管节点。
- [ ] 工作流运行状态持久化、暂停/恢复、断点续跑、重试与执行日志；当前仅支持前台顺序执行和停止。
- [ ] 工作流调用专家、Skills、MCP 与 Phone Agent 时复用统一权限和任务系统。

### 阶段 2：外部工具与开发能力

#### 2A. MCP 与工具权限
- [x] 远程 Streamable HTTP MCP 传输、协议版本协商、tools/list 服务工具发现与 schema 缓存。
- [x] 每个服务/工具单独授权；调用前参数预览、调用后本地审计、权限即时撤销。
- [x] Bearer 凭据 Android Keystore 加密；请求超时/取消、断连错误收口、不可信工具输出隔离。
- [x] Android 端仅连接用户配置的 HTTPS 远程服务，不承诺启动任意本地 MCP Server；stdio 与模型原生 tool-call schema 适配不在本模块范围内。

#### 2B. Git 工作台
> **代码阶段已完成并锁定：** PR #10 已合并至 `refactor/phase-2-architecture`，合并提交 `2e7ca199`。支持 GitHub/GitLab/Gitee PAT 授权与 Android Keystore 加密、仓库搜索/克隆、JGit 分支/Fetch/Pull/Push/Commit、手机端文件浏览编辑/新建删除、Diff/冲突处理/历史/反向回滚；API 超时与连接失败重试、Git 传输超时、失败克隆清理、大仓库可用空间预检均已实现。断点续传、增量/浅克隆作为后续性能增强，不阻塞当前模块锁定。**按既定顺序，Kotlin 编译/JVM/仪器测试、模拟器与真机验收统一留到十九模块代码完成后；目前尚未执行。**
- [x] GitHub/GitLab/Gitee 授权与凭据安全存储（PAT）。
- [x] 仓库浏览、搜索、clone、fetch、pull、push、commit、branch、历史和安全反向回滚。
- [x] 手机端代码查看/编辑、新建/删除、diff、冲突处理和变更审查入口。
- [x] 基础网络/状态/大仓库保护：API 连接失败重试与超时、Git 传输超时、失败克隆清理、可用空间预检、未提交修改保护和冲突处理。

#### 2C. AI 开发工作台
> **源码已整合到 `integration/19-modules`；统一编译与验收尚未执行。** AI 补丁先解析、审查、逐文件批准，再通过本地事务应用；远程 GitHub Actions 仅在用户主动启动后运行。
- [x] 严格补丁 JSON/路径/体积校验、逐文件 diff 预览、SHA-256 审查摘要。
- [x] 敏感路径、凭据、签名材料和应用私有元数据拦截；上下文文件筛选与发送前脱敏。
- [x] Manifest 权限/导出组件、Gradle 依赖声明、CI 危险配置和常见敏感 API 静态检查；Kotlin/Java 同文件有界数据流启发式分析。
- [x] 用户逐文件明确批准后应用；应用前快照、外部修改冲突检测、事务日志、进程中断恢复及回滚保护。
- [x] GitHub Actions 只读预检、任务/Job/Step 状态、有限日志和产物解析；JUnit XML 与 CycloneDX 1.5 SBOM 双配置来源验证。
- [x] Android CI 增加 Debug/Release 依赖树导出、SBOM 生成与验证产物上传；Workflow 仅手动触发完整验收。
- [ ] 运行 Kotlin 编译、JVM/Python 测试、真实 GitHub Actions Workflow；核验完整 SBOM 与产物回传。
- [ ] 禁止静默扩大权限或自动执行模型生成的脚本、构建钩子和任意代码。

### 阶段 3：完成 Phone Agent 的真实任务闭环

- [ ] 统一自然语言目标理解、澄清、规划、信息收集、决策、执行和核验。
- [ ] 跨平台搜索与比价：商品/服务、最终价格、配送费、优惠、库存、时效和商家信息；标注数据来源与实时性。
- [ ] 优先使用公开 API、Android Intent 和可访问网页；Accessibility 作为有边界的补充。
- [ ] 低风险动作可连续执行；支付、下单、预订、转账、敏感信息披露等高风险动作必须用户确认。
- [ ] 登录、验证码、人机验证、风控和无法核验页面支持安全接管，不绕过验证。
- [ ] 动作前目标检查、动作后结果检查、失败回退、幂等保护、暂停/取消和任务通知。
- [ ] 任务跨会话切换、应用重启和网络波动保持状态一致。
- [ ] 在真实设备和代表性 App 上建立兼容性矩阵，逐项验收。

### 阶段 4：语音、后台、产物与体验补齐

- [x] 语音能力补齐原生识别、本机 TTS、可选远程 STT/TTS 与实时双向语音；构建、自动化测试、设备兼容性和端到端服务商验收仍待完成。
- [ ] 后台长任务通知、用户可见任务中心、失败重试与取消。
- [x] 作品/产物库、文件导出、版本记录、分享和清理策略（代码与回归测试已补齐；统一验收阶段执行测试）。
- [x] 能力目录/社区代码已补齐：固定 RSA 验签、SHA-256 包校验、权限/依赖预览、用户确认、原子安装/更新/卸载/回退；新增 SemVer 2.0、递归依赖、约束冲突回溯求解、独立信任根签名撤销清单与受影响技能自动停用。正式可信源及信任根/撤销 URL 需由运营方配置；测试未执行。
- [x] 第19模块媒体 AI 代码已补齐：OpenAI 官方 gpt-transcribe 音频转写（全文模式）与独立视觉模型、OpenAI-compatible 图片理解；兼容 whisper-1 分段时间戳模式（旧模型 2027-02-26 停止服务）；场景识别/音频处理仅在填写真实专属端点后声明能力；上传字节级进度、WorkManager 持久化任务、进程恢复、取消与结果回写均已实现。真实 API Key 联调未执行，需用户配置凭据；测试统一延后。
- [x] 第19模块社区技能管理：固定 RSA 公钥验签、技能包 SHA-256 校验、社区搜索、权限/依赖预览、用户确认安装、原子更新/卸载/回退、防同名本地覆盖；新技能默认停用；公钥轮换需确认且新目录验签成功后才保存。正式可信社区源、公钥撤销、递归依赖和统一验收仍待完成。
- [ ] 手机、平板、折叠屏布局，中英文与无障碍体验。
- [ ] 冷启动、内存、CPU、帧率、网络唤醒和耗电优化。

### 阶段 5：第一版产品冻结与发布验收

只有前面核心模块达到约定验收线，才进入第一版候选构建：

- [ ] 全模块集成测试、数据库迁移测试、网络异常测试和 UI 自动化测试。
- [ ] 从全新安装、旧版本升级、备份恢复到卸载重装的完整数据链路测试。
- [ ] 核心任务端到端测试：对话、知识检索、专家协作、Skill/工作流、MCP、Git、Phone Agent。
- [ ] 真机验收：权限、通知、后台恢复、语音、无障碍、网络、性能和功耗。
- [ ] 安全审查：API Key、敏感数据、工具权限、AI 补丁和高风险操作确认。
- [ ] 冻结功能范围、修复阻断问题、生成第一版候选包和变更说明。

**第一版不是现在马上构建。** 现在的 debug APK/CI 只用于开发阶段验证，不等于产品第一版。

### 执行规则

1. 每次开发以一个可验收的功能闭环为单位，不只交付页面或接口。
2. 底座问题优先修，但不因此无限期推迟知识库、专家、工作流等模块开发。
3. 一个模块完成后先做模块测试，再接入统一入口，最后做跨模块集成。
4. 路线图状态按代码、测试、构建、真机四层记录，不把“写了代码”标成“已完成”。
5. 每次提交前同步更新模块状态和已知限制；不为了让进度好看而提前勾选。
6. 不提前冻结第一版，不为了赶构建删掉核心能力或安全边界。

## 验收规则

- 代码合并不等于功能验收。
- CI 成功只证明云端编译与打包通过。
- 涉及键盘、麦克风、无障碍、后台任务、通知、耗电和厂商系统限制的功能，必须在实体设备验证。
- 路线图中的未实现项只有在代码、测试和验收证据齐全后才能移入「已实现」。


## Cross-cutting requirement: low-cost chat and on-demand task execution

The Phase 2 refactor must preserve a strict separation between ordinary conversation and task execution. The composer must visibly indicate when the active model has not declared support for the selected reasoning mode; a user selection must never imply that the provider received it. Model capability is configured per model, defaults off for legacy entries, and unsupported request fields are omitted. Ordinary chat defaults to Light (轻量思考), with four user-selectable levels: Off (关闭思考), Light (轻量思考), Medium (中度思考), and Heavy (重度思考), does not automatically start Agents or load tools, and uses a compact context budget. Medium/Heavy reasoning and task-only capabilities are opt-in. For an explicit task, resolve the minimum required capability set and load only those tools; never enumerate or inject the full tool catalog on every turn. Task context must be scoped to the task and released afterward. See [the detailed architecture and acceptance gates](PHASE2_CODE_MAP.md#2026-11-18--lightweight-chat-controllable-reasoning-and-precision-tool-dispatch-product-wide-refactor-requirement).


## Explicit task-mode gate

The chat composer exposes **普通聊天** as the default and a separate **任务执行** opt-in. Ordinary chat must not invoke Phone Agent planning or create task records based on keyword matches. Phone Agent planning is reachable only while task mode is explicitly enabled; a GENERAL classification still creates no task. A persisted expert-team selection also does not authorize team execution during ordinary chat.

Task authorization and the related team/expert/skill selections are captured at message-send time. Composer changes after sending cannot retroactively turn an ordinary chat into a task or change the resources used by an in-flight request.


Ordinary chat must not inject selected expert role prompts, expert profile skill context, or selected skill prompt assets solely because a prior selection persisted. Those prompt assets are eligible only after explicit task mode is enabled.


Every model invocation must receive a bounded history window sized against its system prompt and output reserve. Keep full conversation history for persistence/UI; trim only the request payload. Apply the same budget to each expert-team member call as to normal streaming.


All request-affecting UI state—including active expert IDs, project instructions, automatic-memory preference, and user identity—is snapshotted before launching the chat coroutine. In-flight requests must not observe later UI changes.


## Task-scoped capability dispatch — implementation status

The shared Agent runtime now supports short-lived allow-listed tool sessions. Phone Agent exposes only the typed capability needed by the current action and releases it immediately after the step. This is an implemented foundation. Module 13 now adds remote Streamable HTTP MCP discovery and encrypted Bearer-token storage; OAuth-based provider credential leasing and model-native tool invocation remain separate integration work. Ordinary chat remains outside this execution path. Acceptance still requires CI for the exact PR head and dedicated integration tests for expert/workflow/tool routing.


Phone Agent's action-history compactor now retains the most recent 12 distinct action results within a fixed 1,000-character budget, rather than preserving only the oldest entries. This keeps the latest execution evidence available to the next planning call without growing the prompt.


Usage accounting now stores estimated token counts directly, attributes each request to the actual selected Registry model (including failover), and includes Phone Agent planning calls. Streaming fallback estimates use the same bounded-history estimator as non-streaming calls. Provider-reported usage and estimates remain separate; background automatic-memory extraction still needs usage callback integration.


Automatic-memory extraction now contributes its model usage to the same provider-vs-estimate accounting as chat, experts, workflows, and Phone Agent. Usage attribution uses the selected Registry model ID while retaining legacy profile identifiers. Chat callbacks merge against the latest persisted stats rather than stale UI state.


Security invariant: task-scoped capability selection is not authorization. Every selected tool still passes through AgentPermissionRouter; external writes, authentication, financial actions, and device actions remain subject to their separate confirmation/grant rules. Regression coverage verifies that loading an external-write capability does not execute it or bypass WAITING_FOR_USER.


Tool sessions now snapshot the exact handler as well as the selected capability definition. A later registration using the same tool ID cannot retarget an already-open task lease; new sessions use the new registration. JVM regression coverage verifies this isolation. Explicit revocation of already-open leases and credential expiry remain separate follow-up work.


## 2026-11-18 — Phase 2 lease lifecycle closeout work

- Implemented revocable task-owned capability leases and short-lived task credential lease primitives.
- Task pause/cancel and handler exit now revoke leases; Phone Agent action execution uses the shared lease registry and releases each action lease in `finally`.
- Added JVM tests for lease revocation and credential access expiry/revocation.
- Remaining Phase 2 work: connect expert-team and workflow steps to explicit minimum capability sets through the shared tool boundary; complete the capability registration/routing contract; verify the latest PR head with CI. Module 13 now implements remote MCP discovery and encrypted Bearer-token credentials; remaining Phase 2 work is expert-team/workflow routing, OAuth provider credential leasing, model-native tool invocation, and final unified CI after all 19 modules are complete.
- APK construction, installation, and device acceptance remain deferred until all feature code across the 19 modules is written and the final unified acceptance stage begins.



## 2026-11-18 — Unified capability registration and cross-feature catalog

- Added a process-wide tool catalog shared by Phone Agent, expert-team and workflow execution.
- Added operation-scoped capability declarations, with expert-member and workflow-step resolution. Only the declared minimum tool set is leased for that operation; missing tools or missing tool-aware adapters fail closed.
- Added tests for member/step tool isolation and lease release.
- The current expert/workflow adapters still perform model-only calls and have no declared tool sets by default. Model-native tool invocation and OAuth-based remote credential adapters remain follow-up integration work; MCP remote discovery and encrypted Bearer-token storage are implemented in module 13 and must not be described as outstanding.
- APK generation, installation and real-device acceptance remain deferred until all feature code for all 19 modules is complete, followed by one unified final acceptance.


## 2026-11-18 — Phone Agent host-independent execution environment

- Phone Agent now depends on a host-independent `PhoneAgentExecutionEnvironment` contract; the current AccessibilityService is the existing adapter.
- Agent execution, WorkManager availability checks, and UI stop requests use one active-environment provider. Detaching the selected environment leaves no implicit fallback to the user's real screen.
- 该条是 2026-11-18 的阶段记录，已被 2026-11-22 feature/phase2-optimization 源码覆盖：已从 wangzan101/Mika 选择性复用隔离虚拟屏、shell helper、无线 ADB 与任务交接实现，并保留 Apache-2.0 许可证/NOTICE；具体范围见 docs/PHASE2_CODE_MAP.md。
- This does not change the unified final-acceptance rule: no APK build or device acceptance until all 19 modules' feature code is complete.


## 2026-11-21 模块12：双模型对比开发记录

- 新增双模型对比页面：从统一模型目录选择两个不同且可用的模型，共享同一条用户指令与可选系统指令。
- 新增并行请求协调器：两个模型独立发起流式请求，独立显示增量结果；仅在所选模型自身的多个 Key 之间按既有策略尝试，不跨模型静默切换。
- 一侧请求失败时保留另一侧结果；停止操作会取消协程并主动断开两侧活动 HTTP 连接。
- 宽屏并排展示、手机纵向展示；支持单侧回答复制、清空和重新发起对比。
- 新增模型选择/重复选择/不可用模型/空提示词/长度限制 JVM 测试源；尚未执行测试。
- 当前状态：功能代码已补齐并完成静态源码/结构复核，已快进合并锁定至 `refactor/phase-2-architecture`（commit `5868d6d`）；Kotlin 编译与 JVM 测试尚未执行；不启动模拟器或真机验收，按十九模块统一验收安排执行。


## 2026-11-22｜魔搭 OAuth 链路代码补齐（待部署配置与统一验收）
- 新增 `services/modelscope-oauth` Node.js 服务：OAuth Authorization Code、OIDC 用户信息校验、服务端 Secret、短时 state / one-time handoff、回调白名单、限流与 HTTPS 部署约束。
- Android 增加魔搭 OAuth 入口、Deep Link 回调、handoff 兑换、Keystore 密钥保存和 Provider/API Key 自动登记。
- 部署方仍需注册魔搭 OAuth 互联应用、注入 Client ID/Secret、配置精确 Redirect URI 和 HTTPS 服务域名；Android 构建通过 Gradle 属性 `modelscopeOAuthBackendUrl` 指定服务地址。
- OAuth access_token 是否拥有 API-Inference 权限仍需真实账号联调确认；在确认前不宣称推理调用已打通。
- 本轮未运行构建、单元测试或真机测试，模块2未验收、未锁定。

## 五、报表同步时发现的仓库状态差异（2026-11-22）

- 当前整合基线仍是 `integration/19-modules`；本次先后提交了本节基线同步与 README 指针更新。后续核验必须使用最新分支 HEAD，不能沿用更新前的 SHA。
- 当前 GitHub 仍显示两个开放 Draft PR：
  - PR #1 `feature/knowledge-retrieval-ranking` → `main`，未合并。其 PR 描述仍写着“无语义向量检索、无扫描 PDF OCR”等旧限制，与当前整合路线图中已登记的本地 BGE 向量和 OCR 实现不一致；因此 PR 描述不能作为整合分支当前功能状态。
  - PR #3 `refactor/phase-2-architecture` → `feature/voice-system`，未合并且保持 Draft。PR 描述和代码地图记录的是分层重构批次，不应把它误判为十九模块功能都未进入整合基线，也不能把它视为已合并。
- PR #2 已关闭但未合并。PR #4 至 #9 的历史合并目标为 `refactor/phase-2-architecture`；当前该分支相对 `integration/19-modules` 为 behind 739、ahead 0，因此其分支提交已包含在当前整合基线中。仍需按当前源码判断后续是否有覆盖/替代实现，不能仅凭 PR 的 merged 字段推断功能验收完成。
- 本次检索到的功能分支仍有多个（包括模块5/6/7/8/9/10/11/12/13/14/15/17/18、模块2 OAuth、phase2 optimization、staging 修复等）。分支仍存在不等于未整合；必须逐分支比较当前整合分支与功能分支的 ahead/behind、差异文件及关键实现内容。
- 因此，本轮只完成了“报表补录与同步”，尚未完成“所有功能是否已合并”的提交祖先/差异证明，也尚未完成十九模块代码整体性审计。


## 六、2026-11-22 首轮源码与分支核对记录（未完成全量审计）

### 分支差异核对

- 当前整合分支与 `feature/module-18-calendar`、`feature/module-17-smart-editing` 的比较显示：这两个功能分支没有领先于整合分支的独有提交；抽查的日历调度器与媒体渲染器文件在两边 blob 完全一致。此前“模块19仍在 feature/module-18-calendar、未合并”的旧描述已不适合作为当前状态依据。
- 抽查 `feature/module-9-voice-system`、`feature/module-15-ai-coworkbench` 的关键实现文件，与整合分支 blob 完全一致。
- `feature/module-6-knowledge-base`、`feature/module-13-mcp-tool-center`、`feature/module-14-github-workbench` 虽然与整合分支提交图分叉，但抽查的关键文件在整合分支已有更新版本：模块6采用 attention-mask masked mean pooling，而旧分支仍使用 CLS 向量；模块13整合分支增加损坏审计原文隔离保留；模块14整合分支增加凭据保存失败时的安全回滚与失败显式提示。这些差异不能误判成“功能未合并”，反而说明整合分支包含后续修正。
- `feature/module-8-phone-agent` 的 4 个差异文件已逐项核对：Android instrumentation 脚本、module-8-ci.yml 和 gradle.properties 与整合分支 blob 完全一致；android.yml 在整合分支有更新版本。该分支没有需要回合并的独有代码/CI 修复，不应把旧版 Workflow 覆盖回来。
- 当前已确认 PR #1/#3 仍开放 Draft；但 PR 状态和分支图不能代替完整文件级审计。全量分支核对尚未完成。

### 最近新增需求的源码初查

| 需求 | 当前源码证据 | 首轮判断 |
|---|---|---|
| 创作与手机执行混合调度 | `data/ModelLedToolDispatcher.kt` 顶层调度提示明确把创作、专家协作、手机执行视为可组合能力，描述多轮交替；`NestroApplication.kt` 注册 `phone_agent.execute`、`phone_agent.wait`、`media.project.assets`；`PhoneAgentExecutor.kt` 有 `DownloadAsset` 动作；`PhoneAgentService.downloadAsset()` 下载后调用 `MediaProjectStore.importAsset()` 并返回素材 ID；创作可用素材目录查询实际素材 | 核心链路代码已存在，不能重复新建。仍需核查模型工具参数/授权、实际任务入队、等待终态、文件读取/引用和多轮端到端测试 |
| 专家团自主组织 | `ExpertProjectPlanExecutor.kt`、`ExpertTeamExecutionCoordinator.kt` 有 DAG、依赖、并行工作包、结构化交接、检查点及恢复数据；`NestroApp.kt` 接入项目计划执行 | 主体代码已存在；继续核查用户手动模式与自动模式入口、工具调用闭环、断点恢复边界 |
| Mika 虚拟屏移植 | `phone/virtualdisplay/mika/` 下有 ADB、Binder helper、VirtualDisplay、执行环境适配；`PhoneAgentPage.kt` 接入实时帧浮动预览与触控输入 | 底层移植和页面接入已存在；当前 UI 与用户指定交互不完全一致，需修正而非重写 |
| 虚拟屏具体交互 | 当前 `PhoneAgentFloatingPreview` 有标题栏“展开/收起”；点击标题栏折叠/展开；双击画面切换缩放；画面右下角 26dp 区域拖动时按纵向位移缩放；画面点击用于向虚拟屏注入触控 | 与用户要求的“无角标/按钮/手柄、点击窗口放大/缩小、角部上拉缩小下拉放大”不完全一致：标题栏文字和折叠入口仍存在；点击画面会执行触控而非缩放；应调整交互入口和手势映射 |
| 工作流审批 | `NestroApp.kt` 有“授权一次 / 当前任务全部授权 / 拒绝”；任务级授权变量在单次工作流执行作用域内；未选择时 `CompletableDeferred.await()` 等待；弹窗 `onDismissRequest = {}`；`WorkflowExecutionCoordinator.kt` 传递审批回调并持久化工作流检查点 | 未发现默认自动拒绝；当前任务授权范围已在 UI 执行作用域内。但弹窗目前不可通过关闭手势退出（dismiss handler 为空），没有“关闭但保持等待”的独立入口；还需核查等待审批期间任务状态是否持久化为 WAITING_FOR_USER，而非仅 UI 挂起 |
| 记忆关联本地缓存 | 当前首轮抽查尚未确认独立的全局/项目/会话缓存及记忆变更失效机制 | 待专项检索，不得把 BGE 语义检索/embedding cache 或 Prompt Cache 当作用户要求的记忆关联缓存 |

### 本轮结论

- 近期新增的混合调度不是从零开始：整合分支已经有主调度提示、Phone Agent 下载动作、自动导入媒体项目、等待任务和素材目录查询等实现线索。后续重点是核对链路是否真正闭环和测试是否覆盖，不另起一套调度器。
- Mika 虚拟屏底层已经移植，但浮动窗口交互与用户最新明确的手势要求存在可定位差异。
- 工作流审批已具备三种决策及任务级授权；“关闭弹窗但不作授权/拒绝”与等待状态持久化仍需补审。
- 本节只记录首轮抽查，十九模块逐项审计、完整分支差异、冗余代码扫描、测试执行均未完成；不能据此宣布项目收口。



## 2026-11-22 — NESTRO 核心能力边界（用户确认，优先级最高）

NESTRO 不是单纯聊天、单独的 Phone Agent 或媒体编辑器，而是以**文本主模型作为统一理解、判断和调度入口**的智能工作系统。核心验收目标为：听得懂、看得懂、能创作、能执行、会协作。

### 感知与理解
- **音频**：能够接收/录制音频，调用已配置的语音识别或音频理解模型，提取转写文本、说话内容/意图及任务需要的结构化结果；语音合成/朗读属于输出能力，与识别能力分别核验。
- **图片**：能够把图片及必要上下文交给具备视觉能力的模型，理解图像内容并把结果回交主模型；不能仅因 Provider 支持 Chat Completions 就假定其一定支持视觉输入。
- **视频**：能够按实际能力调用视频理解/分析模型；视频生成则调用独立视频生成模型。视频理解、视频生成、视频播放/素材导入是不同能力，分别核验。
- 主模型必须根据当前 Provider/模型配置及能力声明选择可用模型；不支持、未配置或调用失败时如实反馈，不伪造已识别/已生成结果。

### 创作、执行与混合调度
- 文本主模型负责意图识别、任务拆解、能力选择、顺序/并行依赖和最终汇总；它可以直接完成轻任务，也可以调专家团、Phone Agent、知识库、MCP、媒体模型或项目工具。
- **创作层**：专家团按专业分工产出脚本、文案、设计方案、视频/媒体任务及其他项目成果；支持用户手动选专家，也支持主模型根据项目目标自主选人、拆分工作包、设定依赖、并行执行、交接和最终审核。
- **执行层**：Phone Agent 在隔离虚拟屏/手机环境中完成网页或 App 操作、素材搜索下载、文件整理和其他被允许的设备动作；将真实执行状态、文件和项目素材 ID 回传主模型。
- **混合/穿插式调度**：创作与执行不是二选一或固定顺序。创作发现缺素材→主模型派 Phone Agent 搜索/下载→校验并导入项目素材库→专家继续创作；后续需要补资料、修改、转码或再下载时，可以再次切换执行层。允许依赖驱动的串行、并行、多轮交替与失败恢复。
- 所有能力共用现有 AgentRuntime/AgentToolCatalog、任务队列/租约、权限路由、项目范围、事件/检查点和取消/恢复机制。不得为专家、Phone Agent、媒体模型各自再建一套平行任务调度器。
- 普通聊天不应被迫启动专家团或工具；主模型按需选择最小充分能力。专家与设备任务均须把真实状态/产物回传，主模型进行最终质量检查，不把工具 success 等同成品验收。

### 必须形成的可追踪闭环
输入/附件 → 能力识别 → Provider/模型能力匹配 → 主模型决策 → 工具/专家/设备/媒体任务 → 进度/错误/取消/恢复 → 结果与素材导入 → 主模型复核 → 对话交付。
每个能力分别标记：源码存在、入口接通、任务闭环、持久化/恢复、测试覆盖、运行验收；不允许用“有页面/有 Provider/有工具注册”替代全链路完成。



## 2026-11-22 — 全量功能对齐后的真实收口顺序

本阶段目标不是继续堆功能，而是把十九模块已有实现、历史分支已做但未带入的能力、最近对话新要求统一到当前 `integration/19-modules`。每项先对照源码与调用链，只有确认缺口才修改；不整分支 merge、不复制 Provider/Worker/Task Engine。

### A. 先回收被旧架构替换但不能丢失的功能
1. **记忆生命周期保护（代码已补）**：已回收 mutationVersion、项目锁、自动提取版本校验、编辑/删除/清空/关闭自动记忆/项目清理/备份恢复前的失效等待；测试源码已补，尚未执行。
2. **记忆跨项目显式共享（代码已补）**：已恢复默认关闭、逐条授权/撤销、来源项目标识、Room 13→14 迁移与备份往返；测试源码已补，尚未执行。
3. **统一能力中心**：恢复能力总览、项目级能力授权/撤销、来源/可用性、任务状态和审计入口；复用当前 AgentToolCatalog/AgentRuntime/AgentTaskCoordinator/ProjectAccessControlStore，不合入旧 CapabilityRegistryStore/CapabilityTaskEngine。
4. **共享任务内核补语义**：把业务幂等键、重试退避、最大尝试次数、项目/会话/能力任务元数据和状态事件补到当前 AgentTaskCoordinator/持久模型；不另建任务队列。

### B. 补齐最近确认的跨模块能力
1. **听得懂/看得懂（部分代码已接通）**：聊天页多选图片/音频/视频并导入当前项目素材；主模型已可调用 media.ai.run/status 做图片理解、自动/指定语言语音转写、视频场景分段和音频降噪，视频生成也返回后台 task_id。仍缺 ChatMessage 原生多模态 content parts、通用视频问答、非语音音频理解；测试/真实 API 联调待执行。
2. **混合创作/执行（代码入口已接通）**：已修正 expert_team.run 自动选人描述；Phone Agent、Media AI、视频生成改为后台 task_id 快速返回，主模型可先启动执行任务再穿插专家 DAG；依赖步骤需等完成并校验素材。联合联测待执行。
3. **插件审批与恢复**：修正插件内部 EXTERNAL_WRITE 对所有步骤直接 Allow 的确认绕过；让插件执行复用 AgentTaskCoordinator 的任务状态、checkpoint、暂停/恢复，不恢复旧 CapabilityTaskEngine。
4. **日历自动调度**：从提醒通知升级为可持久触发 Agent/Workflow/Phone Agent 的时间调度，补幂等、状态回写、审批、重试/退避、取消和重启恢复；复用统一任务内核。
5. **Phone Agent**：按最新 Mika 交互要求修正浮窗；补任务终态 SQL 保护与 TRAVEL 导航路线授权路径，保留已有虚拟屏和素材下载代码。

### C. 统一清理与优化
- 全仓调用引用与资源引用核对；识别真正无调用死代码、重复路由、重复状态源、旧协议和历史分支残留。
- 只删除已证明无调用且无迁移/反射/Manifest/序列化引用的代码；旧数据/备份兼容代码先确认版本边界。
- 将重复的任务/工具/媒体能力接入统一边界，保留不同业务 Worker 的必要职责，不为了“统一”而合并不相同的任务模型。
- 更新所有模块清单、代码地图、README 与路线图；确保 19 个模块编号/功能范围/代码落点/缺口一致。
- 每项修改后回读源码与引用点，补对应回归测试源码；统一编译、测试、模拟器/真机验收仍在全部功能收口后进行。

### D. 最终交付顺序
十九模块功能盘点完整 → 近期新增功能回收 → 代码最小修复/补齐 → 全仓重复/死代码审查与清理 → 统一源码回读 → 操作界面完整设计并由用户审阅 → 构建 APK → 统一自动化/设备验收。
不得提前构建，不得把新增测试源码说成测试通过，不得把文档更新说成代码功能完成。



## 2026-11-22 统一任务机接线进度修订

本轮已实际修改代码，以下旧缺口状态需按当前实现更新：
- 媒体 AI/渲染 Scheduler 先登记 inline 任务，Worker 通过 `AgentTaskCoordinator.runInline` 执行；WorkManager 只承担系统唤醒。
- 日历事件可配置自动执行目标，按 occurrence 创建幂等任务，由主模型执行链调用现有工具；提醒功能保留。
- 原生工具并发仅开放给显式声明的只读安全工具，其他调用保持串行。
- 工作流、插件、MCP 审批路径进一步接入统一任务状态，审批弹窗可暂时隐藏且不等于拒绝；日历子工具审批状态会回写父任务。
- **尚未闭环：** 进程重启后的审批请求重建/原步骤恢复、日历 nextRunAt/lastRunAt 与完整错误重试记录、通用跨模块依赖/产物交接和专家团日历调度。十九模块统一构建与自动化/设备验收仍按原顺序执行。



---

# 2026-11-24 当前修复检查点：工具链与浮窗已接线，仍未达到编译条件

- 当前分支：integration/19-modules
- 最新本轮业务源码提交：54afc56cf5e1499e58472f239079244c829a7f08
- 最新审计记录提交：35bf72b068f22c510f86b45e597f4a896cb67936
- 代码地图：docs/CURRENT_CODE_MAP.md
- 本轮新增/修改测试源码：UserMemoryRepositoryInstrumentedTest、ModelLedToolDispatcherAllowListTest、PhoneAgentPreviewLayoutTest；均未运行。

## 已完成的代码修复

1. 记忆来源校验与 upsert/replace 已进入同一 Room @Transaction；Store 不再使用事务外预查。仍需决定 sourceConversationId/sourceMessageId 外键级联或源数据删除时的原子清理策略。
2. 专家项目与工作流的工具感知模型调用已接入统一 ChatRequestExecutionCoordinator / ModelLedToolDispatcher。
3. 新增 task-local allowedToolIds，模型只能发现本操作声明的工具。
4. 新增 taskToolAccess，执行器在原生工具调用和兼容文本协议两条路径复用原操作租约，避免从全局 catalog 重新按 ID 获取处理器；现有项目能力授权、AgentRuntime 权限判断、审批和 MCP 重放检查点仍由统一 dispatcher 执行。
5. 专家项目与工作流任务补入 projectId/conversationId 等 AgentTaskMetadata。
6. Phone Agent 浮窗增加可拖动区域和屏幕边界钳制，原有屏幕点击/滑动/缩放交互保留。

## 尚未完成的编译前阻塞

- 记忆来源删除后的完整引用完整性/级联清理；历史召回缓存版本失效。
- 日历任意时间条件触发、日历专属语义型业务错误映射、专家 DAG/工作流分发；通用任务机瞬时网络/HTTP 重试与副作用安全保护已存在，延迟分钟触发已在当前代码修复批次接通。
- 媒体多视频轨/PIP/多音轨及对应时间线、预览、渲染和工程包兼容。
- ChatMessage 原生 image/audio/video parts、模型 Provider 编码/解码、视频问答和非语音音频事件理解。
- 其余已设计 UI 的逐屏源码复核与实际入口接通；当前只完成 Phone Agent 浮窗拖动，不能视为界面整体完成。
- 全量源码复核、差异/冲突检查、测试源码补齐和最终静态自检。

## 编译闸门

按用户要求，以上阻塞全部处理并完成全量静态自检之前，不运行 Gradle 编译或测试。完成后先向用户汇报“可进入编译测试”，由用户决定开始下一步；在此之前不得构建 APK，也不得把新增测试源码写成已通过。

## 2026-11-24 模块18延迟触发源码收口记录

- 代码批次：af39255062d623db8d82adfb21030565eac82361（相对本轮基线 5ccc397e14101d55914493b5be6020705c0b5486；代码变更已合并为单一提交）。
- 已贯通：编辑器延迟分钟配置 → CalendarEvent/Room v6 → Repository 保留与定义版本递增 → WorkManager 延迟唤醒 → Worker generation 校验 → AgentTaskCoordinator 幂等派发；延迟尚未到期的当前 occurrence 可在进程重启后恢复。
- 已补齐：generation=0 的旧任务 ID/幂等键/WorkManager name 兼容；编辑定义后新 generation 独立身份，旧 Worker 不会用新配置提前执行；LocalBackupRepository 兼容旧备份并保存 delay/generation。
- 新增测试源码：规则测试、v5→v6 Room migration 与 Repository 定义编辑测试、备份/恢复往返测试。全部未运行。
- 尚缺：任意时间条件触发、业务失败分类重试/退避、专家 DAG/Workflow 直接分发。UI 轻量入口收敛仍按产品界面阶段处理。
- 验证闸门不变：不运行 Gradle、JVM/Android 测试、模拟器/真机或 APK 构建；待十九模块代码收口和全量静态复核后统一执行。

## 2026-11-24 通用任务机瞬时 HTTP 重试分类补齐

- 源码回读发现：AgentTaskRetryPolicy 已持久化 attempts/maxRetries、执行指数退避，并只对 RetryableAgentTaskException/瞬时网络异常重试；ModelLedToolDispatcher 在外部副作用开始后将不确定失败标记为 NonRetryableAgentTaskException。
- 真实缺口：OpenAiClient 的 OpenAI-compatible、Anthropic、Gemini HTTP 错误原先统一抛 IllegalStateException，导致 408/425/429/5xx 未进入既有重试策略。
- 本轮修复：AgentTaskRetryPolicy 增加瞬时 HTTP 状态分类（408、425、429、500–599）；OpenAiClient 各协议的 HTTP 失败统一通过该分类生成 RetryableAgentTaskException 或非重试 IllegalStateException；补充 408/425/429/5xx 与 400/401/403/404/422 的策略测试。
- 当前不开放副作用后的自动重试；日历专属语义型业务错误映射仍需依据真实业务错误码定义，不能用“把 maxRetries 调大”代替。
- 新增测试源码未运行；不运行 Gradle/JVM/Android 测试，继续遵守十九模块收口后的统一验收闸门。
