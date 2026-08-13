package com.example.receipttracker.ui.budget;


import android.app.AlertDialog;

import android.content.Intent;

import android.os.Bundle;

import android.view.LayoutInflater;

import android.view.View;

import android.view.ViewGroup;

import android.widget.ArrayAdapter;

import android.widget.CheckBox;

import android.widget.Spinner;

import android.widget.TextView;

import android.widget.Toast;


import androidx.annotation.NonNull;

import androidx.annotation.Nullable;

import androidx.appcompat.app.AppCompatActivity;

import androidx.recyclerview.widget.LinearLayoutManager;

import androidx.recyclerview.widget.RecyclerView;


import com.example.receipttracker.R;

import com.example.receipttracker.data.AppDatabase;

import com.example.receipttracker.data.Budget;

import com.example.receipttracker.data.BudgetDao;

import com.example.receipttracker.data.ReceiptDao;

import com.example.receipttracker.log.Logger;

import com.example.receipttracker.util.AppExecutors;

import com.example.receipttracker.util.MoneyUtils;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import com.google.android.material.textfield.TextInputEditText;


import java.util.ArrayList;

import java.util.Collections;

import java.util.HashMap;

import java.util.List;

import java.util.Map;


/**
 * Lists every budget — top-level parents above, sub-budgets indented below
 * each parent. Long-press any row to edit, toggle-active, delete, or
 * add a sub-budget. The FAB creates a new top-level parent budget.
 * Sub-budgets are created from the parent row's long-press menu.
 */
public class BudgetListActivity extends AppCompatActivity {

    /**
     * Optional long extra: when this activity is launched from
     * BudgetDetailActivity's "Add sub-budget" button, the value is the
     * parent budget's id. The list auto-opens the create dialog with
     * that parent preselected.
     */
    public static final String EXTRA_CREATE_UNDER_PARENT = "create_under_parent";

    private static final String TAG = "BudgetList";

    private static final int PROGRESS_PERCENT_MAX = 100;

    private static final int LONG_PRESS_OPTION_TOGGLE_ACTIVE = 0;

    private static final int LONG_PRESS_OPTION_EDIT = 1;

    private static final int LONG_PRESS_OPTION_ADD_SUB = 2;

    private static final int LONG_PRESS_OPTION_DELETE = 3;

    private static final int LONG_PRESS_OPTION_COUNT = 4;

    private static final long NO_PARENT = -1L;


    private RecyclerView recyclerView;

    private View emptyView;

    private BudgetAdapter adapter;

    private BudgetDao budgetDao;

    private ReceiptDao receiptDao;

    private final AppExecutors executors = AppExecutors.get();


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Logger.section("BUDGET LIST");

        Logger.i(TAG, "onCreate");

        setContentView(R.layout.activity_budget_list);

        recyclerView = findViewById(R.id.rv);

        emptyView = findViewById(R.id.tv_empty);

        final ExtendedFloatingActionButton fab = findViewById(R.id.fab_new_budget);

        budgetDao = AppDatabase.get(this).budgetDao();

        receiptDao = AppDatabase.get(this).receiptDao();

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        adapter = new BudgetAdapter();

        recyclerView.setAdapter(adapter);

        budgetDao.getAllParentsLive().observe(this, this::render);

        receiptDao.getAllActiveLive().observe(this, list -> adapter.notifyAllRowsChanged());

        fab.setOnClickListener(clickedView -> showCreateDialog(null, NO_PARENT));

        // If we were launched with the "create under parent" hint, pop
        // the create dialog once we have the BudgetListData loaded.
        final long parentHint = getIntent().getLongExtra(EXTRA_CREATE_UNDER_PARENT, NO_PARENT);

        if (parentHint != NO_PARENT) {
            showCreateDialog(null, parentHint);
        }
    }


    private void render(List<Budget> parents) {
        final int parentCount;
        if (parents == null) parentCount = 0;
        else parentCount = parents.size();

        Logger.i(TAG, "render: " + parentCount + " parent budgets");

        adapter.set(parents);

        final boolean isEmpty = parents == null || parents.isEmpty();

        recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);

        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }


    /**
     * Generic create / edit dialog. Pass {@link #NO_PARENT} for
     * {@code existingParentId} to create a top-level budget; pass the
     * id of a parent to create a sub-budget under it.
     */
    private void showCreateDialog(@Nullable Budget edit, long existingParentId) {
        final boolean isEdit = edit != null;

        final View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_budget, null, false);

        final TextInputEditText etName = dialogView.findViewById(R.id.et_name);

        final TextInputEditText etMax = dialogView.findViewById(R.id.et_max);

        final CheckBox cbActive = dialogView.findViewById(R.id.cb_set_active);

        final Spinner spinnerParent = dialogView.findViewById(R.id.spinner_parent);

        final TextView parentLabel = dialogView.findViewById(R.id.lbl_parent);

        final TextView parentExplainer = dialogView.findViewById(R.id.tv_parent_explainer);


        if (isEdit) {
            etName.setText(edit.name);

            etMax.setText(String.valueOf(edit.maxAmount));

            cbActive.setChecked(edit.isActive);

            // Hide the parent picker on edit — reparenting is a separate
            // flow (move-sub-budget to a different parent via long-press).
            spinnerParent.setVisibility(View.GONE);

            parentLabel.setVisibility(View.GONE);

            parentExplainer.setVisibility(View.GONE);
        } else {
            cbActive.setChecked(true);

            populateParentSpinner(spinnerParent, existingParentId);
        }


        final String dialogTitle;

        if (isEdit) {
            dialogTitle = "Edit budget";
        } else if (existingParentId != NO_PARENT) {
            dialogTitle = "New sub-budget";
        } else {
            dialogTitle = "New budget";
        }

        final String positiveButtonLabel = isEdit ? "Save" : "Create";

        final long chosenParentId = isEdit ? NO_PARENT : existingParentId;

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(dialogTitle)
                .setView(dialogView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(positiveButtonLabel, (dialogInterface, which) -> {
                    final String enteredName;

                    if (etName.getText() == null) enteredName = "";
                    else enteredName = etName.getText().toString().trim();

                    final String enteredMaxText;

                    if (etMax.getText() == null) enteredMaxText = "";
                    else enteredMaxText = etMax.getText().toString().trim();

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

                    if (parsedMax <= 0.0) {
                        Toast.makeText(this, "Max must be positive", Toast.LENGTH_SHORT).show();

                        return;
                    }

                    final boolean wantActive = cbActive.isChecked();

                    final long parentIdForNew;

                    if (isEdit) {
                        parentIdForNew = NO_PARENT;
                    } else if (chosenParentId != NO_PARENT) {
                        parentIdForNew = chosenParentId;
                    } else {
                        // User picked from the spinner. Index 0 is the
                        // synthetic "Top-level" item; indices 1..N are the
                        // parents from the DB.
                        final int selectedIndex = spinnerParent.getSelectedItemPosition();

                        if (selectedIndex <= 0) {
                            parentIdForNew = NO_PARENT;
                        } else {
                            final Object selected = spinnerParent.getItemAtPosition(selectedIndex);

                            if (selected instanceof ParentOption) {
                                parentIdForNew = ((ParentOption) selected).id;
                            } else {
                                parentIdForNew = NO_PARENT;
                            }
                        }
                    }

                    persistBudgetChanges(edit, isEdit, enteredName, parsedMax, wantActive, parentIdForNew);
                })
                .create();

        dialog.show();
    }


    /**
     * Builds the spinner contents: a "Top-level" item (representing a
     * new parent budget) followed by every existing parent budget.
     * The spinner is hidden when editing an existing budget.
     */
    private void populateParentSpinner(Spinner spinner, long preselectedParentId) {
        executors.diskIO().execute(() -> {
            final List<Budget> parents = budgetDao.getAllParents();

            final List<ParentOption> options = new ArrayList<>();

            options.add(new ParentOption(NO_PARENT, "Top-level budget"));

            for (Budget parent : parents) {
                options.add(new ParentOption(parent.id, parent.name));
            }

            final ArrayAdapter<ParentOption> adapter = new ArrayAdapter<>(
                    this,
                    android.R.layout.simple_spinner_item,
                    options);

            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

            runOnUiThread(() -> {
                spinner.setAdapter(adapter);

                if (preselectedParentId != NO_PARENT) {
                    for (int index = 0; index < options.size(); index++) {
                        if (options.get(index).id == preselectedParentId) {
                            spinner.setSelection(index);

                            break;
                        }
                    }
                }
            });
        });
    }


    private void persistBudgetChanges(@Nullable Budget edit, boolean isEdit,
                                       String name, double max, boolean wantActive, long parentId) {
        executors.diskIO().execute(() -> {
            if (isEdit) {
                final Budget updated = edit.withName(name).withMaxAmount(max);

                budgetDao.update(updated);

                if (wantActive && !updated.isActive) {
                    budgetDao.setActive(updated.id);
                } else if (!wantActive && updated.isActive) {
                    budgetDao.clearAllActive();
                }

                Logger.i(TAG, "Updated budget id=" + updated.id);
            } else {
                final Budget newBudget;

                if (parentId == NO_PARENT) {
                    newBudget = new Budget(name, max);
                } else {
                    newBudget = new Budget(parentId, name, max);
                }

                final long newId = budgetDao.insert(newBudget);

                if (wantActive) {
                    budgetDao.setActive(newId);
                }

                Logger.i(TAG, "Created budget id=" + newId + " parentId=" + parentId);
            }
        });
    }


    private void showDeleteDialog(Budget budget) {
        final String message;

        if (budget.isParent()) {
            message = "'" + budget.name + "' will be removed. Linked receipts on its sub-budgets will be unlinked but kept.";
        } else {
            message = "'" + budget.name + "' will be removed. Linked receipts will be unlinked but kept.";
        }

        new AlertDialog.Builder(this)
                .setTitle("Delete budget?")
                .setMessage(message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Delete", (dialogInterface, which) -> {
                    final long idToDelete = budget.id;

                    executors.diskIO().execute(() -> {
                        // If we're deleting a parent, also unlink any
                        // receipts on its sub-budgets.
                        if (budget.isParent()) {
                            final List<Budget> children = budgetDao.getChildren(idToDelete);

                            for (Budget child : children) {
                                receiptDao.clearBudgetOnReceipts(child.id);
                            }
                        }

                        receiptDao.clearBudgetOnReceipts(idToDelete);

                        budgetDao.softDelete(idToDelete);

                        Logger.i(TAG, "Soft-deleted budget id=" + idToDelete);
                    });
                })
                .show();
    }


    // ============ spinner DTO ============

    /**
     * Lightweight row for the parent-picker Spinner. id == {@link #NO_PARENT}
     * means "Top-level" (no parent); any other id is the parent this budget
     * will be created under.
     */
    private static final class ParentOption {
        final long id;

        @NonNull
        final String label;

        ParentOption(long id, @NonNull String label) {
            this.id = id;

            this.label = label;
        }

        @NonNull
        @Override
        public String toString() {
            return label;
        }
    }


    // ============ adapter ============

    /**
     * Two-view-type adapter: VIEW_TYPE_PARENT and VIEW_TYPE_CHILD. The
     * child rows are folded into the parent row's hierarchy in
     * {@link BudgetAdapter#set(List)}: when the parent list updates,
     * each parent's children LiveData observer fires and the adapter
     * consolidates the result into one flat list.
     */
    class BudgetAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        private static final int VIEW_TYPE_PARENT = 0;

        private static final int VIEW_TYPE_CHILD = 1;

        private final List<Budget> parents = new ArrayList<>();

        private final Map<Long, List<Budget>> childrenByParent = new HashMap<>();

        private final Map<Long, Double> spentByBudget = new HashMap<>();

        private final List<Object> rows = new ArrayList<>();


        void set(List<Budget> newParents) {
            parents.clear();

            if (newParents != null) parents.addAll(newParents);

            // Drop observers for parents that went away.
            for (Long oldParentId : childrenByParent.keySet()) {
                boolean stillPresent = false;

                for (Budget parent : parents) {
                    if (parent.id == oldParentId) {
                        stillPresent = true;

                        break;
                    }
                }

                if (!stillPresent) {
                    // LiveData observer cleanup happens via the lifecycle;
                    // the map entry is the only thing we own here.
                    childrenByParent.remove(oldParentId);
                }
            }

            // Subscribe to (or refresh) each parent's children.
            for (Budget parent : parents) {
                if (!childrenByParent.containsKey(parent.id)) {
                    childrenByParent.put(parent.id, new ArrayList<>());

                    budgetDao.getChildrenLive(parent.id).observeForever(children -> {
                        childrenByParent.put(parent.id, children == null
                                ? Collections.emptyList()
                                : children);

                        rebuildRows();

                        refreshSpent();
                    });
                }
            }

            rebuildRows();

            refreshSpent();
        }


        void notifyAllRowsChanged() {
            refreshSpent();
        }


        private void rebuildRows() {
            rows.clear();

            for (Budget parent : parents) {
                rows.add(parent);

                final List<Budget> children = childrenByParent.get(parent.id);

                if (children != null) {
                    for (Budget child : children) {
                        rows.add(child);
                    }
                }
            }

            notifyDataSetChanged();
        }


        private void refreshSpent() {
            executors.diskIO().execute(() -> {
                final Map<Long, Double> next = new HashMap<>();

                for (Budget parent : parents) {
                    next.put(parent.id, budgetDao.sumSpentWithChildren(parent.id));

                    final List<Budget> children = childrenByParent.get(parent.id);

                    if (children != null) {
                        for (Budget child : children) {
                            next.put(child.id, budgetDao.sumSpent(child.id));
                        }
                    }
                }

                runOnUiThread(() -> {
                    spentByBudget.clear();

                    spentByBudget.putAll(next);

                    notifyDataSetChanged();
                });
            });
        }


        @Override
        public int getItemViewType(int position) {
            final Object row = rows.get(position);

            if (row instanceof Budget) {
                final Budget budget = (Budget) row;

                if (budget.isParent()) return VIEW_TYPE_PARENT;
                else return VIEW_TYPE_CHILD;
            }

            return VIEW_TYPE_PARENT;
        }


        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final LayoutInflater inflater = LayoutInflater.from(parent.getContext());

            if (viewType == VIEW_TYPE_PARENT) {
                final View itemView = inflater.inflate(R.layout.item_budget, parent, false);

                return new BudgetViewHolder(itemView);
            } else {
                final View itemView = inflater.inflate(R.layout.item_budget_sub, parent, false);

                return new SubBudgetViewHolder(itemView);
            }
        }


        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final Object row = rows.get(position);

            if (!(row instanceof Budget)) return;

            final Budget budget = (Budget) row;

            if (holder instanceof BudgetViewHolder) {
                bindParent(holder, budget);
            } else if (holder instanceof SubBudgetViewHolder) {
                bindChild(holder, budget);
            }
        }


        private void bindParent(@NonNull RecyclerView.ViewHolder holder, Budget budget) {
            final BudgetViewHolder vh = (BudgetViewHolder) holder;

            final double spent = spentByBudget.getOrDefault(budget.id, 0.0);

            vh.name.setText(budget.name);

            final String amountsLine = String.format("%s / %s",
                    MoneyUtils.format(spent), MoneyUtils.format(budget.maxAmount));

            vh.amounts.setText(amountsLine);

            final int progressPct;

            if (budget.maxAmount > 0.0) {
                final double rawPercent = spent * 100.0 / budget.maxAmount;

                final double clampedPercent = Math.min(PROGRESS_PERCENT_MAX, Math.round(rawPercent));

                progressPct = (int) clampedPercent;
            } else {
                progressPct = 0;
            }

            vh.progress.setProgress(progressPct);

            vh.status.setText(progressPct + "% used");

            vh.activeChip.setVisibility(budget.isActive ? View.VISIBLE : View.GONE);

            vh.subtitle.setText("Tap to view · long-press for options");

            vh.itemView.setOnClickListener(clickedView -> openDetail(budget));

            vh.itemView.setOnLongClickListener(clickedView -> {
                showParentLongPress(budget);

                return true;
            });
        }


        private void bindChild(@NonNull RecyclerView.ViewHolder holder, Budget budget) {
            final SubBudgetViewHolder vh = (SubBudgetViewHolder) holder;

            final double spent = spentByBudget.getOrDefault(budget.id, 0.0);

            vh.name.setText(budget.name);

            final String amountsLine = String.format("%s / %s",
                    MoneyUtils.format(spent), MoneyUtils.format(budget.maxAmount));

            vh.amounts.setText(amountsLine);

            final int progressPct;

            if (budget.maxAmount > 0.0) {
                final double rawPercent = spent * 100.0 / budget.maxAmount;

                final double clampedPercent = Math.min(PROGRESS_PERCENT_MAX, Math.round(rawPercent));

                progressPct = (int) clampedPercent;
            } else {
                progressPct = 0;
            }

            vh.progress.setProgress(progressPct);

            vh.status.setText(progressPct + "% used");

            vh.itemView.setOnClickListener(clickedView -> openDetail(budget));

            vh.itemView.setOnLongClickListener(clickedView -> {
                showChildLongPress(budget);

                return true;
            });
        }


        private void openDetail(Budget budget) {
            final Intent detailIntent = new Intent(BudgetListActivity.this, BudgetDetailActivity.class);

            detailIntent.putExtra(BudgetDetailActivity.EXTRA_BUDGET_ID, budget.id);

            startActivity(detailIntent);
        }


        private void showParentLongPress(Budget budget) {
            final String toggleLabel = budget.isActive ? "Deactivate" : "Set as active";

            final String[] longPressOptions = {
                    toggleLabel,
                    "Edit",
                    "Add sub-budget",
                    "Delete"
            };

            new AlertDialog.Builder(BudgetListActivity.this)
                    .setTitle(budget.name)
                    .setItems(longPressOptions, (dialogInterface, which) -> {
                        if (which == LONG_PRESS_OPTION_TOGGLE_ACTIVE) {
                            executors.diskIO().execute(() -> {
                                if (budget.isActive) budgetDao.clearAllActive();
                                else budgetDao.setActive(budget.id);
                            });
                        } else if (which == LONG_PRESS_OPTION_EDIT) {
                            showCreateDialog(budget, NO_PARENT);
                        } else if (which == LONG_PRESS_OPTION_ADD_SUB) {
                            showCreateDialog(null, budget.id);
                        } else {
                            showDeleteDialog(budget);
                        }
                    })
                    .show();
        }


        private void showChildLongPress(Budget budget) {
            final String[] longPressOptions = {
                    "Edit",
                    "Delete"
            };

            new AlertDialog.Builder(BudgetListActivity.this)
                    .setTitle(budget.name)
                    .setItems(longPressOptions, (dialogInterface, which) -> {
                        if (which == 0) {
                            showCreateDialog(budget, NO_PARENT);
                        } else {
                            showDeleteDialog(budget);
                        }
                    })
                    .show();
        }


        @Override
        public int getItemCount() {
            return rows.size();
        }


        class BudgetViewHolder extends RecyclerView.ViewHolder {

            final TextView name;

            final TextView subtitle;

            final TextView amounts;

            final TextView status;

            final TextView activeChip;

            final android.widget.ProgressBar progress;


            BudgetViewHolder(View itemView) {
                super(itemView);

                name = itemView.findViewById(R.id.tv_name);

                subtitle = itemView.findViewById(R.id.tv_subtitle);

                amounts = itemView.findViewById(R.id.tv_amounts);

                status = itemView.findViewById(R.id.tv_status);

                activeChip = itemView.findViewById(R.id.tv_active_chip);

                progress = itemView.findViewById(R.id.pb_budget);
            }
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
}
