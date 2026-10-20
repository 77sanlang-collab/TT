package com.github.bookkeeping.automation

import com.github.bookkeeping.SourceKey

object Platforms {
    const val XHS = "XIAOHONGSHU"
    const val DOUYIN = "DOUYIN"
    const val XIANYU = "XIANYU"
    const val CUSTOM = "CUSTOM"
    const val SCREENSHOT = "SCREENSHOT"
    val apps = linkedMapOf("com.xunmeng.pinduoduo" to SourceKey.PINDUODUO, "com.xingin.xhs" to XHS, "com.ss.android.ugc.aweme" to DOUYIN, "com.taobao.idlefish" to XIANYU)
    fun name(key: String) = when (key) {
        SourceKey.PINDUODUO -> "拼多多支付"; XHS -> "小红书支付"; DOUYIN -> "抖音支付"; XIANYU -> "闲鱼支付"
        SourceKey.WECHAT -> "微信支付"; SourceKey.ALIPAY -> "支付宝"; SourceKey.BANK -> "银行卡"
        SourceKey.UNIONPAY -> "云闪付"; SourceKey.VIVO_WALLET -> "钱包"; SCREENSHOT -> "截图补记"; else -> "自定义平台"
    }
    fun repayment(raw: String) = listOf("还款成功", "已还款", "成功还款", "还款已完成").any(raw::contains) &&
        listOf("待还款", "将于", "预计", "还款失败").none(raw::contains)
}
