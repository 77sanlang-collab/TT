package com.github.bookkeeping.automation

import android.content.Context
import com.github.bookkeeping.*
import com.github.bookkeeping.deferred.*
import com.github.bookkeeping.recognition.PaymentTextParser

class CaptureEngine(private val context: Context, private val database: com.github.bookkeeping.data.BookkeepingDatabase? = null) {
    fun process(pkg: String, raw: String, clicked: String?, now: Long, accessibility: Boolean): Boolean {
        if (pkg == AccessibilityPackages.SELF) return false
        val settings = SettingsStore(context)
        if (settings.automaticPaused) return false
        val rules = LocalRules(context).all()
        val ruleEnabled = rules.any { it.enabled && it.packageName == pkg }
        val shoppingPlatform = Platforms.apps[pkg]
        val db = database ?: BookkeepingApplication.instance.database
        val repository = DeferredRepository(db)
        if (shoppingPlatform != null || ruleEnabled) {
            if (!settings.isSourceEnabled(shoppingPlatform ?: Platforms.CUSTOM)) return false
            if (Platforms.repayment(raw) || raw.contains("退款成功")) {
                val money = AmountReader.read(raw)
                val income = raw.contains("退款成功")
                val result = DeferredParser.Order("", (if (income) "退款" else "还款") + "待核对", money.cents ?: 0,
                    ShoppingParser.hash("$pkg:$raw:${now / 300_000L}"), "IMMEDIATE", false, shoppingPlatform ?: Platforms.CUSTOM,
                    Platforms.name(shoppingPlatform ?: Platforms.CUSTOM), money.choices,
                    (if (income) "请核对退款金额" else "疑似还款，请选择不计支出") + "；" + money.reason,
                    if (income) CategoryKey.REFUND else CategoryKey.TRANSFER, if (income) Direction.INCOME else Direction.EXPENSE)
                Diagnostics.record(pkg, clicked ?: "通知 / 页面", raw, result.reason)
                return repository.captureParsed(result, now)
            }
            val parsed = ShoppingParser.parse(pkg, raw, clicked, now, rules)
            Diagnostics.record(pkg, clicked ?: "页面 / 通知", raw, parsed?.reason ?: "未满足提交/成功规则，仅浏览不登记")
            if (parsed == null) return false
            return repository.captureParsed(parsed.copy(category = LearnedCategories(context).get(parsed.platform, parsed.product, parsed.direction) ?: parsed.category), now)
        }
        val parsed = if (accessibility) PaymentTextParser.parseAccessibilityText(pkg, raw, now) else PaymentTextParser.parse(pkg, null, raw, now)
        val resolution = AmountReader.read(raw)
        Diagnostics.record(pkg, clicked ?: "页面 / 通知", raw, if (parsed == null) "没有明确的交易完成信息" else resolution.reason)
        if (parsed == null || !settings.isSourceEnabled(parsed.sourceChannel)) return false
        // Clear PDD deductions may reconcile already accepted purchases. Everything else is reviewed first.
        if (parsed.amountCents > 0 && DeferredParser.isPayment(raw)) return BookkeepingRepository.insertCandidateBlocking(db, parsed)
        val repayment = Platforms.repayment(raw)
        val order = DeferredParser.Order(DeferredParser.orderNumber(raw), parsed.merchant.ifBlank { Platforms.name(parsed.sourceChannel) },
            resolution.cents ?: if (resolution.choices.size > 1) 0 else parsed.amountCents,
            "wallet-" + ShoppingParser.hash("${parsed.sourceChannel}:${parsed.direction}:$raw:${now / 5000L}"), "IMMEDIATE", false, parsed.sourceChannel, Platforms.name(parsed.sourceChannel), resolution.choices,
            (if (repayment) "疑似还款，请选择“不计支出”或核对用途；" else "") + resolution.reason,
            parsed.categoryKey, parsed.direction)
        return repository.captureParsed(order.copy(category = LearnedCategories(context).get(order.platform, order.product, order.direction) ?: order.category), now)
    }
}
