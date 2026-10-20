package com.github.bookkeeping.deferred

import java.math.BigDecimal
import java.security.MessageDigest

/** Conservative, local-only recognition. No bare numbers, reminders or amount-only auto matching. */
object DeferredParser {
    const val PACKAGE = "com.xunmeng.pinduoduo"
    private val orderPattern = Regex("(?:订单编号|订单号)\\s*[:：]?\\s*([A-Za-z0-9][A-Za-z0-9-]{7,39})(?![A-Za-z0-9-])")
    private val amountPattern = Regex("(?:实付金额|实付款|订单金额|应付金额|合计|待扣款金额|先用后付金额)\\s*[:：]?\\s*[¥￥]?\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)(?![0-9.])(?:\\s*元)?")
    data class Order(val number: String, val product: String, val cents: Long, val fingerprint: String, val mode: String = "DEFERRED", val submitted: Boolean = false)
    fun orderNumber(raw: String): String = orderPattern.find(raw)?.groupValues?.get(1).orEmpty()
    fun order(packageName: String?, raw: String, now: Long): Order? {
        if (packageName != PACKAGE || !raw.contains("先用后付")) return null
        if (listOf("待支付", "待付款", "支付失败", "下单失败", "已取消", "已关闭", "退款", "扣款成功", "扣款失败").any(raw::contains)) return null
        if (listOf("下单成功", "订单提交成功", "支付成功", "订单支付成功").none(raw::contains)) return null
        // Conflicting totals are ambiguous; ask the user to enter an order instead of guessing.
        val amounts = amountPattern.findAll(raw).mapNotNull { cents(it.groupValues[1]) }.distinct().toList()
        if (amounts.size != 1) return null
        val number = orderNumber(raw)
        val product = product(raw)
        val identity = if (number.isNotBlank()) "order:$number" else
            "candidate:${raw.replace(Regex("\\s+"), " ").trim()}:${now / 300_000L}"
        return Order(number, product, amounts.single(), hash(identity))
    }
    /** A real click creates a REVIEW draft, never an accepted purchase or proof of payment. */
    fun clickedOrder(packageName: String?, clicked: String, raw: String, now: Long): Order? {
        if (packageName != PACKAGE || clicked.length > 200) return null
        if (listOf("提交订单", "0元下单", "确认收货后付款").none(clicked::contains)) return null
        val mode = when {
            raw.contains("确认收货后付款") || Regex("使用\\s*先用后付").containsMatchIn(raw) -> "DEFERRED"
            raw.contains("多多支付") -> "IMMEDIATE"
            else -> return null
        }
        val pattern = Regex("(?:提交订单|确认收货后付款)\\s*[:：]?\\s*[¥￥]\\s*([0-9]{1,7}(?:\\.[0-9]{1,2})?)(?![0-9.])")
        val amounts = pattern.findAll(raw).mapNotNull { cents(it.groupValues[1]) }.distinct().toList()
        if (amounts.size != 1) return null
        val product = product(raw)
        return Order("", product, amounts.single(), hash("click:$mode:$product:${amounts.single()}:${now / 300_000L}"), mode, true)
    }
    private fun product(raw: String): String =
        Regex("(?:商品名称|商品|商户|已选)\\s*[:：]\\s*(.{1,100}?)(?=\\n|数量|款式|套餐|优先使用|使用先用后付|提交订单|0元下单|$)")
            .find(raw)?.groupValues?.get(1)?.trim() ?: "拼多多订单（请补充商品）"

    fun isPayment(raw: String): Boolean = raw.contains("拼多多") &&
        (raw.contains("先用后付") || raw.contains("多多支付") || raw.contains("自动扣款")) &&
        listOf("扣款成功", "已扣款", "已扣费", "支付成功", "已支付", "支付凭证").any(raw::contains) &&
        listOf("将于", "即将", "预计", "待扣款", "扣款失败", "退款").none(raw::contains)

    fun canSettle(order: com.github.bookkeeping.data.DeferredOrder, bill: com.github.bookkeeping.data.BillEntity): Boolean =
        order.status == "OPEN" && order.billId != bill.id && order.amountCents == bill.amountCents &&
            bill.transactionTime >= order.placedAt &&
            bill.direction in setOf(com.github.bookkeeping.Direction.EXPENSE, com.github.bookkeeping.Direction.DEFERRED_PAYMENT)

    fun exactMatch(orders: List<com.github.bookkeeping.data.DeferredOrder>, number: String, cents: Long, time: Long): com.github.bookkeeping.data.DeferredOrder? =
        if (number.isBlank()) null else orders.filter {
            it.status == "OPEN" && it.orderNumber == number && it.amountCents == cents && time >= it.placedAt
        }.singleOrNull()

    private fun cents(value: String): Long? = runCatching { BigDecimal(value).movePointRight(2).longValueExact() }.getOrNull()?.takeIf { it > 0 }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
