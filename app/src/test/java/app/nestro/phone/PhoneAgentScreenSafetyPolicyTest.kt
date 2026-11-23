package app.nestro.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAgentScreenSafetyPolicyTest {
    @Test
    fun authenticationAndAntiAbuseScreensRequireUserHandoff() {
        listOf(
            "请输入验证码",
            "请完成滑动验证",
            "安全验证后继续",
            "检测到异常访问，请稍后再试",
            "请先登录后继续",
            "请输入登录密码",
            "Please enter verification code",
            "Complete the CAPTCHA",
            "Login to continue"
        ).forEach { assertTrue(it, PhoneAgentScreenSafetyPolicy.requiresUserHandoff(it)) }
    }

    @Test
    fun consentAndAndroidPermissionScreensRequireUserHandoff() {
        listOf(
            "Accept all cookies",
            "I agree to the terms",
            "请阅读并同意用户协议",
            "允许 Nestro 访问位置信息",
            "Allow Nestro to access your location",
            "Allow Nestro to access nearby devices",
            "Only this time · Location",
            "Don't allow Nestro to use the camera",
            "Terms of Service · Agree"
        ).forEach { assertTrue(it, PhoneAgentScreenSafetyPolicy.requiresUserHandoff(it)) }
    }

    @Test
    fun ordinaryPagesAndInformationalMentionsDoNotTriggerHandoff() {
        listOf(
            "商品详情：咖啡 价格 29 元",
            "查看账号安全说明",
            "登录入口在页面右上角",
            "验证码是什么？"
        ).forEach { assertFalse(it, PhoneAgentScreenSafetyPolicy.requiresUserHandoff(it)) }
    }

    @Test
    fun navigationStartRequiresExplicitUserHandoff() {
        assertTrue(
            PhoneAgentScreenSafetyPolicy.requiresNavigationStartHandoff(
                "Google Maps · 驾车 · 12 分钟 · 8.5 公里",
                "开始导航"
            )
        )
        assertTrue(
            PhoneAgentScreenSafetyPolicy.requiresNavigationStartHandoff(
                "Directions · 12 min · 8.5 km",
                "Start"
            )
        )
        assertFalse(
            PhoneAgentScreenSafetyPolicy.requiresNavigationStartHandoff(
                "Google Maps · 12 分钟 · 8.5 公里",
                "添加途经点"
            )
        )
        assertFalse(
            PhoneAgentScreenSafetyPolicy.requiresNavigationStartHandoff(
                "商品详情：可查看导航功能",
                "开始"
            )
        )
    }
}
