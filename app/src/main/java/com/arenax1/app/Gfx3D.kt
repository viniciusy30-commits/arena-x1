package com.arenax1.app

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Cada vértice: posição(3) + normal(3) + cor RGBA(4) = 10 floats.
// Normal zero = vértice sem luz (brilho próprio).

class VBuf {
    var v = FloatArray(10 * 3000)
    var n = 0
    fun need(extra: Int) {
        if (n + extra > v.size) v = v.copyOf(maxOf(v.size * 2, n + extra))
    }
}

/** Lote de geometria com pilha de matrizes. Três destinos: opaco, translúcido e aditivo. */
class Batch {
    val opq = VBuf()
    val alp = VBuf()
    val add = VBuf()

    /** 0 = automático (alpha<1 vai para translúcido), 2 = aditivo. */
    var mode = 0

    private val stack = Array(24) { FloatArray(16) }
    private var sp = 0
    private val m: FloatArray get() = stack[sp]

    init {
        Matrix.setIdentityM(stack[0], 0)
    }

    fun clear() {
        opq.n = 0; alp.n = 0; add.n = 0
        sp = 0
        Matrix.setIdentityM(stack[0], 0)
        mode = 0
    }

    fun push() {
        System.arraycopy(stack[sp], 0, stack[sp + 1], 0, 16)
        sp++
    }

    fun pop() {
        sp--
    }

    fun tr(x: Float, y: Float, z: Float) = Matrix.translateM(stack[sp], 0, x, y, z)
    fun rotX(deg: Float) = Matrix.rotateM(stack[sp], 0, deg, 1f, 0f, 0f)
    fun rotY(deg: Float) = Matrix.rotateM(stack[sp], 0, deg, 0f, 1f, 0f)
    fun rotZ(deg: Float) = Matrix.rotateM(stack[sp], 0, deg, 0f, 0f, 1f)
    fun sc(x: Float, y: Float, z: Float) = Matrix.scaleM(stack[sp], 0, x, y, z)

    private fun target(a: Float): VBuf = when {
        mode == 2 -> add
        a < 0.999f -> alp
        else -> opq
    }

    private var cr = 1f
    private var cg = 1f
    private var cb = 1f
    private var ca = 1f
    private var tb: VBuf = opq

    private fun setCol(col: Int, a: Float) {
        cr = ((col shr 16) and 255) / 255f
        cg = ((col shr 8) and 255) / 255f
        cb = (col and 255) / 255f
        ca = a * (((col ushr 24) and 255) / 255f)
        tb = target(ca)
        tb.need(3000)
    }

    /** Emite um vértice transformado pela matriz atual. */
    private fun vx(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float) {
        val a = m
        val b = tb
        val o = b.v
        var i = b.n
        o[i++] = a[0] * px + a[4] * py + a[8] * pz + a[12]
        o[i++] = a[1] * px + a[5] * py + a[9] * pz + a[13]
        o[i++] = a[2] * px + a[6] * py + a[10] * pz + a[14]
        if (nx == 0f && ny == 0f && nz == 0f) {
            o[i++] = 0f; o[i++] = 0f; o[i++] = 0f
        } else {
            val tx = a[0] * nx + a[4] * ny + a[8] * nz
            val ty = a[1] * nx + a[5] * ny + a[9] * nz
            val tz = a[2] * nx + a[6] * ny + a[10] * nz
            val l = sqrt(tx * tx + ty * ty + tz * tz)
            val k = if (l > 0.0001f) 1f / l else 0f
            o[i++] = tx * k; o[i++] = ty * k; o[i++] = tz * k
        }
        o[i++] = cr; o[i++] = cg; o[i++] = cb; o[i++] = ca
        b.n = i
    }

    private fun tri(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        nx: Float, ny: Float, nz: Float, lit: Boolean
    ) {
        if (lit) {
            vx(ax, ay, az, nx, ny, nz); vx(bx, by, bz, nx, ny, nz); vx(cx, cy, cz, nx, ny, nz)
        } else {
            vx(ax, ay, az, 0f, 0f, 0f); vx(bx, by, bz, 0f, 0f, 0f); vx(cx, cy, cz, 0f, 0f, 0f)
        }
    }

    private fun quad(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
        dx: Float, dy: Float, dz: Float,
        nx: Float, ny: Float, nz: Float, lit: Boolean
    ) {
        tri(ax, ay, az, bx, by, bz, cx, cy, cz, nx, ny, nz, lit)
        tri(ax, ay, az, cx, cy, cz, dx, dy, dz, nx, ny, nz, lit)
    }

    /** Caixa centrada em (cx,cy,cz) com tamanho (sx,sy,sz). */
    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, col: Int, a: Float = 1f, lit: Boolean = true) {
        setCol(col, a)
        val x0 = cx - sx / 2f; val x1 = cx + sx / 2f
        val y0 = cy - sy / 2f; val y1 = cy + sy / 2f
        val z0 = cz - sz / 2f; val z1 = cz + sz / 2f
        quad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, 0f, 1f, 0f, lit)
        quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0f, -1f, 0f, lit)
        quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, 1f, 0f, 0f, lit)
        quad(x0, y0, z0, x0, y1, z0, x0, y1, z1, x0, y0, z1, -1f, 0f, 0f, lit)
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f, lit)
        quad(x0, y0, z0, x1, y0, z0, x1, y1, z0, x0, y1, z0, 0f, 0f, -1f, lit)
    }

    /** Cilindro/cone vertical: base em y0 com raio r0, topo em y0+h com raio r1. */
    fun cyl(cx: Float, y0: Float, cz: Float, r0: Float, r1: Float, h: Float, col: Int, seg: Int = 8, a: Float = 1f, lit: Boolean = true) {
        setCol(col, a)
        val y1 = y0 + h
        val slope = (r0 - r1) / (if (h == 0f) 1f else h)
        for (i in 0 until seg) {
            val a0 = (i.toFloat() / seg * 2f * PI).toFloat()
            val a1 = ((i + 1).toFloat() / seg * 2f * PI).toFloat()
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            val am = (a0 + a1) / 2f
            val nx = cos(am); val nz = sin(am)
            val ny = slope
            if (r1 > 0.01f) {
                quad(cx + c0 * r0, y0, cz + s0 * r0, cx + c1 * r0, y0, cz + s1 * r0,
                    cx + c1 * r1, y1, cz + s1 * r1, cx + c0 * r1, y1, cz + s0 * r1, nx, ny, nz, lit)
            } else {
                tri(cx + c0 * r0, y0, cz + s0 * r0, cx + c1 * r0, y0, cz + s1 * r0, cx, y1, cz, nx, ny, nz, lit)
            }
            if (r1 > 0.01f) tri(cx, y1, cz, cx + c0 * r1, y1, cz + s0 * r1, cx + c1 * r1, y1, cz + s1 * r1, 0f, 1f, 0f, lit)
            if (r0 > 0.01f) tri(cx, y0, cz, cx + c0 * r0, y0, cz + s0 * r0, cx + c1 * r0, y0, cz + s1 * r0, 0f, -1f, 0f, lit)
        }
    }

    /** Elipsoide centrado em (cx,cy,cz) com raios (rx,ry,rz). */
    fun sphere(cx: Float, cy: Float, cz: Float, rx: Float, ry: Float, rz: Float, col: Int, a: Float = 1f, lit: Boolean = true, sl: Int = 8, st: Int = 5) {
        setCol(col, a)
        for (i in 0 until st) {
            val p0 = (-PI / 2.0 + PI * i / st).toFloat()
            val p1 = (-PI / 2.0 + PI * (i + 1) / st).toFloat()
            val y0 = sin(p0); val y1 = sin(p1)
            val r0 = cos(p0); val r1 = cos(p1)
            for (j in 0 until sl) {
                val t0 = (2.0 * PI * j / sl).toFloat()
                val t1 = (2.0 * PI * (j + 1) / sl).toFloat()
                val c0 = cos(t0); val s0 = sin(t0); val c1 = cos(t1); val s1 = sin(t1)
                // quatro cantos, cada um com a própria normal (esfera suave)
                val ax = r0 * c0; val ay = y0; val az = r0 * s0
                val bx = r0 * c1; val bz = r0 * s1
                val dx = r1 * c0; val dy = y1; val dz = r1 * s0
                val ex = r1 * c1; val ez = r1 * s1
                if (lit) {
                    vx(cx + ax * rx, cy + ay * ry, cz + az * rz, ax, ay, az)
                    vx(cx + bx * rx, cy + ay * ry, cz + bz * rz, bx, ay, bz)
                    vx(cx + ex * rx, cy + dy * ry, cz + ez * rz, ex, dy, ez)
                    vx(cx + ax * rx, cy + ay * ry, cz + az * rz, ax, ay, az)
                    vx(cx + ex * rx, cy + dy * ry, cz + ez * rz, ex, dy, ez)
                    vx(cx + dx * rx, cy + dy * ry, cz + dz * rz, dx, dy, dz)
                } else {
                    tri(cx + ax * rx, cy + ay * ry, cz + az * rz, cx + bx * rx, cy + ay * ry, cz + bz * rz,
                        cx + ex * rx, cy + dy * ry, cz + ez * rz, 0f, 0f, 0f, false)
                    tri(cx + ax * rx, cy + ay * ry, cz + az * rz, cx + ex * rx, cy + dy * ry, cz + ez * rz,
                        cx + dx * rx, cy + dy * ry, cz + dz * rz, 0f, 0f, 0f, false)
                }
            }
        }
    }

    /** Disco horizontal (leque de triângulos). */
    fun disc(cx: Float, y: Float, cz: Float, r: Float, col: Int, a: Float = 1f, lit: Boolean = false, seg: Int = 24) {
        setCol(col, a)
        for (i in 0 until seg) {
            val a0 = (i.toFloat() / seg * 2f * PI).toFloat()
            val a1 = ((i + 1).toFloat() / seg * 2f * PI).toFloat()
            tri(cx, y, cz, cx + cos(a0) * r, y, cz + sin(a0) * r, cx + cos(a1) * r, y, cz + sin(a1) * r, 0f, 1f, 0f, lit)
        }
    }

    /** Anel horizontal entre r0 e r1. */
    fun ring(cx: Float, y: Float, cz: Float, r0: Float, r1: Float, col: Int, a: Float = 1f, lit: Boolean = false, seg: Int = 28) {
        setCol(col, a)
        for (i in 0 until seg) {
            val a0 = (i.toFloat() / seg * 2f * PI).toFloat()
            val a1 = ((i + 1).toFloat() / seg * 2f * PI).toFloat()
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            quad(cx + c0 * r0, y, cz + s0 * r0, cx + c1 * r0, y, cz + s1 * r0,
                cx + c1 * r1, y, cz + s1 * r1, cx + c0 * r1, y, cz + s0 * r1, 0f, 1f, 0f, lit)
        }
    }

    /** Triângulo cru com cor por vértice (usado no terreno, sem matriz). */
    fun rawVert(px: Float, py: Float, pz: Float, nx: Float, ny: Float, nz: Float, col: Int) {
        val b = opq
        b.need(12)
        var i = b.n
        val o = b.v
        o[i++] = px; o[i++] = py; o[i++] = pz
        o[i++] = nx; o[i++] = ny; o[i++] = nz
        o[i++] = ((col shr 16) and 255) / 255f
        o[i++] = ((col shr 8) and 255) / 255f
        o[i++] = (col and 255) / 255f
        o[i++] = 1f
        b.n = i
    }
}

fun mixCol(a: Int, b: Int, k: Float): Int {
    val kk = k.coerceIn(0f, 1f)
    val r = (((a shr 16) and 255) * (1f - kk) + ((b shr 16) and 255) * kk).toInt()
    val g = (((a shr 8) and 255) * (1f - kk) + ((b shr 8) and 255) * kk).toInt()
    val bl = ((a and 255) * (1f - kk) + (b and 255) * kk).toInt()
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}

fun shadeCol(a: Int, k: Float): Int {
    val r = (((a shr 16) and 255) * k).toInt().coerceIn(0, 255)
    val g = (((a shr 8) and 255) * k).toInt().coerceIn(0, 255)
    val b = ((a and 255) * k).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

fun cc(h: Long): Int = h.toInt()

class StaticMesh(val vbo: Int, val count: Int, val x0: Float, val x1: Float)

/** Escolhe a melhor configuração EGL: MSAA e profundidade 24 se existirem. */
class MsaaChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        for (samples in intArrayOf(4, 2, 0)) {
            for (depth in intArrayOf(24, 16)) {
                val spec = if (samples > 0) intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, 4,
                    EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples, EGL10.EGL_NONE
                ) else intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_NONE
                )
                val num = IntArray(1)
                if (egl.eglChooseConfig(display, spec, null, 0, num) && num[0] > 0) {
                    val cfgs = arrayOfNulls<EGLConfig>(num[0])
                    egl.eglChooseConfig(display, spec, cfgs, num[0], num)
                    val c = cfgs[0]
                    if (c != null) return c
                }
            }
        }
        throw IllegalArgumentException("Nenhuma configuração EGL")
    }
}

const val SKY_R = 0.50f
const val SKY_G = 0.68f
const val SKY_B = 0.84f

class GlRenderer(private val g: GameView) : GLSurfaceView.Renderer {

    private var prog = 0
    private var uVP = 0
    private var uFog = 0
    private var aP = 0
    private var aN = 0
    private var aC = 0
    private var dynVbo = 0
    private var fb: FloatBuffer = ByteBuffer.allocateDirect(4 * 10 * 6000).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val statics = ArrayList<StaticMesh>()

    private val VS = """
        uniform mat4 uVP;
        attribute vec3 aP;
        attribute vec3 aN;
        attribute vec4 aC;
        varying vec4 vC;
        varying float vFog;
        void main() {
            gl_Position = uVP * vec4(aP, 1.0);
            float l = length(aN);
            vec3 col = aC.rgb;
            if (l > 0.5) {
                vec3 n = aN / l;
                float diff = max(dot(n, normalize(vec3(-0.35, 0.85, 0.40))), 0.0);
                float back = max(dot(n, normalize(vec3(0.5, 0.2, -0.6))), 0.0);
                vec3 lc = vec3(0.50, 0.54, 0.64) + diff * vec3(0.62, 0.56, 0.42) + back * vec3(0.10, 0.12, 0.18);
                col = col * lc;
            }
            vC = vec4(col, aC.a);
            vFog = clamp((gl_Position.w - 1900.0) / 3200.0, 0.0, 1.0);
        }
    """.trimIndent()

    private val FS = """
        precision mediump float;
        uniform vec3 uFog;
        varying vec4 vC;
        varying float vFog;
        void main() {
            gl_FragColor = vec4(mix(vC.rgb, uFog, vFog), vC.a);
        }
    """.trimIndent()

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Shader: $log")
        }
        return s
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val vs = shader(GLES20.GL_VERTEX_SHADER, VS)
        val fs = shader(GLES20.GL_FRAGMENT_SHADER, FS)
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        uVP = GLES20.glGetUniformLocation(prog, "uVP")
        uFog = GLES20.glGetUniformLocation(prog, "uFog")
        aP = GLES20.glGetAttribLocation(prog, "aP")
        aN = GLES20.glGetAttribLocation(prog, "aN")
        aC = GLES20.glGetAttribLocation(prog, "aC")
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        dynVbo = ids[0]
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glClearColor(SKY_R, SKY_G, SKY_B, 1f)
        statics.clear()
        synchronized(g.lock) {
            g.scene.buildStatic(this)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        GLES20.glUseProgram(prog)
        GLES20.glEnableVertexAttribArray(aP)
        GLES20.glEnableVertexAttribArray(aN)
        GLES20.glEnableVertexAttribArray(aC)
        synchronized(g.lock) {
            GLES20.glUniformMatrix4fv(uVP, 1, false, g.vp, 0)
            GLES20.glUniform3f(uFog, SKY_R, SKY_G, SKY_B)
            val b = g.scene.frame()
            val cx = g.camX
            // terreno e cenário fixo (por pedaços visíveis)
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
            for (s in statics) {
                if (s.x1 < cx - 3400f || s.x0 > cx + 3400f) continue
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, s.vbo)
                attribs()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, s.count)
            }
            drawBuf(b.opq)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glDepthMask(false)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            drawBuf(b.alp)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
            drawBuf(b.add)
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun attribs() {
        GLES20.glVertexAttribPointer(aP, 3, GLES20.GL_FLOAT, false, 40, 0)
        GLES20.glVertexAttribPointer(aN, 3, GLES20.GL_FLOAT, false, 40, 12)
        GLES20.glVertexAttribPointer(aC, 4, GLES20.GL_FLOAT, false, 40, 24)
    }

    private fun drawBuf(b: VBuf) {
        if (b.n <= 0) return
        if (fb.capacity() < b.n) {
            fb = ByteBuffer.allocateDirect(4 * (b.n + 20000)).order(ByteOrder.nativeOrder()).asFloatBuffer()
        }
        fb.clear()
        fb.put(b.v, 0, b.n)
        fb.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, dynVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, b.n * 4, fb, GLES20.GL_STREAM_DRAW)
        attribs()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, b.n / 10)
    }

    /** Envia um lote estático (terreno, árvores) para a GPU. */
    fun uploadStatic(b: Batch, x0: Float, x1: Float) {
        val buf = b.opq
        if (buf.n <= 0) return
        val f = ByteBuffer.allocateDirect(4 * buf.n).order(ByteOrder.nativeOrder()).asFloatBuffer()
        f.put(buf.v, 0, buf.n)
        f.position(0)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, buf.n * 4, f, GLES20.GL_STATIC_DRAW)
        statics.add(StaticMesh(ids[0], buf.n / 10, x0, x1))
    }
}
