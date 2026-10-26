package com.arenax1.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    private var game: GameView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        hideBars()
        showMenu()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (game != null) {
                    game = null
                    showMenu()
                } else {
                    finish()
                }
            }
        })
        Updater.autoCheck(this)
        mostrarNovidades()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    private fun hideBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val ctl = WindowInsetsControllerCompat(window, window.decorView)
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- MENU

    private fun showMenu() {
        val root = FrameLayout(this)
        root.addView(MenuBg(this), ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val title = TextView(this)
        title.text = "ARENA X1"
        title.textSize = 46f
        title.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        title.setTextColor(Color.parseColor("#FFD54F"))
        title.setShadowLayer(8f, 4f, 4f, Color.BLACK)
        title.gravity = Gravity.CENTER
        col.addView(title)

        val sub = TextView(this)
        sub.text = "Duelo 1v1  •  Teste de campeões"
        sub.textSize = 14f
        sub.typeface = Typeface.MONOSPACE
        sub.setTextColor(Color.parseColor("#B0BEC5"))
        sub.gravity = Gravity.CENTER
        sub.setPadding(0, 0, 0, dp(14))
        col.addView(sub)

        col.addView(botao("JOGAR  (LUX)", "#2E7D32") { iniciarJogo() })
        col.addView(botao("COMO JOGAR", "#455A64") { comoJogar() })
        col.addView(botao("CONFIGURAÇÕES", "#455A64") { configuracoes() })

        title.animate().scaleX(1.06f).scaleY(1.06f).setDuration(1200).withEndAction(object : Runnable {
            override fun run() {
                val big = title.scaleX < 1.03f
                title.animate().scaleX(if (big) 1.06f else 1f).scaleY(if (big) 1.06f else 1f)
                    .setDuration(1200).withEndAction(this).start()
            }
        }).start()

        setContentView(root)
    }

    private fun botao(txt: String, cor: String, acao: () -> Unit): TextView {
        val b = TextView(this)
        b.text = txt
        b.textSize = 17f
        b.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        b.setTextColor(Color.WHITE)
        b.gravity = Gravity.CENTER
        val gd = GradientDrawable()
        gd.setColor(Color.parseColor(cor))
        gd.setStroke(dp(3), Color.parseColor("#FFD54F"))
        gd.cornerRadius = dp(3).toFloat()
        b.background = gd
        b.setPadding(dp(20), dp(9), dp(20), dp(9))
        val lp = LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, dp(5), 0, dp(5))
        b.layoutParams = lp
        b.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f).setDuration(80).start()
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                    acao()
                }
                MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
            }
            true
        }
        return b
    }

    private fun iniciarJogo() {
        val g = GameView(this)
        game = g
        setContentView(g)
    }

    private fun comoJogar() {
        AlertDialog.Builder(this)
            .setTitle("Como jogar")
            .setMessage(
                "Destrua o Nexus inimigo. As torres precisam cair primeiro.\n\n" +
                    "• Joystick (lado esquerdo): mover\n" +
                    "• Botão grande: ataque básico (segure para atacar sem parar)\n" +
                    "• Q: Ligação de Luz, prende o alvo\n" +
                    "• W: Barreira Prismática, escudo e velocidade\n" +
                    "• E: Singularidade, zona que atrasa e explode (toque de novo para explodir antes)\n" +
                    "• R: Centelha Final, laser gigante\n\n" +
                    "Toque numa habilidade para mirar automático, ou segure e arraste para mirar na mão.\n" +
                    "Passiva: habilidades marcam o alvo e o próximo ataque causa dano extra."
            )
            .setPositiveButton("Entendi", null)
            .show()
    }

    private fun configuracoes() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(24), dp(16), dp(24), dp(8))
        val ver = TextView(this)
        ver.text = "Versão instalada: v" + BuildConfig.VERSION_CODE
        val status = TextView(this)
        status.text = ""
        status.setPadding(0, dp(8), 0, dp(8))
        val btn = botao("Verificar atualização", "#1565C0") {}
        box.addView(ver)
        box.addView(status)
        box.addView(btn)
        btn.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                Updater.check(this, false) { msg -> status.text = msg }
            }
            true
        }
        AlertDialog.Builder(this)
            .setTitle("Configurações")
            .setView(box)
            .setPositiveButton("Fechar", null)
            .show()
    }

    // ----------------------------------------------------------- NOVIDADES

    private fun mostrarNovidades() {
        try {
            val txt = assets.open("novidades.txt").bufferedReader().readText()
            var id = ""
            var titulo = ""
            val itens = ArrayList<String>()
            var started = false
            for (raw in txt.lines()) {
                val l = raw.trim()
                if (l.startsWith("#id ")) {
                    if (started) break
                    started = true
                    id = l.substring(4).trim()
                } else if (l.startsWith("#titulo ")) {
                    titulo = l.substring(8).trim()
                } else if (l.startsWith("- ") && started) {
                    itens.add("• " + l.substring(2))
                }
            }
            if (id.isEmpty()) return
            val sp = getSharedPreferences("novidades", Context.MODE_PRIVATE)
            if (sp.getString("visto", "") == id) return
            sp.edit().putString("visto", id).apply()
            AlertDialog.Builder(this)
                .setTitle("O que mudou: $titulo")
                .setMessage(itens.joinToString("\n"))
                .setPositiveButton("Legal!", null)
                .show()
        } catch (e: Exception) {
            // sem novidades
        }
    }
}

/** Fundo animado do menu: céu noturno, estrelas, colinas em blocos e uma orbe de luz. */
class MenuBg(ctx: Context) : View(ctx) {
    private val pt = Paint()
    private var t = 0f
    private var last = 0L

    override fun onDraw(c: Canvas) {
        val now = System.nanoTime()
        val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
        last = now
        t += dt
        val w = width.toFloat()
        val h = height.toFloat()
        pt.style = Paint.Style.FILL
        pt.color = Color.parseColor("#0D1230")
        c.drawRect(0f, 0f, w, h, pt)
        pt.color = Color.parseColor("#1B2559")
        c.drawRect(0f, h * 0.35f, w, h, pt)
        // estrelas
        for (i in 0 until 60) {
            val sx = ((i * 7919) % 1000) / 1000f * w
            val sy = ((i * 104729) % 1000) / 1000f * h * 0.6f
            val a = 120 + (110 * sin(t * 2f + i)).toInt()
            pt.color = Color.argb(a.coerceIn(30, 255), 255, 255, 220)
            c.drawRect(sx, sy, sx + 4f, sy + 4f, pt)
        }
        // orbe de luz
        val ox = w * 0.78f
        val oy = h * 0.28f + sin(t) * 10f
        for (k in 5 downTo 1) {
            pt.color = Color.argb(28, 255, 220, 100)
            c.drawCircle(ox, oy, 30f * k, pt)
        }
        pt.color = Color.parseColor("#FFF59D")
        c.drawRect(ox - 18f, oy - 18f, ox + 18f, oy + 18f, pt)
        // colinas em blocos
        val bs = 36f
        var x = 0f
        var i = 0
        while (x < w) {
            val hh = 60f + ((i * 37) % 5) * bs * 0.8f + sin(i * 0.5f) * bs
            pt.color = Color.parseColor("#14331F")
            c.drawRect(x, h - hh, x + bs, h, pt)
            pt.color = Color.parseColor("#2E7D32")
            c.drawRect(x, h - hh, x + bs, h - hh + 8f, pt)
            x += bs
            i++
        }
        postInvalidateOnAnimation()
    }
}
