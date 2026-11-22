# NESTRO 十九模块统一功能核对（第一轮）

核对分支：`integration/19-modules`  
核对基线：`5cdcab4cad1bf62b6e7b108827272d646aab4dc4`  
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
| 1 专家系统 | 主体已实现，协作产品能力不完整 | 自动组队、DAG 工作包、并行执行和成果交接已有代码；双向自由交流、专家私聊、可编辑任务图、暂停/恢复/返工控制、完整历史分页、临时团队保存/解散、结构化验收/返工协议及进程中断恢复仍未形成完整闭环。 |
| 2 模型管理 | 主体已实现，外部联调待完成 | OpenAI/DeepSeek/Anthropic/Gemini/硅基流动/魔搭等服务商协议与模型注册表代码已存在；魔搭 OAuth 服务部署、第三方应用注册、真实授权范围与推理权限确认仍依赖外部配置；账户余额/免费额度动态同步未实现。 |
| 3 聊天系统 | 主体已实现，模型上下文适配不完整 | 多会话、流式请求、编辑、重新生成、失败重试、版本与进程重建恢复代码已存在；上下文预算目前使用固定默认窗口估算，未按每个模型的真实上下文窗口动态适配。 |
| 4 数据与备份 | 备份恢复主体已实现，资源覆盖不完整 | JSON 备份有校验、事务日志和中断恢复，并覆盖 Room、项目、知识、记忆、技能、作品和日历；附件管理及媒体编辑器二进制素材未纳入通用备份/项目包，完整设备迁移仍需补齐。 |
| 5 项目空间 | 主体已实现，资源归属仍需全链路核对 | 项目隔离、归档、资源范围与项目包已有代码；媒体二进制、所有资源类型复制/删除/迁移的一致性需结合模块17/4继续核对。 |
| 6 知识库 | 主要功能代码已实现 | 多格式解析、PDF OCR、FTS/BM25、本地 BGE 向量与混合检索已存在；Room 12→13 迁移及对应仪器测试源码已存在。统一构建与设备性能仍待验证。 |
| 7 我的记忆 | 主体已实现 | 自动提取、项目隔离、召回、导入导出和来源关联已有代码；需在统一验收中验证隐私过滤、跨项目授权、删除级联和备份恢复一致性。 |
| 8 Phone Agent | 有受控执行闭环，通用目标能力仍有限 | 观察—规划—执行—页面核验、风险暂停、用户接管、持久任务和虚拟屏代码已存在；跨平台信息搜索、公开 API/Intent 优先、复杂登录/验证码接管和不同 App 的通用兼容仍未形成可保证的完整闭环。 |
| 9 语音系统 | 主要功能代码已实现 | Android 识别、本机 TTS、远程 STT/TTS、实时双向语音代码已存在；真实服务商密钥、协议兼容和真机音频路由尚未统一验证。 |
| 10 我的作品 | 主体已实现 | Markdown 作品保存、版本、预览、分享、导出、删除与备份恢复已有代码；需统一验证项目隔离、文件丢失恢复和备份回滚。 |
| 11 专家模板 | 功能代码已实现，测试未执行 | 内置/自定义模板、编辑、复制、删除、搜索、套用和 JSON 导入导出已有代码；JVM/UI 回归与 CI 尚未验证。 |
| 12 双模型对比 | 功能代码已实现，测试未执行 | 双模型并行请求、独立失败、停止、复制和响应布局已有代码；Kotlin 编译/JVM/设备验收尚未完成。 |
| 13 MCP 工具中心 | 受控远程 MCP 已实现，范围有限 | HTTPS Streamable HTTP、工具发现、逐工具授权、参数预览、审计和撤销已有代码；本地 stdio Server 与模型原生 tool-call schema 适配不在当前范围；统一测试未执行。 |
| 14 GitHub 工作台 | 主体已实现，测试未执行 | GitHub/GitLab/Gitee PAT、仓库操作、手机端编辑、Diff、冲突、历史和回滚已有代码；索引并发、删除恢复、凭据回滚与 Push 结果保护已补强；编译/JVM/设备验收待执行。 |
| 15 AI 共建工作台 | 源码已整合，验收未执行 | 补丁审查、逐文件批准、事务回滚、静态扫描、Actions Runner、JUnit/SBOM 解析、NestroApp 聊天回流与 CI 依赖证据已接入；真实 GitHub Actions、SBOM 产物与测试尚未运行。 |
| 16 统一能力中心/插件工坊 | 基础插件工坊已实现，安全能力不完整 | 本地声明式插件生成、预览、安装/启停、版本替换、回滚和卸载已有代码；插件签名/来源验证、依赖锁定、独立资源沙箱、完整审批审计视图、远程目录与导入导出仍未完成。当前不是开放式插件市场，也不执行任意代码。 |
| 17 智能剪辑 | 基础编辑/渲染代码已整合，范围有限 | 素材库、单视频轨+单音频轨、拆分/移动/裁剪、撤销重做、预览、MP4 导出、SRT 管理和后台任务已有代码；多视频轨/PIP、转场、速度曲线、字幕烧录未实现，媒体二进制尚未纳入通用备份。Media3 1.9.0 依赖仍需编译/设备验证。 |
| 18 日历 | 功能代码已整合，验收未执行 | 独立 Room 日程库、月/周/日视图、重复规则、WorkManager 提醒、系统日历读写/导入导出、备份恢复已有代码；WorkManager 提醒可能受系统省电策略延迟，不等同精确闹钟；需统一测试和真机验证。 |
| 19 多模态服务与社区生态 | 核心代码已整合，外部配置与测试未完成 | OpenAI 转写/兼容视觉、媒体 AI 持久任务、SemVer 2.0 依赖求解、原子安装、独立信任根与签名撤销已有代码；真实 API Key、服务商专属端点、正式社区目录/信任根/撤销 URL 需要外部配置，测试未执行。 |

## 已确认的高优先级问题

### A. 工作流执行链路

源码位置：
- `app/src/main/java/app/nestro/data/WorkflowEngine.kt`
- `app/src/main/java/app/nestro/data/WorkflowExecutionCoordinator.kt`
- `app/src/main/java/app/nestro/ui/NestroApp.kt`

已确认：
- 引擎支持简单条件、跳过条件、单步重复、超时、重试和 continue-on-error。
- UI 有当前进程内暂停/继续门闩。
- `WorkflowStep.parallelGroup` 有数据字段，但执行循环仍为顺序 `forEachIndexed`，没有按组并行执行。
- 工作流编辑 UI 未提供 condition、approvalRequired、parallelGroup 的完整配置入口。
- `WorkflowExecutionCoordinator` 没有向引擎传入审批回调；`WorkflowEngine.run` 的默认审批回调返回 true。若导入的工作流将步骤标为需要审批，当前协调路径不能证明已取得用户审批，存在默认放行风险。
- 协调器仅在开始/结束写任务 checkpoint，没有逐步骤持久化恢复；运行历史在执行结束后才写入。

判定：条件/循环属于部分实现；并行、真实审批、可恢复执行未完成。审批默认放行需要优先修复为 fail-closed，并接入真实用户确认。

### B. 专家团队闭环

源码位置：
- `app/src/main/java/app/nestro/data/ExpertProjectPlanExecutor.kt`
- `app/src/main/java/app/nestro/data/ExpertTeamExecutionCoordinator.kt`
- `app/src/main/java/app/nestro/data/ExpertTeamEngine.kt`
- `docs/EXPERT_TEAM_AUTONOMOUS_PROJECTS_AND_PLUGIN_FACTORY.md`

已确认：主模型可以生成工作包 DAG，执行器能校验依赖并按拓扑波次并发执行；依赖成果会交接给后续工作包。

仍缺：完整的结构化验收/返工协议、任务图编辑、暂停/恢复/重做、进程中断后逐包恢复、精确 Token/费用预算和完整协作历史分页。

### C. Phone Agent 目标闭环

源码位置：
- `app/src/main/java/app/nestro/phone/PhoneAgentPlanner.kt`
- `app/src/main/java/app/nestro/phone/PhoneAgentAgent.kt`
- `app/src/main/java/app/nestro/phone/PhoneAgentExecutor.kt`
- `app/src/main/java/app/nestro/phone/PhoneAgentService.kt`

已确认：有分类规划、有限轮次观察—规划—执行、页面证据核验、风险接管与任务暂停；创作素材可通过公开 HTTPS 媒体直链下载并导入项目。

仍缺：跨平台搜索与信息核验的通用数据源、公开 API/Intent 优先策略的完整统一路由、更多真实 App 兼容性和长期连续任务验收。不能把预设步骤或模型计划等同于已完成通用代办能力。

### D. 数据与素材备份

源码位置：
- `app/src/main/java/app/nestro/data/LocalBackupRepository.kt`
- `app/src/main/java/app/nestro/data/ProjectPackageCodec.kt`
- `app/src/main/java/app/nestro/media/MediaProjectStore.kt`

已确认：通用备份含 SHA-256 校验、恢复日志和中断恢复；作品、技能、知识和日历等结构化数据已有备份链路。

仍缺：媒体素材二进制文件没有纳入通用备份/项目包；附件管理和完整设备迁移尚未形成统一链路。

## 下一步核对顺序

1. 优先审查并修复工作流审批默认放行、并行组未执行和断点恢复缺口。
2. 核对专家团队任务 DAG、事件、主模型最终验收和任务中断恢复。
3. 核对 Phone Agent 统一入口、工具目录、执行/核验和媒体素材导入闭环。
4. 核对附件、媒体二进制、项目包和全量备份恢复边界。
5. 逐模块回读实现与测试源码，清除路线图/模块清单中的过期状态。
6. 完成源码核对后，再进入统一编译、JVM/Python、Android instrumentation、模拟器和真机验收。

## 本轮明确未执行

- 未运行 Gradle/Kotlin 编译。
- 未运行 JVM/Python/Android 自动化测试。
- 未触发 GitHub Actions Workflow。
- 未运行模拟器或真机测试。
- 未把未验证功能标记为验收通过。
