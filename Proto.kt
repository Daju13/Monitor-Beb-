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
    const val RATE = 16000
    const val FRAME_BYTES = 640            // 20 ms, PCM16 mono

    // TROQUE ESTES VALORES. SSID deve começar com "DIRECT-xx-"; senha com 8+ caracteres.
    const val GROUP_SSID = "DIRECT-BB-BabaEletronica"
    const val GROUP_PASS = "troque-esta-senha-123"

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
