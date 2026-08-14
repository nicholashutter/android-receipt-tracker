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


import java.util.Collections;

import java.util.List;


/**
 * Detail view for a single budget: name, cap, live running spent
 * amount, progress bar, set-active toggle, edit, and a list of
 * linked receipts. Parents additionally show a sub-budgets section
 * with per-child progress bars and an "Add sub-budget" button.
 */
public class BudgetDetailActivity extends AppCompatActivity {

    public static final String EXTRA_BUDGET_ID = "budget_id";

    private static final String TAG = "BudgetDetail";

    private static final int PROGRESS_PERCENT_MAX = 100;
    private static final int INVALID_BUDGET_ID = -1;
    private static final String PLACEHOLDER_NO_MERCHANT = "(no merchant)";
    private static final String STATUS_UNMATCHED = "unmatched";
    private static final String STATUS_MATCHED = "matched";
    private static final String ACTIVE_BUDGET_LABEL = "Active budget";
    private static final String SET_AS_ACTIVE_LABEL = "Set as active budget";
    private static final long NO_PARENT = -1L;


    private long budgetId;

    private BudgetDao budgetDao;
    private ReceiptDao receiptDao;
    private final AppExecutors executors = AppExecutors.get();

    private TextView nameView;
    private TextView activeChipView;
    private TextView spentView;
    private TextView capView;
    private TextView remainingView;
    private TextView noReceiptsView;
    private android.widget.ProgressBar budgetProgressBar;
    private MaterialButton setActiveButton;
    private MaterialButton editButton;
    private MaterialButton deleteButton;
    private MaterialButton addSubBudgetButton;
    private RecyclerView receiptsRecyclerView;
    private RecyclerView subBudgetsRecyclerView;
    private TextView subBudgetsHeader;
    private TextView subBudgetsEmptyView;
    private LinkedReceiptsAdapter receiptsAdapter;
    private SubBudgetsAdapter subBudgetsAdapter;

    private Budget currentBudget;
    private double currentSpent = 0.0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Logger.section("BUDGET DETAIL");

        budgetId = getIntent().getLongExtra(EXTRA_BUDGET_ID, INVALID_BUDGET_ID);
        Logger.i(TAG, "onCreate budgetId=" + budgetId);

        if (budgetId < 0L) {
            finish();
            return;
        }
        setContentView(R.layout.activity_budget_detail);

        budgetDao = AppDatabase.get(this).budgetDao();
        receiptDao = AppDatabase.get(this).receiptDao();

        nameView = findViewById(R.id.tv_name);
        activeChipView = findViewById(R.id.tv_active_chip);
        spentView = findViewById(R.id.tv_spent);
        capView = findViewById(R.id.tv_cap);
        remainingView = findViewById(R.id.tv_remaining);
        noReceiptsView = findViewById(R.id.tv_no_receipts);
        budgetProgressBar = findViewById(R.id.pb_budget);
        setActiveButton = findViewById(R.id.btn_set_active);
        editButton = findViewById(R.id.btn_edit);
        deleteButton = findViewById(R.id.btn_delete);
        addSubBudgetButton = findViewById(R.id.btn_add_sub_budget);
        subBudgetsHeader = findViewById(R.id.tv_sub_budgets_header);
        subBudgetsEmptyView = findViewById(R.id.tv_sub_budgets_empty);
        receiptsRecyclerView = findViewById(R.id.rv_receipts);
        subBudgetsRecyclerView = findViewById(R.id.rv_sub_budgets);

        receiptsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        receiptsAdapter = new LinkedReceiptsAdapter();
        receiptsRecyclerView.setAdapter(receiptsAdapter);

        subBudgetsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        subBudgetsAdapter = new SubBudgetsAdapter();
        subBudgetsRecyclerView.setAdapter(subBudgetsAdapter);

        budgetDao.getByIdLive(budgetId).observe(this, budget -> {
            if (budget == null) {
                finish();
                return;
            }
            currentBudget = budget;
            renderBudget();
        });

        // For parent budgets, the "Spent" headline rolls up the parent
        // + every child. For sub-budgets, it's just the receipts
        // attached to this row. We re-route based on the row type below.

        budgetDao.getByIdLive(budgetId).observe(this, budget -> {
            if (budget == null) return;

            if (budget.isParent()) {
                budgetDao.sumSpentWithChildrenLive(budgetId).observe(this, rawSpent -> {
                    final double spent;
                    if (rawSpent == null) {
                        spent = 0.0;
                    } else {
                        spent = rawSpent;
                    }
                    currentSpent = spent;
                    renderAmounts();
                });
            } else {
                budgetDao.sumSpentLive(budgetId).observe(this, rawSpent -> {
                    final double spent;
                    if (rawSpent == null) {
                        spent = 0.0;
                    } else {
                        spent = rawSpent;
                    }
                    currentSpent = spent;
                    renderAmounts();
                });
            }
        });

        receiptDao.getByBudgetLive(budgetId).observe(this, receipts -> {
            receiptsAdapter.set(receipts);
            final boolean isListEmpty = receipts == null || receipts.isEmpty();
            noReceiptsView.setVisibility(isListEmpty ? View.VISIBLE : View.GONE);
            receiptsRecyclerView.setVisibility(isListEmpty ? View.GONE : View.VISIBLE);
        });

        budgetDao.getChildrenLive(budgetId).observe(this, children -> {
            subBudgetsAdapter.set(children);
            final boolean isParent = currentBudget != null && currentBudget.isParent();
            final boolean hasChildren = children != null && !children.isEmpty();

            // Sub-budgets section is only meaningful on parents.
            if (isParent) {
                subBudgetsHeader.setVisibility(View.VISIBLE);
                addSubBudgetButton.setVisibility(View.VISIBLE);
                subBudgetsRecyclerView.setVisibility(hasChildren ? View.VISIBLE : View.GONE);
                subBudgetsEmptyView.setVisibility(hasChildren ? View.GONE : View.VISIBLE);
            } else {
                subBudgetsHeader.setVisibility(View.GONE);
                addSubBudgetButton.setVisibility(View.GONE);
                subBudgetsRecyclerView.setVisibility(View.GONE);
                subBudgetsEmptyView.setVisibility(View.GONE);
            }

            Logger.i(TAG, "sub-budgets observer: parent=" + isParent + " count=" + (children == null ? 0 : children.size()));
        });

        setActiveButton.setOnClickListener(clickedView -> markCurrentActive());
        editButton.setOnClickListener(clickedView -> showEditDialog());
        deleteButton.setOnClickListener(clickedView -> showDeleteDialog());
        addSubBudgetButton.setOnClickListener(clickedView -> showCreateSubBudgetDialog());
    }


    private void markCurrentActive() {
        executors.diskIO().execute(() -> {
            if (currentBudget != null && !currentBudget.isActive) {
                budgetDao.setActive(currentBudget.id);
                Logger.i(TAG, "Set budget id=" + currentBudget.id + " as active");
            }
        });
    }


    private void renderBudget() {
        if (currentBudget == null) return;

        nameView.setText(currentBudget.name);

        if (currentBudget.isActive) {
            activeChipView.setVisibility(View.VISIBLE);
        } else {
            activeChipView.setVisibility(View.GONE);
        }

        final String activeButtonLabel;
        if (currentBudget.isActive) {
            activeButtonLabel = ACTIVE_BUDGET_LABEL;
        } else {
            activeButtonLabel = SET_AS_ACTIVE_LABEL;
        }
        setActiveButton.setText(activeButtonLabel);
        setActiveButton.setEnabled(!currentBudget.isActive);

        renderAmounts();
    }


    private void renderAmounts() {
        if (currentBudget == null) return;

        spentView.setText(MoneyUtils.format(currentSpent));
        capView.setText("of " + MoneyUtils.format(currentBudget.maxAmount));

        final int progressPct;
        if (currentBudget.maxAmount > 0.0) {
            final double rawPercent = currentSpent * 100.0 / currentBudget.maxAmount;
            final double clampedPercent = Math.min(PROGRESS_PERCENT_MAX, Math.round(rawPercent));
            progressPct = (int) clampedPercent;
        } else {
            progressPct = 0;
        }
        budgetProgressBar.setProgress(progressPct);

        final double remaining = currentBudget.maxAmount - currentSpent;
        if (remaining < 0.0) {
            remainingView.setText("Over by " + MoneyUtils.format(-remaining));
            remainingView.setTextColor(getColor(R.color.error));
        } else {
            remainingView.setText(MoneyUtils.format(remaining) + " remaining");
            remainingView.setTextColor(getColor(R.color.on_surface));
        }
    }


    private void showEditDialog() {
        if (currentBudget == null) return;

        final View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_budget, null, false);
        final TextView etName = dialogView.findViewById(R.id.et_name);
        final TextView etMax = dialogView.findViewById(R.id.et_max);
        final android.widget.CheckBox cbActive = dialogView.findViewById(R.id.cb_set_active);

        // In the edit dialog, the parent picker is hidden — reparenting
        // is a separate operation (delete + recreate).
        dialogView.findViewById(R.id.spinner_parent).setVisibility(View.GONE);
        dialogView.findViewById(R.id.lbl_parent).setVisibility(View.GONE);
        dialogView.findViewById(R.id.tv_parent_explainer).setVisibility(View.GONE);

        etName.setText(currentBudget.name);
        etMax.setText(String.valueOf(currentBudget.maxAmount));
        cbActive.setChecked(currentBudget.isActive);

        new AlertDialog.Builder(this)
                .setTitle("Edit budget")
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Save", (dialogInterface, which) -> {
                    final String enteredName = etName.getText().toString().trim();
                    final String enteredMaxText = etMax.getText().toString().trim();
                    final boolean wantActive = cbActive.isChecked();

                    if (enteredName.isEmpty() || enteredMaxText.isEmpty()) {
                        Toast.makeText(this, "Name and max are required", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    final double parsedMax;
                    try {
                        parsedMax = Double.parseDouble(enteredMaxText);
                    } catch (NumberFormatException parseFailure) {
                        Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    persistBudgetEdits(enteredName, parsedMax, wantActive);
                })
                .show();
    }


    private void persistBudgetEdits(String newName, double newMax, boolean wantActive) {
        executors.diskIO().execute(() -> {
            final Budget updated = currentBudget.withName(newName).withMaxAmount(newMax);
            budgetDao.update(updated);
            if (wantActive && !updated.isActive) {
                budgetDao.setActive(updated.id);
            } else if (!wantActive && updated.isActive) {
                budgetDao.clearAllActive();
            }
            Logger.i(TAG, "Edited budget id=" + currentBudget.id);
        });
    }


    /**
     * Open the create-sub-budget dialog, pre-selecting the current
     * budget as the parent. The dialog is shared with the list
     * screen's create flow.
     */
    private void showCreateSubBudgetDialog() {
        if (currentBudget == null) return;

        new AlertDialog.Builder(this)
                .setTitle("New sub-budget under " + currentBudget.name)
                .setMessage("Use this to break a parent budget into slices (e.g. 'Memphis' and 'Non-Memphis' under 'Travel').")
                .setPositiveButton("Continue", (dialogInterface, which) -> {
                    launchBudgetListCreateDialogWithParent(currentBudget.id);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }


    /**
     * The shared create dialog lives in BudgetListActivity. To reuse
     * it, we pop the create-bottom-sheet-style flow by navigating
     * there with the parent ID encoded in an Intent extra. For
     * simplicity (and because the list is just one tap from the main
     * screen), we open the list activity with a "create under parent"
     * hint and let the user walk through the list dialog. The bottom
     * line: the user lands on the standard dialog with the parent
     * picker pre-selected.
     */
    private void launchBudgetListCreateDialogWithParent(long parentId) {
        final Intent intent = new Intent(this, BudgetListActivity.class);
        intent.putExtra(BudgetListActivity.EXTRA_CREATE_UNDER_PARENT, parentId);
        startActivity(intent);
    }


    private void showDeleteDialog() {
        if (currentBudget == null) return;
        final String message;
        if (currentBudget.isParent()) {
            message = "'" + currentBudget.name + "' and its sub-budgets will be removed. Linked receipts on all of them will be unlinked but kept.";
        } else {
            message = "'" + currentBudget.name + "' will be removed. Linked receipts will be unlinked but kept.";
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete budget?")
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (dialogInterface, which) -> {
                    final long idToDelete = currentBudget.id;
                    executors.diskIO().execute(() -> {
                        if (currentBudget.isParent()) {
                            final List<Budget> children = budgetDao.getChildren(idToDelete);
                            for (Budget child : children) {
                                receiptDao.clearBudgetOnReceipts(child.id);
                            }
                        }
                        receiptDao.clearBudgetOnReceipts(idToDelete);
                        budgetDao.softDelete(idToDelete);
                        Logger.i(TAG, "Soft-deleted budget id=" + idToDelete);
                        finish();
                    });
                })
                .show();
    }


    // ============ sub-budgets adapter ============

    /**
     * One row per sub-budget. Each row shows the name, the spent /
     * cap, and a progress bar. Tapping the row opens this activity
     * for the child (so the user can drill into a sub-budget).
     */
    class SubBudgetsAdapter extends RecyclerView.Adapter<SubBudgetsAdapter.SubBudgetViewHolder> {

        private List<Budget> data = Collections.emptyList();

        private final java.util.Map<Long, Double> spentByChild = new java.util.HashMap<>();

        void set(List<Budget> newData) {
            if (newData == null) {
                this.data = Collections.emptyList();
            } else {
                this.data = newData;
            }
            notifyDataSetChanged();
            refreshSpent();
        }

        private void refreshSpent() {
            executors.diskIO().execute(() -> {
                final java.util.Map<Long, Double> next = new java.util.HashMap<>();
                for (Budget child : data) {
                    next.put(child.id, budgetDao.sumSpent(child.id));
                }
                runOnUiThread(() -> {
                    spentByChild.clear();
                    spentByChild.putAll(next);
                    notifyDataSetChanged();
                });
            });
        }

        @NonNull
        @Override
        public SubBudgetViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View itemView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_budget_sub, parent, false);
            return new SubBudgetViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull SubBudgetViewHolder holder, int position) {
            final Budget child = data.get(position);
            final double spent = spentByChild.getOrDefault(child.id, 0.0);

            holder.name.setText(child.name);

            final String amountsLine = String.format("%s / %s",
                    MoneyUtils.format(spent), MoneyUtils.format(child.maxAmount));
            holder.amounts.setText(amountsLine);

            final int progressPct;
            if (child.maxAmount > 0.0) {
                final double rawPercent = spent * 100.0 / child.maxAmount;
                final double clampedPercent = Math.min(PROGRESS_PERCENT_MAX, Math.round(rawPercent));
                progressPct = (int) clampedPercent;
            } else {
                progressPct = 0;
            }
            holder.progress.setProgress(progressPct);
            holder.status.setText(progressPct + "% used");

            holder.itemView.setOnClickListener(clickedView -> {
                final Intent intent = new Intent(BudgetDetailActivity.this, BudgetDetailActivity.class);
                intent.putExtra(BudgetDetailActivity.EXTRA_BUDGET_ID, child.id);
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class SubBudgetViewHolder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView amounts;
            final TextView status;
            final android.widget.ProgressBar progress;

            SubBudgetViewHolder(View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.tv_name);
                amounts = itemView.findViewById(R.id.tv_amounts);
                status = itemView.findViewById(R.id.tv_status);
                progress = itemView.findViewById(R.id.pb_budget);
            }
        }
    }


    // ============ linked receipts adapter ============

    /**
     * Reads the receipts whose {@code budgetId} matches this row and
     * renders them in a flat list. Tap a row to open the receipt in
     * the editor.
     */
    class LinkedReceiptsAdapter extends RecyclerView.Adapter<LinkedReceiptsAdapter.ReceiptViewHolder> {

        private List<Receipt> data = Collections.emptyList();

        void set(List<Receipt> newData) {
            if (newData == null) this.data = Collections.emptyList();
            else this.data = newData;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ReceiptViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View itemView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_receipt, parent, false);
            return new ReceiptViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull ReceiptViewHolder holder, int position) {
            final Receipt receipt = data.get(position);

            final String merchant;
            if (receipt.merchant == null || receipt.merchant.isEmpty()) {
                merchant = PLACEHOLDER_NO_MERCHANT;
            } else {
                merchant = receipt.merchant;
            }

            holder.merchant.setText(merchant);

            final String amount = MoneyUtils.format(receipt.amount);
            holder.amount.setText(amount);

            final String date = MoneyUtils.formatDate(receipt.dateMillis);
            holder.date.setText(date);

            // We're already inside the budget-detail screen, so the
            // "in budget X" line is redundant — every row in this
            // adapter is for the current budget by construction.
            holder.budget.setVisibility(View.GONE);

            final String status;
            if (receipt.matchGroupId == null) {
                status = STATUS_UNMATCHED;
            } else {
                status = STATUS_MATCHED;
            }
            holder.status.setText(status);

            holder.itemView.setOnClickListener(clickedView -> {
                final Intent intent = new Intent(BudgetDetailActivity.this, EditReceiptActivity.class);
                intent.putExtra(EditReceiptActivity.EXTRA_RECEIPT_ID, receipt.id);
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class ReceiptViewHolder extends RecyclerView.ViewHolder {
            final TextView merchant;
            final TextView amount;
            final TextView date;
            final TextView budget;
            final TextView status;

            ReceiptViewHolder(View itemView) {
                super(itemView);
                merchant = itemView.findViewById(R.id.tv_merchant);
                amount = itemView.findViewById(R.id.tv_amount);
                date = itemView.findViewById(R.id.tv_date);
                budget = itemView.findViewById(R.id.tv_budget);
                status = itemView.findViewById(R.id.tv_status);
            }
        }
    }
}
