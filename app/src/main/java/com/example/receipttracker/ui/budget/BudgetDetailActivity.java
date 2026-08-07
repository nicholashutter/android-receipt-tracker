package com.example.receipttracker.ui.budget;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.receipttracker.R;
import com.example.receipttracker.data.AppDatabase;
import com.example.receipttracker.data.Budget;
import com.example.receipttracker.data.BudgetDao;
import com.example.receipttracker.data.Receipt;
import com.example.receipttracker.data.ReceiptDao;
import com.example.receipttracker.log.Logger;
import com.example.receipttracker.ui.receipts.EditReceiptActivity;
import com.example.receipttracker.util.AppExecutors;
import com.example.receipttracker.util.MoneyUtils;
import com.google.android.material.button.MaterialButton;

import java.util.List;

/**
 * Detail view for a single budget: name, cap, live running spent amount,
 * progress bar, set-active toggle, edit, and a list of linked receipts.
 */
public class BudgetDetailActivity extends AppCompatActivity {

    public static final String EXTRA_BUDGET_ID = "budget_id";

    private static final String TAG = "BudgetDetail";

    private long budgetId;
    private BudgetDao budgetDao;
    private ReceiptDao receiptDao;
    private final AppExecutors exec = AppExecutors.get();

    private TextView tvName, tvActiveChip, tvSpent, tvCap, tvRemaining, tvNoReceipts;
    private android.widget.ProgressBar pbBudget;
    private MaterialButton btnSetActive, btnEdit, btnDelete;
    private RecyclerView rvReceipts;
    private LinkedReceiptsAdapter adapter;

    private Budget currentBudget;
    private double currentSpent = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.section("BUDGET DETAIL");
        budgetId = getIntent().getLongExtra(EXTRA_BUDGET_ID, -1);
        Logger.i(TAG, "onCreate budgetId=" + budgetId);
        if (budgetId < 0) { finish(); return; }
        setContentView(R.layout.activity_budget_detail);

        budgetDao = AppDatabase.get(this).budgetDao();
        receiptDao = AppDatabase.get(this).receiptDao();

        tvName = findViewById(R.id.tv_name);
        tvActiveChip = findViewById(R.id.tv_active_chip);
        tvSpent = findViewById(R.id.tv_spent);
        tvCap = findViewById(R.id.tv_cap);
        tvRemaining = findViewById(R.id.tv_remaining);
        tvNoReceipts = findViewById(R.id.tv_no_receipts);
        pbBudget = findViewById(R.id.pb_budget);
        btnSetActive = findViewById(R.id.btn_set_active);
        btnEdit = findViewById(R.id.btn_edit);
        btnDelete = findViewById(R.id.btn_delete);
        rvReceipts = findViewById(R.id.rv_receipts);

        rvReceipts.setLayoutManager(new LinearLayoutManager(this));
        adapter = new LinkedReceiptsAdapter();
        rvReceipts.setAdapter(adapter);

        budgetDao.getByIdLive(budgetId).observe(this, b -> {
            if (b == null) { finish(); return; }
            currentBudget = b;
            renderBudget();
        });
        budgetDao.sumSpentLive(budgetId).observe(this, s -> {
            currentSpent = s == null ? 0 : s;
            renderAmounts();
        });
        receiptDao.getByBudgetLive(budgetId).observe(this, list -> {
            adapter.set(list);
            tvNoReceipts.setVisibility(list == null || list.isEmpty() ? View.VISIBLE : View.GONE);
            rvReceipts.setVisibility(list == null || list.isEmpty() ? View.GONE : View.VISIBLE);
        });

        btnSetActive.setOnClickListener(v -> {
            exec.diskIO().execute(() -> {
                if (currentBudget != null && !currentBudget.isActive) {
                    budgetDao.setActive(currentBudget.id);
                    Logger.i(TAG, "Set budget id=" + currentBudget.id + " as active");
                }
            });
        });
        btnEdit.setOnClickListener(v -> showEditDialog());
        btnDelete.setOnClickListener(v -> showDeleteDialog());
    }

    private void renderBudget() {
        if (currentBudget == null) return;
        tvName.setText(currentBudget.name);
        tvActiveChip.setVisibility(currentBudget.isActive ? View.VISIBLE : View.GONE);
        btnSetActive.setText(currentBudget.isActive ? "Active budget" : "Set as active budget");
        btnSetActive.setEnabled(!currentBudget.isActive);
        renderAmounts();
    }

    private void renderAmounts() {
        if (currentBudget == null) return;
        tvSpent.setText(MoneyUtils.format(currentSpent));
        tvCap.setText("of " + MoneyUtils.format(currentBudget.maxAmount));
        int pct = currentBudget.maxAmount > 0
                ? (int) Math.min(100, Math.round(currentSpent * 100.0 / currentBudget.maxAmount))
                : 0;
        pbBudget.setProgress(pct);
        double remaining = currentBudget.maxAmount - currentSpent;
        if (remaining < 0) {
            tvRemaining.setText("Over by " + MoneyUtils.format(-remaining));
            tvRemaining.setTextColor(getColor(R.color.error));
        } else {
            tvRemaining.setText(MoneyUtils.format(remaining) + " remaining");
            tvRemaining.setTextColor(getColor(R.color.on_surface));
        }
    }

    private void showEditDialog() {
        if (currentBudget == null) return;
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_create_budget, null, false);
        ((android.widget.TextView) view.findViewById(R.id.et_name)).setText(currentBudget.name);
        ((android.widget.TextView) view.findViewById(R.id.et_max)).setText(String.valueOf(currentBudget.maxAmount));
        ((android.widget.CheckBox) view.findViewById(R.id.cb_set_active)).setChecked(currentBudget.isActive);
        new AlertDialog.Builder(this)
                .setTitle("Edit budget")
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Save", (d, w) -> {
                    String name = ((android.widget.TextView) view.findViewById(R.id.et_name)).getText().toString().trim();
                    String maxStr = ((android.widget.TextView) view.findViewById(R.id.et_max)).getText().toString().trim();
                    boolean wantActive = ((android.widget.CheckBox) view.findViewById(R.id.cb_set_active)).isChecked();
                    if (name.isEmpty() || maxStr.isEmpty()) {
                        Toast.makeText(this, "Name and max are required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    double max;
                    try { max = Double.parseDouble(maxStr); }
                    catch (NumberFormatException e) { Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show(); return; }
                    final double maxFinal = max;
                    final String nameFinal = name;
                    final boolean activeFinal = wantActive;
                    exec.diskIO().execute(() -> {
                        currentBudget.name = nameFinal;
                        currentBudget.maxAmount = maxFinal;
                        budgetDao.update(currentBudget);
                        if (activeFinal && !currentBudget.isActive) {
                            budgetDao.setActive(currentBudget.id);
                        } else if (!activeFinal && currentBudget.isActive) {
                            budgetDao.clearAllActive();
                        }
                        Logger.i(TAG, "Edited budget id=" + currentBudget.id);
                    });
                })
                .show();
    }

    private void showDeleteDialog() {
        if (currentBudget == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Delete budget?")
                .setMessage("'" + currentBudget.name + "' will be removed. Linked receipts will be unlinked but kept.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (d, w) -> {
                    final long id = currentBudget.id;
                    exec.diskIO().execute(() -> {
                        receiptDao.clearBudgetOnReceipts(id);
                        budgetDao.softDelete(id);
                        Logger.i(TAG, "Soft-deleted budget id=" + id);
                    });
                    finish();
                })
                .show();
    }

    // ============ adapter for linked receipts ============

    class LinkedReceiptsAdapter extends RecyclerView.Adapter<LinkedReceiptsAdapter.VH> {
        private List<Receipt> data = java.util.Collections.emptyList();
        void set(List<Receipt> d) {
            this.data = d == null ? java.util.Collections.emptyList() : d;
            notifyDataSetChanged();
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_receipt, parent, false));
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Receipt r = data.get(position);
            h.merchant.setText(r.merchant == null || r.merchant.isEmpty() ? "(no merchant)" : r.merchant);
            h.date.setText(MoneyUtils.formatDate(r.dateMillis));
            h.amount.setText(MoneyUtils.format(r.amount));
            h.status.setText(r.matchGroupId == null ? "unmatched" : "matched");
            h.status.setBackgroundResource(R.drawable.bg_chip_money);
            h.status.setTextColor(getColor(R.color.on_warning_container));
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(BudgetDetailActivity.this, EditReceiptActivity.class);
                i.putExtra(EditReceiptActivity.EXTRA_RECEIPT_ID, r.id);
                startActivity(i);
            });
        }
        @Override public int getItemCount() { return data.size(); }
        class VH extends RecyclerView.ViewHolder {
            final TextView merchant, date, status, amount;
            VH(View v) {
                super(v);
                merchant = v.findViewById(R.id.tv_merchant);
                date = v.findViewById(R.id.tv_date);
                status = v.findViewById(R.id.tv_status);
                amount = v.findViewById(R.id.tv_amount);
            }
        }
    }
}
