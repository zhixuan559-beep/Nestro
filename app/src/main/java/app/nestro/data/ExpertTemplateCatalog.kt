package app.nestro.data

import org.json.JSONArray
import org.json.JSONObject

object ExpertTemplateCatalog {
    fun builtIns(): List<ExpertTemplate> = listOf(
        template("research-analyst", "深度研究员", "研究分析", "拆解研究问题，区分事实、推断与未知；优先使用可核验来源，比较不同解释，最后给出结论、证据、风险与待验证问题。"),
        template("business-strategist", "商业策略顾问", "商业经营", "从目标、用户、价值、竞争、成本和现金流分析商业问题。明确关键假设与约束，提出可执行策略、优先级、指标和止损条件。"),
        template("product-manager", "产品经理", "产品研发", "把需求转化为清晰的问题定义、用户场景、功能边界、交互流程和验收标准。优先识别依赖、异常路径与最小可交付闭环。"),
        template("data-analyst", "数据分析师", "研究分析", "先检查数据口径、样本范围和缺失值，再选择合适方法。区分相关与因果，展示计算过程、关键发现、不确定性和可复现结论。"),
        template("content-planner", "内容策划师", "内容创作", "围绕目标受众与平台分发机制策划内容。输出选题角度、核心冲突、标题方向、内容结构和互动设计，避免空泛套路。"),
        template("copy-editor", "文案编辑", "内容创作", "保留原文真实意图与有效信息，删掉套话、重复和机器腔。强化表达清晰度、节奏、说服力与平台适配，不虚构事实。"),
        template("software-engineer", "软件工程师", "产品研发", "先理解现有架构与约束，再给出最小必要改动。关注可维护性、安全、异常处理、兼容性和测试；不编造运行结果。"),
        template("learning-coach", "学习教练", "教育成长", "根据学习目标拆分知识点与练习任务。通过问题检查理解，指出认知漏洞，安排反馈与复习，不以堆砌资料代替掌握。"),
        template("project-lead", "项目负责人", "项目管理", "把目标拆成有依赖关系的交付项，明确负责人、完成定义、风险和阻塞。跟踪实际证据，不把计划、提交或口头承诺当作完成。"),
        template("risk-reviewer", "风险审查员", "风险控制", "从合法性、安全、隐私、财务、依赖和失败后果审查方案。区分高低风险，给出触发条件、缓解措施和不可越过的边界。")
    )

    private fun template(id: String, name: String, category: String, prompt: String) = ExpertTemplate(
        id = id,
        name = name,
        description = "Nestro 内置模板 · " + category,
        prompt = prompt,
        profile = ExpertProfile(group = category, description = "适用于" + category + "类任务的角色模板", version = "1.0.0", source = "Nestro 内置模板"),
        category = category,
        builtIn = true
    )
}

object ExpertTemplateBundleCodec {
    fun encode(templates: List<ExpertTemplate>): String {
        require(templates.isNotEmpty() && templates.size <= 100) { "模板数量必须为 1 到 100 个" }
        val clean = templates.map { validate(it) }
        require(clean.map { it.name }.distinct().size == clean.size) { "模板名称不能重复" }
        return JSONObject()
            .put("format", "nestro-expert-templates")
            .put("version", 1)
            .put("templates", JSONArray(ExpertTemplateCodec.encode(clean)))
            .toString(2)
            .also { require(it.length <= 300_000) { "模板文件超过 300 KB" } }
    }

    fun decode(raw: String): List<ExpertTemplate> {
        require(raw.isNotBlank() && raw.length <= 300_000) { "模板文件为空或超过 300 KB" }
        val root = JSONObject(raw)
        require(root.optString("format") == "nestro-expert-templates") { "不是 Nestro 专家模板文件" }
        require(root.optInt("version") == 1) { "不支持的专家模板文件版本" }
        val array = root.optJSONArray("templates") ?: error("模板列表缺失")
        require(array.length() in 1..100) { "模板数量必须为 1 到 100 个" }
        val decoded = ExpertTemplateCodec.decode(array.toString())
        require(decoded.size == array.length()) { "模板数据包含无效项目" }
        val clean = decoded.map { validate(it) }
        require(clean.map { it.name }.distinct().size == clean.size) { "模板名称不能重复" }
        return clean
    }

    private fun validate(template: ExpertTemplate): ExpertTemplate {
        val name = template.name.trim()
        val prompt = template.prompt.trim()
        require(name.isNotBlank() && name.length <= 40) { "模板名称必须为 1 到 40 个字符" }
        require(prompt.isNotBlank() && prompt.length <= 8_000) { "模板指令必须为 1 到 8000 个字符" }
        require(template.description.length <= 300) { "模板描述不能超过 300 个字符" }
        require(template.category.isNotBlank() && template.category.length <= 30) { "模板分类无效" }
        require(template.profile.temperature == null || (template.profile.temperature.isFinite() && template.profile.temperature in 0.0..2.0)) { "Temperature 配置无效" }
        require(template.profile.maxTokens == null || template.profile.maxTokens in 1..32768) { "Max tokens 配置无效" }
        return template.copy(name = name, prompt = prompt, builtIn = false)
    }
}
