package com.example.babymonitor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

import android.content.res.ColorStateList
import android.graphics.Color

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var box: LinearLayout
    private var role = ""

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) launchRole() else status.text = "Permissões necessárias negadas."
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 96, 48, 48) }
        status = TextView(this).apply { textSize = 18f; text = "Escolha a função deste aparelho" }
        box.addView(status)
        btn("Este aparelho é o BEBÊ") { pick("baby") }
        btn("Este aparelho é dos PAIS") { pick("parent") }
        setContentView(box)
    }

    private fun btn(t: String, color: Int? = null, click: () -> Unit) =
    box.addView(Button(this).apply {
        text = t
        color?.let {
            backgroundTintList = ColorStateList.valueOf(it)
            setTextColor(Color.WHITE)
        }
        setOnClickListener { click() }
    })
    
    private fun pick(r: String) {
        role = r
        val p = mutableListOf<String>()
        if (r == "baby") p += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) { p += Manifest.permission.POST_NOTIFICATIONS; p += Manifest.permission.NEARBY_WIFI_DEVICES }
        else p += Manifest.permission.ACCESS_FINE_LOCATION
        askPerms.launch(p.toTypedArray())
    }

    private fun launchRole() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName))
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        box.removeAllViews(); box.addView(status)
        if (role == "baby") {
            startForegroundService(Intent(this, BabyService::class.java))
            status.text = "Modo BEBÊ ativo. Pode deixar o app em segundo plano."
        } else {
            startForegroundService(Intent(this, ParentService::class.java))
            ParentService.onStatus = { runOnUiThread { status.text = it } }
            status.text = ParentService.lastStatus
            btn("Ouvir") { cmd(ParentService.A_LISTEN) }
            btn("Parar de ouvir") { cmd(ParentService.A_STOP) }
            btn("Silenciar alarme", Color.parseColor("#FF0000") { cmd(ParentService.A_SILENCE) }
        }
        btn("Encerrar") {
            if (role == "baby") stopService(Intent(this, BabyService::class.java)) else cmd(ParentService.A_QUIT)
            finish()
        }
    }

    private fun cmd(a: String) { startService(Intent(this, ParentService::class.java).setAction(a)) }

    override fun onDestroy() { ParentService.onStatus = null; super.onDestroy() }
}
