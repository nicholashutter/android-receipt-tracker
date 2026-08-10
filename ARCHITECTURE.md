# Architecture

This document is the detailed design source of truth for the android-receipt-tracker app. The README is the entry point; this file explains how and why each part is built the way it is.

## High-level shape

```
camera button
   │
   ▼
ScanReceiptActivity (CameraX, preview, capture)
   │
   ▼  (downscale → 1600px → JPEG → ML Kit OCR)
rawText  ──────────────────────────────────────────────────────────────┐
   │                                                                  │
   ▼                                                                  │
ReceiptParser.parse(rawText)                                          │
   │  ┌─ guessMerchant (first non-junk caps line)                     │
   │  │     → MerchantClassifier.predict() (canonicalises "WFM" →     │
   │  │       "Whole Foods Market" + category + confidence)            │
   │  ├─ guessDate    (try 5 date regexes)                             │
   │  └─ guessAmount   (last number on a TOTAL-keyword line)            │
   │                                                                  │
   ▼                                                                  │
ReceiptParser.extractAllNumbers(rawText)  ◄────────────────────────────┘
   │  ┌─ bidirectional keyword propagation
   │  │     (OCR reads "Subtotal 44.50" as two lines in either order,
   │  │      so we look both directions, stop at the first non-blank
   │  │      non-keyword line)
   │  └─ List<DetectedNumber> { value, line, lineIndex, keyword }
   ▼
EditReceiptActivity (auto-pick on open)
   │  • re-OCR with bounding boxes  →  VisualSignalDetector
   │     (highlightScore + circleScore per number)
   │  • pickCircledCandidate  →  Priority 0: visually-emphasised,
   │     then TOTAL-keyword, then bottom-half largest, then largest
   │  • pre-fill merchant/date/amount; user can correct any field
   │  • TotalVerifier.verifyEnsemble() across top-10 candidates
   │    by P(isTotal) — votes on recommendedTotal
   │  • "Re-pick the total" override → single TotalVerifier.verify()
   │  • Save → runSanityCheckBeforeSave() → TotalVerifier again → Room
   ▼
Room (ReceiptDao)
   ▼
ReceiptExporter → /sdcard/Documents/ReceiptTracker/export/receipt_<id>_<date>.json + .jpg
```

## Data model (Room)

```sql
CREATE TABLE receipts (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  merchant        TEXT,                -- parsed or user-corrected
  amount          REAL NOT NULL,       -- receipt total
  dateMillis      INTEGER NOT NULL,    -- midnight local time
  notes           TEXT,
  photoPath       TEXT,                -- absolute path under /files/
  rawText         TEXT,                -- full OCR text
  matchGroupId    TEXT,                -- shared UUID with a matched bank transaction
  createdAt       INTEGER NOT NULL
);

CREATE TABLE bank_transactions (
  id              INTEGER PRIMARY KEY AUTOINCREMENT,
  merchant        TEXT NOT NULL,
  amount          REAL NOT NULL,
  dateMillis      INTEGER NOT NULL,
  notes           TEXT,
  matchGroupId    TEXT,                -- when set, the matching receipt has the same value
  createdAt       INTEGER NOT NULL
);
```

Indices: `idx_receipts_matchGroupId`, `idx_bank_transactions_matchGroupId` (so the matcher can find pairs quickly).

`MatchEngine.suggestPairs()` does a greedy match by amount + date proximity, returns a sorted list of suggestions.

## OCR pipeline

### `ReceiptOcr.recognizeText(Bitmap)`

- Wraps `com.google.mlkit.vision.text.TextRecognition` with the `TextRecognizerOptions.DEFAULT_OPTIONS` (Latin script, on-device model).
- Returns the raw `String`. The `Text` object's blocks (with bounding boxes) are sorted by `block.boundingBox.top` before flattening into a single newline-joined string, because ML Kit returns dense regions (item lines) before headers, which broke the parser's "first non-junk line is the merchant" heuristic. Sorting by Y first fixes that.
- Both the raw `String` and the `DetectedNumber[]` are written to the log with section headers, so the full OCR text is always recoverable for debugging.

### `ReceiptOcr.recognizeWithBoxes(Bitmap)`

The structured variant. Returns `List<OcrLine>`, where each `OcrLine` carries the line's text, its bounding box, and a list of `OcrElement` (per-word) bounding boxes. ML Kit still does the recognition; we just preserve the geometry on the way out. Used by the visual-signal detector and the editor's "re-OCR on open" path. The old `recognizeText()` is implemented as a one-line call to `recognizeWithBoxes()` followed by a string join, so every caller is now backed by the structured pipeline.

```java
public static final class OcrLine {
    public final String text;          // line text
    public final Rect bbox;            // line bbox, may be null on edge cases
    public final List<OcrElement> elements;  // per-word text + bbox
}
```

`OcrElement` is roughly a word / number / symbol — whatever ML Kit chose to split on. The element bboxes are what the visual-signal detector measures, because we need the pixels around the number, not the whole line.

### `ReceiptParser`

Three concerns:

1. **Merchant** (`guessMerchant`): scan the first 6 non-empty lines; pick the first that has 2+ uppercase letters and isn't a junk line (e.g. "Receipt", "Invoice", "Order", "Tax ID", "Phone", etc.). The all-caps heuristic is a cheap signal that the line is the brand header. The raw string is then passed through `MerchantClassifier.predict()` (see [JSON-backed merchant classifier](#json-backed-merchant-classifier)) to canonicalise it — "WFM" / "WHOLE FOODS" / "Whole Foods Market #12345" all collapse to "Whole Foods Market" with a confidence. `ParsedReceipt.merchantPrediction` carries the prediction; the editor overwrites the merchant field with the canonical name only when the confidence clears 0.40.

2. **Date** (`guessDate`): try 5 regex patterns in order — `YYYY-MM-DD`, `M/D/YY` (or `M-D-YY`), `M/D/YYYY`, `D MMM YYYY`, `MMM D, YYYY`. First match wins. Time zone is the device's local time zone, normalized to midnight.

3. **Amount** (`guessAmount`): pass 1 — find lines with a `total/amount/due/balance/sum/to pay` keyword, take the last money-shaped number on that line (so "Subtotal 5.00 / Tax 0.40 / Total 5.40" → 5.40). Pass 2 — if no total keyword, fall back to the largest decimal in the receipt.

### `ReceiptParser.extractAllNumbers(String)`

The number extractor. Returns `List<DetectedNumber>` where each entry is one money-shaped match tagged with the line it came from and the nearest keyword (subtotal/tax/tip/total/amount/balance/due) if any.

The interesting part is **keyword propagation**. OCR can read a single line like `Subtotal  44.50` as two adjacent lines in either order:

```
Subtotal          (line N)     — keyword only
44.50             (line N+1)   — number, no keyword

or

44.50             (line N)     — number
Subtotal          (line N+1)   — keyword only
```

To handle both, we walk each number line, and if it has no keyword of its own, look at the **immediately adjacent non-blank lines** in both directions, stopping at the first non-blank non-keyword line. This:

- Handles the "Subtotal\n44.50" case (backward propagation: previous line has the keyword).
- Handles the "44.50\nSubtotal" case (forward propagation: next line has the keyword).
- Refuses to leak keywords across many non-keyword lines (e.g. `TOTAL` won't reach all the way down to a stray "Version 1.5.20" line 14 lines later).

We also tag a number line's own keyword if the keyword is on the same line (the simple case).

The pattern is `ReceiptParser.nearestKeyword()` (skips blank lines, stops at a number line or a non-keyword non-blank line).

### `ReceiptParser.extractAllNumbersWithVisualSignals(Bitmap, List<OcrLine>)`

The visual-signal variant. Walks the structured `OcrLine`s from `ReceiptOcr.recognizeWithBoxes()` and emits one `DetectedNumber` per money match, the same way `extractAllNumbers(String)` does — same keyword-propagation rules, same line association — but pairs each match to the `OcrElement` whose text contains the value. The element bbox drives `VisualSignalDetector.detect()`, so the resulting `DetectedNumber` carries `highlightScore`, `circleScore`, and the element bbox alongside the usual fields. If a line has no element-level match (ML Kit emitted the whole line as one element), it falls back to the line-level bbox. If the bitmap is null, the scores default to 0 — the auto-pick still works, just without the visual-signal priority.

### `ReceiptParser.pickCircledCandidate(List<DetectedNumber>)`

Picks the "circled" number on a receipt — i.e. the one a human would have circled with their pen or highlighted with a marker. Four priorities, checked in order:

0. **Visually-emphasised** — a number whose `highlightScore ≥ 0.20` or `circleScore ≥ 0.25`. This is the strongest "user marked this on purpose" signal we have, and it wins before any text heuristic.
1. **TOTAL-keyword line** — a number whose line contains `total / amount / due / balance / sum / to pay`.
2. **Largest in the bottom half** — the biggest money on the second half of the receipt (where totals almost always print).
3. **Largest overall** — last-resort default.

## The two-stage classifier

Both stages share a common logistic-regression implementation in `LogisticRegression.java`:

```java
public static Trained train(String tag, int featureCount,
                            List<Example> data, HyperParams hp) {
    // Online gradient descent, 800 epochs, LR=0.5, L2=0.01
    // Deterministic (cycle-shift the data each epoch for some variety)
    // Returns Trained { double[] weights, double bias }
}

public static double predictProbability(Trained model, double[] features) {
    return sigmoid(dot(weights, features) + bias);
}
```

`HyperParams` is `(int epochs, double learningRate, double l2Lambda)`. The training loop is stable enough that you can crank epochs to a few thousand without diverging.

### Stage 1 — `PriceClassifier.extractFeatures(DetectedNumber)`

| Index | Feature | Notes |
| --- | --- | --- |
| 0 | `hasDecimal` | `v != floor(v)` — 5.99 yes, 6 no |
| 1 | `valueInRange` | `1 < v < 1000` — typical single-item price range |
| 2 | `hasLetters` | 3+ alphabetic chars on the line (real line items do) |
| 3 | `hasCurrency` | `$` on the line |
| 4 | `hasPriceKeyword` | subtotal/tax/tip/total/amount/balance/due/sum/to pay/grand total/net total/charge/price/cost |
| 5 | `looksLikeDate` | `\d{1,2}[/-]\d{1,2}[/-]\d{2,4}` on the line |
| 6 | `looksLikePhone` | `(nnn) nnn-nnnn` or `nnn-nnn-nnnn` |
| 7 | `looksLikeAuthCode` | no decimal AND value in [100, 1e7) AND no price keyword |
| 8 | `looksLikeQuantity` | integer 1-9 |
| 9 | `hasNoiseKeyword` | version/exp/auth/ref/txn/mid/aid/tsi/tvr/suggested/balance/expired/host/terminal/cvm/iad/gratuity guide |

Threshold: `P(isPrice) >= 0.5` keeps the number.

### Stage 2 — `LinearLearner.extractFeatures(candidate, all, subtotal, tax, tip, totalLines)`

| Index | Feature | Notes |
| --- | --- | --- |
| 0 | `hasTotalKeyword` | total/amount/due/balance on the candidate's line |
| 1 | `hasComponentKeyword` | subtotal/tax/tip on the candidate's line |
| 2 | `isLargest` | value ≥ max of all other numbers |
| 3 | `lineInBottomHalf` | `lineIndex >= totalLines / 2` |
| 4 | `hasDecimal` | value has a decimal point |
| 5 | `belowSubtotal` | value < subtotal - 0.01 (can't be the total if smaller than the subtotal) |
| 6 | `closeToSubPlusTax` | within $1 of subtotal+tax+tip |
| 7 | `looksLikeDate` | `n/n` or `n-n` pattern on the line |
| 8 | `looksLikeCode` | integer, value >= 100 and < 1e6 (auth code / txn id) |
| 9 | `highlightScore` | 0..1, fraction of yellow pixels in the bounding box (see [Visual signal detection](#visual-signal-detection)) |
| 10 | `circleScore` | 0..1, ring-vs-core dark pixel ratio (see [Visual signal detection](#visual-signal-detection)) |

Features 9 and 10 are passed through from `DetectedNumber.highlightScore` / `.circleScore` (set by `ReceiptParser.extractAllNumbersWithVisualSignals`). They are weighted strongly positive by the trained model — a number the user marked on purpose is the strongest "this is the total" signal the model can see.

### `TotalVerifier.verify(candidate, allNumbers, enteredAmount)`

The orchestrator. The flow is:

1. **Price filter**: run `PriceClassifier` on every number, keep only the ones that pass the threshold.
2. **Components**: pull subtotal/tax/tip out of the kept prices by keyword. If none, fall back to the sum of line items.
3. **Heuristic prediction**: `predicted = subtotal + tax + tip` (or `lineItemsSum` if no components).
4. **LinearLearner on the candidate** (the circled/marked value).
5. **LinearLearner on every other kept price** to find the best alternative.
6. **(if enteredAmount provided) LinearLearner on the entered value too** — same features, same model.
7. **Cross-check**: did the user-typed amount agree with the marked one to within a dime?
8. **Sanity check**: how far is each of the two from `predicted`?
9. **3-way majority**: if marked and entered agree → use either, high confidence. If they disagree → the one that matches `sub+tax+tip` better wins, breaking further ties with the model probability. If neither matches sub+tax, fall back to whichever has the higher `P(isTotal)`.

The `Result` returned has the verdict plus every intermediate value, so the UI can show the user the full reasoning.

### Why this architecture

The original "guess the total" logic was a single hand-tuned linear scoring function (tolerance windows, adjustments, etc.) and got 5 things right and 5 things wrong depending on the receipt. Splitting it into two classifiers:

- **Stage 1 (price classifier)** handles the "is this even a number we care about" problem, which is fundamentally different from "is this the total". Mixing them into one model forces the weights to compromise.
- **Stage 2 (total learner)** can assume every input is a price, so its features can focus entirely on "given a price, what are the characteristics of THE total?". `looksLikeDate` and `looksLikeAuthCode` get much weaker weights as a result. Adding the two pixel-level visual-signal features (`highlightScore`, `circleScore`) was a free win for the same reason — they only make sense for a number the user already cared about, and a logistic regression can fold them in without having to disentangle "is it a number" from "is it THE number".

The two stages each train in <100ms on a desktop and run in well under a millisecond per inference, so the whole pipeline is fast enough to run on every save in the background — and fast enough to run as a 10-run ensemble (see [10-run ensemble verifier](#10-run-ensemble-verifier)) without a noticeable lag.

## Visual signal detection

`ocr/VisualSignalDetector.java` reads the actual pixels inside each OCR-detected number's bounding box and scores two "the user marked this on purpose" signals. Both scores are 0..1.

**Yellow highlighter.** A pixel is "highlighter yellow" if R ≥ 200, G ≥ 178, B ≤ 102. The score is the fraction of yellow pixels in the bbox (sampled every 2nd pixel for speed). The thresholds are tuned to accept real marker ink and reject paper-tan; they don't explicitly enforce a "G dominant over R" rule, so very warm-tinted paper can also pass, which is fine for ranking purposes. A score of 0.20+ counts as emphasised.

**Pen circle.** A pen circle around a number leaves a ring of dark pixels with a lighter interior. The detector counts dark pixels (luminance ≤ 89) in the outer 30% of the bbox vs. the inner 40%; the score is a function of the ring/core ratio and the absolute ring density, capped at 1.0. A score of 0.25+ counts as emphasised. To suppress false positives on wide-line bboxes (a line of text has dark pixels at the left/right edges and a white middle, which can look ring-shaped), the detector only runs when the bbox aspect ratio is in [0.6, 1.6]. If the highlight score already tripped, the circle score is skipped as redundant.

Both scores attach to a `DetectedNumber` as `highlightScore` and `circleScore`, then become features 9 and 10 of the `LinearLearner`. The trained weights are strongly positive — on a representative run, around +1.3 for highlight and +1.06 for circle — which means a yellow-marked or pen-circled number gets lifted above the text-heuristic baseline even when the surrounding OCR is noisy.

`DetectedNumber.isVisuallyEmphasised()` is the public predicate (any of the two thresholds tripped). `ReceiptParser.pickCircledCandidate` uses it as Priority 0: a visually-emphasised number wins the auto-pick regardless of what the text-based heuristics say.

## Handwriting recognition (Tesseract)

For every number that the visual-signal detector flags as marked, `ReceiptParser.extractAllNumbersWithVisualSignals` also runs `ocr/HandwritingOcr.java` on the same bbox. `HandwritingOcr` is a thin wrapper around `tesseract4android` 4.7 (LSTM, English). ML Kit's Latin text recognizer is print-optimised and misreads most handwritten digits — a pen-written tip often comes back as empty or as a near-match like `$1S.00`. Tesseract handles handwriting much better, at the cost of a ~22 MB `eng.traineddata` file.

If Tesseract finds a number, it lands in `DetectedNumber.handwritingValue`. When **both** the visual signal AND a handwriting value are present, the `DetectedNumber.value` field is set to the Tesseract result (rather than ML Kit's misread), so every downstream caller of `n.value` (the verifier, the verdict panel, the user's amount field) does the right thing with no changes. `DetectedNumber.isHandwrittenAndMarked()` is the public predicate.

The 22 MB `eng.traineddata` is intentionally not bundled. Run `bash scripts/fetch_tesseract_eng.sh` to fetch it (standard / `fast` / `best` variants). Without the file, `HandwritingOcr.isAvailable()` returns false and the pipeline falls back to ML Kit's number for marked bboxes — the visual signals still work, the user can still see and override, we just trust ML Kit's number rather than Tesseract's. See `app/src/main/assets/tessdata/README.md`.

The 12th feature in the `LinearLearner` is `isHandwritten` (0 or 1: was the value re-OCR'd by Tesseract on a marked bbox?). Three new training examples anchor it at a strongly positive weight, so a hand-written digit the user pointed at is the highest-trust "this is the total" signal the model produces.

## 10-run ensemble verifier

`TotalVerifier.verifyEnsemble(seed, allNumbers, entered, N)` is the "panel vote" version of `verify()`. A single pass is sensitive to noise — a stray `0.99` or `$2.99` can be misread as the total — so the ensemble runs the full verify pipeline on the top-N candidates ranked by `P(isTotal)` and votes on the result.

```java
public static final int DEFAULT_ENSEMBLE_SIZE = 10;

public static Result verifyEnsemble(double seed, List<DetectedNumber> all,
                                    double entered, int N) { ... }
```

Flow:

1. Run `PriceClassifier` once on every number, keep the ones that pass the threshold.
2. Score every kept price with `LinearLearner`, sort by `P(isTotal)` desc.
3. For the top-N, run the full `verify()` (stage 1 + stage 2 + cross-check + sanity + 3-way majority). Each run produces a `(recommendedTotal, confidence)` pair.
4. Bucket the `recommendedTotal` values (compared to the cent) and pick the bucket with the most votes — that's the ensemble winner.
5. Ensemble confidence: `0.55 × max_conf_of_winning_runs + 0.30 × avg_conf_of_winning_runs + 0.15 × vote_share`. A 9/10 vote with a strong max-conf run lands near 0.92; a 6/10 vote with a weak max-conf run lands around 0.60. This is more discriminating than a naive mean because the strongest run gets the loudest voice.

`TotalVerifier.Result` is extended with four new fields populated only by the ensemble path:

| Field | Meaning |
| --- | --- |
| `ensembleVotesForWinner` | how many of the N runs picked the winning total |
| `ensembleSize` | the N used (1 means "single run, no ensemble") |
| `ensembleConfidence` | the blended confidence from the formula above |
| `ensembleSummary` | human-readable, e.g. `"9/10 -> $47.83  max-conf=0.93  avg-conf=0.84  votes=90%"` |

`EditReceiptActivity.runVerifierEnsemble()` calls it after the auto-pick finds a candidate. The legacy single `verify()` is still used by the "Re-pick the total" override (fast feedback for an explicit user choice) and by `runSanityCheckBeforeSave()` on save.

Verified on `noisy_receipt.jpg`: the single-pass auto-pick chose `$2.99` (a false-positive ring on a printed price), but the ensemble correctly voted 9/10 for `$47.83`.

## JSON-backed merchant classifier

`ocr/MerchantClassifier.java` is a small "given a noisy merchant string, what real merchant is this?" classifier backed by a flat JSON file at `app/src/main/assets/merchants.json` (about 100 common US merchants across grocery, restaurant, coffee, gas, pharmacy, hardware, electronics, apparel, home, transport, delivery, shipping, online, and cash categories). Each entry has a canonical `name`, a list of case-insensitive `aliases`, a `weight` in 0..1 (popularity), and a `category` (used downstream for budgets and reporting).

**Loading.** The JSON is read once at app startup on a background thread (wired in `ReceiptTrackerApp.onCreate()` → `AppExecutors.get().diskIO().execute(() -> MerchantClassifier.load(this))`) and cached in memory. The first OCR scan doesn't stall on asset I/O; subsequent `predict()` calls are pure in-memory string work.

**Scoring.** For a given raw merchant string, we normalise it (lowercase, replace punctuation with spaces, collapse whitespace) and score every entry by:

```text
score(entry) = weight(entry) × best_alias_match
where best_alias_match =
    1.0  if the normalised string contains (or is contained by) any alias
    hits / max(token_count, alias_token_count)   otherwise
```

so a whole-string alias match beats a partial match, and partial matches are scaled by how much of the merchant the alias covers. Confidence is then `weight × (0.55 + 0.45 × runner_up_gap)`, capped at 0.99. A clear winner (big gap to the runner-up, high popularity weight) lands close to 1.0; a tight race among equally-weighted entries lands around 0.5.

**API.**

- `Prediction predict(String rawMerchant)` — the single best guess, or `null` if nothing scores above 0. Carries the canonical name, category, confidence, and the original raw string.
- `List<Prediction> topN(String rawMerchant, int n)` — the N best candidates in rank order, for "did we get the merchant right?" UI affordances.

**Caller contract.** `ReceiptParser.parse()` populates `ParsedReceipt.merchantPrediction` whenever it has a non-null raw merchant. `EditReceiptActivity` replaces the merchant field with the canonical name when `prediction.confidence ≥ 0.40`; below that, the user's edit is left alone. The threshold is conservative — we'd rather show a noisy raw string than confidently rewrite "SUSHI RESTAURANT" as "McDonald's" because both contain "restaurant" as a weak alias match.

Adding a new merchant is a JSON edit, not a code change.

## Budgets and soft-delete

Both features are baked into the Room schema and surfaced in the main
screen + a new pair of activities.

### Data model additions (v1 → v2 migration)

```sql
CREATE TABLE budgets (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT,                 -- display name, e.g. "Groceries August"
  maxAmount   REAL NOT NULL,        -- the cap
  createdAt   INTEGER NOT NULL,
  isActive    INTEGER NOT NULL,     -- exactly one row has isActive=1
  isDeleted   INTEGER NOT NULL      -- soft-delete tombstone
);
CREATE INDEX index_budgets_isActive ON budgets (isActive);

ALTER TABLE receipts ADD COLUMN budgetId INTEGER;  -- FK to budgets.id, nullable
ALTER TABLE receipts ADD COLUMN deletedAt INTEGER; -- tombstone, NULL = active
CREATE INDEX index_receipts_budgetId  ON receipts (budgetId);
CREATE INDEX index_receipts_deletedAt ON receipts (deletedAt);
```

The migration is **purely additive** — new nullable columns and a new
table. Every existing row gets `budgetId = NULL` and `deletedAt = NULL`,
which means "not in any budget" and "not deleted", both correct defaults
for pre-existing data. No data loss, no prompt to the user.

`fallbackToDestructiveMigration()` is still wired in as a last-resort
safety net for v3+ if a future migration isn't ready when the schema
bumps.

### Active-budget invariant

"Exactly one budget is active" is enforced by the SQL, not by app code.
`BudgetDao.setActive(id)` is:

```sql
UPDATE budgets SET isActive = (CASE WHEN id = :id THEN 1 ELSE 0 END)
```

A single statement atomically clears every other row's `isActive` and
sets the chosen one. There's no window where two rows can be active or
where no row is active (other than the legitimate "no budget yet"
state, which is just "all rows have isActive=0").

### Spent amount is computed, not stored

`BudgetDetailActivity` shows `spent / cap` via a `LiveData<Double>`:

```sql
SELECT COALESCE(SUM(amount), 0)
FROM receipts
WHERE budgetId = :budgetId AND deletedAt IS NULL
```

It's recomputed on every relevant insert / update / soft-delete, so
it never drifts out of sync with the underlying receipts. Same for the
main screen's "active budget" card.

### Soft-delete vs hard-delete

Receipts use a tombstone column (`deletedAt: Long?`). Every normal DAO
query (`getAllActiveLive`, `countActiveLive`, `sumSpentLive`,
`getByBudgetLive`, the matcher, the exporter) filters
`deletedAt IS NULL`. The only way to see deleted rows is the
`getAllLive` query, which the `ReceiptListActivity` calls when the
"Show deleted" menu toggle is on.

A deleted receipt keeps its JPEG, its OCR text, and its `matchGroupId`,
so flipping the toggle back off and on doesn't re-OCR anything, and
restoring a row is just `UPDATE receipts SET deletedAt = NULL WHERE id = ?`
— a no-op for everything that depends on it.

`Budget` has its own `isDeleted` flag (separate from receipts'
`deletedAt`) so the two can be soft-deleted independently.

### Save flow: receipt + budget

```text
EditReceiptActivity.onSave
  ├─ if lastVerifiedTotal != null && !budgetPromptHandled
  │    └─ look up active budget (exec.diskIO)
  │       └─ if present → showBudgetPrompt(active)
  │            ├─ Add     → pendingBudgetId = active.id; fall through
  │            ├─ Skip    → pendingBudgetId = null; fall through
  │            └─ Choose  → showBudgetPicker(all)
  ├─ runSanityCheckBeforeSave() (existing logic, unchanged)
  └─ saveReceiptInternal(budgetId = pendingBudgetId ?? existing.budgetId)
```

The `budgetPromptHandled` flag prevents re-prompting if the sanity
check re-renders the verdict and fires `runVerifier` again. Once the
user has answered (or there is no active budget), the flag stays set
for the lifetime of this save.

If the user picks "Choose another", `showBudgetPicker` lists every
active budget and stores the selection in `pendingBudgetId`. There's
no "create new budget" shortcut from the picker — the user has to
back out and visit the budgets list to add a fresh one. Intentional,
keeps the picker dialog small.

## Logging

`Logger` writes to:
- logcat (tag `RT` for the umbrella, then per-class sub-tags like `OCR`, `Parser`, `Image`, `Edit`, `Verifier`, `TotalLearner`, `PriceClf`).
- `/data/data/com.example.receipttracker/files/logs/app.log`, rolling at 5MB.
- An in-memory ring buffer (500 lines) that the `LogsActivity` reads from for the in-app log viewer.

Every button click, every OCR run, every parser call, every verifier call writes a section header + body to the log. The log is the primary debugging surface — when a verdict looks wrong, the answer is almost always in the log.

## Threading

- `CameraX` runs on the main thread for the preview, takes pictures off the main thread.
- ML Kit OCR is synchronous (a blocking call). We run it on `AppExecutors.diskIO()` (a single-thread executor) so multiple scans don't pile up.
- Room queries are LiveData → main thread.
- The TotalVerifier runs on `diskIO()` since it can take 50-200ms on a real device. The 10-run ensemble is `ensembleSize × verify` invocations on the same executor — still under a second on a mid-range device.
- `MerchantClassifier.load()` is kicked off on `diskIO()` at app start (`ReceiptTrackerApp.onCreate`) so the first `predict()` call is in-memory and synchronous.
- `Logger` writes are synchronous to logcat and to the ring buffer, but the file write is buffered.

## What's not here (and why)

- **Kotlin**: project is Java 17 only by design. Easier to grep, easier to onboard a junior dev, no coroutine learning curve.
- **Cloud OCR**: privacy + offline-first. ML Kit's on-device model is good enough for printed receipts.
- **Plaid/CSV/OFX import**: bank-transaction entry is manual. Keeps the app self-contained.
- **Multi-currency**: USD only for now. The `MoneyUtils.format()` call uses the device locale for display, but the storage is a plain `REAL` field.
- **Synthetic data training**: the classifiers are trained on a small hand-curated dataset of synthetic receipts + one noisy test image. Real production training data would be receipts we've verified the totals on; we don't have that yet.
- **CI / GitHub Actions**: this is a personal project. Build with `gradlew.bat assembleDebug` locally, install with `adb install -r`.
