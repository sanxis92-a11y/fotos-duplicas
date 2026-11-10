package com.sanxis.fotosduplicadas

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Pantalla de entrada: una calculadora que funciona de verdad.
 * Escribiendo el código secreto y pulsando «=» se abre la app.
 */
class CalculatorActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var exprView: TextView
    private lateinit var resultView: TextView
    private var expr = ""          // forma interna: dígitos, . + - * / %
    private var justEvaluated = false

    private var dark = false
    private var cBg = 0
    private var cKey = 0
    private var cOp = 0
    private var cText = 0
    private var cSub = 0
    private var cAccent = 0

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun round(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun ripple(content: Drawable, radius: Int): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000),
            content, round(Color.WHITE, radius)
        )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        if (!settings.lockEnabled) {
            openApp()
            return
        }

        dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        fun c(l: String, n: String) = Color.parseColor(if (dark) n else l)
        cBg = c("#F4F5FA", "#0F1115")
        cKey = c("#FFFFFF", "#1E2129")
        cOp = c("#E6E8F7", "#262A45")
        cText = c("#15171C", "#EEF0F5")
        cSub = c("#6B7080", "#9AA0AE")
        cAccent = c("#4F46E5", "#818CF8")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cBg)
        }
        val pad = dp(16)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(b.left + pad, b.top + pad, b.right + pad, b.bottom + pad)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft + pad, insets.systemWindowInsetTop + pad,
                    insets.systemWindowInsetRight + pad, insets.systemWindowInsetBottom + pad
                )
            }
            insets
        }

        // Pantalla
        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM or Gravity.END
            setPadding(dp(8), dp(8), dp(8), dp(20))
        }
        exprView = TextView(this).apply {
            textSize = 30f
            setTextColor(cText)
            gravity = Gravity.END
            maxLines = 2
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            if (Build.VERSION.SDK_INT >= 26) {
                setAutoSizeTextTypeUniformWithConfiguration(18, 44, 1, TypedValue.COMPLEX_UNIT_SP)
            }
        }
        resultView = TextView(this).apply {
            textSize = 26f
            setTextColor(cSub)
            gravity = Gravity.END
            maxLines = 1
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        display.addView(exprView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(110)))
        display.addView(resultView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(display, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Teclado
        val keys = listOf(
            listOf("C", "⌫", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("±", "0", ",", "=")
        )
        val pad4 = dp(5)
        for (rowKeys in keys) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in rowKeys) {
                val isOp = k in setOf("÷", "×", "−", "+", "%", "⌫", "C", "±")
                val isEq = k == "="
                val b = TextView(this).apply {
                    text = k
                    gravity = Gravity.CENTER
                    textSize = if (k.length == 1 && k[0].isDigit()) 28f else 26f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    setTextColor(
                        when {
                            isEq -> Color.WHITE
                            k == "C" -> Color.parseColor(if (dark) "#F87171" else "#DC2626")
                            isOp -> cAccent
                            else -> cText
                        }
                    )
                    background = ripple(round(if (isEq) cAccent else if (isOp) cOp else cKey, 24), 24)
                    isClickable = true
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        press(k)
                    }
                }
                if (k == "⌫") b.setOnLongClickListener { press("C"); true }
                row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    setMargins(pad4, pad4, pad4, pad4)
                })
            }
            root.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(78)))
        }

        setContentView(root)
        render()
    }

    // ---------------------------------------------------------------- Lógica

    private fun isOp(c: Char) = c == '+' || c == '-' || c == '*' || c == '/'

    private fun press(k: String) {
        when (k) {
            "C" -> { expr = ""; justEvaluated = false }
            "⌫" -> { if (expr.isNotEmpty()) expr = expr.dropLast(1); justEvaluated = false }
            "=" -> { equals(); return }
            "±" -> toggleSign()
            "," -> {
                if (justEvaluated) { expr = ""; justEvaluated = false }
                val last = lastNumber()
                if (!last.contains('.')) expr += if (last.isEmpty()) "0." else "."
            }
            "%" -> if (expr.isNotEmpty() && expr.last().isDigit()) expr += "%"
            "÷", "×", "−", "+" -> {
                val op = when (k) { "÷" -> '/'; "×" -> '*'; "−" -> '-'; else -> '+' }
                justEvaluated = false
                if (expr.isEmpty()) {
                    if (op == '-') expr = "-"
                } else if (isOp(expr.last())) {
                    expr = expr.dropLast(1) + op
                } else if (expr.last() != '.') {
                    expr += op
                }
            }
            else -> {   // dígito
                if (justEvaluated) { expr = ""; justEvaluated = false }
                if (expr.length < 40) expr += k
            }
        }
        render()
    }

    private fun lastNumber(): String {
        var i = expr.length
        while (i > 0 && (expr[i - 1].isDigit() || expr[i - 1] == '.')) i--
        return expr.substring(i)
    }

    private fun toggleSign() {
        val num = lastNumber()
        if (num.isEmpty()) return
        val start = expr.length - num.length
        val before = expr.substring(0, start)
        expr = if (before.endsWith("-") && (before.length == 1 || isOp(before[before.length - 2]))) {
            before.dropLast(1) + num
        } else {
            before + "-" + num
        }
    }

    private fun equals() {
        if (expr.isEmpty()) return
        // ¿Es el código secreto?
        if (Settings.hashCode(expr) == settings.codeHash) {
            expr = ""
            render()
            openApp()
            return
        }
        val r = evaluate(expr)
        if (r == null) {
            resultView.text = "Error"
            return
        }
        expr = format(r).replace(",", ".")
        justEvaluated = true
        exprView.text = format(r)
        resultView.text = ""
    }

    private fun render() {
        exprView.text = pretty(expr).ifEmpty { "0" }
        val hasOp = expr.drop(1).any { isOp(it) } || expr.contains('%')
        val r = if (hasOp) evaluate(expr) else null
        resultView.text = if (r != null) "= " + format(r) else ""
    }

    private fun pretty(s: String) = s.replace('*', '×').replace('/', '÷').replace('-', '−').replace('.', ',')

    private fun format(v: BigDecimal): String {
        val r = v.round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        val str = if (r.abs() >= BigDecimal("1e15") || (r.signum() != 0 && r.abs() < BigDecimal("1e-9"))) {
            String.format(java.util.Locale.US, "%.6e", r.toDouble())
        } else {
            r.toPlainString()
        }
        return str.replace('.', ',').replace("-", "−")
    }

    /** Evalúa con precedencia (× ÷ antes que + −) y % como «dividir entre 100». */
    private fun evaluate(s: String): BigDecimal? {
        try {
            var t = s
            while (t.isNotEmpty() && (isOp(t.last()) || t.last() == '.')) t = t.dropLast(1)
            if (t.isEmpty() || t == "-") return null
            val nums = ArrayList<BigDecimal>()
            val ops = ArrayList<Char>()
            var i = 0
            var expectNumber = true
            while (i < t.length) {
                val ch = t[i]
                if (expectNumber) {
                    var j = i
                    if (t[j] == '-') j++
                    while (j < t.length && (t[j].isDigit() || t[j] == '.')) j++
                    var n = BigDecimal(t.substring(i, j))
                    while (j < t.length && t[j] == '%') { n = n.divide(BigDecimal(100)); j++ }
                    nums += n
                    i = j
                    expectNumber = false
                } else {
                    if (!isOp(ch)) return null
                    ops += ch
                    i++
                    expectNumber = true
                }
            }
            // Primero × y ÷
            val n2 = ArrayList<BigDecimal>()
            val o2 = ArrayList<Char>()
            n2 += nums[0]
            for (k in ops.indices) {
                val op = ops[k]
                val b = nums[k + 1]
                when (op) {
                    '*' -> n2[n2.size - 1] = n2.last().multiply(b)
                    '/' -> {
                        if (b.signum() == 0) return null
                        n2[n2.size - 1] = n2.last().divide(b, MathContext(20, RoundingMode.HALF_UP))
                    }
                    else -> { o2 += op; n2 += b }
                }
            }
            var acc = n2[0]
            for (k in o2.indices) acc = if (o2[k] == '+') acc.add(n2[k + 1]) else acc.subtract(n2[k + 1])
            return acc
        } catch (_: Exception) {
            return null
        }
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_UNLOCKED, true))
        overridePendingTransitionCompat()
        finish()
    }

    @Suppress("DEPRECATION")
    private fun overridePendingTransitionCompat() {
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
