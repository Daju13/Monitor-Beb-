package com.example.babymonitor

import android.annotation.SuppressLint
import android.app.Service
import android.content.*
import android.content.pm.ServiceInfo
import android.media.*
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.*
import java.io.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Aparelho dos pais: entra no grupo Wi-Fi Direct, recebe áudio, toca, e dispara alarme se o link cair. */
class ParentService : Service() {
    companion object {
        const val A_LISTEN = "listen"; const val A_STOP = "stop"; const val A_SILENCE = "silence"; const val A_QUIT = "quit"
        @Volatile var onStatus: ((String) -> Unit)? = null
        @Volatile var lastStatus = "Iniciando…"
    }

    private val io = Executors.newCachedThreadPool()
    private val hb = Executors.newSingleThreadScheduledExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var mgr: WifiP2pManager
    private lateinit var ch: WifiP2pManager.Channel
    private var track: AudioTrack? = null
    private var ringtone: Ringtone? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var t0 = 0L
    @Volatile private var running = false
    @Volatile private var listening = false
    @Volatile private var up = false
    @Volatile private var everUp = false
    @Volatile private var silenced = false
    @Volatile private var failCount = 0
    @Volatile private var tick = 0L          // última prova de vida do laço de rede
    @Volatile private var quitting = false
    @Volatile private var joinTries = 0
    @Volatile private var lastErr = ""
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var goAddr: InetAddress? = null

    private val rx = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(c: Context, i: Intent) = refreshGroup()
    }

    @SuppressLint("MissingPermission")
    private fun refreshGroup() = mgr.requestConnectionInfo(ch) { info ->
        goAddr = if (info.groupFormed && !info.isGroupOwner) info.groupOwnerAddress else null
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i?.action == A_QUIT) {          // encerramento deliberado: desarma o dead-man
            quitting = true; DeadMan.cancel(this); stopSelf(); return START_NOT_STICKY
        }
        startForeground(2, fgNotification("Babá eletrônica", "Monitorando"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        if (!running) boot()
        when (i?.action) {
            A_LISTEN -> setListening(true)
            A_STOP -> setListening(false)
            A_SILENCE -> { silenced = true; alarm(false) }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission", "WakelockTimeout")
    private fun boot() {
        running = true; t0 = SystemClock.elapsedRealtime(); tick = t0
        DeadMan.clear(this)
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bm:parent").apply { acquire() }
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "bm:wifi").apply { acquire() }

        val minBuf = AudioTrack.getMinBufferSize(Proto.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Proto.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, Proto.FRAME_BYTES * 10))   // ~200 ms
            .setTransferMode(AudioTrack.MODE_STREAM).build()

        mgr = getSystemService(WifiP2pManager::class.java)
        ch = mgr.initialize(this, mainLooper, null)
        registerReceiver(rx, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION))
        refreshGroup()
        io.execute { linkLoop() }
        // Rearma o dead-man enquanto o laço de rede estiver vivo (rearma a cada 5 s, alarme em 30 s).
        hb.scheduleAtFixedRate({
            runCatching { if (SystemClock.elapsedRealtime() - tick < 15_000) DeadMan.arm(this) }
        }, 0, 5, TimeUnit.SECONDS)
    }

    private fun linkLoop() {
        while (running) {
            tick = SystemClock.elapsedRealtime()
            val addr = goAddr
            if (addr == null) {
                status("Procurando o aparelho do bebê… (tentativa ${joinTries + 1})")
                joinGroup()
                sleepQuiet(4000)
                if (goAddr == null) {
                    down()
                    if (++joinTries % 3 == 0) resetP2p()   // a cada 3 falhas, limpa o estado do Wi-Fi Direct
                } else joinTries = 0
                continue
            }
            try { session(addr) } catch (e: Exception) { failCount++ }
            down()
            if (failCount >= 3) {           // grupo provavelmente obsoleto: refaz do zero
                runCatching { mgr.removeGroup(ch, null) }
                goAddr = null; failCount = 0
            }
            sleepQuiet(1000)
        }
    }

    @SuppressLint("MissingPermission")
    private fun joinGroup() {
        // Ingresso direto com SSID+senha conhecidos: sem diálogo de convite no aparelho do bebê.
        val cfg = WifiP2pConfig.Builder()
            .setNetworkName(Proto.GROUP_SSID).setPassphrase(Proto.GROUP_PASS)
            .enablePersistentMode(false).build()
        mgr.connect(ch, cfg, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {}
            override fun onFailure(r: Int) {
                val nome = when (r) {
                    0 -> "erro interno"
                    1 -> "sem suporte a Wi-Fi Direct"
                    2 -> "ocupado"
                    else -> "outro"
                }
                lastErr = "Wi-Fi Direct recusou (código $r: $nome)"
                status(lastErr)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun resetP2p() {
        runCatching { mgr.cancelConnect(ch, null) }
        runCatching { mgr.removeGroup(ch, null) }
        goAddr = null
    }

    private fun session(addr: InetAddress) {
        Socket().use { s ->
            s.connect(InetSocketAddress(addr, Proto.PORT), 3000)
            s.tcpNoDelay = true
            s.soTimeout = 3000                  // sem quadro (áudio ou heartbeat) por 3 s => exceção => alarme
            val ins = DataInputStream(BufferedInputStream(s.getInputStream(), 16384))
            val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            out = o
            if (listening) Proto.write(o, Proto.T_START)
            val beat = hb.scheduleAtFixedRate({
                try { Proto.write(o, Proto.T_BEAT) } catch (e: IOException) { runCatching { s.close() } }
            }, 0, 1, TimeUnit.SECONDS)
            try {
                while (running) {
                    val f = Proto.read(ins)
                    tick = SystemClock.elapsedRealtime()
                    if (!up) {
                        up = true; everUp = true; lastErr = ""; joinTries = 0; silenced = false; failCount = 0
                        alarm(false); status(if (listening) "Ouvindo" else "Conectado (áudio desligado)")
                    }
                    if (f.type == Proto.T_AUDIO && listening) {
                        if (ins.available() > Proto.FRAME_BYTES * 25) continue   // >500 ms acumulados: descarta
                        track?.write(f.payload, 0, f.payload.size)
                    }
                }
            } finally { beat.cancel(true); out = null }
        }
    }

    private fun down() {
        up = false
        status("SEM CONEXÃO com o bebê" + if (lastErr.isNotEmpty()) "\n$lastErr" else "")
        if (running && !silenced && (everUp || SystemClock.elapsedRealtime() - t0 > 15_000)) alarm(true)
    }

    private fun setListening(on: Boolean) {
        listening = on
        if (on) track?.play() else { track?.pause(); track?.flush() }
        io.execute {
            try { out?.let { Proto.write(it, if (on) Proto.T_START else Proto.T_STOP) } } catch (e: IOException) {}
        }
        if (up) status(if (on) "Ouvindo" else "Conectado (áudio desligado)")
    }

    private fun alarm(on: Boolean) {
        ui.post {
            val vib = getSystemService(Vibrator::class.java)
            if (on) {
                if (ringtone?.isPlaying == true) return@post
                runCatching {
                    val am = getSystemService(AudioManager::class.java)
                    am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                }
                ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                    audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    isLooping = true; play()
                }
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 400), 0))
            } else {
                ringtone?.stop(); vib.cancel()
            }
        }
    }

    private fun status(s: String) { lastStatus = s; ui.post { onStatus?.invoke(s) } }
    private fun sleepQuiet(ms: Long) = try { Thread.sleep(ms) } catch (e: InterruptedException) {}

    override fun onDestroy() {
        running = false
        alarm(false)
        if (quitting) DeadMan.cancel(this)   // se foi o sistema que matou, o dead-man continua armado e dispara
        runCatching { unregisterReceiver(rx) }
        runCatching { mgr.removeGroup(ch, null) }
        runCatching { track?.release() }
        wake?.release(); wifiLock?.release()
        io.shutdownNow(); hb.shutdownNow()
        super.onDestroy()
    }
}
