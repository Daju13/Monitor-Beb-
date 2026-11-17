package com.example.babymonitor

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.*
import java.io.*
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Aparelho dos pais: entra na rede do bebê (Wi-Fi comum, via WifiNetworkSpecifier), recebe áudio, toca, e alarma se o link cair. */
class ParentService : Service() {
    companion object {
        const val A_LISTEN = "listen"; const val A_STOP = "stop"; const val A_SILENCE = "silence"; const val A_QUIT = "quit"
        @Volatile var onStatus: ((String) -> Unit)? = null
        @Volatile var lastStatus = "Iniciando…"
    }

    private val io = Executors.newCachedThreadPool()
    private val hb = Executors.newSingleThreadScheduledExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private var cb: ConnectivityManager.NetworkCallback? = null
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
    @Volatile private var lastErr = ""
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var net: Network? = null
    @Volatile private var goAddr: InetAddress? = null

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
    @SuppressLint("WakelockTimeout")
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
                if (cb == null) requestWifi()
                sleepQuiet(3000)
                if (goAddr == null) down()
                continue
            }
            try { session(addr) } catch (e: Exception) { failCount++ }
            down()
            if (failCount >= 3) { resetNet(); failCount = 0 }   // refaz a conexão do zero
            sleepQuiet(1000)
        }
    }

    /** Conecta na rede DIRECT-... do bebê como um Wi-Fi comum. O Android pede confirmação ao usuário. */
    private fun requestWifi() {
        resetNet()
        val spec = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher("DIRECT-", PatternMatcher.PATTERN_PREFIX))
            .setWpa2Passphrase(Proto.GROUP_PASS).build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec).build()
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                net = n; goAddr = gatewayOf(cm.getLinkProperties(n))
            }
            override fun onLinkPropertiesChanged(n: Network, lp: LinkProperties) {
                if (n == net) goAddr = gatewayOf(lp)
            }
            override fun onLost(n: Network) {
                if (n == net) { net = null; goAddr = null }
            }
            override fun onUnavailable() {
                lastErr = "Rede do bebê indisponível (recusada ou fora de alcance)"
                status(lastErr); resetNet()
            }
        }
        cb = c
        status("Conectando à rede do bebê… (aceite o pedido na tela, se aparecer)")
        cm.requestNetwork(req, c, 60_000)
    }

    private fun gatewayOf(lp: LinkProperties?): InetAddress =
        lp?.routes?.mapNotNull { it.gateway }?.firstOrNull { it is Inet4Address && !it.isAnyLocalAddress }
            ?: InetAddress.getByName("192.168.49.1")      // IP padrão do Group Owner no Wi-Fi Direct

    private fun resetNet() {
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cb = null; net = null; goAddr = null
    }

    private fun session(addr: InetAddress) {
        val n = net ?: throw IOException("sem rede")
        n.socketFactory.createSocket().use { s ->      // socket preso à rede do bebê
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
                        up = true; everUp = true; lastErr = ""; silenced = false; failCount = 0
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
        val base = if (net == null) "Procurando a rede do bebê… (aceite o pedido na tela, se aparecer)"
                   else "SEM CONEXÃO com o bebê"
        status(base + if (lastErr.isNotEmpty()) "\n$lastErr" else "")
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
        resetNet()
        runCatching { track?.release() }
        wake?.release(); wifiLock?.release()
        io.shutdownNow(); hb.shutdownNow()
        super.onDestroy()
    }
}
