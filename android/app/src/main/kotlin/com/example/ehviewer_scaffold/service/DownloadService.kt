package com.example.ehviewer_scaffold.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.ehviewer_scaffold.rust.EhRustBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "eh_downloads_channel"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            context.stopService(intent)
        }
    }

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var isMonitoring = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("正在后台同步下载画廊数据...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startAutonomousMonitor()
        return START_STICKY
    }

    /**
     * 自主生命周期闭环监听：
     * 无论用户是否留在 DownloadsScreen 页面或应用退到后台，
     * Service 自行定期轮询下载任务状态。若检测到无活跃任务持续 6 秒，
     * 主动停止前台服务并释放系统常驻通知，杜绝通知栏常驻通知泄露。
     */
    private fun startAutonomousMonitor() {
        if (isMonitoring) return
        isMonitoring = true
        serviceScope.launch {
            var idlePeriods = 0
            while (isActive) {
                delay(3000L)
                try {
                    val tasks = EhRustBridge.getDownloads()
                    val hasActive = tasks.any { it.status == 1 }
                    if (hasActive) {
                        idlePeriods = 0
                    } else {
                        idlePeriods++
                        if (idlePeriods >= 2) { // 连续 6 秒无活跃下载
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                            } else {
                                @Suppress("DEPRECATION")
                                stopForeground(true)
                            }
                            stopSelf()
                            break
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "画廊下载同步",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "维护离线下载的前台服务与网络存活"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Eh-ru 下载服务")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
