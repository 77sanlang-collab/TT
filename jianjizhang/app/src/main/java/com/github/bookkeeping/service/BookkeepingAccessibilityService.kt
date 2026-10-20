package com.github.bookkeeping.service

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.github.bookkeeping.*
import com.github.bookkeeping.automation.*
import com.github.bookkeeping.deferred.DeferredNotifications
import java.util.concurrent.Executors

class BookkeepingAccessibilityService : AccessibilityService() {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "bookkeeping-accessibility").apply { isDaemon = true } }
    private var allowed = AccessibilityPackages.paymentPackages
    private val checkout = mutableMapOf<String, Pair<Long, String>>()
    private val rulesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refreshPackages() }
    override fun onServiceConnected() {
        super.onServiceConnected()
        LocalRules(this).prefs.registerOnSharedPreferenceChangeListener(rulesListener)
        refreshPackages()
    }
    private fun refreshPackages() {
        allowed = AccessibilityPackages.paymentPackages + LocalRules(this).all().filter { it.enabled }.map { it.packageName }
        serviceInfo?.let { info -> info.packageNames = allowed.toTypedArray(); serviceInfo = info }
        checkout.clear()
    }
    @Suppress("DEPRECATION")
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || SettingsStore(this).automaticPaused) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == AccessibilityPackages.SELF || pkg !in allowed) return
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() != pkg) { root?.recycle(); return }
        val text = try { buildString { collectText(root, this, 0) }.take(24000) } finally { root.recycle() }
        if (text.length < 4) return
        val clicked = if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val node = event.source
            listOfNotNull(node?.text?.toString(), node?.contentDescription?.toString(), event.text.joinToString(" "))
                .filter { it.isNotBlank() }.distinct().joinToString(" ").take(1000)
        } else null
        val now = System.currentTimeMillis()
        // Preserve line boundaries: flattening lines can put an unrelated discount next to a total label.
        val preview = if (pkg in Platforms.apps) AmountReader.read(text) else null
        if (preview?.cents != null && listOf("提交订单", "立即支付", "免密支付", "确认购买", "确认收货后付款").any(text::contains)) {
            val safe = "合计 ¥${java.math.BigDecimal(preview.cents).movePointLeft(2).toPlainString()}\n" +
                if (pkg == "com.xunmeng.pinduoduo" && text.contains("确认收货后付款")) "使用先用后付" else Platforms.name(Platforms.apps.getValue(pkg))
            checkout[pkg] = now to safe
        }
        val cached = checkout[pkg]
        val isCheckout = listOf("提交订单", "立即支付", "免密支付", "确认购买", "确认收货后付款").any(text::contains)
        val content = if (clicked != null && !isCheckout && cached != null && now - cached.first in 0..10_000L) cached.second else text
        executor.execute {
            if (CaptureEngine(applicationContext).process(pkg, content, clicked, now, true)) DeferredNotifications.post(applicationContext)
        }
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        LocalRules(this).prefs.unregisterOnSharedPreferenceChangeListener(rulesListener)
        executor.shutdown(); super.onDestroy()
    }
    @Suppress("DEPRECATION")
    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > 12 || out.length > 24000) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.append(it).append('\n') }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() && it != node.text?.toString() }?.let { out.append(it).append('\n') }
        for (i in 0 until node.childCount.coerceAtMost(100)) node.getChild(i)?.let { child ->
            try { collectText(child, out, depth + 1) } finally { child.recycle() }
        }
    }
}
