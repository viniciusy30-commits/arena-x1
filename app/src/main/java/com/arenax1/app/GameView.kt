package com.arenax1.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
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

class FT(val x: Float, var y: Float, val s: String, var life: Float, val col: Int)
class Part(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val col: Int, val sz: Float)

private fun C(h: Long): Int = h.toInt()
private fun rgb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

// ------------------------------------------------------------------- O JOGO

class GameView(ctx: Context) : View(ctx) {

    private val W = 4200f
    private val H = 1280f
    private val LT = 320f
    private val LB = 960f
    private val CY = 640f
    private val d = resources.displayMetrics.density
    private val rnd = Random(System.nanoTime())

    private val all = ArrayList<U>()
    private val minions = ArrayList<Minion>()
    private val towers = ArrayList<Tower>()
    private val projs = ArrayList<Proj>()
    private val zones = ArrayList<Zone>()
    private val beams = ArrayList<Beam>()
    private val texts = ArrayList<FT>()
    private val parts = ArrayList<Part>()
    private var pl = Champ(420f, 640f, 0, true)
    private var bot = Champ(3780f, 640f, 1, false)
    private val champs: List<Champ> get() = listOf(pl, bot)

    private var t = 0f
    private var waveT = 3f
    private var spawnQ = 0
    private var spawnGap = 0f
    private var over = 0
    private var overT = 0f
    private var shake = 0f
    private var last = 0L
    private var camX = 0f
    private var camY = 0f
    private var sc = 1f

    private val pt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val path = Path()

    private val CDS = floatArrayOf(5f, 10f, 7f, 30f)
    private val COST = floatArrayOf(50f, 60f, 70f, 100f)
    private val RANGE = floatArrayOf(950f, 0f, 780f, 1700f)
    private val CH_RANGE = 560f

    // controles
    private var joyId = -1
    private var jbx = 0f
    private var jby = 0f
    private var jkx = 0f
    private var jky = 0f
    private var joyDx = 0f
    private var joyDy = 0f
    private val bId = IntArray(5) { -1 }
    private val bDrag = BooleanArray(5)
    private val bAx = FloatArray(5)
    private val bAy = FloatArray(5)
    private val bx = FloatArray(5)
    private val by = FloatArray(5)
    private val br = FloatArray(5)

    init {
        newGame()
    }

    private fun newGame() {
        minions.clear(); towers.clear(); projs.clear(); zones.clear()
        beams.clear(); texts.clear(); parts.clear()
        towers.add(Tower(300f, CY, 0, true))
        towers.add(Tower(700f, CY, 0, false))
        towers.add(Tower(1500f, CY, 0, false))
        towers.add(Tower(W - 300f, CY, 1, true))
        towers.add(Tower(W - 700f, CY, 1, false))
        towers.add(Tower(W - 1500f, CY, 1, false))
        pl = Champ(430f, CY, 0, true)
        bot = Champ(W - 430f, CY, 1, false)
        pl.mana = pl.maxMana
        bot.mana = bot.maxMana
        t = 0f; waveT = 3f; spawnQ = 0; spawnGap = 0f
        over = 0; overT = 0f; shake = 0f
        joyDx = 0f; joyDy = 0f; joyId = -1
        for (i in 0..4) { bId[i] = -1; bDrag[i] = false }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
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

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        var dt = if (last == 0L) 0.016f else (now - last) / 1e9f
        last = now
        if (dt > 0.05f) dt = 0.05f
        update(dt)
        render(c)
        postInvalidateOnAnimation()
    }

    private fun tick(u: U, dt: Float) {
        u.atkCd -= dt
        if (u.rootT > 0f) u.rootT -= dt
        if (u.slowT > 0f) u.slowT -= dt
        if (u.flash > 0f) u.flash -= dt
        if (u.markT > 0f) u.markT -= dt
        if (u.shieldT > 0f) {
            u.shieldT -= dt
            if (u.shieldT <= 0f) u.shield = 0f
        }
    }

    private fun update(dt: Float) {
        updFx(dt)
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
                val yy = CY + rnd.nextFloat() * 90f - 45f
                minions.add(Minion(480f, yy, 0, caster))
                minions.add(Minion(W - 480f, yy, 1, caster))
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
            p.vx *= 0.95f; p.vy *= 0.95f
            p.life -= dt
            if (p.life <= 0f) pi.remove()
        }
        val ti = texts.iterator()
        while (ti.hasNext()) {
            val f = ti.next()
            f.y -= 38f * dt
            f.life -= dt
            if (f.life <= 0f) ti.remove()
        }
    }

    private fun burst(x: Float, y: Float, col: Int, n: Int) {
        if (parts.size > 450) return
        repeat(n) {
            val a = rnd.nextFloat() * 6.283f
            val s = 40f + rnd.nextFloat() * 220f
            parts.add(Part(x, y, cos(a) * s, sin(a) * s, 0.35f + rnd.nextFloat() * 0.4f, col, 3f + rnd.nextFloat() * 4f))
        }
    }

    // ------------------------------------------------------- DANO E MORTE

    private fun hurt(u: U, amt: Float, src: U?) {
        if (u.dead || over != 0) return
        if (u is Tower && u.nexus && towers.any { !it.dead && !it.nexus && it.team == u.team }) {
            if (u.flash <= 0f) {
                texts.add(FT(u.x, u.y - 110f, "IMUNE", 0.8f, C(0xFF80DEEA)))
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
            texts.add(FT(u.x + rnd.nextFloat() * 20f - 10f, u.y - u.r - 28f, a.roundToInt().toString(), 0.7f,
                if (big) C(0xFFFFD54F) else C(0xFFFFFFFF)))
        }
        if (u.hp <= 0f) kill(u, src)
    }

    private fun kill(u: U, src: U?) {
        u.dead = true
        burst(u.x, u.y, if (u.team == 0) C(0xFF4FC3F7) else C(0xFFFF5252), 20)
        if (u is Minion) {
            for (c in champs) {
                if (!c.dead && c.team != u.team && hypot(c.x - u.x, c.y - u.y) < 900f) {
                    c.xp += 16f
                    if (src === c) {
                        c.gold += 22f
                        texts.add(FT(c.x, c.y - 56f, "+22", 0.9f, C(0xFFFFC107)))
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
                    texts.add(FT(c.x, c.y - 56f, "TORRE +120", 1.2f, C(0xFFFFC107)))
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
                texts.add(FT(k.x, k.y - 60f, "ABATE! +180", 1.3f, C(0xFFFF8A65)))
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
            texts.add(FT(c.x, c.y - 80f, "NIVEL " + c.level, 1.3f, C(0xFF69F0AE)))
            burst(c.x, c.y, C(0xFF69F0AE), 16)
        }
    }

    private fun nearest(x: Float, y: Float, team: Int, range: Float, champPen: Float): U? {
        var b: U? = null
        var bs = 1e9f
        for (u in all) {
            if (u.dead || u.team == team) continue
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
                c.x = if (c.team == 0) 430f else W - 430f
                c.y = CY
            }
            return
        }
        for (i in 0..3) if (c.cd[i] > 0f) c.cd[i] -= dt
        if (c.haste > 0f) c.haste -= dt
        c.mana = min(c.maxMana, c.mana + (8f + c.level) * dt)
        val nx = towers[if (c.team == 0) 0 else 3]
        val home = hypot(nx.x - c.x, nx.y - c.y) < 340f
        c.hp = min(c.maxHp, c.hp + (if (home) 120f else 3f + c.level) * dt)
        if (home) c.mana = min(c.maxMana, c.mana + 40f * dt)
        if (c.player) playerCtl(c, dt) else botThink(c, dt)
    }

    private fun playerCtl(c: Champ, dt: Float) {
        val l = hypot(joyDx, joyDy)
        if (l > 0.15f) moveU(c, joyDx, joyDy, 270f, dt)
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
        val ap = c.ap
        val cs = cos(ang)
        val sn = sin(ang)
        when (s) {
            0 -> {
                projs.add(Proj(c.x, c.y - 10f, cs * 1300f, sn * 1300f, c.team, 55f + ap * 0.55f + c.level * 6f,
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
        val dp = if (p.dead) 1e9f else hypot(p.x - b.x, p.y - b.y)
        val homeX = W - 300f
        var minX = 0f
        for (tw in towers) if (!tw.dead && tw.team == 0) minX = max(minX, tw.x + if (tw.nexus) 560f else 680f)

        if (b.hp < b.maxHp * 0.3f) {
            moveU(b, homeX - b.x, CY - b.y, 250f, dt)
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
            moveU(b, mx, my, 245f, dt)
            b.face = ang
            if (dp < 950f) cast(b, 0, ang, 0f, false)
            if (dp < 760f) cast(b, 2, ang, min(dp, 760f), false)
            if (b.hp < b.maxHp * 0.65f && dp < 700f) cast(b, 1, ang, 0f, false)
            if (dp < 1100f && (p.hp < p.maxHp * 0.5f || (b.level >= 3 && dp < 600f))) cast(b, 3, ang, 0f, false)
            if (dp < 540f) basicAttack(b, p)
        } else {
            var front = W - 900f
            for (m in minions) if (!m.dead && m.team == 1) front = min(front, m.x)
            val destX = max(minX, front + 260f)
            val tg = nearest(b.x, b.y, b.team, CH_RANGE, -50f)
            if (tg != null) basicAttack(b, tg)
            val dx = destX - b.x
            if (abs(dx) > 40f) moveU(b, dx, CY - b.y, 235f, dt)
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
                moveU(m, tg.x - m.x, tg.y - m.y, 115f, dt)
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
            val nxX = if (m.team == 0) W - 300f else 300f
            moveU(m, nxX - m.x, (CY - m.y) * 0.5f, 115f, dt)
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

    // ================================================================ RENDER

    private fun blk(c: Canvas, l: Float, tt: Float, r: Float, b: Float, col: Int) {
        pt.style = Paint.Style.FILL
        pt.color = col
        c.drawRect(l, tt, r, b, pt)
        st.color = C(0x99000000)
        st.strokeWidth = 1.5f
        c.drawRect(l, tt, r, b, st)
    }

    private fun render(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        sc = h / 760f
        val vw = w / sc
        val vh = h / sc
        camX = (pl.x - vw / 2f).coerceIn(0f, max(0f, W - vw))
        camY = CY - vh / 2f
        var sx = 0f
        var sy = 0f
        if (shake > 0f) {
            sx = (rnd.nextFloat() - 0.5f) * 14f * min(1f, shake)
            sy = (rnd.nextFloat() - 0.5f) * 14f * min(1f, shake)
        }
        c.drawColor(C(0xFF141C0E))
        c.save()
        c.scale(sc, sc)
        c.translate(-camX + sx, -camY + sy)

        drawGround(c, vw, vh)
        drawBases(c)
        for (tw in towers) if (tw.dead && !tw.nexus) drawRubble(c, tw)
        for (z in zones) drawZone(c, z)
        for (b in beams) drawBeam(c, b)

        val order = all.filter { !it.dead && it.x > camX - 150f && it.x < camX + vw + 150f }.sortedBy { it.y }
        for (u in order) {
            when (u) {
                is Tower -> drawTower(c, u)
                is Minion -> drawMinion(c, u)
                is Champ -> drawChamp(c, u)
            }
        }
        for (q in projs) drawProj(c, q)
        for (p in parts) {
            pt.style = Paint.Style.FILL
            pt.color = p.col
            pt.alpha = (255 * min(1f, p.life * 2.5f)).toInt().coerceIn(0, 255)
            c.drawRect(p.x - p.sz / 2f, p.y - p.sz / 2f, p.x + p.sz / 2f, p.y + p.sz / 2f, pt)
        }
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = 20f
        for (f in texts) {
            tp.color = C(0xFF000000)
            c.drawText(f.s, f.x + 1.5f, f.y + 1.5f, tp)
            tp.color = f.col
            c.drawText(f.s, f.x, f.y, tp)
        }
        c.restore()
        drawHud(c, w, h, vw)
    }

    private fun drawGround(c: Canvas, vw: Float, vh: Float) {
        val ts = 64f
        val tx0 = max(0, (camX / ts).toInt())
        val tx1 = min((W / ts).toInt() - 1, ((camX + vw) / ts).toInt())
        val ty0 = max(0, (camY / ts).toInt())
        val ty1 = min((H / ts).toInt() - 1, ((camY + vh) / ts).toInt())
        pt.style = Paint.Style.FILL
        for (ty in ty0..ty1) {
            for (tx in tx0..tx1) {
                val hs = ((tx * 73856093) xor (ty * 19349663)) and 0x7fffffff
                val v = hs % 20
                val x = tx * ts
                val y = ty * ts
                if (ty in 5..14) {
                    val mid = ty in 8..11
                    if (mid) {
                        pt.color = rgb(150 + v, 128 + v, 92 + v)
                        c.drawRect(x, y, x + ts, y + ts, pt)
                        pt.color = rgb(120 + v, 100 + v, 70 + v)
                        c.drawRect(x, y + ts - 3f, x + ts, y + ts, pt)
                        c.drawRect(x + ts - 3f, y, x + ts, y + ts, pt)
                        if (hs % 5 == 0) {
                            pt.color = rgb(170 + v, 150 + v, 110 + v)
                            c.drawRect(x + 10f, y + 10f, x + 28f, y + 22f, pt)
                        }
                    } else {
                        pt.color = rgb(86 + v, 140 + v, 58 + v)
                        c.drawRect(x, y, x + ts, y + ts, pt)
                        if (hs % 4 == 0) {
                            pt.color = rgb(66 + v, 118 + v, 44 + v)
                            c.drawRect(x + 14f, y + 18f, x + 20f, y + 30f, pt)
                            c.drawRect(x + 36f, y + 34f, x + 42f, y + 44f, pt)
                        }
                        if (hs % 23 == 0) {
                            pt.color = rgb(255, 235, 59)
                            c.drawRect(x + 30f, y + 12f, x + 36f, y + 18f, pt)
                        }
                    }
                } else if (ty == 4 || ty == 15) {
                    pt.color = rgb(40, 62, 36)
                    c.drawRect(x, y, x + ts, y + ts, pt)
                    val top = if (ty == 4) y + 14f else y
                    pt.color = rgb(118 + v, 118 + v, 126 + v)
                    c.drawRect(x, top, x + ts, top + ts - 14f, pt)
                    pt.color = rgb(150 + v, 150 + v, 158 + v)
                    c.drawRect(x, top, x + ts, top + 8f, pt)
                    pt.color = rgb(80, 80, 90)
                    c.drawRect(x, top + 30f, x + ts, top + 33f, pt)
                    c.drawRect(x + (if (hs % 2 == 0) 20f else 40f), top + 8f, x + (if (hs % 2 == 0) 23f else 43f), top + 30f, pt)
                } else {
                    pt.color = rgb(34 + v, 62 + v, 30 + v)
                    c.drawRect(x, y, x + ts, y + ts, pt)
                    if (hs % 2 == 0) {
                        pt.color = rgb(84, 56, 30)
                        c.drawRect(x + 26f, y + 30f, x + 38f, y + 58f, pt)
                        pt.color = rgb(30 + v, 100 + v, 36)
                        c.drawRect(x + 8f, y + 4f, x + 56f, y + 36f, pt)
                        pt.color = rgb(52 + v, 140 + v, 54)
                        c.drawRect(x + 8f, y + 4f, x + 56f, y + 12f, pt)
                        st.color = C(0x88000000); st.strokeWidth = 1.5f
                        c.drawRect(x + 8f, y + 4f, x + 56f, y + 36f, st)
                    }
                }
            }
        }
        // tinta dos times
        pt.color = C(0x1A2196F3)
        c.drawRect(0f, LT, W / 2f, LB, pt)
        pt.color = C(0x1AF44336)
        c.drawRect(W / 2f, LT, W, LB, pt)
        // linha do meio
        pt.color = C(0x33FFFFFF)
        c.drawRect(W / 2f - 3f, LT, W / 2f + 3f, LB, pt)
    }

    private fun drawBases(c: Canvas) {
        for (team in 0..1) {
            val cx = if (team == 0) 300f else W - 300f
            if (cx < camX - 400f || cx > camX + width / sc + 400f) continue
            pt.style = Paint.Style.FILL
            pt.color = if (team == 0) C(0x334FC3F7) else C(0x33FF5252)
            c.drawCircle(cx, CY, 260f, pt)
            pt.color = if (team == 0) rgb(80, 90, 110) else rgb(110, 80, 80)
            c.drawRect(cx - 150f, CY - 150f, cx + 150f, CY + 150f, pt)
            pt.color = if (team == 0) rgb(60, 70, 90) else rgb(90, 60, 60)
            c.drawRect(cx - 130f, CY - 130f, cx + 130f, CY + 130f, pt)
        }
    }

    private fun drawRubble(c: Canvas, tw: Tower) {
        blk(c, tw.x - 30f, tw.y + 4f, tw.x + 4f, tw.y + 26f, rgb(100, 100, 108))
        blk(c, tw.x + 6f, tw.y + 12f, tw.x + 34f, tw.y + 28f, rgb(85, 85, 92))
    }

    private fun hpBar(c: Canvas, u: U, wd: Float, yo: Float) {
        val x = u.x - wd / 2f
        val y = u.y - yo
        pt.style = Paint.Style.FILL
        pt.color = C(0xCC000000)
        c.drawRect(x - 1.5f, y - 1.5f, x + wd + 1.5f, y + 6.5f, pt)
        pt.color = if (u.team == 0) C(0xFF43A047) else C(0xFFE53935)
        c.drawRect(x, y, x + wd * (u.hp / u.maxHp).coerceIn(0f, 1f), y + 5f, pt)
        if (u.shield > 0f) {
            pt.color = C(0xFFE3F2FD)
            c.drawRect(x, y - 3f, x + wd * (u.shield / u.maxHp).coerceIn(0f, 1f), y, pt)
        }
    }

    private fun drawTower(c: Canvas, tw: Tower) {
        val s = if (tw.nexus) 1.7f else 1f
        val x = tw.x
        val y = tw.y
        val team = if (tw.team == 0) C(0xFF4FC3F7) else C(0xFFFF5252)
        pt.style = Paint.Style.FILL
        pt.color = C(0x55000000)
        c.drawOval(x - 46f * s, y + 18f * s, x + 46f * s, y + 40f * s, pt)
        val f = tw.flash > 0f
        blk(c, x - 34f * s, y - 4f * s, x + 34f * s, y + 30f * s, if (f) C(0xFFFFFFFF) else rgb(110, 110, 118))
        blk(c, x - 26f * s, y - 50f * s, x + 26f * s, y - 4f * s, if (f) C(0xFFFFFFFF) else rgb(140, 140, 150))
        blk(c, x - 31f * s, y - 62f * s, x + 31f * s, y - 50f * s, if (f) C(0xFFFFFFFF) else rgb(90, 90, 100))
        pt.color = team
        c.drawRect(x - 10f * s, y - 40f * s, x + 10f * s, y - 14f * s, pt)
        // cristal
        val pulse = 1f + 0.12f * sin(t * 3f + x)
        val cy = y - 86f * s
        pt.color = (team and 0x00FFFFFF) or C(0x44000000)
        c.drawCircle(x, cy, 34f * s * pulse, pt)
        path.reset()
        path.moveTo(x, cy - 22f * s * pulse)
        path.lineTo(x + 14f * s, cy)
        path.lineTo(x, cy + 22f * s * pulse)
        path.lineTo(x - 14f * s, cy)
        path.close()
        pt.color = team
        c.drawPath(path, pt)
        st.color = C(0xAA000000); st.strokeWidth = 2f
        c.drawPath(path, st)
        pt.color = C(0xAAFFFFFF)
        c.drawRect(x - 3f * s, cy - 10f * s, x + 3f * s, cy + 2f * s, pt)
        // tochas
        for (sd in intArrayOf(-1, 1)) {
            val tx = x + sd * 42f * s
            pt.color = rgb(90, 60, 30)
            c.drawRect(tx - 3f, y + 2f, tx + 3f, y + 24f, pt)
            val fl = 6f + 3f * sin(t * 14f + tx)
            pt.color = C(0xFFFFA726)
            c.drawRect(tx - fl / 2f, y - fl, tx + fl / 2f, y + 2f, pt)
            pt.color = C(0xFFFFEE58)
            c.drawRect(tx - fl / 4f, y - fl / 2f, tx + fl / 4f, y + 2f, pt)
        }
        hpBar(c, tw, 80f * s, 118f * s)
    }

    private fun human(c: Canvas, u: U, s: Float, body: Int, skin: Int, leg: Int, hair: Int, longHair: Boolean) {
        val x = u.x
        val y = u.y
        val dir = if (cos(u.face) >= 0f) 1f else -1f
        val sw = sin(u.walk * 2f) * 3f * s
        val fl = u.flash > 0f
        val bc = if (fl) C(0xFFFFFFFF) else body
        val sk = if (fl) C(0xFFFFFFFF) else skin
        pt.style = Paint.Style.FILL
        pt.color = C(0x55000000)
        c.drawOval(x - 13f * s, y + 10f * s, x + 13f * s, y + 19f * s, pt)
        if (longHair) blk(c, x - 9f * s, y - 27f * s, x + 9f * s, y - 6f * s, hair)
        blk(c, x - 7f * s + sw * 0.5f, y + 2f * s, x - 1f * s + sw * 0.5f, y + 14f * s, if (fl) bc else leg)
        blk(c, x + 1f * s - sw * 0.5f, y + 2f * s, x + 7f * s - sw * 0.5f, y + 14f * s, if (fl) bc else leg)
        blk(c, x - 8f * s, y - 14f * s, x + 8f * s, y + 3f * s, bc)
        blk(c, x - 12f * s, y - 13f * s, x - 8f * s, y - 1f * s, sk)
        blk(c, x + 8f * s, y - 13f * s, x + 12f * s, y - 1f * s, sk)
        blk(c, x - 7f * s, y - 27f * s, x + 7f * s, y - 14f * s, sk)
        blk(c, x - 8f * s, y - 29f * s, x + 8f * s, y - 22f * s, if (fl) bc else hair)
        pt.color = C(0xFF212121)
        val ex = x + dir * 1.5f * s
        c.drawRect(ex - 4f * s, y - 21f * s, ex - 1.5f * s, y - 18f * s, pt)
        c.drawRect(ex + 1.5f * s, y - 21f * s, ex + 4f * s, y - 18f * s, pt)
    }

    private fun drawChamp(c: Canvas, ch: Champ) {
        val ally = ch.team == 0
        val s = 1.5f
        // anel de time
        st.color = if (ally) C(0xFF4FC3F7) else C(0xFFFF5252)
        st.strokeWidth = 3f
        c.drawOval(ch.x - 22f, ch.y + 8f * s - 4f, ch.x + 22f, ch.y + 8f * s + 8f, st)
        if (ally) {
            human(c, ch, s, C(0xFFF5F5F5), C(0xFFFFCCA8), C(0xFF3F51B5), C(0xFFFFD54F), true)
            pt.color = C(0xFF3F51B5)
            c.drawRect(ch.x - 8f * s, ch.y - 2f * s, ch.x + 8f * s, ch.y + 3f * s, pt)
        } else {
            human(c, ch, s, C(0xFF37265A), C(0xFFE0B8A0), C(0xFF1A1030), C(0xFF8E24AA), true)
            pt.color = C(0xFFAB47BC)
            c.drawRect(ch.x - 8f * s, ch.y - 2f * s, ch.x + 8f * s, ch.y + 3f * s, pt)
        }
        // cajado
        val dir = if (cos(ch.face) >= 0f) 1f else -1f
        val sx = ch.x + dir * 22f
        pt.color = C(0xFF6D4C41)
        c.drawRect(sx - 2f, ch.y - 36f, sx + 2f, ch.y + 20f, pt)
        val glow = 1f + 0.15f * sin(t * 6f)
        pt.color = if (ally) C(0x55FFF59D) else C(0x55CE93D8)
        c.drawCircle(sx, ch.y - 42f, 15f * glow, pt)
        pt.color = if (ally) C(0xFFFFF9C4) else C(0xFFE1BEE7)
        c.drawRect(sx - 6f, ch.y - 48f, sx + 6f, ch.y - 36f, pt)
        if (ch.shield > 0f) {
            pt.color = C(0x334FC3F7)
            c.drawCircle(ch.x, ch.y - 8f, 36f, pt)
            st.color = C(0xAA81D4FA); st.strokeWidth = 3f
            c.drawCircle(ch.x, ch.y - 8f, 36f, st)
        }
        if (ch.rootT > 0f) {
            st.color = C(0xFFFFF176); st.strokeWidth = 4f
            c.drawOval(ch.x - 26f, ch.y + 4f, ch.x + 26f, ch.y + 22f, st)
        }
        if (ch.markT > 0f) {
            pt.color = C(0xFFFFF59D)
            c.drawRect(ch.x - 5f, ch.y - 84f, ch.x + 5f, ch.y - 74f, pt)
        }
        hpBar(c, ch, 64f, 70f)
        tp.textSize = 13f
        tp.textAlign = Paint.Align.CENTER
        tp.color = C(0xFFFFFFFF)
        c.drawText("" + ch.level, ch.x - 40f, ch.y - 63f, tp)
    }

    private fun drawMinion(c: Canvas, m: Minion) {
        val ally = m.team == 0
        val s = 0.85f
        val body = if (ally) C(0xFF3F7FD6) else C(0xFFD64F3F)
        human(c, m, s, body, C(0xFF7CB342), if (ally) C(0xFF2B4F8F) else C(0xFF8F2F2B), C(0xFF33691E), false)
        val dir = if (cos(m.face) >= 0f) 1f else -1f
        if (m.caster) {
            path.reset()
            path.moveTo(m.x - 10f * s, m.y - 29f * s)
            path.lineTo(m.x + 10f * s, m.y - 29f * s)
            path.lineTo(m.x, m.y - 46f * s)
            path.close()
            pt.style = Paint.Style.FILL
            pt.color = if (ally) C(0xFF1A237E) else C(0xFF7F0000)
            c.drawPath(path, pt)
            pt.color = if (ally) C(0xFF4FC3F7) else C(0xFFFF8A80)
            c.drawRect(m.x + dir * 14f * s - 4f, m.y - 22f * s, m.x + dir * 14f * s + 4f, m.y - 14f * s, pt)
        } else {
            pt.style = Paint.Style.FILL
            pt.color = C(0xFFB0BEC5)
            val sxx = m.x + dir * 14f * s
            c.drawRect(sxx - 2f, m.y - 20f * s, sxx + 2f, m.y + 2f * s, pt)
        }
        hpBar(c, m, 34f, 52f * s)
    }

    private fun drawProj(c: Canvas, q: Proj) {
        pt.style = Paint.Style.FILL
        when (q.kind) {
            1 -> {
                val a = Math.toDegrees(atan2(q.vy, q.vx).toDouble()).toFloat()
                c.save()
                c.translate(q.x, q.y)
                c.rotate(a)
                pt.color = C(0x55FFF59D)
                c.drawRect(-60f, -20f, 24f, 20f, pt)
                pt.color = C(0xFFFFF59D)
                c.drawRect(-50f, -8f, 20f, 8f, pt)
                pt.color = C(0xFFFFFFFF)
                c.drawRect(-30f, -3f, 20f, 3f, pt)
                c.restore()
            }
            0 -> {
                pt.color = C(0x66FFF59D)
                c.drawCircle(q.x, q.y, 14f, pt)
                pt.color = C(0xFFFFFFFF)
                c.drawRect(q.x - 5f, q.y - 5f, q.x + 5f, q.y + 5f, pt)
            }
            2 -> {
                pt.color = if (q.team == 0) C(0xFF4FC3F7) else C(0xFFFF5252)
                c.drawRect(q.x - 7f, q.y - 7f, q.x + 7f, q.y + 7f, pt)
                pt.color = C(0xFFFFFFFF)
                c.drawRect(q.x - 3f, q.y - 3f, q.x + 3f, q.y + 3f, pt)
            }
            else -> {
                pt.color = if (q.team == 0) C(0xFF81D4FA) else C(0xFFFF8A80)
                c.drawRect(q.x - 4f, q.y - 4f, q.x + 4f, q.y + 4f, pt)
            }
        }
    }

    private fun drawZone(c: Canvas, z: Zone) {
        pt.style = Paint.Style.FILL
        val pulse = 1f + 0.04f * sin(t * 8f)
        pt.color = C(0x33FFF59D)
        c.drawCircle(z.x, z.y, z.r * pulse, pt)
        st.color = C(0xCCFFF59D); st.strokeWidth = 4f
        c.drawCircle(z.x, z.y, z.r * pulse, st)
        st.strokeWidth = 2f
        c.drawCircle(z.x, z.y, z.r * (z.life / 4f).coerceIn(0f, 1f), st)
        for (i in 0 until 6) {
            val a = t * 2.5f + i * 1.047f
            val rr = z.r * 0.6f
            pt.color = C(0xFFFFFFFF)
            c.drawRect(z.x + cos(a) * rr - 4f, z.y + sin(a) * rr * 0.6f - 4f,
                z.x + cos(a) * rr + 4f, z.y + sin(a) * rr * 0.6f + 4f, pt)
        }
        pt.color = C(0xFFFFEB3B)
        c.drawRect(z.x - 10f, z.y - 10f, z.x + 10f, z.y + 10f, pt)
    }

    private fun drawBeam(c: Canvas, b: Beam) {
        val deg = Math.toDegrees(b.ang.toDouble()).toFloat()
        c.save()
        c.translate(b.x, b.y - 8f)
        c.rotate(deg)
        pt.style = Paint.Style.FILL
        if (!b.fired) {
            val k = 1f - (b.delay / 0.6f).coerceIn(0f, 1f)
            pt.color = C(0x33FFF59D)
            c.drawRect(0f, -55f, 1700f, 55f, pt)
            pt.color = C(0x88FFF59D)
            c.drawRect(0f, -55f * k, 1700f, 55f * k, pt)
        } else {
            val k = (b.life / 0.45f).coerceIn(0f, 1f)
            pt.color = C(0x55FFF59D)
            c.drawRect(0f, -70f * k, 1700f, 70f * k, pt)
            pt.color = C(0xCCFFFDE7)
            c.drawRect(0f, -42f * k, 1700f, 42f * k, pt)
            pt.color = C(0xFFFFFFFF)
            c.drawRect(0f, -20f * k, 1700f, 20f * k, pt)
        }
        c.restore()
    }

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
        for (m in minions) if (!m.dead) {
            pt.color = if (m.team == 0) C(0xFF81D4FA) else C(0xFFFF8A80)
            val xx = mx + m.x / W * mw
            val yy = my + (m.y - LT) / (LB - LT) * mh
            c.drawRect(xx - d, yy - d, xx + d, yy + d, pt)
        }
        for (ch in champs) if (!ch.dead) {
            pt.color = if (ch.team == 0) C(0xFFFFFFFF) else C(0xFFFFEB3B)
            val xx = mx + ch.x / W * mw
            val yy = my + (ch.y - LT) / (LB - LT) * mh
            c.drawRect(xx - 3.5f * d, yy - 3.5f * d, xx + 3.5f * d, yy + 3.5f * d, pt)
        }
        st.color = C(0xAAFFFFFF); st.strokeWidth = 1.5f
        c.drawRect(mx + camX / W * mw, my, mx + (camX + vw) / W * mw, my + mh, st)

        // linhas de mira
        for (i in 0..3) {
            if (bId[i] != -1 && bDrag[i] && !pl.dead) {
                val ang = atan2(bAy[i], bAx[i])
                val len = when (i) {
                    0 -> 950f
                    2 -> (hypot(bAx[i], bAy[i]) / d * 3.2f).coerceIn(150f, 780f)
                    3 -> 1700f
                    else -> 120f
                }
                val ox = (pl.x - camX) * sc
                val oy = (pl.y - camY) * sc
                st.color = C(0xAAFFF59D); st.strokeWidth = 4f * d
                c.drawLine(ox, oy, ox + cos(ang) * len * sc, oy + sin(ang) * len * sc, st)
                if (i == 2) {
                    pt.color = C(0x44FFF59D)
                    c.drawCircle(ox + cos(ang) * len * sc, oy + sin(ang) * len * sc, 190f * sc, pt)
                }
            }
        }

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
        return true
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
