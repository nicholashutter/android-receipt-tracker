package com.example.receipttracker.ui;


import android.content.Intent;

import android.os.Bundle;

import android.view.View;

import android.widget.ProgressBar;

import android.widget.TextView;


import androidx.appcompat.app.AppCompatActivity;


import com.example.receipttracker.R;

import com.example.receipttracker.data.AppDatabase;

import com.example.receipttracker.data.Budget;

import com.example.receipttracker.data.BudgetDao;

import com.example.receipttracker.data.ReceiptDao;

import com.example.receipttracker.log.Logger;

import com.example.receipttracker.ui.budget.BudgetDetailActivity;

import com.example.receipttracker.ui.budget.BudgetListActivity;

import com.example.receipttracker.ui.debug.LogsActivity;

import com.example.receipttracker.ui.export.ExportActivity;

import com.example.receipttracker.ui.match.MatchActivity;

import com.example.receipttracker.ui.receipts.CreateReceiptActivity;
import com.example.receipttracker.ui.receipts.ReceiptListActivity;

import com.example.receipttracker.ui.scan.ScanReceiptActivity;

import com.example.receipttracker.ui.transactions.AddTransactionActivity;

import com.example.receipttracker.ui.transactions.TransactionListActivity;

import com.example.receipttracker.util.AppExecutors;

import com.example.receipttracker.util.MoneyUtils;


/**
 * The home screen. Three primary entry points (scan, receipts list, transactions
 * list), an active-budget card if one is set, and the secondary actions for
 * budgets, match, export, and the debug log viewer.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "Main";

    private static final int ACTIVE_BUDGET_PCT_MAX = 100;

    private TextView receiptCountView;
    private TextView transactionCountView;
    private TextView activeBudgetNameView;
    private TextView activeBudgetAmountView;
    private ProgressBar activeBudgetProgress;
    private View activeBudgetCard;

    private AppDatabase database;
    private BudgetDao budgetDao;
    private ReceiptDao receiptDao;
    private final AppExecutors executors = AppExecutors.get();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.i(TAG, "onCreate");
        setContentView(R.layout.activity_main);

        database = AppDatabase.get(this);
        budgetDao = database.budgetDao();
        receiptDao = database.receiptDao();

        receiptCountView = findViewById(R.id.tv_receipt_count);
        transactionCountView = findViewById(R.id.tv_tx_count);
        activeBudgetNameView = findViewById(R.id.tv_active_budget_name);
        activeBudgetAmountView = findViewById(R.id.tv_active_budget_amount);
        activeBudgetProgress = findViewById(R.id.pb_active_budget);
        activeBudgetCard = findViewById(R.id.card_active_budget);

        wirePrimaryAction(R.id.btn_scan, ScanReceiptActivity.class);
        wirePrimaryAction(R.id.btn_create_receipt, CreateReceiptActivity.class);
        wireReceiptsListPills();
        wireSecondaryCards();
        wireActiveBudgetCard();
        wireMatchAndExport();
        wireDebugLink();
        wireLiveData();
    }


    private void wirePrimaryAction(int buttonId, Class<?> targetActivity) {
        findViewById(buttonId).setOnClickListener(clickedView -> {
            final String buttonName = getResources().getResourceEntryName(buttonId);
            Logger.i(TAG, buttonName + " clicked");
            startActivity(new Intent(this, targetActivity));
        });
    }


    private void wireReceiptsListPills() {
        findViewById(R.id.pill_receipts).setOnClickListener(clickedView -> {
            Logger.i(TAG, "pill_receipts clicked");
            startActivity(new Intent(this, ReceiptListActivity.class));
        });
        findViewById(R.id.pill_transactions).setOnClickListener(clickedView -> {
            Logger.i(TAG, "pill_transactions clicked");
            startActivity(new Intent(this, TransactionListActivity.class));
        });
    }


    private void wireSecondaryCards() {
        findViewById(R.id.btn_view_receipts).setOnClickListener(clickedView -> {
            Logger.i(TAG, "btn_view_receipts clicked");
            startActivity(new Intent(this, ReceiptListActivity.class));
        });
        findViewById(R.id.btn_add_tx).setOnClickListener(clickedView -> {
            Logger.i(TAG, "btn_add_tx clicked");
            startActivity(new Intent(this, AddTransactionActivity.class));
        });
        findViewById(R.id.btn_budgets).setOnClickListener(clickedView -> {
            Logger.i(TAG, "btn_budgets clicked");
            startActivity(new Intent(this, BudgetListActivity.class));
        });
    }


    private void wireActiveBudgetCard() {
        // Active budget card: tap to open detail. The "getActive" DB call
        // is synchronous, so we hop to diskIO first.
        activeBudgetCard.setOnClickListener(clickedView -> executors.diskIO().execute(() -> {
            final Budget active = budgetDao.getActive();
            executors.mainThread().execute(() -> {
                if (active == null) {
                    startActivity(new Intent(this, BudgetListActivity.class));
                } else {
                    final Intent detailIntent = new Intent(this, BudgetDetailActivity.class);
                    detailIntent.putExtra(BudgetDetailActivity.EXTRA_BUDGET_ID, active.id);
                    startActivity(detailIntent);
                }
            });
        }));
    }


    private void wireMatchAndExport() {
        findViewById(R.id.btn_match).setOnClickListener(clickedView -> {
            Logger.i(TAG, "btn_match clicked");
            startActivity(new Intent(this, MatchActivity.class));
        });
        findViewById(R.id.btn_export).setOnClickListener(clickedView -> {
            Logger.i(TAG, "btn_export clicked");
            startActivity(new Intent(this, ExportActivity.class));
        });
    }


    private void wireDebugLink() {
        findViewById(R.id.btn_logs).setOnClickListener(clickedView -> {
            Logger.i(TAG, "btn_logs clicked");
            startActivity(new Intent(this, LogsActivity.class));
        });
    }


    private void wireLiveData() {
        receiptDao.countActiveLive().observe(this, rawCount -> {
            final int count;
            if (rawCount == null) {
                count = 0;
            } else {
                count = rawCount;
            }
            Logger.d(TAG, "receipts countActiveLive -> " + count);
            receiptCountView.setText(Integer.toString(count));
        });

        database.bankTransactionDao().countLive().observe(this, rawCount -> {
            final int count;
            if (rawCount == null) {
                count = 0;
            } else {
                count = rawCount;
            }
            Logger.d(TAG, "transactions countLive -> " + count);
            transactionCountView.setText(Integer.toString(count));
        });

        // Active budget: observe the active row AND its spent amount.
        budgetDao.getActiveLive().observe(this, this::renderActiveBudget);
        // sumSpentLive is parameterized, so we observe it in a separate
        // observe call when the active budget is non-null. We do that
        // inside renderActiveBudget.
    }


    private void renderActiveBudget(Budget activeBudget) {
        if (activeBudget == null) {
            activeBudgetNameView.setText("No budget set up");
            activeBudgetAmountView.setText("$0 / $0");
            activeBudgetProgress.setProgress(0);
            return;
        }
        activeBudgetNameView.setText(activeBudget.name);

        // Live observed query for the spent amount.
        budgetDao.sumSpentLive(activeBudget.id).observe(this, rawSpent -> {
            final double spent;
            if (rawSpent == null) {
                spent = 0.0;
            } else {
                spent = rawSpent;
            }
            final String amountLine = String.format("%s / %s",
                    MoneyUtils.format(spent), MoneyUtils.format(activeBudget.maxAmount));
            activeBudgetAmountView.setText(amountLine);

            final int progressPct;
            if (activeBudget.maxAmount > 0.0) {
                final double rawPct = spent * 100.0 / activeBudget.maxAmount;
                final double clampedPct = Math.min(ACTIVE_BUDGET_PCT_MAX, Math.round(rawPct));
                progressPct = (int) clampedPct;
            } else {
                progressPct = 0;
            }
            activeBudgetProgress.setProgress(progressPct);
        });
    }
}
