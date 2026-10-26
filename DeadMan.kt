package com.example.babymonitor

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager

/**
 * Dead-man's switch dos pais. Enquanto o ParentService está vivo e o laço de rede responde,
 * ele rearma um alarme do AlarmManager (setAlarmClock) a cada 5 s, sempre para daqui a 30 s.
 * Se o serviço/processo morrer, ninguém rearma e o alarme dispara, tocando mesmo com o app morto.
 */
object DeadMan {
    const val TIMEOUT_MS = 30_000L
    const val ACTION_FIRE = "com.example.babymonitor.DEADMAN_FIRE"
    const val ACTION_SILENCE = "com.example.babymonitor.DEADMAN_SILENCE"
    private const val NOTIF_ID = 99
    private const val CH = "bm_deadman"

    private fun firePi(c: Context) = PendingIntent.getBroadcast(
        c, 1, Intent(c, DeadManReceiver::class.java).setAction(ACTION_FIRE),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun arm(c: Context) {
        val show = PendingIntent.getActivity(c, 2, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        // setAlarmClock: dispara em Doze e não exige SCHEDULE_EXACT_ALARM.
        c.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(System.currentTimeMillis() + TIMEOUT_MS, show), firePi(c))
    }

    fun cancel(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(firePi(c))
        clear(c)
    }

    fun clear(c: Context) = c.getSystemService(NotificationManager::class.java).cancel(NOTIF_ID)

    fun ring(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        nm.createNotificationChannel(NotificationChannel(CH, "Alarme de falha", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), attrs)
            enableVibration(true); vibrationPattern = longArrayOf(0, 700, 400, 700, 400)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        runCatching {
            val am = c.getSystemService(AudioManager::class.java)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        }
        val open = PendingIntent.getActivity(c, 4, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val silence = PendingIntent.getBroadcast(c, 3,
            Intent(c, DeadManReceiver::class.java).setAction(ACTION_SILENCE), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("BABÁ ELETRÔNICA PAROU")
            .setContentText("O monitoramento foi interrompido. Verifique o bebê.")
            .setCategory(Notification.CATEGORY_ALARM).setOngoing(true)
            .setContentIntent(open).setFullScreenIntent(open, true)
            .addAction(Notification.Action.Builder(
                Icon.createWithResource(c, android.R.drawable.ic_lock_silent_mode), "Silenciar", silence).build())
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT or Notification.FLAG_NO_CLEAR   // repete o som até silenciar
        nm.notify(NOTIF_ID, n)
    }
}

class DeadManReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            DeadMan.ACTION_FIRE -> DeadMan.ring(c)
            DeadMan.ACTION_SILENCE -> DeadMan.clear(c)
        }
    }
}
