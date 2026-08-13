package com.example.receipttracker.data;


import androidx.lifecycle.LiveData;

import androidx.room.Dao;

import androidx.room.Insert;

import androidx.room.Query;

import androidx.room.Update;


import java.util.List;


@Dao
public interface BudgetDao {

    @Insert
    long insert(Budget budget);


    @Update
    void update(Budget budget);


    @Query("SELECT * FROM budgets WHERE isDeleted = 0 ORDER BY createdAt DESC")
    LiveData<List<Budget>> getAllActiveLive();


    @Query("SELECT * FROM budgets WHERE isDeleted = 0 ORDER BY createdAt DESC")
    List<Budget> getAllActive();


    @Query("SELECT * FROM budgets WHERE id = :id LIMIT 1")
    Budget getById(long id);


    @Query("SELECT * FROM budgets WHERE id = :id LIMIT 1")
    LiveData<Budget> getByIdLive(long id);


    @Query("SELECT * FROM budgets WHERE isActive = 1 AND isDeleted = 0 LIMIT 1")
    Budget getActive();


    @Query("SELECT * FROM budgets WHERE isActive = 1 AND isDeleted = 0 LIMIT 1")
    LiveData<Budget> getActiveLive();


    /**
     * Atomically clears any other active budget and marks this one as active.
     * Wrapped in a single transaction so we never end up with two active rows.
     */
    @Query("UPDATE budgets SET isActive = (CASE WHEN id = :id THEN 1 ELSE 0 END)")
    void setActive(long id);


    @Query("UPDATE budgets SET isActive = 0")
    void clearAllActive();


    @Query("UPDATE budgets SET isActive = 0, isDeleted = 1 WHERE id = :id")
    void softDelete(long id);


    @Query("UPDATE budgets SET isDeleted = 0 WHERE id = :id")
    void restore(long id);


    // ---------- hierarchy queries ----------

    /**
     * All top-level parent budgets (parentId IS NULL), excluding soft-deleted.
     * Used by the budget-list screen and the editor's "Add to budget" picker.
     */
    @Query("SELECT * FROM budgets WHERE parentId IS NULL AND isDeleted = 0 "
            + "ORDER BY createdAt DESC")
    LiveData<List<Budget>> getAllParentsLive();


    @Query("SELECT * FROM budgets WHERE parentId IS NULL AND isDeleted = 0 "
            + "ORDER BY createdAt DESC")
    List<Budget> getAllParents();


    /** Sub-budgets (children) of a parent, excluding soft-deleted. */
    @Query("SELECT * FROM budgets WHERE parentId = :parentId AND isDeleted = 0 "
            + "ORDER BY createdAt ASC")
    LiveData<List<Budget>> getChildrenLive(long parentId);


    @Query("SELECT * FROM budgets WHERE parentId = :parentId AND isDeleted = 0 "
            + "ORDER BY createdAt ASC")
    List<Budget> getChildren(long parentId);


    // ---------- spent sums ----------

    /**
     * Sum of receipt amounts linked to a budget, excluding soft-deleted
     * receipts. Returns 0.0 if the budget has no linked receipts.
     */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM receipts "
            + "WHERE budgetId = :budgetId AND deletedAt IS NULL")
    LiveData<Double> sumSpentLive(long budgetId);


    @Query("SELECT COALESCE(SUM(amount), 0) FROM receipts "
            + "WHERE budgetId = :budgetId AND deletedAt IS NULL")
    double sumSpent(long budgetId);


    /**
     * Sum of receipt amounts across the parent itself plus every leaf
     * descendant. This is what shows on the parent's "Spent" headline.
     * The hierarchy is at most one level deep — a parent has sub-budgets,
     * sub-budgets do not have their own sub-budgets.
     */
    @Query("SELECT COALESCE(SUM(amount), 0) FROM receipts "
            + "WHERE (budgetId = :parentId OR budgetId IN ("
            + "  SELECT id FROM budgets WHERE parentId = :parentId AND isDeleted = 0"
            + ")) AND deletedAt IS NULL")
    LiveData<Double> sumSpentWithChildrenLive(long parentId);


    @Query("SELECT COALESCE(SUM(amount), 0) FROM receipts "
            + "WHERE (budgetId = :parentId OR budgetId IN ("
            + "  SELECT id FROM budgets WHERE parentId = :parentId AND isDeleted = 0"
            + ")) AND deletedAt IS NULL")
    double sumSpentWithChildren(long parentId);
}
