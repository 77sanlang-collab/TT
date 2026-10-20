package com.github.bookkeeping.data;
import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
@Entity(tableName = "ignored_fingerprints")
public class IgnoredFingerprint {
    @PrimaryKey @NonNull public String fingerprint = "";
    public long expiresAt;
}
