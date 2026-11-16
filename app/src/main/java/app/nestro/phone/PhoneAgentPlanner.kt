package app.nestro.phone

/** Goal-driven task planning contract. Planning never grants permission or performs actions. */
object PhoneAgentPlanner {
    enum class Category { SHOPPING, FOOD_ORDER, BOOKING, WEB_RESEARCH, FORM, DEVICE_TASK, GENERAL }
    enum class Risk { LOW, MEDIUM, HIGH }
    enum class StepKind { CLARIFY_REQUIREMENTS, DISCOVER_OPTIONS, COMPARE_TOTAL_COST, SELECT_CANDIDATE, OPEN_DESTINATION, VERIFY_PAGE, FILL_NON_SENSITIVE_DETAILS, REVIEW_ORDER, REQUEST_USER_CONFIRMATION, HANDOFF_TO_USER, VERIFY_COMPLETION }

    fun categoryLabel(category: String): String = when (category) {
        Category.SHOPPING.name -> "购物"
        Category.FOOD_ORDER.name -> "外卖/点餐"
        Category.BOOKING.name -> "预约/预订"
        Category.WEB_RESEARCH.name -> "网页检索"
        Category.FORM.name -> "表单处理"
        Category.DEVICE_TASK.name -> "手机操作"
        Category.GENERAL.name -> "通用任务"
        else -> "其他任务"
    }
    data class Step(val kind: StepKind, val title: String, val risk: Risk = Risk.LOW, val requiresUserConfirmation: Boolean = false)
    data class Plan(val category: Category, val goal: String, val steps: List<Step>, val requiresHumanAtCommit: Boolean = true)

    fun plan(prompt: String): Plan {
        val text = prompt.trim()
        val normalized = text.lowercase()
        val diningReservation = Regex("(预约|预订|预定|订位|订座|订桌|订餐厅|订).{0,8}(餐厅|饭店|座位|餐位|包间)|餐厅.{0,6}(预约|预订|预定|订位|订座|订)")
            .containsMatchIn(normalized)
        val deviceTask = listOf("帮我打开", "请帮我打开", "打开微信", "打开抖音", "打开小红书", "打开设置", "打开相机", "打开浏览器", "打开应用", "启动应用", "帮我点击", "帮我操作", "开启蓝牙", "关闭蓝牙", "开启定位", "关闭定位", "发消息给", "发短信给", "发邮件给", "拨打电话", "打电话给").any { normalized.contains(it) } ||
            Regex("(帮我|请帮我).{0,8}(打开|启动|关闭|退出|切换|开启|关掉|点击|操作|设置).{0,12}(应用|软件|微信|抖音|小红书|设置|相机|浏览器|计算器|蓝牙|定位|热点|手电筒|闹钟)").containsMatchIn(normalized) ||
            Regex("(帮我|请帮我)?给.{1,12}(发消息|发短信|发邮件|打电话)").containsMatchIn(normalized) ||
            (listOf("在手机上", "手机上", "手机里").any { normalized.contains(it) } && listOf("打开", "启动", "点击", "操作", "发消息", "发短信", "发邮件", "拨打", "打电话", "开启", "关闭", "切换").any { normalized.contains(it) })
        val category = when {
            diningReservation || listOf("预约", "预订", "预定", "订票", "买票", "订位", "订座", "订桌", "酒店", "机票", "火车票", "电影票", "演出票", "门票", "挂号").any { normalized.contains(it) } -> Category.BOOKING
            listOf("外卖", "点餐", "订餐", "奶茶", "吃饭", "点一份").any { normalized.contains(it) } -> Category.FOOD_ORDER
            listOf("买", "购买", "下单", "购物", "咖啡", "商品", "比价").any { normalized.contains(it) } -> Category.SHOPPING
            listOf("搜索", "查找", "调查", "对比", "资料", "网页").any { normalized.contains(it) } -> Category.WEB_RESEARCH
            listOf("填写", "提交表单", "申请", "报名").any { normalized.contains(it) } -> Category.FORM
            deviceTask -> Category.DEVICE_TASK
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
            Category.WEB_RESEARCH -> listOf(
                Step(StepKind.CLARIFY_REQUIREMENTS, "明确检索范围与判断标准"),
                Step(StepKind.DISCOVER_OPTIONS, "检索多个可靠来源"),
                Step(StepKind.COMPARE_TOTAL_COST, "交叉核对关键事实与来源"),
                Step(StepKind.VERIFY_COMPLETION, "汇总结果并标注无法确认的信息")
            )
            Category.FORM -> listOf(
                Step(StepKind.CLARIFY_REQUIREMENTS, "确认表单字段和提交目标"),
                Step(StepKind.OPEN_DESTINATION, "打开目标网页或应用"),
                Step(StepKind.VERIFY_PAGE, "核对表单主体与字段"),
                Step(StepKind.FILL_NON_SENSITIVE_DETAILS, "仅填写已确认的非敏感信息"),
                Step(StepKind.HANDOFF_TO_USER, "敏感字段或最终提交由用户接手", Risk.HIGH, true)
            )
            Category.DEVICE_TASK -> listOf(
                Step(StepKind.CLARIFY_REQUIREMENTS, "明确目标应用、操作对象与完成标准"),
                Step(StepKind.OPEN_DESTINATION, "打开目标应用或页面"),
                Step(StepKind.VERIFY_PAGE, "核对当前页面与操作对象"),
                Step(StepKind.FILL_NON_SENSITIVE_DETAILS, "仅填写已确认的非敏感内容"),
                Step(StepKind.VERIFY_COMPLETION, "根据页面证据核验目标是否完成")
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
- 先规划，再逐步执行；每次动作前确认目标页面/控件，动作后检查页面状态。失败、页面偏离或出现登录/验证码/风控时暂停并交还用户。
- 普通低风险动作可连续执行；支付、转账、密码、验证码、最终下单/预订/提交等高风险或不可逆步骤必须停下，展示完整信息并取得用户明确确认。
- 不得替用户输入支付密码/验证码，不得绕过平台安全验证，不得声称未核验的操作已完成。手机号、邮箱、地址、姓名、身份证、银行卡等个人信息默认不自动读取或外发，确需填写时必须先向用户确认具体字段与内容。
- 任务步骤参考：
${steps}
- 只有获得工具/页面的实际结果后，才能报告该步骤完成。
""".trimIndent()
    }
}
