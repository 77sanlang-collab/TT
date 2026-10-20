package com.github.bookkeeping.data;

import androidx.room.*;
import java.util.List;
import kotlinx.coroutines.flow.Flow;

@Dao
public interface DeferredOrderDao {
    @Query("SELECT * FROM deferred_orders WHERE status != 'CANCELLED' ORDER BY placedAt DESC, id DESC") Flow<List<DeferredOrder>> observeAll();
    @Query("SELECT * FROM deferred_orders ORDER BY placedAt DESC, id DESC") List<DeferredOrder> getAll();
    @Query("SELECT * FROM deferred_orders WHERE id = :id") DeferredOrder getById(long id);
    @Query("SELECT * FROM deferred_orders WHERE billId = :id OR settlementBillId = :id LIMIT 1") DeferredOrder forBill(long id);
    @Query("SELECT * FROM deferred_orders WHERE fingerprint = :fingerprint LIMIT 1") DeferredOrder byFingerprint(String fingerprint);
    @Insert(onConflict = OnConflictStrategy.IGNORE) long insert(DeferredOrder order);
    @Update int update(DeferredOrder order);
    @Query("DELETE FROM deferred_orders WHERE id = :id") int delete(long id);
    @Query("SELECT COUNT(*) FROM ignored_fingerprints WHERE fingerprint = :key AND expiresAt > :now") int isIgnored(String key, long now);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void ignore(IgnoredFingerprint fingerprint);
    @Query("DELETE FROM ignored_fingerprints WHERE expiresAt <= :now") void purgeIgnored(long now);
}
