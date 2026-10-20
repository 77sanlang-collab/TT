package com.github.bookkeeping

import android.content.Context
import android.net.Uri
import com.github.bookkeeping.data.BillEntity
import com.github.bookkeeping.data.BookkeepingDatabase
import com.github.bookkeeping.recognition.CsvBillImporter
import com.github.bookkeeping.recognition.ImportResult
import com.github.bookkeeping.recognition.PiiMasker
import com.github.bookkeeping.recognition.RecognitionCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class BookkeepingRepository(
    private val context: Context,
    private val database: BookkeepingDatabase
) {
    /** 账单的响应式流：数据库变化时自动推送，UI 无需手动 reload。 */
    fun billsFlow(): Flow<List<BillEntity>> = database.billDao().observeAll()

    fun reviewQueueFlow(): Flow<List<BillEntity>> = database.billDao().observeReviewQueue()

    suspend fun bills(): List<BillEntity> = withContext(Dispatchers.IO) {
        database.billDao().getAll()
    }

    suspend fun search(query: String): List<BillEntity> = withContext(Dispatchers.IO) {
        if (query.isBlank()) database.billDao().getAll() else database.billDao().search(escapeLike(query.trim()))
    }

    /** 转义 LIKE 通配符，避免 "5%" 等查询被当成通配符匹配全表。 */
    private fun escapeLike(query: String): String =
        "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    suspend fun saveManual(
        existingId: Long?,
        amountCents: Long,
        direction: String,
        categoryKey: String,
        merchant: String,
        account: String,
        note: String,
        transactionTime: Long
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // 手动单的 fingerprint 含 id（编辑）或时间戳（新增），使其总是唯一，
        // 不参与自动去重——用户主动录入的即应保留。
        val fingerprint = "manual-${existingId ?: now}-$amountCents-$direction-$transactionTime"
        database.runInTransaction {
            val existing = existingId?.let { database.billDao().getById(it) }
            require(existingId == null || database.deferredOrderDao().forBill(existingId) == null) { "请在先用后付页面管理关联账单" }
            require(existing?.direction != Direction.DEFERRED_PAYMENT) { "请先在先用后付页面处理此扣款" }
            val bill = BillEntity().apply {
                id = existingId ?: 0L
                this.amountCents = amountCents
                this.direction = direction
                this.categoryKey = categoryKey
                this.merchant = merchant
                this.account = account
                this.note = note
                this.transactionTime = transactionTime
                createdAt = existing?.createdAt ?: now
                updatedAt = now
                sourceChannel = SourceKey.MANUAL
                rawRecognizedText = ""
                confidence = 1.0
                reviewStatus = ReviewStatus.ACCEPTED
                this.fingerprint = fingerprint
            }
            if (existing == null) {
                database.billDao().insert(bill)
            } else {
                database.billDao().update(bill)
            }
        }
    }

    suspend fun acceptCandidate(candidate: RecognitionCandidate): Boolean = withContext(Dispatchers.IO) {
        insertCandidateBlocking(database, candidate)
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            require(database.deferredOrderDao().forBill(id) == null) { "请在先用后付页面取消订单或撤销扣款关联" }
            database.billDao().deleteById(id)
        }
    }

    suspend fun setRepayment(id: Long, exclude: Boolean) = withContext(Dispatchers.IO) {
        database.runInTransaction {
            require(database.deferredOrderDao().forBill(id) == null) { "关联订单请在订单页处理，不能排除原消费" }
            val bill = requireNotNull(database.billDao().getById(id))
            require(bill.direction in setOf(Direction.EXPENSE, Direction.REPAYMENT, Direction.DEFERRED_PAYMENT)) { "收入不能标记还款" }
            bill.direction = if (exclude) Direction.REPAYMENT else Direction.EXPENSE
            bill.reviewStatus = ReviewStatus.ACCEPTED; bill.updatedAt = System.currentTimeMillis()
            database.billDao().update(bill)
        }
    }

    suspend fun importFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { stream ->
            // 固定 UTF-8 解码并剥离 BOM，避免首行 header 识别失败
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.orEmpty().removePrefix("\uFEFF")
        var imported = 0
        var skipped = 0
        CsvBillImporter.parseRows(text).forEach { candidate ->
            if (insertCandidateBlocking(database, candidate.copy(sourceChannel = SourceKey.IMPORT))) {
                imported++
            } else {
                skipped++
            }
        }
        ImportResult(imported, skipped)
    }

    companion object {
        fun insertCandidateBlocking(database: BookkeepingDatabase, candidate: RecognitionCandidate): Boolean {
            if (candidate.amountCents <= 0L) return false
            var inserted = false
            database.runInTransaction {
                val now = System.currentTimeMillis()
                val payment = candidate.direction == Direction.EXPENSE &&
                    com.github.bookkeeping.deferred.DeferredParser.isPayment(candidate.rawText)
                val number = com.github.bookkeeping.deferred.DeferredParser.orderNumber(candidate.rawText)
                val identity = if (payment && number.isNotBlank()) "pdd-payment-$number-${candidate.amountCents}" else candidate.fingerprint
                if (database.billDao().countByFingerprint(identity) > 0) return@runInTransaction
                val bill = BillEntity().apply {
                    amountCents = candidate.amountCents
                    direction = if (payment) Direction.DEFERRED_PAYMENT else candidate.direction
                    categoryKey = candidate.categoryKey
                    merchant = candidate.merchant
                    account = candidate.account
                    note = if (payment && number.isNotBlank()) "订单号：$number" else candidate.note
                    transactionTime = candidate.transactionTime
                    createdAt = now; updatedAt = now
                    sourceChannel = candidate.sourceChannel
                    rawRecognizedText = PiiMasker.mask(candidate.rawText)
                    confidence = candidate.confidence
                    reviewStatus = if (payment) ReviewStatus.REVIEW else candidate.reviewStatus
                    fingerprint = identity
                }
                bill.id = database.billDao().insert(bill)
                inserted = bill.id > 0
                if (inserted && payment) com.github.bookkeeping.deferred.DeferredRepository.reconcilePayment(database, bill, number)
            }
            return inserted
        }
    }
}
