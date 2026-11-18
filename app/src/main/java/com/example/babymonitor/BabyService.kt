package com.example.babymonitor

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.*
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.*
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Aparelho do bebê: Group Owner Wi-Fi Direct + servidor TCP + captura de áudio sob comando. */
class BabyService : Service() {
    private val io = Executors.newCachedThreadPool()
    private val hb = Executors.newSingleThreadScheduledExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var mgr: WifiP2pManager
    private lateinit var ch: WifiP2pManager.Channel
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var server: ServerSocket? = null
    private var captureThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var streaming = false
    @Volatile private var out: DataOutputStream? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        startForeground(1, fgNotification("Babá eletrônica", "Aguardando os pais"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (!running) { running = true; boot() }
        // NOT_STICKY: no Android 14+ não é permitido reiniciar FGS de microfone em background.
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun boot() {
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bm:baby").apply { acquire() }
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bm:wifi").apply { acquire() }

        mgr = getSystemService(WifiP2pManager::class.java)
        ch = mgr.initialize(this, mainLooper, null)
        val cfg = WifiP2pConfig.Builder()
            .setNetworkName(Proto.GROUP_SSID).setPassphrase(Proto.GROUP_PASS)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)   // melhor penetração/alcance
            .enablePersistentMode(false).build()
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = createGroup(cfg, 0)
            override fun onFailure(r: Int) = createGroup(cfg, 0)
        })
        io.execute { acceptLoop() }
    }

    @SuppressLint("MissingPermission")
    private fun createGroup(cfg: WifiP2pConfig, tries: Int) {
        if (!running) return
        mgr.createGroup(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { main.postDelayed({ showGroupInfo() }, 1500) }
            override fun onFailure(r: Int) {
                if (tries < 10) main.postDelayed({ createGroup(cfg, tries + 1) }, 2000)
                else note("Falha ao criar a rede (código $r)")
            }
        })
    }

    private fun note(t: String) =
        getSystemService(NotificationManager::class.java).notify(1, fgNotification("Babá eletrônica", t))

    /** Mostra na notificação o nome e a senha REAIS da rede (o Android pode ignorar os que pedimos). */
    @SuppressLint("MissingPermission")
    private fun showGroupInfo() {
        mgr.requestGroupInfo(ch) { g ->
            note(if (g == null) "Rede ainda não criada" else "Rede: ${g.networkName}  Senha: ${g.passphrase}")
        }
    }

    private fun acceptLoop() {
        try {
            server = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Proto.PORT)) }
            while (running) handle(server!!.accept())
        } catch (e: IOException) { /* encerrando */ }
    }

    private fun handle(s: Socket) {
        s.tcpNoDelay = true
        s.soTimeout = 5000                     // pais sem heartbeat por 5 s => derruba
        val ins = DataInputStream(BufferedInputStream(s.getInputStream()))
        val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
        out = o
        var n = 0
        val beat = hb.scheduleAtFixedRate({
            try {
                Proto.write(o, Proto.T_BEAT)
                if (n++ % 10 == 0) Proto.write(o, Proto.T_BATT, batteryPayload())   // a cada 10 s
            } catch (e: IOException) { runCatching { s.close() } }
        }, 0, 1, TimeUnit.SECONDS)
        try {
            while (running) {
                when (Proto.read(ins).type) {
                    Proto.T_START -> startCapture(s)
                    Proto.T_STOP -> stopCapture()
                }
            }
        } catch (e: IOException) { /* desconexão/timeout */ }
        finally {
            beat.cancel(true); stopCapture(); out = null; runCatching { s.close() }
        }
    }

    private fun batteryPayload(): ByteArray {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))   // broadcast sticky: não exige permissão
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 255
        return byteArrayOf(pct.toByte(), if (charging) 1 else 0)
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(s: Socket) {
        if (streaming) return
        streaming = true
        captureThread = Thread {
            val min = AudioRecord.getMinBufferSize(Proto.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)   // troque por MIC se ficar baixo
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Proto.RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(min, Proto.FRAME_BYTES * Proto.CHUNK_FRAMES * 4)).build()
            val buf = ByteArray(Proto.FRAME_BYTES * Proto.CHUNK_FRAMES)
            try {
                rec.startRecording()
                while (streaming) {
                    var off = 0
                    while (off < buf.size && streaming) {
                        val n = rec.read(buf, off, buf.size - off)
                        if (n < 0) throw IOException("AudioRecord erro $n")
                        off += n
                    }
                    if (streaming) out?.let { Proto.write(it, Proto.T_AUDIO, buf) }
                }
            } catch (e: Exception) { runCatching { s.close() } }
            finally { runCatching { rec.stop() }; rec.release(); streaming = false }
        }.apply { start() }
    }

    private fun stopCapture() {
        streaming = false
        captureThread?.join(500); captureThread = null
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        running = false; streaming = false
        runCatching { server?.close() }
        runCatching { mgr.removeGroup(ch, null) }
        wake?.release(); wifiLock?.release()
        io.shutdownNow(); hb.shutdownNow()
        super.onDestroy()
    }
}
