package com.kooky.utsushi

import java.nio.ByteBuffer
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// ===== 色味（Web版と同じ並び。LUT画像の行と対応） =====
data class Look(val name: String, val mono: Boolean, val tip: String)

val LOOKS = listOf(
    Look("CLASSIC", false, "迷ったらこれ。映画っぽく落ち着いた色"),
    Look("CONTEMPORARY", false, "人物・ポートレートに。明るく自然な肌"),
    Look("VIVID", false, "晴れた日の景色・料理に。色しっかり"),
    Look("NATURAL", false, "見たままに近い、やわらかい色"),
    Look("CHROME", false, "くもりの日・街並みに。控えめで上品"),
    Look("CINE", false, "夕方・旅先に。暖かいフィルム調"),
    Look("ETERNAL", false, "夜の街・ネオンに。濃い色と深い黒"),
    Look("MONO", true, "迷ったらモノクロ。しっとりした白黒"),
    Look("MONO HC", true, "光と影が強い場面に。硬めの白黒")
)

data class Aspect(val label: String, val v: Float)

val ASPECTS = listOf(
    Aspect("3:2", 3f / 2f), Aspect("元の比率", 0f), Aspect("1:1", 1f), Aspect("16:9", 16f / 9f), Aspect("4:3", 4f / 3f)
)

/** 画像（幅sw×高さsh）に対する切り抜き範囲 [x, y, w, h]（0〜1） */
fun cropFor(aspectIdx: Int, sw: Float, sh: Float): FloatArray {
    var a = ASPECTS[aspectIdx].v
    if (a == 0f) return floatArrayOf(0f, 0f, 1f, 1f)
    if (sh > sw) a = 1f / a
    val sa = sw / sh
    return if (sa > a) {
        val w = a / sa; floatArrayOf((1 - w) / 2, 0f, w, 1f)
    } else {
        val h = sa / a; floatArrayOf(0f, (1 - h) / 2, 1f, h)
    }
}

fun targetAspect(aspectIdx: Int, sw: Float, sh: Float): Float {
    val a = ASPECTS[aspectIdx].v
    if (a == 0f) return sw / sh
    return if (sh > sw) 1f / a else a
}

fun fmtEV(v: Float): String {
    val s = if (v > 0.049f) "+" else if (v < -0.049f) "−" else "±"
    return s + String.format("%.1f", kotlin.math.abs(v))
}

private fun log2(x: Float) = (ln(x.toDouble()) / ln(2.0)).toFloat()

// ===== おまかせ補正（ホワイトバランス・明るさ） =====
class AutoResult(val wb: FloatArray, val exp: Float, val mode: String)

object Auto {
    @Volatile var wb = floatArrayOf(1f, 1f, 1f)
    @Volatile var exp = 0f
    @Volatile var mode = "mid"
    @Volatile var ready = false

    fun update(a: AutoResult) {
        val t = if (ready) 0.25f else 1f
        wb = FloatArray(3) { wb[it] + (a.wb[it] - wb[it]) * t }
        exp += (a.exp - exp) * t
        mode = a.mode
        ready = true
    }

    fun snapshot() = AutoResult(wb.copyOf(), exp, mode)

    /** RGBAの画素列（縮小済み）から色かぶりと明るさを推定（Web版のanalyzeと同じ考え方） */
    fun analyze(buf: ByteBuffer, w: Int, h: Int): AutoResult {
        val n = w * h
        val ls = FloatArray(n)
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var cnt = 0
        for (i in 0 until n) {
            val r = ((buf.get(i * 4).toInt() and 255) / 255f).pow(2.2f)
            val g = ((buf.get(i * 4 + 1).toInt() and 255) / 255f).pow(2.2f)
            val b = ((buf.get(i * 4 + 2).toInt() and 255) / 255f).pow(2.2f)
            val y = .2126f * r + .7152f * g + .0722f * b
            ls[i] = y
            if (y > .01f && max(r, max(g, b)) < .92f) { sr += r; sg += g; sb += b; cnt++ }
        }
        var wbv = floatArrayOf(1f, 1f, 1f)
        if (cnt > 20) {
            val m = (sr + sg + sb) / 3
            val tgt = floatArrayOf(1.02f, 1f, .97f)
            wbv = floatArrayOf((m / sr * tgt[0]).toFloat(), (m / sg * tgt[1]).toFloat(), (m / sb * tgt[2]).toFloat())
                .map { it.pow(.7f) }.toFloatArray()
            val gn = wbv[1]
            wbv = wbv.map { min(1.6f, max(.65f, it / gn)) }.toFloatArray()
        }
        ls.sort()
        val p50 = ls[(n * .5f).toInt().coerceIn(0, n - 1)]
        val p95 = ls[(n * .95f).toInt().coerceIn(0, n - 1)]
        val eMid = log2(.13f / max(p50, 1e-4f))
        val eHi = log2(.9f / max(p95, 1e-3f)) + .8f
        var e = min(eMid, eHi)
        var mode = if (eMid <= eHi) "mid" else "hi"
        if (e > 1.6f) mode = "max"
        if (e < -1f) mode = "min"
        e = max(-1f, min(1.6f, e))
        return AutoResult(wbv, e, mode)
    }
}

// ===== 測光メーター用の統計（画面に出ている絵を測る） =====
class MeterStats(
    val hist: IntArray, val p5: Int, val p50: Int, val p95: Int, val mean: Int, val cl: Float, val dk: Float
)

fun measure(buf: ByteBuffer, w: Int, h: Int): MeterStats {
    val n = w * h
    val ls = IntArray(n)
    val hist = IntArray(48)
    var cl = 0; var dk = 0; var sum = 0L
    for (i in 0 until n) {
        val y = (.299f * (buf.get(i * 4).toInt() and 255) + .587f * (buf.get(i * 4 + 1).toInt() and 255) +
                .114f * (buf.get(i * 4 + 2).toInt() and 255))
        val yi = y.roundToInt().coerceIn(0, 255)
        ls[i] = yi; sum += yi
        hist[min(47, (y / 256f * 48).toInt())]++
        if (y >= 250) cl++
        if (y <= 15) dk++
    }
    ls.sort()
    fun p(q: Float) = ls[(n * q).toInt().coerceIn(0, n - 1)]
    return MeterStats(hist, p(.05f), p(.5f), p(.95f), (sum / n).toInt(), cl.toFloat() / n, dk.toFloat() / n)
}

// ===== 補正量の組み立て =====
class Params(val wb: FloatArray, val exp: Float, val clar: Float, val nr: Float, val grain: Float, val vig: Float)

fun buildParams(s: Settings, a: AutoResult?): Params {
    val autoExp = if (s.auto && a != null) a.exp else 0f
    val up = max(0f, autoExp + s.ev)
    val clar = (if (s.auto) .3f else .2f) * max(.25f, 1 - .5f * up)
    val nr = min(.9f, .35f + .45f * up)
    val wb = if (s.auto && a != null) a.wb else floatArrayOf(1f, 1f, 1f)
    var grain = 0f; var vig = 0f
    if (s.film) {
        val mono = LOOKS[s.look].mono
        val bg = if (mono) .045f else .03f
        vig = if (mono) .28f else .22f
        val k = max(.15f, 1 - .6f * up)
        grain = bg * k
    }
    return Params(wb, autoExp + s.ev, clar, nr, grain, vig)
}
