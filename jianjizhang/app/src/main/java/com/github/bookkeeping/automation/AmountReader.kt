package com.github.bookkeeping.automation

import java.math.BigDecimal

/** Only explicitly labelled totals outrank other amounts. Ambiguity is never a guessed payment. */
object AmountReader {
    data class Result(val cents: Long?, val choices: List<Long>, val reason: String)
    val labels = listOf("确认收货后付款", "立即支付", "免密支付", "提交订单", "总计", "合计", "实付金额", "实付款", "实际付款", "实际支付", "支付金额", "实付价", "实付", "应付金额", "订单金额", "小计")
    private const val number = "([0-9]{1,7}(?:\\.[0-9]{1,2})?)(?![0-9.])"
    private val currency = Regex("(?:[¥￥]|RMB|CNY|USD)\\s*$number|(?<![0-9.])$number\\s*元", RegexOption.IGNORE_CASE)
    private fun cents(s: String): Long? = runCatching { BigDecimal(s).movePointRight(2).longValueExact() }.getOrNull()?.takeIf { it in 1..999_999_999L }
    fun read(raw: String, preferred: List<String> = labels): Result {
        val text = raw.take(24000)
        val all = currency.findAll(text).mapNotNull { m -> cents(m.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()) }.distinct().toList()
        val contextual = when {
            preferred == labels && (text.contains("退款成功") || text.contains("退款到账")) -> listOf("实际退款", "实退金额", "退款金额")
            preferred == labels && Platforms.repayment(text) -> listOf("实际还款", "实还金额", "还款金额")
            else -> emptyList()
        }
        val effective = if (contextual.isNotEmpty()) contextual else preferred
        if (preferred == labels && contextual.isEmpty()) {
            val strong = listOf("确认收货后付款", "立即支付", "免密支付", "提交订单", "总计", "合计", "实付金额", "实付款", "实际付款", "实际支付", "支付金额", "应付金额")
            val totals = strong.flatMap { label ->
                Regex(Regex.escape(label) + "\\s*[:：]?\\s*(?:[¥￥]|RMB|CNY|USD)?\\s*" + number)
                    .findAll(text).mapNotNull { cents(it.groupValues[1]) }.toList()
            }.distinct()
            if (totals.size > 1) return Result(null, (totals + all).distinct(), "多个总额标签相互冲突，请核对最终金额")
        }
        for (label in effective.filter { it.isNotBlank() }.take(24)) {
            val pattern = Regex(Regex.escape(label) + "\\s*[:：]?\\s*(?:[¥￥]|RMB|CNY|USD)?\\s*" + number)
            val matches = pattern.findAll(text).mapNotNull { cents(it.groupValues[1]) }.distinct().toList()
            if (matches.size == 1) return Result(matches.single(), all.ifEmpty { matches }, "金额依据：$label")
            if (matches.size > 1) return Result(null, (matches + all).distinct(), "“$label”出现不同金额，请选择本单总额")
        }
        // A single visible amount can be proposed, but still requires explicit confirmation.
        return when (all.size) {
            1 -> Result(all.single(), all, "仅找到一个货币金额，请核对是否实付")
            0 -> Result(null, emptyList(), "未找到可信金额，请手动输入")
            else -> Result(null, all, "存在多个金额且没有明确总额，不自动选取")
        }
    }
}
