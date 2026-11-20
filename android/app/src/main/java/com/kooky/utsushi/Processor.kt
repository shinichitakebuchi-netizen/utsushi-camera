package com.kooky.utsushi

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.provider.MediaStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 撮った写真に、プレビューと同じシェーダーで色味をかけて保存する。
 * 画面とは別のGL環境（オフスクリーン）を、専用スレッドで使う。
 */
class Processor(private val ctx: Context) {
    private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglCtx: EGLContext = EGL14.EGL_NO_CONTEXT
    private var surf: EGLSurface = EGL14.EGL_NO_SURFACE
    private var prog: GlProgram? = null
    private var lutTex = 0
    private var maxTex = 4096

    private fun ensureGl() {
        if (prog != null) return
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(dpy, ver, 0, ver, 1)
        val attr = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE
        )
        val cfgs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(dpy, attr, 0, cfgs, 0, 1, num, 0)
        eglCtx = EGL14.eglCreateContext(dpy, cfgs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surf = EGL14.eglCreatePbufferSurface(dpy, cfgs[0], intArrayOf(EGL14.EGL_WIDTH, 16, EGL14.EGL_HEIGHT, 16, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(dpy, surf, surf, eglCtx)
        val mt = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, mt, 0)
        maxTex = max(2048, min(mt[0], 8192))
        prog = GlProgram(false)
        val bmp = ctx.assets.open("lut.png").use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inScaled = false })
        }
        lutTex = GlProgram.texture2D(bmp)
        bmp?.recycle()
    }

    /** 端末内の写真を読み込む（向きは自動で補正） */
    fun decodeUri(uri: Uri): Bitmap {
        val src = android.graphics.ImageDecoder.createSource(ctx.contentResolver, uri)
        var b = android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
            dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            var ss = 1
            while (info.size.width.toLong() * info.size.height / (ss * ss) > 26_000_000L) ss *= 2
            if (ss > 1) dec.setTargetSampleSize(ss)
        }
        if (b.config != Bitmap.Config.ARGB_8888) {
            val c = b.copy(Bitmap.Config.ARGB_8888, false); b.recycle(); b = c
        }
        return b
    }

    /** JPEGを読み込み、回転を直し、大きすぎる場合は縮小 */
    fun decode(jpeg: ByteArray, rotation: Int): Bitmap {
        val bo = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bo)
        var ss = 1
        while (bo.outWidth.toLong() * bo.outHeight / (ss * ss) > 26_000_000L) ss *= 2
        val raw = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
            inSampleSize = ss; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw RuntimeException("画像を読み込めませんでした")
        if (rotation % 360 == 0) return raw
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val r = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        if (r !== raw) raw.recycle()
        return r
    }

    /** 色味をかけた完成画像を返す（GLは呼び出しスレッドで使う＝常に同じスレッドから呼ぶこと） */
    /**
     * maxSide>0：編集画面のプレビュー用に、長辺をこの大きさまで縮めて処理。
     * maxSide=0：保存用。解像度設定（RES）に合わせて縮める。
     */
    fun process(src0: Bitmap, s: Settings, a: AutoResult?, mirror: Boolean, maxSide: Int = 0): Bitmap {
        ensureGl()
        val p = prog!!
        var src = src0
        val long = max(src.width, src.height).toFloat()
        var k = 1f
        if (maxSide > 0) k = min(1f, maxSide / long)
        else {
            val mp = RESS[s.res].mp
            if (mp > 0) {
                val c0 = cropFor(s.aspect, src.width.toFloat(), src.height.toFloat())
                val area = src.width.toDouble() * src.height * c0[2] * c0[3]
                if (area > mp * 1e6) k = Math.sqrt(mp * 1e6 / area).toFloat()
            }
        }
        k = min(k, maxTex / long)
        if (k < .999f) {
            src = Bitmap.createScaledBitmap(src, max(1, (src.width * k).roundToInt()), max(1, (src.height * k).roundToInt()), true)
        }
        val sw = src.width; val sh = src.height
        val crop = cropFor(s.aspect, sw.toFloat(), sh.toFloat())
        val ow = (sw * crop[2]).roundToInt(); val oh = (sh * crop[3]).roundToInt()
        val tex = GlProgram.texture2D(src)
        if (src !== src0) src.recycle()

        val tile = 2048
        val tw = min(tile, ow); val th = min(tile, oh)
        val ftex = GlProgram.texture2D(null, tw, th)
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, f[0])
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ftex, 0)

        val prm = buildParams(s, a)
        val nrk = max(1f, max(sw, sh) / 1600f)
        val seed = (Math.random() * 100).toFloat()
        val gsize = max(1f, max(ow, oh) / 1800f)
        val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        val buf = ByteBuffer.allocateDirect(tw * th * 4).order(ByteOrder.nativeOrder())
        var y = 0
        while (y < oh) {
            var x = 0
            while (x < ow) {
                val w = min(tw, ow - x); val h = min(th, oh - y)
                GLES20.glViewport(0, 0, w, h)
                p.draw(tex, lutTex, Draw(
                    crop = crop, look = s.look, exp = prm.exp, wb = prm.wb, clar = prm.clar, nr = prm.nr,
                    px = floatArrayOf(nrk / sw, nrk / sh), grain = prm.grain, vig = prm.vig, seed = seed,
                    mirror = mirror, gsize = gsize, res = floatArrayOf(ow.toFloat(), oh.toFloat()),
                    tile = floatArrayOf(x.toFloat() / ow, 1f - (y + h).toFloat() / oh, w.toFloat() / ow, h.toFloat() / oh),
                    poff = floatArrayOf(x.toFloat(), (oh - (y + h)).toFloat()), tm = Draw.FLIP_Y
                ))
                buf.position(0)
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                buf.position(0)
                val tb = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                buf.limit(w * h * 4)
                tb.copyPixelsFromBuffer(buf)
                buf.clear()
                // 読み出しは下の行から並ぶので、上下を反転して貼る
                val m = Matrix().apply { postScale(1f, -1f); postTranslate(x.toFloat(), (y + h).toFloat()) }
                cv.drawBitmap(tb, m, null)
                tb.recycle()
                x += tw
            }
            y += th
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glDeleteFramebuffers(1, f, 0)
        GLES20.glDeleteTextures(2, intArrayOf(tex, ftex), 0)
        return if (s.frame) addFrame(out, LOOKS[s.look].name).also { out.recycle() } else out
    }

    /** 白フチ＋ロゴ・日付（Web版と同じレイアウト） */
    private fun addFrame(src: Bitmap, lookName: String): Bitmap {
        val w = src.width; val h = src.height; val s = min(w, h)
        val side = (s * .035f).roundToInt(); val bottom = (s * .11f).roundToInt()
        val c = Bitmap.createBitmap(w + side * 2, h + side + bottom, Bitmap.Config.ARGB_8888)
        val cv = Canvas(c)
        cv.drawColor(Color.rgb(0xf7, 0xf6, 0xf2))
        cv.drawBitmap(src, side.toFloat(), side.toFloat(), null)
        val y = side + h + bottom / 2f
        val fs = bottom * .2f
        val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        pt.color = Color.rgb(0xff, 0x7a, 0x1a)
        cv.drawCircle(side + fs * .4f, y, fs * .3f, pt)
        pt.color = Color.rgb(0x1a, 0x1a, 0x1a)
        pt.textSize = fs
        pt.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val fm = pt.fontMetrics
        val base = y - (fm.ascent + fm.descent) / 2
        cv.drawText("UTSUSHI", side + fs * 1.1f, base, pt)
        pt.color = Color.rgb(0x7a, 0x7a, 0x7a)
        pt.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        pt.textSize = fs * .82f
        pt.textAlign = Paint.Align.RIGHT
        val fm2 = pt.fontMetrics
        val d = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.JAPAN).format(Date())
        cv.drawText("$lookName   $d", (side + w).toFloat(), y - (fm2.ascent + fm2.descent) / 2, pt)
        return c
    }

    /** 端末の「Pictures/UTSUSHI」に保存 */
    fun save(bmp: Bitmap): Uri {
        val name = "UTSUSHI_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.JAPAN).format(Date()) + ".jpg"
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/UTSUSHI")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val r = ctx.contentResolver
        val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: throw RuntimeException("保存先を作れませんでした")
        r.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 93, it) }
        cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
        r.update(uri, cv, null, null)
        return uri
    }

    fun mp(b: Bitmap) = String.format("%.1f", b.width.toLong() * b.height / 1e6)
}
