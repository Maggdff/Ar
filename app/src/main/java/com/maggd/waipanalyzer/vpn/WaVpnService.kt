package com.maggd.waipanalyzer.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import com.maggd.waipanalyzer.MainActivity
import com.maggd.waipanalyzer.R
import com.maggd.waipanalyzer.data.CaptureRepository
import com.maggd.waipanalyzer.data.UiMessage
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Local-only VPN used purely as a packet tap: only WhatsApp's own traffic is routed into it, it is forwarded
 * unchanged to the real network by [VpnEngine], and statistics stay on the device.
 */
class WaVpnService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null
    private var engine: VpnEngine? = null
    private var scope: CoroutineScope? = null
    private var publisher: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            return START_NOT_STICKY
        }
        if (engine == null) startCapture()
        return START_NOT_STICKY
    }

    private fun installedWhatsApp(): List<String> = WHATSAPP_PACKAGES.filter {
        try {
            packageManager.getPackageInfo(it, 0); true
        } catch (e: PackageManager.NameNotFoundException) { false }
    }

    private fun startCapture() {
        startInForeground()
        val pkgs = installedWhatsApp()
        if (pkgs.isEmpty()) return fail(R.string.msg_whatsapp_missing)
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .setBlocking(true)
            .addAddress(TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)
        // Only WhatsApp is routed through the tap, so no other app's traffic is ever seen.
        for (p in pkgs) builder.addAllowedApplication(p)
        val fd = try { builder.establish() } catch (e: Exception) { null } ?: return fail(R.string.msg_vpn_denied)
        pfd = fd
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        CaptureRepository.beginLive(cacheDir, TUN_ADDRESS)
        val e = VpnEngine(
            tunRead = { buf -> try { input.read(buf) } catch (ex: Exception) { -1 } },
            tunWrite = { b, o, l -> output.write(b, o, l) },
            protectTcp = { protect(it) },
            protectUdp = { protect(it) },
            onPacket = CaptureRepository::onLivePacket,
            clientIp = byteArrayOf(10, 8, 0, 2)
        )
        engine = e
        e.start()
        running = true
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        publisher = s.launch {
            while (isActive) {
                CaptureRepository.publish()
                delay(1000)
            }
        }
    }

    private fun fail(msg: Int) {
        CaptureRepository.setMessage(UiMessage(msg))
        stopCapture()
    }

    private fun stopCapture() {
        running = false
        publisher?.cancel(); publisher = null
        scope?.cancel(); scope = null
        engine?.close(); engine = null
        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
        CaptureRepository.endLive()
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onRevoke() { stopCapture() }

    override fun onDestroy() {
        if (engine != null) stopCapture()
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, WaVpnService::class.java).setAction(ACTION_STOP), flags)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_stat), getString(R.string.btn_stop), stop).build())
            .build()
        startForeground(NOTIF_ID, n)
    }

    companion object {
        const val ACTION_START = "com.maggd.waipanalyzer.START"
        const val ACTION_STOP = "com.maggd.waipanalyzer.STOP"
        private const val CHANNEL = "capture"
        private const val NOTIF_ID = 1
        private const val TUN_ADDRESS = "10.8.0.2"
        val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")
        @Volatile var running = false
            private set
    }
}
