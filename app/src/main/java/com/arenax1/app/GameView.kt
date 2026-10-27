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
    var castS = 0
    var castMax = 0.8f
    var ghost = 0f
    var silT = 0f
    var sinceHurt = 99f
    var deathT = 0f
    var deathAcc = 0f
    var deathOwner: Champ? = null
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
    var sprite = false
    var respawn = 0f
    var kills = 0
    var deaths = 0
    var haste = 0f
    var hero = 0
    val cost = FloatArray(4)
    val cdBase = FloatArray(4)
    var swing = 0f
    var swingMax = 0.34f
    var swingN = 0
    var swingBig = false

    var hitT = 0f
    var hitTg: U? = null
    var hitDmg = 0f
    var hitBig = false
    var evT = 0f
    var evS = 0
    var evAng = 0f
    var evDist = 0f
    var evTg: U? = null
    var passCd = 0f
    // Zed
    var shT = 0f
    var shX = 0f
    var shY = 0f
    var shTX = 0f
    var shTY = 0f
    var shMoving = false
    var rsT = 0f
    var rsX = 0f
    var rsY = 0f
    var dashT = 0f
    var dashTg: U? = null
    // Garen
    var qT = 0f
    var wT = 0f
    var spinT = 0f
    var spinTick = 0f
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
    var life = 0.7f
}

/** Efeito visual de habilidade (fumaça, cortes, ondas, espada de luz...). */
class Fx(val kind: Int, var x: Float, var y: Float, val ang: Float, val max: Float, val team: Int) {

    var age = 0f
    var tgt: U? = null
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
    internal val fxs = ArrayList<Fx>()
    internal var picking = true
    internal var pickSel = 2
    internal val pkl = FloatArray(4)
    internal val pkt = FloatArray(4)
    internal val pkr = FloatArray(4)
    internal val pkb = FloatArray(4)
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
    internal val HERO_NAME = arrayOf("Lux", "Yasuo", "Zed", "Garen")

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

    private fun setupHero(c: Champ, h: Int) {
        c.hero = h
        c.sprite = h == 1
        when (h) {
            2 -> {
                c.cost[0] = 40f; c.cost[1] = 40f; c.cost[2] = 35f; c.cost[3] = 70f

                c.cdBase[0] = 5.5f; c.cdBase[1] = 9f; c.cdBase[2] = 4.5f; c.cdBase[3] = 26f
            }
            3 -> {
                c.cost[0] = 25f; c.cost[1] = 30f; c.cost[2] = 30f; c.cost[3] = 80f
                c.cdBase[0] = 7f; c.cdBase[1] = 13f; c.cdBase[2] = 8f; c.cdBase[3] = 28f
                c.maxHp = 950f
                c.hp = 950f
            }
            else -> {
                for (i in 0..3) { c.cost[i] = COST[i]; c.cdBase[i] = CDS[i] }
            }
        }
    }

    private fun startMatch(h: Int) {
        val pool = intArrayOf(0, 2, 3).filter { it != h }
        newGame(h, pool[rnd.nextInt(pool.size)])
        picking = false
    }

    private fun newGame(hero: Int = 2, botHero: Int = 0) {
        minions.clear(); towers.clear(); projs.clear(); zones.clear()
        beams.clear(); texts.clear(); parts.clear(); deadFx.clear(); fxs.clear()
        towers.add(Tower(380f, CY, 0, true))
        towers.add(Tower(1900f, CY, 0, false))
        towers.add(Tower(3800f, CY, 0, false))
        towers.add(Tower(W - 380f, CY, 1, true))
        towers.add(Tower(W - 1900f, CY, 1, false))
        towers.add(Tower(W - 3800f, CY, 1, false))
        pl = Champ(540f, CY, 0, true)
        setupHero(pl, hero)
        bot = Champ(W - 540f, CY, 1, false)
        setupHero(bot, botHero)
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
        bx[4] = wf - 90f * d; by[4] = hf - 85f * d; br[4] = 56f * d
        val ang = floatArrayOf(180f, 145f, 110f, 75f)
        for (i in 0..3) {
            val a = Math.toRadians(ang[i].toDouble()).toFloat()
            bx[i] = bx[4] + cos(a) * 124f * d
            by[i] = by[4] - sin(a) * 124f * d
            br[i] = 39f * d
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
        u.sinceHurt += dt

        if (u.ghost > 0f) u.ghost -= dt
        if (u.silT > 0f) u.silT -= dt
        if (u.deathT > 0f) {
            u.deathT -= dt
            if (u.deathT <= 0f) detonate(u)
        }
        if (u.shieldT > 0f) {
            u.shieldT -= dt
            if (u.shieldT <= 0f) u.shield = 0f
        }
    }

    internal fun update(dt: Float) {
        updFx(dt)
        updateCam()
        if (shake > 0f) shake -= dt * 1.5f
        if (picking) { t += dt; return }
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
        val fi = fxs.iterator()
        while (fi.hasNext()) {
            val f = fi.next()
            f.age += dt
            val tg = f.tgt
            if (tg != null && !tg.dead) { f.x = tg.x; f.y = tg.y }
            if (f.age >= f.max) fi.remove()
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
        if (u.ghost > 0f) return
        if (u is Tower && u.nexus && towers.any { !it.dead && !it.nexus && it.team == u.team }) {
            if (u.flash <= 0f) {
                texts.add(FT(u.x, u.y, "IMUNE", 0.8f, C(0xFF80DEEA)))
                u.flash = 0.3f
            }
            return
        }
        var a = amt
        if (u is Champ && u.wT > 0f) a *= 0.65f
        if (u.shield > 0f) {
            val s = min(u.shield, a)
            u.shield -= s
            a -= s
        }
        u.hp -= a
        u.sinceHurt = 0f
        if (u.deathT > 0f && src != null && src === u.deathOwner) u.deathAcc += a
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
            u.shT = 0f; u.rsT = 0f; u.dashT = 0f; u.spinT = 0f; u.evT = 0f; u.hitT = 0f
            u.qT = 0f; u.wT = 0f; u.deathT = 0f; u.swing = 0f
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
            val add = if (c.hero == 3) 95f else 75f
            c.maxHp += add
            c.hp += add
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
                c.shield = 0f; c.rootT = 0f; c.slowT = 0f; c.silT = 0f
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
        if (c.qT > 0f) c.qT -= dt
        if (c.wT > 0f) c.wT -= dt
        if (c.swing > 0f) c.swing -= dt
        if (c.passCd > 0f) c.passCd -= dt
        if (c.shT > 0f) {
            c.shT -= dt
            updShadowMove(c, dt)
            if (c.shT <= 0f) {
                c.shMoving = false
                addFx(9, c.shX, c.shY, 0f, 0.6f, c.team)
            }
        }
        if (c.rsT > 0f) {
            c.rsT -= dt
            if (c.rsT <= 0f) addFx(9, c.rsX, c.rsY, 0f, 0.6f, c.team)
        }
        if (c.hero == 3 && c.sinceHurt > 6f && c.hp < c.maxHp) c.hp = min(c.maxHp, c.hp + c.maxHp * 0.02f * dt)
        if (c.hitT > 0f) {
            c.hitT -= dt
            if (c.hitT <= 0f) applyHit(c)
        }
        if (c.evT > 0f) {

            c.evT -= dt
            if (c.evT <= 0f) castEvent(c)
        }
        if (c.spinT > 0f) updSpin(c, dt)
        if (c.dashT > 0f) {
            updDash(c, dt)
            return
        }
        if (c.player) playerCtl(c, dt) else botThink(c, dt)
    }

    private fun playerCtl(c: Champ, dt: Float) {
        val l = hypot(joyDx, joyDy)
        if (l > 0.15f) moveU(c, joyDx, joyDy, 330f, dt)
        if (bId[4] != -1) {
            val tg = nearest(c.x, c.y, c.team, atkRange(c), -150f)
            basicAttack(c, tg)
        }
    }

    internal fun atkRange(c: Champ): Float = if (c.hero >= 2) 190f else CH_RANGE

    private fun basicAttack(c: Champ, tg: U?) {
        if (tg == null || tg.dead || c.atkCd > 0f) return
        if (c.spinT > 0f || c.dashT > 0f) return
        if (c.hero >= 2) { meleeAttack(c, tg); return }
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
        if (c.dead || c.dashT > 0f) return false
        if (c.silT > 0f && !(c.hero == 2 && recast && ((s == 1 && c.shT > 0f) || (s == 3 && c.rsT > 0f)))) return false

        if (c.hero == 2) return castZed(c, s, ang, dist, recast)
        if (c.hero == 3) return castGaren(c, s, ang, dist)
        if (s == 2 && recast) {
            val z = zones.firstOrNull { it.owner === c }
            if (z != null) { z.life = 0f; return true }
        }
        if (c.cd[s] > 0f || c.mana < c.cost[s]) return false
        c.mana -= c.cost[s]
        c.cd[s] = c.cdBase[s]
        c.face = ang
        c.castS = s
        c.castMax = when (s) { 3 -> 1.15f; 2 -> 0.9f; else -> 0.8f }
        c.castT = c.castMax
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

    internal fun rangeOf(c: Champ, s: Int): Float = when (c.hero) {
        2 -> floatArrayOf(900f, 650f, 300f, 700f)[s]
        3 -> floatArrayOf(300f, 0f, 300f, 560f)[s]
        else -> RANGE[s]
    }

    private fun castPlayer(slot: Int) {
        if (pl.dead || over != 0) return
        val ang: Float
        val dist: Float
        if (bDrag[slot]) {
            ang = atan2(bAy[slot], bAx[slot])
            dist = (hypot(bAx[slot], bAy[slot]) / d * 3.2f).coerceIn(150f, 780f)
        } else {
            val r = autoAim(pl, rangeOf(pl, slot))
            ang = r.first
            dist = r.second
        }
        cast(pl, slot, ang, dist, true)
    }

    // ------------------------------------------------------------------ IA

    private fun botThink(b: Champ, dt: Float) {
        if (b.hero >= 2) { botMelee(b, dt); return }
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

    // ============================================== ZED E GAREN (HABILIDADES)

    internal fun addFx(kind: Int, x: Float, y: Float, ang: Float, max: Float, team: Int): Fx {
        val f = Fx(kind, x, y, ang, max, team)
        if (fxs.size > 60) fxs.removeAt(0)
        fxs.add(f)
        return f
    }

    private fun begin(c: Champ, s: Int, ang: Float, castMax: Float): Boolean {
        if (c.cd[s] > 0f || c.mana < c.cost[s]) return false
        c.mana -= c.cost[s]
        c.cd[s] = c.cdBase[s]
        c.face = ang
        c.castS = s
        c.castMax = castMax
        c.castT = castMax
        return true
    }

    private fun schedule(c: Champ, tt: Float, s: Int, ang: Float, dist: Float, tg: U?) {
        c.evT = tt; c.evS = s; c.evAng = ang; c.evDist = dist; c.evTg = tg
    }

    private fun pickEnemy(c: Champ, range: Float): Champ? {
        val o = if (c === pl) bot else pl
        if (o.dead || o.team == c.team || o.ghost > 0f || hiddenFor(o, c.team)) return null
        return if (hypot(o.x - c.x, o.y - c.y) - o.r <= range) o else null
    }

    private fun meleeAttack(c: Champ, tg: U) {

        if (hypot(tg.x - c.x, tg.y - c.y) - tg.r > 205f) return
        val zed = c.hero == 2
        c.atkCd = if (zed) 0.6f else 0.72f
        c.face = atan2(tg.y - c.y, tg.x - c.x)
        c.swingMax = if (zed) 0.30f else 0.42f
        c.swing = c.swingMax
        c.swingN++
        var dm = (if (zed) 24f else 30f) + c.ap * (if (zed) 0.3f else 0.32f)
        c.swingBig = false
        if (!zed && c.qT > 0f) {
            dm += 45f + c.ap * 0.55f
            c.qT = 0f
            c.swingBig = true
            c.swingMax = 0.5f
            c.swing = 0.5f
        }
        if (zed && tg.hp < tg.maxHp * 0.5f && c.passCd <= 0f) {
            dm += tg.maxHp * 0.05f
            c.passCd = 8f
        }
        c.hitTg = tg
        c.hitDmg = dm
        c.hitBig = c.swingBig
        c.hitT = c.swingMax * 0.45f
    }

    private fun applyHit(c: Champ) {
        val tg = c.hitTg ?: return
        c.hitTg = null
        if (tg.dead || c.dead) return
        if (hypot(tg.x - c.x, tg.y - c.y) - tg.r > 320f) return
        hurt(tg, c.hitDmg, c)

        burst(tg.x, tg.y, if (c.hero == 2) C(0xFFFF5A5A) else C(0xFFFFE082), if (c.hitBig) 22 else 8)
        if (c.hitBig) {
            tg.silT = max(tg.silT, 1.5f)
            addFx(5, tg.x, tg.y, c.face, 0.55f, c.team)
            shake = max(shake, 0.25f)
            texts.add(FT(tg.x, tg.y, "SILENCIO", 0.9f, C(0xFFFFE082)))
        }
    }

    // ---------------------------------------------------------------- ZED

    private fun castZed(c: Champ, s: Int, ang: Float, dist: Float, recast: Boolean): Boolean {
        when (s) {
            0 -> {
                if (!begin(c, 0, ang, 0.55f)) return false
                schedule(c, 0.2f, 0, ang, 0f, null)
            }
            1 -> {
                if (c.shT > 0f) {
                    if (!recast || c.shMoving) return false
                    swapWith(c, c.shX, c.shY, true)
                    return true
                }
                if (!begin(c, 1, ang, 0.5f)) return false
                val d = dist.coerceIn(280f, 650f)
                c.shX = c.x
                c.shY = c.y
                c.shTX = (c.x + cos(ang) * d).coerceIn(60f, W - 60f)
                c.shTY = (c.y + sin(ang) * d).coerceIn(LT + 30f, LB - 30f)
                c.shMoving = true
                c.shT = 5f
                addFx(0, c.x, c.y, ang, 0.7f, c.team)

            }
            2 -> {
                if (!begin(c, 2, ang, 0.55f)) return false
                schedule(c, 0.16f, 2, ang, 0f, null)
            }
            else -> {
                if (c.rsT > 0f && recast) {
                    swapWith(c, c.rsX, c.rsY, false)
                    return true
                }
                val tg = pickEnemy(c, 700f) ?: return false
                if (!begin(c, 3, atan2(tg.y - c.y, tg.x - c.x), 0.6f)) return false
                c.rsX = c.x
                c.rsY = c.y
                c.rsT = 4.5f
                c.dashTg = tg
                c.dashT = 0.5f
                c.ghost = 0.75f
                addFx(0, c.x, c.y, c.face, 0.8f, c.team)
            }
        }
        return true
    }

    private fun swapWith(c: Champ, sx: Float, sy: Float, wShadow: Boolean) {
        val ox = c.x
        val oy = c.y
        addFx(9, ox, oy, 0f, 0.7f, c.team)
        addFx(9, sx, sy, 0f, 0.7f, c.team)
        c.x = sx
        c.y = sy
        if (wShadow) {

            c.shX = ox
            c.shY = oy
            c.shT = min(c.shT, 2.5f)
        } else {
            c.rsT = 0f
        }
        c.castS = 4
        c.castMax = 0.35f
        c.castT = 0.35f
        c.ghost = max(c.ghost, 0.12f)
        burst(c.x, c.y, C(0xFFB71C1C), 14)
    }

    private fun updShadowMove(c: Champ, dt: Float) {
        if (!c.shMoving) return
        val dx = c.shTX - c.shX
        val dy = c.shTY - c.shY
        val l = hypot(dx, dy)
        val stp = 2600f * dt
        if (l <= stp) {
            c.shX = c.shTX
            c.shY = c.shTY
            c.shMoving = false
        } else {
            c.shX += dx / l * stp
            c.shY += dy / l * stp
        }
    }

    private fun throwShuriken(c: Champ, x: Float, y: Float, ang: Float, dm: Float) {
        val sx = x + cos(ang) * 30f
        val sy = y + sin(ang) * 30f

        val p = Proj(sx, sy, cos(ang) * 1500f, sin(ang) * 1500f, c.team, dm, 950f, 36f, 4, c, 0f, null)
        p.pierce = 99
        projs.add(p)
        burst(sx, sy, C(0xFFFF5A5A), 6)
    }

    private fun slashAt(c: Champ, x: Float, y: Float, dm: Float, hit: HashSet<U>) {
        addFx(1, x, y, c.evAng, 0.55f, c.team)
        for (u in all) {
            if (u.dead || u.team == c.team || hit.contains(u)) continue
            if (hypot(u.x - x, u.y - y) < 235f + u.r) {
                hit.add(u)
                hurt(u, dm, c)
                u.slowT = max(u.slowT, 1.4f)
                burst(u.x, u.y, C(0xFFFF5A5A), 8)
            }
        }
        shake = max(shake, 0.2f)
    }

    private fun updDash(c: Champ, dt: Float) {
        val tg = c.dashTg
        c.dashT -= dt
        if (tg == null || tg.dead) {
            c.dashT = 0f
            c.dashTg = null
            return
        }
        var dx = tg.x - c.rsX
        var dy = tg.y - c.rsY
        val l0 = hypot(dx, dy)
        if (l0 > 0.01f) { dx /= l0; dy /= l0 } else { dx = 1f; dy = 0f }

        val gap = tg.r + c.r + 24f
        val destX = (tg.x + dx * gap).coerceIn(60f, W - 60f)
        val destY = (tg.y + dy * gap).coerceIn(LT + 24f, LB - 24f)
        val mx = destX - c.x
        val my = destY - c.y
        val l = hypot(mx, my)
        val stp = 2800f * dt
        c.mv = 0.1f
        c.walk += dt * 90f
        if (l > 0.01f) c.face = atan2(my, mx)
        if (l <= stp + 18f || c.dashT <= 0f) {
            c.x = destX
            c.y = destY
            c.dashT = 0f
            c.dashTg = null
            c.face = atan2(tg.y - c.y, tg.x - c.x)
            c.castS = 3
            c.castMax = 0.6f
            c.castT = 0.6f
            c.ghost = max(c.ghost, 0.1f)
            hurt(tg, 45f + c.ap * 0.4f, c)
            tg.deathT = 3f
            tg.deathAcc = 0f
            tg.deathOwner = c
            addFx(4, tg.x, tg.y, c.face, 0.6f, c.team)
            shake = max(shake, 0.5f)
            burst(tg.x, tg.y, C(0xFFFF1744), 20)
            texts.add(FT(tg.x, tg.y, "MARCA DA MORTE", 1.0f, C(0xFFFF5252)))
        } else {
            c.x += mx / l * stp
            c.y += my / l * stp
        }

    }

    private fun detonate(u: U) {
        val o = u.deathOwner ?: return
        u.deathOwner = null
        if (u.dead) return
        val dm = 90f + o.ap * 0.6f + u.deathAcc * 0.45f
        addFx(3, u.x, u.y, 0f, 1.0f, o.team)
        hurt(u, dm, o)
        shake = max(shake, 0.9f)
        burst(u.x, u.y, C(0xFFFF1744), 34)
        texts.add(FT(u.x, u.y, "EXECUCAO", 1.1f, C(0xFFFF5252)))
        u.deathAcc = 0f
    }

    // -------------------------------------------------------------- GAREN

    private fun castGaren(c: Champ, s: Int, ang: Float, dist: Float): Boolean {
        when (s) {
            0 -> {
                if (!begin(c, 0, ang, 0.45f)) return false
                c.qT = 4.5f
                c.haste = 1.7f
                c.slowT = 0f
                addFx(6, c.x, c.y, ang, 0.7f, c.team)
            }
            1 -> {
                if (!begin(c, 1, ang, 0.65f)) return false
                c.wT = 2.4f
                c.shield = 55f + c.ap * 0.4f
                c.shieldT = 2.4f
                addFx(6, c.x, c.y, ang, 0.8f, c.team)

            }
            2 -> {
                if (!begin(c, 2, ang, 0.4f)) return false
                schedule(c, 0.1f, 2, ang, 0f, null)
            }
            else -> {
                val tg = pickEnemy(c, 560f) ?: return false
                val a2 = atan2(tg.y - c.y, tg.x - c.x)
                if (!begin(c, 3, a2, 1.3f)) return false
                schedule(c, 0.7f, 3, a2, 0f, tg)
                val f = addFx(8, tg.x, tg.y, a2, 1.6f, c.team)
                f.tgt = tg
            }
        }
        return true
    }

    private fun updSpin(c: Champ, dt: Float) {
        c.spinT -= dt
        c.spinTick -= dt
        if (c.spinTick <= 0f) {
            c.spinTick = 0.4f
            val dm = 15f + c.ap * 0.2f
            var any = false
            for (u in all) {
                if (u.dead || u.team == c.team) continue
                if (hypot(u.x - c.x, u.y - c.y) < 250f + u.r) {
                    val k = if (u is Minion) 1.4f else if (u is Tower) 0.6f else 1f
                    hurt(u, dm * k, c)
                    burst(u.x, u.y, C(0xFFFFE082), 4)
                    any = true
                }

            }
            if (any) shake = max(shake, 0.12f)
        }
        if (c.spinT <= 0f) addFx(7, c.x, c.y, 0f, 0.6f, c.team)
    }

    private fun justice(c: Champ) {
        val tg = c.evTg ?: return
        if (tg.dead) return
        val miss = tg.maxHp - tg.hp
        val dm = 130f + c.ap * 0.6f + miss * 0.28f
        hurt(tg, dm, c)
        burst(tg.x, tg.y, C(0xFFFFF59D), 40)
        shake = max(shake, 1.0f)
        texts.add(FT(tg.x, tg.y, "JUSTICA", 1.2f, C(0xFFFFE082)))
    }

    private fun castEvent(c: Champ) {
        if (c.dead) return
        if (c.hero == 2) {
            when (c.evS) {
                0 -> {
                    val dm = 55f + c.ap * 0.55f + c.level * 6f
                    throwShuriken(c, c.x, c.y, c.evAng, dm)
                    if (c.shT > 0f && !c.shMoving) throwShuriken(c, c.shX, c.shY, c.evAng, dm * 0.6f)
                }
                2 -> {
                    val dm = 40f + c.ap * 0.5f + c.level * 5f
                    val hit = HashSet<U>()
                    slashAt(c, c.x, c.y, dm, hit)
                    if (c.shT > 0f && !c.shMoving) slashAt(c, c.shX, c.shY, dm, hit)
                }

            }
        } else if (c.hero == 3) {
            when (c.evS) {
                2 -> { c.spinT = 2.4f; c.spinTick = 0f }
                3 -> justice(c)
            }
        }
    }

    // --------------------------------------------------------- IA (Zed/Garen)

    private fun botMelee(b: Champ, dt: Float) {
        if (b.dashT > 0f) return
        val p = pl
        val dp = if (p.dead || hiddenFor(p, b.team)) 1e9f else hypot(p.x - b.x, p.y - b.y)
        val homeX = W - 380f
        var minX = 0f
        for (tw in towers) if (!tw.dead && tw.team == 0) minX = max(minX, tw.x + if (tw.nexus) 560f else 680f)
        val zed = b.hero == 2

        if (b.hp < b.maxHp * 0.25f) {
            if (zed && b.shT > 0f && !b.shMoving && hypot(b.shX - homeX, b.shY - CY) < hypot(b.x - homeX, b.y - CY)) {
                cast(b, 1, 0f, 0f, true)
            }
            if (!zed) cast(b, 1, 0f, 0f, false)
            moveU(b, homeX - b.x, CY - b.y, 310f, dt)
            return
        }
        if (dp < 900f && p.x > minX - 200f) {
            val ang = atan2(p.y - b.y, p.x - b.x)
            if (zed) {
                if (dp < 650f && p.hp < p.maxHp * 0.65f) cast(b, 3, ang, 0f, false)

                if (dp > 330f && dp < 650f) cast(b, 1, ang, min(dp, 600f), false)
                if (dp < 900f && dp > 260f) cast(b, 0, ang, 0f, false)
                if (dp < 250f) cast(b, 2, ang, 0f, false)
            } else {
                if (dp > 330f && dp < 750f) cast(b, 0, ang, 0f, false)
                if (dp < 560f && p.hp < p.maxHp * 0.5f) cast(b, 3, ang, 0f, false)
                if (dp < 300f) cast(b, 2, ang, 0f, false)
                if (b.hp < b.maxHp * 0.6f && dp < 420f) cast(b, 1, ang, 0f, false)
            }
            if (dp > 150f && !(b.x <= minX && p.x < b.x)) moveU(b, cos(ang), sin(ang), 305f, dt)
            if (dp < 210f + p.r) basicAttack(b, p)
        } else {
            val tg = nearest(b.x, b.y, b.team, 520f, -50f)
            if (tg != null) {
                val dd = hypot(tg.x - b.x, tg.y - b.y) - tg.r
                if (tg !is Tower && dd > 150f) moveU(b, tg.x - b.x, tg.y - b.y, 295f, dt)
                basicAttack(b, tg)
            } else {
                var front = W - 1200f
                for (m in minions) if (!m.dead && m.team == 1) front = min(front, m.x)
                val destX = max(minX, front + 200f)
                val dx = destX - b.x
                if (abs(dx) > 40f) moveU(b, dx, CY - b.y, 295f, dt)
            }
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
                        if (q.kind != 4) u.markT = 6f
                        burst(u.x, u.y, if (q.kind == 4) C(0xFFFF5A5A) else C(0xFFFFF59D), 12)
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
        Matrix.setLookAtM(viewM, 0, camX + sx, 880f, CY + 600f + sz, camX + sx, 0f, CY + sz * 0.5f, 0f, 1f, 0f)
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
            bar(c, tw, if (tw.nexus) 170f else 105f, if (tw.nexus) 360f else 225f)
        }
        for (m in minions) {
            if (m.dead || abs(m.x - camX) > viewW || hiddenFor(m, 0)) continue
            bar(c, m, 46f, 96f)
        }
        for (ch in champs) {
            if (ch.dead || hiddenFor(ch, 0)) continue
            bar(c, ch, 92f, 170f)
            if (proj(ch.x, groundH(ch.x, ch.y) + 170f, ch.y)) {
                tp.textSize = 12f * d
                tp.textAlign = Paint.Align.CENTER
                tp.color = C(0xFFFFFFFF)
                c.drawText("" + ch.level, sp[0] - 54f * sp[2], sp[1] + 8f * d, tp)
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

        if (picking) { drawPick(c, w, h); return }
        // retrato + barras
        val px = 12f * d
        val py = 10f * d
        blk(c, px, py, px + 44f * d, py + 44f * d, C(0xFF263238))
        pt.color = when (pl.hero) { 2 -> C(0xFF1B1C26); 3 -> C(0xFF6D4C41); else -> C(0xFFFFD54F) }
        c.drawRect(px + 5f * d, py + 4f * d, px + 39f * d, py + 22f * d, pt)
        pt.color = if (pl.hero == 2) C(0xFF3A3A4D) else C(0xFFFFCCA8)
        c.drawRect(px + 11f * d, py + 14f * d, px + 33f * d, py + 36f * d, pt)
        pt.color = if (pl.hero == 2) C(0xFFFF3D3D) else C(0xFF212121)
        c.drawRect(px + 15f * d, py + 22f * d, px + 19f * d, py + 26f * d, pt)
        c.drawRect(px + 25f * d, py + 22f * d, px + 29f * d, py + 26f * d, pt)
        pt.color = when (pl.hero) { 2 -> C(0xFFB71C1C); 3 -> C(0xFF3F7FD6); else -> C(0xFF3F51B5) }
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
            val rc = pl.hero == 2 && !pl.dead && ((i == 1 && pl.shT > 0f && !pl.shMoving) || (i == 3 && pl.rsT > 0f))
            val ready = rc || (pl.cd[i] <= 0f && pl.mana >= pl.cost[i] && !pl.dead && pl.silT <= 0f)
            pt.color = C(0xCC1B1F2B)
            c.drawCircle(bx[i], by[i], br[i], pt)
            drawGlyph(c, i, bx[i], by[i], br[i], ready)
            if (pl.cd[i] > 0f && !rc) {
                pt.color = C(0xAA000000)
                val sweep = 360f * (pl.cd[i] / pl.cdBase[i]).coerceIn(0f, 1f)
                c.drawArc(bx[i] - br[i], by[i] - br[i], bx[i] + br[i], by[i] + br[i], -90f, sweep, true, pt)
                hudText(c, Math.ceil(pl.cd[i].toDouble()).toInt().toString(), bx[i], by[i] + 5f * d, 14f, C(0xFFFFFFFF), Paint.Align.CENTER)
            }
            st.color = if (rc) C(0xFFFF5252) else if (ready) C(0xFFFFD54F) else C(0xFF607D8B)
            st.strokeWidth = 3f * d
            c.drawCircle(bx[i], by[i], br[i], st)
            if (rc) hudText(c, "↺", bx[i] + br[i] * 0.55f, by[i] + br[i] * 0.75f, 13f, C(0xFFFF8A80), Paint.Align.CENTER)
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
            if (overT > 1f) hudText(c, "Toque para escolher o campeão  (Voltar = menu)", w / 2f, h / 2f + 34f * d, 14f, C(0xFFFFFFFF), Paint.Align.CENTER)
        }
    }

    // ============================================ TELA DE ESCOLHA / ÍCONES

    private fun star(c: Canvas, cx: Float, cy: Float, ro: Float, ri: Float, n: Int, rot: Float) {
        path.reset()
        for (i in 0 until n * 2) {
            val a = rot + i * Math.PI.toFloat() / n
            val rr = if (i % 2 == 0) ro else ri
            val px = cx + cos(a) * rr
            val py = cy + sin(a) * rr
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, pt)
    }

    private fun drawEmblem(c: Canvas, hero: Int, cx: Float, cy: Float, r: Float) {
        pt.style = Paint.Style.FILL
        st.style = Paint.Style.STROKE
        when (hero) {
            0 -> {
                pt.color = C(0x55FFD54F)
                c.drawCircle(cx, cy, r, pt)
                pt.color = C(0xFFFFF59D)
                star(c, cx, cy, r * 0.9f, r * 0.28f, 8, t * 0.5f)
                pt.color = C(0xFFFFFFFF)
                c.drawCircle(cx, cy, r * 0.22f, pt)
            }
            1 -> {
                pt.color = C(0x554DB6AC)
                c.drawCircle(cx, cy, r, pt)
                st.color = C(0xFF80CBC4)

                st.strokeWidth = 3.5f * d
                for (i in 0..2) {
                    c.drawArc(cx - r * (0.9f - i * 0.2f), cy - r * (0.9f - i * 0.2f), cx + r * (0.9f - i * 0.2f), cy + r * (0.9f - i * 0.2f), 200f + i * 40f + t * 40f, 200f, false, st)
                }
            }
            2 -> {
                pt.color = C(0x55E53935)
                c.drawCircle(cx, cy, r, pt)
                pt.color = C(0xFF1E1F2C)
                star(c, cx, cy, r * 0.95f, r * 0.26f, 4, t * 1.2f)
                pt.color = C(0xFFFF3D3D)
                star(c, cx, cy, r * 0.5f, r * 0.14f, 4, t * 1.2f + 0.78f)
                pt.color = C(0xFFFFFFFF)
                c.drawCircle(cx, cy, r * 0.1f, pt)
            }
            else -> {
                pt.color = C(0x554F7FE8)
                c.drawCircle(cx, cy, r, pt)
                path.reset()
                path.moveTo(cx - r * 0.55f, cy - r * 0.6f)
                path.lineTo(cx + r * 0.55f, cy - r * 0.6f)
                path.lineTo(cx + r * 0.55f, cy + r * 0.1f)
                path.lineTo(cx, cy + r * 0.8f)
                path.lineTo(cx - r * 0.55f, cy + r * 0.1f)
                path.close()
                pt.color = C(0xFF3F6FD0)
                c.drawPath(path, pt)
                pt.color = C(0xFFFFD54F)
                c.drawRect(cx - r * 0.07f, cy - r * 0.95f, cx + r * 0.07f, cy + r * 0.55f, pt)
                c.drawRect(cx - r * 0.38f, cy - r * 0.35f, cx + r * 0.38f, cy - r * 0.2f, pt)
            }
        }

    }

    private fun drawPick(c: Canvas, w: Float, h: Float) {
        pt.style = Paint.Style.FILL
        pt.color = C(0xE6080C18)
        c.drawRect(0f, 0f, w, h, pt)
        hudText(c, "ESCOLHA SEU CAMPEÃO", w / 2f, h * 0.13f, 20f, C(0xFFFFD54F), Paint.Align.CENTER)
        hudText(c, "toque em um card para jogar", w / 2f, h * 0.13f + 20f * d, 11f, C(0xFFB0BEC5), Paint.Align.CENTER)
        val gap = 12f * d
        val cw = (w - gap * 5f) / 4f
        val top = h * 0.24f
        val btm = h * 0.93f
        val names = arrayOf("LUX", "YASUO", "ZED", "GAREN")
        val roles = arrayOf("Maga de luz", "Espadachim (kit de luz)", "Assassino das sombras", "Guerreiro demaciano")
        val kind = arrayOf("À distância", "À distância", "Corpo a corpo", "Corpo a corpo")
        val accent = intArrayOf(C(0xFFFFD54F), C(0xFF4DB6AC), C(0xFFE53935), C(0xFF4F7FE8))
        val kit = arrayOf(
            arrayOf("Q Prisão de Luz", "W Barreira", "E Singularidade", "R Centelha Final"),
            arrayOf("Q Prisão de Luz", "W Barreira", "E Singularidade", "R Centelha Final"),
            arrayOf("Q Shuriken Navalha", "W Sombra Viva (2x)", "E Corte Sombrio", "R Marca da Morte"),
            arrayOf("Q Golpe Decisivo", "W Coragem", "E Julgamento", "R Justiça Demaciana")
        )
        for (i in 0..3) {
            val l = gap + i * (cw + gap)
            val r = l + cw
            pkl[i] = l; pkr[i] = r; pkt[i] = top; pkb[i] = btm
            val sel = i == pickSel
            pt.color = if (sel) C(0xFF1B2440) else C(0xFF121829)
            c.drawRoundRect(l, top, r, btm, 14f * d, 14f * d, pt)
            st.style = Paint.Style.STROKE
            st.color = accent[i]
            st.strokeWidth = (if (sel) 4f else 2f) * d

            c.drawRoundRect(l, top, r, btm, 14f * d, 14f * d, st)
            val cx = (l + r) / 2f
            drawEmblem(c, i, cx, top + (btm - top) * 0.2f, cw * 0.17f)
            hudText(c, names[i], cx, top + (btm - top) * 0.42f, 17f, accent[i], Paint.Align.CENTER)
            hudText(c, roles[i], cx, top + (btm - top) * 0.50f, 9.5f, C(0xFFCFD8DC), Paint.Align.CENTER)
            hudText(c, kind[i], cx, top + (btm - top) * 0.57f, 9.5f, C(0xFF90A4AE), Paint.Align.CENTER)
            for (k in 0..3) {
                hudText(c, kit[i][k], cx, top + (btm - top) * (0.68f + 0.075f * k), 9.5f, C(0xFFFFFFFF), Paint.Align.CENTER)
            }
        }
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, col: Int, a: Int) {
        pt.style = Paint.Style.FILL
        for (k in 0..3) {
            val rr = r * (1f - k * 0.2f)
            pt.color = ((a * (0.18f + 0.16f * k)).toInt().coerceIn(0, 255) shl 24) or (col and 0xFFFFFF)
            c.drawCircle(x, y, rr, pt)
        }
    }

    private fun bold(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, w: Float, col: Int, a: Int) {
        st.style = Paint.Style.STROKE
        st.strokeWidth = w * 1.9f
        st.color = (a shl 24) or 0x101018
        c.drawLine(x1, y1, x2, y2, st)
        st.strokeWidth = w
        st.color = (a shl 24) or (col and 0xFFFFFF)
        c.drawLine(x1, y1, x2, y2, st)
    }

    private fun boldCircle(c: Canvas, x: Float, y: Float, rr: Float, w: Float, col: Int, a: Int) {
        st.style = Paint.Style.STROKE
        st.strokeWidth = w * 1.9f
        st.color = (a shl 24) or 0x101018
        c.drawCircle(x, y, rr, st)
        st.strokeWidth = w
        st.color = (a shl 24) or (col and 0xFFFFFF)
        c.drawCircle(x, y, rr, st)
    }

    private fun poly(c: Canvas, pts: FloatArray, col: Int, a: Int, edge: Int) {
        path.reset()
        for (i in 0 until pts.size / 2) {
            if (i == 0) path.moveTo(pts[0], pts[1]) else path.lineTo(pts[i * 2], pts[i * 2 + 1])
        }
        path.close()
        pt.style = Paint.Style.FILL
        pt.color = (a shl 24) or (col and 0xFFFFFF)
        c.drawPath(path, pt)
        st.style = Paint.Style.STROKE
        st.strokeWidth = 2f * d
        st.color = (a shl 24) or (edge and 0xFFFFFF)
        c.drawPath(path, st)
    }

    private fun sword(c: Canvas, x: Float, y: Float, r: Float, ang: Float, col: Int, hilt: Int, a: Int) {
        val ca = cos(ang)
        val sa = sin(ang)
        fun p(u: Float, v: Float) = floatArrayOf(x + ca * u - sa * v, y + sa * u + ca * v)
        val pts = ArrayList<Float>()
        for (q in listOf(p(r * 0.9f, 0f), p(r * 0.15f, r * 0.13f), p(-r * 0.35f, r * 0.13f), p(-r * 0.35f, -r * 0.13f), p(r * 0.15f, -r * 0.13f))) { pts.add(q[0]); pts.add(q[1]) }
        poly(c, pts.toFloatArray(), col, a, 0xFFFFFF)
        val g1 = p(-r * 0.4f, -r * 0.45f)
        val g2 = p(-r * 0.4f, r * 0.45f)
        bold(c, g1[0], g1[1], g2[0], g2[1], 3.2f * d, hilt, a)
        val h1 = p(-r * 0.4f, 0f)
        val h2 = p(-r * 0.85f, 0f)
        bold(c, h1[0], h1[1], h2[0], h2[1], 3.4f * d, 0x8D6E63, a)
    }

    private fun drawGlyphNew(c: Canvas, hero: Int, i: Int, x: Float, y: Float, r: Float, ready: Boolean) {
        val a = if (ready) 0xFF else 0x88
        val pu = 0.5f + 0.5f * sin(t * 4f + i)
        pt.style = Paint.Style.FILL
        st.style = Paint.Style.STROKE
        when (hero) {
            2 -> when (i) {
                0 -> {
                    glow(c, x, y, r * 0.95f, 0xFF2A3A, a)
                    star(c, x, y, r * 0.82f, r * 0.24f, 4, t * 2.2f)
                    pt.color = (a shl 24) or 0x2A2A38
                    c.drawCircle(x, y, r * 0.26f, pt)
                    pt.color = (a shl 24) or 0xFF4A4A
                    c.drawCircle(x, y, r * 0.13f, pt)
                }
                1 -> {
                    glow(c, x, y, r * 0.95f, 0x9B2D8F, a)
                    pt.color = (a shl 24) or 0x14101E
                    c.drawCircle(x - r * 0.18f, y + r * 0.1f, r * 0.42f, pt)
                    boldCircle(c, x - r * 0.18f, y + r * 0.1f, r * 0.42f, 2f * d, 0xC03A9A, a)
                    pt.color = (a shl 24) or 0xFF3A4A
                    c.drawCircle(x - r * 0.1f, y + r * 0.02f, r * 0.08f, pt)
                    bold(c, x + r * 0.08f, y - r * 0.12f, x + r * 0.7f, y - r * 0.5f, 3.4f * d, 0xFF6A7A, a)
                    bold(c, x + r * 0.7f, y - r * 0.5f, x + r * 0.42f, y - r * 0.52f, 3f * d, 0xFF6A7A, a)
                    bold(c, x + r * 0.7f, y - r * 0.5f, x + r * 0.66f, y - r * 0.22f, 3f * d, 0xFF6A7A, a)
                }
                2 -> {
                    glow(c, x, y, r * 0.95f, 0xFF2A3A, a)
                    boldCircle(c, x, y, r * 0.62f, 2.4f * d, 0xFFFFFF, a)
                    bold(c, x - r * 0.55f, y - r * 0.55f, x + r * 0.55f, y + r * 0.55f, 4.2f * d, 0xFF4A5A, a)
                    bold(c, x + r * 0.55f, y - r * 0.55f, x - r * 0.55f, y + r * 0.55f, 4.2f * d, 0xFF4A5A, a)
                }
                else -> {
                    glow(c, x, y, r * (0.9f + 0.08f * pu), 0xFF1744, a)
                    boldCircle(c, x, y, r * 0.55f, 3f * d, 0xFF3A4A, a)
                    for (k in 0..3) {
                        val an = k * 1.5708f + t
                        bold(c, x + cos(an) * r * 0.62f, y + sin(an) * r * 0.62f, x + cos(an) * r * 0.92f, y + sin(an) * r * 0.92f, 3.4f * d, 0xFFFFFF, a)
                    }
                    pt.color = (a shl 24) or 0xFFFFFF
                    c.drawCircle(x, y, r * 0.16f, pt)
                    pt.color = (a shl 24) or 0xFF1744
                    c.drawCircle(x, y, r * 0.09f, pt)
                }
            }
            3 -> when (i) {
                0 -> {
                    glow(c, x, y, r * 0.95f, 0xFFC94A, a)
                    sword(c, x, y, r * 0.95f, -0.8f, 0xE3EAF5, 0xFFD54F, a)
                    bold(c, x - r * 0.75f, y + r * 0.55f, x - r * 0.4f, y + r * 0.55f, 2.4f * d, 0xFFF59D, a)
                    bold(c, x - r * 0.8f, y + r * 0.8f, x - r * 0.35f, y + r * 0.8f, 2.4f * d, 0xFFF59D, a)
                }
                1 -> {
                    glow(c, x, y, r * 0.95f, 0x4FA3FF, a)
                    poly(c, floatArrayOf(x - r * 0.55f, y - r * 0.6f, x + r * 0.55f, y - r * 0.6f, x + r * 0.55f, y + r * 0.1f, x, y + r * 0.8f, x - r * 0.55f, y + r * 0.1f), 0x3F7FD6, a, 0xE3F2FF)
                    poly(c, floatArrayOf(x - r * 0.3f, y - r * 0.4f, x + r * 0.3f, y - r * 0.4f, x + r * 0.3f, y + r * 0.05f, x, y + r * 0.45f, x - r * 0.3f, y + r * 0.05f), 0x7FB8FF, a, 0xFFFFFF)
                }
                2 -> {
                    glow(c, x, y, r * 0.95f, 0xFFE9A0, a)
                    for (k in 0..2) {
                        val an = t * 9f + k * 2.0944f
                        sword(c, x, y, r * 0.8f, an, 0xDDE6F2, 0xFFD54F, a)
                    }
                    boldCircle(c, x, y, r * 0.82f, 1.6f * d, 0xFFFFFF, a / 2)
                }
                else -> {
                    glow(c, x, y, r * (0.9f + 0.08f * pu), 0xFFE9A0, a)
                    sword(c, x + r * 0.1f, y + r * 0.1f, r * 1.05f, 2.356f, 0xF2F6FF, 0xFFD54F, a)
                    boldCircle(c, x, y, r * 0.8f, 2.2f * d, 0xFFE9A0, a)
                }
            }
            1 -> when (i) {
                0 -> {
                    glow(c, x, y, r * 0.95f, 0x7FD8FF, a)
                    bold(c, x - r * 0.7f, y + r * 0.45f, x + r * 0.7f, y - r * 0.45f, 4f * d, 0xE1F5FE, a)
                    bold(c, x + r * 0.7f, y - r * 0.45f, x + r * 0.35f, y - r * 0.5f, 3.2f * d, 0xE1F5FE, a)
                    bold(c, x + r * 0.7f, y - r * 0.45f, x + r * 0.55f, y - r * 0.1f, 3.2f * d, 0xE1F5FE, a)
                }
                1 -> {
                    glow(c, x, y, r * 0.95f, 0x7FD8FF, a)
                    for (k in -1..1) bold(c, x - r * 0.55f, y + k * r * 0.35f, x + r * 0.55f, y + k * r * 0.35f - r * 0.1f, 3.4f * d, 0xB3E5FC, a)
                    bold(c, x, y - r * 0.65f, x, y + r * 0.65f, 3.4f * d, 0xFFFFFF, a)
                }
                2 -> {
                    glow(c, x, y, r * 0.95f, 0x7FD8FF, a)
                    bold(c, x - r * 0.7f, y, x + r * 0.5f, y, 4f * d, 0xE1F5FE, a)
                    bold(c, x + r * 0.5f, y, x + r * 0.2f, y - r * 0.3f, 3.2f * d, 0xE1F5FE, a)
                    bold(c, x + r * 0.5f, y, x + r * 0.2f, y + r * 0.3f, 3.2f * d, 0xE1F5FE, a)
                }
                else -> {
                    glow(c, x, y, r * 0.95f, 0x4FC3F7, a)
                    for (k in 0..3) boldCircle(c, x, y - r * 0.5f + k * r * 0.33f, r * (0.2f + 0.14f * k), 2.4f * d, 0xE1F5FE, a)
                }
            }
            else -> when (i) {
                0 -> {
                    glow(c, x, y, r * 0.95f, 0xFFD54F, a)
                    boldCircle(c, x, y, r * 0.45f, 3.4f * d, 0xFFF59D, a)
                    bold(c, x - r * 0.9f, y, x - r * 0.45f, y, 3.4f * d, 0xFFFFFF, a)
                    bold(c, x + r * 0.45f, y, x + r * 0.9f, y, 3.4f * d, 0xFFFFFF, a)
                    pt.color = (a shl 24) or 0xFFFFFF
                    c.drawCircle(x, y, r * 0.16f, pt)
                }
                1 -> {
                    glow(c, x, y, r * 0.95f, 0x81D4FA, a)
                    pt.color = (a shl 24) or 0xB3E5FC
                    c.drawCircle(x, y, r * 0.55f, pt)
                    boldCircle(c, x, y, r * 0.55f, 2.4f * d, 0xFFFFFF, a)
                    star(c, x, y, r * 0.32f, r * 0.1f, 4, 0.785f)
                }
                2 -> {
                    glow(c, x, y, r * 0.95f, 0xFFEB3B, a)
                    pt.color = (a shl 24) or 0xFFF176
                    c.drawCircle(x, y, r * 0.45f, pt)
                    pt.color = (a shl 24) or 0xFFFFFF
                    c.drawCircle(x, y, r * 0.22f, pt)
                    for (k in 0..7) {
                        val an = k * 0.785f + t * 1.5f
                        bold(c, x + cos(an) * r * 0.58f, y + sin(an) * r * 0.58f, x + cos(an) * r * 0.82f, y + sin(an) * r * 0.82f, 2.4f * d, 0xFFFFFF, a)
                    }
                }
                else -> {
                    glow(c, x, y, r * (0.9f + 0.08f * pu), 0xFFE082, a)
                    bold(c, x - r * 0.9f, y, x + r * 0.9f, y, 7f * d, 0xFFF59D, a)
                    bold(c, x - r * 0.9f, y, x + r * 0.9f, y, 3f * d, 0xFFFFFF, a)
                    star(c, x - r * 0.85f, y, r * 0.3f, r * 0.08f, 4, 0f)
                }
            }
        }
    }

    private fun drawGlyph(c: Canvas, i: Int, x: Float, y: Float, r: Float, ready: Boolean) {
        drawGlyphNew(c, pl.hero, i, x, y, r, ready); return
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
        if (picking) {
            for (i in 0..3) {
                if (x >= pkl[i] && x <= pkr[i] && y >= pkt[i] && y <= pkb[i]) {
                    pickSel = i
                    startMatch(i)
                    break
                }
            }
            return
        }
        if (over != 0) {
            if (overT > 1f) { picking = true; newGame(pickSel, 0) }
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
