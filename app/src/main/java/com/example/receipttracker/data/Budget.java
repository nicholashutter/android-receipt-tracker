package com.example.receipttracker.data;


import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
 * <p>Hierarchical budgets: a budget with {@code parentId == null} is a
 * top-level "parent" budget; a budget with {@code parentId != null} is a
 * sub-budget / leaf. Receipts can only be attached to a leaf sub-budget
 * (the user picks which sub-budget a receipt belongs to). The parent's
 * spent is the sum of its own spend plus the spend of every leaf
 * descendant; the parent's {@code maxAmount} is the total cap the
 * children roll up to.</p>
 *
 * <p>Exactly one parent budget is "active" at a time (isActive=1). Sub-
 * budgets are never directly active. The active parent is the one shown
 * on the main screen and is the one the editor's "Add to budget" picker
 * lists the children of.</p>
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
                @Index(value = "isActive"),
                // parentId is queried as "the children of this parent" for the
                // sub-budget list and the parent/child roll-up sums.
                @Index(value = "parentId")
        }
)
public final class Budget {

    @PrimaryKey(autoGenerate = true)
    public final long id;


    /** Display name, e.g. "Groceries August" or "Memphis". */
    @NonNull
    public final String name;


    /** The cap. Spent/max is shown on the main screen and detail screen. */
    public final double maxAmount;


    public final long createdAt;


    /** Exactly one parent budget should have isActive=1. Enforced in BudgetDao. */
    public final boolean isActive;


    /** Soft delete. Filtered out of normal queries. */
    public final boolean isDeleted;


    /**
     * Foreign key to the parent budget's {@link #id}. {@code null} means
     * this is a top-level (parent) budget; non-null means it's a
     * sub-budget / leaf under that parent.
     */
    @Nullable
    public final Long parentId;


    /** Convenience constructor for the "create new parent budget" flow. */
    @Ignore
    public Budget(@NonNull final String name, final double maxAmount) {
        this(0L, name, maxAmount, System.currentTimeMillis(), false, false, null);
    }


    /** Convenience constructor for the "create new sub-budget" flow. */
    @Ignore
    public Budget(final long parentId, @NonNull final String name, final double maxAmount) {
        this(0L, name, maxAmount, System.currentTimeMillis(), false, false, parentId);
    }


    public Budget(
            final long id,
            @NonNull final String name,
            final double maxAmount,
            final long createdAt,
            final boolean isActive,
            final boolean isDeleted,
            @Nullable final Long parentId) {
        this.id = id;
        this.name = name;
        this.maxAmount = maxAmount;
        this.createdAt = createdAt;
        this.isActive = isActive;
        this.isDeleted = isDeleted;
        this.parentId = parentId;
    }


    /**
     * True if this budget is a top-level parent (no parent of its own).
     * The active budget is always a parent; sub-budgets ({@link #parentId}
     * non-null) are leaves.
     */
    public boolean isParent() {
        return parentId == null;
    }


    public Budget withName(@NonNull final String newName) {
        if (newName.equals(this.name)) {
            return this;
        }
        return new Budget(id, newName, maxAmount, createdAt, isActive, isDeleted, parentId);
    }


    public Budget withMaxAmount(final double newMaxAmount) {
        if (newMaxAmount == this.maxAmount) {
            return this;
        }
        return new Budget(id, name, newMaxAmount, createdAt, isActive, isDeleted, parentId);
    }


    public Budget withActive(final boolean newIsActive) {
        if (newIsActive == this.isActive) {
            return this;
        }
        return new Budget(id, name, maxAmount, createdAt, newIsActive, isDeleted, parentId);
    }


    public Budget withDeleted(final boolean newIsDeleted) {
        if (newIsDeleted == this.isDeleted) {
            return this;
        }
        return new Budget(id, name, maxAmount, createdAt, isActive, newIsDeleted, parentId);
    }


    public Budget withParentId(@Nullable final Long newParentId) {
        if (newParentId == null ? parentId == null : newParentId.equals(parentId)) {
            return this;
        }
        return new Budget(id, name, maxAmount, createdAt, isActive, isDeleted, newParentId);
    }


    @NonNull
    @Override
    public String toString() {
        return "Budget{id=" + id + ", name='" + name + "', max=" + maxAmount
                + ", active=" + isActive + ", deleted=" + isDeleted
                + ", parentId=" + parentId + "}";
    }
}
