package com.kooky.utsushi

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// ===== シェーダー（Web版と同じ処理。カメラ映像用＝外部テクスチャ／保存用＝通常テクスチャ） =====
private const val VS = """
attribute vec2 p;
varying vec2 v_uv;
void main(){v_uv=p*.5+.5;gl_Position=vec4(p,0.,1.);}
"""

private const val FS_BODY = """
varying vec2 v_uv;
uniform SAMPLER u_tex;
uniform sampler2D u_lut;
uniform mat4 u_tm;
uniform float u_rot;
uniform vec4 u_crop;
uniform float u_look,u_nlooks,u_exp,u_grain,u_vig,u_seed,u_mirror,u_gsize,u_clar;
uniform vec3 u_wb;
uniform vec2 u_px;
uniform float u_nr;
uniform vec2 u_res;
uniform float u_orig;
uniform vec4 u_tile;
uniform vec2 u_poff;
uniform float u_peak;
const float N=17.;
// 表示上の座標(p) → テクスチャ座標。必要なら追加で90°単位の回転をかける
vec3 tex(vec2 p){
  if(u_rot>.5&&u_rot<1.5) p=vec2(1.-p.y,p.x);
  else if(u_rot>1.5&&u_rot<2.5) p=vec2(1.-p.x,1.-p.y);
  else if(u_rot>2.5) p=vec2(p.y,1.-p.x);
  return texture2D(u_tex,(u_tm*vec4(p,0.,1.)).xy).rgb;
}
float hash(vec2 p){p=fract(p*vec2(123.34,456.21));p+=dot(p,p+45.32);return fract(p.x*p.y);}
float luma(vec3 c){return dot(c,vec3(.2126,.7152,.0722));}
vec3 lut(vec3 c,float lk){
  c=clamp(c,0.,1.);
  float bz=c.b*(N-1.),b0=floor(bz),b1=min(b0+1.,N-1.),f=bz-b0;
  float x0=(b0*N+c.r*(N-1.)+.5)/(N*N),x1=(b1*N+c.r*(N-1.)+.5)/(N*N);
  float y=(lk*N+c.g*(N-1.)+.5)/(N*u_nlooks);
  return mix(texture2D(u_lut,vec2(x0,y)).rgb,texture2D(u_lut,vec2(x1,y)).rgb,f);
}
void main(){
  vec2 g=u_tile.xy+v_uv*u_tile.zw;
  vec2 uv=g; if(u_mirror>.5) uv.x=1.-uv.x;
  vec2 tuv=u_crop.xy+uv*u_crop.zw;
  vec3 c=tex(tuv);
  if(u_orig>.5){gl_FragColor=vec4(c,1.);return;}
  if(u_nr>0.){
    vec3 s1=tex(tuv+vec2(u_px.x,0.)),s2=tex(tuv-vec2(u_px.x,0.));
    vec3 s3=tex(tuv+vec2(0.,u_px.y)),s4=tex(tuv-vec2(0.,u_px.y));
    vec3 s5=tex(tuv+u_px),s6=tex(tuv-u_px);
    vec3 s7=tex(tuv+vec2(u_px.x,-u_px.y)),s8=tex(tuv+vec2(-u_px.x,u_px.y));
    vec3 nb=(s1+s2+s3+s4+s5+s6+s7+s8)*.125;
    float ed=max(max(length(s1-s2),length(s3-s4)),max(length(s5-s6),length(s7-s8)));
    float fl=1.-smoothstep(.04,.12,ed);
    c=mix(c,nb,u_nr*fl);
  }
  if(u_clar>0.){
    float ar0=u_res.x/u_res.y;
    vec2 d=vec2(.007/ar0,.007)*u_crop.zw;
    vec3 bl=vec3(0.);
    for(int i=0;i<12;i++){
      float a=float(i)*.5236; float rr=1.;
      if(i>=6){a+=.2618;rr=.5;}
      bl+=tex(tuv+vec2(cos(a),sin(a))*d*rr);
    }
    bl/=12.;
    float dl=clamp(luma(c)-luma(bl),-.05,.05);
    c+=dl*u_clar*(1.-smoothstep(.75,1.,luma(c)))*smoothstep(0.,.12,luma(c));
  }
  vec3 l=pow(max(c,0.),vec3(2.2))*u_wb*exp2(u_exp);
  if(u_exp>0.){float k=.3*u_exp;l=l*(1.+k)/(1.+k*l);}
  c=pow(clamp(l,0.,1.),vec3(1./2.2));
  c=lut(c,u_look);
  vec2 p=(g-.5)*2.; float ar=u_res.x/u_res.y; p.x*=ar;
  float r=length(p)/length(vec2(ar,1.));
  c*=1.-u_vig*pow(r,2.4);
  float n=hash(floor((gl_FragCoord.xy+u_poff)/u_gsize)+u_seed)-.5;
  float L2=luma(c); float w=1.-pow(abs(L2-.5)*2.,2.);
  c+=n*u_grain*(.35+.65*w);
  if(u_peak>.5){
    vec2 q=u_px*1.5;
    float a1=luma(tex(tuv+vec2(q.x,0.))),a2=luma(tex(tuv-vec2(q.x,0.)));
    float b1=luma(tex(tuv+vec2(0.,q.y))),b2=luma(tex(tuv-vec2(0.,q.y)));
    float ed2=abs(a1-a2)+abs(b1-b2);
    c=mix(c,vec3(1.,.48,.1),smoothstep(.2,.36,ed2));
  }
  gl_FragColor=vec4(clamp(c,0.,1.),1.);
}
"""

private fun fsSource(oes: Boolean): String =
    if (oes) "#extension GL_OES_EGL_image_external : require\nprecision highp float;\n#define SAMPLER samplerExternalOES\n$FS_BODY"
    else "precision highp float;\n#define SAMPLER sampler2D\n$FS_BODY"

/** 1回分の描画設定 */
class Draw(
    var crop: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
    var look: Int = 0,
    var exp: Float = 0f,
    var wb: FloatArray = floatArrayOf(1f, 1f, 1f),
    var clar: Float = 0f,
    var nr: Float = 0f,
    var px: FloatArray = floatArrayOf(0f, 0f),
    var grain: Float = 0f,
    var vig: Float = 0f,
    var seed: Float = 0f,
    var mirror: Boolean = false,
    var gsize: Float = 1f,
    var res: FloatArray = floatArrayOf(1f, 1f),
    var tile: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
    var poff: FloatArray = floatArrayOf(0f, 0f),
    var orig: Boolean = false,
    var peak: Boolean = false,
    var tm: FloatArray = IDENTITY,
    var rot: Int = 0
) {
    companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        /** Bitmap（上が行0）を、下原点の座標で読むための上下反転 */
        val FLIP_Y = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)
    }
}

class GlProgram(private val oes: Boolean) {
    private val prog: Int
    private val loc = HashMap<String, Int>()
    private val quad: FloatBuffer = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }
    private val aPos: Int

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VS)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSource(oes))
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val st = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, st, 0)
        if (st[0] == 0) throw RuntimeException("link: " + GLES20.glGetProgramInfoLog(prog))
        aPos = GLES20.glGetAttribLocation(prog, "p")
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val st = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, st, 0)
        if (st[0] == 0) throw RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun u(n: String) = loc.getOrPut(n) { GLES20.glGetUniformLocation(prog, n) }

    /** テクスチャ texId（ユニット0）と LUT（ユニット1）で描画 */
    fun draw(texId: Int, lutId: Int, d: Draw) {
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, texId)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutId)
        GLES20.glUniform1i(u("u_tex"), 0)
        GLES20.glUniform1i(u("u_lut"), 1)
        GLES20.glUniformMatrix4fv(u("u_tm"), 1, false, d.tm, 0)
        GLES20.glUniform1f(u("u_rot"), d.rot.toFloat())
        GLES20.glUniform4f(u("u_crop"), d.crop[0], d.crop[1], d.crop[2], d.crop[3])
        GLES20.glUniform1f(u("u_look"), d.look.toFloat())
        GLES20.glUniform1f(u("u_nlooks"), LOOKS.size.toFloat())
        GLES20.glUniform1f(u("u_exp"), d.exp)
        GLES20.glUniform1f(u("u_grain"), d.grain)
        GLES20.glUniform1f(u("u_vig"), d.vig)
        GLES20.glUniform1f(u("u_seed"), d.seed)
        GLES20.glUniform1f(u("u_mirror"), if (d.mirror) 1f else 0f)
        GLES20.glUniform1f(u("u_gsize"), d.gsize)
        GLES20.glUniform1f(u("u_clar"), d.clar)
        GLES20.glUniform3f(u("u_wb"), d.wb[0], d.wb[1], d.wb[2])
        GLES20.glUniform2f(u("u_px"), d.px[0], d.px[1])
        GLES20.glUniform1f(u("u_nr"), d.nr)
        GLES20.glUniform2f(u("u_res"), d.res[0], d.res[1])
        GLES20.glUniform1f(u("u_orig"), if (d.orig) 1f else 0f)
        GLES20.glUniform4f(u("u_tile"), d.tile[0], d.tile[1], d.tile[2], d.tile[3])
        GLES20.glUniform2f(u("u_poff"), d.poff[0], d.poff[1])
        GLES20.glUniform1f(u("u_peak"), if (d.peak) 1f else 0f)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    companion object {
        fun texture2D(bmp: Bitmap?, w: Int = 0, h: Int = 0): Int {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            if (bmp != null) GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            else GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            return t[0]
        }

        fun textureOES(): Int {
            val t = IntArray(1)
            GLES20.glGenTextures(1, t, 0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0])
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            return t[0]
        }
    }
}

/** 小さな描画先（測光・解析用）。描いてから画素を読み出す */
class Fbo(val w: Int, val h: Int) {
    val tex = GlProgram.texture2D(null, w, h)
    val id: Int
    val buf: ByteBuffer = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())

    init {
        val f = IntArray(1)
        GLES20.glGenFramebuffers(1, f, 0)
        id = f[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    fun bind() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, id)
        GLES20.glViewport(0, 0, w, h)
    }

    fun read(): ByteBuffer {
        buf.position(0)
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        buf.position(0)
        return buf
    }
}
