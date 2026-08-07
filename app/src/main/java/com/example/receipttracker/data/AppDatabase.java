package com.example.receipttracker.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(
        entities = {Receipt.class, BankTransaction.class, Budget.class},
        version = 2,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {

    private static final String DB_NAME = "receipt_tracker.db";
    private static volatile AppDatabase INSTANCE;

    public abstract ReceiptDao receiptDao();
    public abstract BankTransactionDao bankTransactionDao();
    public abstract BudgetDao budgetDao();

    /**
     * v1 -> v2: add the {@code budgets} table, plus two nullable columns
     * on {@code receipts} ({@code budgetId}, {@code deletedAt}) and the
     * matching indices. All new bits are nullable / have defaults, so the
     * migration is purely additive and safe on existing data.
     */
    static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `budgets` ("
                    + "`id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, "
                    + "`name` TEXT, "
                    + "`maxAmount` REAL NOT NULL, "
                    + "`createdAt` INTEGER NOT NULL, "
                    + "`isActive` INTEGER NOT NULL, "
                    + "`isDeleted` INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_budgets_isActive` "
                    + "ON `budgets` (`isActive`)");
            db.execSQL("ALTER TABLE `receipts` ADD COLUMN `budgetId` INTEGER");
            db.execSQL("ALTER TABLE `receipts` ADD COLUMN `deletedAt` INTEGER");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipts_budgetId` "
                    + "ON `receipts` (`budgetId`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_receipts_deletedAt` "
                    + "ON `receipts` (`deletedAt`)");
        }
    };

    public static AppDatabase get(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    DB_NAME)
                            .addMigrations(MIGRATION_1_2)
                            // Last-resort: if a future version can't migrate,
                            // wipe rather than crash. Pre-alpha, so OK.
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return INSTANCE;
    }
}
