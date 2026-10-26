package com.arenax1.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// ------------------------------------------------------------------ ENTIDADES

open class U(var x: Float, var y: Float, val team: Int, var maxHp: Float, val r: Float) {
    var hp = maxHp
    var dead = false
    var shield = 0f
    var shieldT = 0f
    var rootT = 0f
    var slowT = 0f
    var flash = 0f
    var atkCd = 0f
    var markT = 0f
    var face = if (team == 0) 0f else Math.PI.toFloat()
    var walk = 0f
    var mv = 0f
    var castT = 0f
}

class Minion(x: Float, y: Float, team: Int, val caster: Boolean) :
    U(x, y, team, if (caster) 220f else 340f, 15f)

class Tower(x: Float, y: Float, team: Int, val nexus: Boolean) :
    U(x, y, team, if (nexus) 3000f else 2000f, if (nexus) 56f else 38f)

class Champ(x: Float, y: Float, team: Int, val player: Boolean) : U(x, y, team, 700f, 18f) {
    var level = 1
    var xp = 0f
    var gold = 0f
    var mana = 440f
    val cd = FloatArray(4)
    var respawn = 0f
    var kills = 0
    var deaths = 0
    var haste = 0f
    val maxMana: Float get() = 400f + 40f * level
    val ap: Float get() = 55f + 12f * (level - 1) + gold / 25f
}

class Proj(
    var x: Float, var y: Float, val vx: Float, val vy: Float, val team: Int,
    val dmg: Float, var left: Float, val rad: Float, val kind: Int,
    val owner: U?, val root: Float, val target: U?
) {
    var pierce = 2
    val hit = HashSet<U>()
}

class Zone(val x: Float, val y: Float, val r: Float, var life: Float, val owner: Champ, val dmg: Float)
class Beam(var x: Float, var y: Float, val ang: Float, var delay: Float, val owner: Champ, val dmg: Float) {
    var fired = false
    var life = 0.45f
}

class FT(val x: Float, var y: Float, val s: String, var life: Float, val col: Int) {
    var h = 80f
}
class DeadFx(val u: U) {
    var t = 0f
}
class Part(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val col: Int, val sz: Float) {
    var z = 20f
    var vz = 0f
}

private fun C(h: Long): Int = h.toInt()
private fun rgb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

// ------------------------------------------------------------------- O JOGO

const val GAME_SPEED = 0.62f

class GameView(ctx: Context) : FrameLayout(ctx) {

    internal val W = MAP_W
    internal val H = 1280f
    internal val LT = 250f
    internal val LB = 1030f
    internal val CY = 640f
    internal val d = resources.displayMetrics.density
    internal val rnd = Random(System.nanoTime())

    internal val all = ArrayList<U>()
    internal val minions = ArrayList<Minion>()
    internal val towers = ArrayList<Tower>()
    internal val projs = ArrayList<Proj>()
    internal val zones = ArrayList<Zone>()
    internal val beams = ArrayList<Beam>()
    internal val texts = ArrayList<FT>()
    internal val parts = ArrayList<Part>()
    internal val deadFx = ArrayList<DeadFx>()
    internal val lock = Any()
    internal val vp = FloatArray(16)
    internal val camPos = FloatArray(3)
    internal var viewW = 1600f
    internal val scene = Scene(this)
    internal var pl = Champ(540f, 640f, 0, true)
    internal var bot = Champ(MAP_W - 540f, 640f, 1, false)
    internal val champs: List<Champ> get() = listOf(pl, bot)

    internal var t = 0f
    internal var waveT = 3f
    internal var spawnQ = 0
    internal var spawnGap = 0f
    internal var over = 0
    internal var overT = 0f
    internal var shake = 0f
    internal var last = 0L
    internal var camX = 0f
    internal var camY = 0f
    internal var sc = 1f

    internal val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    internal val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    internal val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    internal val path = Path()

    internal val CDS = floatArrayOf(5f, 10f, 7f, 30f)
    internal val COST = floatArrayOf(50f, 60f, 70f, 100f)
    internal val RANGE = floatArrayOf(950f, 0f, 780f, 1700f)
    internal val CH_RANGE = 560f

    // controles
    internal var joyId = -1
    internal var jbx = 0f
    internal var jby = 0f
    internal var jkx = 0f
    internal var jky = 0f
    internal var joyDx = 0f
    internal var joyDy = 0f
    internal val bId = IntArray(5) { -1 }
    internal val bDrag = BooleanArray(5)
    internal val bAx = FloatArray(5)
    internal val bAy = FloatArray(5)
    internal val bx = FloatArray(5)
    internal val by = FloatArray(5)
    internal val br = FloatArray(5)

    init {
        newGame()
    }

    private fun newGame() {
        minions.clear(); towers.clear(); projs.clear(); zones.clear()
        beams.clear(); texts.clear(); parts.clear(); deadFx.clear()
        towers.add(Tower(380f, CY, 0, true))
        towers.add(Tower(1900f, CY, 0, false))
        towers.add(Tower(3800f, CY, 0, false))
        towers.add(Tower(W - 380f, CY, 1, true))
        towers.add(Tower(W - 1900f, CY, 1, false))
        towers.add(Tower(W - 3800f, CY, 1, false))
        pl = Champ(540f, CY, 0, true)
        bot = Champ(W - 540f, CY, 1, false)
        pl.mana = pl.maxMana
        bot.mana = bot.maxMana
        t = 0f; waveT = 3f; spawnQ = 0; spawnGap = 0f
        over = 0; overT = 0f; shake = 0f
        joyDx = 0f; joyDy = 0f; joyId = -1
        for (i in 0..4) { bId[i] = -1; bDrag[i] = false }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        if (w > 0 && h > 0) glv.holder.setFixedSize((w * 0.9f).toInt(), (h * 0.9f).toInt())
        val wf = w.toFloat()
        val hf = h.toFloat()
        bx[4] = wf - 90f * d; by[4] = hf - 85f * d; br[4] = 46f * d
        val ang = floatArrayOf(180f, 145f, 110f, 75f)
        for (i in 0..3) {
            val a = Math.toRadians(ang[i].toDouble()).toFloat()
            bx[i] = bx[4] + cos(a) * 98f * d
            by[i] = by[4] - sin(a) * 98f * d
            br[i] = 30f * d
        }
    }

    // ----------------------------------------------------------------- LOOP

    internal fun tick(u: U, dt: Float) {
        u.atkCd -= dt
        if (u.rootT > 0f) u.rootT -= dt
        if (u.slowT > 0f) u.slowT -= dt
        if (u.flash > 0f) u.flash -= dt
        if (u.markT > 0f) u.markT -= dt
        if (u.mv > 0f) u.mv -= dt
        if (u.castT > 0f) u.castT -= dt
        if (u.shieldT > 0f) {
            u.shieldT -= dt
            if (u.shieldT <= 0f) u.shield = 0f
        }
    }

    internal fun update(dt: Float) {
        updFx(dt)
        updateCam()
        if (shake > 0f) shake -= dt * 1.5f
        if (over != 0) {
            overT += dt
            return
        }
        t += dt
        all.clear()
        all.addAll(towers); all.addAll(minions); all.add(pl); all.add(bot)

        // ondas de minions
        waveT -= dt
        if (waveT <= 0f) { waveT = 28f; spawnQ = 5; spawnGap = 0f }
        if (spawnQ > 0) {
            spawnGap -= dt
            if (spawnGap <= 0f) {
                val idx = 5 - spawnQ
                val caster = idx >= 3
                val yy = CY + rnd.nextFloat() * 220f - 110f
                minions.add(Minion(640f, yy, 0, caster))
                minions.add(Minion(W - 640f, yy, 1, caster))
                spawnQ--
                spawnGap = 0.8f
            }
        }

        updChamp(pl, dt)
        updChamp(bot, dt)
        for (m in minions) if (!m.dead) updMinion(m, dt)
        separate()
        for (tw in towers) updTower(tw, dt)
        updProjs(dt)
        updZones(dt)
        updBeams(dt)
        minions.removeAll { it.dead }
    }

    private fun updFx(dt: Float) {
        val pi = parts.iterator()
        while (pi.hasNext()) {
            val p = pi.next()
            p.x += p.vx * dt; p.y += p.vy * dt
            p.z += p.vz * dt; p.vz -= 520f * dt
            if (p.z < 3f) { p.z = 3f; p.vz = 0f }
            p.vx *= 0.95f; p.vy *= 0.95f
            p.life -= dt
            if (p.life <= 0f) pi.remove()
        }
        val di = deadFx.iterator()
        while (di.hasNext()) {
            val f = di.next()
            f.t += dt
            if (f.t > 0.8f) di.remove()
        }
        val ti = texts.iterator()
        while (ti.hasNext()) {
            val f = ti.next()
            f.h += 38f * dt
            f.life -= dt
            if (f.life <= 0f) ti.remove()
        }
    }

    internal fun burst(x: Float, y: Float, col: Int, n: Int) {
        if (parts.size > 450) return
        repeat(n) {
            val a = rnd.nextFloat() * 6.283f
            val s = 40f + rnd.nextFloat() * 220f
            val p = Part(x, y, cos(a) * s, sin(a) * s, 0.35f + rnd.nextFloat() * 0.4f, col, 3f + rnd.nextFloat() * 4f)
            p.z = 14f + rnd.nextFloat() * 24f
            p.vz = 60f + rnd.nextFloat() * 200f
            parts.add(p)
        }
    }

    // ------------------------------------------------------- DANO E MORTE

    private fun hurt(u: U, amt: Float, src: U?) {
        if (u.dead || over != 0) return
        if (u is Tower && u.nexus && towers.any { !it.dead && !it.nexus && it.team == u.team }) {
            if (u.flash <= 0f) {
                texts.add(FT(u.x, u.y, "IMUNE", 0.8f, C(0xFF80DEEA)))
                u.flash = 0.3f
            }
            return
        }
        var a = amt
        if (u.shield > 0f) {
            val s = min(u.shield, a)
            u.shield -= s
            a -= s
        }
        u.hp -= a
        u.flash = 0.1f
        if (a >= 1f) {
            val big = u is Champ
            texts.add(FT(u.x + rnd.nextFloat() * 20f - 10f, u.y, a.roundToInt().toString(), 0.7f,
                if (big) C(0xFFFFD54F) else C(0xFFFFFFFF)))
        }
        if (u.hp <= 0f) kill(u, src)
    }

    private fun kill(u: U, src: U?) {
        u.dead = true
        burst(u.x, u.y, if (u.team == 0) C(0xFF4FC3F7) else C(0xFFFF5252), if (u is Tower) 46 else 20)
        if (u is Minion || u is Champ) deadFx.add(DeadFx(u))
        if (u is Minion) {
            for (c in champs) {
                if (!c.dead && c.team != u.team && hypot(c.x - u.x, c.y - u.y) < 900f) {
                    c.xp += 16f
                    if (src === c) {
                        c.gold += 22f
                        texts.add(FT(c.x, c.y, "+22", 0.9f, C(0xFFFFC107)))
                    }
                    levelCheck(c)
                }
            }
        } else if (u is Tower) {
            shake = 1f
            if (u.nexus) {
                over = if (u.team == 1) 1 else 2
                overT = 0f
            } else {
                for (c in champs) if (c.team != u.team) {
                    c.gold += 120f; c.xp += 60f
                    texts.add(FT(c.x, c.y, "TORRE +120", 1.2f, C(0xFFFFC107)))
                    levelCheck(c)
                }
            }
        } else if (u is Champ) {
            u.deaths++
            u.respawn = 4f + u.level * 1.5f
            shake = 0.6f
            val k = if (src is Champ) src else champs.firstOrNull { it.team != u.team }
            if (k != null) {
                k.kills++
                k.gold += 180f
                k.xp += 120f
                texts.add(FT(k.x, k.y, "ABATE! +180", 1.3f, C(0xFFFF8A65)))
                levelCheck(k)
            }
        }
    }

    private fun levelCheck(c: Champ) {
        while (c.level < 9 && c.xp >= 70f + 45f * c.level) {
            c.xp -= 70f + 45f * c.level
            c.level++
            c.maxHp += 75f
            c.hp += 75f
            texts.add(FT(c.x, c.y, "NIVEL " + c.level, 1.3f, C(0xFF69F0AE)))
            burst(c.x, c.y, C(0xFF69F0AE), 16)
        }
    }

    internal fun brushOf(x: Float, y: Float): Int {
        for (i in BRUSHES.indices) {
            val q = BRUSHES[i]
            if (hypot(x - q[0], y - q[1]) < q[2] + 6f) return i
        }
        return -1
    }

    /** Unidade dentro da moita fica invisível para o time rival, salvo se alguém dele estiver perto ou na mesma moita. */
    internal fun hiddenFor(u: U, viewer: Int): Boolean {
        if (u.team == viewer || u.dead) return false
        val k = brushOf(u.x, u.y)
        if (k < 0) return false
        for (a in all) {
            if (a.dead || a.team != viewer) continue
            if (hypot(a.x - u.x, a.y - u.y) < 320f || brushOf(a.x, a.y) == k) return false
        }
        return true
    }

    private fun nearest(x: Float, y: Float, team: Int, range: Float, champPen: Float): U? {
        var b: U? = null
        var bs = 1e9f
        for (u in all) {
            if (u.dead || u.team == team || hiddenFor(u, team)) continue
            val dd = hypot(u.x - x, u.y - y) - u.r
            if (dd > range) continue
            val s = dd + when (u) {
                is Minion -> 0f
                is Champ -> champPen
                else -> 200f
            }
            if (s < bs) { bs = s; b = u }
        }
        return b
    }

    // ------------------------------------------------------------ MOVIMENTO

    private fun moveU(u: U, dx: Float, dy: Float, sp: Float, dt: Float) {
        if (u.rootT > 0f) return
        val l = hypot(dx, dy)
        if (l < 0.001f) return
        var s = sp
        if (u.slowT > 0f) s *= 0.6f
        if (u is Champ && u.haste > 0f) s *= 1.3f
        u.x += dx / l * s * dt
        u.y += dy / l * s * dt
        u.x = u.x.coerceIn(60f, W - 60f)
        u.y = u.y.coerceIn(LT + 24f, LB - 24f)
        u.face = atan2(dy, dx)
        u.walk += dt * s * 0.05f
        u.mv = 0.1f
    }

    // -------------------------------------------------------------- CAMPEÃO

    private fun updChamp(c: Champ, dt: Float) {
        tick(c, dt)
        if (c.dead) {
            c.respawn -= dt
            if (c.respawn <= 0f) {
                c.dead = false
                c.hp = c.maxHp
                c.mana = c.maxMana
                c.shield = 0f; c.rootT = 0f; c.slowT = 0f
                c.x = if (c.team == 0) 540f else W - 540f
                c.y = CY
            }
            return
        }
        for (i in 0..3) if (c.cd[i] > 0f) c.cd[i] -= dt
        if (c.haste > 0f) c.haste -= dt
        c.mana = min(c.maxMana, c.mana + (8f + c.level) * dt)
        val nx = towers[if (c.team == 0) 0 else 3]
        val home = hypot(nx.x - c.x, nx.y - c.y) < 380f
        c.hp = min(c.maxHp, c.hp + (if (home) 120f else 3f + c.level) * dt)
        if (home) c.mana = min(c.maxMana, c.mana + 40f * dt)
        if (c.player) playerCtl(c, dt) else botThink(c, dt)
    }

    private fun playerCtl(c: Champ, dt: Float) {
        val l = hypot(joyDx, joyDy)
        if (l > 0.15f) moveU(c, joyDx, joyDy, 330f, dt)
        if (bId[4] != -1) {
            val tg = nearest(c.x, c.y, c.team, CH_RANGE, -150f)
            basicAttack(c, tg)
        }
    }

    private fun basicAttack(c: Champ, tg: U?) {
        if (tg == null || tg.dead || c.atkCd > 0f) return
        if (hypot(tg.x - c.x, tg.y - c.y) - tg.r > CH_RANGE + 20f) return
        c.atkCd = 0.7f
        c.face = atan2(tg.y - c.y, tg.x - c.x)
        var dm = 22f + c.ap * 0.25f
        if (tg.markT > 0f) {
            dm += 12f + 9f * c.level + c.ap * 0.2f
            tg.markT = 0f
            burst(tg.x, tg.y, C(0xFFFFF59D), 10)
        }
        projs.add(Proj(c.x, c.y - 10f, 0f, 0f, c.team, dm, 0f, 0f, 0, c, 0f, tg))
    }

    private fun autoAim(c: Champ, range: Float): Pair<Float, Float> {
        val o = if (c === pl) bot else pl
        if (!o.dead) {
            val dd = hypot(o.x - c.x, o.y - c.y)
            if (dd < max(range, 400f) + 150f) return Pair(atan2(o.y - c.y, o.x - c.x), min(dd, 780f))
        }
        val e = nearest(c.x, c.y, c.team, max(range, 400f), 0f)
        if (e != null) return Pair(atan2(e.y - c.y, e.x - c.x), min(hypot(e.x - c.x, e.y - c.y), 780f))
        return Pair(c.face, 600f)
    }

    private fun cast(c: Champ, s: Int, ang: Float, dist: Float, recast: Boolean): Boolean {
        if (c.dead) return false
        if (s == 2 && recast) {
            val z = zones.firstOrNull { it.owner === c }
            if (z != null) { z.life = 0f; return true }
        }
        if (c.cd[s] > 0f || c.mana < COST[s]) return false
        c.mana -= COST[s]
        c.cd[s] = CDS[s]
        c.face = ang
        c.castT = 0.8f
        val ap = c.ap
        val cs = cos(ang)
        val sn = sin(ang)
        when (s) {
            0 -> {
                projs.add(Proj(c.x, c.y - 10f, cs * 1050f, sn * 1050f, c.team, 55f + ap * 0.55f + c.level * 6f,
                    950f, 26f, 1, c, 1.6f, null))
                burst(c.x + cs * 30f, c.y + sn * 30f, C(0xFFFFF59D), 8)
            }
            1 -> {
                c.shield = 70f + ap * 0.6f + c.level * 8f
                c.shieldT = 2.5f
                c.haste = 1.5f
                burst(c.x, c.y, C(0xFF81D4FA), 16)
            }
            2 -> {
                val zx = (c.x + cs * dist).coerceIn(60f, W - 60f)
                val zy = (c.y + sn * dist).coerceIn(LT + 30f, LB - 30f)
                zones.add(Zone(zx, zy, 190f, 4f, c, 50f + ap * 0.6f + c.level * 5f))
            }
            3 -> {
                beams.add(Beam(c.x, c.y, ang, 0.6f, c, 170f + ap * 0.9f + c.level * 10f))
            }
        }
        return true
    }

    private fun castPlayer(slot: Int) {
        if (pl.dead || over != 0) return
        val ang: Float
        val dist: Float
        if (bDrag[slot]) {
            ang = atan2(bAy[slot], bAx[slot])
            dist = (hypot(bAx[slot], bAy[slot]) / d * 3.2f).coerceIn(150f, 780f)
        } else {
            val r = autoAim(pl, RANGE[slot])
            ang = r.first
            dist = r.second
        }
        cast(pl, slot, ang, dist, true)
    }

    // ------------------------------------------------------------------ IA

    private fun botThink(b: Champ, dt: Float) {
        val p = pl
        val dp = if (p.dead || hiddenFor(p, b.team)) 1e9f else hypot(p.x - b.x, p.y - b.y)
        val homeX = W - 380f
        var minX = 0f
        for (tw in towers) if (!tw.dead && tw.team == 0) minX = max(minX, tw.x + if (tw.nexus) 560f else 680f)

        if (b.hp < b.maxHp * 0.3f) {
            moveU(b, homeX - b.x, CY - b.y, 310f, dt)
            if (b.hp < b.maxHp * 0.5f && dp < 700f) cast(b, 1, 0f, 0f, false)
            return
        }
        if (dp < 950f && p.x > minX - 200f) {
            val ang = atan2(p.y - b.y, p.x - b.x)
            var mx = 0f
            var my = 0f
            if (dp < 330f) { mx = -cos(ang); my = -sin(ang) }
            else if (dp > 540f) { mx = cos(ang); my = sin(ang) }
            val perp = sin(t * 1.7f)
            mx += -sin(ang) * perp * 0.8f
            my += cos(ang) * perp * 0.8f
            if (b.x <= minX && mx < 0f) mx = 0f
            moveU(b, mx, my, 305f, dt)
            b.face = ang
            if (dp < 950f) cast(b, 0, ang, 0f, false)
            if (dp < 760f) cast(b, 2, ang, min(dp, 760f), false)
            if (b.hp < b.maxHp * 0.65f && dp < 700f) cast(b, 1, ang, 0f, false)
            if (dp < 1100f && (p.hp < p.maxHp * 0.5f || (b.level >= 3 && dp < 600f))) cast(b, 3, ang, 0f, false)
            if (dp < 540f) basicAttack(b, p)
        } else {
            var front = W - 1200f
            for (m in minions) if (!m.dead && m.team == 1) front = min(front, m.x)
            val destX = max(minX, front + 260f)
            val tg = nearest(b.x, b.y, b.team, CH_RANGE, -50f)
            if (tg != null) basicAttack(b, tg)
            val dx = destX - b.x
            if (abs(dx) > 40f) moveU(b, dx, CY - b.y, 295f, dt)
        }
    }

    // --------------------------------------------------- MINIONS E TORRES

    private fun updMinion(m: Minion, dt: Float) {
        tick(m, dt)
        val range = if (m.caster) 290f else 48f
        val tg = nearest(m.x, m.y, m.team, 340f, 60f)
        if (tg != null) {
            val dist = hypot(tg.x - m.x, tg.y - m.y) - tg.r
            if (dist > range) {
                moveU(m, tg.x - m.x, tg.y - m.y, 140f, dt)
            } else {
                m.face = atan2(tg.y - m.y, tg.x - m.x)
                if (m.atkCd <= 0f) {
                    if (m.caster) {
                        m.atkCd = 1.3f
                        projs.add(Proj(m.x, m.y - 8f, 0f, 0f, m.team, 16f, 0f, 0f, 3, m, 0f, tg))
                    } else {
                        m.atkCd = 1.0f
                        hurt(tg, 13f, m)
                        burst(tg.x, tg.y, C(0xFFFFFFFF), 3)
                    }
                }
            }
        } else {
            val nxX = if (m.team == 0) W - 380f else 380f
            moveU(m, nxX - m.x, (CY - m.y) * 0.5f, 140f, dt)
        }
    }

    private fun separate() {
        for (i in minions.indices) {
            val a = minions[i]
            if (a.dead) continue
            for (j in i + 1 until minions.size) {
                val b = minions[j]
                if (b.dead) continue
                val dx = b.x - a.x
                val dy = b.y - a.y
                val dd = hypot(dx, dy)
                val mn = a.r + b.r
                if (dd < mn && dd > 0.01f) {
                    val push = (mn - dd) * 0.5f
                    a.x -= dx / dd * push; a.y -= dy / dd * push
                    b.x += dx / dd * push; b.y += dy / dd * push
                }
            }
        }
    }

    private fun updTower(tw: Tower, dt: Float) {
        if (tw.dead) return
        tick(tw, dt)
        if (tw.atkCd > 0f) return
        val rg = if (tw.nexus) 520f else 640f
        val tg = nearest(tw.x, tw.y, tw.team, rg, 1000f) ?: return
        tw.atkCd = 1.1f
        projs.add(Proj(tw.x, tw.y - 70f, 0f, 0f, tw.team, if (tw.nexus) 70f else 90f, 0f, 0f, 2, tw, 0f, tg))
    }

    // ------------------------------------------- PROJÉTEIS, ZONAS E LASERS

    private fun updProjs(dt: Float) {
        val pi = projs.iterator()
        while (pi.hasNext()) {
            val q = pi.next()
            val tg = q.target
            if (tg != null) {
                if (tg.dead) { pi.remove(); continue }
                val dx = tg.x - q.x
                val dy = tg.y - q.y
                val l = hypot(dx, dy)
                val sp = (when (q.kind) { 0 -> 1100f; 2 -> 1000f; else -> 650f }) * dt
                if (l <= sp + tg.r) {
                    hurt(tg, q.dmg, q.owner)
                    burst(tg.x, tg.y, if (q.team == 0) C(0xFFFFF59D) else C(0xFFFF8A80), 4)
                    pi.remove()
                } else {
                    q.x += dx / l * sp
                    q.y += dy / l * sp
                }
            } else {
                q.x += q.vx * dt
                q.y += q.vy * dt
                q.left -= hypot(q.vx, q.vy) * dt
                var done = q.left <= 0f
                for (u in all) {
                    if (done) break
                    if (u.dead || u.team == q.team || u is Tower || q.hit.contains(u)) continue
                    if (hypot(u.x - q.x, u.y - q.y) < q.rad + u.r) {
                        q.hit.add(u)
                        hurt(u, q.dmg, q.owner)
                        u.rootT = max(u.rootT, q.root)
                        u.markT = 6f
                        burst(u.x, u.y, C(0xFFFFF59D), 12)
                        q.pierce--
                        if (q.pierce <= 0) done = true
                    }
                }
                if (done) pi.remove()
            }
        }
    }

    private fun updZones(dt: Float) {
        val zi = zones.iterator()
        while (zi.hasNext()) {
            val z = zi.next()
            z.life -= dt
            for (u in all) {
                if (!u.dead && u.team != z.owner.team && hypot(u.x - z.x, u.y - z.y) < z.r + u.r) {
                    u.slowT = max(u.slowT, 0.25f)
                }
            }
            if (z.life <= 0f) {
                for (u in all) {
                    if (!u.dead && u.team != z.owner.team && hypot(u.x - z.x, u.y - z.y) < z.r + u.r) {
                        hurt(u, z.dmg, z.owner)
                        u.markT = 6f
                    }
                }
                burst(z.x, z.y, C(0xFFFFF59D), 30)
                shake = max(shake, 0.3f)
                zi.remove()
            }
        }
    }

    private fun updBeams(dt: Float) {
        for (b in beams) {
            if (b.owner.dead && !b.fired) { b.fired = true; b.life = 0f; continue }
            if (!b.fired) {
                b.x = b.owner.x
                b.y = b.owner.y
                b.delay -= dt
                if (b.delay <= 0f) {
                    b.fired = true
                    val cs = cos(b.ang)
                    val sn = sin(b.ang)
                    for (u in all) {
                        if (u.dead || u.team == b.owner.team) continue
                        val rx = u.x - b.x
                        val ry = u.y - b.y
                        val along = rx * cs + ry * sn
                        val perp = abs(-rx * sn + ry * cs)
                        if (along > -20f && along < 1700f && perp < 55f + u.r) {
                            hurt(u, b.dmg, b.owner)
                            u.markT = 6f
                        }
                    }
                    shake = 0.8f
                }
            } else {
                b.life -= dt
            }
        }
        beams.removeAll { it.fired && it.life <= 0f }
    }

    // ============================================================ CÂMERA 3D

    private fun blk(c: Canvas, l: Float, tt: Float, r: Float, b: Float, col: Int) {
        pt.style = Paint.Style.FILL
        pt.color = col
        c.drawRect(l, tt, r, b, pt)
        st.color = C(0x99000000)
        st.strokeWidth = 1.5f
        c.drawRect(l, tt, r, b, st)
    }


    internal fun updateCam() {
        camX = pl.x.coerceIn(1300f, W - 1300f)
        var sx = 0f
        var sz = 0f
        if (shake > 0f) {
            sx = (rnd.nextFloat() - 0.5f) * 16f * min(1f, shake)
            sz = (rnd.nextFloat() - 0.5f) * 16f * min(1f, shake)
        }
        val aspect = if (height > 0) width.toFloat() / height.toFloat() else 2f
        Matrix.perspectiveM(projM, 0, 45f, aspect, 150f, 7000f)
        camPos[0] = camX + sx; camPos[1] = 1200f; camPos[2] = CY + 820f + sz
        Matrix.setLookAtM(viewM, 0, camX + sx, 1200f, CY + 820f + sz, camX + sx, 0f, CY + sz * 0.5f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, projM, 0, viewM, 0)
        viewW = 1230f * aspect
    }

    private val viewM = FloatArray(16)
    private val projM = FloatArray(16)
    private val sp = FloatArray(3)

    /** Projeta um ponto do mundo na tela: sp = (x, y, pixels por unidade). */
    internal fun proj(x: Float, y: Float, z: Float): Boolean {
        val cx = vp[0] * x + vp[4] * y + vp[8] * z + vp[12]
        val cy = vp[1] * x + vp[5] * y + vp[9] * z + vp[13]
        val cw = vp[3] * x + vp[7] * y + vp[11] * z + vp[15]
        if (cw < 50f) return false
        sp[0] = (cx / cw * 0.5f + 0.5f) * width
        sp[1] = (1f - (cy / cw * 0.5f + 0.5f)) * height
        sp[2] = height / (0.8284f * cw)
        return true
    }

    private fun bar(c: Canvas, u: U, wd: Float, hgt: Float) {
        if (!proj(u.x, groundH(u.x, u.y) + hgt, u.y)) return
        val w = wd * sp[2]
        val th = max(3f * d, 6f * sp[2])
        val x = sp[0] - w / 2f
        val y = sp[1]
        pt.style = Paint.Style.FILL
        pt.color = C(0xCC000000)
        c.drawRect(x - 1.5f, y - 1.5f, x + w + 1.5f, y + th + 1.5f, pt)
        pt.color = if (u.team == 0) C(0xFF43A047) else C(0xFFE53935)
        c.drawRect(x, y, x + w * (u.hp / u.maxHp).coerceIn(0f, 1f), y + th, pt)
        if (u.shield > 0f) {
            pt.color = C(0xFFE3F2FD)
            c.drawRect(x, y - th * 0.5f, x + w * (u.shield / u.maxHp).coerceIn(0f, 1f), y, pt)
        }
    }

    /** Barras de vida, nível e números de dano, projetados sobre o mundo 3D. */
    internal fun drawWorldUi(c: Canvas) {
        for (tw in towers) {
            if (tw.dead || abs(tw.x - camX) > viewW) continue
            bar(c, tw, if (tw.nexus) 130f else 80f, if (tw.nexus) 290f else 172f)
        }
        for (m in minions) {
            if (m.dead || abs(m.x - camX) > viewW || hiddenFor(m, 0)) continue
            bar(c, m, 36f, 76f)
        }
        for (ch in champs) {
            if (ch.dead || hiddenFor(ch, 0)) continue
            bar(c, ch, 70f, 128f)
            if (proj(ch.x, groundH(ch.x, ch.y) + 128f, ch.y)) {
                tp.textSize = 12f * d
                tp.textAlign = Paint.Align.CENTER
                tp.color = C(0xFFFFFFFF)
                c.drawText("" + ch.level, sp[0] - 41f * sp[2], sp[1] + 8f * d, tp)
            }
        }
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = 15f * d
        for (f in texts) {
            if (!proj(f.x, groundH(f.x, f.y) + f.h, f.y)) continue
            tp.color = C(0xFF000000)
            c.drawText(f.s, sp[0] + 1.5f * d, sp[1] + 1.5f * d, tp)
            tp.color = f.col
            c.drawText(f.s, sp[0], sp[1], tp)
        }
    }

    private var dtS = 0.016f

    /** Chamado uma vez por quadro pela thread do GL (dentro do lock): avança o jogo em câmera lenta suave. */
    internal fun stepFrame() {
        val now = System.nanoTime()
        var dt = if (last == 0L) 0.016f else (now - last) / 1e9f
        last = now
        if (dt > 0.05f) dt = 0.05f
        dtS = dtS * 0.6f + dt * 0.4f
        update(dtS * GAME_SPEED)
    }

    /** Camada transparente por cima do 3D: atualiza o jogo e desenha o HUD. */
    inner class HudView(ctx: Context) : View(ctx) {
        override fun onDraw(c: Canvas) {
            synchronized(lock) {
                drawWorldUi(c)
                drawHud(c, width.toFloat(), height.toFloat(), viewW)
            }
            postInvalidateOnAnimation()
        }
    }

    private val glv = GLSurfaceView(ctx).apply {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MsaaChooser())
        setRenderer(GlRenderer(this@GameView))
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
    }
    private val hudView = HudView(ctx)

    init {
        addView(glv, FrameLayout.LayoutParams(-1, -1))
        addView(hudView, FrameLayout.LayoutParams(-1, -1))
    }

    fun onPauseGame() = glv.onPause()
    fun onResumeGame() = glv.onResume()
    // ================================================================== HUD

    private fun hudText(c: Canvas, s: String, x: Float, y: Float, size: Float, col: Int, al: Paint.Align) {
        tp.textSize = size * d
        tp.textAlign = al
        tp.color = C(0xFF000000)
        c.drawText(s, x + 1.5f * d, y + 1.5f * d, tp)
        tp.color = col
        c.drawText(s, x, y, tp)
    }

    private fun drawHud(c: Canvas, w: Float, h: Float, vw: Float) {
        pt.style = Paint.Style.FILL
        // retrato + barras
        val px = 12f * d
        val py = 10f * d
        blk(c, px, py, px + 44f * d, py + 44f * d, C(0xFF263238))
        pt.color = C(0xFFFFD54F)
        c.drawRect(px + 5f * d, py + 4f * d, px + 39f * d, py + 22f * d, pt)
        pt.color = C(0xFFFFCCA8)
        c.drawRect(px + 11f * d, py + 14f * d, px + 33f * d, py + 36f * d, pt)
        pt.color = C(0xFF212121)
        c.drawRect(px + 15f * d, py + 22f * d, px + 19f * d, py + 26f * d, pt)
        c.drawRect(px + 25f * d, py + 22f * d, px + 29f * d, py + 26f * d, pt)
        pt.color = C(0xFF3F51B5)
        c.drawRect(px + 8f * d, py + 36f * d, px + 36f * d, py + 42f * d, pt)
        val bxx = px + 50f * d
        val bw = 150f * d
        pt.color = C(0xCC000000)
        c.drawRect(bxx, py, bxx + bw, py + 16f * d, pt)
        pt.color = C(0xFF43A047)
        c.drawRect(bxx + 2f, py + 2f, bxx + 2f + (bw - 4f) * (pl.hp / pl.maxHp).coerceIn(0f, 1f), py + 16f * d - 2f, pt)
        if (pl.shield > 0f) {
            pt.color = C(0xFFE3F2FD)
            c.drawRect(bxx + 2f, py + 2f, bxx + 2f + (bw - 4f) * (pl.shield / pl.maxHp).coerceIn(0f, 1f), py + 6f * d, pt)
        }
        hudText(c, pl.hp.toInt().toString() + "/" + pl.maxHp.toInt(), bxx + bw / 2f, py + 12f * d, 10f, C(0xFFFFFFFF), Paint.Align.CENTER)
        pt.color = C(0xCC000000)
        c.drawRect(bxx, py + 19f * d, bxx + bw, py + 29f * d, pt)
        pt.color = C(0xFF1E88E5)
        c.drawRect(bxx + 2f, py + 21f * d, bxx + 2f + (bw - 4f) * (pl.mana / pl.maxMana).coerceIn(0f, 1f), py + 29f * d - 2f, pt)
        hudText(c, "NV " + pl.level, bxx, py + 44f * d, 11f, C(0xFF69F0AE), Paint.Align.LEFT)
        pt.color = C(0xFFFFC107)
        c.drawCircle(bxx + 62f * d, py + 40f * d, 5f * d, pt)
        hudText(c, pl.gold.toInt().toString(), bxx + 70f * d, py + 44f * d, 11f, C(0xFFFFC107), Paint.Align.LEFT)

        // relógio + placar
        val mm = (t / 60f).toInt()
        val ss = (t % 60f).toInt()
        hudText(c, String.format("%02d:%02d", mm, ss), w / 2f, 20f * d, 15f, C(0xFFFFFFFF), Paint.Align.CENTER)
        hudText(c, pl.kills.toString() + "  :  " + bot.kills, w / 2f, 38f * d, 12f, C(0xFFFFD54F), Paint.Align.CENTER)

        // minimapa
        val mw = 190f * d
        val mh = 34f * d
        val mx = w - mw - 12f * d
        val my = 10f * d
        pt.color = C(0xCC000000)
        c.drawRect(mx - 2f, my - 2f, mx + mw + 2f, my + mh + 2f, pt)
        pt.color = C(0xFF3E5A34)
        c.drawRect(mx, my, mx + mw, my + mh, pt)
        pt.color = C(0xFF8B7B5E)
        c.drawRect(mx, my + mh * 0.35f, mx + mw, my + mh * 0.65f, pt)
        for (tw in towers) if (!tw.dead) {
            pt.color = if (tw.team == 0) C(0xFF4FC3F7) else C(0xFFFF5252)
            val s = if (tw.nexus) 5f * d else 3.5f * d
            c.drawRect(mx + tw.x / W * mw - s, my + mh / 2f - s, mx + tw.x / W * mw + s, my + mh / 2f + s, pt)
        }
        for (m in minions) if (!m.dead && !hiddenFor(m, 0)) {
            pt.color = if (m.team == 0) C(0xFF81D4FA) else C(0xFFFF8A80)
            val xx = mx + m.x / W * mw
            val yy = my + (m.y - LT) / (LB - LT) * mh
            c.drawRect(xx - d, yy - d, xx + d, yy + d, pt)
        }
        for (ch in champs) if (!ch.dead && !hiddenFor(ch, 0)) {
            pt.color = if (ch.team == 0) C(0xFFFFFFFF) else C(0xFFFFEB3B)
            val xx = mx + ch.x / W * mw
            val yy = my + (ch.y - LT) / (LB - LT) * mh
            c.drawRect(xx - 3.5f * d, yy - 3.5f * d, xx + 3.5f * d, yy + 3.5f * d, pt)
        }
        st.color = C(0xAAFFFFFF); st.strokeWidth = 1.5f
        c.drawRect(mx + (camX - vw / 2f) / W * mw, my, mx + (camX + vw / 2f) / W * mw, my + mh, st)

        // joystick
        val jx = if (joyId != -1) jbx else 110f * d
        val jy = if (joyId != -1) jby else h - 100f * d
        pt.color = C(0x22FFFFFF)
        c.drawCircle(jx, jy, 52f * d, pt)
        st.color = C(0x66FFFFFF); st.strokeWidth = 2f * d
        c.drawCircle(jx, jy, 52f * d, st)
        pt.color = C(0x88FFFFFF)
        val kx = if (joyId != -1) jkx else jx
        val ky = if (joyId != -1) jky else jy
        c.drawRect(kx - 20f * d, ky - 20f * d, kx + 20f * d, ky + 20f * d, pt)

        // botões
        val names = arrayOf("Q", "W", "E", "R")
        for (i in 0..3) {
            val ready = pl.cd[i] <= 0f && pl.mana >= COST[i] && !pl.dead
            pt.color = C(0xCC1B1F2B)
            c.drawCircle(bx[i], by[i], br[i], pt)
            drawGlyph(c, i, bx[i], by[i], br[i], ready)
            if (pl.cd[i] > 0f) {
                pt.color = C(0xAA000000)
                val sweep = 360f * (pl.cd[i] / CDS[i]).coerceIn(0f, 1f)
                c.drawArc(bx[i] - br[i], by[i] - br[i], bx[i] + br[i], by[i] + br[i], -90f, sweep, true, pt)
                hudText(c, Math.ceil(pl.cd[i].toDouble()).toInt().toString(), bx[i], by[i] + 5f * d, 14f, C(0xFFFFFFFF), Paint.Align.CENTER)
            }
            st.color = if (ready) C(0xFFFFD54F) else C(0xFF607D8B)
            st.strokeWidth = 3f * d
            c.drawCircle(bx[i], by[i], br[i], st)
            hudText(c, names[i], bx[i] - br[i] * 0.55f, by[i] - br[i] * 0.55f, 11f, C(0xFFFFFFFF), Paint.Align.CENTER)
        }
        pt.color = if (bId[4] != -1) C(0xFFFFB300) else C(0xCC1B1F2B)
        c.drawCircle(bx[4], by[4], br[4], pt)
        st.color = C(0xFFFFD54F); st.strokeWidth = 4f * d
        c.drawCircle(bx[4], by[4], br[4], st)
        pt.color = C(0xFFFFF59D)
        val gl = 1f + 0.08f * sin(t * 6f)
        c.drawRect(bx[4] - 14f * d * gl, by[4] - 14f * d * gl, bx[4] + 14f * d * gl, by[4] + 14f * d * gl, pt)
        pt.color = C(0xFFFFFFFF)
        c.drawRect(bx[4] - 6f * d, by[4] - 6f * d, bx[4] + 6f * d, by[4] + 6f * d, pt)

        // dica inicial
        if (t < 7f && over == 0) {
            hudText(c, "Destrua o Nexus inimigo  •  segure e arraste uma habilidade para mirar", w / 2f, h - 14f * d, 11f, C(0xFFFFFFFF), Paint.Align.CENTER)
        }
        // morto
        if (pl.dead && over == 0) {
            pt.color = C(0x66000000)
            c.drawRect(0f, 0f, w, h, pt)
            hudText(c, "Renascendo em " + Math.ceil(pl.respawn.toDouble()).toInt() + "s", w / 2f, h / 2f, 22f, C(0xFFFFFFFF), Paint.Align.CENTER)
        }
        // fim
        if (over != 0) {
            pt.color = C(0xAA000000)
            c.drawRect(0f, 0f, w, h, pt)
            if (over == 1) hudText(c, "VITÓRIA!", w / 2f, h / 2f, 44f, C(0xFFFFD54F), Paint.Align.CENTER)
            else hudText(c, "DERROTA", w / 2f, h / 2f, 44f, C(0xFFFF5252), Paint.Align.CENTER)
            if (overT > 1f) hudText(c, "Toque para jogar de novo  (Voltar = menu)", w / 2f, h / 2f + 34f * d, 14f, C(0xFFFFFFFF), Paint.Align.CENTER)
        }
    }

    private fun drawGlyph(c: Canvas, i: Int, x: Float, y: Float, r: Float, ready: Boolean) {
        val a = if (ready) 0xFF else 0x77
        pt.style = Paint.Style.FILL
        when (i) {
            0 -> {
                pt.color = (a shl 24) or 0xFFF59D
                c.drawRect(x - r * 0.6f, y - r * 0.12f, x + r * 0.6f, y + r * 0.12f, pt)
                pt.color = (a shl 24) or 0xFFFFFF
                c.drawRect(x + r * 0.1f, y - r * 0.25f, x + r * 0.5f, y + r * 0.25f, pt)
            }
            1 -> {
                pt.color = (a shl 24) or 0x81D4FA
                c.drawCircle(x, y, r * 0.5f, pt)
                pt.color = (a shl 24) or 0xE1F5FE
                c.drawRect(x - r * 0.2f, y - r * 0.3f, x + r * 0.2f, y + r * 0.3f, pt)
            }
            2 -> {
                pt.color = (a shl 24) or 0xFFEB3B
                c.drawCircle(x, y, r * 0.5f, pt)
                pt.color = (a shl 24) or 0xFFFFFF
                c.drawCircle(x, y, r * 0.22f, pt)
            }
            else -> {
                pt.color = (a shl 24) or 0xFFF59D
                c.drawRect(x - r * 0.7f, y - r * 0.3f, x + r * 0.7f, y + r * 0.3f, pt)
                pt.color = (a shl 24) or 0xFFFFFF
                c.drawRect(x - r * 0.7f, y - r * 0.1f, x + r * 0.7f, y + r * 0.1f, pt)
            }
        }
    }

    // ============================================================== TOQUE

    private fun hitBtn(x: Float, y: Float): Int {
        for (i in 0..4) if (hypot(x - bx[i], y - by[i]) <= br[i] * 1.25f) return i
        return -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        synchronized(lock) { handleTouch(e) }
        return true
    }

    private fun handleTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                pDown(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) pMove(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                pUp(e.getPointerId(i))
            }
            MotionEvent.ACTION_CANCEL -> {
                joyId = -1; joyDx = 0f; joyDy = 0f
                for (i in 0..4) { bId[i] = -1; bDrag[i] = false }
            }
        }
    }

    private fun pDown(id: Int, x: Float, y: Float) {
        if (over != 0) {
            if (overT > 1f) newGame()
            return
        }
        val b = hitBtn(x, y)
        if (b >= 0 && bId[b] == -1) {
            bId[b] = id
            bDrag[b] = false
            return
        }
        if (x < width * 0.55f && joyId == -1) {
            joyId = id
            jbx = x; jby = y; jkx = x; jky = y
            joyDx = 0f; joyDy = 0f
        }
    }

    private fun pMove(id: Int, x: Float, y: Float) {
        if (id == joyId) {
            var dx = x - jbx
            var dy = y - jby
            val maxR = 48f * d
            val l = hypot(dx, dy)
            if (l > maxR) { dx = dx / l * maxR; dy = dy / l * maxR }
            jkx = jbx + dx; jky = jby + dy
            joyDx = dx / maxR; joyDy = dy / maxR
            return
        }
        for (i in 0..3) {
            if (bId[i] == id) {
                val dx = x - bx[i]
                val dy = y - by[i]
                if (hypot(dx, dy) > 18f * d) {
                    bDrag[i] = true
                    bAx[i] = dx
                    bAy[i] = dy
                }
            }
        }
    }

    private fun pUp(id: Int) {
        if (id == joyId) {
            joyId = -1; joyDx = 0f; joyDy = 0f
        }
        for (i in 0..4) {
            if (bId[i] == id) {
                bId[i] = -1
                if (i < 4) castPlayer(i)
                bDrag[i] = false
            }
        }
    }
}
