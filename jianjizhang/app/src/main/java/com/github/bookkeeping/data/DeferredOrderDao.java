package com.github.bookkeeping.data;

import androidx.room.*;
import java.util.List;
import kotlinx.coroutines.flow.Flow;

@Dao
public interface DeferredOrderDao {
    @Query("SELECT * FROM deferred_orders ORDER BY placedAt DESC, id DESC") Flow<List<DeferredOrder>> observeAll();
    @Query("SELECT * FROM deferred_orders ORDER BY placedAt DESC, id DESC") List<DeferredOrder> getAll();
    @Query("SELECT * FROM deferred_orders WHERE id = :id") DeferredOrder getById(long id);
    @Query("SELECT * FROM deferred_orders WHERE billId = :id OR settlementBillId = :id LIMIT 1") DeferredOrder forBill(long id);
    @Query("SELECT * FROM deferred_orders WHERE fingerprint = :fingerprint LIMIT 1") DeferredOrder byFingerprint(String fingerprint);
    @Insert(onConflict = OnConflictStrategy.IGNORE) long insert(DeferredOrder order);
    @Update int update(DeferredOrder order);
}
