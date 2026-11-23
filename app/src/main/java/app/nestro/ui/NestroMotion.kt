package app.nestro.ui

/**
 * Shared motion timings for the chat-first interface.
 *
 * Keep transitions short and state-driven. Animation must never gate business actions.
 */
internal object NestroMotion {
    const val FAST = 120
    const val FAST_OUT = 100
    const val STANDARD = 160
    const val STANDARD_OUT = 120
    const val DRAWER_IN = 180
    const val DRAWER_OUT = 160
    const val FADE_IN = 140
}
