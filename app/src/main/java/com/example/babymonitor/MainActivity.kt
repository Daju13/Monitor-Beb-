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
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var box: LinearLayout
    private lateinit var nameEt: EditText
    private lateinit var passEt: EditText
    private lateinit var voxBtn: Button
    private var role = ""

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) launchRole() else status.text = "Permissões necessárias negadas."
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        status = TextView(this).apply { textSize = 18f; text = "Escolha a função deste aparelho" }
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
        setContentView(ScrollView(this).apply { addView(box) })
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

    private fun cmd(a: String) { startService(Intent(this, ParentService::class.java).setAction(a)) }

    override fun onDestroy() { ParentService.onStatus = null; super.onDestroy() }
}
