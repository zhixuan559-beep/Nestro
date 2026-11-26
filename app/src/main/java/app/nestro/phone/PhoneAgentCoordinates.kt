package app.nestro.phone

/**
 * 坐标点按的纯换算：无 Android 依赖，可在 JVM 单测里直接验证。
 * 坐标一律用千分比（0..1000），与设备像素无关；模型只使用屏幕快照里
 * 实际标注的坐标，不得编造。
 */
object PhoneAgentCoordinates {
    /** 千分比坐标是否合法。 */
    fun isValid(xPermille: Int, yPermille: Int): Boolean =
        xPermille in 0..1000 && yPermille in 0..1000

    /** 节点中心点的千分比坐标；bounds 为空或屏幕尺寸无效时返回 null。 */
    fun centerPermille(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        displayWidth: Int,
        displayHeight: Int
    ): Pair<Int, Int>? {
        if (displayWidth <= 0 || displayHeight <= 0) return null
        if (right <= left || bottom <= top) return null
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        return (centerX * 1000 / displayWidth).coerceIn(0, 1000) to
            (centerY * 1000 / displayHeight).coerceIn(0, 1000)
    }

    /** 千分比坐标换算为设备像素。 */
    fun toPixels(permille: Int, size: Int): Int {
        require(permille in 0..1000) { "坐标超出范围" }
        require(size > 0) { "屏幕尺寸无效" }
        return permille * size / 1000
    }

    /** 快照行尾标注的坐标后缀，例如 [x=540,y=960]。 */
    fun suffix(xPermille: Int, yPermille: Int): String = "[x=$xPermille,y=$yPermille]"
}
