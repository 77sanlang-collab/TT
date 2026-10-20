package com.github.bookkeeping.deferred

import com.github.bookkeeping.*
import com.github.bookkeeping.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** All order/bill changes are atomic. Settlement never creates a second consumption. */
class DeferredRepository(private val db: BookkeepingDatabase) {
    fun ordersFlow() = db.deferredOrderDao().observeAll()

    fun capture(packageName: String?, raw: String, now: Long, clicked: String? = null): Boolean {
        val parsed = if (clicked == null) DeferredParser.order(packageName, raw, now)
            else DeferredParser.clickedOrder(packageName, clicked, raw, now)
        if (parsed == null) return false
        var inserted = false
        db.runInTransaction {
            val dao = db.deferredOrderDao()
            if (parsed.number.isNotBlank() && dao.getAll().any { it.orderNumber == parsed.number }) return@runInTransaction
            if (parsed.number.isNotBlank()) {
                val recent = dao.getAll().filter {
                    it.orderNumber.isBlank() && it.amountCents == parsed.cents && it.paymentMode == parsed.mode &&
                        it.status in setOf("REVIEW", "OPEN") && now - it.placedAt in 0..120_000L
                }.singleOrNull()
                if (recent != null) { recent.orderNumber = parsed.number; dao.update(recent); return@runInTransaction }
            }
            if (parsed.number.isBlank() && dao.getAll().any {
                it.orderNumber.isBlank() && it.amountCents == parsed.cents && it.paymentMode == parsed.mode &&
                    it.product == parsed.product && kotlin.math.abs(now - it.placedAt) <= 300_000L
            }) return@runInTransaction
            val order = DeferredOrder().apply {
                orderNumber = parsed.number; product = parsed.product; amountCents = parsed.cents; paymentMode = parsed.mode
                placedAt = now; fingerprint = parsed.fingerprint
                // Checkout pages contain shipping addresses: never persist the full page.
                evidence = if (parsed.submitted) "检测到提交动作，尚未验证下单成功" else "检测到订单成功文案"
            }
            inserted = dao.insert(order) > 0
        }
        return inserted
    }

    suspend fun confirm(id: Long?, product: String, amount: Long, category: String, number: String, placedAt: Long, mode: String = "DEFERRED") = withContext(Dispatchers.IO) {
        require(amount in 1..999_999_999L) { "金额应大于 0，且不超过 9,999,999.99 元" }
        require(product.isNotBlank()) { "请填写商品或商户" }
        require(category in CategoryCatalog.expense.map { it.key }) { "请选择支出分类" }
        require(mode in setOf("DEFERRED", "IMMEDIATE")) { "请选择支付方式" }
        require(number.length <= 40) { "订单号过长" }
        require(placedAt > 0 && placedAt <= System.currentTimeMillis() + 60_000L) { "请选择有效的下单时间" }
        db.runInTransaction {
            val dao = db.deferredOrderDao()
            val order = if (id == null) DeferredOrder().apply {
                fingerprint = "manual-order-${UUID.randomUUID()}"
            } else requireNotNull(dao.getById(id)) { "订单已不存在" }
            require(order.status in setOf("REVIEW", "OPEN")) { "已结清或已取消的订单不能直接修改" }
            if (number.isNotBlank()) require(dao.getAll().none { it.id != order.id && it.orderNumber == number }) { "这个订单号已登记，请勿重复记账" }
            order.product = product.trim(); order.amountCents = amount; order.categoryKey = category
            order.orderNumber = number.trim(); order.placedAt = placedAt; order.paymentMode = mode
            val bill = if (order.billId == 0L) BillEntity().apply {
                fingerprint = "order-consumption-${order.fingerprint}"
                createdAt = System.currentTimeMillis()
            } else requireNotNull(db.billDao().getById(order.billId)) { "原消费记录缺失" }
            bill.amountCents = amount; bill.direction = Direction.EXPENSE; bill.categoryKey = category
            bill.merchant = order.product; bill.account = if (mode == "DEFERRED") "拼多多先用后付" else "多多支付"
            bill.note = "下单消费（扣款不重复计支出）"; bill.transactionTime = placedAt
            bill.sourceChannel = SourceKey.PINDUODUO; bill.reviewStatus = ReviewStatus.ACCEPTED
            bill.updatedAt = System.currentTimeMillis()
            if (order.billId == 0L) {
                order.billId = db.billDao().insert(bill)
                check(order.billId > 0) { "消费记录保存失败" }
            } else db.billDao().update(bill)
            order.status = "OPEN"
            if (order.id == 0L) { order.id = dao.insert(order); check(order.id > 0) } else dao.update(order)
            // If a clear payment was captured before order confirmation, reconcile it now.
            if (order.orderNumber.isNotBlank()) {
                val payments = db.billDao().getAll().filter {
                    it.direction == Direction.DEFERRED_PAYMENT &&
                        DeferredParser.orderNumber(it.note.orEmpty()) == order.orderNumber &&
                        DeferredParser.canSettle(order, it) && dao.forBill(it.id) == null
                }
                payments.singleOrNull()?.let { settleInTransaction(db, order, it) }
            }
        }
    }

    suspend fun cancel(id: Long) = withContext(Dispatchers.IO) {
        db.runInTransaction {
            val order = requireNotNull(db.deferredOrderDao().getById(id))
            require(order.status in setOf("REVIEW", "OPEN")) { "已结清订单请用退款记账处理，不能直接取消" }
            if (order.billId != 0L) db.billDao().deleteById(order.billId)
            order.billId = 0; order.status = "CANCELLED"
            db.deferredOrderDao().update(order)
        }
    }

    suspend fun settle(id: Long, paymentId: Long?) = withContext(Dispatchers.IO) {
        db.runInTransaction {
            val order = requireNotNull(db.deferredOrderDao().getById(id))
            require(order.status == "OPEN") { "请先确认订单入账" }
            if (paymentId == null) {
                order.status = "SETTLED"; order.settledAt = System.currentTimeMillis()
                db.deferredOrderDao().update(order)
            } else {
                val payment = requireNotNull(db.billDao().getById(paymentId))
                require(DeferredParser.canSettle(order, payment)) { "金额、时间或记录类型不匹配；部分扣款暂不支持关联" }
                require(db.deferredOrderDao().forBill(paymentId) == null) { "此账单已关联其他订单" }
                settleInTransaction(db, order, payment)
            }
        }
    }

    suspend fun undoSettlement(id: Long) = withContext(Dispatchers.IO) {
        db.runInTransaction {
            val order = requireNotNull(db.deferredOrderDao().getById(id))
            require(order.status == "SETTLED") { "订单未结清" }
            if (order.settlementBillId != 0L) {
                val payment = requireNotNull(db.billDao().getById(order.settlementBillId))
                payment.direction = Direction.DEFERRED_PAYMENT; payment.reviewStatus = ReviewStatus.REVIEW
                db.billDao().update(payment)
            }
            order.status = "OPEN"; order.settlementBillId = 0; order.settledAt = 0
            db.deferredOrderDao().update(order)
        }
    }

    suspend fun treatAsExpense(id: Long) = withContext(Dispatchers.IO) {
        db.runInTransaction {
            require(db.deferredOrderDao().forBill(id) == null) { "请先撤销关联" }
            val bill = requireNotNull(db.billDao().getById(id))
            require(bill.direction == Direction.DEFERRED_PAYMENT)
            bill.direction = Direction.EXPENSE; bill.reviewStatus = ReviewStatus.ACCEPTED
            db.billDao().update(bill)
        }
    }

    companion object {
        /** Called inside an existing transaction, only for exact order-number + amount matches. */
        fun reconcilePayment(db: BookkeepingDatabase, payment: BillEntity, number: String) {
            DeferredParser.exactMatch(db.deferredOrderDao().getAll(), number, payment.amountCents, payment.transactionTime)
                ?.let { settleInTransaction(db, it, payment) }
        }
        private fun settleInTransaction(db: BookkeepingDatabase, order: DeferredOrder, payment: BillEntity) {
            payment.direction = Direction.DEFERRED_PAYMENT
            payment.reviewStatus = ReviewStatus.ACCEPTED
            db.billDao().update(payment)
            order.status = "SETTLED"; order.settlementBillId = payment.id; order.settledAt = payment.transactionTime
            db.deferredOrderDao().update(order)
        }
    }
}
