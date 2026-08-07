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
EditReceiptActivity
   │  • pre-fill from scan's parsed merchant/date/amount
   │  • user can correct any field
   │  • "Pick & verify the total" → AlertDialog of every DetectedNumber
   │    tap one → TotalVerifier.verify(picked, all, entered)
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

### `ReceiptParser`

Three concerns:

1. **Merchant** (`guessMerchant`): scan the first 6 non-empty lines; pick the first that has 2+ uppercase letters and isn't a junk line (e.g. "Receipt", "Invoice", "Order", "Tax ID", "Phone", etc.). The all-caps heuristic is a cheap signal that the line is the brand header.

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
- **Stage 2 (total learner)** can assume every input is a price, so its features can focus entirely on "given a price, what are the characteristics of THE total?". `looksLikeDate` and `looksLikeAuthCode` get much weaker weights as a result.

The two stages each train in <100ms on a desktop and run in well under a millisecond per inference, so the whole pipeline is fast enough to run on every save in the background.

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
- The TotalVerifier runs on `diskIO()` since it can take 50-200ms on a real device.
- `Logger` writes are synchronous to logcat and to the ring buffer, but the file write is buffered.

## What's not here (and why)

- **Kotlin**: project is Java 17 only by design. Easier to grep, easier to onboard a junior dev, no coroutine learning curve.
- **Cloud OCR**: privacy + offline-first. ML Kit's on-device model is good enough for printed receipts.
- **Plaid/CSV/OFX import**: bank-transaction entry is manual. Keeps the app self-contained.
- **Multi-currency**: USD only for now. The `MoneyUtils.format()` call uses the device locale for display, but the storage is a plain `REAL` field.
- **Synthetic data training**: the classifiers are trained on a small hand-curated dataset of synthetic receipts + one noisy test image. Real production training data would be receipts we've verified the totals on; we don't have that yet.
- **CI / GitHub Actions**: this is a personal project. Build with `gradlew.bat assembleDebug` locally, install with `adb install -r`.
