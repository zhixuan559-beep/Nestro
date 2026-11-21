# Nestro 实现状态与开发路线图

> 状态口径：只有代码已落地且对应构建通过，才标记为「已实现」。UI 占位、设计说明、接口草稿不计入完成。实体设备相关行为在真机验收前仍标记为「待真机验证」。

> **2026-11-20 模块7锁定记录：** PR #7 已合并至 `refactor/phase-2-architecture`，合并提交 `c2c2e18b7e5e383cb550a2ed7ac2fdb67584739d`。对应 CI Run #72 的 `verify` 与 `instrumentation` 均通过；编译、JVM 单元测试、Lint、APK/测试包构建及 Android 模拟器测试全部成功。模块7代码与云端验证锁定完成。

> **2026-11-21 模块8锁定记录：** PR #8 已合并至 `refactor/phase-2-architecture`，合并提交 `ad48068efc97d99b37af851ef7c884539144c5ed`；提交 `af3d5a7ee2057f546a88ed7f96e80b73eca4a32a` 对应 CI 的 `verify` 与 `instrumentation` 均通过。模块8云端代码与模拟器验证完成；真实设备兼容性、后台限制和功耗验收仍待完成。

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
- [x] 语音服务扩展契约：定义可选 SpeechInputProvider、SpeechOutputProvider 与 RealtimeVoiceProvider；预留服务商、API 地址、API Key、模型和音色配置字段，不内置第三方服务。
- [x] 本机语音合成：接入 Android TextToSpeech，支持中英文选择、语速/音调调节、朗读状态反馈与停止；文本长度和控制字符校验，不上传朗读文本。
- [ ] 第三方语音服务商的真实 Provider 实现、加密配置管理与实时双工语音连接；当前仅有接口契约，不能视为已接通。
- [ ] 实体设备键盘、语音识别兼容性和交互流畅度验收。

### Phone Agent
- [x] AccessibilityService 注册与用户手动授权入口。
- [x] 用户主动读取当前窗口可访问文本，跳过密码字段。
- [x] 基础可见文本点击能力。
- [x] 临时单次/任务授权、撤销和本地审计。
- [x] 自然语言任务规划与动作编排：模型驱动多轮观察-规划-执行-核验；严格有界 JSON 响应解析；Google、Bing、DuckDuckGo 三路网页检索与多来源交叉核验提示；真机稳定性单独验收。
- [x] 动作前目标校验、动作后结果验证、停止控制和异常恢复：点击/输入目标必须唯一、可见、启用；动作结果逐步核验；停止标记按 taskId 隔离；WorkManager 持久队列、进程恢复和不确定动作人工核对；真机适配单独验收。
- [x] 风险分类与敏感内容保护：统一模型解析、持久化计划恢复和 Android 执行端策略；覆盖高风险提交、登录/验证码/风控、同意条款、系统权限、敏感字段与个人信息值、公开 URL 安全校验、任务目标/进度/日志脱敏；真机适配单独验收。
- [ ] Phone Agent 真机专项验收：不同 Android 版本、主流浏览器/目标应用、登录与权限弹窗、系统进程回收、厂商后台限制、连续任务稳定性和功耗。

### 工程交付
- [x] GitHub Actions PR 自动化测试；第一版 APK 构建与 artifact 上传延后至十九个模块全部完成后的统一交付阶段。
- [x] GitHub Actions 自动运行 JVM 单元测试与 Android 模拟器测试；当前覆盖 SSE 解码、Phone Agent 计划/动作校验、知识检索排序与文档分块。APK 构建/上传不在模块开发阶段执行。
- [ ] 数据库迁移、集成和 Compose UI 测试。
- [ ] 实体设备安装、升级、数据迁移、权限和功耗验收。

## 未实现能力清单

### 1. 数据层与本地知识
- Room 数据库 schema 版本升级、迁移测试与异常恢复策略。
- 数据库 schema 版本升级、可回滚迁移、崩溃恢复与备份一致性测试。
- 附件管理、文件引用、完整备份校验和设备迁移。
- [x] TXT、Markdown、HTML、CSV、JSON、XML 等文本导入与本地解析（单文件 2 MB）。
- [x] DOCX、XLSX、PPTX Office 文档文本解析；[x] PDF 文本层提取与扫描页中文本地 OCR（逐页处理、加密/页数/文本量限制）。
- [x] 文档分块、项目内关键词检索与 RAG 上下文来源标注初版。
- [x] SQLite FTS4 全文召回、项目级 BM25 排序、中文 n-gram/子串召回、稀有词权重、长查询覆盖、来源标注与项目隔离。
- [x] SQLite FTS4 + 项目级 BM25 混合词法检索；[ ] 本地 BGE-small-zh-v1.5 INT8 ONNX 向量索引与混合排序（实现已加入模块6分支，待 CI 通过后勾选；模型下载后离线运行，不上传知识库内容）。
- [x] Phase 3 首项：知识库加入 Room FTS4 索引与 10→11 数据库迁移；检索优先走 FTS，失败或无命中时安全回退关键词检索；迁移测试覆盖旧库升级与 FTS 命中。
- [x] Phase 3 知识库继续补齐：Apache PDFBox 提取文本层；无文本层页面由内置 ML Kit 中文识别器在本机逐页 OCR，进入现有分块/去重/FTS/BM25 索引链路。
- [x] 删除文档时通过 Room 外键级联清理全部知识片段索引。
- [ ] Room 12→13 迁移新增项目知识片段向量表；项目隔离查询、文档删除级联和迁移测试已补，待最终 CI 核验。

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

> 2026-11-20：模块5已完整验收并锁定：PR #4 已合并至 `refactor/phase-2-architecture`，合并提交 `dba49675483520a5413ebea494423f7a80d395ae`；CI #149 的 Android instrumentation 90/90 通过。模块6原有知识库代码已迁移到模块5锁定后的基线，并补齐本地 BGE-small-zh-v1.5 INT8 ONNX 语义向量、BM25 混合排序、FTS 安全回退、OCR 协程取消、XML/Office 格式测试、向量索引迁移与项目隔离。分支 `feature/module-6-knowledge-base` 正在执行最终 CI；未通过前不提交 PR、不锁定模块6。

### 4. 专家与多智能体
- [x] 自定义专家配置导入/导出、专家版本历史与差异比较。
- [x] 团队角色指令、2–4 成员并行执行、主模型汇总、单成员超时/重试/失败隔离、停止控制、调用次数上限与执行轨迹。
- [ ] 动态任务拆解与派发、精确 Token/费用预算、进程级中断恢复。
- 专家模板安全导入导出。

### 5. Skills 与工作流
- [x] 本地技能 CRUD、启停、试用入口、导入导出和权限声明校验。
- [ ] 外部技能仓库拉取、依赖解析与隔离执行。
- 依赖声明、权限清单、风险提示、隔离验证。
- [x] 两步工作流编辑器初版：支持提示词/专家/技能节点、保存、编辑、删除和顺序执行，前序结果传入下一步。
- [ ] 动态增删/排序节点、条件/循环/审批节点与可视化连线。
- 可持久化任务执行、暂停/恢复、重试、取消和日志。

### 6. MCP 与模型路由
- MCP 本地/远程传输、服务发现和工具 schema。
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
- [x] 原生语音输入与可选 SpeechInputProvider、SpeechOutputProvider、RealtimeVoiceProvider 扩展接口；不内置 TTS 或第三方语音服务。
- [ ] 作品/产物库、文件导出、版本和分享（已有按项目隔离的本地 Markdown 产物库、助手回复一键保存、版本递增、预览、分享、SAF 导出和删除；已纳入带 SHA-256 校验的 JSON 备份/恢复，并校验项目归属）。
- [ ] 能力目录/社区、安装来源和权限透明（已实现 HTTPS 远程目录读取、用户固定 RSA 公钥验签、技能包 SHA-256/名称/版本核验、权限与依赖预览及用户确认安装；代码已补社区版本比较、防降级和同名覆盖提示，卸载复用本地技能库删除流程；仍需接入经过独立渠道核验的正式社区地址/公钥，并完成 CI/设备验收）。

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
- [ ] 专家创建、编辑、版本、启停、导入导出和生命周期管理。
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
- [ ] 本地/远程 MCP 传输适配、服务发现、协议握手和工具 schema。
- [ ] 每个服务、每个工具单独授权；调用前预览参数，调用后审计。
- [ ] 凭据加密、权限撤销、超时、断连恢复和不可信工具输出隔离。
- [ ] Android 不默认承诺可运行任意本地 MCP Server；按实际运行环境提供连接方式。

#### 2B. Git 工作台
- [ ] GitHub/GitLab/Gitee 授权与凭据安全存储。
- [ ] 仓库浏览、搜索、clone、pull、push、commit、branch、历史和回滚。
- [ ] 手机端代码查看/编辑、diff、冲突处理和变更审查。
- [ ] 网络中断、仓库状态冲突和大仓库性能处理。

#### 2C. AI 开发工作台
- [ ] AI 生成补丁、变更范围说明和逐文件 diff 预览。
- [ ] 依赖、权限、危险操作和影响面检查。
- [ ] 隔离构建/测试环境与测试结果回传。
- [ ] 用户明确批准后才应用变更；应用前快照，失败可回滚。
- [ ] 禁止静默扩大权限或执行未审查任意代码。

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

- [x] 语音方向收敛为原生语音输入；第三方识别、语音合成和实时语音仅预留 Provider 扩展契约。
- [ ] 后台长任务通知、用户可见任务中心、失败重试与取消。
- [ ] 作品/产物库、文件导出、版本记录、分享和清理策略。
- [ ] 能力目录/社区、来源验证、安装审查、更新和卸载。
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

The shared Agent runtime now supports short-lived allow-listed tool sessions. Phone Agent exposes only the typed capability needed by the current action and releases it immediately after the step. This is an implemented foundation; it does not yet constitute full MCP/plugin discovery or per-provider credential leasing. Ordinary chat remains outside this execution path. Acceptance still requires CI for the exact PR head and dedicated integration tests for expert/workflow/tool routing.


Phone Agent's action-history compactor now retains the most recent 12 distinct action results within a fixed 1,000-character budget, rather than preserving only the oldest entries. This keeps the latest execution evidence available to the next planning call without growing the prompt.


Usage accounting now stores estimated token counts directly, attributes each request to the actual selected Registry model (including failover), and includes Phone Agent planning calls. Streaming fallback estimates use the same bounded-history estimator as non-streaming calls. Provider-reported usage and estimates remain separate; background automatic-memory extraction still needs usage callback integration.


Automatic-memory extraction now contributes its model usage to the same provider-vs-estimate accounting as chat, experts, workflows, and Phone Agent. Usage attribution uses the selected Registry model ID while retaining legacy profile identifiers. Chat callbacks merge against the latest persisted stats rather than stale UI state.


Security invariant: task-scoped capability selection is not authorization. Every selected tool still passes through AgentPermissionRouter; external writes, authentication, financial actions, and device actions remain subject to their separate confirmation/grant rules. Regression coverage verifies that loading an external-write capability does not execute it or bypass WAITING_FOR_USER.


Tool sessions now snapshot the exact handler as well as the selected capability definition. A later registration using the same tool ID cannot retarget an already-open task lease; new sessions use the new registration. JVM regression coverage verifies this isolation. Explicit revocation of already-open leases and credential expiry remain separate follow-up work.


## 2026-11-18 — Phase 2 lease lifecycle closeout work

- Implemented revocable task-owned capability leases and short-lived task credential lease primitives.
- Task pause/cancel and handler exit now revoke leases; Phone Agent action execution uses the shared lease registry and releases each action lease in `finally`.
- Added JVM tests for lease revocation and credential access expiry/revocation.
- Remaining Phase 2 work: connect expert-team and workflow steps to explicit minimum capability sets through the shared tool boundary; complete the capability registration/routing contract; verify the latest PR head with CI. MCP discovery and remote provider adapters remain dependent on that shared contract and are not marked complete.
- APK construction, installation, and device acceptance remain deferred until all feature code across the 19 modules is written and the final unified acceptance stage begins.



## 2026-11-18 — Unified capability registration and cross-feature catalog

- Added a process-wide tool catalog shared by Phone Agent, expert-team and workflow execution.
- Added operation-scoped capability declarations, with expert-member and workflow-step resolution. Only the declared minimum tool set is leased for that operation; missing tools or missing tool-aware adapters fail closed.
- Added tests for member/step tool isolation and lease release.
- The current expert/workflow adapters still perform model-only calls and have no declared tool sets by default. Model-native tool invocation, MCP/plugin discovery and remote credential adapters remain follow-up integration work; do not mark them complete based on the registry foundation alone.
- APK generation, installation and real-device acceptance remain deferred until all feature code for all 19 modules is complete, followed by one unified final acceptance.


## 2026-11-18 — Phone Agent host-independent execution environment

- Phone Agent now depends on a host-independent `PhoneAgentExecutionEnvironment` contract; the current AccessibilityService is the existing adapter.
- Agent execution, WorkManager availability checks, and UI stop requests use one active-environment provider. Detaching the selected environment leaves no implicit fallback to the user's real screen.
- Phase 2 provides the architecture seam only. A concrete isolated VirtualDisplay adapter is a Phone Agent feature implementation; reuse from Mika requires the exact repository and license to be checked before copying code.
- This does not change the unified final-acceptance rule: no APK build or device acceptance until all 19 modules' feature code is complete.
