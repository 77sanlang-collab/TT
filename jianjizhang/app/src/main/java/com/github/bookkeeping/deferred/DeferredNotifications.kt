package com.github.bookkeeping.deferred

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.github.bookkeeping.MainActivity

object DeferredNotifications {
    fun post(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = "deferred_orders"
        manager.createNotificationChannel(NotificationChannel(channel, "拼多多订单待确认", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).putExtra("open_deferred", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, 102, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("拼多多订单待确认")
            .setContentText("打开核对金额和分类，确认后计入下单当天的消费")
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(pending).setAutoCancel(true).build()
        try { manager.notify(102, notification) } catch (_: SecurityException) { /* Order stays in review queue. */ }
    }
}
