package com.example.receipttracker.data;


import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;


/**
 * A user-defined budget. Receipts can be linked to at most one budget via
 * {@code Receipt.budgetId}. The "spent" amount is computed live from
 * linked receipts (filtering soft-deleted ones) - we don't store it
 * denormalized because that drifts on every edit/delete.
 *
 * <p>Exactly one budget is "active" at a time (isActive=1). When a receipt's
 * total is verified, it auto-links to the active budget.</p>
 *
 * <p>Immutable: every mutation goes through a {@code with*} method that
 * returns a new instance with the requested field replaced. The {@code id}
 * field is the only one we do not provide a {@code with*} for; it is the
 * primary key and never changes once assigned by Room.</p>
 */
@Entity(
        tableName = "budgets",
        indices = {
                // isActive is queried as "the one active row" so an index makes
                // that lookup O(1) instead of a full scan.
                @Index(value = "isActive")
        }
)
public final class Budget {

    @PrimaryKey(autoGenerate = true)
    public final long id;


    /** Display name, e.g. "Groceries August". */
    @NonNull
    public final String name;


    /** The cap. Spent/max is shown on the main screen and detail screen. */
    public final double maxAmount;


    public final long createdAt;


    /** Exactly one budget should have isActive=1. Enforced in BudgetDao. */
    public final boolean isActive;


    /** Soft delete. Filtered out of normal queries. */
    public final boolean isDeleted;


    /** Convenience constructor for the "create new budget" flow. */
    @Ignore
    public Budget(@NonNull final String name, final double maxAmount) {
        this(0L, name, maxAmount, System.currentTimeMillis(), false, false);
    }


    public Budget(
            final long id,
            @NonNull final String name,
            final double maxAmount,
            final long createdAt,
            final boolean isActive,
            final boolean isDeleted) {
        this.id = id;
        this.name = name;
        this.maxAmount = maxAmount;
        this.createdAt = createdAt;
        this.isActive = isActive;
        this.isDeleted = isDeleted;
    }


    public Budget withName(@NonNull final String newName) {
        if (newName.equals(this.name)) {
            return this;
        }
        return new Budget(id, newName, maxAmount, createdAt, isActive, isDeleted);
    }


    public Budget withMaxAmount(final double newMaxAmount) {
        if (newMaxAmount == this.maxAmount) {
            return this;
        }
        return new Budget(id, name, newMaxAmount, createdAt, isActive, isDeleted);
    }


    public Budget withActive(final boolean newIsActive) {
        if (newIsActive == this.isActive) {
            return this;
        }
        return new Budget(id, name, maxAmount, createdAt, newIsActive, isDeleted);
    }


    public Budget withDeleted(final boolean newIsDeleted) {
        if (newIsDeleted == this.isDeleted) {
            return this;
        }
        return new Budget(id, name, maxAmount, createdAt, isActive, newIsDeleted);
    }


    @NonNull
    @Override
    public String toString() {
        return "Budget{id=" + id + ", name='" + name + "', max=" + maxAmount
                + ", active=" + isActive + ", deleted=" + isDeleted + "}";
    }
}
