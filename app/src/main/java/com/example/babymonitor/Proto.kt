package com.example.babymonitor

import android.app.*
import android.content.Context
import android.content.Intent
import java.io.DataInputStream
import java.io.DataOutputStream

object Proto {
    const val PORT = 8988
    const val T_AUDIO: Byte = 1
    const val T_BEAT: Byte = 2
    const val T_START: Byte = 3
    const val T_STOP: Byte = 4
    const val T_VOX: Byte = 6           // payload: [modo 0=desligado,1=alta,2=média,3=baixa]
    const val T_BATT: Byte = 5          // payload: [percentual 0-100 (255=desconhecido)][1=carregando]
    const val RATE = 16000
    const val FRAME_BYTES = 640            // 20 ms, PCM16 mono
    const val CHUNK_FRAMES = 5             // 5 quadros = 100 ms por pacote (50 -> 10 pacotes/s)
    const val READ_TIMEOUT_MS = 6000       // pais: sem quadro por 6 s => link considerado caído
    const val ALARM_GRACE_MS = 6000        // pais: link caído por 6 s seguidos => toca o alarme (~12 s no total)


    class Frame(val type: Byte, val payload: ByteArray)

    // Quadro: [tipo:1][tamanho:2][payload]
    fun write(o: DataOutputStream, type: Byte, p: ByteArray = ByteArray(0)) = synchronized(o) {
        o.writeByte(type.toInt()); o.writeShort(p.size); o.write(p); o.flush()
    }

    fun read(i: DataInputStream): Frame {
        val t = i.readByte()
        val b = ByteArray(i.readUnsignedShort())
        i.readFully(b)
        return Frame(t, b)
    }
}

fun Context.fgNotification(title: String, text: String): Notification {
    getSystemService(NotificationManager::class.java)
        .createNotificationChannel(NotificationChannel("bm", "Babá eletrônica", NotificationManager.IMPORTANCE_LOW))
    val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(this, "bm").setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle(title).setContentText(text).setContentIntent(pi).setOngoing(true).build()
}
