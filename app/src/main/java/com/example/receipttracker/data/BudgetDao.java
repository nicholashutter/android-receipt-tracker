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
}
