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
        return captureParsed(parsed, now)
    }

    fun captureParsed(parsed: DeferredParser.Order, now: Long): Boolean {
        var inserted = false
        db.runInTransaction {
            val dao = db.deferredOrderDao()
            dao.purgeIgnored(now)
            if (dao.isIgnored(parsed.fingerprint, now) > 0 || (!parsed.fingerprint.startsWith("screenshot-") && dao.isIgnored(contentKey(parsed.platform, parsed.product, parsed.cents), now) > 0) ||
                (parsed.number.isNotBlank() && dao.isIgnored(numberKey(parsed.platform, parsed.number), now) > 0)) return@runInTransaction
            if (parsed.number.isNotBlank() && dao.getAll().any { it.orderNumber == parsed.number && it.platform == parsed.platform }) return@runInTransaction
            if (!parsed.fingerprint.startsWith("screenshot-") && parsed.number.isBlank() && dao.getAll().any {
                it.platform == parsed.platform && it.orderNumber.isBlank() && it.amountCents == parsed.cents && it.paymentMode == parsed.mode &&
                    it.product == parsed.product && kotlin.math.abs(now - it.placedAt) <= 2_000L
            }) return@runInTransaction
            val order = DeferredOrder().apply {
                orderNumber = parsed.number; product = parsed.product; amountCents = parsed.cents; paymentMode = parsed.mode
                platform = parsed.platform; accountName = parsed.account; amountOptions = parsed.choices.joinToString(",")
                categoryKey = parsed.category; direction = parsed.direction
                placedAt = now; fingerprint = parsed.fingerprint
                // Checkout pages contain shipping addresses: never persist the full page.
                evidence = if (parsed.submitted) "检测到提交动作，尚未验证下单成功" else "检测到交易或订单文案"
                evidence += "；" + parsed.reason
            }
            inserted = dao.insert(order) > 0
        }
        return inserted
    }

    suspend fun confirm(id: Long?, product: String, amount: Long, category: String, number: String, placedAt: Long, mode: String = "DEFERRED", platform: String = SourceKey.PINDUODUO, account: String = "", direction: String = Direction.EXPENSE, reviewOnly: Boolean = false) = withContext(Dispatchers.IO) {
        require(amount in 1..999_999_999L) { "金额应大于 0，且不超过 9,999,999.99 元" }
        require(product.isNotBlank()) { "请填写商品或商户" }
        require(direction in setOf(Direction.EXPENSE, Direction.INCOME)) { "请选择收入或支出" }
        require(category in CategoryCatalog.forDirection(direction).map { it.key }) { "请选择支出分类" }
        require(mode in setOf("DEFERRED", "IMMEDIATE")) { "请选择支付方式" }
        require(number.length <= 40) { "订单号过长" }
        require(placedAt > 0 && placedAt <= System.currentTimeMillis() + 60_000L) { "请选择有效的下单时间" }
        db.runInTransaction {
            val dao = db.deferredOrderDao()
            val order = if (id == null) DeferredOrder().apply {
                fingerprint = "manual-order-${UUID.randomUUID()}"
            } else requireNotNull(dao.getById(id)) { "订单已不存在" }
            require(!reviewOnly || order.status == "REVIEW") { "这条记录已在别处处理，请刷新" }
            require(order.status in setOf("REVIEW", "OPEN") || order.direction == Direction.INCOME) { "已结清或已取消的订单不能直接修改" }
            if (number.isNotBlank()) require(dao.getAll().none { it.id != order.id && it.orderNumber == number && it.platform == platform }) { "这个订单号已登记，请勿重复记账" }
            order.product = product.trim(); order.amountCents = amount; order.categoryKey = category
            order.orderNumber = number.trim(); order.placedAt = placedAt; order.paymentMode = mode
            order.platform = platform; order.accountName = account.trim().take(40); order.direction = direction
            val bill = if (order.billId == 0L) BillEntity().apply {
                fingerprint = "order-consumption-${order.fingerprint}"
                createdAt = System.currentTimeMillis()
            } else requireNotNull(db.billDao().getById(order.billId)) { "原消费记录缺失" }
            bill.amountCents = amount; bill.direction = direction; bill.categoryKey = category
            bill.merchant = order.product; bill.account = account.ifBlank { if (mode == "DEFERRED") "拼多多先用后付" else if (platform == SourceKey.PINDUODUO) "多多支付" else com.github.bookkeeping.automation.Platforms.name(platform) }
            bill.note = "下单消费（扣款不重复计支出）"; bill.transactionTime = placedAt
            bill.sourceChannel = platform; bill.reviewStatus = ReviewStatus.ACCEPTED
            bill.updatedAt = System.currentTimeMillis()
            if (order.billId == 0L) {
                order.billId = db.billDao().insert(bill)
                check(order.billId > 0) { "消费记录保存失败" }
            } else db.billDao().update(bill)
            order.status = if (direction == Direction.INCOME) "SETTLED" else "OPEN"
            if (direction == Direction.INCOME) order.settledAt = placedAt
            if (order.id == 0L) { order.id = dao.insert(order); check(order.id > 0) } else dao.update(order)
            // If a clear payment was captured before order confirmation, reconcile it now.
            if (order.orderNumber.isNotBlank() && order.platform == SourceKey.PINDUODUO && order.direction == Direction.EXPENSE) {
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
            require(order.status in setOf("REVIEW", "OPEN") || order.direction == Direction.INCOME) { "已结清订单请用退款记账处理，不能直接取消" }
            if (order.status == "REVIEW") {
                ignoreInTransaction(order)
                db.deferredOrderDao().delete(order.id)
                return@runInTransaction
            }
            if (order.billId != 0L) db.billDao().deleteById(order.billId)
            order.billId = 0; order.status = "CANCELLED"
            db.deferredOrderDao().update(order)
        }
    }

    suspend fun ignoreReview(id: Long) = withContext(Dispatchers.IO) {
        db.runInTransaction {
            val order = requireNotNull(db.deferredOrderDao().getById(id))
            require(order.status == "REVIEW") { "这条记录已经入账，请刷新后处理" }
            ignoreInTransaction(order); db.deferredOrderDao().delete(id)
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

    private fun ignoreInTransaction(order: DeferredOrder) {
        val now = System.currentTimeMillis()
        val keys = if (order.fingerprint.startsWith("screenshot-")) listOf(order.fingerprint)
            else listOf(order.fingerprint, contentKey(order.platform, order.product, order.amountCents))
        keys.forEach { key ->
            db.deferredOrderDao().ignore(IgnoredFingerprint().apply { fingerprint = key; expiresAt = now + 300_000L })
        }
        if (order.orderNumber.isNotBlank()) db.deferredOrderDao().ignore(IgnoredFingerprint().apply {
            fingerprint = numberKey(order.platform, order.orderNumber); expiresAt = Long.MAX_VALUE
        })
    }

    suspend fun repaymentDraft(id: Long, amount: Long? = null) = withContext(Dispatchers.IO) {
        db.runInTransaction {
            val order = requireNotNull(db.deferredOrderDao().getById(id))
            if (amount != null) { require(amount in 1..999_999_999L); order.amountCents = amount }
            require(order.status == "REVIEW" && order.amountCents > 0 && order.direction == Direction.EXPENSE) { "请先核对金额" }
            val bill = BillEntity().apply {
                amountCents = order.amountCents; direction = Direction.REPAYMENT; categoryKey = CategoryKey.TRANSFER
                merchant = order.product; account = order.accountName; transactionTime = order.placedAt
                createdAt = System.currentTimeMillis(); updatedAt = createdAt; sourceChannel = order.platform
                reviewStatus = ReviewStatus.ACCEPTED; fingerprint = "repayment-${order.fingerprint}"; note = "还款，不计消费"
            }
            check(db.billDao().insert(bill) > 0) { "已存在相同还款记录" }
            ignoreInTransaction(order); db.deferredOrderDao().delete(order.id)
        }
    }

    suspend fun linkDraftPayment(draftId: Long, orderId: Long) = withContext(Dispatchers.IO) {
        db.runInTransaction {
            val draft = requireNotNull(db.deferredOrderDao().getById(draftId))
            val order = requireNotNull(db.deferredOrderDao().getById(orderId))
            require(draft.status == "REVIEW" && draft.id != order.id && draft.direction == Direction.EXPENSE)
            val payment = BillEntity().apply {
                amountCents = draft.amountCents; direction = Direction.DEFERRED_PAYMENT
                merchant = draft.product; account = draft.accountName; transactionTime = draft.placedAt
                sourceChannel = draft.platform; createdAt = System.currentTimeMillis(); updatedAt = createdAt
                fingerprint = "linked-payment-${draft.fingerprint}"; reviewStatus = ReviewStatus.ACCEPTED
            }
            require(DeferredParser.canSettle(order, payment)) { "金额或时间不匹配" }
            payment.id = db.billDao().insert(payment); check(payment.id > 0)
            settleInTransaction(db, order, payment)
            ignoreInTransaction(draft); db.deferredOrderDao().delete(draft.id)
        }
    }

    companion object {
        private fun contentKey(platform: String, product: String, cents: Long) = com.github.bookkeeping.automation.ShoppingParser.hash("ignored:$platform:$product:$cents")
        private fun numberKey(platform: String, number: String) = com.github.bookkeeping.automation.ShoppingParser.hash("ignored-order:$platform:$number")
        /** Called inside an existing transaction, only for exact order-number + amount matches. */
        fun reconcilePayment(db: BookkeepingDatabase, payment: BillEntity, number: String) {
            DeferredParser.exactMatch(db.deferredOrderDao().getAll().filter { it.platform == SourceKey.PINDUODUO }, number, payment.amountCents, payment.transactionTime)
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
