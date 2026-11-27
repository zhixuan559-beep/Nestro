package app.nestro.phone

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.nestro.MainActivity
import kotlin.math.roundToInt

/**
 * 悬浮胶囊（步骤 b：单对话主界面骨架，学 Mika）。
 * 离开 App 时在屏幕上层报任务进度：可拖动、点按回 App、点 × 关闭；
 * 需要用户接手时圆点变琥珀色提醒（其余时间白点，黑白单色纪律）。
 *
 * 挂在无障碍服务上，用 TYPE_ACCESSIBILITY_OVERLAY，不用额外悬浮窗权限；
 * 服务未连接时所有调用都是空操作，绝不抛异常、不影响任务执行。
 */
class PhoneAgentCapsule(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density

    private var capsuleView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var labelView: TextView? = null
    private var dotView: View? = null

    /** 用户点了 ×：本次隐藏，直到下一次任务开始才再出现。 */
    @Volatile
    var dismissed: Boolean = false
        private set

    fun show(label: String) = showOrUpdate(label, needsUser = false, forceShow = true)

    fun update(label: String, needsUser: Boolean) = showOrUpdate(label, needsUser, forceShow = false)

    fun hide() {
        mainHandler.post {
            runCatching { capsuleView?.let { windowManager.removeView(it) } }
            capsuleView = null
            params = null
            labelView = null
            dotView = null
        }
    }

    private fun showOrUpdate(label: String, needsUser: Boolean, forceShow: Boolean) {
        mainHandler.post {
            if (dismissed && !forceShow) return@post
            if (forceShow) dismissed = false
            val view = capsuleView ?: buildCapsule().also { capsuleView = it }
            labelView?.text = label
            dotView?.background = dotDrawable(needsUser)
            if (params == null) {
                params = capsuleParams()
                runCatching { windowManager.addView(view, params) }
                    .onFailure {
                        capsuleView = null
                        params = null
                        labelView = null
                        dotView = null
                    }
            }
        }
    }

    private fun dotDrawable(needsUser: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (needsUser) Color.parseColor("#E8A33D") else Color.WHITE)
        }

    private fun buildCapsule(): LinearLayout {
        val dotSize = (8 * density).roundToInt()
        val dot = View(service).apply {
            layoutParams = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                marginEnd = (8 * density).roundToInt()
            }
            background = dotDrawable(needsUser = false)
        }
        dotView = dot
        val label = TextView(service).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        labelView = label
        val close = TextView(service).apply {
            text = "×"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding((10 * density).roundToInt(), 0, 0, 0)
            setOnClickListener {
                dismissed = true
                hide()
            }
        }
        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(
                (14 * density).roundToInt(),
                (10 * density).roundToInt(),
                (12 * density).roundToInt(),
                (10 * density).roundToInt()
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20 * density
                setColor(Color.parseColor("#111111"))
            }
            addView(dot)
            addView(label)
            addView(close)
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        row.setOnTouchListener { _, event ->
            val p = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = p.x
                    startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).roundToInt()
                    val dy = (event.rawY - downY).roundToInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    p.x = startX + dx
                    p.y = startY + dy
                    runCatching { windowManager.updateViewLayout(row, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        val intent = Intent(service, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        runCatching { service.startActivity(intent) }
                    }
                    true
                }
                else -> false
            }
        }
        return row
    }

    private fun capsuleParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (24 * density).roundToInt()
            y = (96 * density).roundToInt()
        }
}
