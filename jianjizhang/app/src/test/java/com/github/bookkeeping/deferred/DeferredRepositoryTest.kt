package com.github.bookkeeping.deferred

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.github.bookkeeping.*
import com.github.bookkeeping.data.*
import com.github.bookkeeping.recognition.PaymentTextParser
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DeferredRepositoryTest {
    private lateinit var db: BookkeepingDatabase
    private lateinit var repo: DeferredRepository
    private val placedAt = 1_790_000_000_000L
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), BookkeepingDatabase::class.java).allowMainThreadQueries().build()
        repo = DeferredRepository(db)
    }
    @After fun tearDown() { db.close() }
    private fun capture(number: String = "123456789012345678") {
        repo.capture(DeferredParser.PACKAGE, "下单成功 先用后付 订单金额：¥80.00 商品：毛巾 订单号：$number", placedAt)
    }
    private suspend fun confirm() {
        val o = db.deferredOrderDao().getAll().first()
        repo.confirm(o.id, "毛巾", 8000, CategoryKey.SHOPPING, o.orderNumber, placedAt)
    }
    private fun payment(number: String = "123456789012345678", cents: String = "80.00"): Long {
        val raw = "拼多多先用后付扣款成功${cents}元" + if (number.isBlank()) "" else " 订单号：$number"
        val candidate = PaymentTextParser.parse("com.tencent.mm", "微信支付", raw, placedAt + 864000000)!!
        BookkeepingRepository.insertCandidateBlocking(db, candidate)
        return db.billDao().getAll().first { it.direction == Direction.DEFERRED_PAYMENT }.id
    }
    private fun expenseTotal() = db.billDao().getAll().filter { it.direction == Direction.EXPENSE }.sumOf { it.amountCents }

    @Test fun confirmationRecordsPurchaseDateAndExactPaymentSettlesWithoutSecondExpense() = runBlocking {
        capture(); capture()
        assertEquals(1, db.deferredOrderDao().getAll().size)
        assertEquals(0, db.billDao().getAll().size)
        confirm(); confirm()
        assertEquals(8000L, expenseTotal())
        assertEquals(placedAt, db.billDao().getAll().single().transactionTime)
        payment()
        assertEquals("SETTLED", db.deferredOrderDao().getAll().single().status)
        assertEquals(8000L, expenseTotal())
        payment()
        assertEquals(2, db.billDao().getAll().size)
        repo.undoSettlement(db.deferredOrderDao().getAll().single().id)
        assertEquals("OPEN", db.deferredOrderDao().getAll().single().status)
        assertEquals(8000L, expenseTotal())
        assertEquals(ReviewStatus.REVIEW, db.billDao().getAll().first { it.direction == Direction.DEFERRED_PAYMENT }.reviewStatus)
    }
    @Test fun sameAmountWithoutOrderNumberNeverAutomaticallySettlesAndManualLinkIsReversible() = runBlocking {
        capture(); confirm()
        val id = payment("")
        val order = db.deferredOrderDao().getAll().single()
        assertEquals("OPEN", order.status)
        repo.settle(order.id, id)
        assertEquals(8000L, expenseTotal())
        repo.undoSettlement(order.id)
        repo.treatAsExpense(id)
        assertEquals(16000L, expenseTotal())
        repo.settle(order.id, id)
        assertEquals(8000L, expenseTotal())
    }
    @Test fun differentAmountAndAlreadyLinkedBillsAreRejectedAtomically() = runBlocking {
        capture(); confirm()
        val paymentId = payment("", "79.00")
        val order = db.deferredOrderDao().getAll().single()
        try { repo.settle(order.id, paymentId); fail("Must reject unequal amounts") } catch (_: IllegalArgumentException) { }
        assertEquals("OPEN", db.deferredOrderDao().getById(order.id).status)
        assertEquals(8000L, expenseTotal())
        try { repo.settle(order.id, order.billId); fail("Cannot link original consumption") } catch (_: IllegalArgumentException) { }
    }
    @Test fun cancellationRemovesConsumptionAndBlocksRecapture() = runBlocking {
        capture(); confirm()
        repo.cancel(db.deferredOrderDao().getAll().single().id)
        assertEquals(0L, expenseTotal())
        capture()
        assertEquals(1, db.deferredOrderDao().getAll().size)
        assertEquals("CANCELLED", db.deferredOrderDao().getAll().single().status)
    }
    @Test fun paymentBeforeConfirmationIsMatchedAfterConfirmation() = runBlocking {
        capture(); payment(); confirm()
        assertEquals("SETTLED", db.deferredOrderDao().getAll().single().status)
        assertEquals(8000L, expenseTotal())
    }
    @Test fun buttonClickCreatesOnlyReviewDraftAndConfirmedDuoduoPaymentIsNotDeferredDebt() = runBlocking {
        val raw = "已选：腰带 数量1 优先使用多多支付 提交订单 ¥3.99"
        assertFalse(repo.capture(DeferredParser.PACKAGE, raw, placedAt))
        assertTrue(repo.capture(DeferredParser.PACKAGE, raw, placedAt, "提交订单 ¥3.99"))
        assertFalse(repo.capture(DeferredParser.PACKAGE, raw, placedAt + 100, "提交订单 ¥3.99"))
        assertTrue(db.billDao().getAll().isEmpty())
        val o = db.deferredOrderDao().getAll().single()
        assertEquals("REVIEW", o.status)
        assertEquals("IMMEDIATE", o.paymentMode)
        assertFalse(o.evidence.contains("腰带"))
        repo.confirm(o.id, o.product, o.amountCents, o.categoryKey, "", placedAt, o.paymentMode)
        assertEquals(399L, expenseTotal())
        assertEquals("多多支付", db.billDao().getAll().single().account)
        assertEquals(0L, db.deferredOrderDao().getAll().filter { it.status == "OPEN" && it.paymentMode == "DEFERRED" }.sumOf { it.amountCents })
    }
    @Test fun migrationPreservesExistingBillsAndValidatesRoomSchema() {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        val schema = JSONObject(javaClass.getResource("/schema-v2.json")!!.readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
                val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            old.execSQL("INSERT INTO bills (amountCents,direction,categoryKey,merchant,account,note,transactionTime,createdAt,updatedAt,sourceChannel,rawRecognizedText,confidence,reviewStatus,fingerprint) VALUES (1234,'EXPENSE','food','旧账单','','',1,1,1,'MANUAL','',1.0,'ACCEPTED','old-bill')")
            old.version = 2
        }
        val upgraded = Room.databaseBuilder(context, BookkeepingDatabase::class.java, name).allowMainThreadQueries()
            .addMigrations(BookkeepingDatabase.MIGRATION_2_3, BookkeepingDatabase.MIGRATION_3_4).build()
        try {
            assertEquals(1234L, upgraded.billDao().getAll().single().amountCents)
            assertTrue(upgraded.deferredOrderDao().getAll().isEmpty())
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
