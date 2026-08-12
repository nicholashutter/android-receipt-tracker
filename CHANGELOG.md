# Changelog

All notable changes to this project will be documented in this file. The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project follows [Semantic Versioning](https://semver.org/) as of v1.0.0.

## [Unreleased]

### Fixed
- **Delete button unreachable on existing receipts.** The delete
  button was hidden when an existing receipt was loaded
  (`loadExistingReceipt` set `btnDelete.setVisibility(View.GONE)`),
  so the cascade-unmatch + photo-removal path was invisible. Flipped
  so the button is visible for existing receipts and hidden for new
  (unsaved) ones, where the back button is the discard.
- **Auto-pick picked the highest number with a decimal, ignoring
  category.** A receipt with `Tax  9.25%` and `TOTAL  6.25` would
  pick 9.25 as the total because the parser stored `$50.00` as
  `50.0` (integer-shaped) and the value-shape fallbacks misclassified
  8.00 as a quantity and 50.00 as an auth code. Added a rule-based
  `NumberCategory` classifier on `DetectedNumber` (TOTAL / SUBTOTAL
  / LINE_ITEM / TAX / PERCENTAGE / DATE / PHONE / AUTH_CODE /
  QUANTITY / YEAR / OTHER) that picks one category per number from
  keyword + value-shape + line-text context. `pickCircledCandidate`
  now filters the candidate pool to TOTAL / SUBTOTAL / LINE_ITEM
  before applying the priority-1..5 size heuristics. The 9.25%
  tax-rate case is now correctly routed to PERCENTAGE, not TOTAL.

### Added
- **NumberCategory classifier** (`ocr/NumberCategory.java` +
  `DetectedNumber.classify()`). 12 categories, keyword-driven
  primary classification, value-shape fallback. The fallback
  excludes AUTH_CODE/QUANTITY/YEAR when the line text carries a
  decimal point (so `8.00` doesn't fall into the quantity bucket).
  PERCENTAGE is checked before TAX so `Tax  9.25%` classifies as a
  rate, not a money amount.
- **"Add to budget" button on the edit screen** for existing
  receipts. Opens a picker of all active budgets; selecting one
  updates the receipt's `budgetId` and saves. Replaces the
  previous post-verifier "Add to budget?" prompt which was tied to
  the verifier flow.

### Removed
- **TotalVerifier UI surface.** The "Total verification" section
  (`btn_mark_total`, `tv_verifier` panel, verdict drawable swapped
  by `applyVerdictBackground`) is gone from `EditReceiptActivity`.
  The `runVerifier*`, `runSanityCheckBeforeSave`, `showBudgetPrompt`,
  `applyVerdictBackground`, `buildVerifierBody`, `showRePickToast`,
  `onMarkTotalClicked`, `autoPickAndVerify`, and `tryExtractWithVisualSignals`
  methods are deleted. The `TotalVerifier` class itself stays
  in `match/` for the `TestPipelineActivity` debug screen and
  for the unit tests; only the edit-screen UI was removed. The
  reason: Plaid integration isn't on the table for the next
  release and the verifier was the source of more confusion
  than it was worth.

### Tests
- New `DetectedNumberTest` cases covering every category path
  (TOTAL, SUBTOTAL, TAX, PERCENTAGE, LINE_ITEM, DATE, PHONE,
  AUTH_CODE, QUANTITY, the round-decimal rescue, and the
  `mid` → AUTH_CODE false-positive guard).
- New `ReceiptParserTest` cases covering the 9.25% tax bug,
  the date-vs-total priority, the year-vs-total priority, and
  the SUBTOTAL-over-LINE_ITEM fallback.

## [1.1.1] — 2026-08-12

Patch release. Hotfix for a Room schema-identity mismatch that
bricked the app on first launch after the v1.1.0 refactor.

### Fixed
- **Room `IllegalStateException` on every LiveData refresh.** The
  v1.1.0 refactor added explicit `@Index` declarations on `Receipt`
  and re-ordered entity fields, which changed the schema identity
  hash from `2b121208…` to `88e953c8…`. The database `version` was
  not bumped, so Room refused to open the on-device DB and the
  exception escaped the `arch_disk_io_*` executor (Room's
  `fallbackToDestructiveMigration` never fired because the crash
  happened during a LiveData query, not during the upgrade path).
  Bumped `version` 2 → 3 in `AppDatabase`. The existing
  `fallbackToDestructiveMigration` (already approved in the source
  comment for pre-alpha) now wipes the local DB on first launch
  after upgrade.

### Added
- **`scripts/pull-crash-log.ps1`.** Diagnostic helper. Reads
  `adb logcat -b crash` plus the app's own `filesDir/logs/app.log`
  via `run-as`, falling back to plain `cat` if `run-as` is blocked
  (release builds). Prints the AndroidRuntime FATAL stack and the
  last 80 lines of the in-process `Logger` file in one shot.

## [1.1.0] — 2026-08-11

Minor release. Pre-alpha — flagged as a pre-release on GitHub. New
features (image quality gate, calibration, items-sum validation, test
bank), a date-parse bug fix, and a large internal C-style refactor.
No breaking changes for downstream callers.

### Added
- **Image quality gate.** New `ocr/ImageQualityGate` runs four pre-OCR
  checks (Laplacian-variance blur, mean-luma brightness, Sobel
  gradient-tilt, long-edge size) on the captured photo before ML Kit
  fires. The gate samples the bitmap down to 256×256 so the work is
  ~20ms on a modern phone, shows a `"Photo quality is low (blurry,
  tilted). The scan result may be inaccurate — consider retaking."`
  toast when any check fails, and **never blocks** the user. Wired
  into `ScanReceiptActivity.showCapturedAndOcr`. Pure-Java testable
  variant (`assessLuminance(int[], int, int, int, int, int)`) so the
  four checks are covered by `ImageQualityGateTest`.
- **Temperature-scaling calibration in `LogisticRegression`.** New
  `temperature` field on `Trained` and a `calibrate(model, heldOut)`
  static method that does a grid search over T in [0.1, 10.0] in
  steps of 0.05, minimising average log-loss. `predictProbability`
  now applies `sigmoid(logit / T)`. The default of T=1.0 preserves
  the existing uncalibrated behavior; the architecture is in place
  for when held-out data arrives (either from a future
  `"Was this right?"` user-correction affordance, or from a labelled
  evaluation set). Calling `LogisticRegression.calibrate(PriceClassifier.getTrained(), heldOut)`
  one-liners the calibration.
- **Item-list sum validation in `TotalVerifier`.** Sums the line-item
  prices on a receipt (numbers without a keyword, with two decimal
  places, not matching any of subtotal/tax/tip) and includes
  `items sum=$X.XX  (circled delta=$Y.YY, agrees|close|disagrees)`
  in the `sanityCheck` text alongside the existing sub+tax+tip
  message. The check fires only with 2+ line items (a single item
  could trivially equal the total). Example output:
  `sub+tax+tip=$19.99  (circled delta=$0.00); items sum=$18.49  (circled delta=$1.50, close)`.
- **Unit test bank — 181 tests across 14 test classes, JUnit 5 + AssertJ.**
  `app/src/test/java/` is new in this release. The test runner is
  JUnit Platform (`testOptions { unitTests.all { it.useJUnitPlatform() } }`),
  the assertion library is AssertJ 3.24.2, and a real
  `org.json:json:20231013` ships on the test classpath so
  `org.json.JSONObject` is functional (the Android jar ships only
  stubs). Test JVM is pinned to UTC via
  `jvmArgs '-Duser.timezone=UTC'` for deterministic date-formatting
  tests. `returnDefaultValues = true` so Room annotations and
  `android.graphics.Rect` references in our POJOs don't blow up at
  test time. The bank covers:
  - **OCR** — `ReceiptParserTest` (23), `DetectedNumberTest` (13),
    `ParsedReceiptTest` (9), `MerchantClassifierTest` (11),
    `ImageQualityGateTest` (7).
  - **ML pipeline** — `LogisticRegressionTest` (14), `LinearLearnerTest` (14),
    `PriceClassifierTest` (11), `TotalVerifierTest` (14), `MatchEngineTest` (13).
  - **Data** — `ReceiptTest` (18), `BudgetTest` (10), `BankTransactionTest` (10).
  - **Utility / export** — `MoneyUtilsTest` (6), `ReceiptExporterTest` (8).
  Two previously-`@Disabled` tests for the `"5 Jan 2024"` /
  `"Jan 5, 2024"` date formats are re-enabled (see Fixed below).
- **`docs/improving-the-total-scanner.md`** — 50-idea report on
  improving the OCR / classifier pipeline, with a top-5 priority
  list. The shipped image quality gate, calibration, and items-sum
  work are the first three of that list.
- **Bank-API integration report** (carried over from `[Unreleased]`).
  `docs/decisions/0002-bank-api-integration.md` compares Plaid /
  Finicity / MX / Yodlee / Akoya / Stripe Financial Connections on
  Android, with 2026 pricing, a 16.5-day effort estimate broken into
  6 phases, and a recommendation to **defer the integration** until
  the app has shipped and external users have hit the manual-entry
  friction enough to justify the cost.
- **`scripts/fetch_tesseract_eng.sh`** + `app/src/main/assets/tessdata/README.md`
  (carried over from `[Unreleased]`). Placeholder infrastructure for
  a future Tesseract re-OCR path. **Orphaned by this release** — see
  Removed below.

### Fixed
- **`ReceiptParser.tryParseDate` parsed `"5 Jan 2024"` and `"Jan 5, 2024"`
  as null.** The day/month assignments in the two `isMonthToken`
  branches were swapped from what the comments claimed. For
  pattern 4 (`day month-name year`, e.g. `5 Jan 2024`), the
  `isMonthToken(secondGroup)` branch was parsing `firstGroup` (the
  digit "5") as the month name and `secondGroup` (the month "Jan")
  as the day via `Integer.parseInt`, which threw on the month token.
  For pattern 5 (`month-name day, year`, e.g. `Jan 5, 2024`), the
  `isMonthToken(firstGroup)` branch had the symmetric problem. Both
  bugs were caught by the new `ReceiptParserTest` cases for
  `"5 Jan 2024"` and `"Jan 5, 2024"`. Fix: swapped the day/month
  assignments in each branch to match the comment.

### Changed
- **C-style final-at-top refactor across the entire main source tree**
  (34 files). Every method body rewritten to declare all locals
  as `final` at the top, assigned once, used as a pipeline. No more
  mid-method re-assignments. Single-letter variable names (`r`, `b`,
  `v`, `p`, `n`, `t`, `c`, `s`, `m`, `e`, `d`, `k`, etc.) replaced
  with descriptive identifiers. Blank line between every statement.
  No ternary operators anywhere (`if/else` and `switch` expressions
  only). **No behavior change** — 181 tests passing before and after.
- **Entity classes now fully immutable.** `Receipt`, `Budget`,
  `BankTransaction`, `DetectedNumber`, `ParsedReceipt` — every
  mutation goes through a `with*` copy method that returns a new
  instance (or `this` when the value is unchanged, so the no-op case
  is allocation-free). `id` is the only field without a `with*`
  method; it is the primary key and never changes once assigned by
  Room. Room `@Ignore` on `Budget`'s 2-arg convenience constructor
  so Room uses the 6-arg canonical constructor at codegen time.
- **`TotalVerifier` extracted 25+ named constants** (`TOL_STRICT`,
  `TOL_TIGHT`, `TOL_LOOSE`, `PRICE_DELTA_TOLERANCE`, `SUBTOTAL_FLOOR`,
  `LINE_ITEM_MIN`, `MIN_LINE_ITEMS_FOR_SUM`, `CONF_NO_COMPONENTS`,
  `CONF_DELTA_STRICT`, `CONF_DELTA_TIGHT`, `CONF_DELTA_LOOSE`,
  `CONF_DELTA_RATIO`, `CONF_DELTA_FLOOR`, `DELTA_RATIO_THRESHOLD`,
  `ALT_PRESSURE_MARGIN`, `HIGH_CONFIDENCE_BUMP`, `DISAGREE_PENALTY_BASE`,
  `MATCH_BOOST`, `ENSEMBLE_WEIGHT_*`, etc.) where there were magic
  numbers. The monothlic `verify()` method was split into
  `runStage1`, `runHeuristic`, `runStage2`, `runEnteredIfPresent`,
  `buildSanityCheck`, `combineAndAdjust`, `decideRecommendation`,
  `buildReasoning`, plus a separate `verifyEnsemble` method.
- **`VisualSignalDetector` thresholds extracted into named constants**
  (`HIGHLIGHT_R_MIN`, `HIGHLIGHT_G_MIN`, `HIGHLIGHT_B_MAX`,
  `DARK_LUMINANCE_MAX`, `RING_MARGIN_FRACTION`, `ASPECT_MIN/MAX`,
  `SMALL_BBOX_MAX_WIDTH/HEIGHT`, etc.).
- **12 unused imports removed** across 6 files (`TotalVerifier`,
  `LogisticRegression`, `LinearLearner`, `PriceClassifier`,
  `EditReceiptActivity`, `ReceiptParser`).

### Removed
- **`ocr/HandwritingOcr.java`** — was a thin wrapper around
  `tesseract4android` that called `recognizeFirstNumber` on a marked
  bbox, but the result was discarded by every caller (the structured
  OCR pipeline kept the ML Kit value). Dead code. The 12th
  `isHandwritten` feature in `LinearLearner` (which existed only to
  consume `DetectedNumber.handwritingValue`) was removed in the same
  commit; `FEATURE_COUNT` is back to 11 and the synthetic training
  set was updated to match. **Orphans**: the `tesseract4android` Gradle
  dependency in `app/build.gradle`, the `scripts/fetch_tesseract_eng.sh`
  helper, and `app/src/main/assets/tessdata/README.md` are all
  unused by the current pipeline. A follow-up release will drop the
  unused dependency.

## [1.0.1] — 2026-08-10

Patch release. Pre-alpha — flagged as a pre-release on GitHub.

### Fixed
- **Re-pick didn't update as picked in the budget.** When the user tapped
  "Re-pick the total" and selected a number that the verifier disagreed
  with (e.g. picked a subtotal when the verifier preferred the line
  total), `renderVerifier()` overwrote the amount field with the
  verifier's `recommendedTotal`. The receipt then saved with the
  verifier's value, not the user's pick — so the budget never
  reflected what the user actually marked.
- **Save-time sanity check stomped on manual entries.** Once the user
  re-picked a total and then typed a manual correction in the amount
  field, `runSanityCheckBeforeSave()` ran `renderVerifier()` with
  default-overwrite behavior, which clobbered the user's entry with
  the sanity check's recommended total before saving. The receipt
  was then persisted with the sanity check's value, not the typed
  value.

### Changed
- `EditReceiptActivity.renderVerifier(picked, r, entered, autoPicked)`
  gained two optional parameters (preserved as an overload so
  existing call sites keep working): `overwriteAmount` (default true)
  and `showToast` (default true). When `overwriteAmount=false` the
  amount field is left untouched, so the verdict panel is purely
  informational; when `showToast=false` the "Re-picked" confirmation
  toast is suppressed.
- The re-pick path now sets `etAmount = picked.value` and
  `lastVerifiedTotal = picked.value`, so the budget prompt fires
  with the user's number, not the verifier's adjusted value.
- The save-time sanity check now calls
  `renderVerifier(..., overwriteAmount=false, showToast=false)`. The
  sanity check still runs and shows the existing
  `"Sanity check: $X.XX ..."` toast; it just doesn't touch
  `etAmount`. The receipt saves with whatever the user entered or
  re-picked.

## [1.0.0] — 2026-08-09

First tagged release. Pre-alpha — flagged as a pre-release on GitHub.

### Added
- **Visual signal detection.** `VisualSignalDetector` reads the
  actual pixels inside each OCR-detected number's bounding box and
  scores two "the user marked this on purpose" signals: a yellow
  highlighter swatch (R≥200, G≥178, B≤102 → fraction of yellow
  pixels in the bbox) and a pen circle (ring-vs-core dark-pixel
  ratio, with an aspect-ratio filter to reject wide line bboxes).
  The scores are passed through `ReceiptOcr.recognizeWithBoxes()`
  (which returns structured `OcrLine` + per-element bboxes, with the
  old `recognizeText()` preserved for back-compat) and attached to
  each `DetectedNumber` as `highlightScore` and `circleScore`.
  `ReceiptParser.pickCircledCandidate` now has a Priority 0 that
  returns the most-emphasised number before any text heuristic, and
  the two scores are passed straight through as features 9 and 10
  of the `LinearLearner` (11 features total, up from 9). Trained
  weights are strongly positive (around +1.3 for highlight, +1.06
  for circle on a representative run). On open, `EditReceiptActivity`
  re-runs OCR against the saved photo so the visual signals reflect
  the image at edit time, not the scan-time text alone. Motivation:
  a yellow highlight or pen circle is the strongest "this is the
  total" signal a human can leave on a receipt, and treating it as
  one is a much bigger win than any text heuristic.

- **10-run ensemble verifier.** `TotalVerifier.verifyEnsemble(seed,
  allNumbers, entered, N)` runs the full verify pipeline against
  the top-N candidates ranked by `P(isTotal)`, then votes on
  `recommendedTotal`. Default N=10 (`DEFAULT_ENSEMBLE_SIZE`); the
  ensemble winner is whichever value won the most votes. Ensemble
  confidence blends three signals: 55% of the max per-run
  confidence for the winner, 30% of the average per-run confidence
  for the winner, and 15% of the vote share (so 9/10 votes = 0.90).
  `TotalVerifier.Result` is extended with `ensembleVotesForWinner`,
  `ensembleSize`, `ensembleConfidence`, and `ensembleSummary`
  (`"9/10 -> $47.83  max-conf=0.93  avg-conf=0.84  votes=90%"`).
  The editor's auto-pick path calls `verifyEnsemble()` on open; the
  "Re-pick" override still calls a single `verify()` for fast
  feedback. Motivation: a single pass is sensitive to noise (a
  stray "0.99" or "$2.99" can be misread as the total), but
  voting across 10 candidates smooths that out. Verified on
  `noisy_receipt.jpg`: single-pass auto-pick chose $2.99
  (false-positive circle), ensemble correctly voted 9/10 for
  $47.83.

- **JSON-backed merchant classifier.** `assets/merchants.json` is
  the source of truth — about 100 common US merchants across
  grocery, restaurant, coffee, gas, pharmacy, hardware,
  electronics, apparel, home, transport, delivery, shipping, and
  online categories, each with a list of case-insensitive aliases
  and a 0..1 popularity weight. `MerchantClassifier` reads the
  JSON once at app startup on a background thread (wired in
  `ReceiptTrackerApp.onCreate()`) and caches it. `predict(raw)`
  returns a name + category + [0,1] confidence; `topN(raw, n)`
  returns the N best matches for ranked display. Scoring is
  `weight × best_alias_match`, where a whole-string alias match
  scores 1.0 and a token-level substring match scores as a
  hit/total ratio; the final confidence is a linear blend of the
  entry's weight and the gap to the runner-up (a clear winner with
  a high popularity weight lands near 1.0, a tight race around
  0.5). `ParsedReceipt.merchantPrediction` carries the prediction
  alongside the raw merchant string. On open, `EditReceiptActivity`
  replaces the parsed merchant with the canonical name when the
  prediction confidence is ≥ 0.40, and leaves the user's edit alone
  otherwise. Motivation: the merchant line is the largest text at
  the top of most US receipts and OCR frequently merges it with an
  address or phone number, so a small curated list of common
  chains + their known aliases normalises "WHOLE FOODS" / "WFM" /
  "Whole Foods Market" all to "Whole Foods Market" without
  overfitting to a single merchant.

### Changed
- `LinearLearner` is now an 11-feature logistic regression (was
  9 features). The new `highlightScore` and `circleScore`
  features come straight from `VisualSignalDetector` via
  `DetectedNumber`, with strongly positive trained weights — they
  lift a visually-emphasised number above a non-emphasised one
  even when the text-based features are noisy.

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
- **Main screen refresh.** New "Budgets" secondary tile (indigo) and a
  full-width "Active budget" card (with progress bar) replace the
  old two-pill row. The receipts count and transactions count now use
  `countActiveLive` / `countLive` respectively so soft-deleted rows
  don't inflate the headline number.

### Changed
- `EditReceiptActivity` no longer asks the user to mark a number on
  every scan. The flow is now: scan → editor opens with amount
  pre-filled → user confirms by tapping Save (or edits / re-picks if
  needed). The "Pick & verify the total" filled button is now an
  outlined "Re-pick the total" override.
- `TestPipelineActivity.openInEditor()` no longer pre-inserts a
  receipt row. It now passes the OCR'd values as a new-receipt
  intent, which lets the editor's auto-pick path fire.

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
