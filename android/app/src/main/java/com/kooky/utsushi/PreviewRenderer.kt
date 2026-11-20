package com.kooky.utsushi

import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * カメラ映像（SurfaceTexture）に色味をかけて画面へ描く。
 * 0.25秒ごとに、元の映像からおまかせ補正を計算し、出来上がりの絵から測光メーターの値を出す。
 */
class PreviewRenderer(
    private val view: GLSurfaceView,
    private val settings: Settings,
    private val callbacks: Callbacks
) : GLSurfaceView.Renderer {

    interface Callbacks {
        /** 新しいSurfaceTextureが用意できた（GLスレッドから呼ばれる） */
        fun onSurfaceTextureReady(st: SurfaceTexture)
        /** 映像の縦横（表示上の向き）が分かった／変わった */
        fun onUprightSize(w: Int, h: Int)
        fun onMeter(stats: MeterStats?)
    }

    @Volatile var bufW = 1440
    @Volatile var bufH = 1080
    /** 変換行列に回転が含まれない端末向けの追加回転（90°単位） */
    @Volatile var sensorQuarter = 1
    @Volatile var peaking = false

    private var st: SurfaceTexture? = null
    private var oesTex = 0
    private var lutTex = 0
    private lateinit var prog: GlProgram
    private lateinit var ana: Fbo
    private lateinit var met: Fbo
    private val tm = FloatArray(16)
    private var vw = 1
    private var vh = 1
    private var lastT = 0L
    private var frame = 0
    private var lastUpright = 0L
    private var hasFrame = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prog = GlProgram(true)
        oesTex = GlProgram.textureOES()
        val bmp = view.context.assets.open("lut.png").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })
        }
        lutTex = GlProgram.texture2D(bmp)
        bmp?.recycle()
        ana = Fbo(64, 48)
        met = Fbo(80, 60)
        hasFrame = false
        st?.release()
        val s = SurfaceTexture(oesTex)
        s.setOnFrameAvailableListener { view.requestRender() }
        st = s
        callbacks.onSurfaceTextureReady(s)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        vw = width; vh = height
    }

    /** 回転込みで、表示上の向きにしたときの縦横 */
    private fun uprightSwap(): Boolean {
        val matrixRot = abs(tm[0]) < .5f
        val q = if (matrixRot) 0 else sensorQuarter
        return matrixRot xor (q % 2 == 1)
    }

    private fun extraRot(): Int = if (abs(tm[0]) < .5f) 0 else sensorQuarter

    override fun onDrawFrame(gl: GL10?) {
        val s = st ?: return
        try {
            s.updateTexImage()
        } catch (e: Exception) {
            return
        }
        s.getTransformMatrix(tm)
        hasFrame = true
        val swap = uprightSwap()
        val uw = if (swap) bufH else bufW
        val uh = if (swap) bufW else bufH
        val key = uw.toLong() * 100000 + uh
        if (key != lastUpright) { lastUpright = key; callbacks.onUprightSize(uw, uh) }

        val a = if (settings.auto && Auto.ready) Auto.snapshot() else null
        val p = buildParams(settings, a)
        val d = Draw(
            crop = cropFor(settings.aspect, uw.toFloat(), uh.toFloat()),
            look = settings.look, exp = p.exp, wb = p.wb, clar = p.clar, nr = p.nr,
            px = floatArrayOf(1f / uw, 1f / uh), grain = p.grain, vig = p.vig,
            seed = (frame++ % 60) * 1.37f, gsize = 1f, res = floatArrayOf(vw.toFloat(), vh.toFloat()),
            tm = tm, rot = extraRot(), peak = peaking
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, vw, vh)
        prog.draw(oesTex, lutTex, d)

        val now = SystemClock.uptimeMillis()
        if (now - lastT > 250) {
            lastT = now
            // おまかせ補正：切り抜き前の元映像で判断
            if (settings.auto) {
                ana.bind()
                prog.draw(oesTex, lutTex, Draw(tm = tm, rot = extraRot(), orig = true))
                Auto.update(Auto.analyze(ana.read(), ana.w, ana.h))
            }
            // 測光メーター：出来上がりの絵（粒子・ピーキングなし）を測る
            if (settings.meter) {
                met.bind()
                val d2 = Draw(
                    crop = d.crop, look = d.look, exp = d.exp, wb = d.wb, clar = d.clar, nr = d.nr, px = d.px,
                    grain = 0f, vig = d.vig, res = floatArrayOf(met.w.toFloat(), met.h.toFloat()), tm = tm, rot = d.rot
                )
                prog.draw(oesTex, lutTex, d2)
                callbacks.onMeter(measure(met.read(), met.w, met.h))
            } else callbacks.onMeter(null)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    /** タップ位置（表示上の切り抜き後0〜1、yは下向き）→ カメラ映像バッファ上の位置（0〜1） */
    fun toBufferPoint(nx: Float, ny: Float): FloatArray {
        val swap = uprightSwap()
        val uw = if (swap) bufH else bufW
        val uh = if (swap) bufW else bufH
        val c = cropFor(settings.aspect, uw.toFloat(), uh.toFloat())
        var px = c[0] + nx * c[2]
        var py = 1f - (c[1] + ny * c[3])
        when (extraRot()) {
            1 -> { val t = px; px = 1f - py; py = t }
            2 -> { px = 1f - px; py = 1f - py }
            3 -> { val t = px; px = py; py = 1f - t }
        }
        val s = tm[0] * px + tm[4] * py + tm[12]
        val t = tm[1] * px + tm[5] * py + tm[13]
        return floatArrayOf(s.coerceIn(0f, 1f), t.coerceIn(0f, 1f))
    }

    fun release() {
        st?.release(); st = null
    }
}
