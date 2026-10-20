package com.github.bookkeeping.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "deferred_orders", indices = {@Index(value = {"fingerprint"}, unique = true)})
public class DeferredOrder {
    @PrimaryKey(autoGenerate = true) public long id;
    @NonNull public String orderNumber = "";
    @NonNull public String product = "";
    @NonNull public String paymentMode = "DEFERRED";
    public long amountCents;
    @NonNull public String categoryKey = "shopping";
    @NonNull public String status = "REVIEW";
    public long placedAt;
    public long billId;
    public long settlementBillId;
    public long settledAt;
    @NonNull public String fingerprint = "";
    @NonNull public String evidence = "";
}
