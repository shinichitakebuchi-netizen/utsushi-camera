package com.kooky.utsushi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MainActivity : ComponentActivity(), PreviewRenderer.Callbacks {

    private lateinit var s: Settings
    private lateinit var root: FrameLayout
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: PreviewRenderer
    private lateinit var finder: FinderOverlay
    private lateinit var meter: MeterView
    private lateinit var top: LinearLayout
    private lateinit var bottom: LinearLayout
    private lateinit var looksScroll: HorizontalScrollView
    private lateinit var looksRow: LinearLayout
    private lateinit var tip: TextView
    private lateinit var evRuler: RulerView
    private lateinit var evVal: TextView
    private lateinit var mfRow: LinearLayout
    private lateinit var mfRuler: RulerView
    private lateinit var mfVal: TextView
    private lateinit var shutter: ShutterView
    private lateinit var thumb: ImageView
    private lateinit var zoomBar: LinearLayout
    private lateinit var toastView: TextView
    private lateinit var msg: TextView
    private val pills = HashMap<String, Pill>()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var uprightW = 1080
    private var uprightH = 1440
    private var minFocusDiopter = 0f
    private var afModes = listOf<String>()
    private var lastUri: Uri? = null
    private var devRotation = Surface.ROTATION_0
    private var busy = false

    private val procThread = HandlerThread("utsushi-proc").apply { start() }
    private val procHandler = Handler(procThread.looper)
    private lateinit var processor: Processor
    private lateinit var orientation: OrientationEventListener

    // ===== 起動 =====
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        s = Settings(this)
        processor = Processor(applicationContext)
        buildUi()
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val r = when (o) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (r != devRotation) {
                    devRotation = r
                    imageCapture?.targetRotation = r
                    val deg = when (r) { Surface.ROTATION_90 -> 90f; Surface.ROTATION_270 -> -90f; Surface.ROTATION_180 -> 180f; else -> 0f }
                    thumb.animate().rotation(deg).setDuration(250).start()
                    flipBtn.animate().rotation(deg).setDuration(250).start()
                }
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            surfaceTexture?.let { bindCamera(it) }
        } else {
            msg.text = "カメラの許可が必要です。\n設定 → アプリ → 写 → 権限 から許可してね。"
            msg.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        glView.onResume()
        if (orientation.canDetectOrientation()) orientation.enable()
    }

    override fun onPause() {
        super.onPause()
        orientation.disable()
        provider?.unbindAll()
        camera = null
        glView.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        procThread.quitSafely()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) capture()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ===== PreviewRenderer.Callbacks =====
    override fun onSurfaceTextureReady(st: SurfaceTexture) {
        runOnUiThread {
            surfaceTexture = st
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) bindCamera(st)
        }
    }

    override fun onUprightSize(w: Int, h: Int) {
        runOnUiThread {
            if (w != uprightW || h != uprightH) { uprightW = w; uprightH = h; layoutImage() }
        }
    }

    override fun onMeter(stats: MeterStats?) {
        runOnUiThread {
            if (s.meter && stats != null) meter.update(stats, if (Auto.ready) Auto.snapshot() else null, s.auto, s.ev)
            updateInfo()
        }
    }

    // ===== カメラ =====
    private fun bindCamera(st: SurfaceTexture) {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({
            val p = f.get()
            provider = p
            p.unbindAll()
            val selector = if (s.back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
            val prevSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(android.util.Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            val preview = Preview.Builder().setResolutionSelector(prevSel).build()
            preview.setSurfaceProvider(ContextCompat.getMainExecutor(this)) { req -> provideSurface(req, st) }
            val capSel = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()
            val ic = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(capSel)
                .setTargetRotation(devRotation)
                .build()
            imageCapture = ic
            try {
                val cam = p.bindToLifecycle(this, selector, preview, ic)
                camera = cam
                msg.visibility = View.GONE
                setupCameraInfo(cam)
            } catch (e: Exception) {
                msg.text = "カメラを開けませんでした\n${e.message}"
                msg.visibility = View.VISIBLE
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun provideSurface(req: SurfaceRequest, st: SurfaceTexture) {
        val res = req.resolution
        st.setDefaultBufferSize(res.width, res.height)
        renderer.bufW = res.width
        renderer.bufH = res.height
        val surface = Surface(st)
        req.provideSurface(surface, ContextCompat.getMainExecutor(this)) { surface.release() }
    }

    private fun setupCameraInfo(cam: Camera) {
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        val so = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        renderer.sensorQuarter = (so / 90) % 4
        minFocusDiopter = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val modes = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
        val av = ArrayList<String>()
        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) av.add("AF-C")
        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO)) av.add("AF-S")
        if (modes.contains(CaptureRequest.CONTROL_AF_MODE_OFF) && minFocusDiopter > 0f) av.add("MF")
        afModes = av
        if (!av.contains(s.af)) s.af = av.firstOrNull() ?: "AF-C"
        applyFocusMode()
        setupZoom(cam)
        renderToggles()
    }

    private fun setupZoom(cam: Camera) {
        zoomBar.removeAllViews()
        val zs = cam.cameraInfo.zoomState.value ?: return
        val levels = listOf(.5f, 1f, 2f, 3f, 5f).filter { it >= zs.minZoomRatio - .01f && it <= zs.maxZoomRatio + .01f }
        if (levels.size < 2) { zoomBar.visibility = View.GONE; return }
        zoomBar.visibility = View.VISIBLE
        for (z in levels) {
            val t = TextView(this).apply {
                text = if (z < 1f) ".5×" else "${z.toInt()}×"
                typeface = C.MONO; setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); gravity = Gravity.CENTER
                tag = z
            }
            val sz = dp(36f).toInt()
            zoomBar.addView(t, LinearLayout.LayoutParams(sz, sz))
            t.setOnClickListener {
                cam.cameraControl.setZoomRatio(z)
                markZoom(z)
            }
        }
        markZoom(1f)
    }

    private fun markZoom(z: Float) {
        for (i in 0 until zoomBar.childCount) {
            val t = zoomBar.getChildAt(i) as TextView
            val on = (t.tag as Float) == z
            t.setTextColor(if (on) Color.BLACK else C.FG)
            t.background = if (on) GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.FG) } else null
        }
    }

    // ===== ピント =====
    /** 0〜MF_Nの目盛り → 焦点距離（ディオプター：0が無限遠）。左端が一番近い */
    private fun mfDiopter(k: Int): Float {
        val t = 1f - k.toFloat() / Settings.MF_N
        return minFocusDiopter * t.pow(2)
    }

    private fun fmtDist(k: Int): String {
        val d = mfDiopter(k)
        if (d <= 0.05f) return "∞"
        val m = 1f / d
        return if (m >= 10f) "∞" else if (m >= 1f) String.format("%.1fm", m) else "${(m * 100).toInt()}cm"
    }

    private fun applyFocusMode() {
        val cam = camera ?: return
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        renderer.peaking = s.af == "MF"
        if (s.af == "MF") {
            cam.cameraControl.cancelFocusAndMetering()
            c2.setCaptureRequestOptions(CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, mfDiopter(s.mfk))
                .build())
        } else {
            c2.clearCaptureRequestOptions()
            cam.cameraControl.cancelFocusAndMetering()
        }
    }

    private fun applyManualDistance() {
        val cam = camera ?: return
        if (s.af != "MF") return
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, mfDiopter(s.mfk))
            .build())
    }

    private fun tapFocus(x: Float, y: Float) {
        val cam = camera ?: return
        if (s.af == "MF") { toast("MF中：下の目盛りでピントを合わせてね（オレンジの輪郭＝合っている所）"); return }
        if (afModes.isEmpty()) { finder.showAf(x, y, "非対応", false, true); return }
        val nx = x / finder.width; val ny = y / finder.height
        val bp = renderer.toBufferPoint(nx, ny)
        val pt = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(bp[0], bp[1])
        val keep = s.af == "AF-S"
        val b = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        if (keep) b.disableAutoCancel() else b.setAutoCancelDuration(4, TimeUnit.SECONDS)
        finder.showAf(x, y, if (keep) "AF-S  LOCK" else "AF-C", keep, false)
        finder.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        val fut = cam.cameraControl.startFocusAndMetering(b.build())
        fut.addListener({
            try {
                val r = fut.get()
                if (!r.isFocusSuccessful) finder.setAfNg(if (keep) "AF-S  ×" else "AF-C  ×")
            } catch (e: Exception) {
                finder.setAfNg("失敗")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ===== 撮影 =====
    private fun capture() {
        val ic = imageCapture ?: return
        if (busy) return
        busy = true; shutter.busy = true
        val fl = View(this).apply { setBackgroundColor(Color.BLACK); alpha = .85f }
        root.addView(fl, FrameLayout.LayoutParams(glView.width, glView.height).apply {
            leftMargin = (glView.layoutParams as FrameLayout.LayoutParams).leftMargin
            topMargin = (glView.layoutParams as FrameLayout.LayoutParams).topMargin
        })
        fl.animate().alpha(0f).setDuration(180).withEndAction { root.removeView(fl) }.start()
        shutter.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        val snapAuto = if (s.auto && Auto.ready) Auto.snapshot() else null
        val mirror = false
        ic.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val rot = image.imageInfo.rotationDegrees
                val buf = image.planes[0].buffer
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                image.close()
                procHandler.post {
                    try {
                        val src = processor.decode(bytes, rot)
                        val out = processor.process(src, s, snapAuto, mirror)
                        src.recycle()
                        val uri = processor.save(out)
                        val th = Bitmap.createScaledBitmap(out, 160, (160f * out.height / out.width).toInt(), true)
                        val mp = processor.mp(out)
                        out.recycle()
                        runOnUiThread {
                            lastUri = uri; thumb.setImageBitmap(th)
                            toast("保存しました（${mp}MP）")
                            busy = false; shutter.busy = false
                        }
                    } catch (e: Throwable) {
                        runOnUiThread { toast("保存に失敗：${e.message}"); busy = false; shutter.busy = false }
                    }
                }
            }

            override fun onError(e: ImageCaptureException) {
                toast("撮影に失敗：${e.message}")
                busy = false; shutter.busy = false
            }
        })
    }

    // ===== 画面づくり =====
    private lateinit var flipBtn: TextView

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)

        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = false
        }
        renderer = PreviewRenderer(glView, s, this)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        root.addView(glView, FrameLayout.LayoutParams(1, 1))

        finder = FinderOverlay(this)
        root.addView(finder, FrameLayout.LayoutParams(1, 1))
        finder.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) { tapFocus(e.x, e.y); v.performClick() }
            true
        }

        meter = MeterView(this)
        root.addView(meter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        zoomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(3f).toInt(), dp(3f).toInt(), dp(3f).toInt(), dp(3f).toInt())
            background = GradientDrawable().apply { cornerRadius = dp(24f); setColor(Color.argb(102, 0, 0, 0)) }
        }
        root.addView(zoomBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        msg = TextView(this).apply {
            setTextColor(C.DIM); gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "カメラを起動中…"; setLineSpacing(0f, 1.5f)
        }
        root.addView(msg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // --- 上部：ロゴ＋ボタン列 ---
        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f).toInt(), dp(8f).toInt(), 0, dp(8f).toInt())
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(235, 11, 11, 11), Color.argb(140, 11, 11, 11)))
        }
        val brand = TextView(this).apply {
            text = "写"; typeface = C.SERIF; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(C.FG)
        }
        top.addView(brand)
        val dot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(C.ACC) } }
        top.addView(dot, LinearLayout.LayoutParams(dp(7f).toInt(), dp(7f).toInt()).apply { leftMargin = dp(8f).toInt(); rightMargin = dp(10f).toInt() })
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(18f).toInt()) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, dp(14f).toInt(), 0) }
        hs.addView(row)
        top.addView(hs, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun addPill(key: String, label: String, toggle: Boolean, onClick: () -> Unit) {
            val p = Pill(this, label, toggle)
            p.setOnClickListener { onClick() }
            row.addView(p, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(5f).toInt() })
            pills[key] = p
        }
        addPill("auto", "AUTO", true) { s.auto = !s.auto; s.save(); renderToggles(); toast(if (s.auto) "おまかせ補正：ON（色かぶり・明るさを自動で整えます）" else "おまかせ補正：OFF") }
        addPill("meter", "METER", true) { s.meter = !s.meter; s.save(); renderToggles() }
        addPill("af", "AF-C", false) { cycleAf() }
        addPill("aspect", "3:2", false) { s.aspect = (s.aspect + 1) % ASPECTS.size; s.save(); renderToggles(); layoutImage() }
        addPill("grid", "GRID", true) { s.grid = !s.grid; s.save(); renderToggles() }
        addPill("film", "FILM", true) { s.film = !s.film; s.save(); renderToggles(); toast(if (s.film) "粒子・周辺減光：ON" else "粒子・周辺減光：OFF") }
        addPill("frame", "FRAME", true) { s.frame = !s.frame; s.save(); renderToggles(); toast(if (s.frame) "白フチ：ON" else "白フチ：OFF") }
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // --- 下部：色味・ヒント・EV・MF・シャッター ---
        bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(0, 11, 11, 11), Color.argb(190, 11, 11, 11), Color.argb(235, 11, 11, 11)))
            setPadding(0, dp(28f).toInt(), 0, dp(14f).toInt())
        }
        looksScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(60f).toInt()) }
        looksRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        looksScroll.addView(looksRow)
        LOOKS.forEachIndexed { i, l ->
            val c = LookChip(this, l.name)
            c.setOnClickListener { selectLook(i) }
            looksRow.addView(c)
        }
        bottom.addView(looksScroll)
        val line = View(this).apply { setBackgroundColor(Color.rgb(0x26, 0x26, 0x26)) }
        bottom.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f).toInt()))
        tip = TextView(this).apply {
            typeface = C.SERIF; setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); setTextColor(C.DIM); gravity = Gravity.CENTER
            letterSpacing = .06f; setPadding(0, dp(8f).toInt(), 0, dp(2f).toInt())
        }
        bottom.addView(tip)

        fun rulerRow(label: String, r: RulerView, v: TextView): LinearLayout {
            val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18f).toInt(), 0, dp(18f).toInt(), 0) }
            val t = TextView(this).apply { text = label; typeface = C.MONO; setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f); setTextColor(C.DIM); letterSpacing = .14f }
            l.addView(t, LinearLayout.LayoutParams(dp(30f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            l.addView(r, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            v.typeface = C.MONO; v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); v.setTextColor(C.FG); v.gravity = Gravity.END
            l.addView(v, LinearLayout.LayoutParams(dp(52f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
            return l
        }
        evRuler = RulerView(this).apply {
            lo = -15; hi = 15; resetValue = 0
            isMajor = { it % 5 == 0 }; isZero = { it == 0 }
            labelOf = { if (it % 5 != 0) null else if (it == 0) "0" else (if (it > 0) "+" else "−") + (kotlin.math.abs(it) / 10f).let { v -> if (v % 1f == 0f) v.toInt().toString() else v.toString() } }
            onChange = { s.ev = it / 10f; s.save(); evVal.text = fmtEV(s.ev); updateInfo() }
        }
        evVal = TextView(this)
        bottom.addView(rulerRow("EV", evRuler, evVal))
        mfRuler = RulerView(this).apply {
            lo = 0; hi = Settings.MF_N; resetValue = Settings.MF_N
            isMajor = { it % 10 == 0 }
            labelOf = { if (it == 0) "近" else if (it == Settings.MF_N) "遠" else null }
            onChange = { s.mfk = it; s.save(); mfVal.text = fmtDist(it); applyManualDistance() }
        }
        mfVal = TextView(this)
        mfRow = rulerRow("MF", mfRuler, mfVal)
        bottom.addView(mfRow)

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(34f).toInt(), dp(10f).toInt(), dp(34f).toInt(), dp(4f).toInt()) }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(12f); setColor(Color.rgb(0x16, 0x16, 0x16)); setStroke(dp(1f).toInt(), Color.rgb(0x2c, 0x2c, 0x2c)) }
            setOnClickListener { openLast() }
        }
        controls.addView(thumb, LinearLayout.LayoutParams(dp(54f).toInt(), dp(54f).toInt()))
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        shutter = ShutterView(this).apply { setOnClickListener { capture() } }
        controls.addView(shutter, LinearLayout.LayoutParams(dp(80f).toInt(), dp(80f).toInt()))
        controls.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        flipBtn = TextView(this).apply {
            text = "⟲"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f); setTextColor(C.FG); gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.rgb(0x1d, 0x1d, 0x1d), Color.rgb(0x11, 0x11, 0x11))).apply {
                shape = GradientDrawable.OVAL; setStroke(dp(1f).toInt(), Color.rgb(0x2a, 0x2a, 0x2a))
            }
            setOnClickListener {
                s.back = !s.back; s.save(); Auto.ready = false
                surfaceTexture?.let { st -> bindCamera(st) }
            }
        }
        controls.addView(flipBtn, LinearLayout.LayoutParams(dp(54f).toInt(), dp(54f).toInt()))
        bottom.addView(controls)
        root.addView(bottom, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        toastView = TextView(this).apply {
            setTextColor(C.FG); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); gravity = Gravity.CENTER
            setPadding(dp(16f).toInt(), dp(8f).toInt(), dp(16f).toInt(), dp(8f).toInt())
            background = GradientDrawable().apply { cornerRadius = dp(10f); setColor(Color.argb(235, 28, 28, 28)); setStroke(dp(1f).toInt(), Color.rgb(0x2c, 0x2c, 0x2c)) }
            alpha = 0f; maxWidth = (resources.displayMetrics.widthPixels * .88f).toInt()
        }
        root.addView(toastView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(250f).toInt()
        })

        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutImage() }
        renderToggles()
        selectLook(s.look, false)
    }

    private fun selectLook(i: Int, notify: Boolean = true) {
        s.look = i; s.save()
        for (k in 0 until looksRow.childCount) (looksRow.getChildAt(k) as LookChip).selectedLook = k == i
        tip.text = LOOKS[i].tip
        looksRow.post {
            val c = looksRow.getChildAt(i)
            val pad = looksScroll.width / 2
            if (looksRow.paddingLeft != pad) looksRow.setPadding(pad - dp(40f).toInt(), 0, pad - dp(40f).toInt(), 0)
            looksScroll.smoothScrollTo(c.left + c.width / 2 - looksScroll.width / 2, 0)
        }
        if (notify) finder.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        updateInfo()
    }

    private fun cycleAf() {
        if (afModes.size < 2) {
            toast("切替不可：この端末で使えるピントは「${afModes.joinToString(" / ").ifEmpty { "なし" }}」のみ"); return
        }
        s.af = afModes[(afModes.indexOf(s.af) + 1) % afModes.size]; s.save()
        finder.hideAf()
        applyFocusMode()
        renderToggles()
        layoutImage()
        toast(when (s.af) {
            "AF-C" -> "AF-C：自動で合わせ続けます（タップで合わせる位置を指定）"
            "AF-S" -> "AF-S：タップした所で合わせて固定"
            else -> "MF：目盛りで手動。オレンジの輪郭＝ピントが合っている所"
        })
    }

    private fun renderToggles() {
        pills["auto"]?.on = s.auto
        pills["meter"]?.on = s.meter
        pills["grid"]?.on = s.grid
        pills["film"]?.on = s.film
        pills["frame"]?.on = s.frame
        pills["aspect"]?.text = ASPECTS[s.aspect].label
        pills["af"]?.text = s.af
        meter.visibility = if (s.meter) View.VISIBLE else View.GONE
        finder.grid = s.grid
        finder.invalidate()
        evRuler.set(Math.round(s.ev * 10)); evVal.text = fmtEV(s.ev)
        mfRow.visibility = if (s.af == "MF" && afModes.contains("MF")) View.VISIBLE else View.GONE
        mfRuler.set(s.mfk); mfVal.text = fmtDist(s.mfk)
        updateInfo()
    }

    private fun updateInfo() {
        val tot = (if (s.auto && Auto.ready) Auto.exp else 0f) + s.ev
        val ev = fmtEV(tot)
        finder.infoBold = ev
        finder.info = "${if (s.auto) "A" else "M"}  $ev  EV  ${LOOKS[s.look].name}  ${ASPECTS[s.aspect].label}"
        finder.invalidate()
    }

    /** 写真の表示位置：上のボタン列のすぐ下から、横幅いっぱい */
    private fun layoutImage() {
        if (root.width == 0) return
        val a = targetAspect(s.aspect, uprightW.toFloat(), uprightH.toFloat())
        val rw = root.width.toFloat(); val rh = root.height.toFloat()
        val top0 = top.height.toFloat()
        val bot = bottom.top + looksScroll.top.toFloat()
        val avH = rh - top0
        var w: Float; var h: Float
        if (rw / avH > a) { h = avH; w = h * a } else { w = rw; h = w / a }
        val left = (rw - w) / 2
        val topY = top0 + max(0f, (bot - top0 - h) / 2)
        fun place(v: View) {
            val lp = v.layoutParams as FrameLayout.LayoutParams
            if (lp.width != w.toInt() || lp.height != h.toInt() || lp.leftMargin != left.toInt() || lp.topMargin != topY.toInt()) {
                lp.width = w.toInt(); lp.height = h.toInt(); lp.leftMargin = left.toInt(); lp.topMargin = topY.toInt(); lp.gravity = Gravity.TOP or Gravity.START
                v.layoutParams = lp
            }
        }
        place(glView); place(finder)
        val ml = meter.layoutParams as FrameLayout.LayoutParams
        val mlx = (left + dp(12f)).toInt(); val mty = (topY + dp(34f)).toInt()
        if (ml.leftMargin != mlx || ml.topMargin != mty) { ml.leftMargin = mlx; ml.topMargin = mty; ml.gravity = Gravity.TOP or Gravity.START; meter.layoutParams = ml }
        val zl = zoomBar.layoutParams as FrameLayout.LayoutParams
        val zy = (min(topY + h, bot) - dp(58f)).toInt()
        if (zl.topMargin != zy) { zl.topMargin = zy; zl.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; zoomBar.layoutParams = zl }
    }

    private fun openLast() {
        val u = lastUri ?: run { toast("まだ撮影していません"); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast("写真を開けませんでした")
        }
    }

    private fun toast(t: String) {
        toastView.text = t
        toastView.animate().cancel()
        toastView.alpha = 1f
        toastView.animate().alpha(0f).setStartDelay(1800).setDuration(300).start()
    }
}
