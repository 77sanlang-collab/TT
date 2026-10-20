package com.github.bookkeeping.automation

import com.github.bookkeeping.*
import com.github.bookkeeping.deferred.DeferredParser
import java.security.MessageDigest

object ShoppingParser {
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    fun parse(pkg: String?, raw: String, clicked: String?, now: Long, rules: List<LocalRule> = emptyList()): DeferredParser.Order? {
        if (pkg.isNullOrBlank() || pkg == AccessibilityPackages.SELF) return null
        val rule = rules.firstOrNull { it.enabled && it.packageName == pkg && (if (it.click) clicked?.contains(it.trigger) == true else raw.contains(it.trigger)) }
        val platform = Platforms.apps[pkg] ?: if (rule != null) Platforms.CUSTOM else return null
        if (listOf("支付失败", "下单失败", "订单已取消", "交易关闭", "退款成功").any(raw::contains)) return null
        val actions = when (platform) {
            SourceKey.PINDUODUO -> listOf("提交订单", "0元下单", "确认收货后付款")
            Platforms.XHS, Platforms.DOUYIN -> listOf("立即支付", "提交订单")
            Platforms.XIANYU -> listOf("确认购买", "免密支付", "立即付款", "确认付款")
            else -> emptyList()
        }
        val clickedSubmit = clicked != null && clicked.length <= 250 && actions.any(clicked::contains)
        val success = listOf("下单成功", "支付成功", "订单提交成功", "付款成功", "交易成功").any(raw::contains)
        if (rule == null && !clickedSubmit && (clicked != null || !success)) return null
        val pageMoney = AmountReader.read(raw, rule?.amountLabels ?: AmountReader.labels)
        val buttonMoney = if (clickedSubmit || rule?.click == true) AmountReader.read(clicked.orEmpty(),
            if (rule != null) listOf(rule.trigger) + rule.amountLabels else actions) else null
        val money = if (buttonMoney?.cents != null && buttonMoney.reason.startsWith("金额依据：")) buttonMoney else pageMoney
        // A triggered page without a trustworthy amount remains an editable review draft.
        val product = Regex("(?:商品名称|已选|商户)\\s*[:：]\\s*([^\\n]{1,80})").find(raw)?.groupValues?.get(1)?.trim()
            ?: (rule?.name ?: Platforms.name(platform)) + "订单（请补充商品）"
        val number = DeferredParser.orderNumber(raw)
        val mode = if (platform == SourceKey.PINDUODUO && (raw.contains("确认收货后付款") || Regex("使用\\s*先用后付").containsMatchIn(raw))) "DEFERRED" else "IMMEDIATE"
        val identity = if (number.isNotBlank()) "$platform:order:$number" else "$pkg:$product:${money.cents}:${now / (if (clickedSubmit || rule?.click == true) 2000L else 300_000L)}"
        return DeferredParser.Order(number, product, money.cents ?: 0, hash(identity), mode, clickedSubmit || rule?.click == true,
            platform, rule?.name ?: Platforms.name(platform), money.choices, money.reason)
    }
}
