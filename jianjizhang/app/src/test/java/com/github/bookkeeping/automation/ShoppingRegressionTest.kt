package com.github.bookkeeping.automation

import com.github.bookkeeping.*
import com.github.bookkeeping.recognition.PaymentTextParser
import org.junit.Assert.*
import org.junit.Test

class ShoppingRegressionTest {
    @Test fun xiaohongshuTotalOutranksOriginalPriceDiscountAndShipping() {
        val text = "商品金额 ¥22.8\n限时立减 -¥13\n运费 ¥0\n总计：¥9.8\n微信支付\n支付宝先用后付 首次开通立减0.5元起\n总计¥9.8\n已减¥13\n立即支付"
        assertNull(ShoppingParser.parse("com.xingin.xhs", text, null, 1000))
        val draft = ShoppingParser.parse("com.xingin.xhs", text, "立即支付", 1000)!!
        assertEquals(980L, draft.cents); assertEquals(Platforms.XHS, draft.platform)
    }
    @Test fun douyinTotalOutranksInstallmentAndBankPromotions() {
        val text = "商品¥27.80\n抖音月付 不分期0费用\n¥9.45×3期\n含利息¥0.18/期\n银行卡立减5元\n更换其他支付方式最高立减10元\n立即支付 ¥27.80"
        val draft = ShoppingParser.parse("com.ss.android.ugc.aweme", text, "立即支付 ¥27.80", 1000)!!
        assertEquals(2780L, draft.cents); assertEquals("IMMEDIATE", draft.mode); assertEquals("抖音支付", draft.account)
    }
    @Test fun douyinSkuIgnoresOptionalMembershipAddOn() {
        val text = "实付价 ¥19.8\n返990金币\nDOU省卡¥2.90\n立即支付 ¥19.80\n抖音月付确认收货后付款"
        assertEquals(1980L, ShoppingParser.parse("com.ss.android.ugc.aweme", text, "立即支付 ¥19.80", 1000)!!.cents)
    }
    @Test fun xianyuBothLayoutsPickTheOrderTotal() {
        val a = "商品信息¥35.00\n运费¥0.00\n小计¥35.00\n合计：¥35.00\n确认购买"
        val b = "商品¥2.21\n其他款式¥6.00\n顺手买¥7.70\n支付宝支付\n免密支付 ¥2.21"
        assertEquals(3500L, ShoppingParser.parse("com.taobao.idlefish", a, "确认购买", 1000)!!.cents)
        assertEquals(221L, ShoppingParser.parse("com.taobao.idlefish", b, "免密支付 ¥2.21", 1000)!!.cents)
        assertNull(ShoppingParser.parse("com.taobao.idlefish", b, "更换支付方式", 1000))
    }
    @Test fun wechatFortySixDoesNotBecomeTwoAndAmbiguityIsExplicit() {
        assertEquals(4600L, PaymentTextParser.parse("com.tencent.mm", "微信支付", "支付成功 优惠¥2.00 实付¥46.00", 1000)!!.amountCents)
        val unclear = PaymentTextParser.parse("com.tencent.mm", "微信支付", "支付成功 ¥2.00 商品¥46.00", 1000)!!
        assertEquals(0L, unclear.amountCents); assertTrue(unclear.needsReview)
        assertNull(AmountReader.read("¥46.00 ¥2.00").cents)
    }
    @Test fun customRuleUsesLiteralLabelsAndOnlyTheConfiguredAppAndEvent() {
        val rule = LocalRule("test", "com.example.shop", "新平台", "确认下单", true, listOf("最终应付"))
        val raw = "原价¥99 最终应付：¥48.60 立减¥50.40 确认下单"
        assertEquals(4860L, ShoppingParser.parse(rule.packageName, raw, "确认下单", 1000, listOf(rule))!!.cents)
        assertNull(ShoppingParser.parse(rule.packageName, raw, null, 1000, listOf(rule)))
        assertNull(ShoppingParser.parse("com.other.shop", raw, "确认下单", 1000, listOf(rule)))
        assertNull(ShoppingParser.parse(AccessibilityPackages.SELF, raw, "确认下单", 1000, listOf(rule.copy(packageName = AccessibilityPackages.SELF))))
    }
    @Test fun conflictingTotalsAndMalformedDecimalsAreNeverGuessed() {
        assertNull(AmountReader.read("总计¥46.00 总计¥2.00").cents)
        assertNull(AmountReader.read("立即支付¥2.00 总计¥46.00").cents)
        assertEquals(2000L, AmountReader.read("退款成功 退款金额¥20.00 原支付金额¥46.00").cents)
        assertNull(AmountReader.read("¥9.999").cents)
        assertNull(AmountReader.read("商品编号12345678 日期2026-10-20").cents)
    }
    @Test fun actualClickedButtonOutranksAnAlreadyChangedPage() {
        val draft = ShoppingParser.parse("com.ss.android.ugc.aweme", "支付后活动 优惠¥2.00", "立即支付 ¥46.00", 1000)!!
        assertEquals(4600L, draft.cents)
    }
    @Test fun aRefundMenuDoesNotTurnAPurchaseIntoIncome() {
        val result = PaymentTextParser.parse("com.tencent.mm", "微信支付", "支付成功 实付¥46.00 申请退款", 1000)!!
        assertEquals(Direction.EXPENSE, result.direction)
    }
    @Test fun repaymentRemindersAreNotTreatedAsSuccessfulRepayment() {
        assertTrue(Platforms.repayment("抖音月付还款成功300元"))
        assertFalse(Platforms.repayment("待还款300元 将于明日自动扣款"))
        assertFalse(Platforms.repayment("还款失败300元"))
    }
}
