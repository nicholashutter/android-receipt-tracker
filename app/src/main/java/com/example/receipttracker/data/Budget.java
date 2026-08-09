package com.example.receipttracker.data;


import androidx.annotation.Nullable;

import androidx.room.Entity;

import androidx.room.Index;

import androidx.room.PrimaryKey;


/**
 * A user-defined budget. Receipts can be linked to at most one budget via
 * {@code Receipt.budgetId}. The "spent" amount is computed live from
 * linked receipts (filtering soft-deleted ones) - we don't store it
 * denormalized because that drifts on every edit/delete.
 *
 * Exactly one budget is "active" at a time (isActive=1). When a receipt's
 * total is verified, it auto-links to the active budget.
 */
@Entity(
        tableName = "budgets",
        indices = {
                // isActive is queried as "the one active row" so an index makes
                // that lookup O(1) instead of a full scan.
                @Index(value = "isActive")
        }
)
public class Budget {

    @PrimaryKey(autoGenerate = true)
    public long id;


    /** Display name, e.g. "Groceries August". */
    public String name;


    /** The cap. Spent/max is shown on the main screen and detail screen. */
    public double maxAmount;


    public long createdAt;


    /** Exactly one budget should have isActive=1. Enforced in BudgetDao. */
    public boolean isActive;


    /** Soft delete. Filtered out of normal queries. */
    public boolean isDeleted;


    public Budget() {}


    public Budget(String name, double maxAmount) {
        this.name = name;

        this.maxAmount = maxAmount;

        this.createdAt = System.currentTimeMillis();

        this.isActive = false;

        this.isDeleted = false;
    }


    @Nullable
    @Override
    public String toString() {
        return "Budget{id=" + id + ", name='" + name + "', max=" + maxAmount
                + ", active=" + isActive + ", deleted=" + isDeleted + "}";
    }
}
