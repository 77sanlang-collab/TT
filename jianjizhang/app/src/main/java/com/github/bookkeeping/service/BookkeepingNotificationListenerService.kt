package com.github.bookkeeping.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.github.bookkeeping.BookkeepingApplication
import com.github.bookkeeping.BookkeepingRepository
import com.github.bookkeeping.SettingsStore
import com.github.bookkeeping.recognition.PaymentTextParser
import java.util.concurrent.Executors

class BookkeepingNotificationListenerService : NotificationListenerService() {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bookkeeping-notification").apply { isDaemon = true }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val store = SettingsStore(applicationContext)
        if (store.automaticPaused) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = listOfNotNull(
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        ).joinToString(" ").ifBlank { null }

        // 解析（含正则/SHA-256）与落库都在后台线程执行，避免阻塞通知管道主线程。
        val packageName = sbn.packageName
        val postTime = sbn.postTime
        executor.execute {
            if (com.github.bookkeeping.automation.CaptureEngine(applicationContext).process(
                    packageName, listOfNotNull(title, text).joinToString("\n"), null, postTime, false)) {
                com.github.bookkeeping.deferred.DeferredNotifications.post(applicationContext)
            }
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }
}
