# Nestro

Nestro 是一个开源、本地优先的 Android AI 工作站。它没有 Nestro 自营账号系统或 Nestro 托管的模型后端，用户自行选择并授权模型服务。

## 当前已实现

- Kotlin + Jetpack Compose Android 应用，深色界面与薄荷绿强调色。
- OpenAI Chat Completions 兼容接口配置。
- API Key 使用 Android Keystore 的 AES-GCM 加密保存。
- 本地对话历史与自定义专家提示词。
- 本地技能库：内置结构化总结、精简改写、代码审查，可创建和删除自定义技能；技能提示词会与当前专家提示词组合后发送。
- 模型连接测试。
- 本地 JSON 备份与恢复：包含对话、专家、技能配置及模型地址/名称；不包含 API Key。若恢复的模型地址或名称与本机不同，旧 Key 会被清除，需重新配置，避免把 Key 发给错误服务商。
- GitHub Actions 自动构建 debug APK。

## 配置模型

在「模型管理」填写：

- API Base URL，例如 `https://api.example.com/v1`
- 服务商提供的模型标识
- 用户自己的 API Key

应用请求 `POST {baseUrl}/chat/completions`。不同服务商的兼容程度可能不同，可先使用「测试连接」验证。Key 使用当前设备的 Android Keystore 加密，不写入仓库。

## 本地数据与备份

会话、专家提示词和技能配置保存在应用私有存储。可在「数据与设置」导出 JSON 备份或从备份恢复。备份不包含 API Key，因为加密密钥绑定设备 Keystore；换设备后需要重新配置模型 Key。备份文件由用户自行保管。

## 尚未实现

以下能力仍在开发中，不能视为当前版本已有功能：

- 多项目空间隔离、知识库索引、文档导入与 RAG。
- 多会话管理、流式输出、停止生成、消息版本与草稿。
- 专家团队协作、专家分享与导入。
- 外部 Skill 仓库导入、MCP 客户端与权限审查/隔离执行。
- GitHub/GitLab/Gitee 仓库操作。
- AI 代码补丁预览、隔离测试、差异审查、批准与回滚。
- 完整的数据迁移、自动升级迁移与设备级恢复机制。

外部 Skill/MCP 和 AI 代码执行不会在安全审查能力落地前开放。

## 构建与验证

推送到 `main` 会触发 GitHub Actions，生成 debug APK artifact。CI 构建成功只代表云端编译和打包通过，不代表已在实体 Android 设备完成安装、交互、网络和数据迁移测试。
