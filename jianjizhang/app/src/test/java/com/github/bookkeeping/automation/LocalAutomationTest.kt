package com.github.bookkeeping.automation

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.github.bookkeeping.*
import com.github.bookkeeping.data.*
import com.github.bookkeeping.deferred.*
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
class LocalAutomationTest {
    private lateinit var db: BookkeepingDatabase
    private lateinit var repo: DeferredRepository
    private val now = System.currentTimeMillis() - 10000
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(context, BookkeepingDatabase::class.java).allowMainThreadQueries().build()
        repo = DeferredRepository(db)
        context.getSharedPreferences("local_rules", 0).edit().clear().commit()
    }
    @After fun close() { db.close(); Diagnostics.clear() }
    private fun draft(platform: String = Platforms.DOUYIN, key: String = "test-order", cents: Long = 2780) = DeferredParser.Order("", "测试商品", cents, key, "IMMEDIATE", true, platform, Platforms.name(platform))
    private fun total() = db.billDao().getAll().filter { it.direction == Direction.EXPENSE }.sumOf { it.amountCents }
    private suspend fun confirm(id: Long) {
        val o = db.deferredOrderDao().getById(id)
        repo.confirm(o.id, o.product, o.amountCents, o.categoryKey, o.orderNumber, o.placedAt, o.paymentMode, o.platform, o.accountName, o.direction, true)
    }
    @Test fun ignoredDraftLeavesNoOrderNoBillAndOnlyHashesSuppressRepeats() = runBlocking {
        val p = draft(); assertTrue(repo.captureParsed(p, now))
        repo.ignoreReview(db.deferredOrderDao().getAll().single().id)
        assertTrue(db.deferredOrderDao().getAll().isEmpty()); assertTrue(db.billDao().getAll().isEmpty())
        assertFalse(repo.captureParsed(p, System.currentTimeMillis()))
        db.openHelper.writableDatabase.query("SELECT fingerprint FROM ignored_fingerprints").use { cursor ->
            assertTrue(cursor.moveToFirst()); assertFalse(cursor.getString(0).contains("测试商品"))
        }
    }
    @Test fun anOldPopupCannotIgnoreAnAlreadyAcceptedPurchase() = runBlocking {
        repo.captureParsed(draft(), now); val id = db.deferredOrderDao().getAll().single().id
        confirm(id)
        try { repo.ignoreReview(id); fail("Stale ignore must not delete an expense") } catch (_: IllegalArgumentException) { }
        assertEquals(2780L, total())
    }
    @Test fun walletCandidatesAreReviewedAndFortySixIsNotAutomaticallyPostedAsTwo() {
        assertTrue(CaptureEngine(context, db).process("com.tencent.mm", "支付成功 优惠¥2 实付¥46", null, now, false))
        assertTrue(db.billDao().getAll().isEmpty())
        assertEquals(4600L, db.deferredOrderDao().getAll().single().amountCents)
    }
    @Test fun ambiguousWalletAmountHasNoExpenseUntilEdited() {
        assertTrue(CaptureEngine(context, db).process("com.tencent.mm", "支付成功 ¥2 商品¥46", null, now, false))
        assertEquals(0L, db.deferredOrderDao().getAll().single().amountCents)
        assertTrue(db.billDao().getAll().isEmpty())
    }
    @Test fun monthlyRepaymentCanBeExcludedWithoutDeletingOriginalPurchases() = runBlocking {
        repo.captureParsed(draft(), now); confirm(db.deferredOrderDao().getAll().single().id)
        repo.captureParsed(draft(key = "repayment", cents = 2780).copy(product = "月付还款"), now + 1000)
        val pending = db.deferredOrderDao().getAll().first { it.status == "REVIEW" }
        repo.repaymentDraft(pending.id)
        assertEquals(2780L, total())
        val repayment = db.billDao().getAll().first { it.direction == Direction.REPAYMENT }
        val bills = BookkeepingRepository(context, db)
        bills.setRepayment(repayment.id, false); assertEquals(5560L, total())
        bills.setRepayment(repayment.id, true); assertEquals(2780L, total())
    }
    @Test fun walletPaymentCanBeLinkedToShoppingPurchaseWithoutSecondExpense() = runBlocking {
        repo.captureParsed(draft(), now); val original = db.deferredOrderDao().getAll().single().id; confirm(original)
        repo.captureParsed(draft(SourceKey.WECHAT, "wallet").copy(product = "微信付款"), now + 2000)
        val wallet = db.deferredOrderDao().getAll().first { it.status == "REVIEW" }
        repo.linkDraftPayment(wallet.id, original)
        assertEquals(2780L, total()); assertEquals("SETTLED", db.deferredOrderDao().getById(original).status)
        assertNull(db.deferredOrderDao().getById(wallet.id))
    }
    @Test fun screenshotIdentityPreventsDuplicateImportAcrossDifferentFilenames() {
        val p = draft(Platforms.SCREENSHOT, "screenshot-same-image")
        assertTrue(repo.captureParsed(p, now)); assertFalse(repo.captureParsed(p, now + 86400000))
        assertEquals(1, db.deferredOrderDao().getAll().size)
    }
    @Test fun distinctScreenshotsWithTheSameAmountCanBothBeImported() {
        assertTrue(repo.captureParsed(draft(Platforms.SCREENSHOT, "screenshot-one"), now))
        assertTrue(repo.captureParsed(draft(Platforms.SCREENSHOT, "screenshot-two"), now + 1000))
        assertEquals(2, db.deferredOrderDao().getAll().size)
    }
    @Test fun invalidSelfRuleIsRejectedAndValidRulesSurviveReload() {
        val store = LocalRules(context)
        val rule = LocalRule(packageName = "com.example.shop", name = "测试平台", trigger = "支付成功")
        store.save(rule); assertEquals(rule, LocalRules(context).all().single())
        try { store.save(rule.copy(packageName = AccessibilityPackages.SELF)); fail("Self capture rejected") } catch (_: IllegalArgumentException) { }
    }
    @Test fun diagnosticsAreOptInAndCanBeClearedWithoutDatabaseWrites() {
        Diagnostics.record("com.example.shop", "点击", "实付¥46", "测试")
        assertEquals("", Diagnostics.snapshots.value.text)
        Diagnostics.start(); Diagnostics.record("com.example.shop", "点击", "手机13800000000 实付¥46", "测试")
        assertFalse(Diagnostics.snapshots.value.text.contains("13800000000"))
        Diagnostics.clear(); assertEquals("", Diagnostics.snapshots.value.text); assertTrue(db.billDao().getAll().isEmpty())
    }
    @Test fun versionThreeMigrationPreservesBillsAndRemovesOldIgnoredDetails() {
        val name = "migration-v3-local.db"; context.deleteDatabase(name)
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        val schema = JSONObject(javaClass.getResource("/schema-v3.json")!!.readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                old.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
                val indices = e.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indices.length()) old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", e.getString("tableName")))
            }
            old.execSQL("INSERT INTO bills (amountCents,direction,categoryKey,transactionTime,createdAt,updatedAt,sourceChannel,confidence,reviewStatus,fingerprint) VALUES(4600,'EXPENSE','shopping',1,1,1,'WECHAT',1,'ACCEPTED','old')")
            old.execSQL("INSERT INTO deferred_orders(orderNumber,product,paymentMode,amountCents,categoryKey,status,placedAt,billId,settlementBillId,settledAt,fingerprint,evidence) VALUES('','旧忽略商品','IMMEDIATE',200,'shopping','CANCELLED',1,0,0,0,'old-ignore','旧原文')")
            old.version = 3
        }
        val upgraded = Room.databaseBuilder(context, BookkeepingDatabase::class.java, name).allowMainThreadQueries().addMigrations(BookkeepingDatabase.MIGRATION_3_4).build()
        try {
            assertEquals(4600L, upgraded.billDao().getAll().single().amountCents)
            assertTrue(upgraded.deferredOrderDao().getAll().isEmpty())
            assertEquals(1, upgraded.deferredOrderDao().isIgnored("old-ignore", now))
        } finally { upgraded.close(); context.deleteDatabase(name) }
    }
}
