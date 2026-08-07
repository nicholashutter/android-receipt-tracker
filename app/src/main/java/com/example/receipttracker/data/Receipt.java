package com.example.receipttracker.data;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A scanned receipt. All fields are nullable except id, amount and date because the OCR pass
 * is best-effort - we keep what we got and let the user fix the rest on the edit screen.
 */
@Entity(tableName = "receipts")
public class Receipt {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @Nullable
    public String merchant;

    /** Epoch millis at midnight local time of the receipt date. */
    public long dateMillis;

    /** Receipt total in major units (e.g. dollars). Always positive. */
    public double amount;

    /** Absolute path to the JPEG of the original receipt, or null if not saved. */
    @Nullable
    public String photoPath;

    /** Raw OCR text so the user can sanity-check what was read. */
    @Nullable
    public String rawText;

    @Nullable
    public String notes;

    public long createdAt;

    /**
     * Shared match-group id. When non-null, points to the same UUID on the matched
     * BankTransaction row. Null means unmatched.
     */
    @Nullable
    public String matchGroupId;
}
