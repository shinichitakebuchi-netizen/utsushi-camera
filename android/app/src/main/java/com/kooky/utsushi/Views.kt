package com.kooky.utsushi

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ===== 共通の色・寸法 =====
object C {
    val ACC = Color.rgb(0xff, 0x7a, 0x1a)
    val BLUE = Color.rgb(0x4a, 0x7f, 0xd4)
    val FG = Color.rgb(0xf2, 0xf0, 0xeb)
    val DIM = Color.rgb(0x8a, 0x87, 0x80)
    val MUTE = Color.rgb(0x5a, 0x58, 0x53)
    val BG = Color.rgb(0x0b, 0x0b, 0x0b)
    val MONO: Typeface = Typeface.MONOSPACE
    val SERIF: Typeface = Typeface.create("serif", Typeface.NORMAL)
}

fun Context.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
fun View.dp(v: Float) = context.dp(v)

// ===== LEDつきボタン（トグル）／切替ボタン =====
@SuppressLint("ViewConstructor")
class Pill(ctx: Context, label: String, private val toggle: Boolean) : TextView(ctx) {
    var on = false
        set(v) { field = v; refresh() }
    private val led = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        text = label
        typeface = C.MONO
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = .12f
        gravity = Gravity.CENTER
        val ph = dp(10f).toInt()
        setPadding(if (toggle) dp(20f).toInt() else ph, dp(7f).toInt(), if (toggle) ph else dp(18f).toInt(), dp(7f).toInt())
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.rgb(0x1b, 0x1b, 0x1b), Color.rgb(0x12, 0x12, 0x12))).apply {
            cornerRadius = dp(8f); setStroke(dp(1f).toInt(), Color.rgb(0x27, 0x27, 0x27))
        }
        refresh()
    }

    private fun refresh() {
        setTextColor(if (!toggle || on) C.FG else C.DIM)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        if (toggle) {
            if (on) {
                led.color = C.ACC; led.setShadowLayer(dp(4f), 0f, 0f, C.ACC)
            } else {
                led.color = Color.rgb(0x38, 0x38, 0x38); led.clearShadowLayer()
            }
            canvas.drawCircle(dp(11f), cy, dp(2.6f), led)
        } else {
            // ▼（押すと切り替わる印）
            led.clearShadowLayer(); led.color = C.MUTE
            val x = width - dp(11f)
            val path = android.graphics.Path().apply {
                moveTo(x - dp(3f), cy - dp(1.5f)); lineTo(x + dp(3f), cy - dp(1.5f)); lineTo(x, cy + dp(2.5f)); close()
            }
            canvas.drawPath(path, led)
        }
    }
}

// ===== 目盛りダイヤル（左右ドラッグで1目盛りずつ。ダブルタップで初期値） =====
class RulerView(ctx: Context) : View(ctx) {
    var lo = -15
    var hi = 15
    var value = 0
        private set
    var resetValue = 0
    var labelOf: (Int) -> String? = { null }
    var isMajor: (Int) -> Boolean = { it % 5 == 0 }
    var isZero: (Int) -> Boolean = { false }
    var onChange: (Int) -> Unit = {}

    private val step get() = dp(10f)
    private var dragging = false
    private var startX = 0f
    private var startVal = 0f
    private var offset = 0f   // ドラッグ中の小数位置
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            set(resetValue, true); return true
        }
        override fun onDown(e: MotionEvent) = true
    })

    fun set(v: Int, notify: Boolean = false) {
        val nv = v.coerceIn(lo, hi)
        val changed = nv != value
        value = nv
        if (!dragging) offset = nv.toFloat()
        invalidate()
        if (changed && notify) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange(nv)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        gd.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true; startX = e.x; startVal = value.toFloat(); offset = startVal
            }
            MotionEvent.ACTION_MOVE -> {
                offset = (startVal - (e.x - startX) / step).coerceIn(lo.toFloat(), hi.toFloat())
                val v = offset.roundToInt()
                if (v != value) {
                    value = v; performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onChange(v)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false; offset = value.toFloat(); invalidate()
            }
        }
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(42f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val h = height.toFloat()
        val pos = if (dragging) offset else value.toFloat()
        pt.textAlign = Paint.Align.CENTER
        pt.typeface = C.MONO
        pt.textSize = dp(8.5f)
        for (i in lo..hi) {
            val x = cx + (i - pos) * step
            if (x < -step || x > width + step) continue
            val fade = (1f - abs(x - cx) / (width * .5f)).coerceIn(0f, 1f)
            val a = (255 * min(1f, fade * 1.8f)).toInt()
            val major = isMajor(i)
            val len = if (isZero(i)) dp(15f) else if (major) dp(13f) else dp(7f)
            pt.color = if (isZero(i)) C.FG else if (major) Color.rgb(0x86, 0x83, 0x7c) else Color.rgb(0x45, 0x45, 0x45)
            pt.alpha = a
            pt.strokeWidth = dp(1f)
            canvas.drawLine(x, h - dp(8f) - len, x, h - dp(8f), pt)
            labelOf(i)?.let {
                pt.color = C.MUTE; pt.alpha = a
                canvas.drawText(it, x, dp(11f), pt)
            }
        }
        // 針
        pt.alpha = 255
        pt.color = C.ACC
        pt.setShadowLayer(dp(4f), 0f, 0f, C.ACC)
        canvas.drawRoundRect(RectF(cx - dp(1f), h - dp(26f), cx + dp(1f), h - dp(5f)), dp(1f), dp(1f), pt)
        pt.clearShadowLayer()
    }
}

// ===== シャッター（ギザギザの縁つき） =====
class ShutterView(ctx: Context) : View(ctx) {
    var busy = false
        set(v) { field = v; invalidate() }
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pressedNow = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedNow = true; invalidate() }
            MotionEvent.ACTION_UP -> { pressedNow = false; invalidate(); performClick() }
            MotionEvent.ACTION_CANCEL -> { pressedNow = false; invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(cx, cy)
        // ローレット
        pt.style = Paint.Style.STROKE
        pt.strokeWidth = dp(1.6f)
        for (i in 0 until 120) {
            val a = Math.toRadians(i * 3.0)
            pt.color = if (i % 2 == 0) Color.rgb(0xe8, 0xe6, 0xe0) else Color.rgb(0x8f, 0x8c, 0x85)
            canvas.drawLine(cx + (r - dp(5f)) * cos(a).toFloat(), cy + (r - dp(5f)) * sin(a).toFloat(),
                cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat(), pt)
        }
        pt.style = Paint.Style.FILL
        pt.shader = null
        pt.color = C.BG
        canvas.drawCircle(cx, cy, r - dp(5f), pt)
        val ir = (r - dp(9f)) * (if (pressedNow) .9f else 1f)
        if (busy) {
            pt.color = C.ACC
        } else {
            pt.shader = RadialGradient(cx - ir * .3f, cy - ir * .4f, ir * 1.5f,
                intArrayOf(Color.WHITE, Color.rgb(0xe6, 0xe3, 0xdc), Color.rgb(0xc4, 0xc0, 0xb6)),
                floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(cx, cy, ir, pt)
        pt.shader = null
    }
}

// ===== 色味ダイヤルの1項目 =====
class LookChip(ctx: Context, label: String) : TextView(ctx) {
    var selectedLook = false
        set(v) { field = v; setTextColor(if (v) C.FG else C.MUTE); invalidate() }
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        text = label
        typeface = C.MONO
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        letterSpacing = .16f
        setPadding(dp(11f).toInt(), dp(13f).toInt(), dp(11f).toInt(), dp(12f).toInt())
        setTextColor(C.MUTE)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        if (selectedLook) {
            pt.color = C.ACC; pt.setShadowLayer(dp(4f), 0f, 0f, C.ACC)
            canvas.drawCircle(cx, dp(4f), dp(2.6f), pt)
            pt.clearShadowLayer()
            canvas.drawRect(cx - dp(1f), height - dp(9f), cx + dp(1f), height.toFloat(), pt)
        } else {
            pt.color = Color.rgb(0x3c, 0x3c, 0x3c)
            canvas.drawRect(cx - dp(.5f), height - dp(5f), cx + dp(.5f), height.toFloat(), pt)
        }
    }
}

// ===== 色味ダイヤル（横スクロール。選んだ色味が真ん中に来る） =====
class LooksDial(ctx: Context, private val onSelect: (Int) -> Unit) : android.widget.HorizontalScrollView(ctx) {
    private val row = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.HORIZONTAL }

    init {
        isHorizontalScrollBarEnabled = false
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(60f).toInt())
        addView(row)
        LOOKS.forEachIndexed { i, l ->
            val c = LookChip(ctx, l.name)
            c.setOnClickListener { onSelect(i) }
            row.addView(c)
        }
    }

    fun select(i: Int) {
        for (k in 0 until row.childCount) (row.getChildAt(k) as LookChip).selectedLook = k == i
        post {
            val c = row.getChildAt(i) ?: return@post
            val pad = width / 2 - dp(40f).toInt()
            if (row.paddingLeft != pad && pad > 0) row.setPadding(pad, 0, pad, 0)
            row.post { smoothScrollTo(c.left + c.width / 2 - width / 2, 0) }
        }
    }
}

// ===== ファインダー（四隅のカギ・グリッド・情報表示・ピント枠） =====
class FinderOverlay(ctx: Context) : View(ctx) {
    var grid = true
    /** 水平器：端末の傾き（度、時計回りが正）。nullなら非表示 */
    var levelAngle: Float? = null
    var info = ""
    var infoBold = ""
    private var afX = 0f
    private var afY = 0f
    private var afLabel = ""
    private var afT = 0L
    private var afKeep = false
    private var afNg = false
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)

    fun showAf(x: Float, y: Float, label: String, keep: Boolean, ng: Boolean) {
        afX = x; afY = y; afLabel = label; afKeep = keep; afNg = ng; afT = SystemClock.uptimeMillis()
        invalidate()
    }

    fun setAfNg(label: String) { afNg = true; afLabel = label; invalidate() }

    fun hideAf() { afT = 0; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        pt.style = Paint.Style.STROKE
        if (grid) {
            pt.color = Color.argb(56, 255, 255, 255); pt.strokeWidth = dp(1f)
            for (k in 1..2) {
                canvas.drawLine(w * k / 3, 0f, w * k / 3, h, pt)
                canvas.drawLine(0f, h * k / 3, w, h * k / 3, pt)
            }
        }
        // 水平器：線が水平（オレンジ）になるように構える
        levelAngle?.let { ang ->
            val base = Math.round(ang / 90f) * 90f
            val d = ang - base
            val ok = abs(d) < 1f
            val cx = w / 2; val cy = h / 2; val half = dp(60f)
            canvas.save(); canvas.rotate(-base, cx, cy)
            pt.color = Color.argb(150, 255, 255, 255); pt.strokeWidth = dp(1.5f)
            canvas.drawLine(cx - half - dp(16f), cy, cx - half - dp(4f), cy, pt)
            canvas.drawLine(cx + half + dp(4f), cy, cx + half + dp(16f), cy, pt)
            canvas.restore()
            canvas.save(); canvas.rotate(-ang, cx, cy)
            pt.color = if (ok) C.ACC else Color.argb(200, 255, 255, 255); pt.strokeWidth = dp(2f)
            if (ok) pt.setShadowLayer(dp(4f), 0f, 0f, C.ACC) else pt.setShadowLayer(dp(2f), 0f, 0f, Color.argb(160, 0, 0, 0))
            canvas.drawLine(cx - half, cy, cx + half, cy, pt)
            pt.clearShadowLayer()
            canvas.restore()
        }
        // 四隅
        pt.color = Color.argb(190, 255, 255, 255); pt.strokeWidth = dp(1.5f)
        val m = dp(9f); val l = dp(16f)
        canvas.drawLine(m, m, m + l, m, pt); canvas.drawLine(m, m, m, m + l, pt)
        canvas.drawLine(w - m, m, w - m - l, m, pt); canvas.drawLine(w - m, m, w - m, m + l, pt)
        canvas.drawLine(m, h - m, m + l, h - m, pt); canvas.drawLine(m, h - m, m, h - m - l, pt)
        canvas.drawLine(w - m, h - m, w - m - l, h - m, pt); canvas.drawLine(w - m, h - m, w - m, h - m - l, pt)
        // 上部中央の情報（LED風）
        pt.style = Paint.Style.FILL
        pt.typeface = C.MONO; pt.textSize = dp(10.5f); pt.textAlign = Paint.Align.LEFT; pt.letterSpacing = .08f
        val full = info
        val tw = pt.measureText(full)
        var x = (w - tw) / 2
        val y = dp(24f)
        // 太字部分（数値）だけ白
        val i = if (infoBold.isEmpty()) -1 else full.indexOf(infoBold)
        pt.setShadowLayer(dp(4f), 0f, 0f, Color.argb(140, 255, 122, 26))
        if (i < 0) {
            pt.color = C.ACC; canvas.drawText(full, x, y, pt)
        } else {
            val a = full.substring(0, i); val b = infoBold; val c = full.substring(i + b.length)
            pt.color = C.ACC; canvas.drawText(a, x, y, pt); x += pt.measureText(a)
            pt.color = Color.WHITE; pt.isFakeBoldText = true; pt.setShadowLayer(dp(2f), 0f, dp(1f), Color.BLACK)
            canvas.drawText(b, x, y, pt); x += pt.measureText(b); pt.isFakeBoldText = false
            pt.color = C.ACC; pt.setShadowLayer(dp(4f), 0f, 0f, Color.argb(140, 255, 122, 26))
            canvas.drawText(c, x, y, pt)
        }
        pt.clearShadowLayer()
        // ピント枠
        if (afT > 0) {
            val dt = SystemClock.uptimeMillis() - afT
            val alpha = when {
                dt < 1400 -> 255
                afKeep -> 110
                else -> 0
            }
            if (alpha > 0) {
                val sc = if (dt < 300) 1.45f - .45f * (dt / 300f) else 1f
                val half = dp(35f) * sc; val cl = dp(14f) * sc
                val col = if (afNg) Color.rgb(0x9a, 0x97, 0x8f) else C.ACC
                pt.color = col; pt.alpha = alpha; pt.style = Paint.Style.STROKE; pt.strokeWidth = dp(1.5f)
                pt.setShadowLayer(dp(2f), 0f, 0f, Color.argb(160, 0, 0, 0))
                val l0 = afX - half; val r0 = afX + half; val t0 = afY - half; val b0 = afY + half
                canvas.drawLine(l0, t0, l0 + cl, t0, pt); canvas.drawLine(l0, t0, l0, t0 + cl, pt)
                canvas.drawLine(r0, t0, r0 - cl, t0, pt); canvas.drawLine(r0, t0, r0, t0 + cl, pt)
                canvas.drawLine(l0, b0, l0 + cl, b0, pt); canvas.drawLine(l0, b0, l0, b0 - cl, pt)
                canvas.drawLine(r0, b0, r0 - cl, b0, pt); canvas.drawLine(r0, b0, r0, b0 - cl, pt)
                pt.style = Paint.Style.FILL
                canvas.drawCircle(afX, afY, dp(2f), pt)
                pt.textAlign = Paint.Align.CENTER; pt.textSize = dp(9.5f); pt.isFakeBoldText = true
                canvas.drawText(afLabel, afX, b0 + dp(14f), pt)
                pt.isFakeBoldText = false; pt.clearShadowLayer(); pt.alpha = 255
                if (dt < 1500) postInvalidateOnAnimation()
            }
        }
    }
}

// ===== 測光メーター（小さく・薄く） =====
class MeterView(ctx: Context) : View(ctx) {
    private var st: MeterStats? = null
    private var auto: AutoResult? = null
    private var autoOn = true
    private var ev = 0f
    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)

    fun update(s: MeterStats?, a: AutoResult?, autoOn: Boolean, ev: Float) {
        st = s; auto = a; this.autoOn = autoOn; this.ev = ev; invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dp(150f).toInt(), dp(122f).toInt())
    }

    private fun pc(v: Float) = if (v > 0 && v * 100 < 1) "<1%" else "${(v * 100).roundToInt()}%"

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        pt.style = Paint.Style.FILL
        pt.color = Color.argb(107, 8, 8, 8)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), dp(8f), dp(8f), pt)
        pt.style = Paint.Style.STROKE; pt.strokeWidth = dp(1f); pt.color = Color.argb(20, 255, 255, 255)
        canvas.drawRoundRect(RectF(.5f, .5f, w - .5f, h - .5f), dp(8f), dp(8f), pt)
        pt.style = Paint.Style.FILL
        pt.setShadowLayer(dp(1.5f), 0f, dp(.5f), Color.argb(150, 0, 0, 0))
        val pad = dp(8f)
        // 見出し
        pt.typeface = C.SERIF; pt.textSize = dp(10f); pt.color = C.FG; pt.textAlign = Paint.Align.LEFT; pt.letterSpacing = .2f
        canvas.drawText("測光", pad, pad + dp(9f), pt)
        val a = auto
        val tag = if (autoOn && a != null) when (a.mode) {
            "mid" -> "中間基準"; "hi" -> "明部優先"; "max" -> "上限 +1.6"; "min" -> "下限 −1.0"; else -> "AUTO"
        } else "MANUAL"
        pt.typeface = C.MONO; pt.textSize = dp(8f); pt.letterSpacing = 0f
        val tw = pt.measureText(tag)
        val tagCol = if (autoOn && a != null) C.ACC else C.DIM
        pt.color = tagCol; pt.textAlign = Paint.Align.RIGHT
        canvas.drawText(tag, w - pad - dp(4f), pad + dp(8.5f), pt)
        pt.style = Paint.Style.STROKE; pt.strokeWidth = dp(1f); pt.clearShadowLayer()
        pt.color = Color.argb(128, Color.red(tagCol), Color.green(tagCol), Color.blue(tagCol))
        canvas.drawRoundRect(RectF(w - pad - tw - dp(8f), pad, w - pad, pad + dp(11.5f)), dp(3f), dp(3f), pt)
        pt.style = Paint.Style.FILL
        // ヒストグラム
        val hx = pad; val hy = pad + dp(16f); val hw = w - pad * 2; val hh = dp(26f)
        val bw = hw / 48
        pt.color = Color.argb(41, 74, 127, 212); canvas.drawRect(hx, hy, hx + bw * 3, hy + hh, pt)
        pt.color = Color.argb(41, 255, 122, 26); canvas.drawRect(hx + hw - bw, hy, hx + hw, hy + hh, pt)
        val s = st
        if (s != null) {
            val mx = max(1, s.hist.maxOrNull() ?: 1)
            for (i in 0 until 48) {
                val bh = sqrt(s.hist[i].toFloat() / mx) * (hh - dp(2f))
                pt.color = if (i >= 47) C.ACC else if (i < 3) C.BLUE else Color.argb(184, 242, 240, 235)
                canvas.drawRect(hx + i * bw + .5f, hy + hh - bh, hx + (i + 1) * bw - .5f, hy + hh, pt)
            }
            pt.color = Color.argb(140, 255, 255, 255)
            canvas.drawRect(hx + s.p50 / 256f * hw, hy, hx + s.p50 / 256f * hw + dp(1f), hy + hh, pt)
        }
        pt.color = Color.argb(51, 255, 255, 255)
        canvas.drawRect(hx, hy + hh, hx + hw, hy + hh + dp(1f), pt)
        pt.setShadowLayer(dp(1.5f), 0f, dp(.5f), Color.argb(150, 0, 0, 0))
        // 暗部・中間・明部
        var y = hy + hh + dp(14f)
        val cols = floatArrayOf(hx + hw / 6, hx + hw / 2, hx + hw * 5 / 6)
        val lab = arrayOf("暗部", "中間", "明部")
        val vals = if (s != null) arrayOf("${s.p5}", "${s.p50}", "${s.p95}") else arrayOf("—", "—", "—")
        for (k in 0..2) {
            pt.textAlign = Paint.Align.RIGHT; pt.typeface = Typeface.DEFAULT; pt.textSize = dp(7.5f); pt.color = C.DIM
            canvas.drawText(lab[k], cols[k] - dp(1f), y, pt)
            pt.textAlign = Paint.Align.LEFT; pt.typeface = C.MONO; pt.textSize = dp(11f); pt.color = C.FG
            canvas.drawText(vals[k], cols[k] + dp(1f), y, pt)
        }
        // 黒つぶれ・平均・白飛び
        y += dp(12f)
        pt.textSize = dp(8f)
        if (s != null) {
            pt.textAlign = Paint.Align.LEFT; pt.typeface = Typeface.DEFAULT; pt.color = C.DIM
            canvas.drawText("黒つぶれ", hx, y, pt)
            pt.typeface = C.MONO; pt.color = if (s.dk >= .05f) C.BLUE else C.FG
            canvas.drawText(pc(s.dk), hx + pt.measureText("黒つぶれ") + dp(10f), y, pt)
            pt.textAlign = Paint.Align.CENTER; pt.typeface = C.MONO; pt.color = C.FG
            canvas.drawText("平均 ${s.mean}", hx + hw * .55f, y, pt)
            pt.textAlign = Paint.Align.RIGHT; pt.color = if (s.cl >= .02f) C.ACC else C.FG
            canvas.drawText("白飛び " + pc(s.cl), hx + hw, y, pt)
        }
        // EV
        pt.clearShadowLayer()
        y += dp(6f)
        pt.color = Color.argb(20, 255, 255, 255); canvas.drawRect(hx, y, hx + hw, y + dp(1f), pt)
        pt.setShadowLayer(dp(1.5f), 0f, dp(.5f), Color.argb(150, 0, 0, 0))
        y += dp(11f)
        pt.textAlign = Paint.Align.LEFT; pt.typeface = C.MONO; pt.textSize = dp(8.5f)
        pt.color = C.DIM; canvas.drawText("EV", hx, y, pt)
        pt.color = C.FG
        val evText = if (autoOn && a != null) "A ${fmtEV(a.exp)} 手動 ${fmtEV(ev)} → ${fmtEV(a.exp + ev)}" else "手動のみ ${fmtEV(ev)}"
        canvas.drawText(evText, hx + dp(16f), y, pt)
        // WB
        y += dp(13f)
        pt.color = C.DIM; canvas.drawText("WB", hx, y, pt)
        val bx = hx + dp(16f); val by = y - dp(3f); val bL = dp(34f)
        pt.clearShadowLayer()
        pt.shader = LinearGradient(bx, 0f, bx + bL, 0f, intArrayOf(C.BLUE, Color.rgb(0x4a, 0x4a, 0x4a), C.ACC), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(bx, by - dp(1.5f), bx + bL, by + dp(1.5f)), dp(1.5f), dp(1.5f), pt)
        pt.shader = null
        var t = 0f
        val wbText: String
        if (autoOn && a != null) {
            t = (Math.log(a.wb[0].toDouble() / a.wb[2]) / Math.log(2.0)).toFloat()
            wbText = if (abs(t) < .03f) "ほぼ中立" else (if (t > 0) "暖め " else "冷まし ") +
                    "${((Math.pow(2.0, abs(t).toDouble()) - 1) * 100).roundToInt()}%"
        } else wbText = "補正なし"
        val dx = bx + bL / 2 + (t / .8f).coerceIn(-1f, 1f) * bL / 2
        pt.color = Color.BLACK; canvas.drawCircle(dx, by, dp(4.2f), pt)
        pt.color = Color.WHITE; canvas.drawCircle(dx, by, dp(3f), pt)
        pt.setShadowLayer(dp(1.5f), 0f, dp(.5f), Color.argb(150, 0, 0, 0))
        pt.color = C.FG; pt.typeface = Typeface.DEFAULT
        canvas.drawText(wbText, bx + bL + dp(6f), y, pt)
        pt.clearShadowLayer()
    }
}
