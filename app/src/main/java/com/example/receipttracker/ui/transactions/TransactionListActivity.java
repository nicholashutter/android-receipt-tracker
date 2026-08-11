package com.example.receipttracker.ui.transactions;


import android.content.Intent;

import android.os.Bundle;

import android.view.LayoutInflater;

import android.view.View;

import android.view.ViewGroup;

import android.widget.TextView;


import androidx.annotation.NonNull;

import androidx.appcompat.app.AppCompatActivity;

import androidx.recyclerview.widget.LinearLayoutManager;

import androidx.recyclerview.widget.RecyclerView;


import com.example.receipttracker.R;

import com.example.receipttracker.data.AppDatabase;

import com.example.receipttracker.data.BankTransaction;

import com.example.receipttracker.util.MoneyUtils;


import java.util.Collections;

import java.util.List;


/**
 * Read-only list of every bank transaction the user has entered. Tapping a
 * row opens the {@link AddTransactionActivity} in "edit" mode.
 */
public class TransactionListActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private View emptyView;
    private TransactionAdapter adapter;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transaction_list);

        recyclerView = findViewById(R.id.rv);
        emptyView = findViewById(R.id.tv_empty);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new TransactionAdapter();
        recyclerView.setAdapter(adapter);

        AppDatabase.get(this).bankTransactionDao().getAllLive().observe(this, this::render);
    }


    private void render(List<BankTransaction> transactions) {
        adapter.set(transactions);
        final boolean isEmpty = transactions == null || transactions.isEmpty();
        if (isEmpty) {
            emptyView.setVisibility(View.VISIBLE);
        } else {
            emptyView.setVisibility(View.GONE);
        }
        if (isEmpty) {
            recyclerView.setVisibility(View.GONE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
        }
    }


    /** RecyclerView adapter for the transaction list. */
    class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.ViewHolder> {

        // MUTABLE: re-set in set().
        private List<BankTransaction> data = Collections.emptyList();


        void set(List<BankTransaction> newData) {
            if (newData == null) {
                this.data = Collections.emptyList();
            } else {
                this.data = newData;
            }
            notifyDataSetChanged();
        }


        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View inflatedView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_transaction, parent, false);
            return new ViewHolder(inflatedView);
        }


        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            final BankTransaction transaction = data.get(position);
            holder.description.setText(transaction.description);

            final String accountSuffix;
            if (transaction.account == null || transaction.account.isEmpty()) {
                accountSuffix = "";
            } else {
                accountSuffix = " - " + transaction.account;
            }
            final String dateWithAccount = MoneyUtils.formatDate(transaction.dateMillis) + accountSuffix;
            holder.dateAccount.setText(dateWithAccount);

            holder.amount.setText(MoneyUtils.format(transaction.amount));

            if (transaction.matchGroupId != null) {
                holder.status.setText(R.string.receipt_match_status_matched);
                holder.status.setTextColor(getColor(R.color.ok));
            } else {
                holder.status.setText(R.string.receipt_match_status_unmatched);
                holder.status.setTextColor(getColor(R.color.warn));
            }

            holder.itemView.setOnClickListener(clickedView -> {
                final Intent editIntent = new Intent(TransactionListActivity.this, AddTransactionActivity.class);
                editIntent.putExtra(AddTransactionActivity.EXTRA_TRANSACTION_ID, transaction.id);
                startActivity(editIntent);
            });
        }


        @Override
        public int getItemCount() {
            return data.size();
        }


        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView description;
            final TextView dateAccount;
            final TextView status;
            final TextView amount;

            ViewHolder(View itemView) {
                super(itemView);
                description = itemView.findViewById(R.id.tv_description);
                dateAccount = itemView.findViewById(R.id.tv_date_account);
                status = itemView.findViewById(R.id.tv_status);
                amount = itemView.findViewById(R.id.tv_amount);
            }
        }
    }
}
