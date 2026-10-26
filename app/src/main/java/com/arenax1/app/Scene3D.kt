package com.arenax1.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// ------------------------------------------------------------ TERRENO (matemática)

private fun hash2(ix: Int, iz: Int): Float {
    var h = ix * 374761393 + iz * 668265263
    h = (h xor (h ushr 13)) * 1274126177.toInt()
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 65535f
}

fun vnoise(x: Float, z: Float): Float {
    val ix = floor(x).toInt()
    val iz = floor(z).toInt()
    val fx = x - ix
    val fz = z - iz
    val sx = fx * fx * (3f - 2f * fx)
    val sz = fz * fz * (3f - 2f * fz)
    val a = hash2(ix, iz)
    val b = hash2(ix + 1, iz)
    val c = hash2(ix, iz + 1)
    val d = hash2(ix + 1, iz + 1)
    val top = a + (b - a) * sx
    val bot = c + (d - c) * sx
    return top + (bot - top) * sz
}

const val MAP_W = 7200f

/** Altura do chão no ponto (x, z). z do mundo = y do jogo. */
fun groundH(x: Float, z: Float): Float {
    val d = if (z < 170f) 170f - z else if (z > 1110f) z - 1110f else 0f
    var h = 0f
    if (d > 0f) {
        val k = min(1f, d / 80f)
        val s = k * k * (3f - 2f * k)
        val k2 = min(1f, d / 260f)
        h = s * 62f + vnoise(x * 0.012f, z * 0.012f) * 38f * k2 + vnoise(x * 0.05f, z * 0.05f) * 6f * k
    }
    val dx = abs(x - MAP_W / 2f)
    if (dx < 180f) {
        val k = 1f - dx / 180f
        val dip = k * k * (3f - 2f * k)
        val onBridge = z > 395f && z < 885f
        h = if (onBridge) 0.8f else h - dip * 9f
    }
    return h
}

private fun rgbI(r: Float, g: Float, b: Float): Int =
    (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

private fun terrainColor(x: Float, z: Float): Int {
    val n = vnoise(x * 0.05f, z * 0.05f)
    val n2 = vnoise(x * 0.012f, z * 0.012f)
    val d = if (z < 170f) 170f - z else if (z > 1110f) z - 1110f else 0f
    var c: Int
    if (d > 0f) {
        val rock = rgbI(58f + n * 34f, 70f + n * 34f, 96f + n * 38f)
        val snow = rgbI(232f - n * 14f, 241f - n * 10f, 250f)
        c = mixCol(snow, rock, (d - 14f) / 70f)
        c = mixCol(c, rgbI(34f + n * 20f, 44f + n * 22f, 70f + n * 26f), ((d - 190f) / 160f).coerceIn(0f, 1f))
        if (n2 > 0.62f && d > 90f) c = mixCol(c, rgbI(120f, 190f, 230f), 0.35f)
    } else if (z in 400f..880f) {
        c = rgbI(86f + n * 10f, 98f + n * 10f, 120f + n * 10f)
    } else {
        val patch = vnoise(x * 0.006f + 40f, z * 0.02f)
        c = rgbI(222f + n * 12f - n2 * 22f, 234f + n * 8f - n2 * 18f, 248f - n2 * 8f)
        c = mixCol(c, rgbI(150f, 176f, 212f), patch * 0.35f)
        if (z in 372f..400f || z in 880f..908f) c = mixCol(c, rgbI(120f, 138f, 168f), 0.5f)

    }
    val dx = abs(x - MAP_W / 2f)
    if (dx < 190f) {
        val k = 1f - dx / 190f
        c = mixCol(c, rgbI(196f, 224f, 244f), min(1f, k * 2.2f))
        c = mixCol(c, rgbI(78f, 150f, 206f), ((k - 0.42f) * 1.8f).coerceIn(0f, 1f))
    }
    val n3 = vnoise(x * 0.2f + 9f, z * 0.2f)
    c = shadeCol(c, 0.93f + n3 * 0.14f)
    // leve tinta de cada time
    c = if (x < MAP_W / 2f) mixCol(c, rgbI(90f, 140f, 255f), 0.07f) else mixCol(c, rgbI(255f, 100f, 90f), 0.07f)
    return c
}

// ----------------------------------------------------------------------- CENA

class Scene(private val g: GameView) {

    private val b = Batch().also { it.lod = true }
    private var t = 0f
    private var fl = false

    private val white = cc(0xFFFFFFFF)
    private val stone = cc(0xFF8A8D99)
    private val stoneD = cc(0xFF5F6270)
    private val stoneL = cc(0xFFA8ABB8)
    private val wood = cc(0xFF6D4C41)

    private fun K(col: Int): Int = if (fl) mixCol(col, white, 0.75f) else col

    // ---------------------------------------------------- PARTE ESTÁTICA

    fun buildStatic(r: GlRenderer) {
        val cell = 20f
        val x0 = -400f
        val z0 = -200f
        val nx = ((MAP_W + 800f) / cell).toInt() + 1
        val nz = 84
        val hh = Array(nx + 1) { FloatArray(nz + 1) }
        val cols = Array(nx + 1) { IntArray(nz + 1) }
        val nrm = Array(nx + 1) { Array(nz + 1) { FloatArray(3) } }
        for (i in 0..nx) {
            for (j in 0..nz) {
                val x = x0 + i * cell
                val z = z0 + j * cell
                val h = groundH(x, z)
                hh[i][j] = h
                cols[i][j] = terrainColor(x, z)
                val dx = groundH(x + 6f, z) - groundH(x - 6f, z)
                val dz = groundH(x, z + 6f) - groundH(x, z - 6f)
                val nx2 = -dx / 12f
                val nz2 = -dz / 12f
                val l = sqrt(nx2 * nx2 + 1f + nz2 * nz2)
                nrm[i][j][0] = nx2 / l; nrm[i][j][1] = 1f / l; nrm[i][j][2] = nz2 / l
            }
        }
        val perChunk = 20
        val nChunks = (nx + perChunk - 1) / perChunk
        val chunks = Array(nChunks) { Batch() }

        for (ci in 0 until nChunks) {
            val bt = chunks[ci]
            for (i in ci * perChunk until min(nx, (ci + 1) * perChunk)) {
                for (j in 0 until nz) {
                    val xa = x0 + i * cell
                    val xb = xa + cell
                    val za = z0 + j * cell
                    val zb = za + cell
                    fun v(ii: Int, jj: Int, x: Float, z: Float) {
                        bt.rawVert(x, hh[ii][jj], z, nrm[ii][jj][0], nrm[ii][jj][1], nrm[ii][jj][2], cols[ii][jj])
                    }
                    v(i, j, xa, za); v(i + 1, j, xb, za); v(i + 1, j + 1, xb, zb)
                    v(i, j, xa, za); v(i + 1, j + 1, xb, zb); v(i, j + 1, xa, zb)
                }
            }
        }

        fun chunkOf(x: Float): Batch = chunks[((x - x0) / (cell * perChunk)).toInt().coerceIn(0, nChunks - 1)]

        // árvores da selva
        var seed = 0
        for (row in 0 until 3) {
            for (side in 0..1) {
                val zb = if (side == 0) 110f - row * 70f else 1170f + row * 70f
                var ix = 0
                while (ix < 84) {
                    val hx = hash2(ix + row * 97, side * 31 + row)
                    val hz = hash2(ix * 7 + 3, row * 13 + side)
                    val hs = hash2(ix + 11, row * 5 + side * 77)
                    seed++
                    if (hx > 0.12f) {
                        val x = -400f + ix * 92f + hx * 56f
                        val z = zb + (hz - 0.5f) * 36f
                        tree(chunkOf(x), x, groundH(x, z), z, 0.8f + hs * 0.7f, hs)
                    }
                    ix++
                }
            }
        }
        // flores e pedras no gramado
        for (i in 0 until 340) {
            val x = 100f + hash2(i, 1) * (MAP_W - 200f)
            val z = if (hash2(i, 2) < 0.5f) 200f + hash2(i, 3) * 160f else 920f + hash2(i, 4) * 160f
            val kind = hash2(i, 5)
            val bt = chunkOf(x)
            val gy = groundH(x, z)
            if (kind < 0.55f) {
                val fc = if (kind < 0.2f) cc(0xFF7FDBFF) else if (kind < 0.4f) cc(0xFFFFFFFF) else cc(0xFFB39DDB)
                bt.cyl(x, gy, z, 0.6f, 0.6f, 5f, cc(0xFF5F7FA8), 4)
                bt.sphere(x, gy + 6f, z, 2.4f, 1.6f, 2.4f, fc, 1f, true, 6, 3)
            } else {
                bt.sphere(x, gy + 3f, z, 7f + hash2(i, 6) * 5f, 4.5f, 6f, cc(0xFFC9D6E8), 1f, true, 6, 4)
            }
        }
        // bases
        for (team in 0..1) {
            val cx = if (team == 0) 380f else MAP_W - 380f
            val bt = chunkOf(cx)
            val tint = if (team == 0) cc(0xFF4A6FA5) else cc(0xFFA5504A)
            val glow = if (team == 0) cc(0xFF4FC3F7) else cc(0xFFFF5252)
            val dir = if (team == 0) -1f else 1f
            bt.cyl(cx, 0.3f, 640f, 275f, 268f, 3f, mixCol(cc(0xFF6B7080), tint, 0.5f), 36)
            bt.cyl(cx, 3.3f, 640f, 205f, 200f, 4f, stone, 32)
            bt.ring(cx, 7.6f, 640f, 150f, 158f, glow, 1f, false, 32)
            bt.cyl(cx, 7.4f, 640f, 150f, 146f, 1f, stoneD, 32)
            for (k in 0 until 8) {
                val a = k * 0.7854f
                val px = cx + cos(a) * 238f
                val pz = 640f + sin(a) * 238f
                bt.cyl(px, 0.5f, pz, 10f, 8f, 52f, stoneL, 8)
                bt.cyl(px, 52f, pz, 11f, 11f, 5f, stoneD, 8)
                bt.sphere(px, 62f, pz, 6f, 6f, 6f, glow, 1f, false, 6, 4)
            }
            // degraus largos ao redor da plataforma
            bt.cyl(cx, 0.1f, 640f, 296f, 290f, 2f, mixCol(stoneD, tint, 0.35f), 40)
            bt.cyl(cx, 1.6f, 640f, 283f, 278f, 2f, mixCol(stone, tint, 0.2f), 40)
            // piscina de cura luminosa com ondas concêntricas
            bt.cyl(cx, 7.8f, 640f, 140f, 140f, 0.8f, mixCol(glow, cc(0xFF2A6AA0), 0.45f), 32, 1f, false)
            for (k in 0 until 4) {
                bt.ring(cx, 8.8f + k * 0.05f, 640f, 28f + k * 30f, 31f + k * 30f, mixCol(glow, white, 0.55f), 1f, false, 36)
            }
            bt.sphere(cx, 10f, 640f, 16f, 5f, 16f, white, 1f, false, 8, 4)
            // arcos ligando os pilares (abertura voltada para a lane)
            for (k in 0 until 8) {
                val open = if (team == 0) (k == 7 || k == 0) else (k == 3 || k == 4)
                if (open) continue
                val am = k * 0.7854f + 0.3927f
                val mx = cx + cos(am) * 238f * cos(0.3927f)
                val mz = 640f + sin(am) * 238f * cos(0.3927f)
                bt.push()
                bt.tr(mx, 0f, mz)
                bt.rotY(-(Math.toDegrees(am.toDouble()).toFloat() + 90f))
                bt.box(0f, 57f, 0f, 182f, 8f, 12f, stoneL)
                bt.box(0f, 52.5f, 0f, 170f, 1.8f, 7f, glow, 1f, false)
                bt.sphere(0f, 66f, 0f, 7f, 8f, 7f, glow, 1f, false, 7, 4)
                bt.pop()
            }
            // espiras de cristal atrás da base
            val bx = cx + dir * 330f
            crystals(chunkOf(bx), bx, 640f - 150f, 2.3f, glow, team * 7 + 1)
            crystals(chunkOf(bx - dir * 40f), bx - dir * 40f, 640f + 150f, 2.0f, glow, team * 7 + 2)
            crystals(chunkOf(bx + dir * 20f), bx + dir * 20f, 640f, 2.8f, glow, team * 7 + 3)
        }
        // ponte
        run {
            val cx = MAP_W / 2f
            val bt = chunkOf(cx)
            bt.box(cx, -1.8f, 640f, 380f, 5.2f, 480f, cc(0xFFA9B6C8))
            var px = cx - 180f
            while (px <= cx + 180f) {
                bt.box(px, 0.9f, 640f, 2f, 0.4f, 468f, cc(0xFF6F7C92))
                px += 36f
            }
            for (sd in intArrayOf(-1, 1)) {
                val zz = 640f + sd * 236f
                bt.box(cx, 8f, zz, 380f, 12f, 8f, cc(0xFFC3D0E2))
                var qx = cx - 180f
                while (qx <= cx + 180f) {
                    bt.box(qx, 12f, zz, 14f, 22f, 14f, cc(0xFFDCE6F4))
                    bt.sphere(qx, 25f, zz, 5f, 4f, 5f, cc(0xFF8FE3FF), 1f, false, 6, 3)
                    qx += 90f
                }
            }
        }
        run {
            val cx = MAP_W / 2f
            val bt = chunkOf(cx)
            for (e in intArrayOf(-1, 1)) {
                val ax = cx + e * 196f
                for (sd in intArrayOf(-1, 1)) {
                    bt.cyl(ax, 0f, 640f + sd * 236f, 15f, 12f, 86f, stoneL, 10)
                    bt.cyl(ax, 86f, 640f + sd * 236f, 17f, 17f, 6f, stoneD, 10)
                    bt.sphere(ax, 100f, 640f + sd * 236f, 8f, 10f, 8f, cc(0xFF8FE3FF), 1f, false, 8, 5)
                }
                bt.box(ax, 80f, 640f, 14f, 9f, 486f, stoneL)
                bt.box(ax, 75f, 640f, 8f, 2f, 470f, cc(0xFF8FE3FF), 1f, false)
            }
            for (sd in intArrayOf(-1, 1)) {
                bt.box(cx, 1.3f, 640f + sd * 150f, 360f, 0.3f, 3f, cc(0xFF8FE3FF), 1f, false)
                bt.box(cx, 1.3f, 640f + sd * 70f, 360f, 0.3f, 2f, cc(0xFF8FE3FF), 1f, false)
            }
            for (k in 0 until 44) {
                for (sd in intArrayOf(-1, 1)) {
                    val x = cx - 188f + k * 8.6f + hash2(k, 140 + sd) * 4f
                    val h = 10f + hash2(k, 150 + sd) * 34f
                    bt.cyl(x, -5f - h, 640f + sd * 242f, 0f, 2.6f, h, cc(0xFFC9ECFF), 5)
                }
            }
        }
        // lajotas da lane (pedras individuais com junta escura)
        run {
            var px = 0f
            var col = 0
            while (px < MAP_W) {
                if (abs(px + 32f - MAP_W / 2f) > 235f) {
                    val bt = chunkOf(px)
                    val ally = px < MAP_W / 2f
                    for (row in 0 until 7) {
                        val zc = 430f + row * 70f
                        val hsh = hash2(col, row + 40)
                        val shade = 0.86f + hsh * 0.26f
                        val base = cc(0xFFA9B6CC)
                        val tint = mixCol(base, if (ally) cc(0xFF9DB6E0) else cc(0xFFE0A9A2), 0.16f)
                        val off = if (row % 2 == 0) 0f else 32f
                        bt.box(px + off + 32f, -0.5f + hsh * 0.25f, zc, 62f, 2f, 62f, shadeCol(tint, shade))
                    }
                }
                px += 64f
                col++
            }
            // meio-fio dourado e linha de energia no centro
            var qx = 0f
            while (qx < MAP_W) {
                if (abs(qx + 70f - MAP_W / 2f) > 275f) {
                    val bt = chunkOf(qx)
                    val gl = if (qx < MAP_W / 2f) cc(0xFF4FC3F7) else cc(0xFFFF6B5E)
                    for (sd in intArrayOf(-1, 1)) {
                        bt.box(qx + 70f, 2f, 640f + sd * 243f, 140f, 5f, 9f, cc(0xFF8C9AB2))
                        bt.box(qx + 70f, 4.6f, 640f + sd * 243f, 140f, 0.8f, 3f, gl)
                    }
                    bt.box(qx + 70f, 0.8f, 640f, 70f, 0.4f, 3.5f, gl, 1f, false)
                }
                qx += 140f
            }
        }
        // capim, arbustos, rochas e cristais
        run {
            for (i in 0 until 1100) {
                val x = 60f + hash2(i, 61) * (MAP_W - 120f)
                val top = hash2(i, 62) < 0.5f
                val z = if (top) 180f + hash2(i, 63) * 190f else 910f + hash2(i, 64) * 190f
                if (abs(x - MAP_W / 2f) < 200f) continue
                val bt = chunkOf(x)
                val gy = groundH(x, z)
                val gc = mixCol(cc(0xFF7FA3C9), cc(0xFFDDEBFA), hash2(i, 65))
                for (k in 0 until 3) {
                    bt.cyl(x + k * 2.4f - 2.4f, gy, z + (k % 2) * 2f, 1.5f, 0f, 8f + hash2(i, 66 + k) * 8f, gc, 4)
                }
            }
            for (i in 0 until 46) {
                val x = 500f + hash2(i, 81) * (MAP_W - 1000f)
                if (abs(x - MAP_W / 2f) < 330f) continue
                val z = if (hash2(i, 82) < 0.5f) 200f + hash2(i, 83) * 130f else 930f + hash2(i, 84) * 130f
                rock(chunkOf(x), x, z, 18f + hash2(i, 85) * 10f, i + 300, true)
            }
            for (i in 0 until 70) {
                val x = 100f + hash2(i, 111) * (MAP_W - 200f)
                val side = hash2(i, 112) < 0.5f
                val z = if (side) 30f + hash2(i, 113) * 120f else 1130f + hash2(i, 114) * 120f
                val bt = chunkOf(x)
                rock(bt, x, z, 26f + hash2(i, 115) * 28f, i + 600, true)
                if (hash2(i, 117) < 0.3f) {
                    val cr = if (x < MAP_W / 2f) cc(0xFF5BC8FF) else cc(0xFFFF7BC6)
                    crystals(bt, x + 40f, z + 12f, 0.7f + hash2(i, 118) * 0.6f, cr, i + 900)
                }
            }
            // montanhas ao fundo (horizonte)
            for (i in 0 until 46) {
                val x = -300f + i * 175f + hash2(i, 121) * 80f
                for (side in 0..1) {
                    val z = if (side == 0) -300f - hash2(i, 122 + side) * 260f else 1580f + hash2(i, 124 + side) * 260f
                    val h = 330f + hash2(i, 126 + side) * 360f
                    val r = 190f + hash2(i, 128 + side) * 150f
                    val bt = chunkOf(x)
                    val rc = mixCol(cc(0xFF4A5778), cc(0xFF7388B0), hash2(i, 130 + side))
                    bt.cyl(x, -10f, z, r, 0f, h, rc, 7)
                    bt.cyl(x, h * 0.72f - 10f, z, r * 0.3f, 0f, h * 0.3f, cc(0xFFF1F4FA), 7)
                }
            }
        }
        // pilares luminosos nas bordas da lane + muretas (estilo ARAM)
        run {
            var px = 760f
            var idx = 0
            while (px < MAP_W - 700f) {
                if (abs(px - MAP_W / 2f) > 300f) {
                    val bt = chunkOf(px)
                    val ally = px < MAP_W / 2f
                    val gl = if (ally) cc(0xFF4FC3F7) else cc(0xFFFF5252)
                    for (sd in intArrayOf(-1, 1)) {
                        val pz = 640f + sd * 262f
                        val gy = groundH(px, pz)
                        bt.cyl(px, gy, pz, 9f, 7f, 30f, stoneL, 6)
                        bt.cyl(px, gy + 30f, pz, 10f, 10f, 4f, stoneD, 6)
                        bt.sphere(px, gy + 40f, pz, 5f, 6f, 5f, gl, 1f, false, 6, 4)
                        if (idx % 2 == 0) bt.box(px + 120f, gy + 6f, pz + sd * 8f, 150f, 12f, 10f, mixCol(stoneD, gl, 0.12f))
                    }
                    idx++
                }
                px += 240f
            }
            // rochas grandes na margem do rio
            for (k in 0 until 14) {
                val sd = if (k % 2 == 0) -1 else 1
                val rx = MAP_W / 2f + sd * (210f + hash2(k, 21) * 40f)
                val rz = 330f + hash2(k, 22) * 620f
                val bt = chunkOf(rx)
                rock(bt, rx, rz, 22f + hash2(k, 23) * 14f, k + 1200, true)
            }
        }
        // moitas na lane (dois pontos de cada lado, como no ARAM) e cristais no abismo
        run {
            val fr = floatArrayOf(0.20f, 0.30f, 0.41f, 0.59f, 0.70f, 0.80f)
            var sdx = 0
            for (f in fr) {
                val bx = MAP_W * f
                for (zc in floatArrayOf(418f, 862f)) {
                    brush(chunkOf(bx), bx, zc, 62f, sdx * 17 + 5)
                    sdx++
                }
            }
            for (i in 0 until 26) {
                val x = 200f + hash2(i, 161) * (MAP_W - 400f)
                if (abs(x - MAP_W / 2f) < 300f) continue
                val z = if (hash2(i, 162) < 0.5f) 150f else 1130f
                val cr = if (x < MAP_W / 2f) cc(0xFF5BC8FF) else cc(0xFFFF7BC6)
                crystals(chunkOf(x), x, z, 0.8f + hash2(i, 163) * 0.9f, cr, i + 1500)
            }
        }
        for (ci in 0 until nChunks) {
            val xa = x0 + ci * perChunk * cell
            r.uploadStatic(chunks[ci], xa - 300f, xa + perChunk * cell + 300f)
        }
    }

    /** Moita densa estilo ARAM: massa escura, folhas em leque e flores luminosas. */
    private fun brush(bt: Batch, cx: Float, cz: Float, rad: Float, seed: Int) {
        for (c in 0 until 9) {
            val a = hash2(seed, c * 3) * 6.2832f
            val d = sqrt(hash2(seed, c * 3 + 1)) * rad
            val x = cx + cos(a) * d
            val z = cz + sin(a) * d * 0.7f
            val gy = groundH(x, z) + 0.5f
            val sz = 0.8f + hash2(seed, c * 3 + 2) * 0.6f
            val dark = mixCol(cc(0xFF1E5A4A), cc(0xFF2F7F63), hash2(seed + 5, c))
            val mid = mixCol(cc(0xFF2F8F6A), cc(0xFF5BBF86), hash2(seed + 7, c))
            val tipc = mixCol(cc(0xFF8FE0B4), cc(0xFFDDF6F0), hash2(seed + 9, c))
            bt.sphere(x, gy + 9f * sz, z, 15f * sz, 11f * sz, 14f * sz, dark, 1f, true, 9, 6)
            bt.sphere(x + 4f, gy + 13f * sz, z - 3f, 11f * sz, 10f * sz, 10f * sz, mid, 1f, true, 8, 5)
            for (k in 0 until 16) {
                val ra = hash2(seed + c, k * 2) * 360f
                val lean = 12f + hash2(seed + c, k * 2 + 1) * 34f
                val hgt = (22f + hash2(seed, c * 31 + k) * 20f) * sz
                bt.push()
                bt.tr(x, gy + 6f * sz, z)
                bt.rotY(ra)
                bt.rotZ(lean)
                bt.cyl(0f, 0f, 0f, 3.6f * sz, 0f, hgt, mixCol(mid, tipc, k / 16f), 5)
                bt.pop()
            }
            if (hash2(seed, c + 200) < 0.45f) {
                bt.sphere(x + 6f, gy + 26f * sz, z + 4f, 2.2f, 2.2f, 2.2f, cc(0xFFBFF3FF), 1f, false, 5, 3)
                bt.sphere(x - 7f, gy + 22f * sz, z - 5f, 1.8f, 1.8f, 1.8f, cc(0xFFFFFFFF), 1f, false, 5, 3)
            }
        }
    }

    /** Rocha composta: vários blocos arredondados com tampa de neve. */
    private fun rock(bt: Batch, x: Float, z: Float, r: Float, seed: Int, snow: Boolean) {
        val gy = groundH(x, z)
        val base = mixCol(cc(0xFF4E5A72), cc(0xFF8794B0), hash2(seed, 1))
        for (k in 0 until 5) {
            val ox = (hash2(seed, 10 + k) - 0.5f) * r * 1.2f
            val oz = (hash2(seed, 20 + k) - 0.5f) * r
            val rr = r * (0.45f + hash2(seed, 30 + k) * 0.5f)
            val hy = rr * (0.7f + hash2(seed, 40 + k) * 0.7f)
            val c2 = shadeCol(base, 0.85f + hash2(seed, 50 + k) * 0.3f)
            bt.sphere(x + ox, gy + hy * 0.55f, z + oz, rr * 1.1f, hy, rr, c2, 1f, true, 7, 5)
            if (snow) bt.sphere(x + ox, gy + hy * 1.18f, z + oz, rr * 0.85f, hy * 0.32f, rr * 0.78f, cc(0xFFEEF5FF), 1f, true, 7, 4)
        }
    }

    /** Aglomerado de cristais facetados com pontas luminosas. */
    private fun crystals(bt: Batch, x: Float, z: Float, s: Float, col: Int, seed: Int) {
        val gy = groundH(x, z)
        for (k in 0 until 7) {
            val ra = hash2(seed, k) * 360f
            val lean = hash2(seed, 10 + k) * 28f
            val h = (30f + hash2(seed, 20 + k) * 60f) * s
            val r = (5f + hash2(seed, 30 + k) * 5f) * s
            bt.push()
            bt.tr(x + (hash2(seed, 40 + k) - 0.5f) * 22f * s, gy, z + (hash2(seed, 50 + k) - 0.5f) * 18f * s)
            bt.rotY(ra)
            bt.rotZ(lean)
            bt.cyl(0f, -2f, 0f, r, r * 0.9f, h * 0.62f, mixCol(col, cc(0xFF203A66), 0.35f), 6)
            bt.cyl(0f, h * 0.62f - 2f, 0f, r * 0.9f, 0f, h * 0.38f, col, 6, 1f, false)
            bt.pop()
        }
    }

    private fun tree(bt: Batch, x: Float, y: Float, z: Float, s: Float, hs: Float) {
        val trunk = cc(0xFF4A3B38)
        val gA = mixCol(cc(0xFF1D4A55), cc(0xFF2A6A6E), hs)
        val gB = mixCol(cc(0xFF245C63), cc(0xFF3A8486), hs)
        val sn = cc(0xFFF1F7FF)
        bt.cyl(x, y - 2f, z, 6f * s, 4f * s, 34f * s, trunk, 6)
        for (k in 0 until 4) {
            val yy = y + (18f + k * 28f) * s
            val r = (48f - k * 10f) * s
            val hgt = (50f - k * 4f) * s
            bt.cyl(x, yy, z, r, 0f, hgt, if (k % 2 == 0) gA else gB, 9)
            bt.cyl(x, yy + hgt * 0.36f, z, r * 0.7f, 0f, hgt * 0.64f, sn, 9)
        }
    }

    // ------------------------------------------------------------ QUADRO

    fun frame(): Batch {
        b.clear()
        t = g.t
        val cx = g.camX
        fl = false

        // água do rio
        val wx = MAP_W / 2f
        b.box(wx, -3.4f, 640f, 330f, 0.4f, 1700f, cc(0xFF8FD3F0), 0.72f, false)
        b.mode = 2
        for (i in 0 until 9) {
            val zz = ((i * 190f + t * 38f) % 1700f) - 200f
            b.box(wx + sin(t + i) * 40f, -3f, zz, 70f + (i % 3) * 30f, 0.3f, 3f, white, 0.35f, false)
        }
        b.mode = 0

        // neve caindo
        b.mode = 2
        for (i in 0 until 110) {
            val fx = cx - 1500f + ((i * 0.618034f) % 1f) * 3000f + sin(t * 0.8f + i) * 18f
            val fz = g.CY - 900f + ((i * 0.381966f + 0.17f) % 1f) * 1700f

            val spd = 55f + ((i * 0.7548f) % 1f) * 60f
            val fy = 760f - ((t * spd + i * 61f) % 760f)
            b.box(fx, fy, fz, 2.6f, 2.6f, 2.6f, white, 0.75f, false)
        }
        b.mode = 0
        for (tw in g.towers) {
            if (abs(tw.x - cx) > 2700f) continue
            if (tw.dead) rubble(tw) else tower(tw)
        }
        for (m in g.minions) {
            if (m.dead || abs(m.x - cx) > 2500f) continue
            place(m)
            fl = m.flash > 0f
            b.sc(1.7f, 1.7f, 1.7f)
            minion(m)
            b.pop()
            shadow(m.x, m.y, 22f)
        }
        for (c in g.champs) {
            if (c.dead || abs(c.x - cx) > 2500f) continue
            place(c)
            fl = c.flash > 0f
            b.sc(1.9f, 1.9f, 1.9f)
            lux(c)
            b.pop()
            shadow(c.x, c.y, 32f)
            teamRing(c)
        }
        fl = false
        for (f in g.deadFx) {
            val u = f.u
            val k = (f.t / 0.8f).coerceIn(0f, 1f)
            place(u)
            fl = false
            b.rotZ(-k * 88f)
            val s = 1f - k * 0.55f
            b.sc(s, s, s)
            if (u is Champ) { b.sc(1.9f, 1.9f, 1.9f); lux(u) } else if (u is Minion) { b.sc(1.7f, 1.7f, 1.7f); minion(u) }
            b.pop()
        }
        for (z in g.zones) zone(z)
        for (bm in g.beams) beam(bm)
        for (q in g.projs) proj(q)
        b.mode = 2
        for (p in g.parts) {
            val a = min(1f, p.life * 2.5f)
            b.box(p.x, groundH(p.x, p.y) + p.z, p.y, p.sz, p.sz, p.sz, p.col, a, false)
        }
        b.mode = 0
        aim()
        return b
    }

    private fun place(u: U) {
        b.push()
        b.tr(u.x, groundH(u.x, u.y), u.y)
        b.rotY(-Math.toDegrees(u.face.toDouble()).toFloat())
    }

    private fun shadow(x: Float, y: Float, r: Float) {
        val gy = groundH(x, y) + 0.9f
        val bk = cc(0xFF050A1E)
        b.disc(x, gy, y, r, bk, 0.13f, false, 16)
        b.disc(x, gy + 0.05f, y, r * 0.76f, bk, 0.13f, false, 16)
        b.disc(x, gy + 0.1f, y, r * 0.5f, bk, 0.16f, false, 14)
    }

    private fun teamRing(c: Champ) {
        val col = if (c.team == 0) cc(0xFF4FC3F7) else cc(0xFFFF5252)
        val gy = groundH(c.x, c.y) + 1.1f
        b.mode = 2
        b.ring(c.x, gy, c.y, 34f, 39f, col, 0.85f, false, 28)
        if (c.rootT > 0f) b.ring(c.x, gy + 0.2f, c.y, 44f, 50f, cc(0xFFFFF176), 0.95f, false, 28)
        b.mode = 0
    }

    // ------------------------------------------------------- CAMPEÃ (LUX)

    /** Segmento pendurado para baixo a partir da origem: r0 em cima, r1 embaixo. */
    private fun limb(len: Float, r0: Float, r1: Float, col: Int, seg: Int = 10) {
        b.cyl(0f, -len, 0f, r1, r0, len, col, seg)
    }

    /** Mecha/trança: cadeia de elipsoides que afunila e balança suavemente. */
    private fun strand(n: Int, r0: Float, r1: Float, step: Float, c1: Int, c2: Int, sway: Float, curl: Float) {
        b.push()
        for (i in 0 until n) {
            val k = i / (n - 1f)
            val r = r0 + (r1 - r0) * k
            b.rotZ(curl + sway * sin(t * 2.4f + i * 0.8f))
            b.sphere(0f, -step * 0.5f, 0f, r, step * 0.78f, r, K(if (i % 2 == 0) c1 else c2), 1f, true, 10, 6)
            b.tr(0f, -step, 0f)
        }
        b.pop()
    }

    private fun lux(c: Champ) {
        b.dq = 0.8f
        val ally = c.team == 0
        val dress = if (ally) cc(0xFFF7F9FF) else cc(0xFF3E2D68)
        val dressS = if (ally) cc(0xFFD5DEF5) else cc(0xFF2A1D4A)
        val trim = if (ally) cc(0xFF3D74E6) else cc(0xFF9B55E8)
        val trimD = if (ally) cc(0xFF244FB0) else cc(0xFF6B33B2)
        val gold = if (ally) cc(0xFFFFD54F) else cc(0xFFE6BAFF)
        val goldD = if (ally) cc(0xFFD9A01C) else cc(0xFFB586EA)
        val hair = if (ally) cc(0xFFFFE483) else cc(0xFF8D41D6)
        val hairD = if (ally) cc(0xFFE9B94B) else cc(0xFF5F23A3)
        val skin = if (ally) cc(0xFFFFE0CA) else cc(0xFFEEC9B7)
        val skinD = if (ally) cc(0xFFF2BFA6) else cc(0xFFD9A894)
        val glow = if (ally) cc(0xFFFFF7B8) else cc(0xFFE9BCFF)
        val gem = if (ally) cc(0xFF62C0FF) else cc(0xFFFF80E6)
        val eye = if (ally) cc(0xFF2F78FF) else cc(0xFFB43CFF)
        val amp = (c.mv / 0.1f).coerceIn(0f, 1f)
        val ph = c.walk * 1.1f
        val sw = sin(ph) * amp
        val bob = abs(sin(ph)) * 2.0f * amp + sin(t * 2f) * 0.6f
        val ck = (c.castT / 0.45f).coerceIn(0f, 1f)
        val ak = ((c.atkCd - 0.45f) / 0.25f).coerceIn(0f, 1f)
        val act = max(ck, ak)

        // aura e sigilo de conjuração no chão
        b.mode = 2
        b.disc(0f, 0.9f, 0f, 30f, glow, 0.08f, false, 24)
        if (act > 0.02f) {
            val sr = 38f + 16f * act
            b.ring(0f, 1.2f, 0f, sr, sr + 3.2f, glow, 0.7f * act, false, 36)
            b.ring(0f, 1.3f, 0f, sr * 0.64f, sr * 0.64f + 2.2f, white, 0.5f * act, false, 32)
            for (k in 0 until 8) {
                b.push()
                b.rotY(k * 45f + t * 80f)
                b.box(sr * 0.5f, 1.4f, 0f, sr * 0.85f, 0.4f, 2.0f, glow, 0.4f * act, false)
                b.pop()
            }
        }
        b.mode = 0

        // ---------------- pernas e botas
        for (s in intArrayOf(-1, 1)) {
            val swing = s * sw
            b.push()
            b.tr(0f, 27.5f, s * 4.8f)
            b.rotZ(swing * 30f)
            limb(12f, 3.4f, 2.8f, K(skin), 12)
            b.sphere(0f, -12f, 0f, 3.0f, 3.0f, 3.0f, K(skin), 1f, true, 10, 6)
            b.tr(0f, -12f, 0f)
            b.rotZ(-max(0f, -swing) * 36f - 2f)
            // cano da bota branco com punho azul e anel dourado
            b.cyl(0f, -15.4f, 0f, 3.4f, 3.1f, 15.4f, K(dress), 12)
            b.cyl(0f, -8.6f, 0f, 3.9f, 3.8f, 1.7f, K(trim), 12)
            b.cyl(0f, -3.4f, 0f, 4.1f, 3.7f, 2.2f, K(gold), 12)
            // pé arredondado, bico dourado e sola
            b.sphere(2.8f, -13.7f, 0f, 6.0f, 2.7f, 3.5f, K(dress), 1f, true, 10, 6)
            b.sphere(7.4f, -14.0f, 0f, 2.5f, 1.9f, 2.5f, K(gold), 1f, true, 8, 5)
            b.sphere(2.4f, -15.3f, 0f, 6.2f, 0.9f, 3.3f, K(goldD), 1f, true, 10, 4)
            b.pop()
        }

        // ---------------- corpo (inclina pra frente ao andar)
        b.push()
        b.tr(0f, 27f + bob, 0f)
        b.rotZ(-amp * 5f - act * 4f)
        b.tr(0f, -27f, 0f)

        // saia: base azul lisa + pétalas brancas arredondadas com barra dourada
        b.cyl(0f, 18.5f, 0f, 17.5f, 9.5f, 13f, K(trim), 16)
        b.cyl(0f, 18.2f, 0f, 18.0f, 17.7f, 1.6f, K(trimD), 16)
        for (i in 0 until 14) {
            val flare = amp * 9f * abs(sin(ph + i * 0.5f)) + sin(t * 2f + i) * 2f
            b.push()
            b.rotY(i * 25.714f + 6f)
            b.tr(10.4f, 33.5f, 0f)
            b.rotZ(24f + flare)
            val pc = if (i % 2 == 0) dress else dressS
            b.sphere(0.9f, -10.4f, 0f, 1.4f, 12f, 5.2f, K(pc), 1f, true, 8, 6)
            b.sphere(1.2f, -19.8f, 0f, 1.6f, 2.5f, 4.8f, K(if (i % 2 == 0) gold else goldD), 1f, true, 8, 4)
            b.sphere(2.1f, -22.4f, 0f, 1.2f, 1.2f, 1.2f, if (fl) white else gem, 1f, false, 6, 4)
            b.pop()
        }
        // painéis longos traseiros (capa) em formato de folha
        for (s in intArrayOf(-1, 1)) {
            b.push()
            b.tr(-8f, 33f, s * 5f)
            b.rotZ(10f + amp * 24f + sin(t * 3f + s) * 4f)
            b.sphere(-1.5f, -13f, 0f, 1.5f, 14.5f, 4.6f, K(trim), 1f, true, 8, 6)
            b.sphere(-1.2f, -13f, 0f, 1.5f, 11f, 1.4f, K(goldD), 1f, true, 6, 5)
            b.sphere(-1.7f, -26.5f, 0f, 1.7f, 2.4f, 4.8f, K(gold), 1f, true, 8, 4)
            b.pop()
        }
        // cintura dourada
        b.cyl(0f, 30.2f, 0f, 10.8f, 9.4f, 3f, K(gold), 16)
        b.sphere(8.8f, 31.7f, 0f, 2.2f, 2.2f, 2.2f, if (fl) white else gem, 1f, false, 8, 5)
        b.sphere(8.2f, 31.7f, 0f, 3.3f, 3.3f, 3.0f, K(goldD), 1f, true, 10, 6)

        // tronco / corpete
        b.cyl(0f, 32f, 0f, 8.6f, 6.6f, 14f, K(dress), 16)
        b.cyl(0f, 36.6f, 0f, 8.3f, 8.0f, 1.2f, K(gold), 16)
        b.cyl(0f, 41.2f, 0f, 7.7f, 7.3f, 1.2f, K(gold), 16)
        b.sphere(7.0f, 38.5f, 0f, 1.0f, 5.2f, 1.9f, K(trim), 1f, true, 6, 5)
        b.sphere(2.6f, 43.5f, 3.3f, 3.7f, 3.1f, 3.7f, K(dress), 1f, true, 10, 6)
        b.sphere(2.6f, 43.5f, -3.3f, 3.7f, 3.1f, 3.7f, K(dress), 1f, true, 10, 6)
        // gema do peito
        b.sphere(7.4f, 41f, 0f, 3.2f, 3.4f, 3.2f, K(gold), 1f, true, 10, 6)
        b.sphere(8.3f, 41f, 0f, 1.9f, 2.4f, 1.9f, if (fl) white else gem, 1f, false, 8, 5)
        // colar e pescoço
        b.cyl(0f, 45f, 0f, 6.6f, 5.2f, 3.2f, K(gold), 14)
        b.cyl(0f, 47.6f, 0f, 2.7f, 2.4f, 5.6f, K(skin), 10)
        // ombreiras douradas com lâmina curva
        for (s in intArrayOf(-1, 1)) {
            b.sphere(0f, 46.6f, s * 8.6f, 4.7f, 3.3f, 4.3f, K(gold), 1f, true, 12, 7)
            b.sphere(0.4f, 48.5f, s * 8.6f, 2.0f, 1.4f, 2.0f, if (fl) white else gem, 1f, false, 8, 5)
            b.push()
            b.tr(0f, 46f, s * 11.6f)
            b.rotX(s * 30f)
            b.sphere(0f, -2.6f, 0f, 1.2f, 4.2f, 1.1f, K(goldD), 1f, true, 6, 5)
            b.pop()
        }

        // ---------------- cabeça (proporção maior, estilo LoL)
        b.push()
        b.tr(0f, 58f, 0f)
        b.sc(1.1f, 1.1f, 1.1f)
        b.tr(0f, -58f, 0f)
        b.sphere(0f, 62f, 0f, 9.6f, 10.2f, 9.4f, K(skin), 1f, true, 16, 10)
        b.sphere(1.2f, 57.2f, 0f, 6.6f, 4.6f, 6.4f, K(skin), 1f, true, 12, 7)
        for (s in intArrayOf(-1, 1)) {
            // olho grande: esclera, íris, pupila e brilhos
            b.sphere(8.2f, 61.4f, s * 3.9f, 1.3f, 3.1f, 2.4f, if (fl) white else cc(0xFFFFFFFF), 1f, false, 8, 6)
            b.sphere(8.9f, 61.0f, s * 3.9f, 0.9f, 2.6f, 2.0f, if (fl) white else eye, 1f, false, 8, 6)
            b.sphere(9.3f, 60.6f, s * 3.9f, 0.6f, 1.7f, 1.3f, if (fl) white else cc(0xFF0E1B4D), 1f, false, 6, 4)
            b.sphere(9.5f, 62.4f, s * 3.2f, 0.45f, 0.8f, 0.8f, white, 1f, false, 5, 3)
            b.sphere(9.4f, 59.8f, s * 4.6f, 0.3f, 0.45f, 0.45f, white, 1f, false, 5, 3)
            // cílios e sobrancelha curvos
            b.sphere(8.8f, 64.7f, s * 4.1f, 0.8f, 0.6f, 2.5f, K(hairD), 1f, true, 6, 4)
            b.sphere(8.3f, 66.5f, s * 4.4f, 0.6f, 0.5f, 2.0f, K(hairD), 1f, true, 6, 4)
            // bochechas
            b.sphere(8.0f, 58.2f, s * 6.0f, 1.0f, 0.8f, 1.6f, K(cc(0xFFFF9C96)), 1f, true, 6, 4)
            // orelha
            b.sphere(0.5f, 61.2f, s * 9.4f, 1.5f, 2.4f, 0.9f, K(skinD), 1f, true, 6, 4)
        }
        b.sphere(9.4f, 58.4f, 0f, 0.8f, 0.8f, 0.9f, K(skinD), 1f, true, 5, 3)
        b.sphere(8.9f, 55.9f, 0f, 0.6f, 0.5f, 1.4f, K(cc(0xFFD8665E)), 1f, true, 6, 3)

        // ---------------- cabelo volumoso
        b.sphere(-2.6f, 63.0f, 0f, 11.2f, 12.0f, 11.2f, K(hair), 1f, true, 16, 10)
        b.sphere(1.0f, 70.6f, 0f, 8.8f, 5.0f, 10.0f, K(hair), 1f, true, 12, 7)
        b.sphere(-7.8f, 55.0f, 0f, 5.6f, 8.6f, 7.8f, K(hairD), 1f, true, 10, 7)
        // franja em mechas curvas
        for (k in -3..3) {
            b.push()
            b.tr(6.4f, 68.4f, k * 3.0f)
            b.rotZ(-16f - abs(k) * 3f)
            b.rotX(k * 8f)
            b.sphere(0.8f, -2.8f, 0f, 2.6f, 6.0f, 2.3f, K(if (k % 2 == 0) hair else hairD), 1f, true, 8, 6)
            b.pop()
        }
        for (s in intArrayOf(-1, 1)) {
            // mechas laterais
            b.push()
            b.tr(3.4f, 66f, s * 9.4f)
            b.rotX(s * 6f)
            b.sphere(0f, -6.6f, 0f, 2.7f, 9.6f, 2.1f, K(hair), 1f, true, 8, 7)
            b.pop()
            // coque + longas maria-chiquinhas em cadeia de mechas
            b.push()
            b.tr(-5f, 67.5f, s * 9.6f)
            b.rotX(s * 18f)
            b.rotZ(8f + amp * 14f)
            b.sphere(0f, 0f, 0f, 6.0f, 6.0f, 6.0f, K(hair), 1f, true, 12, 8)
            b.cyl(0f, -3.2f, 0f, 4.2f, 4.2f, 2.6f, K(gold), 12)
            b.sphere(0f, 3.8f, 0f, 1.9f, 1.9f, 1.9f, if (fl) white else gem, 1f, false, 6, 4)
            b.tr(0f, -3.6f, 0f)
            b.rotZ(-26f - amp * 8f)
            strand(9, 5.4f, 1.4f, 7f, hair, hairD, 5f + amp * 4f, 5f)
            b.pop()
        }
        // tiara com asinhas
        b.sphere(6.6f, 69.8f, 0f, 1.5f, 1.2f, 5.6f, K(gold), 1f, true, 8, 5)
        b.sphere(7.8f, 70.4f, 0f, 2.2f, 2.8f, 2.2f, if (fl) white else gem, 1f, false, 8, 5)
        for (s in intArrayOf(-1, 1)) {
            b.push()
            b.tr(5.8f, 70.4f, s * 5.2f)
            b.rotX(s * -30f)
            b.sphere(0f, 3.4f, 0f, 1.3f, 4.2f, 0.9f, K(gold), 1f, true, 6, 5)
            b.pop()
            b.push()
            b.tr(3.2f, 71f, s * 8.4f)
            b.rotX(s * -52f)
            b.sphere(0f, 2.6f, 0f, 1.0f, 3.2f, 0.8f, K(goldD), 1f, true, 6, 5)
            b.pop()
        }

        b.pop()

        // ---------------- asas: penas em leque (elipsoides dourados e brancos)
        for (s in intArrayOf(-1, 1)) {
            for (k in 0 until 6) {
                val len = 30f - k * 3.4f
                b.push()
                b.tr(-8f, 47f + k * 0.8f, s * 3.2f)
                b.rotX(s * (12f + k * 13f))
                b.rotZ(32f - k * 3f + sin(t * 2.6f + k * 0.8f) * 3.5f + amp * 6f)
                b.sphere(0f, len * 0.5f, 0f, 1.8f, len * 0.5f, 3.6f - k * 0.2f, K(if (k % 2 == 0) gold else goldD), 1f, true, 8, 7)
                b.sphere(1.0f, len * 0.6f, 0f, 1.3f, len * 0.4f, 2.5f - k * 0.15f, K(dress), 1f, true, 8, 6)
                b.sphere(0.4f, len * 0.95f, 0f, 1.2f, 3.2f, 2.2f, K(gold), 1f, true, 6, 4)
                b.mode = 2
                b.sphere(0f, len * 0.55f, 0f, 1.2f, len * 0.7f, 5.2f, glow, 0.12f, false, 8, 6)
                b.mode = 0
                b.pop()
            }
        }

        // ---------------- braço livre
        run {
            val a1 = -sw * 32f + 5f
            b.push()
            b.tr(0f, 46.4f, -9.4f)
            b.rotZ(a1)
            b.sphere(0f, 0f, 0f, 3.8f, 3.6f, 3.6f, K(dress), 1f, true, 10, 6)
            limb(10.5f, 2.5f, 2.2f, K(dress), 12)
            b.cyl(0f, -10.8f, 0f, 2.9f, 2.9f, 1.6f, K(trim), 12)
            b.tr(0f, -10.5f, 0f)
            b.rotZ(-(14f + max(0f, sw) * 14f))
            limb(9.5f, 2.2f, 1.9f, K(skin), 12)
            b.cyl(0f, -9.2f, 0f, 2.8f, 2.6f, 4.8f, K(gold), 12)
            b.sphere(0f, -10.6f, 0f, 2.3f, 2.4f, 2.3f, K(skin), 1f, true, 10, 6)
            b.pop()
        }

        // ---------------- braço do cajado + cajado
        run {
            val armA = 10f + sw * 16f + act * 74f
            val elbow = -(12f + act * 14f)
            val staffW = -14f - 56f * act
            b.push()
            b.tr(0f, 46.4f, 9.4f)
            b.rotZ(armA)
            b.sphere(0f, 0f, 0f, 3.8f, 3.6f, 3.6f, K(dress), 1f, true, 10, 6)
            limb(10.5f, 2.5f, 2.2f, K(dress), 12)
            b.cyl(0f, -10.8f, 0f, 2.9f, 2.9f, 1.6f, K(trim), 12)
            b.tr(0f, -10.5f, 0f)
            b.rotZ(elbow)
            limb(9.5f, 2.2f, 1.9f, K(skin), 12)
            b.cyl(0f, -9.2f, 0f, 2.8f, 2.6f, 4.8f, K(gold), 12)
            b.sphere(0f, -10.6f, 0f, 2.3f, 2.4f, 2.3f, K(skin), 1f, true, 10, 6)
            b.tr(0f, -10.6f, 0f)
            b.rotZ(staffW - armA - elbow)
            // haste branca com anéis dourados
            b.cyl(0f, -24f, 0f, 1.3f, 1.4f, 76f, K(dress), 10)
            for (ry in intArrayOf(-12, 6, 24, 40)) {
                b.cyl(0f, ry.toFloat(), 0f, 2.4f, 2.4f, 1.8f, K(gold), 10)
            }
            b.cyl(0f, 42f, 0f, 2.3f, 3.8f, 5f, K(gold), 10)
            // lua crescente dourada em arco suave segurando o cristal
            for (j in 0..8) {
                val ang = Math.toRadians((-84.0 + j * 21.0)).toFloat()
                val rr = 1.9f - abs(j - 4) * 0.2f
                b.sphere(0f, 49.2f + 8.0f * (1f - cos(ang)), 8.2f * sin(ang), rr, rr, rr, K(if (j % 2 == 0) gold else goldD), 1f, true, 8, 5)
            }
            b.sphere(0f, 57.6f, 8.2f, 1.2f, 2.6f, 1.2f, K(gold), 1f, true, 6, 4)
            b.sphere(0f, 57.6f, -8.2f, 1.2f, 2.6f, 1.2f, K(gold), 1f, true, 6, 4)
            // cristal facetado girando
            val pulse = 1f + 0.16f * sin(t * 6f)
            b.push()
            b.tr(0f, 57f, 0f)
            b.rotY(t * 60f)
            b.cyl(0f, -8f, 0f, 0f, 5.2f, 8f, if (fl) white else gem, 6)
            b.cyl(0f, 0f, 0f, 5.2f, 0f, 9f, if (fl) white else glow, 6)
            b.pop()
            b.mode = 2
            val gr = (10f + 8f * act) * pulse
            b.sphere(0f, 57f, 0f, gr, gr, gr, glow, 0.10f + 0.18f * act, false, 10, 7)
            b.sphere(0f, 57f, 0f, 5.4f * pulse, 5.4f * pulse, 5.4f * pulse, white, 0.32f, false, 8, 5)
            b.push()
            b.tr(0f, 57f, 0f)
            b.rotY(t * 140f)
            b.rotX(65f)
            b.ring(0f, 0f, 0f, 8.4f, 9.4f, white, 0.5f, false, 20)
            b.pop()
            b.push()
            b.tr(0f, 57f, 0f)
            b.rotY(-t * 110f)
            b.rotZ(60f)
            b.ring(0f, 0f, 0f, 11.4f, 12.1f, glow, 0.38f, false, 20)
            b.pop()
            b.mode = 0
            b.pop()
        }

        // faíscas de luz orbitando
        b.mode = 2
        for (k in 0 until 5) {
            val a = t * 1.7f + k * 1.2566f
            b.sphere(cos(a) * 25f, 44f + sin(t * 2f + k * 2f) * 9f, sin(a) * 25f, 1.8f, 1.8f, 1.8f, glow, 0.7f, false, 5, 3)
        }
        b.mode = 0

        // escudo (W)
        if (c.shield > 0f) {
            b.mode = 2
            b.sphere(0f, 38f, 0f, 31f, 37f, 31f, cc(0xFFFFE8A6), 0.18f, false, 16, 10)
            b.sphere(0f, 38f, 0f, 34f, 40f, 34f, cc(0xFFFFFFFF), 0.07f, false, 16, 10)
            b.ring(0f, 14f, 0f, 30f, 34f, cc(0xFFFFF3C4), 0.5f, false, 28)
            for (bd in 0 until 4) {
                val dy = -24f + bd * 16f
                val rb = 31f * sqrt(1f - (dy / 37f) * (dy / 37f))
                b.cyl(0f, 38f + dy, 0f, rb, rb, 1.2f, cc(0xFFFFF3C4), 20, 0.4f, false)
            }
            for (k in 0 until 6) {
                val a = -t * 2.4f + k * 1.047f
                b.sphere(cos(a) * 33f, 38f + sin(t * 3f + k) * 12f, sin(a) * 33f, 2.2f, 2.2f, 2.2f, white, 0.8f, false, 5, 3)
            }
            b.mode = 0
        }
        b.pop()
        // marca da passiva
        if (c.markT > 0f) {
            b.push()
            b.tr(0f, 100f, 0f)
            b.rotY(t * 120f)
            b.cyl(0f, 0f, 0f, 0f, 5f, 6f, cc(0xFFFFF59D), 4, 1f, false)
            b.cyl(0f, -6f, 0f, 0f, 5f, 6f, cc(0xFFFFF59D), 4, 1f, false)
            b.pop()
        }
        b.dq = 1f
    }

    // ---------------------------------------------------------- MINION

    private fun minion(m: Minion) {
        b.dq = 0.7f
        val ally = m.team == 0
        val tc = if (ally) cc(0xFF3F7FD6) else cc(0xFFD64F3F)
        val tl = if (ally) cc(0xFF7FB2F2) else cc(0xFFF28F7F)
        val dk = if (ally) cc(0xFF2B4F8F) else cc(0xFF8F2F2B)
        val gold = cc(0xFFFFD54F)
        val steel = cc(0xFFB7C3CC)
        val steelD = cc(0xFF78899A)
        val leather = cc(0xFF5D4037)
        val skin = cc(0xFF8BC34A)
        val skinD = cc(0xFF6FA83A)
        val glowC = if (ally) cc(0xFF80D8FF) else cc(0xFFFF8A80)
        val amp = (m.mv / 0.1f).coerceIn(0f, 1f)
        val ph = m.walk * 1.2f
        val sw = sin(ph) * amp
        val period = if (m.caster) 1.3f else 1.0f
        val ak = ((m.atkCd - (period - 0.3f)) / 0.3f).coerceIn(0f, 1f)

        // pernas curtas e botas grandes
        if (!m.caster) {
            for (s in intArrayOf(-1, 1)) {
                b.push()
                b.tr(0f, 12f, s * 3.9f)
                b.rotZ(s * sw * 36f)
                b.cyl(0f, -9f, 0f, 3.5f, 3.1f, 9f, K(dk), 10)
                b.sphere(1.8f, -10.6f, 0f, 5.4f, 3.0f, 3.8f, K(leather), 1f, true, 10, 6)
                b.sphere(5.6f, -10.8f, 0f, 2.2f, 1.8f, 2.4f, K(steelD), 1f, true, 7, 4)
                b.pop()
            }
        } else {
            for (s in intArrayOf(-1, 1)) {
                b.push()
                b.tr(0f, 6f, s * 3.5f)
                b.rotZ(s * sw * 30f)
                b.sphere(2.6f, -4.4f, 0f, 5.0f, 2.2f, 3.4f, K(leather), 1f, true, 9, 5)
                b.pop()
            }
        }
        b.push()
        b.tr(0f, abs(sw) * 1.3f, 0f)

        if (!m.caster) {
            // túnica, cinto, saiote e tabardo
            b.cyl(0f, 10f, 0f, 9.2f, 6.6f, 15f, K(tc), 14)
            b.cyl(0f, 11f, 0f, 9.4f, 9.1f, 2.8f, K(leather), 14)
            b.sphere(8.4f, 12.4f, 0f, 1.2f, 2.1f, 2.4f, K(gold), 1f, true, 7, 4)
            for (k in 0 until 7) {
                b.push()
                b.rotY(k * 51.4f)
                b.tr(8.2f, 11.5f, 0f)
                b.rotZ(16f)
                b.sphere(0.4f, -3.4f, 0f, 1.0f, 4.6f, 2.8f, K(if (k % 2 == 0) tc else dk), 1f, true, 6, 4)
                b.pop()
            }
            b.sphere(7.2f, 19f, 0f, 1.0f, 6.4f, 4.4f, K(tl), 1f, true, 7, 5)
            // peitoral de aço com emblema
            b.sphere(2.4f, 21.2f, 0f, 7.4f, 6.6f, 8.2f, K(steel), 1f, true, 12, 8)
            b.sphere(8.8f, 21.4f, 0f, 1.2f, 2.8f, 2.8f, K(gold), 1f, true, 8, 5)
        } else {
            // manto longo com barra dourada e capuz nas costas
            b.cyl(0f, 1.5f, 0f, 11.4f, 6.6f, 26f, K(tc), 16)
            b.cyl(0f, 1.2f, 0f, 11.7f, 11.4f, 2.2f, K(gold), 16)
            b.cyl(0f, 6f, 0f, 11.0f, 10.8f, 1.2f, K(dk), 16)
            b.cyl(0f, 18f, 0f, 8.2f, 7.4f, 2.4f, K(leather), 14)
            b.sphere(7.6f, 19.2f, 0f, 1.3f, 2.3f, 2.3f, K(gold), 1f, true, 7, 4)
            b.sphere(-4f, 28f, 0f, 6.8f, 5.2f, 9.0f, K(dk), 1f, true, 10, 6)
            b.sphere(1.6f, 22.4f, 0f, 6.6f, 4.6f, 7.4f, K(tl), 1f, true, 10, 6)
        }

        // cabeça grande e expressiva
        b.sphere(0f, 31.4f, 0f, 9.4f, 8.8f, 8.6f, K(skin), 1f, true, 14, 10)
        b.sphere(2.4f, 27.4f, 0f, 6.4f, 4.4f, 6.0f, K(skin), 1f, true, 10, 6)
        for (s in intArrayOf(-1, 1)) {
            // orelhas pontudas
            b.push()
            b.tr(0f, 32.4f, s * 8.6f)
            b.rotX(s * -30f)
            b.sphere(-1f, 0f, 3.2f * s, 1.5f, 2.2f, 3.8f, K(skinD), 1f, true, 6, 4)
            b.pop()
            // olhos grandes
            b.sphere(7.9f, 31.2f, s * 3.3f, 1.7f, 2.4f, 2.1f, white, 1f, false, 8, 6)
            b.sphere(9.2f, 31.0f, s * 3.2f, 0.8f, 1.5f, 1.1f, if (m.caster) glowC else cc(0xFF101010), 1f, false, 6, 4)
            b.sphere(9.7f, 31.8f, s * 2.8f, 0.3f, 0.5f, 0.5f, white, 1f, false, 4, 3)
            // sobrancelhas franzidas
            b.push()
            b.tr(8.0f, 34.2f, s * 3.2f)
            b.rotX(s * 18f)
            b.sphere(0f, 0f, 0f, 0.9f, 0.7f, 3.0f, K(cc(0xFF33691E)), 1f, true, 6, 3)
            b.pop()
            // ombreiras redondas
            b.sphere(0f, 24.4f, s * 9.4f, 4.6f, 3.2f, 4.2f, K(if (m.caster) dk else steel), 1f, true, 10, 6)
        }
        b.sphere(9.2f, 28.4f, 0f, 1.5f, 1.3f, 1.4f, K(skinD), 1f, true, 6, 4)
        b.sphere(8.6f, 25.6f, 0f, 0.7f, 0.9f, 3.2f, K(cc(0xFF3E2723)), 1f, true, 6, 3)

        if (m.caster) {
            // chapéu pontudo com aba, faixa e ponta torta
            b.cyl(0f, 36.2f, 0f, 14.4f, 13.4f, 1.6f, K(dk), 18)
            b.cyl(0f, 37.6f, 0f, 9.4f, 6.4f, 15f, K(dk), 14)
            b.cyl(0f, 37.8f, 0f, 9.5f, 9.4f, 3f, K(tc), 14)
            b.sphere(8.6f, 39.2f, 0f, 1.1f, 1.9f, 1.9f, K(gold), 1f, true, 7, 4)
            b.push()
            b.tr(0f, 52.4f, 0f)
            b.rotZ(-32f)
            b.cyl(0f, 0f, 0f, 6.4f, 2.4f, 9f, K(dk), 12)
            b.push()
            b.tr(0f, 9f, 0f)
            b.rotZ(-34f)
            b.cyl(0f, 0f, 0f, 2.4f, 0f, 7f, K(dk), 10)
            b.sphere(0f, 7.6f, 0f, 1.6f, 1.6f, 1.6f, if (fl) white else glowC, 1f, false, 6, 4)
            b.pop()
            b.pop()
        } else {
            // elmo de aço com nasal e crista de cerdas da cor do time
            b.sphere(0f, 37.2f, 0f, 9.8f, 5.8f, 9.2f, K(steel), 1f, true, 14, 8)
            b.sphere(-1f, 36.6f, 0f, 9.6f, 3.4f, 9.4f, K(steelD), 1f, true, 12, 5)
            b.sphere(8.8f, 33.6f, 0f, 0.9f, 4.4f, 1.1f, K(steelD), 1f, true, 6, 4)
            b.push()
            b.tr(-1f, 42f, 0f)
            b.rotZ(-140f)
            strand(6, 3.6f, 1.2f, 5.6f, tc, dk, 2.5f, 13f)
            b.pop()
        }

        // braço direito (arma)
        val a1 = 12f + ak * 100f - sw * 20f
        b.push()
        b.tr(0f, 24f, 9.0f)
        b.rotZ(a1)
        b.sphere(0f, 0f, 0f, 3.6f, 3.4f, 3.4f, K(skin), 1f, true, 8, 5)
        b.cyl(0f, -11f, 0f, 2.2f, 2.5f, 11f, K(skin), 10)
        b.tr(0f, -11f, 0f)
        b.sphere(0f, 0f, 0f, 3.0f, 3.0f, 3.0f, K(skin), 1f, true, 8, 5)
        if (m.caster) {
            b.rotZ(-a1 - 8f - ak * 40f)
            b.cyl(0f, -12f, 0f, 1.3f, 1.3f, 40f, K(wood), 10)
            // orbe preso por garras em arco
            for (j in 0..6) {
                val ang = Math.toRadians((-70.0 + j * 23.0)).toFloat()
                val rr = 1.5f - abs(j - 3) * 0.12f
                b.sphere(0f, 29.4f + 5f * (1f - cos(ang)), 5.4f * sin(ang), rr, rr, rr, K(gold), 1f, true, 6, 4)
            }
            b.sphere(0f, 32.4f, 0f, 3.8f, 3.8f, 3.8f, if (fl) white else glowC, 1f, false, 10, 7)
            b.mode = 2
            b.sphere(0f, 32.4f, 0f, 7.4f + 1.2f * sin(t * 5f), 7.4f + 1.2f * sin(t * 5f), 7.4f + 1.2f * sin(t * 5f), glowC, 0.26f, false, 8, 6)
            b.mode = 0
        } else {
            b.rotZ(-70f - a1 * 0.2f)
            // espada curva: lâmina em elipsoides, guarda dourada e cabo
            b.cyl(0f, -4f, 0f, 1.0f, 1.0f, 6f, K(leather), 8)
            b.sphere(0f, 0f, 0f, 1.7f, 1.7f, 1.7f, K(gold), 1f, true, 6, 4)
            b.sphere(0f, 0.4f, 0f, 1.6f, 1.6f, 6.2f, K(gold), 1f, true, 6, 4)
            b.sphere(0f, 8f, 0f, 1.0f, 8.2f, 2.8f, K(steel), 1f, true, 6, 6)
            b.sphere(0.5f, 17f, 0f, 0.9f, 5.0f, 2.3f, K(steel), 1f, true, 6, 5)
            b.sphere(1.2f, 22.4f, 0f, 0.7f, 3.4f, 1.4f, K(steel), 1f, true, 6, 4)
        }
        b.pop()

        // braço esquerdo (escudo redondo com emblema)
        b.push()
        b.tr(0f, 24f, -9.0f)
        b.rotZ(-sw * 22f + 6f)
        b.sphere(0f, 0f, 0f, 3.6f, 3.4f, 3.4f, K(skin), 1f, true, 8, 5)
        b.cyl(0f, -11f, 0f, 2.2f, 2.5f, 11f, K(skin), 10)
        b.sphere(0f, -11f, 0f, 3.0f, 3.0f, 3.0f, K(skin), 1f, true, 8, 5)
        if (!m.caster) {
            b.push()
            b.tr(3.6f, -7.5f, -2.4f)
            b.rotZ(90f)
            b.cyl(0f, -1.2f, 0f, 9.6f, 9.6f, 2.4f, K(steel), 18)
            b.cyl(0f, 1.2f, 0f, 8.0f, 8.0f, 0.8f, K(tc), 18)
            b.cyl(0f, 1.9f, 0f, 4.8f, 4.8f, 0.6f, K(tl), 14)
            b.sphere(0f, 2.6f, 0f, 2.6f, 1.6f, 2.6f, K(gold), 1f, true, 8, 5)
            b.pop()
        }
        b.pop()
        b.pop()
        b.dq = 1f
    }

    // ----------------------------------------------------------- TORRES

    private fun tower(tw: Tower) {
        b.dq = 0.8f
        val s = if (tw.nexus) 1.7f else 1f
        val team = if (tw.team == 0) cc(0xFF4FC3F7) else cc(0xFFFF5252)
        val teamD = if (tw.team == 0) cc(0xFF2A74A8) else cc(0xFFA83A3A)
        val teamL = if (tw.team == 0) cc(0xFFBDEBFF) else cc(0xFFFFC4BD)
        fl = tw.flash > 0f
        val gold = cc(0xFFFFD34E)
        val goldD = cc(0xFFC9972B)
        val bob = sin(t * 2.2f + tw.x) * 4f
        val pulse = 1f + 0.12f * sin(t * 3f + tw.x)
        b.push()
        b.tr(tw.x, groundH(tw.x, tw.y), tw.y)
        b.sc(s, s, s)

        // rocha rasgada ao redor da base
        for (i in 0 until 9) {
            b.push()
            b.rotY(i * 40f + hash2(tw.x.toInt(), i) * 20f)
            b.tr(46f, 0f, 0f)
            b.rotZ(-24f)
            b.cyl(0f, -4f, 0f, 10f, 0f, 30f + hash2(i, 7) * 18f, K(if (i % 2 == 0) stoneD else stone), 6)
            b.pop()
        }
        // plinto em degraus
        b.cyl(0f, 0f, 0f, 54f, 50f, 8f, K(stoneD), 20)
        b.cyl(0f, 8f, 0f, 46f, 41f, 8f, K(stone), 20)
        b.cyl(0f, 16f, 0f, 38f, 34f, 3f, K(goldD), 20)
        b.mode = 2
        b.ring(0f, 19.4f, 0f, 30f, 36f, team, 0.45f, false, 28)
        b.mode = 0

        // fuste afilado em três segmentos com faixas douradas
        b.cyl(0f, 18f, 0f, 32f, 26f, 34f, K(stone), 16)
        b.cyl(0f, 51f, 0f, 27.2f, 27f, 3.4f, K(gold), 16)
        b.cyl(0f, 54f, 0f, 26f, 21f, 36f, K(stoneL), 16)
        b.cyl(0f, 89f, 0f, 22.2f, 22f, 3f, K(gold), 16)
        b.cyl(0f, 92f, 0f, 21f, 17f, 26f, K(stone), 16)
        // runas luminosas nos quatro lados
        for (i in 0 until 4) {
            b.push()
            b.rotY(i * 90f)
            b.box(29f, 35f, 0f, 1.6f, 17f, 3.6f, team, 1f, false)
            b.box(24f, 70f, 0f, 1.6f, 20f, 3.4f, team, 1f, false)
            b.box(19.6f, 104f, 0f, 1.6f, 13f, 3.0f, team, 1f, false)
            b.box(28.6f, 35f, 0f, 1.0f, 3f, 9f, teamL, 1f, false)
            b.pop()
        }
        // taça dourada no topo
        b.cyl(0f, 118f, 0f, 22f, 19f, 5f, K(stoneD), 16)
        b.cyl(0f, 123f, 0f, 13f, 19f, 8f, K(gold), 14)
        b.cyl(0f, 131f, 0f, 19.5f, 19.5f, 1.6f, K(goldD), 14)

        // quatro garras curvas envolvendo o núcleo
        for (i in 0 until 4) {
            b.push()
            b.rotY(i * 90f + 45f)
            b.tr(17f, 130f, 0f)
            b.rotZ(174f)
            strand(5, 4.6f, 1.6f, 12f, gold, goldD, 0f, 6.5f)
            b.pop()
        }
        // flâmulas penduradas
        for (i in 0 until 3) {
            b.push()
            b.rotY(i * 120f + 30f)
            b.tr(20.6f, 117f, 0f)
            b.rotZ(sin(t * 1.6f + i + tw.x) * 5f)
            b.sphere(0.4f, -16f, 0f, 0.9f, 17f, 6.8f, K(teamD), 1f, true, 6, 6)
            b.sphere(0.8f, -9f, 0f, 0.9f, 3.0f, 3.2f, K(gold), 1f, true, 6, 4)
            b.sphere(0.4f, -32f, 0f, 1.0f, 3f, 3.4f, K(gold), 1f, true, 6, 4)
            b.pop()
        }

        // núcleo de energia: orbe, anéis giratórios, estilhaços e feixe
        val oy = 152f + bob
        b.push()
        b.tr(0f, oy, 0f)
        b.rotY(t * 55f)
        b.cyl(0f, -22f, 0f, 0f, 12f, 22f, if (fl) white else team, 6, 1f, false)
        b.cyl(0f, 0f, 0f, 12f, 0f, 22f, if (fl) white else teamL, 6, 1f, false)
        b.pop()
        b.mode = 2
        b.sphere(0f, oy, 0f, 30f * pulse, 30f * pulse, 30f * pulse, team, 0.18f, false, 10, 7)
        b.sphere(0f, oy, 0f, 15f * pulse, 15f * pulse, 15f * pulse, teamL, 0.30f, false, 10, 7)
        b.cyl(0f, oy + 24f, 0f, 6f, 0f, 70f, team, 8, 0.20f, false)
        b.push()
        b.tr(0f, oy, 0f)
        b.rotY(t * 70f)
        b.rotX(72f)
        b.ring(0f, 0f, 0f, 27f, 29f, teamL, 0.6f, false, 28)
        b.pop()
        b.push()
        b.tr(0f, oy, 0f)
        b.rotY(-t * 50f)
        b.rotZ(58f)
        b.ring(0f, 0f, 0f, 35f, 36.6f, team, 0.45f, false, 28)
        b.pop()
        b.mode = 0
        for (i in 0 until 6) {
            val a = t * 1.1f + i * 1.0472f
            b.push()
            b.tr(cos(a) * 44f, oy - 8f + sin(t * 2f + i * 1.7f) * 8f, sin(a) * 44f)
            b.rotY(-a * 57.3f)
            b.cyl(0f, -6f, 0f, 0f, 2.8f, 6f, if (fl) white else team, 4, 1f, false)
            b.cyl(0f, 0f, 0f, 2.8f, 0f, 6f, if (fl) white else team, 4, 1f, false)
            b.pop()
        }

        // tochas ao pé da torre
        for (sd in intArrayOf(-1, 1)) {
            val tx = sd * 48f
            b.cyl(tx, 2f, 0f, 2.8f, 2.3f, 24f, K(wood), 8)
            b.cyl(tx, 24f, 0f, 3.6f, 5.2f, 4f, K(goldD), 8)
            val fh = 8f + 3f * sin(t * 14f + tx)
            b.mode = 2
            b.cyl(tx, 28f, 0f, 5f, 0f, fh * 1.8f, cc(0xFFFFA726), 6, 0.85f, false)
            b.cyl(tx, 28f, 0f, 2.6f, 0f, fh * 1.2f, cc(0xFFFFEE58), 6, 0.9f, false)
            b.mode = 0
        }
        if (tw.nexus) {
            // nexus: estilhaços grandes orbitando e pulso no chão
            for (i in 0 until 4) {
                val a = t * 0.8f + i * 1.5708f
                b.sphere(cos(a) * 66f, 112f + sin(t * 2f + i) * 8f, sin(a) * 66f, 6f, 10f, 6f, if (fl) white else team, 1f, false, 7, 5)
            }
            b.mode = 2
            val k = (t * 0.5f) % 1f
            b.ring(0f, 14f, 0f, 55f + 30f * k, 60f + 32f * k, team, 0.5f * (1f - k), false, 36)
            b.ring(0f, 14f, 0f, 55f, 62f, team, 0.4f, false, 28)
            b.mode = 0
        }
        b.pop()
        fl = false
        b.dq = 1f
    }

    private fun rubble(tw: Tower) {
        val s = if (tw.nexus) 1.8f else 1f
        for (i in 0 until 7) {
            val a = hash2(tw.x.toInt(), i) * 6.28f
            val d = hash2(i, tw.x.toInt()) * 34f * s
            val px = tw.x + cos(a) * d
            val pz = tw.y + sin(a) * d
            b.push()
            b.tr(px, groundH(px, pz) + 4f * s, pz)
            b.rotY(a * 57f)
            b.rotZ(hash2(i, 9) * 50f - 25f)
            b.box(0f, 0f, 0f, (14f + hash2(i, 3) * 14f) * s, 9f * s, (12f + hash2(i, 4) * 12f) * s, if (i % 2 == 0) stone else stoneD)
            b.pop()
        }
    }

    // --------------------------------------------------- PROJÉTEIS E MAGIAS

    private fun proj(q: Proj) {
        val gy = groundH(q.x, q.y)
        val ally = q.team == 0
        when (q.kind) {
            1 -> {
                val deg = Math.toDegrees(atan2(q.vy, q.vx).toDouble()).toFloat()
                b.push()
                b.tr(q.x, gy + 46f, q.y)
                b.rotY(-deg)
                b.mode = 2
                // cauda de cometa afunilando
                for (k in 0 until 9) {
                    val kk = k / 9f
                    val rr = 17f * (1f - kk * 0.8f)
                    b.sphere(-k * 15f, 0f, 0f, rr * 1.7f, rr, rr, cc(0xFFFFE680), 0.34f * (1f - kk), false, 8, 5)
                }
                // duas hélices de luz
                for (k in 0 until 14) {
                    val a = t * 16f + k * 0.55f
                    val amp = 16f * (1f - k / 18f)
                    b.sphere(-k * 9f, sin(a) * amp, cos(a) * amp, 3.2f, 3.2f, 3.2f, cc(0xFFFFF59D), 0.9f, false, 5, 3)
                    b.sphere(-k * 9f, -sin(a) * amp, -cos(a) * amp, 2.4f, 2.4f, 2.4f, white, 0.8f, false, 5, 3)
                }
                b.sphere(0f, 0f, 0f, 28f, 28f, 28f, cc(0xFFFFE680), 0.26f, false, 10, 7)
                b.sphere(4f, 0f, 0f, 16f, 16f, 16f, white, 0.65f, false, 10, 7)
                // estrela de luz girando
                b.push()
                b.rotX(t * 140f)

                b.box(0f, 0f, 0f, 1.5f, 66f, 2f, white, 0.7f, false)
                b.box(0f, 0f, 0f, 1.5f, 2f, 66f, white, 0.7f, false)
                b.rotX(45f)
                b.box(0f, 0f, 0f, 1.5f, 44f, 2f, white, 0.5f, false)
                b.box(0f, 0f, 0f, 1.5f, 2f, 44f, white, 0.5f, false)
                b.pop()
                b.mode = 0
                b.sphere(0f, 0f, 0f, 9f, 9f, 9f, white, 1f, false, 8, 6)
                b.pop()
            }
            0 -> {
                b.mode = 2
                b.sphere(q.x, gy + 60f, q.y, 20f, 20f, 20f, cc(0xFFFFF59D), 0.35f, false, 6, 4)
                b.sphere(q.x, gy + 60f, q.y, 11f, 11f, 11f, cc(0xFFFFFFFF), 0.3f, false, 6, 4)
                b.mode = 0
                b.sphere(q.x, gy + 60f, q.y, 7f, 7f, 7f, white, 1f, false, 6, 4)
            }
            2 -> {
                val col = if (ally) cc(0xFF4FC3F7) else cc(0xFFFF5252)
                b.mode = 2
                b.sphere(q.x, gy + 70f, q.y, 18f, 18f, 18f, col, 0.35f, false, 6, 4)
                b.mode = 0
                b.sphere(q.x, gy + 70f, q.y, 7f, 7f, 7f, col, 1f, false, 6, 4)
                b.sphere(q.x, gy + 70f, q.y, 3.5f, 3.5f, 3.5f, white, 1f, false, 5, 3)
            }
            else -> {
                val col = if (ally) cc(0xFF81D4FA) else cc(0xFFFF8A80)
                b.mode = 2
                b.sphere(q.x, gy + 32f, q.y, 9f, 9f, 9f, col, 0.4f, false, 6, 4)
                b.mode = 0
                b.sphere(q.x, gy + 32f, q.y, 4f, 4f, 4f, col, 1f, false, 6, 4)
            }
        }
    }

    private fun zone(z: Zone) {
        val gy = groundH(z.x, z.y) + 1.4f
        val pulse = 1f + 0.03f * sin(t * 8f)
        val rr = z.r * pulse
        val gold = cc(0xFFFFF0A0)
        b.mode = 2
        // piso em gradiente de luz
        b.disc(z.x, gy, z.y, rr, gold, 0.10f, false, 36)
        b.disc(z.x, gy + 0.05f, z.y, rr * 0.8f, gold, 0.10f, false, 32)
        b.disc(z.x, gy + 0.1f, z.y, rr * 0.55f, gold, 0.13f, false, 28)
        // borda forte e anéis
        b.ring(z.x, gy + 0.2f, z.y, rr - 7f, rr, white, 0.9f, false, 44)
        b.ring(z.x, gy + 0.25f, z.y, rr * 0.72f, rr * 0.72f + 3f, gold, 0.6f, false, 40)
        val k = (z.life / 4f).coerceIn(0f, 1f)
        b.ring(z.x, gy + 0.3f, z.y, max(0f, rr * k - 3f), rr * k, white, 0.8f, false, 36)
        // runas girando na borda
        for (i in 0 until 16) {
            b.push()
            b.tr(z.x, gy + 0.3f, z.y)
            b.rotY(t * 45f + i * 22.5f)
            b.box(rr - 16f, 0f, 0f, 14f, 0.4f, 3.2f, gold, 0.85f, false)
            b.pop()
        }
        // estrela de 8 pontas
        b.push()
        b.tr(z.x, gy + 0.5f, z.y)
        b.rotY(-t * 30f)
        for (kk in 0 until 4) {
            b.push()
            b.rotY(kk * 45f)
            b.box(0f, 0f, 0f, rr * 1.55f, 0.4f, 3.6f, gold, 0.5f, false)

            b.pop()
        }
        b.pop()
        // faíscas subindo
        for (i in 0 until 12) {
            val a = -t * 1.1f + i * 0.5236f
            val rd = rr * (0.3f + 0.55f * ((i * 0.37f) % 1f))
            val hh = (t * 45f + i * 37f) % 100f
            b.sphere(z.x + cos(a) * rd, gy + hh, z.y + sin(a) * rd, 2.8f, 2.8f, 2.8f, white, 0.9f * (1f - hh / 100f), false, 5, 3)
        }
        // coluna e orbe de luz central
        b.cyl(z.x, gy, z.y, 18f, 8f, 160f, gold, 10, 0.15f, false)
        val oy = gy + 28f + sin(t * 3f) * 4f
        b.sphere(z.x, oy, z.y, 36f * pulse, 36f * pulse, 36f * pulse, gold, 0.22f, false, 12, 8)
        b.sphere(z.x, oy, z.y, 21f, 21f, 21f, white, 0.5f, false, 10, 7)
        b.sphere(z.x, oy, z.y, 9f, 9f, 9f, white, 1f, false, 8, 6)
        b.mode = 0
    }

    private fun beam(bm: Beam) {
        val deg = Math.toDegrees(bm.ang.toDouble()).toFloat()
        val gold = cc(0xFFFFF0A0)
        val hot = cc(0xFFFFE066)
        b.push()
        b.tr(bm.x, groundH(bm.x, bm.y) + 40f, bm.y)
        b.rotY(-deg)
        b.mode = 2
        if (!bm.fired) {
            val k = 1f - (bm.delay / 0.6f).coerceIn(0f, 1f)
            b.box(850f, -38f, 0f, 1700f, 0.5f, 120f, gold, 0.14f, false)
            b.box(850f, -38f, 0f, 1700f, 0.6f, 120f * k, gold, 0.38f, false)
            b.box(850f, -38f, 0f, 1700f, 0.7f, 4f, white, 0.85f, false)

            b.sphere(22f, 0f, 0f, 22f * k, 22f * k, 22f * k, gold, 0.55f, false, 10, 7)
            b.sphere(22f, 0f, 0f, 10f * k, 10f * k, 10f * k, white, 0.95f, false, 8, 6)
            for (i in 0 until 3) {
                val kk = ((1f - k) + i * 0.33f) % 1f
                b.push()
                b.tr(34f, 0f, 0f)
                b.rotZ(90f)
                b.ring(0f, 0f, 0f, 64f * kk, 64f * kk + 4f, gold, 0.75f * (1f - kk), false, 22)
                b.pop()
            }
            // sigilo no chão da conjuradora
            val sr = 78f * (0.4f + 0.6f * k)
            b.ring(0f, -38.6f, 0f, sr, sr + 3.5f, white, 0.8f * k, false, 36)
            for (i in 0 until 8) {
                b.push()
                b.rotY(i * 45f + t * 120f)
                b.box(sr * 0.5f, -38.4f, 0f, sr * 0.9f, 0.4f, 2f, gold, 0.6f * k, false)
                b.pop()
            }
        } else {
            val k = (bm.life / 0.45f).coerceIn(0f, 1f)
            val fl2 = 0.94f + 0.06f * sin(t * 60f)
            b.box(850f, 0f, 0f, 1700f, 140f * k * fl2, 200f * k, gold, 0.14f, false)
            b.box(850f, 0f, 0f, 1700f, 100f * k, 146f * k, hot, 0.24f, false)
            b.box(850f, 0f, 0f, 1700f, 64f * k, 94f * k, cc(0xFFFFFDE7), 0.55f, false)
            b.box(850f, 0f, 0f, 1700f, 28f * k, 46f * k, white, 1f, false)
            b.box(850f, -39f, 0f, 1700f, 0.5f, 270f * k, gold, 0.32f, false)
            // origem
            b.sphere(0f, 0f, 0f, 78f * k, 78f * k, 78f * k, gold, 0.4f, false, 12, 8)
            b.sphere(0f, 0f, 0f, 44f * k, 44f * k, 44f * k, white, 0.6f, false, 10, 7)
            b.box(0f, 0f, 0f, 3f, 320f * k, 3f, white, 0.8f, false)
            b.box(0f, 0f, 0f, 3f, 3f, 320f * k, white, 0.8f, false)

            // fim do laser
            b.sphere(1700f, 0f, 0f, 40f * k, 40f * k, 40f * k, gold, 0.4f, false, 10, 7)
            // anéis de energia correndo
            for (i in 0 until 12) {
                val px = ((i * 150f + t * 2600f) % 1700f)
                b.push()
                b.tr(px, 0f, 0f)
                b.rotZ(90f)
                b.ring(0f, 0f, 0f, 46f * k, 52f * k, gold, 0.5f, false, 14)
                b.pop()
            }
            // faíscas
            for (i in 0 until 16) {
                val px = i * 105f + 30f
                val off = sin(t * 18f + i * 2.1f) * 85f * k
                b.sphere(px, cos(t * 15f + i) * 26f, off, 4.5f, 4.5f, 4.5f, white, 0.9f, false, 5, 3)
            }
        }
        b.mode = 0
        b.pop()
    }

    /** Indicadores de mira: preenchimento translúcido em degradê, bordas finas e setas animadas. */
    private fun aim() {
        val pl = g.pl
        if (pl.dead) return
        val fill = cc(0xFFFFB938)
        val edge = cc(0xFFC97A00)
        val tip = cc(0xFFFFF0C2)
        for (i in 0..3) {
            if (g.bId[i] == -1 || !g.bDrag[i]) continue
            val ang = atan2(g.bAy[i], g.bAx[i])
            val gy = groundH(pl.x, pl.y) + 1.8f
            b.push()
            b.tr(pl.x, gy, pl.y)
            b.mode = 0
            if (i == 1) {
                val pr = 1f + 0.05f * sin(t * 7f)
                b.disc(0f, 0f, 0f, 58f * pr, fill, 0.16f, false, 36)
                b.ring(0f, 0.1f, 0f, 55f * pr, 59f * pr, edge, 0.7f, false, 40)
                b.pop()
                continue
            }
            b.rotY(-Math.toDegrees(ang.toDouble()).toFloat())
            val len = when (i) {
                0 -> 950f
                2 -> (sqrt(g.bAx[i] * g.bAx[i] + g.bAy[i] * g.bAy[i]) / g.d * 3.2f).coerceIn(150f, 780f)
                else -> 1700f
            }
            if (i == 2) {
                // alcance máximo ao redor da conjuradora
                b.ring(0f, 0f, 0f, 776f, 781f, edge, 0.22f, false, 64)
                // linha pontilhada até o alvo
                val dn = (len / 40f).toInt()
                for (j in 1 until dn) {
                    b.box(j * 40f, 0.1f, 0f, 9f, 0.3f, 2.4f, edge, 0.55f, false)
                }
                // círculo da área
                b.disc(len, 0f, 0f, 190f, fill, 0.17f, false, 40)
                b.disc(len, 0.05f, 0f, 120f, fill, 0.10f, false, 32)
                b.ring(len, 0.2f, 0f, 184f, 190f, edge, 0.75f, false, 48)
                b.ring(len, 0.2f, 0f, 112f, 114.5f, edge, 0.35f, false, 40)
                for (j in 0 until 12) {
                    b.push()
                    b.tr(len, 0.25f, 0f)
                    b.rotY(j * 30f + t * 25f)
                    b.box(168f, 0f, 0f, 22f, 0.3f, 3f, tip, 0.6f, false)
                    b.pop()
                }
            } else {
                val wd = if (i == 0) 52f else 110f
                val n = 8
                val sl = len / n
                for (k in 0 until n) {
                    val al = 0.32f * (1f - 0.78f * k / n)
                    b.box(sl * (k + 0.5f), 0f, 0f, sl + 0.5f, 0.3f, wd, fill, al, false)
                }
                b.box(len / 2f, 0.2f, wd / 2f, len, 0.3f, 1.6f, edge, 0.7f, false)
                b.box(len / 2f, 0.2f, -wd / 2f, len, 0.3f, 1.6f, edge, 0.7f, false)
                b.box(len, 0.2f, 0f, 1.8f, 0.3f, wd + 1.6f, edge, 0.7f, false)
                // setas correndo na direção do disparo
                val cn = (len / 130f).toInt().coerceAtLeast(3)
                val spd = if (i == 3) 520f else 300f
                for (j in 0 until cn) {
                    val px = (t * spd + j * len / cn) % len
                    val fa = 0.1f + 0.6f * (1f - px / len)
                    b.push()
                    b.tr(px, 0.4f, 0f)
                    for (s in intArrayOf(-1, 1)) {
                        b.push()
                        b.rotY(s * 40f)
                        b.box(-wd * 0.15f, 0f, 0f, wd * 0.36f, 0.3f, 2.4f, tip, fa, false)
                        b.pop()
                    }
                    b.pop()
                }
                // ponta de seta no fim da linha
                for (s in intArrayOf(-1, 1)) {
                    b.push()
                    b.tr(len, 0.4f, 0f)
                    b.rotY(s * 40f)
                    b.box(-wd * 0.2f, 0f, 0f, wd * 0.46f, 0.3f, 3f, edge, 0.8f, false)
                    b.pop()
                }
            }
            b.pop()
        }
    }
}
