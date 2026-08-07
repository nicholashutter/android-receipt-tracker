package com.example.receipttracker.data;

import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * A scanned receipt. All fields are nullable except id, amount and date because the OCR pass
 * is best-effort - we keep what we got and let the user fix the rest on the edit screen.
 */
@Entity(
        tableName = "receipts",
        indices = {
                // budgetId is queried for "all receipts in budget X" and the
                // sum-of-amounts query; index avoids full scans.
                @Index(value = "budgetId"),
                // deletedAt is filtered in most queries (deletedAt IS NULL).
                @Index(value = "deletedAt")
        }
)
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

    /**
     * Optional FK into {@link Budget#id}. When a receipt's total is verified
     * with an active budget in place, this gets set automatically. Null
     * means the receipt is not in any budget.
     */
    @Nullable
    public Long budgetId;

    /**
     * Soft-delete tombstone. Null = active. Non-null = the user "cleared" this
     * receipt (it stays in the DB but is hidden from normal views and excluded
     * from budget sums). Use {@code Show deleted} in the receipts list to
     * recover.
     */
    @Nullable
    public Long deletedAt;
}
