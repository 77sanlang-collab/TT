package com.github.bookkeeping.automation

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.*
import com.github.bookkeeping.*
import com.github.bookkeeping.data.DeferredOrder
import com.github.bookkeeping.deferred.DeferredRepository
import kotlinx.coroutines.*

/** Optional overlay; system-protected payment/lock screens may hide it. Notification is always the fallback. */
object ConfirmationOverlay {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: android.view.View? = null
    fun offer(context: Context) {
        val app = context.applicationContext
        if (!app.getSharedPreferences("local_rules", Context.MODE_PRIVATE).getBoolean("overlay", false) || !Settings.canDrawOverlays(app) ||
            app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        scope.launch {
            if (current != null) return@launch
            val db = BookkeepingApplication.instance.database
            val order = withContext(Dispatchers.IO) { db.deferredOrderDao().getAll().firstOrNull { it.status == "REVIEW" } } ?: return@launch
            show(app, order)
        }
    }
    private fun show(context: Context, order: DeferredOrder) {
        if (current != null) return
        val manager = context.getSystemService(WindowManager::class.java)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(28, 24, 28, 20); setBackgroundColor(android.graphics.Color.WHITE)
        }
        val heading = TextView(context).apply {
            text = "核对后记账\n${order.accountName.ifBlank { Platforms.name(order.platform) }}\n${order.product.take(40)}\n" +
                if (order.amountCents > 0) "¥${java.math.BigDecimal(order.amountCents).movePointLeft(2)}" else "金额不明确，请修改"
            textSize = 17f; setTextColor(android.graphics.Color.BLACK)
        }
        box.addView(heading)
        box.addView(TextView(context).apply { text = "点击提交不代表成功；请确认交易或下单已完成。"; setTextColor(android.graphics.Color.DKGRAY) })
        val categories = CategoryCatalog.forDirection(order.direction)
        val spinner = Spinner(context)
        spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, categories.map { context.getString(it.labelRes) })
        spinner.setSelection(categories.indexOfFirst { it.key == order.categoryKey }.coerceAtLeast(0)); box.addView(spinner)
        fun dismiss() { current?.let { runCatching { manager.removeView(it) } }; current = null }
        var working = false
        fun action(button: Button, block: suspend () -> Unit) {
            if (working) return
            working = true; button.isEnabled = false
            scope.launch {
                try { block(); dismiss() }
                catch (e: Exception) { Toast.makeText(context, e.message ?: "操作失败，请打开应用核对", Toast.LENGTH_LONG).show(); working = false; button.isEnabled = true }
            }
        }
        val row = LinearLayout(context)
        row.addView(Button(context).apply {
            text = "确认"; isEnabled = order.amountCents > 0
            setOnClickListener { action(this) {
                DeferredRepository(BookkeepingApplication.instance.database).confirm(order.id, order.product, order.amountCents,
                    categories[spinner.selectedItemPosition].key, order.orderNumber, order.placedAt, order.paymentMode, order.platform, order.accountName, order.direction, true)
                LearnedCategories(context).save(order.platform, order.product, order.direction, categories[spinner.selectedItemPosition].key)
            } }
        })
        row.addView(Button(context).apply { text = "修改"; setOnClickListener {
            if (!working) { dismiss(); context.startActivity(Intent(context, MainActivity::class.java).putExtra("open_deferred", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        } })
        row.addView(Button(context).apply { text = "忽略"; setOnClickListener { action(this) { DeferredRepository(BookkeepingApplication.instance.database).ignoreReview(order.id) } } })
        box.addView(row)
        box.addView(Button(context).apply { text = "稍后核对"; setOnClickListener { if (!working) dismiss() } })
        val width = (context.resources.displayMetrics.widthPixels - 40).coerceAtMost((360 * context.resources.displayMetrics.density).toInt())
        val params = WindowManager.LayoutParams(width, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.CENTER }
        try { manager.addView(box, params); current = box } catch (_: Exception) { current = null }
    }
}
