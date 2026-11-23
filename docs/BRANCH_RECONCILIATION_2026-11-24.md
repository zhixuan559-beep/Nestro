# NESTRO 历史分支核销记录（2026-11-24）

## 核验基线

- 仓库：`zhixuan559-beep/Nestro`
- 唯一开发主线：`main`
- 本轮重新读取的分支列表：38 条（包含 `main`）；上一轮记录的 39 条与本轮 GitHub 实际检索不一致。
- 本轮核验时 main HEAD：`dd27688185d22b9e6faf9118a53778925576c3c6`
- 相对 main 有独有提交的分支：20 条（包含此前已初步核销的 `design/chat-first-minimal-nost`）；其余 18 条没有 ahead 提交（包含 main）。
- 本文件记录的是 GitHub compare + 分支/main 实际源码逐文件对照。静态核验不等于编译、测试或设备验收。
- 处理原则：不把旧分支快照整体合并；只迁移 main 真正缺失且仍有效的语义。当前逐文件对照未发现需要直接复制回 main 的独有实现；旧架构功能按当前统一架构承接，不恢复第二套任务队列或权限体系。
- 本轮没有删除任何分支，也没有对源码作覆盖性修改。

## 已核销分支

| 分支 | 相对 main 独有提交 | 涉及范围 | 核销处理 |
|---|---:|---|---|
| `design/chat-first-minimal-nost` | 7 | Manifest、KnowledgePage、NestroApp、主题色、UI 设计文档 | 此前已逐文件初核；main 有对应且更新的聊天优先界面和黑白主题实现，不重复迁移。 |
| `fix/orchestration-retry-safety` | 3 | ExpertTeamExecutionCoordinator、WorkflowExecutionCoordinator、AgentTaskCoordinatorTest | main 已使用更完整的 AgentToolAccess/工具租约与 operation 生命周期；分支旧 AgentToolDiscovery 接口不迁回，测试文件内容一致。 |
| `fix/module-13-audit-reliability` | 3 | McpToolCenter、McpToolCenterTest、模块文档 | main 已有串行审计、损坏审计隔离、任务恢复/取消及审批审计；测试文件一致，分支旧版服务实现不覆盖 main。 |
| `fix/monochrome-ui-semantic-colors` | 3 | NestroApp、PhoneAgentPage、Theme | main 主题文件已采用黑白语义色；PhoneAgentPage/NestroApp 已有更完整的任务状态、窗口拖动边界和交互实现。 |
| `fix/automatic-chat-dispatch-entry` | 9 | NestroApp、UI 设计文档 | 分支是较早 UI/入口快照；main 已有当前模型主导调度、工具执行入口和更新后的页面路由，不回迁旧 NestroApp。 |
| `ui/chat-first-directory-refine` | 12 | KnowledgePage、NestroApp、NestroComponents、NestroPalette、ToolsDirectoryPage、设计文档 | ToolsDirectoryPage/NestroPalette 已在 main；main 另有任务中心入口、状态动效及语义化颜色，旧页面实现不迁移。 |
| `fix/memory-context-cache-phone-navigation` | 17 | NestroApplication、ChatRequestContextAssembler、UserMemoryRepository、PhoneAgent 规划/解析/模型及测试 | main 有更严格的历史语料版本缓存失效、统一任务调度和 Phone Agent 风险/旅行规划；同名解析器/模型文件对照一致，不迁回旧实现。 |
| `feature/expert-group-chat-lifecycle` | 28 | MainActivity、会话持久化、专家/技能编解码、NestroApp、语音按钮、Theme | 主体实现已在 main；main 额外处理会话删除后记忆来源失效、技能包有界读取及更新的团队协作/工具租约。无需重复迁移。 |
| `feature/knowledge-retrieval-ranking` | 90 | 知识检索、向量/文本解码、数据库、记忆、Phone Agent、能力中心及测试 | main 已有更完整知识库/向量检索和更严格的代理对边界、Phone Agent 风险校验及统一任务内核。旧 CapabilityTaskEngine 不迁回，避免第二套队列；旧 CapabilityCenterPage 不整页复制，能力授权/审计由现有目录承接，任务由统一任务中心承载。 |
| `feature/module-6-knowledge-base` | 26 | 知识库导入、OCR、Embedding、向量检索、数据库、页面和测试 | 主要源码/测试均已在 main；main 的 BGE 实现采用 attention-mask-aware mean pooling，较分支 CLS 取值更完整；页面也已接入项目权限和语义主题色。 |
| `feature/module-7-skill-library` | 37 | 技能依赖解析、社区客户端、安装规划/策略、目录页和测试 | main 已由 SkillSemVer、扩展后的 SkillDependencyResolver、SkillCommunityClient、SkillCommunityLifecycleStore、SkillTrustStore 承接版本约束、依赖求解、签名校验、原子安装/回滚和依赖保护；不引入旧 Planner/Policy 第二套安装流程。 |
| `feature/module-8-phone-agent` | 11 | Android CI、Module 8 CI、instrumentation 脚本、Gradle 配置 | instrumentation 脚本、Module 8 CI、Gradle 配置与 main 相同；Android 主工作流已在 main 扩展 APK 构建、SBOM 和依赖导出。无需迁移。 |
| `feature/module-9-voice-system` | 115 | Android TTS、OpenAI-compatible STT/TTS、Realtime WebSocket、PCM 音频、配置存储、语音页面和测试 | 语音主体文件/测试已在 main；main 另有 TTS 中文音色枚举和 locale 能力检查。无需回迁。 |
| `feature/module-13-mcp-tool-center` | 70 | MCP Streamable HTTP 客户端、工具中心服务/页面、应用接线、测试和文档 | main 已有更完整的会话并发/恢复保护、统一任务恢复入口、撤销后待恢复任务取消和审计损坏隔离。旧服务和页面不覆盖 main。 |
| `feature/module-14-github-workbench` | 91 | GitWorkbench、GitWorkbenchPage、账号/策略测试、NestroApp | 页面及账号测试内容一致；main 的仓库索引并发锁、凭据失败回滚、路径/URL 策略测试更完整。无需迁移。 |
| `feature/module-15-ai-coworkbench` | 207 | Coworkbench 页面/服务/分析器/Actions Runner、SBOM 脚本、测试、CI | 主要源码与测试已在 main；SBOM 脚本也已存在，main 有额外扫描/CI调整。分支差异不足以支持覆盖 main。 |
| `wip/module-5-project-space` | 6 | 项目授权/资源范围 Store、NestroApp、策略测试、设计文档 | main 对项目 ID/资源键校验、团队资源可见性、权限撤销审计和测试覆盖更完整；保留 main。 |
| `wip/module-6-finalize` | 21 | 知识库 Embedding/OCR/导入/检索/页面/数据库/测试 | 与 module-6 分支同源旧快照；main 已有更完整的向量均值池化、XML 安全解析、异步 OCR、索引重建和主题实现。无需迁移。 |
| `wip/module-6-knowledge-base` | 3 | PDF OCR、Chunker、Repository、Search、Store、Decoder、KnowledgePage | main OCR 为 suspend 非 runBlocking；Repository 已接入向量索引；Chunker 有更严格 Unicode 代理对边界；Store/页面实现更完整。无需迁移。 |
| `test/github-write-access-20261122` | 2 | GitHub compare 未返回文件差异 | compare 返回 ahead 2、files=[]；没有可识别的文件级代码迁移项。保留分支，最终清理前再核对提交元数据。 |

## 仅落后于 main、没有独有提交的分支

本轮 compare 显示 18 条分支（包含 main）没有相对 main 的 ahead 提交。它们不是“功能已核销”的证明，只表示没有独有提交可供迁移。最终统一清理时再按用户确认的策略删除非 main 分支。

## 当前结论与下一阶段

1. 20 条有 ahead 提交的分支均已完成源码差异核销；其中最初的 design 分支已在此前初步核销，本轮完成其余 19 条。
2. 本轮没有发现需要将旧代码直接复制覆盖 main 的情况；旧 CapabilityTaskEngine/CapabilityCenterPage 等重复架构不回迁。
3. 分支尚未删除；待全部分支核销和主线验收完成后，再统一只保留 main。
4. 本轮属于 GitHub 源码静态核对，尚未执行 Gradle 编译、JVM 测试、Android instrumentation、模拟器或真机验收。不得据此宣称 APK 已可构建。
5. 下一阶段：按十九模块统一核查实际入口、调用链、模型能力声明、专家团协作、媒体编辑、任务持久化/取消/恢复、幂等与重复实现；补齐真实缺口后统一编译测试。
