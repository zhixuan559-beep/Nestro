package app.nestro.phone

/** Goal-driven task planning contract. Planning never grants permission or performs actions. */
object PhoneAgentPlanner {
    /** Replans remaining work from the current screen without granting permissions. */
    fun replanFromCurrentState(plan: Plan, currentScreen: String, completedStep: Int): Plan {
        require(completedStep >= 0)
        val safeScreen = currentScreen.take(8_000)
        val remaining = plan.steps.drop(completedStep.coerceAtMost(plan.steps.size))
        return plan.copy(steps = remaining.mapIndexed { index, step ->
            step.copy(title = if (index == 0 && safeScreen.isNotBlank()) step.title + "（基于当前屏幕重新核验）" else step.title)
        })
    }

    enum class Category { SHOPPING, FOOD_ORDER, BOOKING, NAVIGATION, WEB_RESEARCH, FORM, CREATIVE_ASSET, GENERAL }
    enum class Risk { LOW, MEDIUM, HIGH }
    enum class StepKind { CLARIFY_REQUIREMENTS, DISCOVER_OPTIONS, COMPARE_TOTAL_COST, SELECT_CANDIDATE, OPEN_DESTINATION, VERIFY_PAGE, FILL_NON_SENSITIVE_DETAILS, DOWNLOAD_ASSET, IMPORT_ASSET, REVIEW_ORDER, REQUEST_USER_CONFIRMATION, HANDOFF_TO_USER, VERIFY_COMPLETION }
    data class Step(val kind: StepKind, val title: String, val risk: Risk = Risk.LOW, val requiresUserConfirmation: Boolean = false)
    data class Plan(val category: Category, val goal: String, val steps: List<Step>, val requiresHumanAtCommit: Boolean = true)

    fun shouldCreateTask(taskModeEnabled: Boolean, plan: Plan): Boolean =
        taskModeEnabled && plan.goal.isNotBlank()

    fun plan(prompt: String): Plan {
        val text = prompt.trim()
        val normalized = text.lowercase()
        val category = when {
            listOf("下载素材", "下载图片", "下载视频", "素材采集", "收集素材", "获取素材", "图片素材", "视频素材", "音频素材").any { normalized.contains(it) } -> Category.CREATIVE_ASSET
            listOf("买", "购买", "下单", "购物", "咖啡", "商品", "比价").any { normalized.contains(it) } -> Category.SHOPPING
            listOf("订票", "预约", "预订", "酒店", "机票", "火车票", "挂号").any { normalized.contains(it) } -> Category.BOOKING
            listOf("导航", "路线", "怎么走", "开车去", "步行去", "骑车去", "带我去", "去机场", "去车站", "去医院").any { normalized.contains(it) } -> Category.NAVIGATION
            listOf("外卖", "点餐", "订餐", "奶茶", "餐厅", "吃饭").any { normalized.contains(it) } -> Category.FOOD_ORDER
            listOf("搜索", "查找", "调查", "对比", "资料", "网页").any { normalized.contains(it) } -> Category.WEB_RESEARCH
            listOf("填写", "提交表单", "申请", "报名").any { normalized.contains(it) } -> Category.FORM
            else -> Category.GENERAL
        }
        val steps = when (category) {
            Category.SHOPPING, Category.FOOD_ORDER -> listOf(
                Step(StepKind.CLARIFY_REQUIREMENTS, "确认商品、规格、预算、位置和送达要求"),
                Step(StepKind.DISCOVER_OPTIONS, "检索可用平台、商家和商品"),
                Step(StepKind.COMPARE_TOTAL_COST, "比较商品价、配送费、优惠、时效与最终到手价"),
                Step(StepKind.SELECT_CANDIDATE, "按用户约束选择候选方案"),
                Step(StepKind.OPEN_DESTINATION, "打开对应 App 或网页"),
                Step(StepKind.VERIFY_PAGE, "核对商家、商品、规格和实时价格"),
                Step(StepKind.FILL_NON_SENSITIVE_DETAILS, "填写非敏感配送信息并准备订单"),
                Step(StepKind.REVIEW_ORDER, "复核商品、地址、优惠和订单总额"),
                Step(StepKind.REQUEST_USER_CONFIRMATION, "付款前交由用户确认", Risk.HIGH, true),
                Step(StepKind.VERIFY_COMPLETION, "仅在平台明确反馈后确认订单结果")
            )
            Category.BOOKING -> listOf(
                Step(StepKind.CLARIFY_REQUIREMENTS, "确认日期、时间、人数/乘客和预算"),
                Step(StepKind.DISCOVER_OPTIONS, "检索可用平台与候选项"),
                Step(StepKind.COMPARE_TOTAL_COST, "比较总价、规则、时段和取消条件"),
                Step(StepKind.SELECT_CANDIDATE, "选择符合约束的候选项"),
                Step(StepKind.OPEN_DESTINATION, "打开对应 App 或网页"),
                Step(StepKind.VERIFY_PAGE, "核对实时库存、价格和预订条件"),
                Step(StepKind.REVIEW_ORDER, "复核预订信息与最终金额"),
                Step(StepKind.REQUEST_USER_CONFIRMATION, "付款或最终提交前由用户确认", Risk.HIGH, true),
                Step(StepKind.VERIFY_COMPLETION, "核对平台返回的预订结果")
            )
            Category.NAVIGATION -> listOf(
                Step(StepKind.CLARIFY_REQUIREMENTS, "确认出发地、目的地和出行方式"),
                Step(StepKind.DISCOVER_OPTIONS, "打开地图路线页面并获取可用路线"),
                Step(StepKind.VERIFY_PAGE, "核对起终点、路线、距离、预计耗时和交通方式"),
                Step(StepKind.REQUEST_USER_CONFIRMATION, "将路线交给用户核对，确认后再开始导航", Risk.HIGH, true),
                Step(StepKind.HANDOFF_TO_USER, "把地图导航页面交到真实主屏，由用户本人启动导航", Risk.HIGH, true)
            )
            Category.WEB_RESEARCH -> listOf(
                Step(StepKind.CLARIFY_REQUIREMENTS, "明确检索范围与判断标准"),
                Step(StepKind.DISCOVER_OPTIONS, "检索多个可靠来源"),
                Step(StepKind.COMPARE_TOTAL_COST, "交叉核对关键事实与来源"),
                Step(StepKind.VERIFY_COMPLETION, "汇总结果并标注无法确认的信息")
            )
            Category.CREATIVE_ASSET -> listOf(
                Step(StepKind.CLARIFY_REQUIREMENTS, "确认素材主题、格式、数量、清晰度与用途"),
                Step(StepKind.DISCOVER_OPTIONS, "检索可访问的素材来源并核对来源信息"),
                Step(StepKind.COMPARE_TOTAL_COST, "比较素材质量、授权范围、文件格式与下载限制"),
                Step(StepKind.SELECT_CANDIDATE, "选择符合创作要求且授权信息清晰的素材"),
                Step(StepKind.OPEN_DESTINATION, "打开素材来源或下载页面"),
                Step(StepKind.VERIFY_PAGE, "核对素材预览、来源、格式和授权说明"),
                Step(StepKind.DOWNLOAD_ASSET, "下载选定的图片、视频或音频素材"),
                Step(StepKind.IMPORT_ASSET, "将下载文件导入当前项目素材库并核验文件"),
                Step(StepKind.VERIFY_COMPLETION, "确认素材已进入当前项目并汇总来源与授权信息")
            )
            Category.FORM -> listOf(
                Step(StepKind.CLARIFY_REQUIREMENTS, "确认表单字段和提交目标"),
                Step(StepKind.OPEN_DESTINATION, "打开目标网页或应用"),
                Step(StepKind.VERIFY_PAGE, "核对表单主体与字段"),
                Step(StepKind.FILL_NON_SENSITIVE_DETAILS, "仅填写已确认的非敏感信息"),
                Step(StepKind.HANDOFF_TO_USER, "敏感字段或最终提交由用户接手", Risk.HIGH, true)
            )
            Category.GENERAL -> listOf(
                Step(StepKind.CLARIFY_REQUIREMENTS, "理解目标、边界和完成标准"),
                Step(StepKind.DISCOVER_OPTIONS, "选择可用的信息来源或工具"),
                Step(StepKind.VERIFY_COMPLETION, "核验结果，不把计划描述成已完成")
            )
        }
        return Plan(category, text, steps)
    }

    fun contextFor(prompt: String): String {
        val plan = plan(prompt)
        if (plan.category == Category.GENERAL) return ""
        val steps = plan.steps.joinToString("\n") { "- ${it.title}" }
        return """
Phone Agent 目标执行约束：
- 当前用户目标分类：${plan.category.name}。
- 先理解目标并自主收集可访问的信息，不要一听到目标就直接打开某个预设 App。
- 购物/订餐必须尽可能比较多个可用来源的最终到手价、配送费、优惠、时效、库存与商家信息；区分实时核验数据和无法访问的数据，不得编造。
- 出行导航必须先确认出发地、目的地和出行方式，再打开地图路线页面；必须在页面上核对路线与预计耗时。不得把搜索结果或估算冒充实时导航路线。路线交给用户确认后，使用现有主屏交接机制交由用户本人启动导航；不得在未确认时自动开始导航。
- 先规划，再逐步执行；每次动作前确认目标页面/控件，动作后检查页面状态。失败、页面偏离或出现登录/验证码/风控时暂停并交还用户。
- 普通低风险动作可连续执行；支付、转账、密码、验证码、最终下单/预订/提交等高风险或不可逆步骤必须停下，展示完整信息并取得用户明确确认。
- 不得替用户输入支付密码/验证码，不得绕过平台安全验证，不得声称未核验的操作已完成。手机号、邮箱、地址、姓名、身份证、银行卡等个人信息默认不自动读取或外发，确需填写时必须先向用户确认具体字段与内容。
- 任务步骤参考：
${steps}
- 创作素材任务：优先寻找可直接下载的公开媒体文件；使用 download_asset 下载后自动导入当前项目素材库。只接受图片、视频、音频文件；核对来源和授权，禁止下载来源不明或明显侵权的素材。
- 只有获得工具/页面的实际结果后，才能报告该步骤完成。
""".trimIndent()
    }
}
