/*
 * Adapted from Mika (https://github.com/wangzan101/Mika).
 * Copyright (C) 2026 wangzan101.
 * Licensed under the Apache License, Version 2.0.
 * Original: app/src/main/java/com/wangzan101/mika/cap/phone/display/PhoneViewport.kt
 * Adaptation: package namespace only.
 */
package app.nestro.phone.virtualdisplay.mika

import kotlin.math.roundToInt

internal data class PhoneViewport(val width: Int, val height: Int, val dpi: Int)

/** Keep the virtual phone's layout density usable while scaling to the available viewport. */
internal fun phoneViewport(width: Int, height: Int, deviceDpi: Int): PhoneViewport {
    require(width > 0 && height > 0 && deviceDpi > 0)
    val scale = minOf(1f, 2048f / maxOf(width, height))
    val w = (width * scale).roundToInt().coerceAtLeast(1)
    val h = (height * scale).roundToInt().coerceAtLeast(1)
    val dpi = minOf(deviceDpi * scale, w * 160f / 320f).roundToInt().coerceIn(72, 1000)
    return PhoneViewport(w, h, dpi)
}
