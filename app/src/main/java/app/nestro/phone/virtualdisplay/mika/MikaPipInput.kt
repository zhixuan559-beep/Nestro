/*
 * Adapted from Mika (https://github.com/wangzan101/Mika).
 * Copyright (C) 2026 wangzan101.
 * Licensed under the Apache License, Version 2.0.
 * Original: app/src/main/java/com/wangzan101/mika/cap/phone/display/PipInput.kt
 * Adaptation: package namespace only.
 */
package app.nestro.phone.virtualdisplay.mika

/** Normalized input coordinates for a user-operated virtual-screen preview. */
interface MikaPipInput {
    suspend fun tap(fx: Float, fy: Float)
    suspend fun swipe(points: List<Pair<Float, Float>>, durationMs: Long)
    suspend fun back()
}
