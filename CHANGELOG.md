# Changelog

All notable changes to this project will be documented in this file. The format is loosely based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project does not yet use semantic versioning.

## [Unreleased] — 2026-08-07

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
