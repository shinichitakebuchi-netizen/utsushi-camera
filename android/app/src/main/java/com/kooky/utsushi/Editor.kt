package com.kooky.utsushi

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 読み込んだ写真の編集画面（Web版の編集画面と同じ操作）
 * ・左右スワイプで色味切替　・長押しで元の写真　・EV／AUTO／FILM／FRAME／比率／METER
 * 画像処理はすべて procHandler のスレッドで行う（GLを使うため）
 */
class Editor(
    private val act: Activity,
    private val s: Settings,
    private val processor: Processor,
    private val procHandler: Handler,
    private val toast: (String) -> Unit,
    private val onSettingsChanged: () -> Unit
) {
    val view: FrameLayout = FrameLayout(act).apply { setBackgroundColor(C.BG); visibility = View.GONE; isClickable = true }
    val isOpen get() = view.visibility == View.VISIBLE

    private val image = ImageView(act).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
    private val meter = MeterView(act)
    private val nameFlash = TextView(act)
    private val busyView = TextView(act)
    private val info = TextView(act)
    private val dial: LooksDial
    private val tip = TextView(act)
    private val ev = RulerView(act)
    private val evVal = TextView(act)
    private val pills = HashMap<String, Pill>()

    private var full: Bitmap? = null
    private var small: Bitmap? = null
    private var auto: AutoResult? = null
    private var shown: Bitmap? = null
    @Volatile private var gen = 0
    private var original = false

    init {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        view.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 上部：キャンセル／情報／保存
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f).toInt(), dp(10f).toInt(), dp(12f).toInt(), dp(10f).toInt())
        }
        bar.addView(button("キャンセル", false) { close() })
        info.apply { typeface = C.MONO; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); setTextColor(C.DIM); gravity = Gravity.CENTER }
        bar.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(button("保存", true) { save() })
        col.addView(bar)

        // 写真
        val wrap = FrameLayout(act)
        wrap.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wrap.addView(meter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(12f).toInt(); topMargin = dp(12f).toInt()
        })
        nameFlash.apply {
            typeface = C.MONO; setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f); letterSpacing = .3f; setTextColor(Color.WHITE)
            setShadowLayer(dp(6f), 0f, dp(1f), Color.argb(160, 0, 0, 0)); alpha = 0f; paint.isFakeBoldText = true
        }
        wrap.addView(nameFlash, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        busyView.apply {
            text = "処理中…"; setTextColor(C.FG); gravity = Gravity.CENTER; setBackgroundColor(Color.argb(150, 0, 0, 0))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); letterSpacing = .1f; visibility = View.GONE; isClickable = true
        }
        wrap.addView(busyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        col.addView(wrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setupGestures()

        val hint = TextView(act).apply {
            text = "← 左右にスワイプで色味を切替 ／ 長押しで元の写真 →"; setTextColor(C.MUTE); gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f); setPadding(0, dp(6f).toInt(), 0, 0)
        }
        col.addView(hint)
        dial = LooksDial(act) { setLook(it) }
        col.addView(dial)
        col.addView(View(act).apply { setBackgroundColor(Color.rgb(0x26, 0x26, 0x26)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f).toInt()))
        tip.apply {
            typeface = C.SERIF; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); setTextColor(C.DIM); gravity = Gravity.CENTER
            setPadding(0, dp(8f).toInt(), 0, dp(2f).toInt())
        }
        col.addView(tip)

        // EV
        val evRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18f).toInt(), 0, dp(18f).toInt(), 0) }
        evRow.addView(TextView(act).apply { text = "EV"; typeface = C.MONO; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); setTextColor(C.DIM) },
            LinearLayout.LayoutParams(dp(30f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        ev.apply {
            lo = -15; hi = 15; resetValue = 0; isMajor = { it % 5 == 0 }; isZero = { it == 0 }
            labelOf = { if (it % 5 != 0) null else if (it == 0) "0" else (if (it > 0) "+" else "−") + (abs(it) / 10f).let { v -> if (v % 1f == 0f) v.toInt().toString() else v.toString() } }
            onChange = { s.ev = it / 10f; s.save(); evVal.text = fmtEV(s.ev); onSettingsChanged(); render() }
        }
        evRow.addView(ev, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        evVal.apply { typeface = C.MONO; setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); setTextColor(C.FG); gravity = Gravity.END }
        evRow.addView(evVal, LinearLayout.LayoutParams(dp(52f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
        col.addView(evRow)

        // 切替ボタン
        val hs = HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false }
        val opts = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(12f).toInt(), dp(8f).toInt(), dp(12f).toInt(), dp(16f).toInt()) }
        hs.addView(opts)
        fun pill(key: String, label: String, toggle: Boolean, f: () -> Unit) {
            val p = Pill(act, label, toggle); p.setOnClickListener { f(); s.save(); onSettingsChanged(); refresh(); render() }
            opts.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(5f).toInt() })
            pills[key] = p
        }
        pill("auto", "AUTO", true) { s.auto = !s.auto }
        pill("meter", "METER", true) { s.meter = !s.meter }
        pill("film", "FILM", true) { s.film = !s.film }
        pill("frame", "FRAME", true) { s.frame = !s.frame }
        pill("aspect", "3:2", false) { s.aspect = (s.aspect + 1) % ASPECTS.size }
        pill("res", "MAX", false) { s.res = (s.res + 1) % RESS.size; toast("保存サイズ：" + RESS[s.res].label) }
        col.addView(hs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun dp(v: Float) = act.dp(v)

    private fun button(t: String, pri: Boolean, f: () -> Unit) = TextView(act).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); letterSpacing = .06f
        setTextColor(if (pri) Color.BLACK else C.FG); paint.isFakeBoldText = pri
        setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(8f).toInt())
        background = GradientDrawable().apply {
            cornerRadius = dp(8f)
            if (pri) setColor(C.ACC) else { setColor(Color.rgb(0x17, 0x17, 0x17)); setStroke(dp(1f).toInt(), Color.rgb(0x27, 0x27, 0x27)) }
        }
        setOnClickListener { f() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val gd = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                if (abs(dx) > dp(40f) && abs(vx) > abs(vy)) {
                    val n = LOOKS.size
                    setLook(((s.look + if (dx < 0) 1 else -1) % n + n) % n, true)
                    return true
                }
                return false
            }
            override fun onLongPress(e: MotionEvent) {
                original = true
                small?.let { image.setImageBitmap(it) }
                info.text = "元の写真"
            }
        })
        image.setOnTouchListener { _, e ->
            gd.onTouchEvent(e)
            if ((e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) && original) {
                original = false
                shown?.let { image.setImageBitmap(it) }
                updateInfo()
            }
            true
        }
    }

    fun open(uri: Uri) {
        view.visibility = View.VISIBLE
        busyView.text = "読み込み中…"; busyView.visibility = View.VISIBLE
        image.setImageDrawable(null)
        refresh()
        procHandler.post {
            try {
                val f = processor.decodeUri(uri)
                val k = minOf(1f, 1400f / maxOf(f.width, f.height))
                val sm = if (k < 1f) Bitmap.createScaledBitmap(f, (f.width * k).toInt().coerceAtLeast(1), (f.height * k).toInt().coerceAtLeast(1), true) else f
                // おまかせ補正は小さくした画像から判断
                val a64 = Bitmap.createScaledBitmap(sm, 64, 48, true)
                val buf = ByteBuffer.allocateDirect(64 * 48 * 4).order(ByteOrder.nativeOrder())
                a64.copyPixelsToBuffer(buf); buf.position(0); a64.recycle()
                val a = Auto.analyze(buf, 64, 48)
                act.runOnUiThread {
                    full = f; small = sm; auto = a
                    busyView.visibility = View.GONE
                    refresh(); render()
                }
            } catch (e: Throwable) {
                act.runOnUiThread { toast("読み込めませんでした：${e.message}"); close() }
            }
        }
    }

    fun close() {
        view.visibility = View.GONE
        val f = full; val sm = small; val sh = shown
        full = null; small = null; shown = null; auto = null
        image.setImageDrawable(null)
        procHandler.post {
            if (sh != null && sh !== sm) sh.recycle()
            if (sm != null && sm !== f) sm.recycle()
            f?.recycle()
        }
    }

    private fun setLook(i: Int, flash: Boolean = false) {
        s.look = i; s.save(); onSettingsChanged()
        if (flash) {
            nameFlash.text = LOOKS[i].name
            nameFlash.animate().cancel(); nameFlash.alpha = 1f
            nameFlash.animate().alpha(0f).setStartDelay(500).setDuration(350).start()
        }
        refresh(); render()
    }

    private fun refresh() {
        pills["auto"]?.on = s.auto
        pills["meter"]?.on = s.meter
        pills["film"]?.on = s.film
        pills["frame"]?.on = s.frame
        pills["aspect"]?.text = ASPECTS[s.aspect].label
        pills["res"]?.text = RESS[s.res].label
        meter.visibility = if (s.meter) View.VISIBLE else View.GONE
        dial.select(s.look)
        tip.text = LOOKS[s.look].tip
        ev.set(Math.round(s.ev * 10)); evVal.text = fmtEV(s.ev)
        updateInfo()
    }

    private fun updateInfo() {
        val f = full ?: run { info.text = ""; return }
        val c = cropFor(s.aspect, f.width.toFloat(), f.height.toFloat())
        var w = f.width * c[2]; var h = f.height * c[3]
        val mp = RESS[s.res].mp
        if (mp > 0 && w * h > mp * 1e6f) { val k = Math.sqrt(mp * 1e6 / (w * h)).toFloat(); w *= k; h *= k }
        info.text = "${w.toInt()}×${h.toInt()}  ${LOOKS[s.look].name}"
    }

    /** プレビューを描き直す（連続操作は最後の1回だけ処理） */
    private fun render() {
        val sm = small ?: return
        val g = ++gen
        val a = auto
        procHandler.post {
            if (g != gen) return@post
            try {
                val out = processor.process(sm, s, if (s.auto) a else null, false, 1400)
                val st = if (s.meter) {
                    val m = Bitmap.createScaledBitmap(out, 80, maxOf(8, 80 * out.height / out.width), true)
                    val buf = ByteBuffer.allocateDirect(m.width * m.height * 4).order(ByteOrder.nativeOrder())
                    m.copyPixelsToBuffer(buf); buf.position(0)
                    measure(buf, m.width, m.height).also { m.recycle() }
                } else null
                act.runOnUiThread {
                    if (small !== sm) { out.recycle(); return@runOnUiThread }
                    val old = shown
                    shown = out
                    if (!original) image.setImageBitmap(out)
                    if (st != null) meter.update(st, a, s.auto, s.ev)
                    if (old != null && old !== sm) procHandler.post { old.recycle() }
                }
            } catch (e: Throwable) {
                act.runOnUiThread { toast("表示に失敗：${e.message}") }
            }
        }
    }

    private fun save() {
        val f = full ?: return
        busyView.text = "保存中…"; busyView.visibility = View.VISIBLE
        val a = auto
        procHandler.post {
            try {
                val out = processor.process(f, s, if (s.auto) a else null, false)
                processor.save(out)
                val mp = processor.mp(out)
                out.recycle()
                act.runOnUiThread { busyView.visibility = View.GONE; toast("保存しました（${mp}MP）"); close() }
            } catch (e: Throwable) {
                act.runOnUiThread { busyView.visibility = View.GONE; toast("保存に失敗：${e.message}") }
            }
        }
    }
}
