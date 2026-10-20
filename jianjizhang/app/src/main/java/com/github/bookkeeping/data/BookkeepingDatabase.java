package com.github.bookkeeping.data;

import androidx.room.Database;
import androidx.room.migration.Migration;
import androidx.room.RoomDatabase;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {BillEntity.class, CategoryEntity.class, SourceSettingEntity.class, DeferredOrder.class},
        version = 3,
        exportSchema = true
)
public abstract class BookkeepingDatabase extends RoomDatabase {
    public abstract DeferredOrderDao deferredOrderDao();

    public abstract BillDao billDao();

    public abstract CategoryDao categoryDao();

    public abstract SourceSettingDao sourceSettingDao();

    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS deferred_orders (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, orderNumber TEXT NOT NULL, product TEXT NOT NULL, paymentMode TEXT NOT NULL, amountCents INTEGER NOT NULL, categoryKey TEXT NOT NULL, status TEXT NOT NULL, placedAt INTEGER NOT NULL, billId INTEGER NOT NULL, settlementBillId INTEGER NOT NULL, settledAt INTEGER NOT NULL, fingerprint TEXT NOT NULL, evidence TEXT NOT NULL)");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_deferred_orders_fingerprint ON deferred_orders(fingerprint)");
        }
    };

    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("DROP INDEX IF EXISTS index_categories_categoryKey");
            database.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_categoryKey_direction ON categories(categoryKey, direction)");
        }
    };
}
