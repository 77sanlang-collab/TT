package com.github.bookkeeping.deferred

import com.github.bookkeeping.recognition.PaymentTextParser
import org.junit.Assert.*
import org.junit.Test

class DeferredParserTest {
    private val success = "下单成功 先用后付 订单金额：¥80.00 商品：毛巾 订单号：123456789012345678"
    @Test fun onlyPddAndExplicitSuccessAreCaptured() {
        assertNotNull(DeferredParser.order(DeferredParser.PACKAGE, success, 1000))
        assertNull(DeferredParser.order("com.tencent.mm", success, 1000))
        assertNull(DeferredParser.order(DeferredParser.PACKAGE, success.replace("下单成功", "确认订单"), 1000))
        listOf("待支付", "待付款", "下单失败", "订单已取消", "退款成功", "扣款成功").forEach {
            assertNull(DeferredParser.order(DeferredParser.PACKAGE, "$success $it", 1000))
        }
    }
    @Test fun amountComesFromTotalNotPriceDateOrOrderNumber() {
        val parsed = DeferredParser.order(DeferredParser.PACKAGE, success + " 2026-10-20 单价¥40.00 数量2", 1000)!!
        assertEquals(8000, parsed.cents)
        assertEquals("123456789012345678", parsed.number)
        assertNull(DeferredParser.order(DeferredParser.PACKAGE, success.replace("订单金额：¥80.00", "¥80.00"), 1000))
        assertNull(DeferredParser.order(DeferredParser.PACKAGE, "$success 应付金额：¥70.00", 1000))
        assertNull(DeferredParser.order(DeferredParser.PACKAGE, success.replace("80.00", "80.001"), 1000))
    }
    @Test fun orderNumberDeduplicatesAcrossDaysButDistinctOrdersRemainDistinct() {
        val a = DeferredParser.order(DeferredParser.PACKAGE, success, 1000)!!
        val b = DeferredParser.order(DeferredParser.PACKAGE, success, 86400000)!!
        assertEquals(a.fingerprint, b.fingerprint)
        assertNotEquals(a.fingerprint, DeferredParser.order(DeferredParser.PACKAGE, success.replace("123456789012345678", "123456789012345679"), 1000)!!.fingerprint)
    }
    @Test fun checkoutClickUsesPayableAmountInsteadOfZeroOrCrossedOutPrice() {
        val text = "款式 价格¥9 已选：两条装腰带 套餐 快要抢光¥3.99 使用 先用后付，更换多多支付最高减2元 0元下单，确认收货后付款 ¥3.99"
        assertNull(DeferredParser.order(DeferredParser.PACKAGE, text, 1000))
        assertNull(DeferredParser.clickedOrder(DeferredParser.PACKAGE, "更换支付方式", text, 1000))
        val result = DeferredParser.clickedOrder(DeferredParser.PACKAGE, "0元下单，确认收货后付款 ¥3.99", text, 1000)!!
        assertEquals(399L, result.cents)
        assertEquals("DEFERRED", result.mode)
        assertTrue(result.submitted)
        assertEquals("两条装腰带", result.product)
    }
    @Test fun duoduoPayClickIsNotConfusedWithDeferredPayment() {
        val text = "已选：两条装腰带 数量1 优先使用 多多支付最高减2元，更换支付方式 提交订单 ¥3.99"
        val result = DeferredParser.clickedOrder(DeferredParser.PACKAGE, "提交订单 ¥3.99", text, 1000)!!
        assertEquals("IMMEDIATE", result.mode)
        assertEquals(399L, result.cents)
        assertNull(DeferredParser.clickedOrder(DeferredParser.PACKAGE, "提交订单", text.replace("¥3.99", "¥0.00"), 1000))
    }
    @Test fun remindersAndRefundsAreNotSettlements() {
        assertTrue(DeferredParser.isPayment("拼多多先用后付扣款成功80.00元"))
        listOf("将于明日拼多多先用后付扣款成功80元", "拼多多先用后付扣款失败80元", "拼多多先用后付退款成功80元").forEach {
            assertFalse(DeferredParser.isPayment(it))
        }
        assertNull(PaymentTextParser.parse("com.tencent.mm", "微信支付", "拼多多先用后付将于明日扣款80.00元", 1000))
        assertNull(PaymentTextParser.parseAccessibilityText(DeferredParser.PACKAGE, success, 1000))
    }
}
