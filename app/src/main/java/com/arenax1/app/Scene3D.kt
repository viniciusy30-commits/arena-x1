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

const val MAP_W = 9600f

/** Sem moitas nesta arena: a vegetação é só decoração de borda. (Lista mantida vazia para o esconderijo poder voltar.) */
val BRUSHES: List<FloatArray> = emptyList()

/** Meia-largura da ponte (até a borda externa da pedra): mais larga nas bases, uniforme no resto. */
fun laneHalf(x: Float): Float {
    val e = min(x, MAP_W - x)
    val k = ((e - 650f) / 420f).coerceIn(0f, 1f)
    val s = k * k * (3f - 2f * k)
    return 470f - 70f * s
}

/** Limites de movimento: encostam na face interna dos parapeitos. */
fun laneT(x: Float): Float = 640f - laneHalf(x) + 34f
fun laneB(x: Float): Float = 640f + laneHalf(x) - 34f

/** Altura do chão no ponto (x, z). z do mundo = y do jogo. Ponte plana; fora dela, abismo e paredões. */
fun groundH(x: Float, z: Float): Float {
    val hw = laneHalf(x)
    val dz = abs(z - 640f)
    val ex = if (x < -40f) -40f - x else if (x > MAP_W + 40f) x - MAP_W - 40f else 0f
    val o = max(dz - hw, ex)
    if (o <= 0f) return 0f
    val k = min(1f, o / 46f)
    var h = -k * (2f - k) * 330f
    val w = ((o - 160f) / 300f).coerceIn(0f, 1f)
    h += w * w * (3f - 2f * w) * 520f
    h += (vnoise(x * 0.012f, z * 0.012f) - 0.5f) * 70f * k * (0.3f + w) + (vnoise(x * 0.05f, z * 0.05f) - 0.5f) * 14f * k
    return h
}

private fun rgbI(r: Float, g: Float, b: Float): Int =
    (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

private fun terrainColor(x: Float, z: Float): Int {
    val n = vnoise(x * 0.05f, z * 0.05f)
    val n2 = vnoise(x * 0.012f, z * 0.012f)
    val hw = laneHalf(x)
    val dz = abs(z - 640f)
    val ex = if (x < -40f) -40f - x else if (x > MAP_W + 40f) x - MAP_W - 40f else 0f
    val o = max(dz - hw, ex)
    var c: Int
    if (o <= 0f) {
        c = rgbI(66f + n * 10f, 78f + n * 10f, 104f + n * 10f)
    } else {
        val h = groundH(x, z)
        c = rgbI(54f + n * 30f, 66f + n * 30f, 94f + n * 34f)
        c = mixCol(c, rgbI(24f + n * 10f, 42f + n * 14f, 80f + n * 18f), ((-h - 60f) / 200f).coerceIn(0f, 1f))
        c = mixCol(c, rgbI(124f, 164f, 208f), (0.2f + n2 * 0.35f) * ((-h - 200f) / 130f).coerceIn(0f, 1f))
        if (h > 20f) c = mixCol(c, rgbI(206f, 222f, 244f), ((h - 20f) / 90f).coerceIn(0f, 1f))
        if (o < 50f) c = mixCol(c, rgbI(150f, 190f, 226f), 0.25f * (1f - o / 50f))
    }
    val n3 = vnoise(x * 0.2f + 9f, z * 0.2f)
    return shadeCol(c, 0.93f + n3 * 0.14f)
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

        val iceC = cc(0xFF9FD6F2)
        val snowC = cc(0xFFEAF2FC)
        val midX = MAP_W / 2f
        val cyanG = cc(0xFF4FC3F7)
        val redG = cc(0xFFFF5252)
        val rockC = cc(0xFF4A566F)
        fun glowOf(x: Float): Int = if (x < midX) cyanG else redG

        // ---- ponte: fachada lateral, parapeitos (alguns quebrados), runas e gelo pendurado
        run {
            var x = -40f
            var si = 0
            while (x < MAP_W + 40f) {
                val xm = x + 30f
                val bt = chunkOf(xm)
                val hw = laneHalf(xm)
                val slope = (laneHalf(xm + 30f) - laneHalf(xm - 30f)) / 60f
                val rot = Math.toDegrees(atan2(slope, 1f).toDouble()).toFloat()
                for (sd in intArrayOf(-1, 1)) {
                    val hs = hash2(si, 300 + sd)
                    bt.push()
                    bt.tr(xm, 0f, 640f + sd * (hw - 11f))
                    bt.rotY(-sd * rot)
                    bt.box(0f, -45f, 0f, 62f, 90f, 22f, mixCol(stoneD, cc(0xFF3E4A66), 0.35f + hs * 0.25f))
                    bt.box(0f, -92f, 0f, 62f, 6f, 24f, cc(0xFF2E3850))
                    if (hs < 0.78f) {
                        bt.box(0f, 13f, 0f, 58f, 26f, 22f, shadeCol(stone, 0.85f + hs * 0.3f))
                        if (hs < 0.5f) bt.box(0f, 27.5f, 0f, 56f, 4f, 20f, snowC)
                        bt.box(0f, 17f, -sd * 11.3f, 50f, 3f, 0.8f, mixCol(glowOf(xm), white, 0.15f), 1f, false)
                    } else if (hs < 0.92f) {
                        bt.box(0f, 5f, 0f, 40f, 10f, 22f, shadeCol(stone, 0.8f))
                        bt.box(14f, 13f, sd * 3f, 18f, 8f, 16f, stoneD)
                    }
                    if (hash2(si, 320 + sd) < 0.4f) {
                        val len = 22f + hash2(si, 330 + sd) * 46f
                        bt.cyl(hash2(si, 340 + sd) * 40f - 20f, -92f - len, sd * 6f, 0f, 4.5f, len, iceC, 5)
                    }
                    bt.pop()
                }
                si++
                x += 60f
            }
            // pilares do abismo sustentando a ponte
            var px = 300f
            while (px < MAP_W) {
                val bt = chunkOf(px)
                val hw = laneHalf(px)
                for (sd in intArrayOf(-1, 1)) {
                    val zc = 640f + sd * (hw - 60f)
                    bt.cyl(px, -330f, zc, 52f, 34f, 280f, mixCol(stoneD, cc(0xFF2E3A58), 0.5f), 8)
                    bt.box(px, -46f, zc, 110f, 24f, 70f, stoneD)
                    bt.sphere(px, -33f, zc, 40f, 5f, 28f, snowC, 1f, true, 6, 3)
                }
                px += 600f
            }
        }

        // ---- piso da ponte: lajes de tamanhos variados, rachaduras, neve nas bordas, gelo e runas
        run {
            var px = -20f
            var col = 0
            while (px < MAP_W + 20f) {
                val bw = 74f + hash2(col, 41) * 70f
                val xm = px + bw / 2f
                val bt = chunkOf(xm)
                val hw = laneHalf(xm)
                val ally = xm < midX
                var zc = 640f - hw + 22f
                val zEnd = 640f + hw - 22f
                var row = 0
                while (zc < zEnd - 8f) {
                    val d = min(zEnd - zc, 56f + hash2(col, row + 90) * 44f)
                    val zm = zc + d / 2f
                    val hsh = hash2(col * 13 + row, 40 + row)
                    val mid = 1f - (abs(zm - 640f) / hw).coerceIn(0f, 1f)
                    val side = (abs(xm - midX) / midX).coerceIn(0f, 1f)
                    val tint = mixCol(cc(0xFFB4BCCB), if (ally) cc(0xFF6FA6D8) else cc(0xFFD9806E), 0.18f + 0.40f * side)
                    var cold = mixCol(tint, cc(0xFFE6E2D2), (0.10f + mid * 0.22f) * (1f - side * 0.6f) + hash2(col, row + 130) * 0.12f)
                    if (abs(zm - 640f) > hw - 110f) cold = mixCol(cold, cc(0xFF4A5470), 0.45f)
                    bt.box(xm, -0.5f + hsh * 0.35f, zm, bw - 2.5f, 2f, d - 2.5f, shadeCol(cold, 0.84f + hsh * 0.3f))
                    val edgeZone = abs(zm - 640f) > hw - 170f
                    if (hash2(col, row + 170) < 0.09f) {
                        bt.push()
                        bt.tr(xm + (hsh - 0.5f) * bw * 0.3f, 0.9f, zm)
                        bt.rotY(hsh * 160f)
                        bt.box(0f, 0f, 0f, bw * 0.6f, 0.3f, 1.6f, cc(0xFF3B465E), 1f, false)
                        bt.pop()
                    }
                    if (edgeZone && hash2(col, row + 210) < 0.12f) {
                        bt.sphere(xm + (hsh - 0.5f) * bw * 0.4f, 0.9f, zm, bw * 0.34f, 1.6f, d * 0.3f, snowC, 1f, true, 6, 3)
                    }
                    if (hash2(col, row + 250) < 0.05f) {
                        bt.box(xm, 1.1f, zm, bw * 0.8f, 0.4f, d * 0.7f, cc(0xFFB8E6FA), 0.45f, false)
                    }
                    if (edgeZone && hash2(col, row + 290) < 0.04f) {
                        bt.box(xm, 1.1f, zm, 16f, 0.3f, 2f, mixCol(glowOf(xm), stoneD, 0.5f), 1f, false)
                    }
                    zc += d
                    row++
                }
                px += bw
                col++
            }
            // círculo rúnico central
            val bt = chunkOf(midX)
            bt.ring(midX, 1.2f, 640f, 150f, 158f, cc(0xFF7FB6D6), 1f, false, 48)
            bt.ring(midX, 1.2f, 640f, 96f, 99f, cc(0xFF6E9ABB), 1f, false, 40)
            for (k in 0 until 8) {
                bt.push()
                bt.tr(midX, 1.3f, 640f)
                bt.rotY(k * 45f)
                bt.box(127f, 0f, 0f, 52f, 0.3f, 2.2f, cc(0xFF7FB6D6), 1f, false)
                bt.pop()
            }
        }

        // ---- postes de luz e mastros ao longo dos parapeitos
        run {
            var px = 1100f
            var idx = 0
            while (px < MAP_W - 1000f) {
                val bt = chunkOf(px)
                val hw = laneHalf(px)
                val gl = glowOf(px)
                for (sd in intArrayOf(-1, 1)) {
                    val pz = 640f + sd * (hw - 24f)
                    bt.cyl(px, 0f, pz, 10f, 8f, 34f, stoneL, 6)
                    bt.cyl(px, 34f, pz, 12f, 12f, 4f, stoneD, 6)
                    bt.sphere(px, 44f, pz, 6f, 7f, 6f, gl, 1f, false, 6, 4)
                    if (idx % 2 == 0) bt.cyl(px, 38f, pz, 2.2f, 2.2f, 74f, stoneD, 5)
                }
                idx++
                px += 540f
            }
        }

        // ---- as duas bases: praça ampla, muralha com portal, bastiões, arco de entrada
        for (team in 0..1) {
            val dir = if (team == 0) 1f else -1f
            val bx = if (team == 0) 0f else MAP_W
            val cx = if (team == 0) 380f else MAP_W - 380f
            val bt = chunkOf(cx)
            val tint = if (team == 0) cc(0xFF4A6FA5) else cc(0xFFA5504A)
            val glow = if (team == 0) cyanG else redG
            val accent = if (team == 0) cc(0xFFBDEBFF) else cc(0xFFFFB74D)
            val wallC = mixCol(cc(0xFF5C6580), tint, 0.18f)
            bt.cyl(cx, 0.3f, 640f, 312f, 306f, 2.4f, mixCol(cc(0xFF6B7488), tint, 0.4f), 40)
            bt.cyl(cx, 2.7f, 640f, 232f, 228f, 2.4f, mixCol(stone, tint, 0.25f), 36)
            bt.ring(cx, 5.3f, 640f, 150f, 160f, glow, 1f, false, 36)
            bt.ring(cx, 5.4f, 640f, 188f, 192f, accent, 1f, false, 40)
            for (k in 0 until 8) {
                bt.push()
                bt.tr(cx, 5.2f, 640f)
                bt.rotY(k * 45f)
                bt.box(250f, 0f, 0f, 100f, 0.3f, 3.4f, mixCol(glow, stoneD, 0.35f), 1f, false)
                bt.pop()
            }
            bt.cyl(cx, 3f, 640f, 70f, 66f, 6f, stoneD, 20)
            // muralha do fundo
            val wx = bx + dir * 22f
            bt.box(wx, 85f, 640f, 64f, 170f, 1000f, wallC)
            bt.box(wx + dir * 30f, 8f, 640f, 8f, 16f, 1000f, stoneD)
            for (k in 0 until 26) {
                val zz = 150f + k * 39f
                if (k % 2 == 0) {
                    bt.box(wx, 183f, zz, 62f, 26f, 22f, wallC)
                    bt.box(wx, 197.5f, zz, 60f, 3f, 20f, snowC)
                }
                if (k % 3 == 1) {
                    val len = 14f + hash2(k, 500 + team) * 30f
                    bt.cyl(wx + dir * 32f, 170f - len, zz, 0f, 3.5f, len, iceC, 5)
                }
            }
            // portal
            bt.box(wx + dir * 31f, 45f, 640f, 4f, 90f, 90f, cc(0xFF0B1426), 1f, false)
            for (sd in intArrayOf(-1, 1)) bt.box(wx + dir * 31f, 50f, 640f + sd * 55f, 14f, 100f, 20f, stoneL)
            bt.box(wx + dir * 31f, 105f, 640f, 14f, 16f, 130f, stoneL)
            bt.sphere(wx + dir * 40f, 118f, 640f, 9f, 9f, 9f, glow, 1f, false, 8, 5)
            // bastiões redondos nos cantos
            for (sd in intArrayOf(-1, 1)) {
                val tx = bx + dir * 10f
                val tz = 640f + sd * 500f
                bt.cyl(tx, -90f, tz, 72f, 66f, 90f, stoneD, 12)
                bt.cyl(tx, 0f, tz, 64f, 58f, 220f, wallC, 12)
                bt.cyl(tx, 150f, tz, 66f, 66f, 10f, stoneD, 12)
                bt.cyl(tx, 220f, tz, 74f, 0f, 96f, mixCol(tint, cc(0xFF2A3550), 0.5f), 12)
                bt.cyl(tx, 262f, tz, 44f, 0f, 54f, snowC, 12)
                bt.cyl(tx, 316f, tz, 2.5f, 2.5f, 60f, stoneD, 6)
                bt.box(tx + dir * 60f, 130f, tz, 3f, 26f, 9f, glow, 1f, false)
            }
            // arco de entrada da base
            val arcx = bx + dir * 900f
            val ahw = laneHalf(arcx) - 30f
            for (sd in intArrayOf(-1, 1)) {
                bt.cyl(arcx, 0f, 640f + sd * ahw, 24f, 20f, 150f, stoneL, 10)
                bt.box(arcx, 150f, 640f + sd * ahw, 60f, 14f, 60f, stoneD)
                bt.sphere(arcx, 166f, 640f + sd * ahw, 8f, 8f, 8f, glow, 1f, false, 6, 4)
            }
            bt.box(arcx, 166f, 640f, 40f, 22f, 2f * ahw + 40f, stone)
            bt.box(arcx, 154f, 640f, 10f, 2f, 2f * ahw, glow, 1f, false)
            bt.sphere(arcx, 188f, 640f, 10f, 12f, 10f, accent, 1f, false, 8, 5)
            // cristais ladeando o núcleo
            crystals(bt, wx + dir * 120f, 640f - 270f, 1.6f, glow, team * 7 + 1)
            crystals(bt, wx + dir * 120f, 640f + 270f, 1.6f, glow, team * 7 + 2)
        }

        // ---- cristais brilhantes ao longo da ponte, na cor de cada lado
        run {
            var cxp = 700f
            var ci = 0
            while (cxp < MAP_W - 700f) {
                val sd = if (ci % 2 == 0) -1 else 1
                val gcol = if (cxp < midX) cyanG else redG
                val bt = chunkOf(cxp)
                crystals(bt, cxp, 640f + sd * (laneHalf(cxp) + 55f), 1.15f + hash2(ci, 811) * 0.5f, gcol, 900 + ci)
                cxp += 480f + hash2(ci, 812) * 160f
                ci++
            }
        }

        // ---- abismo: espirais de rocha com topo nevado
        run {
            for (i in 0 until 95) {
                val x = -300f + hash2(i, 401) * (MAP_W + 600f)
                val side = if (hash2(i, 402) < 0.5f) -1 else 1
                val z = 640f + side * (laneHalf(x) + 260f + hash2(i, 403) * 420f)
                val base = groundH(x, z)
                val cap = if (side > 0) 40f else 150f
                val top = -60f + hash2(i, 404) * (cap + 60f)
                val h = max(60f, top - base)
                val r = 30f + hash2(i, 405) * 50f
                val bt = chunkOf(x)
                bt.cyl(x, base - 6f, z, r, r * 0.12f, h, mixCol(rockC, cc(0xFF8FA2C6), hash2(i, 406) * 0.5f), 7)
                bt.cyl(x, base - 6f + h * 0.75f, z, r * 0.36f, 0f, h * 0.25f + 6f, snowC, 7)
            }
        }

        // ---- plataformas antigas em ruínas, com pinheiros congelados, arbustos e musgo
        run {
            for (i in 0 until 34) {
                val x = 150f + hash2(i, 421) * (MAP_W - 300f)
                val side = if (i % 2 == 0) -1 else 1
                val z = 640f + side * (laneHalf(x) + 120f + hash2(i, 422) * 150f)
                val py = -70f - hash2(i, 423) * 90f
                val sx = 110f + hash2(i, 424) * 90f
                val sz = 70f + hash2(i, 425) * 50f
                val bt = chunkOf(x)
                bt.box(x, py, z, sx, 22f, sz, stoneD)
                bt.cyl(x, py - 70f, z, sx * 0.22f, sx * 0.12f, 70f, rockC, 7)
                bt.box(x, py + 13f, z, sx * 0.96f, 4f, sz * 0.96f, snowC)
                if (hash2(i, 426) < 0.7f) {
                    bt.cyl(x + sx * 0.25f, py + 11f, z, 11f, 10f, 30f + hash2(i, 429) * 40f, stoneL, 8)
                    bt.sphere(x + sx * 0.25f, py + 44f, z, 10f, 3f, 10f, snowC, 1f, true, 6, 3)
                }
                if (hash2(i, 427) < 0.5f) tree(bt, x - sx * 0.28f, py + 11f, z, 0.5f, hash2(i, 428))
                bt.sphere(x - sx * 0.05f, py + 16f, z + sz * 0.2f, 11f, 7f, 9f, snowC, 1f, true, 6, 3)
                bt.sphere(x + sx * 0.1f, py + 12.5f, z - sz * 0.2f, 14f, 2.2f, 10f, cc(0xFF2F5A58), 1f, true, 6, 3)
            }
        }

        // ---- montanhas distantes em camadas, cada vez mais claras e azuladas
        run {
            for (layer in 0 until 3) {
                for (i in 0 until 52) {
                    val x = -600f + i * 205f + hash2(i, 440 + layer) * 90f
                    val z = -380f - layer * 320f - hash2(i, 450 + layer) * 120f
                    val h = 520f + layer * 220f + hash2(i, 460 + layer) * 340f
                    val r = 260f + hash2(i, 470 + layer) * 200f
                    val bt = chunkOf(x)
                    val rc = mixCol(mixCol(cc(0xFF4E5E86), cc(0xFF9DB3D8), layer / 2f), cc(0xFF6E84AE), hash2(i, 480 + layer) * 0.4f)
                    bt.cyl(x, -330f, z, r, 0f, h, rc, 7)
                    bt.cyl(x, -330f + h * 0.7f, z, r * 0.32f, 0f, h * 0.3f + 4f, cc(0xFFF1F4FA), 7)
                }
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
        sprites.clear()
        t = g.t
        val cx = g.camX
        fl = false

        // névoa lenta nas profundezas do abismo
        b.mode = 2
        for (L in 0 until 3) {
            val my = -70f - L * 105f
            for (i in 0 until 9) {
                val mx = cx - 1500f + (((i * 0.618034f + L * 0.37f) % 1f) * 3000f + t * (14f + L * 9f)) % 3000f
                val side = if (i % 2 == 0) -1 else 1
                val mz = 640f + side * (470f + ((i * 0.381966f + L * 0.2f) % 1f) * 260f)
                b.box(mx, my, mz, 520f, 6f, 190f, cc(0xFFAFC8E8), 0.07f + 0.02f * L, false)
            }
        }
        for (i in 0 until 8) {
            val mx = cx - 1400f + (((i * 0.618034f) % 1f) * 2800f + t * 30f) % 2800f
            val side = if (i % 2 == 0) -1 else 1
            b.box(mx, -8f, 640f + side * (laneHalf(mx) + 40f), 300f, 4f, 70f, cc(0xFFC9DCF2), 0.09f, false)
        }
        // brilho ocasional de gelo nas bordas da ponte
        for (i in 0 until 160) {
            val gx = hash2(i, 701) * MAP_W
            if (abs(gx - cx) > 1300f) continue
            val tw = 0.5f + 0.5f * sin(t * 2.4f + i * 1.9f)
            if (tw < 0.8f) continue
            val side = if (i % 2 == 0) -1 else 1
            val gz = 640f + side * (laneHalf(gx) - 44f - hash2(i, 702) * 120f)
            b.box(gx, 3f, gz, 3.2f, 3.2f, 3.2f, white, (tw - 0.8f) * 5f, false)
        }
        // núcleos pulsando
        for (team in 0..1) {
            val ncx = if (team == 0) 380f else MAP_W - 380f
            if (abs(ncx - cx) > 2200f) continue
            val gl = if (team == 0) cc(0xFF4FC3F7) else cc(0xFFFF5252)
            val pu = 0.5f + 0.5f * sin(t * 2.2f + team * 1.7f)
            b.ring(ncx, 5.9f, 640f, 120f + pu * 26f, 130f + pu * 26f, gl, 0.25f + 0.35f * (1f - pu), false, 40)
            b.disc(ncx, 5.8f, 640f, 112f, gl, 0.10f + 0.08f * pu, false, 32)
        }
        // pulsos de energia correndo pela linha central da ponte
        for (i in 0 until 8) {
            val px = ((i * 1200f + t * 340f) % MAP_W)
            if (abs(px - cx) > 1900f) continue
            val pc = if (px < MAP_W / 2f) cc(0xFF4FC3F7) else cc(0xFFFF6E5C)
            val py = groundH(px, 640f) + 2.2f
            b.box(px, py, 640f, 70f, 0.8f, 7f, pc, 0.55f, false)
            b.box(px, py + 0.1f, 640f, 26f, 0.8f, 3f, white, 0.8f, false)
        }
        // faíscas de gelo subindo do abismo
        for (i in 0 until 50) {
            val fx = cx - 1400f + (((i * 0.618034f) % 1f) * 2800f + t * 18f) % 2800f
            val side = if (i % 2 == 0) -1 else 1
            val fz = 640f + side * (laneHalf(fx) + 30f + ((i * 0.381966f) % 1f) * 260f)
            val fy = -160f + ((t * (26f + (i % 5) * 7f) + i * 47f) % 420f)
            b.box(fx, fy, fz, 3f, 3f, 3f, cc(0xFFBFE6FF), 0.55f * (1f - (fy + 160f) / 420f), false)
        }
        // neve soprada em rajadas
        val gust = 60f + 110f * (0.5f + 0.5f * sin(t * 0.27f))
        for (i in 0 until 120) {
            val fx = cx - 1500f + (((i * 0.618034f) % 1f) * 3000f + t * gust) % 3000f
            val fz = g.CY - 900f + ((i * 0.381966f + 0.17f) % 1f) * 1700f
            val spd = 40f + ((i * 0.7548f) % 1f) * 45f
            val fy = 760f - ((t * spd + i * 61f) % 760f)
            b.box(fx, fy, fz, 2.6f, 2.6f, 2.6f, white, 0.75f, false)
        }
        b.mode = 0
        // bandeiras ao vento
        for (team in 0..1) {
            val bx = if (team == 0) 0f else MAP_W
            val dir = if (team == 0) 1f else -1f
            if (abs(bx - cx) > 2300f) continue
            val fc = if (team == 0) cc(0xFF3FA8D8) else cc(0xFFD84A3A)
            for (sd in intArrayOf(-1, 1)) flag(bx + dir * 10f, 366f, 640f + sd * 500f, fc, team * 2f + sd)
        }
        var fpx = 1100f
        var fidx = 0
        while (fpx < MAP_W - 1000f) {
            if (fidx % 2 == 0 && abs(fpx - cx) < 1800f) {
                val fc = if (fpx < MAP_W / 2f) cc(0xFF3FA8D8) else cc(0xFFD84A3A)
                for (sd in intArrayOf(-1, 1)) flag(fpx, 104f, 640f + sd * (laneHalf(fpx) - 24f), fc, fidx * 0.7f + sd)
            }
            fidx++
            fpx += 540f
        }
        for (tw in g.towers) {
            if (abs(tw.x - cx) > 3000f) continue
            if (tw.dead) rubble(tw) else tower(tw)
        }
        for (m in g.minions) {
            if (m.dead || abs(m.x - cx) > 2500f || g.hiddenFor(m, 0)) continue
            place(m)
            fl = m.flash > 0f
            b.sc(1.2f, 1.2f, 1.2f)
            minion(m)
            b.pop()
            shadow(m.x, m.y, 16f)
            if (m.rootT > 0f) bindFx(m)
        }
        for (c in g.champs) {
            if (c.dead || abs(c.x - cx) > 2500f || g.hiddenFor(c, 0)) continue
            place(c)
            fl = c.flash > 0f
            if (c.sprite) {
                addSprite(c)
            } else {
                champBody(c)
            }
            b.pop()
            shadow(c.x, c.y, 24f)
            teamRing(c)
            if (c.rootT > 0f) bindFx(c)
        }
        fl = false
        for (c in g.champs) {
            if (c.dead || abs(c.x - cx) > 2600f) continue
            if (c.hero == 2) zedShadows(c)
            if (c.spinT > 0f) spinFx(c)
        }
        for (c in g.champs) if (!c.dead && c.deathT > 0f && abs(c.x - cx) < 2600f && !g.hiddenFor(c, 0)) deathMark(c)
        for (m in g.minions) if (!m.dead && m.deathT > 0f && abs(m.x - cx) < 2600f) deathMark(m)
        for (f in g.deadFx) {
            val u = f.u
            val k = (f.t / 0.8f).coerceIn(0f, 1f)
            place(u)
            fl = false
            b.rotZ(-k * 88f)
            val s = 1f - k * 0.55f
            b.sc(s, s, s)
            if (u is Champ) champBody(u) else if (u is Minion) { b.sc(1.2f, 1.2f, 1.2f); minion(u) }
            b.pop()
        }
        for (z in g.zones) zone(z)
        for (bm in g.beams) beam(bm)
        for (f in g.fxs) fx(f)
        midSeal()
        for (q in g.projs) proj(q)
        b.mode = 2
        for (p in g.parts) {
            val a = min(1f, p.life * 2.5f)
            b.box(p.x, groundH(p.x, p.y) + p.z, p.y, p.sz, p.sz, p.sz, p.col, a, false)
        }
        b.mode = 0
        if (g.pl.hero >= 2) aimNew(g.pl) else aim()
        return b
    }

    private fun flag(x: Float, y: Float, z: Float, col: Int, ph: Float) {
        for (k in 0 until 4) {
            val w = sin(t * 3.2f + ph - k * 0.9f) * (3f + k * 2.2f)
            b.box(x + 6f + k * 6.5f, y - k * 1.2f, z + w, 6.8f, 13f - k * 1.5f, 1.6f, col, 1f, false)
        }
    }

    /** Sprites 2D (Yasuo): quads inclinados para a câmera, 8 direções, 6 quadros de caminhada. */
    val sprites = ArrayList<Float>()

    private fun addSprite(c: Champ) {
        val ang = c.face
        val idx = (((Math.round(ang / (Math.PI / 4.0)).toInt()) % 8) + 8) % 8
        val moving = c.mv > 0f
        val row = if (moving) (c.walk * 0.6f).toInt() % 6 else 6
        val u0 = idx / 8f
        val u1 = (idx + 1) / 8f
        val v0 = row / 7f
        val v1 = (row + 1) / 7f
        val k = 2.5f
        val hw = 48f * k
        val gy = groundH(c.x, c.y)
        val uy = 0.93f
        val uz = -0.37f
        val bot = -21f * k
        val top = 75f * k
        val fv = if (c.flash > 0f) 1f else 0f
        fun v(sx: Float, h: Float, tu: Float, tv: Float) {
            sprites.add(c.x + sx); sprites.add(gy + uy * h + 2f); sprites.add(c.y + uz * h)
            sprites.add(tu); sprites.add(tv); sprites.add(fv)
        }
        v(-hw, bot, u0, v1); v(hw, bot, u1, v1); v(hw, top, u1, v0)
        v(-hw, bot, u0, v1); v(hw, top, u1, v0); v(-hw, top, u0, v0)
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

    /** Prisão de luz (Q da Lux): anéis dourados girando e fagulhas em volta do alvo. */
    private fun bindFx(u: U) {
        val gy = groundH(u.x, u.y)
        val k = (u.rootT / 1.6f).coerceIn(0f, 1f)
        val gl = cc(0xFFFFF59D)
        b.mode = 2
        for (i in 0 until 3) {
            val yy = gy + 12f + i * 24f + sin(t * 5f + i) * 3f
            b.push()
            b.tr(u.x, yy, u.y)
            b.rotY(t * (150f + i * 60f) * (if (i % 2 == 0) 1f else -1f))
            b.ring(0f, 0f, 0f, 27f - i * 2f, 31f - i * 2f, gl, 0.25f + 0.6f * k, false, 22)
            for (j in 0 until 4) {
                b.push()
                b.rotY(j * 90f)
                b.box(29f, 0f, 0f, 7f, 3.4f, 3.4f, white, 0.95f, false)
                b.pop()
            }
            b.pop()
        }
        b.sphere(u.x, gy + 34f, u.y, 30f, 42f, 30f, gl, 0.08f + 0.1f * k, false, 10, 7)
        b.mode = 0
    }

    private fun teamRing(c: Champ) {
        val col = if (c.team == 0) cc(0xFF4FC3F7) else cc(0xFFFF5252)
        val gy = groundH(c.x, c.y) + 1.1f
        b.mode = 2
        b.ring(c.x, gy, c.y, 26f, 30f, col, 0.85f, false, 28)
        if (c.rootT > 0f) b.ring(c.x, gy + 0.2f, c.y, 34f, 38f, cc(0xFFFFF176), 0.95f, false, 28)
        b.mode = 0
    }

    // ================================================== ZED E GAREN (MODELOS)

    private fun smooth(x: Float): Float {
        val q = x.coerceIn(0f, 1f)
        return q * q * (3f - 2f * q)
    }

    private fun zl(len: Float, r0: Float, r1: Float, col: Int, a: Float) {
        b.cyl(0f, -len, 0f, r1, r0, len, col, 10, a)
    }

    private fun zstrand(n: Int, r0: Float, r1: Float, step: Float, c1: Int, c2: Int, sway: Float, curl: Float, a: Float) {
        b.push()
        for (i in 0 until n) {
            val k = i / (n - 1f)
            val r = r0 + (r1 - r0) * k
            b.rotZ(curl + sway * sin(t * 2.6f + i * 0.8f))
            b.sphere(0f, -step * 0.5f, 0f, r, step * 0.78f, r, if (i % 2 == 0) c1 else c2, a, true, 8, 5)
            b.tr(0f, -step, 0f)
        }
        b.pop()
    }

    /** Shuriken de quatro lâminas deitado (plano XZ), girando em Y. */
    private fun shuriken(r: Float, spin: Float, col: Int, edge: Int, a: Float) {
        b.push()
        b.rotY(spin)
        for (i in 0 until 4) {
            b.push()
            b.rotY(i * 90f)
            b.sphere(r * 0.5f, 0f, 0f, r * 0.55f, r * 0.07f, r * 0.2f, col, a, true, 6, 4)

            b.sphere(r * 0.8f, 0f, r * 0.13f, r * 0.2f, r * 0.05f, r * 0.12f, edge, a, true, 5, 3)
            b.pop()
        }
        b.sphere(0f, 0f, 0f, r * 0.24f, r * 0.12f, r * 0.24f, edge, a, true, 8, 4)
        b.pop()
    }

    private fun champBody(c: Champ) {
        if (c.sprite) return
        when (c.hero) {
            2 -> { b.sc(1.35f, 1.35f, 1.35f); zed(c, false, 0) }
            3 -> { b.sc(1.4f, 1.4f, 1.4f); garen(c) }
            else -> { b.sc(1.35f, 1.35f, 1.35f); lux(c) }
        }
    }

    /** Zed. mode: 0 = normal, 1 = sombra parada, 2 = sombra correndo. */
    private fun zed(c: Champ, ghost: Boolean, mode: Int) {
        b.dq = 0.8f
        val ally = c.team == 0
        val dashing = !ghost && c.dashT > 0f
        val ga = if (ghost) 0.5f else if (dashing) 0.35f else 1f
        val suit = if (ghost) cc(0xFF0B0B12) else if (ally) cc(0xFF1D1E2B) else cc(0xFF241A35)
        val suitL = if (ghost) cc(0xFF15151E) else if (ally) cc(0xFF34364A) else cc(0xFF3A2C52)
        val steel = if (ghost) cc(0xFF20202B) else cc(0xFF8E93A8)
        val red = if (ghost) cc(0xFF8A1620) else if (ally) cc(0xFFFF3D3D) else cc(0xFFC05CFF)
        val redD = if (ghost) cc(0xFF4A0A10) else if (ally) cc(0xFFB71C1C) else cc(0xFF7B2BB8)
        val face = if (ghost) cc(0xFF0E0E16) else cc(0xFF2A2A3A)
        val amp = when (mode) { 0 -> (c.mv / 0.1f).coerceIn(0f, 1f); 1 -> 0f; else -> 1f }
        val ph = if (mode == 2) t * 16f else c.walk * 1.1f
        val sw = sin(ph) * amp
        val bob = abs(sin(ph)) * 2.2f * amp + sin(t * 2f) * 0.6f

        val casting = c.castT > 0f
        val cp = if (casting) (1f - c.castT / c.castMax).coerceIn(0f, 1f) else 1f
        val cs = if (casting) c.castS else -1
        val sp = if (!ghost && c.swing > 0f && !casting) (1f - c.swing / c.swingMax).coerceIn(0f, 1f) else -1f
        val pi = Math.PI.toFloat()
        var armR = -sw * 30f
        var armL = sw * 30f
        var elbR = 12f
        var elbL = 12f
        var spR = 6f
        var spL = 6f
        var lean = amp * 6f
        var twist = 0f
        var crouch = 0f
        var spin = 0f
        val dk = if (dashing) 1f else 0f
        if (dashing) {
            armR = -70f; armL = -70f; elbR = 20f; elbL = 20f; lean = 56f; crouch = 3f; spR = 10f; spL = 10f
        } else if (cs >= 0) {
            when (cs) {
                0 -> {
                    val wnd = smooth(cp / 0.3f)
                    val rel = smooth((cp - 0.3f) / 0.18f)
                    val set = smooth((cp - 0.55f) / 0.45f)
                    val rest = 1f - set
                    twist = (-34f * wnd * (1f - rel) + 24f * rel) * rest
                    armR = (-80f * wnd + 190f * rel) * rest
                    armL = (-50f * wnd + 120f * rel) * rest
                    elbR = 20f * (1f - rel) + 8f * rel
                    lean = 6f + 14f * rel * rest
                    crouch = 4f * wnd * (1f - rel)
                }

                1 -> {
                    val th = smooth(cp / 0.3f) * (1f - smooth((cp - 0.6f) / 0.4f))
                    armR = 125f * th
                    armL = -40f * th
                    twist = 18f * th
                    lean = amp * 6f + 12f * th
                    crouch = 3f * th
                }
                2 -> {
                    val a = smooth(cp / 0.85f)
                    val op = sin(pi * cp.coerceIn(0f, 1f))
                    spin = a * 720f
                    crouch = 8f * op
                    armR = 0f; armL = 0f; elbR = 0f; elbL = 0f
                    spR = 85f * op; spL = 85f * op
                    lean = 8f * op
                }
                3 -> {
                    val a1 = smooth(cp / 0.3f)
                    val a2 = smooth((cp - 0.22f) / 0.3f)
                    val rl = 1f - smooth((cp - 0.75f) / 0.25f)
                    armR = 150f * a1 * rl
                    armL = 150f * a2 * rl
                    elbR = 6f; elbL = 6f
                    twist = (22f * a1 - 44f * a2) * rl
                    lean = 10f + 12f * rl
                    crouch = 5f * rl
                }
                else -> {
                    val op = sin(pi * cp.coerceIn(0f, 1f))
                    spin = smooth(cp / 0.7f) * 360f
                    crouch = 5f * op

                    armR = 40f * op; armL = 40f * op; spR = 60f * op; spL = 60f * op
                }
            }
        } else if (sp >= 0f) {
            val fwd = smooth(sp / 0.5f)
            val back = smooth((sp - 0.65f) / 0.35f)
            val a = (-70f + 190f * fwd) * (1f - back)
            val tw = (-18f + 38f * fwd) * (1f - back)
            if (c.swingN % 2 == 0) { armR = a; elbR = 10f; twist = tw; armL = 20f }
            else { armL = a; elbL = 10f; twist = -tw; armR = 20f }
            lean = 4f + 10f * fwd * (1f - back)
        }

        b.push()
        if (spin != 0f) b.rotY(spin)
        b.mode = 2
        b.disc(0f, 0.9f, 0f, 26f, red, 0.10f * ga, false, 20)
        if (dashing) {
            for (i in 0 until 7) {
                b.sphere(-14f - i * 17f, 30f + sin(t * 30f + i) * 4f, 0f, 16f - i * 1.6f, 7f, 9f, red, 0.35f * (1f - i / 7f), false, 6, 4)
            }
        }
        b.mode = 0

        // pernas
        for (s in intArrayOf(-1, 1)) {
            val swing = s * sw
            val back = max(0f, -swing)
            b.push()
            b.tr(0f, 27.5f - crouch, s * 4.4f)
            b.rotZ(swing * 32f - dk * (30f + s * 14f) + crouch * 2.5f)
            zl(11f, 3.6f, 3.0f, suit, ga)

            b.sphere(0f, -11f, 0f, 3.1f, 3.1f, 3.1f, suitL, ga, true, 8, 5)
            b.tr(0f, -11f, 0f)
            b.rotZ(-back * 40f - 3f - crouch * 4f - dk * 20f)
            zl(10f, 3.0f, 2.5f, suitL, ga)
            b.cyl(0f, -9.6f, 0f, 2.9f, 3.2f, 5.2f, steel, 10, ga)
            b.cyl(0f, -9.2f, 0f, 3.1f, 3.1f, 1.2f, red, 10, ga)
            b.sphere(2.6f, -10.2f, 0f, 5.4f, 2.4f, 3.2f, suit, ga, true, 8, 5)
            b.sphere(6.6f, -10.5f, 0f, 2.4f, 1.8f, 2.4f, suitL, ga, true, 6, 4)
            b.pop()
        }

        // corpo
        b.push()
        b.tr(0f, 27f + bob - crouch * 0.6f, 0f)
        b.rotZ(-lean)
        b.rotY(twist)
        b.tr(0f, -27f, 0f)
        b.cyl(0f, 23f, 0f, 7.4f, 6.6f, 5.4f, suit, 12, ga)
        b.cyl(0f, 27.4f, 0f, 7.0f, 7.0f, 2.0f, red, 12, ga)
        b.sphere(6.9f, 28.4f, 0f, 1.8f, 1.8f, 1.8f, steel, ga, true, 6, 4)
        for (sd in intArrayOf(-1, 1)) {
            b.push()
            b.tr(-6.2f, 27f, sd * 2.6f)
            b.rotZ(-(18f + amp * 30f) + sin(t * 3f + sd) * 5f)
            b.box(0f, -8f, 0f, 1.4f, 16f, 4.2f, red, ga, true)
            b.pop()
        }
        b.push()
        b.tr(6.4f, 27f, 0f)
        b.rotZ(-(amp * 14f) - 4f + sin(t * 3f) * 3f)
        b.box(0f, -6.5f, 0f, 1.4f, 13f, 6f, suitL, ga, true)
        b.pop()

        b.cyl(0f, 29f, 0f, 7.0f, 7.8f, 12f, suit, 14, ga)
        b.sphere(3.8f, 37.5f, 0f, 4.3f, 5.4f, 7.2f, suitL, ga, true, 10, 6)
        b.box(7.4f, 35f, 0f, 0.9f, 10f, 1.6f, red, ga, false)
        b.cyl(0f, 40.2f, 0f, 5.6f, 4.2f, 3.4f, suit, 12, ga)
        b.cyl(0f, 40.8f, 0f, 5.8f, 5.8f, 0.9f, red, 12, ga)
        for (s in intArrayOf(-1, 1)) {
            b.sphere(0f, 40.6f, s * 9.0f, 4.4f, 3.4f, 4.4f, steel, ga, true, 10, 6)
            b.sphere(0f, 42.6f, s * 9.0f, 3.2f, 1.2f, 3.4f, redD, ga, true, 8, 4)
        }
        // shurikens nas costas
        for (s in intArrayOf(-1, 1)) {
            b.push()
            b.tr(-7.6f, 36f + s * 2.5f, s * 2.8f)
            b.rotZ(90f)
            shuriken(if (s > 0) 8f else 9.5f, t * 40f * s, suitL, red, ga)
            b.pop()
        }
        // cabeça
        b.push()
        b.tr(0.8f, 47.5f, 0f)
        b.rotZ(lean * 0.5f)
        b.sphere(0f, 0f, 0f, 5.4f, 6.0f, 5.2f, face, ga, true, 12, 8)
        b.sphere(2.6f, -0.6f, 0f, 3.2f, 4.6f, 4.8f, steel, ga, true, 10, 6)
        b.sphere(-1.2f, 2.0f, 0f, 5.0f, 4.6f, 5.0f, suit, ga, true, 10, 6)
        b.mode = 2
        for (s in intArrayOf(-1, 1)) {
            b.box(5.6f, 1.0f, s * 2.2f, 1.4f, 1.0f, 2.8f, red, 0.95f * ga, false)
            b.sphere(5.8f, 1.0f, s * 2.2f, 2.4f, 1.8f, 3.2f, red, 0.25f * ga, false, 6, 4)
        }
        b.mode = 0
        b.push()
        b.tr(-4f, 2.6f, 0f)

        b.rotZ(-(112f - amp * 22f))
        zstrand(7, 2.3f, 0.7f, 5.4f, suit, red, 5f, -4f, ga)
        b.pop()
        b.pop()
        // braços com lâminas
        for (s in intArrayOf(-1, 1)) {
            val aA = if (s > 0) armR else armL
            val eA = if (s > 0) elbR else elbL
            val spr = if (s > 0) spR else spL
            b.push()
            b.tr(0f, 40f, s * 8.8f)
            b.rotX(-s * spr)
            b.rotZ(aA)
            zl(10f, 2.9f, 2.4f, suit, ga)
            b.sphere(0f, -10f, 0f, 2.6f, 2.6f, 2.6f, suitL, ga, true, 8, 5)
            b.tr(0f, -10f, 0f)
            b.rotZ(eA)
            zl(8.5f, 2.4f, 2.0f, suitL, ga)
            b.cyl(0f, -8.6f, 0f, 2.7f, 2.4f, 4.4f, steel, 10, ga)
            b.sphere(0f, -9.8f, 0f, 2.3f, 2.3f, 2.3f, suit, ga, true, 8, 5)
            b.sphere(0f, -19.5f, 0f, 0.9f, 9.2f, 0.4f, steel, ga, true, 6, 5)
            b.mode = 2
            b.sphere(0f, -19.5f, 0f, 1.5f, 9.8f, 0.8f, red, 0.35f * ga, false, 6, 5)
            b.mode = 0
            if (s > 0 && cs == 0 && cp < 0.36f) {
                b.push()
                b.tr(0f, -12f, 0f)
                shuriken(12f, t * 900f, suitL, red, 1f)
                b.pop()
            }
            b.pop()
        }

        // arco do corte básico
        if (sp >= 0.22f && sp <= 0.85f) {
            val kk = (sp - 0.22f) / 0.63f
            val even = c.swingN % 2 == 0
            b.mode = 2
            b.push()
            b.tr(0f, 38f, 0f)
            b.rotX(if (even) -28f else 28f)
            for (j in 0..10) {
                val f = j / 10f
                val an = (110f - 150f * f * min(1f, kk * 1.6f)) * 0.0174533f
                val alp = 0.85f * (1f - kk) * (0.3f + 0.7f * f)
                b.sphere(cos(an) * 25f, sin(an) * 25f, 0f, 3.8f, 3.8f, 1.1f, red, alp, false, 6, 4)
                b.sphere(cos(an) * 25f, sin(an) * 25f, 0f, 2.2f, 2.2f, 0.8f, white, alp, false, 5, 3)
            }
            b.pop()
            b.mode = 0
        }
        b.pop()
        b.pop()
        b.dq = 1f
    }

    private fun zedShadows(c: Champ) {
        if (c.shT > 0f) {
            val fa = if (c.shMoving) atan2(c.shTY - c.shY, c.shTX - c.shX) else c.face
            shadowAt(c, c.shX, c.shY, if (c.shMoving) 2 else 1, fa)
        }
        if (c.rsT > 0f) {
            val gy = groundH(c.rsX, c.rsY) + 1.6f
            shadowAt(c, c.rsX, c.rsY, 1, c.face)
            b.mode = 2

            val k = (c.rsT / 4.5f).coerceIn(0f, 1f)
            b.ring(c.rsX, gy, c.rsY, 44f * k + 6f, 44f * k + 9f, cc(0xFFFF1744), 0.8f, false, 28)
            b.mode = 0
        }
    }

    private fun shadowAt(c: Champ, x: Float, y: Float, mode: Int, fa: Float) {
        val gy = groundH(x, y)
        b.push()
        b.tr(x, gy, y)
        b.rotY(-Math.toDegrees(fa.toDouble()).toFloat())
        b.sc(1.35f, 1.35f, 1.35f)
        zed(c, true, mode)
        b.pop()
        b.mode = 2
        b.ring(x, gy + 1.4f, y, 30f, 34f, cc(0xFFFF2A3A), 0.35f + 0.25f * sin(t * 6f), false, 24)
        b.mode = 0
    }

    private fun deathMark(u: U) {
        val gy = groundH(u.x, u.y) + 118f
        val k = (u.deathT / 3f).coerceIn(0f, 1f)
        val rr = cc(0xFFFF1744)
        b.mode = 2
        b.push()
        b.tr(u.x, gy, u.y)
        b.rotY(t * 120f)
        b.ring(0f, 0f, 0f, 24f, 28f, rr, 0.9f, false, 26)
        b.ring(0f, 0f, 0f, 5f + 18f * k, 7f + 18f * k, white, 0.9f, false, 22)
        for (i in 0 until 6) {
            b.push()
            b.rotY(i * 60f)

            b.box(26f, 0f, 0f, 10f, 1.4f, 3f, rr, 0.9f, false)
            b.pop()
        }
        b.pop()
        b.cyl(u.x, gy - 28f, u.y, 0f, 7f, 22f, rr, 6, 0.9f, false)
        b.mode = 0
    }

    // ------------------------------------------------------------------ GAREN

    private fun garen(c: Champ) {
        b.dq = 0.8f
        val ally = c.team == 0
        val armor = if (ally) cc(0xFF2E5EC4) else cc(0xFF9A2E3C)
        val armorD = if (ally) cc(0xFF1D3D8A) else cc(0xFF65202A)
        val steel = cc(0xFFC3CCDB)
        val steelD = cc(0xFF8D98AD)
        val gold = if (ally) cc(0xFFFFD54F) else cc(0xFFE8C06A)
        val goldD = cc(0xFFC9972B)
        val cape = if (ally) cc(0xFF24459E) else cc(0xFF6E1F2A)
        val skin = cc(0xFFF1C6A2)
        val skinD = cc(0xFFD9A57F)
        val hair = cc(0xFF5A3A22)
        val glow = if (ally) cc(0xFFFFF3B0) else cc(0xFFFFB3B3)
        val amp = (c.mv / 0.1f).coerceIn(0f, 1f)
        val ph = c.walk * 1.05f
        val sw = sin(ph) * amp
        val bob = abs(sin(ph)) * 2.0f * amp + sin(t * 1.8f) * 0.5f
        val casting = c.castT > 0f
        val cp = if (casting) (1f - c.castT / c.castMax).coerceIn(0f, 1f) else 1f
        val cs = if (casting) c.castS else -1
        val spinning = c.spinT > 0f

        val sp = if (c.swing > 0f && !casting && !spinning) (1f - c.swing / c.swingMax).coerceIn(0f, 1f) else -1f
        val big = c.swingBig
        val even = c.swingN % 2 == 0
        val idleA = 24f - sw * 12f
        val idleFt = if (c.qT > 0f) -38f else 22f
        var armA = idleA
        var elbA = 26f
        var ft = idleFt
        var armL = sw * 26f
        var elbL = 12f
        var spL = 8f
        var lean = amp * 5f
        var twist = 0f
        var crouch = 0f
        var spinA = 0f
        var sparkle = if (c.qT > 0f) 1f else 0f
        if (spinning || cs == 2) {
            val ramp = if (spinning) 1f else smooth(cp / 0.9f)
            spinA = if (spinning) (t * 1100f) % 360f else ramp * 540f
            armA = 88f; elbA = 0f; ft = 90f
            armL = 88f; elbL = 0f; spL = 60f
            lean = 12f * ramp
            crouch = 5f * ramp
            sparkle = 1f
        } else if (cs >= 0) {
            when (cs) {
                0 -> {
                    val h = smooth(cp / 0.25f) * (1f - smooth((cp - 0.7f) / 0.3f))
                    lean = amp * 5f + 20f * h
                    crouch = 3f * h
                    armA = idleA + (-70f - idleA) * h
                    ft = idleFt + (-105f - idleFt) * h

                    armL = armL + (-40f - armL) * h
                    sparkle = 1f
                }
                1 -> {
                    val up = smooth(cp / 0.35f)
                    val pln = smooth((cp - 0.45f) / 0.2f)
                    val rec = smooth((cp - 0.82f) / 0.18f)
                    armA = (idleA + (165f - idleA) * up - 105f * pln) * (1f - rec) + idleA * rec
                    ft = (idleFt * (1f - up) + 170f * pln) * (1f - rec) + idleFt * rec
                    armL = 70f * pln * (1f - rec)
                    elbL = 30f * pln
                    crouch = 5f * pln * (1f - rec)
                    lean = 8f * pln * (1f - rec)
                    sparkle = 1f
                }
                3 -> {
                    val rise = smooth(cp / 0.5f)
                    val slam = smooth((cp - 0.5f) / 0.14f)
                    val rec = smooth((cp - 0.78f) / 0.22f)
                    val rl = 1f - rec
                    armA = (idleA + (175f - idleA) * rise - 120f * slam) * rl + idleA * rec
                    ft = (idleFt * (1f - rise) - 5f * rise + 150f * slam) * rl + idleFt * rec
                    armL = (160f * rise - 110f * slam) * rl
                    elbL = 10f
                    lean = (-8f * rise + 36f * slam) * rl
                    crouch = 6f * slam * rl
                    spL = 14f
                    sparkle = 1f
                }
            }
        } else if (sp >= 0f) {
            val wind = smooth(sp / 0.28f)

            val strike = smooth((sp - 0.28f) / 0.22f)
            val rec = smooth((sp - 0.62f) / 0.38f)
            val rl = 1f - rec
            if (even) {
                armA = (idleA + 130f * wind - 105f * strike) * rl + idleA * rec
                ft = ((idleFt - 85f * wind) + 210f * strike) * rl + idleFt * rec
                twist = (-10f * wind + 20f * strike) * rl
                lean = (4f + 12f * strike) * rl
            } else {
                armA = (idleA + 60f * wind) * rl + idleA * rec
                ft = ((idleFt - 70f * wind) + 190f * strike) * rl + idleFt * rec
                twist = (-45f * wind + 95f * strike) * rl
                lean = (6f + 6f * strike) * rl
            }
            armL = armL - 20f * wind * rl
            if (big) { crouch = 3f * wind * rl; sparkle = 1f }
        }

        b.push()
        if (spinA != 0f) b.rotY(spinA)
        if (sparkle > 0f || c.wT > 0f) {
            b.mode = 2
            b.disc(0f, 0.9f, 0f, 34f, glow, 0.10f, false, 22)
            b.mode = 0
        }
        // pernas
        for (s in intArrayOf(-1, 1)) {
            val swing = s * sw
            val back = max(0f, -swing)
            b.push()
            b.tr(0f, 27.5f - crouch, s * 5.2f)
            b.rotZ(swing * 28f + crouch * 2.5f)

            limb(11f, 4.4f, 3.7f, K(armorD), 12)
            b.sphere(0f, -11f, 0f, 3.9f, 3.9f, 3.9f, K(steelD), 1f, true, 10, 6)
            b.tr(0f, -11f, 0f)
            b.rotZ(-back * 36f - 3f - crouch * 4f)
            limb(10f, 3.7f, 3.2f, K(steel), 12)
            b.cyl(0f, -9f, 0f, 3.5f, 3.8f, 2f, K(gold), 12)
            b.sphere(3.2f, -10.6f, 0f, 5.8f, 2.8f, 3.9f, K(armorD), 1f, true, 10, 6)
            b.sphere(7.5f, -10.8f, 0f, 2.6f, 2.2f, 3.0f, K(steel), 1f, true, 8, 5)
            b.pop()
        }

        // corpo
        b.push()
        b.tr(0f, 27f + bob - crouch * 0.6f, 0f)
        b.rotZ(-lean)
        b.rotY(twist)
        b.tr(0f, -27f, 0f)
        b.cyl(0f, 21f, 0f, 9.4f, 8.6f, 7f, K(armorD), 14)
        for (i in 0 until 9) {
            val fl2 = amp * 7f * abs(sin(ph + i * 0.6f)) + sin(t * 1.8f + i) * 1.2f
            b.push()
            b.rotY(i * 40f + 8f)
            b.tr(7.6f, 28.4f, 0f)
            b.rotZ(18f + fl2)
            b.sphere(0.6f, -6f, 0f, 1.5f, 7.2f, 3.6f, K(if (i % 2 == 0) armor else steel), 1f, true, 8, 5)
            b.sphere(0.9f, -12.4f, 0f, 1.6f, 1.6f, 3.2f, K(gold), 1f, true, 6, 4)
            b.pop()
        }
        b.cyl(0f, 27.6f, 0f, 8.6f, 8.0f, 2.8f, K(gold), 14)
        b.sphere(8.2f, 29f, 0f, 2.4f, 2.4f, 2.4f, K(steel), 1f, true, 8, 5)
        b.cyl(0f, 29.5f, 0f, 8.2f, 10.0f, 12.5f, K(steel), 16)
        b.sphere(5.2f, 36.2f, 0f, 4.4f, 5.6f, 8.6f, K(armor), 1f, true, 10, 6)

        for (s in intArrayOf(-1, 1)) {
            b.push()
            b.tr(8.6f, 37.4f, s * 3.4f)
            b.rotX(-s * 28f)
            b.sphere(0f, 0f, 0f, 1.0f, 1.6f, 4.2f, K(gold), 1f, true, 6, 4)
            b.pop()
        }
        b.sphere(9.2f, 36f, 0f, 1.5f, 3.2f, 1.5f, K(goldD), 1f, true, 6, 4)
        b.cyl(0f, 41.2f, 0f, 7.6f, 5.2f, 3.4f, K(steelD), 14)
        for (s in intArrayOf(-1, 1)) {
            b.sphere(0f, 42.2f, s * 11.6f, 6.2f, 4.6f, 6.4f, K(steel), 1f, true, 12, 7)
            b.sphere(0f, 44.2f, s * 11.8f, 5.2f, 2.0f, 5.4f, K(armor), 1f, true, 10, 5)
            b.sphere(0f, 42.4f, s * 11.6f, 6.4f, 0.9f, 6.6f, K(gold), 1f, true, 10, 4)
            b.sphere(0f, 47.2f, s * 13.4f, 1.2f, 3.4f, 1.2f, K(gold), 1f, true, 6, 4)
        }
        // capa
        b.push()
        b.tr(-7.6f, 42f, 0f)
        for (i in 0 until 4) {
            val sway = sin(t * 2.6f + i * 0.7f) * 3f
            b.rotZ(-(7f + amp * 13f) + sway)
            b.box(-0.8f, -4.8f, 0f, 1.5f, 9.8f, 17f - i * 1.5f, K(cape), 1f, true)
            b.tr(0f, -9.3f, 0f)
        }
        b.pop()
        // cabeça
        b.push()
        b.tr(1f, 49f, 0f)
        b.rotZ(lean * 0.45f)
        b.sphere(0f, 0f, 0f, 5.4f, 6.0f, 5.2f, K(skin), 1f, true, 12, 8)
        b.sphere(2.6f, -3.2f, 0f, 3.2f, 2.4f, 4.0f, K(skinD), 1f, true, 8, 5)
        b.sphere(-0.8f, 2.4f, 0f, 5.8f, 4.4f, 5.6f, K(hair), 1f, true, 10, 6)

        b.sphere(3.4f, 4.2f, 0f, 3.0f, 2.2f, 4.6f, K(hair), 1f, true, 8, 5)
        for (s in intArrayOf(-1, 1)) {
            b.sphere(5.1f, 0.8f, s * 2.1f, 0.9f, 1.2f, 1.2f, K(cc(0xFF263238)), 1f, false, 6, 4)
            b.box(5.0f, 2.4f, s * 2.1f, 0.8f, 0.7f, 2.6f, K(hair), 1f, false)
        }
        b.sphere(5.6f, -0.8f, 0f, 1.1f, 1.2f, 1.1f, K(skinD), 1f, true, 6, 4)
        b.pop()
        // braços e espada
        for (s in intArrayOf(-1, 1)) {
            val aA = if (s > 0) armA else armL
            val eA = if (s > 0) elbA else elbL
            val spr = if (s > 0) 7f else spL
            b.push()
            b.tr(0f, 41.6f, s * 11.8f)
            b.rotX(-s * spr)
            b.rotZ(aA)
            limb(9.4f, 3.5f, 3.0f, K(steel), 12)
            b.sphere(0f, -9.4f, 0f, 3.4f, 3.4f, 3.4f, K(armor), 1f, true, 10, 6)
            b.tr(0f, -9.4f, 0f)
            b.rotZ(eA)
            limb(8.6f, 3.0f, 2.8f, K(steelD), 12)
            b.cyl(0f, -9.2f, 0f, 3.5f, 3.2f, 3.6f, K(gold), 12)
            b.sphere(0f, -10.8f, 0f, 3.1f, 3.3f, 3.1f, K(steel), 1f, true, 10, 6)
            if (s > 0) {
                b.tr(0f, -10.8f, 0f)
                b.rotZ(-ft - aA - eA)
                b.cyl(0f, -6f, 0f, 1.5f, 1.5f, 10f, K(cc(0xFF4E342E)), 8)
                b.sphere(0f, -7f, 0f, 2.3f, 2.3f, 2.3f, K(gold), 1f, true, 8, 5)
                b.box(0f, 4.6f, 0f, 15f, 2.6f, 2.6f, K(gold), 1f, true)
                b.sphere(7.6f, 4.6f, 0f, 1.8f, 1.8f, 1.8f, K(goldD), 1f, true, 6, 4)
                b.sphere(-7.6f, 4.6f, 0f, 1.8f, 1.8f, 1.8f, K(goldD), 1f, true, 6, 4)
                b.box(0f, 38f, 0f, 7.2f, 66f, 1.7f, K(steel), 1f, true)

                b.box(0f, 36f, 0f, 2.0f, 58f, 2.0f, K(steelD), 1f, true)
                b.sphere(0f, 73.5f, 0f, 3.5f, 6.5f, 1.0f, K(steel), 1f, true, 6, 4)
                if (sparkle > 0f || c.wT > 0f) {
                    b.mode = 2
                    val gp = 0.28f + 0.1f * sin(t * 9f)
                    b.box(0f, 38f, 0f, 10.5f, 70f, 3.6f, glow, gp, false)
                    b.sphere(0f, 38f, 0f, 9f, 40f, 4f, glow, 0.10f, false, 8, 6)
                    b.mode = 0
                }
            }
            b.pop()
        }
        // rastro da espada
        if (sp >= 0.25f && sp <= 0.9f) {
            val kk = (sp - 0.25f) / 0.65f
            b.mode = 2
            b.push()
            b.tr(0f, 41.6f, 11.8f)
            b.rotX(if (even) 0f else 38f)
            val a0 = if (even) -65f else -50f
            val a1 = if (even) 150f else 140f
            val prog = min(1f, kk * 1.7f)
            for (j in 0..13) {
                val f = j / 13f
                val ang = a0 + (a1 - a0) * f * prog
                val rad = ang * 0.0174533f
                val alp = (0.7f * (1f - kk) * (0.25f + 0.75f * f) * (if (big) 1.3f else 1f)).coerceAtMost(1f)
                b.push()
                b.tr(sin(rad) * 70f, cos(rad) * 70f, 0f)
                b.rotZ(-ang)
                b.box(0f, 0f, 0f, 7.5f, 22f, 1.4f, if (big) glow else white, alp, false)
                b.pop()

            }
            b.pop()
            b.mode = 0
        }
        // barreira de coragem (W)
        if (c.wT > 0f) {
            val k = (c.wT / 2.4f).coerceIn(0f, 1f)
            b.mode = 2
            b.sphere(0f, 36f, 0f, 36f, 42f, 36f, glow, 0.14f + 0.05f * sin(t * 8f), false, 16, 10)
            b.ring(0f, 14f, 0f, 33f, 37f, glow, 0.5f * k, false, 28)
            for (kk in 0 until 6) {
                val a = -t * 2.2f + kk * 1.047f
                b.sphere(cos(a) * 36f, 36f + sin(t * 3f + kk) * 14f, sin(a) * 36f, 2.2f, 2.2f, 2.2f, white, 0.8f, false, 5, 3)
            }
            b.mode = 0
        }
        b.pop()
        b.pop()
        b.dq = 1f
    }

    /** Redemoinho do Julgamento (E), em escala de mundo. */
    private fun spinFx(c: Champ) {
        val gy = groundH(c.x, c.y)
        val inK = smooth((2.4f - c.spinT) / 0.15f)
        val outK = smooth(c.spinT / 0.25f)
        val fade = min(inK, outK)
        val ang = t * 1100f
        val gold = cc(0xFFFFE9A0)
        b.mode = 2
        b.push()
        b.tr(c.x, gy + 44f, c.y)

        for (i in 0 until 3) {
            for (j in 0 until 7) {
                b.push()
                b.rotY(-(ang + i * 120f - j * 10f))
                val a = 0.5f * (1f - j / 7f) * fade
                b.sphere(170f, 0f, 0f, 78f, 3.2f - j * 0.2f, 12f - j, if (j == 0) white else gold, a, false, 8, 4)
                b.pop()
            }
        }
        b.ring(0f, -3f, 0f, 120f, 252f, gold, 0.10f * fade, false, 36)
        b.ring(0f, -3f, 0f, 236f, 250f, white, 0.5f * fade, false, 36)
        b.ring(0f, -42f, 0f, 200f, 252f, gold, 0.18f * fade, false, 36)
        for (i in 0 until 10) {
            val aa = t * 7f + i * 0.628f
            val hh = (t * 120f + i * 31f) % 120f
            val rd = 150f + 40f * sin(i * 1.3f)
            b.sphere(cos(aa) * rd, -40f + hh, sin(aa) * rd, 3f, 3f, 3f, white, 0.5f * fade * (1f - hh / 120f), false, 5, 3)
        }
        b.pop()
        b.mode = 0
    }

    // ------------------------------------------------------ EFEITOS DE HABILIDADE

    private fun fx(f: Fx) {
        val k = (f.age / f.max).coerceIn(0f, 1f)
        val gy = groundH(f.x, f.y)
        val rr = cc(0xFFFF2A3A)
        val gold = cc(0xFFFFE9A0)
        when (f.kind) {
            0, 9 -> {
                b.mode = 0

                for (i in 0 until 9) {
                    val a = i * 0.7f
                    val rd = 8f + 46f * k
                    val h = 10f + 70f * k + (i % 3) * 8f
                    val sz = (14f + 10f * (i % 3)) * (1f - 0.4f * k)
                    b.sphere(f.x + cos(a) * rd, gy + h, f.y + sin(a) * rd, sz, sz, sz, cc(0xFF140A1E), 0.55f * (1f - k), false, 7, 5)
                }
                b.mode = 2
                b.ring(f.x, gy + 1.5f, f.y, 20f + 90f * k, 26f + 90f * k, rr, 0.8f * (1f - k), false, 30)
                b.cyl(f.x, gy, f.y, 22f * (1f - k) + 6f, 6f, 150f * (1f - k * 0.4f), rr, 10, 0.22f * (1f - k), false)
                for (i in 0 until 8) {
                    val a = i * 0.785f + t * 3f
                    val rd = 30f + 60f * k
                    b.sphere(f.x + cos(a) * rd, gy + 8f + 80f * k * ((i % 3) + 1) / 3f, f.y + sin(a) * rd, 2.8f, 2.8f, 2.8f, white, 0.8f * (1f - k), false, 5, 3)
                }
                b.mode = 0
            }
            1 -> {
                val e = 1f - (1f - k) * (1f - k)
                val rad = 235f
                b.mode = 2
                b.disc(f.x, gy + 1f, f.y, rad * e, rr, 0.10f * (1f - k), false, 36)
                b.ring(f.x, gy + 1.5f, f.y, rad * e - 10f, rad * e + 4f, rr, 0.85f * (1f - k), false, 44)
                b.ring(f.x, gy + 1.7f, f.y, rad * e * 0.7f, rad * e * 0.7f + 3f, white, 0.7f * (1f - k), false, 40)
                for (i in 0 until 8) {
                    b.push()
                    b.tr(f.x, gy + 34f, f.y)
                    b.rotY(i * 45f + e * 260f)
                    b.box(rad * e * 0.85f, 0f, 0f, 70f * (1f - k * 0.5f), 2.2f, 6f, if (i % 2 == 0) white else rr, 0.9f * (1f - k), false)
                    b.pop()
                }
                b.ring(f.x, gy + 34f, f.y, rad * e * 0.5f, rad * e, rr, 0.18f * (1f - k), false, 40)

                b.mode = 0
            }
            3 -> {
                val e = 1f - (1f - k) * (1f - k)
                val red = cc(0xFFFF1744)
                b.mode = 2
                for (i in 0 until 3) {
                    val kk = ((k * 1.4f) - i * 0.18f).coerceIn(0f, 1f)
                    b.ring(f.x, gy + 1.5f + i, f.y, 40f + 280f * kk, 50f + 285f * kk, red, 0.8f * (1f - kk), false, 40)
                }
                b.cyl(f.x, gy, f.y, 40f * (1f - k) + 10f, 4f, 600f * (1f - k * 0.6f), red, 12, 0.35f * (1f - k), false)
                b.cyl(f.x, gy, f.y, 20f * (1f - k) + 5f, 2f, 700f, white, 10, 0.5f * (1f - k), false)
                b.push()
                b.tr(f.x, gy + 60f, f.y)
                b.rotY(k * 200f)
                b.box(0f, 0f, 0f, 260f * e, 2f, 6f, red, 0.8f * (1f - k), false)
                b.box(0f, 0f, 0f, 6f, 2f, 260f * e, red, 0.8f * (1f - k), false)
                b.pop()
                b.sphere(f.x, gy + 50f, f.y, 70f * (1f - k), 70f * (1f - k), 70f * (1f - k), red, 0.4f * (1f - k), false, 12, 8)
                b.mode = 0
            }
            4 -> {
                val e = smooth(k / 0.35f)
                val fade = 1f - smooth((k - 0.4f) / 0.6f)
                b.mode = 2
                for (i in 0..1) {
                    val sg = if (i == 0) 1f else -1f
                    b.push()
                    b.tr(f.x, gy + 58f, f.y)
                    b.rotZ(38f * sg)
                    b.box(0f, 0f, 0f, 230f * e, 7f, 3f, rr, 0.9f * fade, false)
                    b.box(0f, 0f, 0f, 250f * e, 2.4f, 4f, white, fade, false)

                    b.pop()
                    b.push()
                    b.tr(f.x, gy + 40f, f.y)
                    b.rotY(38f * sg)
                    b.box(0f, 0f, 0f, 230f * e, 3f, 8f, rr, 0.7f * fade, false)
                    b.box(0f, 0f, 0f, 250f * e, 2f, 3f, white, 0.9f * fade, false)
                    b.pop()
                }
                b.sphere(f.x, gy + 58f, f.y, 46f * e * fade, 46f * e * fade, 46f * e * fade, rr, 0.35f * fade, false, 10, 7)
                b.ring(f.x, gy + 2f, f.y, 30f + 120f * e, 36f + 120f * e, rr, 0.7f * fade, false, 30)
                b.mode = 0
            }
            5 -> {
                val e = smooth(k / 0.3f)
                val fade = 1f - smooth((k - 0.35f) / 0.65f)
                b.mode = 2
                b.push()
                b.tr(f.x, gy + 50f, f.y)
                b.rotY(-Math.toDegrees(f.ang.toDouble()).toFloat())
                for (j in 0..11) {
                    val a = (-80f + 160f * j / 11f * e) * 0.0174533f
                    b.sphere(sin(a) * 70f, cos(a) * 70f - 24f, 0f, 9f, 9f, 3f, if (j > 8) white else gold, 0.8f * fade, false, 6, 4)
                }
                b.pop()
                b.ring(f.x, gy + 2f, f.y, 20f + 110f * e, 28f + 110f * e, gold, 0.8f * fade, false, 30)
                b.sphere(f.x, gy + 46f, f.y, 40f * e * fade, 40f * e * fade, 40f * e * fade, gold, 0.3f * fade, false, 10, 7)
                b.mode = 0
            }
            6 -> {
                b.mode = 2
                for (i in 0 until 3) {
                    val kk = (k * 1.3f - i * 0.2f).coerceIn(0f, 1f)

                    b.ring(f.x, gy + 2f + i * 22f * kk, f.y, 30f + 70f * kk, 36f + 70f * kk, if (i == 0) white else gold, 0.8f * (1f - kk), false, 30)
                }
                b.cyl(f.x, gy, f.y, 36f * (1f - k) + 8f, 8f, 130f * (0.4f + 0.6f * (1f - k)), gold, 12, 0.22f * (1f - k), false)
                for (i in 0 until 10) {
                    val a = i * 0.628f
                    val rd = 40f + 50f * k
                    val hh = 10f + 100f * k
                    b.sphere(f.x + cos(a) * rd, gy + hh * ((i % 3) + 1) / 3f, f.y + sin(a) * rd, 3f, 3f, 3f, white, 0.9f * (1f - k), false, 5, 3)
                }
                b.mode = 0
            }
            7 -> {
                b.mode = 2
                b.ring(f.x, gy + 2f, f.y, 120f + 140f * k, 130f + 140f * k, white, 0.8f * (1f - k), false, 40)
                b.ring(f.x, gy + 2.3f, f.y, 90f + 120f * k, 98f + 120f * k, gold, 0.6f * (1f - k), false, 36)
                b.mode = 0
            }
            8 -> justiceFx(f, gy)
        }
    }

    private fun justiceFx(f: Fx, gy: Float) {
        val a = f.age
        val gold = cc(0xFFFFE9A0)
        val fall = (a / 0.7f).coerceIn(0f, 1f)
        val hh = 900f * (1f - fall * fall)
        val after = ((a - 0.7f) / 0.9f).coerceIn(0f, 1f)
        b.mode = 2
        if (a < 0.7f) {
            val rw = 260f - 170f * fall
            b.disc(f.x, gy + 1.2f, f.y, rw, gold, 0.10f + 0.12f * fall, false, 36)
            b.ring(f.x, gy + 1.6f, f.y, rw - 8f, rw, white, 0.9f, false, 40)
            b.ring(f.x, gy + 1.7f, f.y, rw * 0.55f, rw * 0.55f + 4f, gold, 0.7f, false, 36)
            for (i in 0 until 8) {
                b.push()
                b.tr(f.x, gy + 1.8f, f.y)
                b.rotY(i * 45f + t * 90f)
                b.box(rw * 0.78f, 0f, 0f, 22f, 0.5f, 3f, white, 0.8f, false)
                b.pop()
            }
            b.cyl(f.x, gy, f.y, 40f, 40f, 1100f, gold, 12, 0.08f + 0.10f * fall, false)
        }
        b.mode = 0
        if (after < 1f) {
            val alpha = if (a < 0.7f) 1f else (1f - after).coerceIn(0f, 1f)
            b.push()
            b.tr(f.x, gy + hh, f.y)
            b.cyl(0f, 0f, 0f, 0f, 24f, 70f, cc(0xFFEAF2FF), 4, alpha, true)
            b.box(0f, 200f, 0f, 46f, 260f, 12f, cc(0xFFDDE8FA), alpha, true)
            b.box(0f, 200f, 0f, 14f, 250f, 14f, white, alpha, true)
            b.box(0f, 338f, 0f, 150f, 16f, 22f, cc(0xFFFFD54F), alpha, true)
            b.cyl(0f, 346f, 0f, 9f, 9f, 100f, cc(0xFF8D6E63), 8, alpha, true)
            b.sphere(0f, 452f, 0f, 16f, 16f, 16f, cc(0xFFFFD54F), alpha, true, 8, 6)
            b.mode = 2
            b.box(0f, 200f, 0f, 70f, 280f, 30f, gold, 0.25f * alpha, false)
            b.sphere(0f, 120f, 0f, 60f, 130f, 60f, white, 0.18f * alpha, false, 10, 8)
            b.mode = 0
            b.pop()
        }
        if (a >= 0.7f) {
            val e = after
            b.mode = 2
            for (i in 0 until 3) {
                val kk = (e * 1.2f - i * 0.16f).coerceIn(0f, 1f)
                b.ring(f.x, gy + 2f + i, f.y, 40f + 300f * kk, 52f + 300f * kk, if (i == 0) white else gold, 0.9f * (1f - kk), false, 44)
            }
            b.cyl(f.x, gy, f.y, 50f * (1f - e) + 10f, 8f, 700f * (1f - e * 0.5f), gold, 14, 0.4f * (1f - e), false)
            b.cyl(f.x, gy, f.y, 20f * (1f - e) + 4f, 2f, 800f, white, 10, 0.6f * (1f - e), false)
            for (i in 0 until 14) {
                val ang = i * 0.449f
                val rd = 30f + 200f * e
                val hy = 20f + 150f * e * (0.4f + (i % 4) * 0.2f)
                b.sphere(f.x + cos(ang) * rd, gy + hy, f.y + sin(ang) * rd, 4f, 4f, 4f, white, 0.9f * (1f - e), false, 5, 3)
            }
            b.mode = 0
        }
    }

    private fun aimNew(pl: Champ) {
        val fill = if (pl.hero == 2) cc(0xFFFF4D4D) else cc(0xFFFFD54F)
        val edge = if (pl.hero == 2) cc(0xFFB71C1C) else cc(0xFFC97A00)
        val tip = cc(0xFFFFF0E0)
        for (i in 0..3) {
            if (g.bId[i] == -1 || !g.bDrag[i]) continue
            val ang = atan2(g.bAy[i], g.bAx[i])
            val gy = groundH(pl.x, pl.y) + 1.8f
            val dist = (sqrt(g.bAx[i] * g.bAx[i] + g.bAy[i] * g.bAy[i]) / g.d * 3.2f).coerceIn(150f, 780f)
            var kind = 1
            var rad = 0f
            var len = 0f
            var wd = 0f
            if (pl.hero == 2) {
                when (i) {
                    0 -> { kind = 0; len = 900f; wd = 72f }
                    1 -> { kind = 3; len = dist.coerceIn(280f, 650f) }
                    2 -> { kind = 1; rad = 235f }
                    else -> { kind = 1; rad = 700f }
                }
            } else {
                when (i) {
                    0 -> { kind = 1; rad = 70f }
                    1 -> { kind = 1; rad = 75f }
                    2 -> { kind = 1; rad = 250f }
                    else -> { kind = 1; rad = 560f }
                }
            }
            b.push()
            b.tr(pl.x, gy, pl.y)
            b.mode = 0
            if (kind == 1) {
                val pr = 1f + 0.03f * sin(t * 7f)
                b.disc(0f, 0f, 0f, rad * pr, fill, 0.14f, false, 40)
                b.ring(0f, 0.1f, 0f, rad * pr - 4f, rad * pr, edge, 0.8f, false, 48)
                for (j in 0 until 10) {
                    b.push()
                    b.rotY(j * 36f + t * 20f)
                    b.box(rad * 0.9f, 0.2f, 0f, 18f, 0.3f, 2.6f, tip, 0.6f, false)
                    b.pop()
                }
            } else {
                b.rotY(-Math.toDegrees(ang.toDouble()).toFloat())
                if (kind == 0) {
                    val n = 8
                    val sl = len / n
                    for (k in 0 until n) {
                        val al = 0.32f * (1f - 0.78f * k / n)
                        b.box(sl * (k + 0.5f), 0f, 0f, sl + 0.5f, 0.3f, wd, fill, al, false)
                    }
                    b.box(len / 2f, 0.2f, wd / 2f, len, 0.3f, 1.6f, edge, 0.7f, false)
                    b.box(len / 2f, 0.2f, -wd / 2f, len, 0.3f, 1.6f, edge, 0.7f, false)
                    b.box(len, 0.2f, 0f, 1.8f, 0.3f, wd + 1.6f, edge, 0.7f, false)
                    val cn = (len / 130f).toInt().coerceAtLeast(3)
                    for (j in 0 until cn) {
                        val px = (t * 360f + j * len / cn) % len
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
                } else {
                    val dn = (len / 40f).toInt()
                    for (j in 1 until dn) b.box(j * 40f, 0.1f, 0f, 9f, 0.3f, 2.4f, edge, 0.55f, false)
                    b.disc(len, 0f, 0f, 70f, fill, 0.2f, false, 28)
                    b.ring(len, 0.2f, 0f, 66f, 70f, edge, 0.8f, false, 32)
                }
            }
            b.pop()
        }
    }

    private fun midSeal() {
        val mx = MAP_W / 2f
        val my = groundH(mx, 640f) + 2.4f
        val pu = 0.5f + 0.5f * sin(t * 1.6f)
        val ice = cc(0xFFBFE6FF)
        b.mode = 2
        b.disc(mx, my, 640f, 140f, cc(0xFF9FD8FF), 0.07f + 0.05f * pu, false, 36)
        b.ring(mx, my, 640f, 150f, 158f, ice, 0.55f, false, 48)
        b.ring(mx, my + 0.1f, 640f, 96f, 100f, white, 0.5f, false, 40)
        for (i in 0 until 12) {
            b.push()
            b.tr(mx, my + 0.2f, 640f)
            b.rotY(i * 30f + t * 18f)
            b.box(124f, 0f, 0f, 26f, 0.5f, 3.6f, ice, 0.7f, false)
            b.pop()
        }
        for (i in 0 until 4) {
            val a = t * 0.6f + i * 1.5708f
            b.cyl(mx + cos(a) * 128f, my, 640f + sin(a) * 128f, 5f, 2f, 90f + 30f * pu, if (i % 2 == 0) cc(0xFF4FC3F7) else cc(0xFFFF6E5C), 6, 0.35f, false)
        }
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
        val ck0 = (c.castT / c.castMax).coerceIn(0f, 1f)
        val ck = ck0 * ck0 * (3f - 2f * ck0)
        val ak = ((c.atkCd - 0.45f) / 0.25f).coerceIn(0f, 1f)
        val casting = c.castT > 0f
        val cp = if (casting) (1f - c.castT / c.castMax).coerceIn(0f, 1f) else 1f
        fun sm(x: Float): Float { val q = x.coerceIn(0f, 1f); return q * q * (3f - 2f * q) }
        val cin = sm(cp / 0.22f)
        val cout = 1f - sm((cp - 0.8f) / 0.2f)
        val hold = cin * cout
        val act = if (casting && c.castS == 3) max(ak, 0.35f + 0.65f * sm(cp / 0.5f) * cout) else max(ck, ak)
        // pose do braço do cajado por skill (Q estocada, W erguer, E arremesso, R carga e disparo)
        var pArm = act * 74f
        var pElb = act * 14f
        var pStf = -56f * act
        if (casting) {
            when (c.castS) {
                0 -> { val th = sm((cp - 0.12f) / 0.2f) * cout; pArm = 84f * th; pElb = 8f * th; pStf = -78f * th }
                1 -> { pArm = 150f * hold; pElb = 6f * hold; pStf = 10f * hold }
                2 -> { val th = sm((cp - 0.3f) / 0.16f) * cout; pArm = 125f * hold; pElb = 10f * hold; pStf = 6f * hold - 74f * th }
                else -> {
                    val rs = sm(cp / 0.45f)
                    val sl = sm((cp - 0.5f) / 0.1f)
                    pArm = (150f * rs - 66f * sl) * cout
                    pElb = 8f * rs * cout
                    pStf = (14f * rs - 70f * sl) * cout
                }
            }
        }

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
        if (casting && c.castS == 1) {
            val wr = 30f + 190f * cp
            b.ring(0f, 1.5f, 0f, wr, wr + 5f, white, 0.8f * (1f - cp), false, 40)
            b.ring(0f, 1.6f, 0f, wr * 0.7f, wr * 0.7f + 3f, glow, 0.6f * (1f - cp), false, 36)
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
            val armA = 10f + sw * 16f + pArm
            val elbow = -(12f + pElb)
            val staffW = -14f + pStf
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

        // círculo rúnico girando na base, faíscas subindo e halo no topo
        b.mode = 2
        b.push()
        b.tr(0f, 1.6f, 0f)
        b.rotY(t * 18f)
        b.ring(0f, 0f, 0f, 78f, 82f, team, 0.55f, false, 40)
        b.ring(0f, 0.1f, 0f, 64f, 66f, teamL, 0.4f, false, 36)
        for (i in 0 until 8) {
            b.push()
            b.rotY(i * 45f)
            b.box(72f, 0f, 0f, 14f, 0.5f, 2.6f, teamL, 0.7f, false)
            b.box(88f, 0f, 0f, 3f, 0.5f, 7f, team, 0.6f, false)
            b.pop()
        }
        b.pop()
        for (i in 0 until 7) {
            val kk = (t * 0.45f + i * 0.143f) % 1f
            val a = i * 2.4f + t * 0.8f
            b.box(cos(a) * (34f - 10f * kk), 20f + 150f * kk, sin(a) * (34f - 10f * kk), 2.6f, 2.6f, 2.6f, teamL, 0.8f * (1f - kk), false)
        }
        b.ring(0f, oy + 44f + sin(t * 1.5f) * 3f, 0f, 22f, 24f, gold, 0.5f, false, 28)
        b.mode = 0

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
        val el = 4f - z.life
        val landRaw = (el / 0.4f).coerceIn(0f, 1f)
        val land = landRaw * landRaw * (3f - 2f * landRaw)
        val det = (1f - z.life / 0.35f).coerceIn(0f, 1f)
        val pulse = 1f + 0.03f * sin(t * (8f + 22f * det))
        val rr = z.r * pulse * (0.15f + 0.85f * land)
        val gold = cc(0xFFFFF0A0)
        b.mode = 2
        if (landRaw < 1f) {
            val fy = gy + 28f + (1f - landRaw) * (1f - landRaw) * 360f
            b.cyl(z.x, fy, z.y, 6f, 14f, 150f, gold, 8, 0.25f * (1f - landRaw), false)
            b.sphere(z.x, fy, z.y, 22f, 22f, 22f, white, 0.9f, false, 8, 6)
        }
        if (el in 0.4f..0.9f) {
            val sk = (el - 0.4f) / 0.5f
            b.ring(z.x, gy + 0.6f, z.y, z.r * sk, z.r * sk + 6f, white, 0.8f * (1f - sk), false, 44)
        }
        if (det > 0f) {
            b.ring(z.x, gy + 0.7f, z.y, rr * (0.5f + 0.7f * det), rr * (0.5f + 0.7f * det) + 8f, white, 0.9f * det, false, 44)
            b.cyl(z.x, gy, z.y, rr * 0.5f, rr * 0.2f, 420f * det, gold, 12, 0.35f * det, false)
        }
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
        val oy = gy + 28f + sin(t * 3f) * 4f + (1f - land) * (1f - land) * 360f
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
            for (i in 0 until 16) {
                val kk = (t * 1.6f + i * 0.0625f) % 1f
                val a = i * 0.9f + t * 7f
                val rad = 130f * (1f - kk)
                b.sphere(22f + (1f - kk) * 30f, sin(a) * rad, cos(a) * rad, 3.6f, 3.6f, 3.6f, white, 0.3f + 0.7f * kk, false, 5, 3)
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
            val k = sqrt((bm.life / 0.7f).coerceIn(0f, 1f))
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
