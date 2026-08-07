# Changelog

All notable changes to this project will be documented in this file. The format is loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project does not yet use semantic versioning.

## [Unreleased] — 2026-08-07

### Added
- **OCR auto-picks the total.** `ReceiptParser.pickCircledCandidate(numbers)`
  picks the most likely "circled" number on a freshly scanned receipt
  using a three-tier heuristic: a number on a TOTAL-keyword line
  first, then the largest number in the bottom half of the receipt,
  then the largest number overall. `EditReceiptActivity` runs this
  on every new-receipt open, runs the verifier, and pre-fills the
  amount field with the recommended total. The user can still edit
  the amount as a correction, or tap "Re-pick the total" (an
  outlined-button override, no longer the primary action) to pick a
  different number from the detected list. The verdict panel
  renders with the header "Auto-picked by OCR" so it's clear where
  the value came from.

### Changed
- `EditReceiptActivity` no longer asks the user to mark a number on
  every scan. The flow is now: scan → editor opens with amount
  pre-filled → user confirms by tapping Save (or edits / re-picks if
  needed). The "Pick & verify the total" filled button is now an
  outlined "Re-pick the total" override.
- `TestPipelineActivity.openInEditor()` no longer pre-inserts a
  receipt row. It now passes the OCR'd values as a new-receipt
  intent, which lets the editor's auto-pick path fire.

## [0.3.0] — 2026-08-07

### Added
- **Budgets (running budget per category).** Users can create multiple named budgets
  (e.g. "Groceries August", "Travel"), each with a max amount. Exactly one is
  active at a time. The active budget shows up on the main screen as a
  full-width card with a live progress bar (spent / cap). Long-press a budget
  in the list to set it active, edit it, or soft-delete it. Soft-deleting a
  budget clears its `isActive` and hides it from the list (the `Budget`
  entity has its own `isDeleted` flag, separate from receipts).
- **Auto-link verified receipts to the active budget.** When the user saves a
  receipt from `EditReceiptActivity` and a total was just verified, a dialog
  pops up: *"Add $47.83 to 'Groceries August' budget? (24% used, $200 cap)"*
  with three buttons — **Add**, **Skip**, **Choose another**. "Add" stores
  `receipt.budgetId`; "Skip" stores NULL; "Choose another" opens a
  `BudgetPickerDialog` over the list of active budgets. Manual budget
  selection is also possible via the receipt edit screen.
- **Soft-delete receipts (non-destructive).** Every receipt now has a
  `deletedAt` tombstone column. The `ReceiptListActivity` filters
  `deletedAt IS NULL` by default, so deleted receipts disappear from every
  normal view (main count, budget spent, match list, export). A new menu
  item **"Show deleted"** toggles the filter and reveals them with a
  faded style + a "DELETED" warning chip. Tapping a deleted item shows
  **Restore** / **Delete forever** options. A **"Clear all"** menu item in
  normal mode soft-deletes every receipt in one shot; **"Restore all"** in
  show-deleted mode brings them back. There is no destructive batch delete
  in the UI — recoverability is the whole point.
- **Database migration 1 → 2.** Adds the `budgets` table plus two nullable
  columns on `receipts` (`budgetId`, `deletedAt`) and three matching
  indices. The migration is purely additive; existing data is preserved
  with no prompts. `fallbackToDestructiveMigration()` is kept as a
  last-resort safety net for any future v3+ migration that doesn't ship.
- **New `Budget` entity & `BudgetDao`.** LiveData queries for
  `getAllActiveLive`, `getActiveLive`, `sumSpentLive` power the main screen
  and the detail screen. `setActive` is a single atomic SQL UPDATE that
  flips `isActive = (id = :id) ? 1 : 0` on every row in one statement, so
  the "exactly one active" invariant is enforced by the query, not by
  app code racing the DB.
- **New screens: `BudgetListActivity` (FAB "New budget" + long-press
  menu) and `BudgetDetailActivity` (live spent/cap, progress bar, linked
  receipts).** Both are registered in `AndroidManifest.xml`. Layouts,
  icons (`ic_budget.xml`, `ic_add.xml`), and a `dialog_create_budget.xml`
  with Name + Max + "Make this the active budget" checkbox round out the
  set.
- **Main screen refresh.** New "Budgets" secondary tile (indigo) and a
  full-width "Active budget" card (with progress bar) replace the
  old two-pill row. The receipts count and transactions count now use
  `countActiveLive` / `countLive` respectively so soft-deleted rows
  don't inflate the headline number.

### Fixed
- **Main-thread DB crash on the active-budget card.** `MainActivity` was
  calling `budgetDao.getActive()` synchronously on the UI thread inside a
  click listener. Moved to `exec.diskIO().execute(...)` with
  `runOnUiThread` for the post-DB navigation. (The same pattern is used
  throughout the new budget code.)

## [0.2.0] — 2026-08-07

### Added
- **Two-stage price + total classifier.**
  - `LogisticRegression` utility class with online gradient descent (800 epochs, LR=0.5, L2=0.01) and stable sigmoid.
  - `PriceClassifier` — 10 features, binary "is this a price?" with a `hasNoiseKeyword` feature that captures auth codes, expiration dates, suggested tips, and version-number lines that the MONEY regex used to mistake for prices.
  - `LinearLearner` (a.k.a. TotalLearner) — 9 features, binary "is this the total?" trained on synthetic receipts.
  - `TotalVerifier.verify(candidate, allNumbers, enteredAmount)` — orchestrates the two stages, runs an entered-vs-circled cross-check, and a sub+tax+tip sanity check. Returns a 3-way majority verdict (circled / entered / model-best) with a `recommendedSource` string.
- **Bidirectional keyword propagation in `ReceiptParser.extractAllNumbers`** with a "stop at first non-blank non-keyword line" rule. Fixes the bug where OCR reading "Subtotal  44.50" as two lines in either order mis-tagged the number.
- **`EditReceiptActivity` save-time sanity check.** On Save, the verifier runs against the current entered amount + the closest detected number, surfaces the result in the verdict panel and a toast, and only then writes to Room.
- **`TestPipelineActivity`** — debug activity that runs the full pipeline on a caller-supplied image path and dumps every intermediate value (raw OCR, per-number P(isPrice), per-price P(isTotal), trained weights, the sanity check) into the log. Useful for regression testing without launching the camera.
- **`Logger` enhancements** — per-class sub-tags (`OCR`, `Parser`, `Image`, `Edit`, `Verifier`, `TotalLearner`, `PriceClf`), 5MB rolling log file at `/files/logs/app.log`, 500-line in-memory ring buffer, and an `UncaughtExceptionHandler` that writes the stack to the log before the process dies.
- **`LogsActivity`** — in-app tail of the log with Refresh / Share / Clear buttons.

### Changed
- `EditReceiptActivity` no longer overwrites the amount field the moment the user picks a number from the dialog — it shows the full verdict, then auto-applies the recommended total. The user can still edit the amount afterwards; the next save will re-run the sanity check.
- `ReceiptParser.extractAllNumbers` no longer relies on a single forward propagation. The new bidirectional search handles both `Subtotal\n44.50` and `44.50\nSubtotal` correctly.

### Fixed
- **Critical**: `EditReceiptActivity` was missing from `AndroidManifest.xml` after a previous rename, causing the app to crash with `ActivityNotFoundException` the moment a scan completed. Added.
- `ReceiptOcr.flatten()` now sorts text blocks by bounding-box Y before concatenating. The unsorted order put dense item lines before the merchant header, which broke the parser's "first caps line is the merchant" heuristic.
- `MatchActivity` was crashing on `UnmatchedTxRow` cast because the row view holder didn't include a `TYPE_UNMATCHED_TX` case. Added.
- `ExportActivity` and `ScanReceiptActivity` were using a non-final local variable in a lambda (`final String[]` holder pattern).

## [Pre-history] — before 2026-08-07

Initial project bootstrap. The git history was lost in a workspace sync before the initial commit on github.com, so this is reconstructed from memory + `STATE.md`:

- Single-receipt demo: scan → save → list. Worked end-to-end.
- Room schema: `Receipt`, `BankTransaction` with `matchGroupId` foreign-key-style linkage.
- Manual bank-transaction entry form.
- JSON + JPEG export to `/sdcard/Documents/ReceiptTracker/export/`.
- `MatchEngine` greedy match by amount + date proximity.
- "Mark and verify" — original hand-tuned scoring function in `TotalVerifier`. No ML.
- Logged into the app and tested on a real credit-card slip; auto-detected merchant was junk (back of the card has no merchant name) and the OCR misread "TOTAL" as "10TAL" on the slip.
