package com.example.receipttracker.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.example.receipttracker.R;
import com.example.receipttracker.data.AppDatabase;
import com.example.receipttracker.log.Logger;
import com.example.receipttracker.ui.debug.LogsActivity;
import com.example.receipttracker.ui.export.ExportActivity;
import com.example.receipttracker.ui.match.MatchActivity;
import com.example.receipttracker.ui.receipts.ReceiptListActivity;
import com.example.receipttracker.ui.scan.ScanReceiptActivity;
import com.example.receipttracker.ui.transactions.AddTransactionActivity;
import com.example.receipttracker.ui.transactions.TransactionListActivity;

public class MainActivity extends AppCompatActivity {

    private TextView tvReceiptCount;
    private TextView tvTxCount;
    private AppDatabase db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.i("Main", "onCreate");
        setContentView(R.layout.activity_main);

        db = AppDatabase.get(this);

        // Live count TextViews inside the status pills.
        tvReceiptCount = findViewById(R.id.tv_receipt_count);
        tvTxCount = findViewById(R.id.tv_tx_count);

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

        // Secondary action cards: browse receipts / log a new bank charge.
        findViewById(R.id.btn_view_receipts).setOnClickListener(v -> {
            Logger.i("Main", "btn_view_receipts clicked");
            startActivity(new Intent(this, ReceiptListActivity.class));
        });
        findViewById(R.id.btn_add_tx).setOnClickListener(v -> {
            Logger.i("Main", "btn_add_tx clicked");
            startActivity(new Intent(this, AddTransactionActivity.class));
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

        // LiveData: the counts re-render automatically on every DB change.
        db.receiptDao().getAllLive().observe(this, list -> {
            int n = list == null ? 0 : list.size();
            Logger.d("Main", "receipts LiveData -> " + n);
            tvReceiptCount.setText(Integer.toString(n));
        });
        db.bankTransactionDao().countLive().observe(this, count -> {
            int n = count == null ? 0 : count;
            Logger.d("Main", "transactions LiveData -> " + n);
            tvTxCount.setText(Integer.toString(n));
        });
    }
}
