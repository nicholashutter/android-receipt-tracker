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
import java.util.List;
import java.util.UUID;

public class MatchActivity extends AppCompatActivity {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SUGGEST = 1;
    private static final int TYPE_MATCHED = 2;
    private static final int TYPE_UNMATCHED_TX = 3;

    private RecyclerView rv;
    private MatchAdapter adapter;
    private AppDatabase db;
    private final AppExecutors exec = AppExecutors.get();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.section("MATCH");
        Logger.i("Match", "onCreate");
        setContentView(R.layout.activity_match);
        rv = findViewById(R.id.rv);
        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MatchAdapter();
        rv.setAdapter(adapter);
        db = AppDatabase.get(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        exec.diskIO().execute(() -> {
            List<Receipt> unmatched = db.receiptDao().getUnmatched();
            List<Receipt> matched = db.receiptDao().getMatched();
            List<BankTransaction> unmatchedTx = db.bankTransactionDao().getUnmatched();
            List<BankTransaction> matchedTx = db.bankTransactionDao().getMatched();
            Logger.i("Match", "reload: unmatchedReceipts=" + unmatched.size()
                    + " matchedReceipts=" + matched.size()
                    + " unmatchedTx=" + unmatchedTx.size()
                    + " matchedTx=" + matchedTx.size());

            List<MatchEngine.Suggestion> suggestions =
                    MatchEngine.suggest(unmatched, unmatchedTx);
            Logger.i("Match", "Engine produced " + suggestions.size() + " suggestions ("
                    + suggestions.stream().filter(s -> s.best != null).count() + " with a candidate)");

            // Pair up matched records by groupId
            java.util.Map<String, Receipt> rByGroup = new java.util.HashMap<>();
            for (Receipt r : matched) if (r.matchGroupId != null) rByGroup.put(r.matchGroupId, r);
            java.util.Map<String, BankTransaction> tByGroup = new java.util.HashMap<>();
            for (BankTransaction t : matchedTx) if (t.matchGroupId != null) tByGroup.put(t.matchGroupId, t);

            List<Object> rows = new ArrayList<>();
            rows.add(new Header(getString(R.string.match_section_unmatched_receipts, unmatched.size())));
            if (unmatched.isEmpty()) {
                rows.add(new Header("-"));
            } else {
                for (MatchEngine.Suggestion s : suggestions) {
                    rows.add(new SuggestionRow(s.receipt, s.best));
                }
            }
            rows.add(new Header(getString(R.string.match_section_unmatched_tx, unmatchedTx.size())));
            if (unmatchedTx.isEmpty()) {
                rows.add(new Header("-"));
            } else {
                for (BankTransaction t : unmatchedTx) rows.add(new UnmatchedTxRow(t));
            }
            rows.add(new Header(getString(R.string.match_section_matched, matched.size())));
            if (matched.isEmpty()) {
                rows.add(new Header("-"));
            } else {
                for (java.util.Map.Entry<String, Receipt> e : rByGroup.entrySet()) {
                    BankTransaction t = tByGroup.get(e.getKey());
                    if (t != null) rows.add(new MatchedRow(e.getValue(), t));
                }
            }

            List<Object> finalRows = rows;
            exec.mainThread().execute(() -> adapter.setRows(finalRows));
        });
    }

    // --- Row model ---

    static class Header { final String text; Header(String t) { this.text = t; } }
    static class SuggestionRow {
        final Receipt r; @Nullable final BankTransaction t;
        SuggestionRow(Receipt r, @Nullable BankTransaction t) { this.r = r; this.t = t; }
    }
    static class UnmatchedTxRow { final BankTransaction t; UnmatchedTxRow(BankTransaction t) { this.t = t; } }
    static class MatchedRow { final Receipt r; final BankTransaction t; MatchedRow(Receipt r, BankTransaction t) { this.r = r; this.t = t; } }

    // --- Adapter ---

    class MatchAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        private List<Object> rows = new ArrayList<>();

        void setRows(List<Object> r) { this.rows = r; notifyDataSetChanged(); }

        @Override
        public int getItemViewType(int position) {
            Object o = rows.get(position);
            if (o instanceof Header) return TYPE_HEADER;
            if (o instanceof SuggestionRow) return TYPE_SUGGEST;
            if (o instanceof MatchedRow) return TYPE_MATCHED;
            if (o instanceof UnmatchedTxRow) return TYPE_UNMATCHED_TX;
            return TYPE_HEADER;
        }

        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                return new HeaderVH(inf.inflate(R.layout.item_match_header, parent, false));
            }
            if (viewType == TYPE_SUGGEST) {
                return new SuggestVH(inf.inflate(R.layout.item_match_suggestion, parent, false));
            }
            if (viewType == TYPE_UNMATCHED_TX) {
                return new SuggestVH(inf.inflate(R.layout.item_match_suggestion, parent, false));
            }
            return new MatchedVH(inf.inflate(R.layout.item_match_matched, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object o = rows.get(position);
            if (holder instanceof HeaderVH) {
                ((HeaderVH) holder).tv.setText(((Header) o).text);
            } else if (holder instanceof SuggestVH) {
                SuggestVH vh = (SuggestVH) holder;
                if (o instanceof SuggestionRow) {
                    SuggestionRow s = (SuggestionRow) o;
                    String sMerchant;
                    if (s.r.merchant == null) {
                        sMerchant = "(no merchant)";
                    } else {
                        sMerchant = s.r.merchant;
                    }
                    vh.left.setText(sMerchant);
                    vh.leftAmount.setText(MoneyUtils.format(s.r.amount));
                    if (s.t != null) {
                        vh.right.setText(s.t.description + " - " + MoneyUtils.formatDate(s.t.dateMillis));
                        vh.rightAmount.setText(MoneyUtils.format(s.t.amount));
                        vh.itemView.setOnClickListener(view -> confirmMatch(s.r, s.t));
                    } else {
                        vh.right.setText(R.string.match_no_suggestion);
                        vh.rightAmount.setText("");
                        vh.itemView.setOnClickListener(null);
                        vh.itemView.setClickable(false);
                    }
                } else if (o instanceof UnmatchedTxRow) {
                    UnmatchedTxRow u = (UnmatchedTxRow) o;
                    vh.left.setText(u.t.description);
                    vh.leftAmount.setText(MoneyUtils.format(u.t.amount));
                    vh.right.setText(MoneyUtils.formatDate(u.t.dateMillis));
                    vh.rightAmount.setText("");
                    vh.itemView.setOnClickListener(null);
                    vh.itemView.setClickable(false);
                }
            } else if (holder instanceof MatchedVH) {
                MatchedRow m = (MatchedRow) o;
                MatchedVH vh = (MatchedVH) holder;
                String mMerchant;
                if (m.r.merchant == null) {
                    mMerchant = "(no merchant)";
                } else {
                    mMerchant = m.r.merchant;
                }
                vh.left.setText(mMerchant);
                vh.leftAmount.setText(MoneyUtils.format(m.r.amount));
                vh.right.setText(m.t.description + " - " + MoneyUtils.formatDate(m.t.dateMillis)
                        + " - " + MoneyUtils.format(m.t.amount));
                vh.unlink.setOnClickListener(view -> unlink(m.r, m.t));
            }
        }

        @Override public int getItemCount() { return rows.size(); }

        class HeaderVH extends RecyclerView.ViewHolder {
            final TextView tv;
            HeaderVH(View v) { super(v); tv = (TextView) v; }
        }
        class SuggestVH extends RecyclerView.ViewHolder {
            final TextView left, leftAmount, right, rightAmount;
            SuggestVH(View v) {
                super(v);
                left = v.findViewById(R.id.tv_left);
                leftAmount = v.findViewById(R.id.tv_left_amount);
                right = v.findViewById(R.id.tv_right);
                rightAmount = v.findViewById(R.id.tv_right_amount);
            }
        }
        class MatchedVH extends RecyclerView.ViewHolder {
            final TextView left, leftAmount, right;
            final MaterialButton unlink;
            MatchedVH(View v) {
                super(v);
                left = v.findViewById(R.id.tv_left);
                leftAmount = v.findViewById(R.id.tv_left_amount);
                right = v.findViewById(R.id.tv_right);
                unlink = v.findViewById(R.id.btn_unlink);
            }
        }
    }

    private void confirmMatch(Receipt r, BankTransaction t) {
        String groupId = UUID.randomUUID().toString();
        Logger.i("Match", "confirmMatch: receipt=" + r.id + " (" + r.merchant
                + " $" + r.amount + ") <- tx=" + t.id + " (" + t.description
                + " $" + t.amount + ") groupId=" + groupId);
        exec.diskIO().execute(() -> {
            db.receiptDao().setMatchGroup(r.id, groupId);
            db.bankTransactionDao().setMatchGroup(t.id, groupId);
            exec.mainThread().execute(() -> {
                Toast.makeText(MatchActivity.this, "Matched", Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }

    private void unlink(Receipt r, BankTransaction t) {
        Logger.i("Match", "unlink: receipt=" + r.id + " tx=" + t.id);
        exec.diskIO().execute(() -> {
            db.receiptDao().clearMatchGroup(r.id);
            db.bankTransactionDao().clearMatchGroup(t.id);
            exec.mainThread().execute(() -> {
                Toast.makeText(MatchActivity.this, "Unmatched", Toast.LENGTH_SHORT).show();
                reload();
            });
        });
    }
}
