package com.example.babymonitor

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var box: LinearLayout
    private lateinit var nameEt: EditText
    private lateinit var passEt: EditText
    private lateinit var voxBtn: Button
    private var role = ""
    private var pendingApk: File? = null

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) launchRole() else status.text = "Permissões necessárias negadas."
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        status = TextView(this).apply { textSize = 18f; text = "Escolha a função deste aparelho\nVersão instalada: v${Updater.installedCode(this@MainActivity)}" }
        box.addView(status)
        box.addView(label("Nome da rede (letras e números; vale no aparelho do bebê)"))
        nameEt = EditText(this).apply {
            setText(Config.suffix(this@MainActivity)); inputType = InputType.TYPE_CLASS_TEXT
        }
        box.addView(nameEt)
        box.addView(label("Senha da rede (8 a 63 caracteres; igual nos dois aparelhos)"))
        passEt = EditText(this).apply {
            setText(Config.pass(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        box.addView(passEt)
        btn("Este aparelho é o BEBÊ") { pick("baby") }
        btn("Este aparelho é dos PAIS") { pick("parent") }
        btn("Buscar atualização") { checkUpdate(true) }
        setContentView(ScrollView(this).apply { addView(box) })
        checkUpdate(false)
    }

    private fun label(t: String) = TextView(this).apply { text = t; textSize = 13f; setPadding(0, 24, 0, 0) }

    private fun btn(t: String, color: Int? = null, click: () -> Unit): Button {
        val b = Button(this).apply {
            text = t
            color?.let {
                backgroundTintList = ColorStateList.valueOf(it)
                setTextColor(Color.WHITE)
            }
            setOnClickListener { click() }
        }
        box.addView(b)
        return b
    }

    private fun pick(r: String) {
        val name = nameEt.text.toString().trim()
        val pass = passEt.text.toString()
        val err = Config.validate(name, pass)
        if (err != null) { status.text = err; return }
        Config.save(this, name, pass)
        role = r
        val p = mutableListOf<String>()
        if (r == "baby") p += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) { p += Manifest.permission.POST_NOTIFICATIONS; p += Manifest.permission.NEARBY_WIFI_DEVICES }
        else p += Manifest.permission.ACCESS_FINE_LOCATION
        askPerms.launch(p.toTypedArray())
    }

    private fun voxLabel(m: Int) =
        arrayOf("VOX: desligado", "VOX: sensibilidade alta", "VOX: sensibilidade média", "VOX: sensibilidade baixa")[m]

    private fun launchRole() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName))
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        box.removeAllViews(); box.addView(status)
        if (role == "baby") {
            startForegroundService(Intent(this, BabyService::class.java))
            status.text = "Modo BEBÊ ativo. Pode deixar o app em segundo plano.\nRede: ${Config.ssid(this)}"
        } else {
            startForegroundService(Intent(this, ParentService::class.java))
            ParentService.onStatus = { runOnUiThread { status.text = it } }
            status.text = ParentService.lastStatus
            btn("Ouvir", Color.parseColor("#2E7D32")) { cmd(ParentService.A_LISTEN) }
            btn("Parar de ouvir", Color.parseColor("#F9A825")) { cmd(ParentService.A_STOP) }
            voxBtn = btn(voxLabel(Config.voxMode(this)), Color.parseColor("#1565C0")) {
                val m = (Config.voxMode(this) + 1) % 4
                Config.setVox(this, m)
                voxBtn.text = voxLabel(m)
                cmd(ParentService.A_VOX)
            }
            btn("Silenciar alarme", Color.parseColor("#C62828")) { cmd(ParentService.A_SILENCE) }
        }
        btn("Encerrar") {
            if (role == "baby") stopService(Intent(this, BabyService::class.java)) else cmd(ParentService.A_QUIT)
            finish()
        }
    }

    // ---------------- Atualização via GitHub Releases ----------------
    private fun checkUpdate(manual: Boolean) {
        Thread {
            val info = try { Updater.check(this) } catch (e: Exception) { null }
            runOnUiThread {
                if (info != null) askUpdate(info)
                else if (manual) status.text = "Nenhuma atualização encontrada (ou sem internet)."
            }
        }.start()
    }

    private fun askUpdate(info: Updater.Info) {
        val notes = info.notes.trim().take(300)
        AlertDialog.Builder(this)
            .setTitle("Atualização disponível (${info.tag})")
            .setMessage((if (notes.isEmpty()) "" else notes + "\n\n") + "Atualizar agora?")
            .setPositiveButton("Sim") { _, _ -> startDownload(info) }
            .setNegativeButton("Não", null)
            .show()
    }

    private fun startDownload(info: Updater.Info) {
        status.text = "Baixando atualização… 0%"
        Thread {
            try {
                val f = Updater.download(this, info) { p -> runOnUiThread { status.text = "Baixando atualização… $p%" } }
                runOnUiThread { status.text = "Download concluído."; installOrAskPermission(f) }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Falha no download: ${e.message}" }
            }
        }.start()
    }

    private fun installOrAskPermission(f: File) {
        if (packageManager.canRequestPackageInstalls()) {
            Updater.install(this, f)
        } else {
            pendingApk = f
            status.text = "Permita instalar apps desta fonte e volte ao app para concluir."
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
    }

    override fun onResume() {
        super.onResume()
        pendingApk?.let {
            if (packageManager.canRequestPackageInstalls()) { pendingApk = null; Updater.install(this, it) }
        }
    }

    private fun cmd(a: String) { startService(Intent(this, ParentService::class.java).setAction(a)) }

    override fun onDestroy() { ParentService.onStatus = null; super.onDestroy() }
}
