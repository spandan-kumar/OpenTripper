package io.opentripper

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Shared native-View design tokens and components. No connection or navigation state lives here. */
object AppUi {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()
    fun color(context: Context, id: Int) = context.getColor(id)
    fun background(context: Context) = color(context, R.color.canvas)
    fun ink(context: Context) = color(context, R.color.ink)
    fun muted(context: Context) = color(context, R.color.muted)
    fun accent(context: Context) = color(context, R.color.accent)
    fun shape(context: Context, fill: Int, radius: Int = 20, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(context, radius).toFloat()
        stroke?.let { setStroke(dp(context, 1), it) }
    }
    fun insets(activity: Activity, root: View) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        activity.window.statusBarColor = background(activity)
        activity.window.navigationBarColor = background(activity)
        val light = activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(activity.window, root).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, value ->
            val bars = value.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            value
        }
        ViewCompat.requestApplyInsets(root)
    }
    fun column(context: Context, parent: LinearLayout? = null, padding: Int = 0) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, padding), dp(context, padding), dp(context, padding), dp(context, padding))
        parent?.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    fun scroll(context: Context, content: LinearLayout) = ScrollView(context).apply {
        isFillViewport = true; clipToPadding = false
        val bounds = FrameLayout(context)
        bounds.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(bounds)
        addOnLayoutChangeListener { _, _, _, right, _, _, _, oldRight, _ ->
            if (right != oldRight) {
                val lp = content.layoutParams as FrameLayout.LayoutParams
                val width = minOf(width, dp(context, 680))
                if (lp.width != width) { lp.width = width; content.layoutParams = lp }
            }
        }
    }
    fun page(activity: Activity, title: String, subtitle: String? = null): LinearLayout {
        val root = column(activity).apply { setBackgroundColor(background(activity)) }
        val content = column(activity, padding = 24)
        root.addView(scroll(activity, content), LinearLayout.LayoutParams(-1, -1))
        activity.setContentView(root); insets(activity, root)
        button(content, "‹  Back", quiet = true) { activity.finish() }.apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL; setPadding(0, dp(context, 8), 0, dp(context, 8))
        }
        text(content, title, 32f, bold = true)
        subtitle?.let { text(content, it, 15f, secondary = true) }
        return content
    }
    fun text(parent: LinearLayout, value: String, size: Float = 16f, secondary: Boolean = false, bold: Boolean = false): TextView = TextView(parent.context).apply {
        text = value; textSize = size; setTextColor(if (secondary) muted(context) else ink(context))
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(context, 3).toFloat(), 1.08f)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 12) })
        if (size >= 22f) ViewCompat.setAccessibilityHeading(this, true)
    }
    fun label(parent: LinearLayout, value: String) = text(parent, value.uppercase(), 12f, secondary = true, bold = true).apply {
        letterSpacing = .12f; setPadding(0, dp(context, 16), 0, 0)
    }
    fun card(parent: LinearLayout, dark: Boolean = false): LinearLayout = column(parent.context, padding = 20).apply {
        background = shape(context, color(context, if (dark) R.color.hero else R.color.surface), 24,
            if (dark) null else color(context, R.color.outline))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 16) })
    }
    fun button(parent: LinearLayout, label: String, primary: Boolean = false, quiet: Boolean = false, danger: Boolean = false, action: () -> Unit): Button = Button(parent.context).apply {
        text = label; isAllCaps = false; textSize = 16f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(context, if (quiet) 48 else 56); minimumWidth = dp(context, 48)
        gravity = Gravity.CENTER; setPadding(dp(context, 18), dp(context, 12), dp(context, 18), dp(context, 12))
        val fill = color(context, if (primary) R.color.accent else if (quiet) R.color.canvas else R.color.surface_alt)
        val fg = color(context, if (danger) R.color.danger else if (primary) R.color.on_accent else R.color.ink)
        background = RippleDrawable(ColorStateList.valueOf(color(context, R.color.ripple)), shape(context, fill, 16), null)
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(muted(context), fg)))
        stateListAnimator = null
        setOnClickListener { action() }
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 10) })
    }
    fun styleInput(view: EditText) = view.apply {
        minHeight = dp(context, 56); textSize = 16f; setTextColor(ink(context)); setHintTextColor(muted(context))
        background = shape(context, color(context, R.color.surface_alt), 14, color(context, R.color.control_outline))
        setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 12) }
    }
    fun styleSpinner(view: Spinner) = view.apply {
        minimumHeight = dp(context, 56); setPaddingRelative(dp(context, 8), 0, dp(context, 40), 0)
        val chevron = context.getDrawable(R.drawable.ic_chevron_down)!!.mutate().apply { setTint(ink(context)) }
        background = LayerDrawable(arrayOf(shape(context, color(context, R.color.surface_alt), 14, color(context, R.color.control_outline)), chevron)).apply {
            setLayerSize(1, dp(context, 20), dp(context, 20))
            setLayerGravity(1, Gravity.END or Gravity.CENTER_VERTICAL)
            setLayerInsetRelative(1, 0, 0, dp(context, 12), 0)
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 12) }
    }
    fun styleToggle(view: CompoundButton) = view.apply {
        minHeight = dp(context, 56); textSize = 15f; setTextColor(ink(context))
        setPadding(dp(context, 4), dp(context, 8), dp(context, 4), dp(context, 8))
    }
    fun disclosure(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit): LinearLayout {
        val body = column(parent.context)
        val toggle = button(parent, "$title  +", quiet = true) {}
        toggle.setOnClickListener {
            val expanded = body.visibility == View.GONE
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle.text = "$title  ${if (expanded) "−" else "+"}"
            ViewCompat.setStateDescription(toggle, if (expanded) "Expanded" else "Collapsed")
        }
        ViewCompat.setStateDescription(toggle, "Collapsed")
        parent.addView(body, LinearLayout.LayoutParams(-1, -2)); build(body); body.visibility = View.GONE
        return body
    }
    fun linkRow(parent: LinearLayout, title: String, subtitle: String, action: () -> Unit) {
        val row = card(parent)
        text(row, "$title  ›", 19f, bold = true)
        text(row, subtitle, 14f, secondary = true).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = 0 }
        row.isClickable = true; row.isFocusable = true; row.contentDescription = "$title. $subtitle"
        row.foreground = RippleDrawable(ColorStateList.valueOf(color(row.context, R.color.ripple)), null, shape(row.context, android.graphics.Color.WHITE, 24))
        row.setOnClickListener { action() }
    }
}
