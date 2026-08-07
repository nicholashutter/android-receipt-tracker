package com.example.receipttracker.ui.budget;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
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
import com.example.receipttracker.util.AppExecutors;
import com.example.receipttracker.util.MoneyUtils;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists all user budgets and lets the user create, open, set-active, or
 * delete them. Tapping a card opens {@link BudgetDetailActivity}.
 */
public class BudgetListActivity extends AppCompatActivity {

    private static final String TAG = "BudgetList";

    private RecyclerView rv;
    private View tvEmpty;
    private BudgetAdapter adapter;
    private BudgetDao budgetDao;
    private ReceiptDao receiptDao;
    private final AppExecutors exec = AppExecutors.get();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.section("BUDGET LIST");
        Logger.i(TAG, "onCreate");
        setContentView(R.layout.activity_budget_list);

        rv = findViewById(R.id.rv);
        tvEmpty = findViewById(R.id.tv_empty);
        ExtendedFloatingActionButton fab = findViewById(R.id.fab_new_budget);

        budgetDao = AppDatabase.get(this).budgetDao();
        receiptDao = AppDatabase.get(this).receiptDao();

        rv.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BudgetAdapter();
        rv.setAdapter(adapter);

        budgetDao.getAllActiveLive().observe(this, this::render);
        // Observe receipt changes so spent totals stay in sync.
        receiptDao.getAllActiveLive().observe(this, list -> adapter.notifyAllBudgetsChanged());

        fab.setOnClickListener(v -> showCreateDialog(null));
    }

    private void render(List<Budget> budgets) {
        Logger.i(TAG, "render: " + (budgets == null ? 0 : budgets.size()) + " budgets");
        adapter.set(budgets);
        boolean empty = budgets == null || budgets.isEmpty();
        rv.setVisibility(empty ? View.GONE : View.VISIBLE);
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
    }

    private void showCreateDialog(Budget edit) {
        boolean isEdit = edit != null;
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_create_budget, null, false);
        TextInputEditText etName = view.findViewById(R.id.et_name);
        TextInputEditText etMax = view.findViewById(R.id.et_max);
        CheckBox cbActive = view.findViewById(R.id.cb_set_active);
        if (isEdit) {
            etName.setText(edit.name);
            etMax.setText(String.valueOf(edit.maxAmount));
            cbActive.setChecked(edit.isActive);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(isEdit ? "Edit budget" : "New budget")
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(isEdit ? "Save" : "Create", (d, w) -> {
                    String name = etName.getText() == null ? "" : etName.getText().toString().trim();
                    String maxStr = etMax.getText() == null ? "" : etMax.getText().toString().trim();
                    if (name.isEmpty() || maxStr.isEmpty()) {
                        Toast.makeText(this, "Name and max are required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    double max;
                    try { max = Double.parseDouble(maxStr); }
                    catch (NumberFormatException e) { Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show(); return; }
                    if (max <= 0) { Toast.makeText(this, "Max must be positive", Toast.LENGTH_SHORT).show(); return; }
                    final double maxFinal = max;
                    final String nameFinal = name;
                    final boolean wantActive = cbActive.isChecked();
                    exec.diskIO().execute(() -> {
                        if (isEdit) {
                            edit.name = nameFinal;
                            edit.maxAmount = maxFinal;
                            budgetDao.update(edit);
                            if (wantActive && !edit.isActive) {
                                budgetDao.setActive(edit.id);
                            } else if (!wantActive && edit.isActive) {
                                budgetDao.clearAllActive();
                            }
                            Logger.i(TAG, "Updated budget id=" + edit.id);
                        } else {
                            Budget nb = new Budget(nameFinal, maxFinal);
                            long newId = budgetDao.insert(nb);
                            if (wantActive) {
                                budgetDao.setActive(newId);
                                nb.id = newId;
                                nb.isActive = true;
                            }
                            Logger.i(TAG, "Created budget id=" + newId);
                        }
                    });
                });
        b.show();
    }

    private void showDeleteDialog(Budget b) {
        new AlertDialog.Builder(this)
                .setTitle("Delete budget?")
                .setMessage("'" + b.name + "' will be removed. Linked receipts will be unlinked but kept.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (d, w) -> {
                    exec.diskIO().execute(() -> {
                        receiptDao.clearBudgetOnReceipts(b.id);
                        budgetDao.softDelete(b.id);
                        Logger.i(TAG, "Soft-deleted budget id=" + b.id);
                    });
                })
                .show();
    }

    // ============ adapter ============

    class BudgetAdapter extends RecyclerView.Adapter<BudgetAdapter.VH> {
        private List<Budget> data = java.util.Collections.emptyList();
        // We can't bind per-row spent in onBindViewHolder because the LiveData
        // is observed at the activity level, not per row. We recompute spent
        // by querying sumSpent on the disk executor every time receipts change
        // and post results back through a simple map.
        private final Map<Long, Double> spentByBudget = new HashMap<>();

        void set(List<Budget> d) {
            this.data = d == null ? java.util.Collections.emptyList() : d;
            notifyDataSetChanged();
            refreshSpent();
        }

        void notifyAllBudgetsChanged() {
            refreshSpent();
        }

        private void refreshSpent() {
            exec.diskIO().execute(() -> {
                Map<Long, Double> next = new HashMap<>();
                for (Budget b : data) {
                    next.put(b.id, budgetDao.sumSpent(b.id));
                }
                runOnUiThread(() -> {
                    spentByBudget.clear();
                    spentByBudget.putAll(next);
                    notifyDataSetChanged();
                });
            });
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_budget, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Budget b = data.get(position);
            h.name.setText(b.name);
            double spent = spentByBudget.getOrDefault(b.id, 0.0);
            h.amounts.setText(String.format("%s / %s",
                    MoneyUtils.format(spent), MoneyUtils.format(b.maxAmount)));
            int pct = b.maxAmount > 0 ? (int) Math.min(100, Math.round(spent * 100.0 / b.maxAmount)) : 0;
            h.progress.setProgress(pct);
            h.status.setText(pct + "% used");
            h.activeChip.setVisibility(b.isActive ? View.VISIBLE : View.GONE);
            h.itemView.setOnClickListener(v -> {
                Intent i = new Intent(BudgetListActivity.this, BudgetDetailActivity.class);
                i.putExtra(BudgetDetailActivity.EXTRA_BUDGET_ID, b.id);
                startActivity(i);
            });
            h.itemView.setOnLongClickListener(v -> {
                String[] opts = {b.isActive ? "Deactivate" : "Set as active",
                        "Edit", "Delete"};
                new AlertDialog.Builder(BudgetListActivity.this)
                        .setTitle(b.name)
                        .setItems(opts, (d, w) -> {
                            if (w == 0) {
                                exec.diskIO().execute(() -> {
                                    if (b.isActive) budgetDao.clearAllActive();
                                    else budgetDao.setActive(b.id);
                                });
                            } else if (w == 1) {
                                showCreateDialog(b);
                            } else {
                                showDeleteDialog(b);
                            }
                        })
                        .show();
                return true;
            });
        }

        @Override public int getItemCount() { return data.size(); }

        class VH extends RecyclerView.ViewHolder {
            final TextView name, subtitle, amounts, status, activeChip;
            final android.widget.ProgressBar progress;
            VH(View v) {
                super(v);
                name = v.findViewById(R.id.tv_name);
                subtitle = v.findViewById(R.id.tv_subtitle);
                amounts = v.findViewById(R.id.tv_amounts);
                status = v.findViewById(R.id.tv_status);
                activeChip = v.findViewById(R.id.tv_active_chip);
                progress = v.findViewById(R.id.pb_budget);
                subtitle.setText("Tap to view · long-press for options");
            }
        }
    }
}
