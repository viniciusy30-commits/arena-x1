package com.arenax1.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Updater {
    const val REPO = "viniciusy30-commits/arena-x1"
    private const val PREFS = "updater"

    fun autoCheck(act: Activity) {
        val sp = act.getSharedPreferences(PREFS, 0)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("last", 0L) < 6L * 3600L * 1000L) return
        sp.edit().putLong("last", now).apply()
        check(act, true, null)
    }

    fun check(act: Activity, silent: Boolean, onMsg: ((String) -> Unit)?) {
        if (!silent) onMsg?.invoke("Procurando atualização...")
        Thread {
            try {
                val con = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                con.connectTimeout = 8000
                con.readTimeout = 8000
                con.setRequestProperty("Accept", "application/vnd.github+json")
                if (con.responseCode != 200) throw Exception("HTTP " + con.responseCode)
                val js = JSONObject(con.inputStream.bufferedReader().readText())
                val tag = js.getString("tag_name").removePrefix("v").toIntOrNull() ?: 0
                var found: String? = null
                val assets = js.getJSONArray("assets")
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) {
                        found = a.getString("browser_download_url")
                        break
                    }
                }
                val url = found
                act.runOnUiThread {
                    if (act.isFinishing) return@runOnUiThread
                    if (tag > BuildConfig.VERSION_CODE && url != null) {
                        askUpdate(act, tag, url)
                    } else if (!silent) {
                        onMsg?.invoke("Você já está na versão mais nova (v" + BuildConfig.VERSION_CODE + ").")
                    }
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    if (!silent) onMsg?.invoke("Sem internet ou repositório indisponível.")
                }
            }
        }.start()
    }

    private fun askUpdate(act: Activity, tag: Int, url: String) {
        AlertDialog.Builder(act)
            .setTitle("Nova versão disponível")
            .setMessage("A versão v$tag está pronta. Deseja atualizar agora?")
            .setPositiveButton("Atualizar") { _, _ -> download(act, url) }
            .setNegativeButton("Depois", null)
            .show()
    }

    private fun download(act: Activity, url: String) {
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(act)
                .setTitle("Permissão necessária")
                .setMessage("Para instalar a atualização, permita \"Instalar apps desconhecidos\" para o Arena X1 na próxima tela e volte aqui.")
                .setPositiveButton("Abrir configuração") { _, _ ->
                    val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + act.packageName))
                    act.startActivity(i)
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val d = act.resources.displayMetrics.density
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((24 * d).toInt(), (20 * d).toInt(), (24 * d).toInt(), (8 * d).toInt())
        val label = TextView(act)
        label.text = "Baixando... 0%"
        label.gravity = Gravity.CENTER
        val bar = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal)
        bar.max = 100
        box.addView(label)
        box.addView(bar)
        val dlg = AlertDialog.Builder(act).setTitle("Atualizando").setView(box).setCancelable(false).show()
        Thread {
            try {
                val dir = File(act.cacheDir, "updates")
                dir.mkdirs()
                val out = File(dir, "ArenaX1.apk")
                val con = URL(url).openConnection() as HttpURLConnection
                con.connectTimeout = 15000
                con.readTimeout = 15000
                val total = con.contentLength
                con.inputStream.use { inp ->
                    out.outputStream().use { os ->
                        val buf = ByteArray(32 * 1024)
                        var got = 0L
                        var lastP = -1
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            os.write(buf, 0, n)
                            got += n
                            if (total > 0) {
                                val p = (got * 100 / total).toInt()
                                if (p != lastP) {
                                    lastP = p
                                    act.runOnUiThread {
                                        bar.progress = p
                                        label.text = "Baixando... $p%"
                                    }
                                }
                            }
                        }
                    }
                }
                act.runOnUiThread {
                    dlg.dismiss()
                    install(act, out)
                }
            } catch (e: Exception) {
                act.runOnUiThread {
                    dlg.dismiss()
                    AlertDialog.Builder(act).setTitle("Falha no download")
                        .setMessage("Não foi possível baixar a atualização. Tente de novo mais tarde.")
                        .setPositiveButton("OK", null).show()
                }
            }
        }.start()
    }

    private fun install(act: Activity, file: File) {
        val uri = FileProvider.getUriForFile(act, act.packageName + ".fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "application/vnd.android.package-archive")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        act.startActivity(i)
    }
}
