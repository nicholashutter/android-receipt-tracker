package com.example.receipttracker.ui.match;


import android.os.Bundle;

import android.view.LayoutInflater;

import android.view.View;

import android.view.ViewGroup;

import android.widget.TextView;

import android.widget.Toast;


import androidx.annotation.NonNull;

import androidx.annotation.Nullable;

import androidx.appcompat.app.AppCompatActivity;

import androidx.recyclerview.widget.LinearLayoutManager;

import androidx.recyclerview.widget.RecyclerView;


import com.example.receipttracker.R;

import com.example.receipttracker.data.AppDatabase;

import com.example.receipttracker.data.BankTransaction;

import com.example.receipttracker.data.Receipt;

import com.example.receipttracker.log.Logger;

import com.example.receipttracker.match.MatchEngine;

import com.example.receipttracker.util.AppExecutors;

import com.example.receipttracker.util.MoneyUtils;

import com.google.android.material.button.MaterialButton;


import java.util.ArrayList;

import java.util.HashMap;

import java.util.List;

import java.util.Map;

import java.util.UUID;


/**
 * Three-section match screen: suggested pairings, unmatched bank
 * transactions, and already-confirmed pairings. Tapping a suggestion
 * locks in the match via {@link #confirmMatch}; the "Unlink" button on
 * a confirmed row reverses it.
 */
public class MatchActivity extends AppCompatActivity {

    private static final String TAG = "Match";
    private static final String PLACEHOLDER_NO_MERCHANT = "(no merchant)";
    private static final String SECTION_TITLE_FMT_PREFIX = "";

    private static final int ROW_TYPE_HEADER = 0;
    private static final int ROW_TYPE_SUGGEST = 1;
    private static final int ROW_TYPE_MATCHED = 2;
    private static final int ROW_TYPE_UNMATCHED_TX = 3;


    private RecyclerView recyclerView;
    private MatchAdapter adapter;
    private AppDatabase database;
    private final AppExecutors executors = AppExecutors.get();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.section("MATCH");
        Logger.i(TAG, "onCreate");
        setContentView(R.layout.activity_match);

        recyclerView = findViewById(R.id.rv);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MatchAdapter();
        recyclerView.setAdapter(adapter);
        database = AppDatabase.get(this);
    }


    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }


    private void reload() {
        executors.diskIO().execute(() -> {
            final List<Receipt> unmatchedReceipts = database.receiptDao().getUnmatched();
            final List<Receipt> matchedReceipts = database.receiptDao().getMatched();
            final List<BankTransaction> unmatchedTransactions = database.bankTransactionDao().getUnmatched();
            final List<BankTransaction> matchedTransactions = database.bankTransactionDao().getMatched();
            Logger.i(TAG, "reload: unmatchedReceipts=" + unmatchedReceipts.size()
                    + " matchedReceipts=" + matchedReceipts.size()
                    + " unmatchedTx=" + unmatchedTransactions.size()
                    + " matchedTx=" + matchedTransactions.size());

            final List<MatchEngine.Suggestion> suggestions =
                    MatchEngine.suggest(unmatchedReceipts, unmatchedTransactions);
            Logger.i(TAG, "Engine produced " + suggestions.size() + " suggestions ("
                    + suggestions.stream().filter(s -> s.best != null).count() + " with a candidate)");

            final List<Object> rows = buildRows(unmatchedReceipts, matchedReceipts,
                    unmatchedTransactions, matchedTransactions, suggestions);

            executors.mainThread().execute(() -> adapter.setRows(rows));
        });
    }


    private List<Object> buildRows(List<Receipt> unmatchedReceipts, List<Receipt> matchedReceipts,
                                   List<BankTransaction> unmatchedTransactions,
                                   List<BankTransaction> matchedTransactions,
                                   List<MatchEngine.Suggestion> suggestions) {
        final Map<String, Receipt> receiptsByGroup = new HashMap<>();
        for (final Receipt receipt : matchedReceipts) {
            if (receipt.matchGroupId != null) {
                receiptsByGroup.put(receipt.matchGroupId, receipt);
            }
        }
        final Map<String, BankTransaction> transactionsByGroup = new HashMap<>();
        for (final BankTransaction transaction : matchedTransactions) {
            if (transaction.matchGroupId != null) {
                transactionsByGroup.put(transaction.matchGroupId, transaction);
            }
        }

        final List<Object> rows = new ArrayList<>();
        rows.add(new Header(getString(R.string.match_section_unmatched_receipts, unmatchedReceipts.size())));
        appendUnmatchedReceiptRows(rows, suggestions);
        rows.add(new Header(getString(R.string.match_section_unmatched_tx, unmatchedTransactions.size())));
        appendUnmatchedTransactionRows(rows, unmatchedTransactions);
        rows.add(new Header(getString(R.string.match_section_matched, matchedReceipts.size())));
        appendMatchedRows(rows, receiptsByGroup, transactionsByGroup);
        return rows;
    }


    private void appendUnmatchedReceiptRows(List<Object> rows, List<MatchEngine.Suggestion> suggestions) {
        if (suggestions.isEmpty()) {
            rows.add(new Header("-"));
            return;
        }
        for (final MatchEngine.Suggestion suggestion : suggestions) {
            rows.add(new SuggestionRow(suggestion.receipt, suggestion.best));
        }
    }


    private void appendUnmatchedTransactionRows(List<Object> rows, List<BankTransaction> unmatchedTransactions) {
        if (unmatchedTransactions.isEmpty()) {
            rows.add(new Header("-"));
            return;
        }
        for (final BankTransaction transaction : unmatchedTransactions) {
            rows.add(new UnmatchedTxRow(transaction));
        }
    }


    private void appendMatchedRows(List<Object> rows,
                                    Map<String, Receipt> receiptsByGroup,
                                    Map<String, BankTransaction> transactionsByGroup) {
        if (receiptsByGroup.isEmpty()) {
            rows.add(new Header("-"));
            return;
        }
        for (final Map.Entry<String, Receipt> entry : receiptsByGroup.entrySet()) {
            final BankTransaction partner = transactionsByGroup.get(entry.getKey());
            if (partner != null) {
                rows.add(new MatchedRow(entry.getValue(), partner));
            }
        }
    }


    // --- Row model ---

    static final class Header {
        final String text;
        Header(String text) { this.text = text; }
    }

    static final class SuggestionRow {
        final Receipt receipt;
        @Nullable final BankTransaction transaction;
        SuggestionRow(Receipt receipt, @Nullable BankTransaction transaction) {
            this.receipt = receipt;
            this.transaction = transaction;
        }
    }

    static final class UnmatchedTxRow {
        final BankTransaction transaction;
        UnmatchedTxRow(BankTransaction transaction) { this.transaction = transaction; }
    }

    static final class MatchedRow {
        final Receipt receipt;
        final BankTransaction transaction;
        MatchedRow(Receipt receipt, BankTransaction transaction) {
            this.receipt = receipt;
            this.transaction = transaction;
        }
    }


    // --- Adapter ---

    class MatchAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        // MUTABLE: re-set in setRows().
        private List<Object> rows = new ArrayList<>();


        void setRows(List<Object> newRows) {
            this.rows = newRows;
            notifyDataSetChanged();
        }


        @Override
        public int getItemViewType(int position) {
            final Object row = rows.get(position);
            if (row instanceof Header) return ROW_TYPE_HEADER;
            if (row instanceof SuggestionRow) return ROW_TYPE_SUGGEST;
            if (row instanceof MatchedRow) return ROW_TYPE_MATCHED;
            if (row instanceof UnmatchedTxRow) return ROW_TYPE_UNMATCHED_TX;
            return ROW_TYPE_HEADER;
        }


        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            if (viewType == ROW_TYPE_HEADER) {
                return new HeaderHolder(inflater.inflate(R.layout.item_match_header, parent, false));
            }
            if (viewType == ROW_TYPE_SUGGEST) {
                return new SuggestHolder(inflater.inflate(R.layout.item_match_suggestion, parent, false));
            }
            if (viewType == ROW_TYPE_UNMATCHED_TX) {
                // Reuse the suggestion layout for unmatched transactions too.
                return new SuggestHolder(inflater.inflate(R.layout.item_match_suggestion, parent, false));
            }
            return new MatchedHolder(inflater.inflate(R.layout.item_match_matched, parent, false));
        }


        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final Object row = rows.get(position);

            if (holder instanceof HeaderHolder) {
                ((HeaderHolder) holder).titleView.setText(((Header) row).text);
                return;
            }
            if (holder instanceof SuggestHolder) {
                bindSuggestHolder((SuggestHolder) holder, row);
                return;
            }
            if (holder instanceof MatchedHolder) {
                bindMatchedHolder((MatchedHolder) holder, (MatchedRow) row);
            }
        }


        private void bindSuggestHolder(SuggestHolder holder, Object row) {
            if (row instanceof SuggestionRow) {
                final SuggestionRow suggestion = (SuggestionRow) row;
                final String merchantLabel;
                if (suggestion.receipt.merchant == null) {
                    merchantLabel = PLACEHOLDER_NO_MERCHANT;
                } else {
                    merchantLabel = suggestion.receipt.merchant;
                }
                holder.leftView.setText(merchantLabel);
                holder.leftAmountView.setText(MoneyUtils.format(suggestion.receipt.amount));
                if (suggestion.transaction != null) {
                    final String rightText = suggestion.transaction.description
                            + " - " + MoneyUtils.formatDate(suggestion.transaction.dateMillis);
                    holder.rightView.setText(rightText);
                    holder.rightAmountView.setText(MoneyUtils.format(suggestion.transaction.amount));
                    holder.itemView.setOnClickListener(clickedView -> confirmMatch(suggestion.receipt, suggestion.transaction));
                } else {
                    holder.rightView.setText(R.string.match_no_suggestion);
                    holder.rightAmountView.setText("");
                    holder.itemView.setOnClickListener(null);
                    holder.itemView.setClickable(false);
                }
            } else if (row instanceof UnmatchedTxRow) {
                final UnmatchedTxRow unmatched = (UnmatchedTxRow) row;
                holder.leftView.setText(unmatched.transaction.description);
                holder.leftAmountView.setText(MoneyUtils.format(unmatched.transaction.amount));
                holder.rightView.setText(MoneyUtils.formatDate(unmatched.transaction.dateMillis));
                holder.rightAmountView.setText("");
                holder.itemView.setOnClickListener(null);
                holder.itemView.setClickable(false);
            }
        }


        private void bindMatchedHolder(MatchedHolder holder, MatchedRow matched) {
            final String merchantLabel;
            if (matched.receipt.merchant == null) {
                merchantLabel = PLACEHOLDER_NO_MERCHANT;
            } else {
                merchantLabel = matched.receipt.merchant;
            }
            holder.leftView.setText(merchantLabel);
            holder.leftAmountView.setText(MoneyUtils.format(matched.receipt.amount));
            final String rightText = matched.transaction.description
                    + " - " + MoneyUtils.formatDate(matched.transaction.dateMillis)
                    + " - " + MoneyUtils.format(matched.transaction.amount);
            holder.rightView.setText(rightText);
            holder.unlinkButton.setOnClickListener(clickedView -> unlink(matched.receipt, matched.transaction));
        }


        @Override
        public int getItemCount() {
            return rows.size();
        }


        class HeaderHolder extends RecyclerView.ViewHolder {
            final TextView titleView;
            HeaderHolder(View itemView) {
                super(itemView);
                titleView = (TextView) itemView;
            }
        }

        class SuggestHolder extends RecyclerView.ViewHolder {
            final TextView leftView;
            final TextView leftAmountView;
            final TextView rightView;
            final TextView rightAmountView;

            SuggestHolder(View itemView) {
                super(itemView);
                leftView = itemView.findViewById(R.id.tv_left);
                leftAmountView = itemView.findViewById(R.id.tv_left_amount);
                rightView = itemView.findViewById(R.id.tv_right);
                rightAmountView = itemView.findViewById(R.id.tv_right_amount);
            }
        }

        class MatchedHolder extends RecyclerView.ViewHolder {
            final TextView leftView;
            final TextView leftAmountView;
            final TextView rightView;
            final MaterialButton unlinkButton;

            MatchedHolder(View itemView) {
                super(itemView);
                leftView = itemView.findViewById(R.id.tv_left);
                leftAmountView = itemView.findViewById(R.id.tv_left_amount);
                rightView = itemView.findViewById(R.id.tv_right);
                unlinkButton = itemView.findViewById(R.id.btn_unlink);
            }
        }
    }


    private void confirmMatch(Receipt receipt, BankTransaction transaction) {
        final String groupId = UUID.randomUUID().toString();
        Logger.i(TAG, "confirmMatch: receipt=" + receipt.id + " (" + receipt.merchant
                + " $" + receipt.amount + ") <- tx=" + transaction.id
                + " (" + transaction.description + " $" + transaction.amount
                + ") groupId=" + groupId);
        executors.diskIO().execute(() -> {
            database.receiptDao().setMatchGroup(receipt.id, groupId);
            database.bankTransactionDao().setMatchGroup(transaction.id, groupId);
            executors.mainThread().execute(() -> {
                Toast.makeText(this, "Matched", Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }


    private void unlink(Receipt receipt, BankTransaction transaction) {
        Logger.i(TAG, "unlink: receipt=" + receipt.id + " tx=" + transaction.id);
        executors.diskIO().execute(() -> {
            database.receiptDao().clearMatchGroup(receipt.id);
            database.bankTransactionDao().clearMatchGroup(transaction.id);
            executors.mainThread().execute(() -> {
                Toast.makeText(this, "Unmatched", Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }
}
