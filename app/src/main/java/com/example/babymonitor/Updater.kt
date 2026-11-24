package com.example.babymonitor

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Atualização via GitHub Releases. O repositório precisa ser público para a API responder sem token. */
object Updater {
    class Info(val versionCode: Int, val tag: String, val url: String, val notes: String)

    fun installedCode(c: Context): Long =
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    /** Retorna a release mais nova, ou null (já atualizado, sem internet, sem repo configurado). Rodar fora da thread principal. */
    fun check(c: Context): Info? {
        val repo = BuildConfig.GITHUB_REPO
        if (repo.isBlank()) return null
        val conn = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000; conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return null
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = j.getString("tag_name")                       // "v57" => versionCode 57
            val code = tag.filter { it.isDigit() }.toIntOrNull() ?: return null
            if (code <= installedCode(c)) return null
            val assets = j.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == "babymonitor.apk") url = a.getString("browser_download_url")
            }
            return Info(code, tag, url ?: return null, j.optString("body"))
        } finally { conn.disconnect() }
    }

    fun download(c: Context, info: Info, onProgress: (Int) -> Unit): File {
        val dir = File(c.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "babymonitor-${info.versionCode}.apk")
        val conn = URL(info.url).openConnection() as HttpURLConnection   // segue o redirecionamento https do GitHub
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        try {
            val total = conn.contentLength
            conn.inputStream.use { ins ->
                f.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) {
                            val p = (done * 100 / total).toInt()
                            if (p != last) { last = p; onProgress(p) }
                        }
                    }
                }
            }
        } finally { conn.disconnect() }
        return f
    }

    /** Abre o instalador do sistema. O Android só aceita se a assinatura for a mesma do app instalado. */
    fun install(a: Activity, f: File) {
        val uri = FileProvider.getUriForFile(a, a.packageName + ".fileprovider", f)
        a.startActivity(Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
