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
import com.example.receipttracker.ui.receipts.ReceiptListActivity;
import com.example.receipttracker.ui.scan.ScanReceiptActivity;
import com.example.receipttracker.ui.transactions.AddTransactionActivity;
import com.example.receipttracker.ui.transactions.TransactionListActivity;
import com.example.receipttracker.util.AppExecutors;
import com.example.receipttracker.util.MoneyUtils;

public class MainActivity extends AppCompatActivity {

    private TextView tvReceiptCount, tvTxCount;
    private TextView tvActiveBudgetName, tvActiveBudgetAmount;
    private ProgressBar pbActiveBudget;
    private View cardActiveBudget;
    private AppDatabase db;
    private BudgetDao budgetDao;
    private ReceiptDao receiptDao;
    private final AppExecutors exec = AppExecutors.get();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.i("Main", "onCreate");
        setContentView(R.layout.activity_main);

        db = AppDatabase.get(this);
        budgetDao = db.budgetDao();
        receiptDao = db.receiptDao();

        tvReceiptCount = findViewById(R.id.tv_receipt_count);
        tvTxCount = findViewById(R.id.tv_tx_count);
        tvActiveBudgetName = findViewById(R.id.tv_active_budget_name);
        tvActiveBudgetAmount = findViewById(R.id.tv_active_budget_amount);
        pbActiveBudget = findViewById(R.id.pb_active_budget);
        cardActiveBudget = findViewById(R.id.card_active_budget);

        // Primary action: scan a receipt.
        findViewById(R.id.btn_scan).setOnClickListener(v -> {
            Logger.i("Main", "btn_scan clicked");
            startActivity(new Intent(this, ScanReceiptActivity.class));
        });

        // Status pills: tap the receipts pill -> list, tap the tx pill -> list.
        findViewById(R.id.pill_receipts).setOnClickListener(v -> {
            Logger.i("Main", "pill_receipts clicked");
            startActivity(new Intent(this, ReceiptListActivity.class));
        });
        findViewById(R.id.pill_transactions).setOnClickListener(v -> {
            Logger.i("Main", "pill_transactions clicked");
            startActivity(new Intent(this, TransactionListActivity.class));
        });

        // Secondary action cards: browse receipts / log a new bank charge / open budgets.
        findViewById(R.id.btn_view_receipts).setOnClickListener(v -> {
            Logger.i("Main", "btn_view_receipts clicked");
            startActivity(new Intent(this, ReceiptListActivity.class));
        });
        findViewById(R.id.btn_add_tx).setOnClickListener(v -> {
            Logger.i("Main", "btn_add_tx clicked");
            startActivity(new Intent(this, AddTransactionActivity.class));
        });
        findViewById(R.id.btn_budgets).setOnClickListener(v -> {
            Logger.i("Main", "btn_budgets clicked");
            startActivity(new Intent(this, BudgetListActivity.class));
        });

        // Active budget card: tap to open detail. The "getActive" DB call
        // is synchronous, so we hop to diskIO first.
        cardActiveBudget.setOnClickListener(v -> {
            exec.diskIO().execute(() -> {
                Budget active = budgetDao.getActive();
                runOnUiThread(() -> {
                    if (active == null) {
                        startActivity(new Intent(this, BudgetListActivity.class));
                    } else {
                        Intent i = new Intent(this, BudgetDetailActivity.class);
                        i.putExtra(BudgetDetailActivity.EXTRA_BUDGET_ID, active.id);
                        startActivity(i);
                    }
                });
            });
        });

        // Tertiary: match + export.
        findViewById(R.id.btn_match).setOnClickListener(v -> {
            Logger.i("Main", "btn_match clicked");
            startActivity(new Intent(this, MatchActivity.class));
        });
        findViewById(R.id.btn_export).setOnClickListener(v -> {
            Logger.i("Main", "btn_export clicked");
            startActivity(new Intent(this, ExportActivity.class));
        });

        // Debug link.
        findViewById(R.id.btn_logs).setOnClickListener(v -> {
            Logger.i("Main", "btn_logs clicked");
            startActivity(new Intent(this, LogsActivity.class));
        });

        // LiveData wiring.
        receiptDao.countActiveLive().observe(this, n -> {
            int count;
            if (n == null) {
                count = 0;
            } else {
                count = n;
            }
            Logger.d("Main", "receipts countActiveLive -> " + count);
            tvReceiptCount.setText(Integer.toString(count));
        });
        db.bankTransactionDao().countLive().observe(this, count -> {
            int n;
            if (count == null) {
                n = 0;
            } else {
                n = count;
            }
            Logger.d("Main", "transactions countLive -> " + n);
            tvTxCount.setText(Integer.toString(n));
        });

        // Active budget: observe the active row AND its spent amount.
        budgetDao.getActiveLive().observe(this, this::renderActiveBudget);
        // sumSpentLive is parameterized, so we observe it in a separate observe call
        // when the active budget is non-null. We do that inside renderActiveBudget.
    }

    private void renderActiveBudget(Budget active) {
        if (active == null) {
            tvActiveBudgetName.setText("No budget set up");
            tvActiveBudgetAmount.setText("$0 / $0");
            pbActiveBudget.setProgress(0);
            return;
        }
        tvActiveBudgetName.setText(active.name);
        // Live observed query for the spent amount.
        budgetDao.sumSpentLive(active.id).observe(this, spent -> {
            double s;
            if (spent == null) {
                s = 0;
            } else {
                s = spent;
            }
            tvActiveBudgetAmount.setText(String.format("%s / %s",
                    MoneyUtils.format(s), MoneyUtils.format(active.maxAmount)));
            int pct;
            if (active.maxAmount > 0) {
                pct = (int) Math.min(100, Math.round(s * 100.0 / active.maxAmount));
            } else {
                pct = 0;
            }
            pbActiveBudget.setProgress(pct);
        });
    }
}
