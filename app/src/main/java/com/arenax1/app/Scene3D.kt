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

const val MAP_W = 4200f

/** Altura do chão no ponto (x, z). z do mundo = y do jogo. */
fun groundH(x: Float, z: Float): Float {
    val d = if (z < 300f) 300f - z else if (z > 980f) z - 980f else 0f
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
        val onBridge = z > 500f && z < 780f
        h = if (onBridge) 0.8f else h - dip * 9f
    }
    return h
}

private fun rgbI(r: Float, g: Float, b: Float): Int =
    (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

private fun terrainColor(x: Float, z: Float): Int {
    val n = vnoise(x * 0.05f, z * 0.05f)
    val n2 = vnoise(x * 0.012f, z * 0.012f)
    val d = if (z < 300f) 300f - z else if (z > 980f) z - 980f else 0f
    var c: Int
    if (d > 0f) {
        val rock = rgbI(120f + n * 34f, 114f + n * 30f, 108f + n * 26f)
        val jungle = rgbI(36f + n * 22f + n2 * 18f, 84f + n * 30f + n2 * 20f, 34f + n * 14f)
        c = mixCol(rock, jungle, (d - 28f) / 90f)
    } else if (z in 506f..774f) {
        val tx = floor(x / 64f).toInt()
        val tz = floor(z / 64f).toInt()
        val chk = ((tx + tz) and 1) * 9f
        c = rgbI(158f + n * 18f + chk, 136f + n * 16f + chk, 98f + n * 12f + chk)
        if (z < 522f || z > 758f) c = shadeCol(c, 0.72f)
    } else {
        c = rgbI(88f + n * 12f - n2 * 10f, 150f + n * 14f - n2 * 14f, 62f + n * 8f)
    }
    val dx = abs(x - MAP_W / 2f)
    if (dx < 190f) {
        val k = 1f - dx / 190f
        c = mixCol(c, rgbI(206f, 190f, 140f), min(1f, k * 2.2f))
        c = mixCol(c, rgbI(52f, 112f, 150f), ((k - 0.42f) * 1.8f).coerceIn(0f, 1f))
    }
    // leve tinta de cada time
    c = if (x < MAP_W / 2f) mixCol(c, rgbI(90f, 140f, 255f), 0.06f) else mixCol(c, rgbI(255f, 100f, 90f), 0.06f)
    return c
}

// ----------------------------------------------------------------------- CENA

class Scene(private val g: GameView) {

    private val b = Batch()
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
        val cell = 40f
        val x0 = -400f
        val z0 = -200f
        val nx = 125
        val nz = 42
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
        val perChunk = 10
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
        for (row in 0 until 5) {
            for (side in 0..1) {
                val zb = if (side == 0) 250f - row * 62f else 1010f + row * 62f
                var ix = 0
                while (ix < 64) {
                    val hx = hash2(ix + row * 97, side * 31 + row)
                    val hz = hash2(ix * 7 + 3, row * 13 + side)
                    val hs = hash2(ix + 11, row * 5 + side * 77)
                    seed++
                    if (hx > 0.12f) {
                        val x = -400f + ix * 82f + hx * 50f
                        val z = zb + (hz - 0.5f) * 36f
                        tree(chunkOf(x), x, groundH(x, z), z, 0.8f + hs * 0.7f, hs)
                    }
                    ix++
                }
            }
        }
        // flores e pedras no gramado
        for (i in 0 until 200) {
            val x = 100f + hash2(i, 1) * (MAP_W - 200f)
            val z = if (hash2(i, 2) < 0.5f) 330f + hash2(i, 3) * 160f else 790f + hash2(i, 4) * 150f
            val kind = hash2(i, 5)
            val bt = chunkOf(x)
            val gy = groundH(x, z)
            if (kind < 0.55f) {
                val fc = if (kind < 0.2f) cc(0xFFFFEB3B) else if (kind < 0.4f) cc(0xFFFFFFFF) else cc(0xFFF48FB1)
                bt.cyl(x, gy, z, 0.6f, 0.6f, 5f, cc(0xFF2E7D32), 4)
                bt.sphere(x, gy + 6f, z, 2.4f, 1.6f, 2.4f, fc, 1f, true, 6, 3)
            } else {
                bt.sphere(x, gy + 3f, z, 7f + hash2(i, 6) * 5f, 4.5f, 6f, cc(0xFF8E9099), 1f, true, 6, 4)
            }
        }
        // bases
        for (team in 0..1) {
            val cx = if (team == 0) 300f else MAP_W - 300f
            val bt = chunkOf(cx)
            val tint = if (team == 0) cc(0xFF4A6FA5) else cc(0xFFA5504A)
            val glow = if (team == 0) cc(0xFF4FC3F7) else cc(0xFFFF5252)
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
        }
        // ponte
        run {
            val cx = MAP_W / 2f
            val bt = chunkOf(cx)
            bt.box(cx, -1.8f, 640f, 380f, 5.2f, 280f, cc(0xFF9A8F78))
            var px = cx - 180f
            while (px <= cx + 180f) {
                bt.box(px, 0.9f, 640f, 2f, 0.4f, 268f, cc(0xFF6E6552))
                px += 36f
            }
            for (sd in intArrayOf(-1, 1)) {
                val zz = 640f + sd * 136f
                bt.box(cx, 8f, zz, 380f, 12f, 8f, cc(0xFFB3A98F))
                var qx = cx - 180f
                while (qx <= cx + 180f) {
                    bt.box(qx, 12f, zz, 14f, 22f, 14f, cc(0xFFC8BEA3))
                    bt.sphere(qx, 25f, zz, 5f, 4f, 5f, cc(0xFFFFD54F), 1f, true, 6, 3)
                    qx += 90f
                }
            }
        }
        for (ci in 0 until nChunks) {
            val xa = x0 + ci * perChunk * cell
            r.uploadStatic(chunks[ci], xa - 300f, xa + perChunk * cell + 300f)
        }
    }

    private fun tree(bt: Batch, x: Float, y: Float, z: Float, s: Float, hs: Float) {
        val trunk = cc(0xFF5D4037)
        val gA = mixCol(cc(0xFF1F5E2A), cc(0xFF2E7D32), hs)
        val gB = mixCol(cc(0xFF2E7D32), cc(0xFF4CAF50), hs)
        bt.cyl(x, y - 2f, z, 6f * s, 4f * s, 46f * s, trunk, 5)
        if (hs < 0.55f) {
            bt.cyl(x, y + 30f * s, z, 38f * s, 0f, 62f * s, gA, 7)
            bt.cyl(x, y + 62f * s, z, 28f * s, 0f, 56f * s, gB, 7)
        } else {
            bt.sphere(x, y + 62f * s, z, 34f * s, 30f * s, 34f * s, gA, 1f, true, 7, 4)
            bt.sphere(x + 12f * s, y + 80f * s, z - 6f * s, 22f * s, 20f * s, 22f * s, gB, 1f, true, 7, 4)
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
        b.box(wx, -3.4f, 640f, 330f, 0.4f, 1700f, cc(0xFF4FA3D8), 0.62f, false)
        b.mode = 2
        for (i in 0 until 9) {
            val zz = ((i * 190f + t * 38f) % 1700f) - 200f
            b.box(wx + sin(t + i) * 40f, -3f, zz, 70f + (i % 3) * 30f, 0.3f, 3f, white, 0.35f, false)
        }
        b.mode = 0

        for (tw in g.towers) {
            if (abs(tw.x - cx) > 1700f) continue
            if (tw.dead) rubble(tw) else tower(tw)
        }
        for (m in g.minions) {
            if (m.dead || abs(m.x - cx) > 1500f) continue
            place(m)
            fl = m.flash > 0f
            b.sc(1.35f, 1.35f, 1.35f)
            minion(m)
            b.pop()
            shadow(m.x, m.y, 18f)
        }
        for (c in g.champs) {
            if (c.dead || abs(c.x - cx) > 1500f) continue
            place(c)
            fl = c.flash > 0f
            b.sc(1.45f, 1.45f, 1.45f)
            lux(c)
            b.pop()
            shadow(c.x, c.y, 25f)
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
            if (u is Champ) { b.sc(1.45f, 1.45f, 1.45f); lux(u) } else if (u is Minion) { b.sc(1.35f, 1.35f, 1.35f); minion(u) }
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
        b.disc(x, groundH(x, y) + 0.5f, y, r, cc(0xFF000000), 0.28f, false, 14)
    }

    private fun teamRing(c: Champ) {
        val col = if (c.team == 0) cc(0xFF4FC3F7) else cc(0xFFFF5252)
        val gy = groundH(c.x, c.y) + 0.9f
        b.mode = 2
        b.ring(c.x, gy, c.y, 26f, 30f, col, 0.85f, false, 24)
        if (c.rootT > 0f) b.ring(c.x, gy + 0.2f, c.y, 34f, 39f, cc(0xFFFFF176), 0.95f, false, 24)
        b.mode = 0
    }

    // ------------------------------------------------------- CAMPEÃ (LUX)

    private fun lux(c: Champ) {
        val ally = c.team == 0
        val dress = if (ally) cc(0xFFF2F5FF) else cc(0xFF3B2B63)
        val trim = if (ally) cc(0xFF3F6FD8) else cc(0xFF8E4DDB)
        val gold = if (ally) cc(0xFFFFD34E) else cc(0xFFD9A7FF)
        val hair = if (ally) cc(0xFFFFE17A) else cc(0xFF7B2FBF)
        val skin = if (ally) cc(0xFFFFDCC0) else cc(0xFFE8C2B0)
        val glow = if (ally) cc(0xFFFFF7B0) else cc(0xFFE6B8FF)
        val amp = (c.mv / 0.1f).coerceIn(0f, 1f)
        val ph = c.walk * 1.1f
        val sw = sin(ph) * amp
        val bob = abs(sin(ph)) * 1.8f * amp + sin(t * 2f) * 0.5f
        val ck = (c.castT / 0.45f).coerceIn(0f, 1f)
        val ak = ((c.atkCd - 0.45f) / 0.25f).coerceIn(0f, 1f)
        val act = max(ck, ak)

        // pernas e botas
        for (s in intArrayOf(-1, 1)) {
            b.push()
            b.tr(0f, 26f, s * 4.5f)
            b.rotZ(s * sw * 32f)
            b.cyl(0f, -23f, 0f, 3.0f, 3.6f, 23f, K(skin), 6)
            b.cyl(0f, -27f, 0f, 3.9f, 3.4f, 9f, K(gold), 6)
            b.box(1.6f, -26.5f, 0f, 8f, 3f, 5.2f, K(gold))
            b.pop()
        }
        b.push()
        b.tr(0f, bob, 0f)
        // saia
        b.cyl(0f, 21f, 0f, 15.5f, 8f, 19f, K(dress), 10)
        b.cyl(0f, 20.5f, 0f, 16f, 15.2f, 3f, K(trim), 10)
        b.cyl(0f, 19.6f, 0f, 16.4f, 16f, 1.4f, K(gold), 10)
        for (i in 0 until 6) {
            b.push()
            b.rotY(i * 60f + 10f)
            b.tr(13f, 29f, 0f)
            b.rotZ(-28f)
            b.box(0f, 3f, 0f, 2f, 9f, 5.5f, K(gold))
            b.pop()
        }
        // tronco
        b.cyl(0f, 38f, 0f, 7.6f, 6.4f, 13f, K(dress), 8)
        b.cyl(0f, 42.5f, 0f, 8.0f, 6.9f, 7.5f, K(gold), 8)
        b.sphere(0f, 52f, 0f, 7.2f, 2.3f, 7.2f, K(gold), 1f, true, 8, 3)
        b.cyl(0f, 50f, 0f, 2.6f, 2.4f, 4f, K(skin), 6)
        // cabeça
        b.sphere(0f, 61f, 0f, 8.2f, 8.8f, 8.0f, K(skin), 1f, true, 10, 6)
        for (s in intArrayOf(-1, 1)) {
            b.sphere(7.2f, 61.6f, s * 3.3f, 1.1f, 1.9f, 1.5f, if (fl) white else cc(0xFF2F6BFF), 1f, false, 6, 3)
            b.sphere(7.6f, 62.3f, s * 3.0f, 0.5f, 0.6f, 0.5f, white, 1f, false, 4, 2)
            b.sphere(6.6f, 58.4f, s * 5.4f, 1.3f, 0.9f, 0.6f, K(cc(0xFFFFA8A0)), 1f, true, 5, 3)
            b.box(6.6f, 64.6f, s * 3.4f, 1f, 0.7f, 3f, K(shadeCol(hair, 0.7f)))
        }
        b.box(7.7f, 57.3f, 0f, 0.8f, 0.8f, 2.4f, K(cc(0xFFD2685F)))
        // cabelo
        b.sphere(-2.2f, 63f, 0f, 9.2f, 10.2f, 9.4f, K(hair), 1f, true, 10, 6)
        b.sphere(3.2f, 68.4f, 0f, 6.0f, 3.4f, 8.6f, K(hair), 1f, true, 8, 4)
        for (s in intArrayOf(-1, 1)) {
            b.sphere(2f, 62.5f, s * 7.6f, 3.2f, 6.8f, 2.2f, K(hair), 1f, true, 6, 4)
            // maria-chiquinhas balançando
            b.push()
            b.tr(-4f, 67f, s * 8.8f)
            b.rotZ(12f + sin(t * 2.2f + s) * 5f + amp * 8f)
            b.rotX(s * 14f)
            b.sphere(0f, 0f, 0f, 5.2f, 5.2f, 5.2f, K(hair), 1f, true, 7, 4)
            b.cyl(0f, -31f, 0f, 1.4f, 4.6f, 31f, K(hair), 6)
            b.sphere(0f, 0f, 0f, 1.5f, 1.5f, 6.4f, K(gold), 1f, true, 5, 3)
            b.pop()
        }
        // tiara
        b.box(5.6f, 66.2f, 0f, 2f, 2.2f, 9.5f, K(gold))
        b.sphere(7f, 66.2f, 0f, 1.7f, 1.7f, 1.7f, if (fl) white else glow, 1f, false, 6, 3)
        for (s in intArrayOf(-1, 1)) {
            b.push()
            b.tr(2f, 68f, s * 8.2f)
            b.rotX(s * 40f)
            b.box(0f, 3f, 0f, 2f, 7f, 1.2f, K(gold))
            b.pop()
        }
        // asas douradas
        for (s in intArrayOf(-1, 1)) {
            for (k in 0 until 3) {
                b.push()
                b.tr(-7.5f, 49f + k * 2f, s * 3f)
                b.rotX(s * (22f + k * 15f))
                b.rotZ(14f + sin(t * 3f + k) * 3f)
                b.box(0f, 8f - k, 0f, 1.2f, 17f - k * 3f, 4.2f, K(if (k == 1) trim else gold))
                b.pop()
            }
        }
        // braço livre
        b.push()
        b.tr(0f, 50f, -8.6f)
        b.rotZ(-sw * 30f + 4f)
        b.cyl(0f, -13f, 0f, 2.0f, 2.5f, 13f, K(dress), 6)
        b.cyl(0f, -12f, 0f, 2.9f, 2.9f, 3f, K(gold), 6)
        b.sphere(0f, -14.2f, 0f, 2.3f, 2.3f, 2.3f, K(skin), 1f, true, 6, 3)
        b.pop()
        // braço do cajado + cajado
        val armA = 8f + sw * 18f + act * 72f
        val staffW = -12f - 58f * act
        b.push()
        b.tr(0f, 50f, 8.6f)
        b.rotZ(armA)
        b.cyl(0f, -13f, 0f, 2.0f, 2.5f, 13f, K(dress), 6)
        b.cyl(0f, -12f, 0f, 2.9f, 2.9f, 3f, K(gold), 6)
        b.sphere(0f, -14.2f, 0f, 2.3f, 2.3f, 2.3f, K(skin), 1f, true, 6, 3)
        b.tr(0f, -14f, 0f)
        b.rotZ(staffW - armA)
        b.cyl(0f, -20f, 0f, 1.2f, 1.2f, 66f, K(wood), 6)
        b.cyl(0f, 44f, 0f, 2.4f, 3.4f, 3.5f, K(gold), 6)
        for (s in intArrayOf(-1, 1)) {
            b.push()
            b.tr(0f, 50f, s * 3f)
            b.rotX(s * 30f)
            b.box(0f, 3f, 0f, 1.2f, 8f, 2f, K(gold))
            b.pop()
        }
        val pulse = 1f + 0.18f * sin(t * 6f)
        b.sphere(0f, 53f, 0f, 3.6f, 4.6f, 3.6f, if (fl) white else glow, 1f, false, 8, 5)
        b.mode = 2
        b.sphere(0f, 53f, 0f, 8.5f * pulse, 8.5f * pulse, 8.5f * pulse, glow, 0.25f, false, 8, 5)
        b.mode = 0
        b.pop()
        // escudo (W)
        if (c.shield > 0f) {
            b.sphere(0f, 38f, 0f, 30f, 36f, 30f, cc(0xFF8EDCFF), 0.26f, false, 12, 8)
        }
        b.pop()
        // marca da passiva
        if (c.markT > 0f) {
            b.push()
            b.tr(0f, 96f, 0f)
            b.rotY(t * 120f)
            b.cyl(0f, 0f, 0f, 0f, 5f, 6f, cc(0xFFFFF59D), 4, 1f, false)
            b.cyl(0f, -6f, 0f, 0f, 5f, 6f, cc(0xFFFFF59D), 4, 1f, false)
            b.pop()
        }
    }

    // ---------------------------------------------------------- MINION

    private fun minion(m: Minion) {
        val ally = m.team == 0
        val tc = if (ally) cc(0xFF3F7FD6) else cc(0xFFD64F3F)
        val dk = if (ally) cc(0xFF2B4F8F) else cc(0xFF8F2F2B)
        val skin = cc(0xFF8BC34A)
        val amp = (m.mv / 0.1f).coerceIn(0f, 1f)
        val ph = m.walk * 1.2f
        val sw = sin(ph) * amp
        val period = if (m.caster) 1.3f else 1.0f
        val ak = ((m.atkCd - (period - 0.3f)) / 0.3f).coerceIn(0f, 1f)
        for (s in intArrayOf(-1, 1)) {
            b.push()
            b.tr(0f, 13f, s * 3.6f)
            b.rotZ(s * sw * 36f)
            b.box(0f, -6.5f, 0f, 4.6f, 13f, 4.6f, K(dk))
            b.box(1.2f, -12.5f, 0f, 7f, 3f, 5.4f, K(cc(0xFF3E2A1C)))
            b.pop()
        }
        b.push()
        b.tr(0f, abs(sw) * 1.2f, 0f)
        b.cyl(0f, 11f, 0f, 7.8f, 6.2f, 15f, K(tc), 8)
        b.cyl(0f, 11.5f, 0f, 8.0f, 7.8f, 2.6f, K(cc(0xFF6D4C41)), 8)
        b.sphere(0f, 32f, 0f, 8.2f, 7.6f, 7.6f, K(skin), 1f, true, 8, 5)
        for (s in intArrayOf(-1, 1)) {
            b.sphere(0f, 32.5f, s * 8f, 1.6f, 2.8f, 1.2f, K(skin), 1f, true, 5, 3)
            b.sphere(7.1f, 33f, s * 2.9f, 1.5f, 1.8f, 1.5f, white, 1f, false, 5, 3)
            b.sphere(8.3f, 33f, s * 2.9f, 0.7f, 0.9f, 0.7f, cc(0xFF101010), 1f, false, 4, 2)
        }
        b.box(7.6f, 28.6f, 0f, 0.8f, 0.8f, 3.4f, K(cc(0xFF33691E)))
        if (m.caster) {
            b.cyl(0f, 35.5f, 0f, 10.8f, 10.8f, 1.2f, K(dk), 8)
            b.cyl(0f, 36.5f, 0f, 8.0f, 0f, 17f, K(dk), 8)
        } else {
            b.sphere(0f, 35.4f, 0f, 8.8f, 5.4f, 8.4f, K(dk), 1f, true, 8, 4)
            b.box(0f, 41.6f, 0f, 9f, 3.6f, 1.6f, K(tc))
        }
        // braço direito (arma)
        val a1 = 12f + ak * 100f - sw * 20f
        b.push()
        b.tr(0f, 24f, 8.4f)
        b.rotZ(a1)
        b.cyl(0f, -11f, 0f, 2.0f, 2.3f, 11f, K(skin), 5)
        b.tr(0f, -11f, 0f)
        if (m.caster) {
            b.rotZ(-a1 - 8f - ak * 40f)
            b.cyl(0f, -12f, 0f, 1.2f, 1.2f, 38f, K(wood), 5)
            b.sphere(0f, 28f, 0f, 3.6f, 3.6f, 3.6f, if (fl) white else (if (ally) cc(0xFF80D8FF) else cc(0xFFFF8A80)), 1f, false, 6, 4)
            b.mode = 2
            b.sphere(0f, 28f, 0f, 6f, 6f, 6f, if (ally) cc(0xFF80D8FF) else cc(0xFFFF8A80), 0.3f, false, 6, 4)
            b.mode = 0
        } else {
            b.rotZ(-70f - a1 * 0.2f)
            b.box(0f, 6f, 0f, 1.4f, 13f, 3.2f, K(cc(0xFFCFD8DC)))
            b.box(0f, -0.5f, 0f, 2f, 2.4f, 7f, K(cc(0xFFFFB300)))
        }
        b.pop()
        // braço esquerdo (escudo)
        b.push()
        b.tr(0f, 24f, -8.4f)
        b.rotZ(-sw * 22f + 6f)
        b.cyl(0f, -11f, 0f, 2.0f, 2.3f, 11f, K(skin), 5)
        if (!m.caster) {
            b.box(2.4f, -8f, -1.5f, 2.2f, 13f, 11f, K(tc))
            b.box(3.6f, -8f, -1.5f, 0.8f, 9f, 7f, K(cc(0xFFFFD54F)))
        }
        b.pop()
        b.pop()
    }

    // ----------------------------------------------------------- TORRES

    private fun tower(tw: Tower) {
        val s = if (tw.nexus) 1.7f else 1f
        val team = if (tw.team == 0) cc(0xFF4FC3F7) else cc(0xFFFF5252)
        val teamD = if (tw.team == 0) cc(0xFF2A74A8) else cc(0xFFA83A3A)
        fl = tw.flash > 0f
        val gold = cc(0xFFFFD34E)
        b.push()
        b.tr(tw.x, groundH(tw.x, tw.y), tw.y)
        b.sc(s, s, s)
        b.cyl(0f, 0f, 0f, 46f, 42f, 12f, K(stoneD), 10)
        b.cyl(0f, 12f, 0f, 33f, 26f, 60f, K(stone), 10)
        b.cyl(0f, 38f, 0f, 30.4f, 29.8f, 8f, K(teamD), 10)
        b.cyl(0f, 72f, 0f, 26f, 35f, 16f, K(stoneL), 10)
        b.cyl(0f, 88f, 0f, 35f, 35f, 6f, K(stoneD), 10)
        b.cyl(0f, 94f, 0f, 30f, 12f, 6f, K(stone), 10)
        for (i in 0 until 4) {
            b.push()
            b.rotY(i * 90f + 45f)
            b.tr(31f, 88f, 0f)
            b.cyl(0f, 0f, 0f, 5f, 0f, 20f, K(gold), 5)
            b.pop()
        }
        b.cyl(0f, 100f, 0f, 8f, 5f, 8f, K(gold), 8)
        val bob = sin(t * 2.2f + tw.x) * 4f
        val pulse = 1f + 0.12f * sin(t * 3f + tw.x)
        b.push()
        b.tr(0f, 130f + bob, 0f)
        b.rotY(t * 55f)
        b.cyl(0f, -24f, 0f, 0f, 14f, 24f, if (fl) white else team, 4, 1f, false)
        b.cyl(0f, 0f, 0f, 14f, 0f, 24f, if (fl) white else team, 4, 1f, false)
        b.pop()
        b.mode = 2
        b.sphere(0f, 130f + bob, 0f, 34f * pulse, 34f * pulse, 34f * pulse, team, 0.22f, false, 10, 6)
        b.mode = 0
        // tochas
        for (sd in intArrayOf(-1, 1)) {
            val tx = sd * 44f
            b.cyl(tx, 2f, 0f, 2.6f, 2.2f, 24f, K(wood), 5)
            val fh = 8f + 3f * sin(t * 14f + tx)
            b.mode = 2
            b.cyl(tx, 26f, 0f, 5f, 0f, fh * 1.8f, cc(0xFFFFA726), 5, 0.85f, false)
            b.cyl(tx, 26f, 0f, 2.6f, 0f, fh * 1.2f, cc(0xFFFFEE58), 5, 0.9f, false)
            b.mode = 0
        }
        if (tw.nexus) {
            for (i in 0 until 4) {
                val a = t * 0.8f + i * 1.5708f
                b.sphere(cos(a) * 62f, 110f + sin(t * 2f + i) * 8f, sin(a) * 62f, 6f, 9f, 6f, if (fl) white else team, 1f, false, 6, 4)
            }
            b.mode = 2
            b.ring(0f, 14f, 0f, 55f, 62f, team, 0.5f, false, 28)
            b.mode = 0
        }
        b.pop()
        fl = false
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
                b.tr(q.x, gy + 40f, q.y)
                b.rotY(-deg)
                b.mode = 2
                b.sphere(-18f, 0f, 0f, 46f, 12f, 12f, cc(0xFFFFF59D), 0.35f, false, 8, 4)
                b.sphere(-30f, 0f, 0f, 40f, 7f, 7f, cc(0xFFFFFFFF), 0.25f, false, 8, 4)
                b.mode = 0
                b.sphere(0f, 0f, 0f, 22f, 5.5f, 5.5f, cc(0xFFFFFDE7), 1f, false, 8, 4)
                b.pop()
            }
            0 -> {
                b.mode = 2
                b.sphere(q.x, gy + 44f, q.y, 14f, 14f, 14f, cc(0xFFFFF59D), 0.35f, false, 6, 4)
                b.mode = 0
                b.sphere(q.x, gy + 44f, q.y, 5f, 5f, 5f, white, 1f, false, 6, 4)
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
        val pulse = 1f + 0.04f * sin(t * 8f)
        b.mode = 2
        b.disc(z.x, gy, z.y, z.r * pulse, cc(0xFFFFF59D), 0.2f, false, 30)
        b.ring(z.x, gy + 0.2f, z.y, z.r * pulse - 6f, z.r * pulse, cc(0xFFFFF59D), 0.85f, false, 36)
        val k = (z.life / 4f).coerceIn(0f, 1f)
        b.ring(z.x, gy + 0.3f, z.y, z.r * k - 3f, z.r * k, cc(0xFFFFFFFF), 0.7f, false, 30)
        for (i in 0 until 6) {
            val a = t * 2.5f + i * 1.047f
            val rr = z.r * 0.6f
            b.sphere(z.x + cos(a) * rr, gy + 10f + sin(t * 4f + i) * 6f, z.y + sin(a) * rr, 4f, 4f, 4f, white, 0.9f, false, 5, 3)
        }
        b.sphere(z.x, gy + 18f, z.y, 14f, 14f, 14f, cc(0xFFFFF59D), 0.45f, false, 8, 5)
        b.sphere(z.x, gy + 18f, z.y, 7f, 7f, 7f, white, 0.9f, false, 8, 5)
        b.mode = 0
    }

    private fun beam(bm: Beam) {
        val deg = Math.toDegrees(bm.ang.toDouble()).toFloat()
        b.push()
        b.tr(bm.x, groundH(bm.x, bm.y) + 38f, bm.y)
        b.rotY(-deg)
        b.mode = 2
        if (!bm.fired) {
            val k = 1f - (bm.delay / 0.6f).coerceIn(0f, 1f)
            b.box(850f, -36f, 0f, 1700f, 0.5f, 110f, cc(0xFFFFF59D), 0.22f, false)
            b.box(850f, -36f, 0f, 1700f, 0.6f, 110f * k, cc(0xFFFFF59D), 0.5f, false)
            b.sphere(14f, 0f, 0f, 16f * k, 16f * k, 16f * k, cc(0xFFFFF59D), 0.6f, false, 8, 5)
        } else {
            val k = (bm.life / 0.45f).coerceIn(0f, 1f)
            b.box(850f, 0f, 0f, 1700f, 100f * k, 140f * k, cc(0xFFFFF59D), 0.28f, false)
            b.box(850f, 0f, 0f, 1700f, 62f * k, 90f * k, cc(0xFFFFFDE7), 0.6f, false)
            b.box(850f, 0f, 0f, 1700f, 26f * k, 44f * k, white, 1f, false)
            b.sphere(10f, 0f, 0f, 34f * k, 34f * k, 34f * k, cc(0xFFFFF59D), 0.5f, false, 8, 5)
        }
        b.mode = 0
        b.pop()
    }

    /** Indicadores de mira enquanto o jogador arrasta uma habilidade. */
    private fun aim() {
        val pl = g.pl
        if (pl.dead) return
        for (i in 0..3) {
            if (g.bId[i] == -1 || !g.bDrag[i]) continue
            val ang = atan2(g.bAy[i], g.bAx[i])
            val len = when (i) {
                0 -> 950f
                2 -> (sqrt(g.bAx[i] * g.bAx[i] + g.bAy[i] * g.bAy[i]) / g.d * 3.2f).coerceIn(150f, 780f)
                3 -> 1700f
                else -> 120f
            }
            val wd = when (i) {
                0 -> 52f
                3 -> 110f
                else -> 8f
            }
            val gy = groundH(pl.x, pl.y) + 1.6f
            b.push()
            b.tr(pl.x, gy, pl.y)
            b.rotY(-Math.toDegrees(ang.toDouble()).toFloat())
            b.mode = 2
            b.box(len / 2f, 0f, 0f, len, 0.5f, wd, cc(0xFFFFF59D), 0.32f, false)
            if (i == 2) b.disc(len, 0f, 0f, 190f, cc(0xFFFFF59D), 0.22f, false, 28)
            b.mode = 0
            b.pop()
        }
    }
}
