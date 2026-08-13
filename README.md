# android-receipt-tracker

Local-first Android app for scanning paper receipts with on-device ML Kit OCR, Room storage, and a two-stage price+total classifier (logistic regression) for cross-verifying the marked total.

Everything happens on the device — no cloud OCR, no third-party receipt APIs, no Plaid. The app is a Java 17 libGDX-style project (vanilla Android, Gradle 8.10, Android Gradle Plugin 8.7) and is built against `minSdk 26 / target 34`.

## Features

- **Camera capture** of a paper receipt (CameraX, downscales to 1600px on the long edge before saving).
- **Create receipt** (hand receipt, online order, no photo). A second entry point on the main screen wraps the three creation paths (type the details, take a photo, pick from gallery) and converges to the same editor as the camera-greedy "Scan a receipt" flow.
- **On-device OCR** via Google ML Kit (`com.google.mlkit:text-recognition:16.0.0`). No network round-trip — your receipt never leaves the phone.
- **Receipt parser** (`ReceiptParser`) that pulls merchant, date, line items, subtotal, tax, tip, total out of the raw OCR text using a hand-tuned heuristic plus regex. On every scan, an auto-pick heuristic (`pickCircledCandidate`) chooses the most likely "circled" number — a visually-emphasised number (yellow highlighter or pen circle) first, then a number on a TOTAL-keyword line, then the largest number in the bottom half of the receipt, then the largest number overall — and the editor pre-fills the amount with that value. The user can still edit the amount as a correction, or hit "Re-pick the total" to pick a different number from the full list of candidates ranked by category (TOTAL → SUBTOTAL → LINE_ITEM + every other number the OCR saw, with a small "(excluded: ...)" tag on the ones the auto-pick filtered out).
- **Visual signal detection.** When the editor opens, it re-runs OCR with bounding boxes and walks the actual pixels inside each number's bbox to score two "the user marked this on purpose" signals: a yellow highlighter swatch and a pen circle drawn around the number. The two scores become `DetectedNumber` fields, so a highlighted or circled number wins the auto-pick.
- **Category-based auto-pick.** Each OCR-detected number is classified by `NumberCategory.classify()` into one of 12 categories (TOTAL, SUBTOTAL, LINE_ITEM, TAX, PERCENTAGE, DATE, PHONE, AUTH_CODE, QUANTITY, YEAR, OTHER) using a keyword + value-shape + line-text rule. The auto-pick restricts the candidate pool to TOTAL/SUBTOTAL/LINE_ITEM, then walks the four priorities (visually-emphasised, TOTAL-keyword line, bottom-half largest, largest overall). The user can override any time via "Re-pick the total".
- **Two-stage classifier (diagnostic dump).** `PriceClassifier` (10 features, binary logistic regression) drops OCR noise, then `LinearLearner` (11 features) scores each surviving price for P(is the real total). The classifier pipeline runs in the `TestPipelineActivity` debug screen and in unit tests, but is not invoked by the editor UI. See `TotalVerifier` for the legacy pipeline that combines the two stages with a 10-run ensemble, sub+tax sanity check, and entered-vs-circled cross-check; the class is still in the codebase for the test pipeline.
- **Merchant auto-categorisation.** A JSON-backed classifier (`MerchantClassifier` + `app/src/main/assets/merchants.json`, ~100 common US merchants) canonicalises the parsed merchant line — "WHOLE FOODS MARKET #12345" / "WFM" / "WHOLE FOODS" all collapse to "Whole Foods Market" with a category. The editor uses the canonical name when the prediction confidence clears 0.40; below that, the user's edit is left alone.
- **Manual bank-transaction entry** (no Plaid/CSV/OFX) plus an automatic matcher that suggests pairings between entered transactions and saved receipts.
- **Running budgets (multiple named, exactly one active).** Create as many budgets
  as you want ("Groceries August", "Travel", "Coffee"), each with a cap. The
  active budget shows up on the main screen as a live progress card. Any
  existing receipt can be attached to a budget via the "Add to budget" button
  on the edit screen — useful for budgets created after a receipt was already
  in the system. Soft-delete a budget to remove it from the list without
  losing the linked receipts.
- **Soft-delete receipts (recoverable).** "Clear all" hides every receipt from
  the list without deleting the files. Toggle "Show deleted" in the receipt
  list menu to see them, tap one to restore or permanently delete.
- **JSON + JPEG export** of every receipt.
- **In-app log viewer** that tails a rolling 5MB log file at `/data/data/com.example.receipttracker/files/logs/app.log`.
- **Debug `TestPipelineActivity`** (`adb shell am start -n com.example.receipttracker/.ui.debug.TestPipelineActivity --es extra_image_path /data/.../receipt.jpg`) that runs the full pipeline and dumps intermediate state into the log so the two classifiers can be inspected from outside the app.

## Quick start

This is a vanilla Android Gradle project. The build env on the dev box is:

- JDK 21 (`C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`)
- Android SDK at `C:\Users\nicho\AppData\Local\Android\Sdk` (platforms 34 + 36, build-tools 34/35/36)
- A connected device or emulator (the project was developed against `OpenFPS_API36`, API 36 google_apis x86_64)

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:ANDROID_HOME = "C:\Users\nicho\AppData\Local\Android\Sdk"
cd C:\Development\FullStack\androidscanner
gradlew.bat assembleDebug
```

Then install and launch on a device or emulator:

```bash
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.receipttracker/.ui.MainActivity
```

There's a convenience script `build-and-install.ps1` that wraps the above.

## How a receipt flows through the app

```
CameraX capture
  └─► downscale to 1600px longest edge
       └─► save JPEG to /files/receipts/receipt_<ts>.jpg
            └─► ML Kit on-device text recognition
                 └─► rawText (full OCR string, including all the auth-code noise)
                      └─► ReceiptParser.parse(rawText)
                           │  • heuristic merchant / date / line items
                           │  • MerchantClassifier.predict(merchant) → canonical name + category
                           │  • bidirectional keyword propagation for subtotal/tax/tip/total
                           │    (handles OCR reading "Subtotal\n44.50" as "44.50\nSubtotal")
                           └─► ParsedReceipt { merchant, date, amount, merchantPrediction }
                                └─► EditReceiptActivity
                                     │  • Re-pick the total (picker over all OCR numbers,
                                     │    sorted by category + value desc) if the auto-pick
                                     │    picked the wrong number
                                     │  • user can edit merchant/date/amount
                                     │  • user taps Save
                                     │    → writes to Room
                                     └─► Room (ReceiptDao)
                                          └─► ReceiptExporter
                                               └─► /sdcard/Documents/ReceiptTracker/export/receipt_<id>_<date>.json + .jpg
```

## The two-stage classifier

The architecture is the same as the one you suggested:

> You might need a classification model first that classifies as a price or not a price for all the numbers, then based on that do linear learner to determine which price is the total or subtotal.

Both stages are real logistic-regression classifiers, trained at class-load via online gradient descent with L2 regularization on a small in-code dataset. The shared `LogisticRegression` utility lives in `app/src/main/java/com/example/receipttracker/match/LogisticRegression.java` and is used by both `PriceClassifier.java` and `LinearLearner.java`. Weights are deterministic (no randomness in training), so the learned weights are stable across runs.

### Stage 1 — `PriceClassifier`

10 features → P(this number is a price):

| Feature | Meaning | Weight (after training) |
| --- | --- | --- |
| `hasDecimal` | 1 if the value has a decimal point (e.g. 5.99, not 6) | +1.73 |
| `valueInRange` | 1 if 1.00 < value < 1000.0 | +1.16 |
| `hasLetters` | 1 if the OCR line has 3+ alphabetic chars (real line items say "Bananas 1.99") | +1.30 |
| `hasCurrency` | 1 if line has `$` | +0.58 |
| `hasPriceKeyword` | 1 if line has subtotal/tax/tip/total/amount/balance/due/... | +0.44 |
| `looksLikeDate` | 1 if line has `\d{1,2}[/-]\d{1,2}[/-]\d{2,4}` | -0.33 |
| `looksLikePhone` | 1 if line matches `(nnn) nnn-nnnn` or `nnn-nnn-nnnn` | -0.32 |
| `looksLikeAuthCode` | 1 if no decimal AND value in [100, 1e7) AND no price keyword | **-2.73** |
| `looksLikeQuantity` | 1 if integer 1-9 | -0.93 |
| `hasNoiseKeyword` | 1 if line has version/exp/auth/ref/txn/mid/aid/tsi/tvr/suggested/balance/... | -1.10 |

Threshold: `P(isPrice) >= 0.5` keeps the number, otherwise drops it.

This is what kills the "Version 1.5.20" / "Card Exp 12.27" / "Tip Suggested 9.57" / "Ref #: 12345" lines that the OCR sometimes misreads as money.

### Stage 2 — `LinearLearner` (a.k.a. TotalLearner)

11 features → P(this price is the real total):

| Feature | Weight |
| --- | --- |
| `hasTotalKeyword` (total / amount / due / balance on the line) | +1.56 |
| `hasComponentKeyword` (subtotal / tax / tip on the line) | **-0.77** |
| `isLargest` (≥ all other prices) | **+2.04** |
| `lineInBottomHalf` | +1.31 |
| `hasDecimal` | +0.90 |
| `belowSubtotal` (smaller than the detected subtotal) | -0.73 |
| `closeToSubPlusTax` (within $1 of subtotal+tax+tip) | +1.59 |
| `looksLikeDate` | -0.26 |
| `looksLikeCode` (no decimal, value >= 100) | -0.26 |
| `highlightScore` (fraction of yellow pixels in the bbox) | strongly positive (~+1.3) |
| `circleScore` (ring-vs-core dark pixel ratio) | strongly positive (~+1.06) |
| bias | -2.95 |

Run only on the prices that survived stage 1. The two visual-signal features (added in the latest pass) are passed straight through from `DetectedNumber.highlightScore` / `.circleScore`, which are themselves computed by `VisualSignalDetector` from the source photo. Because the trained weights are recomputed at every app start by the logistic-regression trainer, treat the numbers above as representative of one run rather than exact — they are stable across launches of the same code, but the model is self-training.

### Combining the two passes

`TotalVerifier.verify(candidate, allNumbers, enteredAmount)` runs both stages on the marked value, runs both stages on the user-entered amount (if provided), compares them to the heuristic `subtotal+tax+tip` prediction, and picks the winner. The reasoning string includes every intermediate value, so the user can see exactly which signal the verdict came from:

```
Stage 1 (PriceClf):
  circled $34.56  P(isPrice)=0.87
  entered $62.21  P(isPrice)=0.95
Stage 2 (TotalLearner):
  circled $34.56  P(isTotal)=0.95  (best other: $3.49 P=0.19)
  entered $62.21  P(isTotal)=0.10
Cross-check:
  WARN entered and circled differ — sanity check decides
Sanity check:  sub+tax+tip=$34.56  (circled delta=$0.00, entered delta=$27.65)
Combined:  0.10  ->  recommended 34.56 (circled (sanity wins))
```

### How the auto-pick works

On a fresh scan, `EditReceiptActivity` runs `ReceiptParser.extractAllNumbers(rawText)` to find every number on the receipt, paired with the line text and a propagated keyword ("subtotal", "tax", "total", etc.). Each `DetectedNumber` is then classified by `NumberCategory.classify()` into one of 12 categories based on a keyword + value-shape + line-text mix (a rule-based check, no ML). The auto-pick filter restricts the candidate pool to `TOTAL`, `SUBTOTAL`, and `LINE_ITEM` — tax percentages, dates, phone numbers, auth codes, etc. are excluded. `pickCircledCandidate` then walks four priorities in order: visually-emphasised (yellow highlighter or pen circle), TOTAL-keyword line, bottom-half largest, largest overall. The chosen number pre-fills the amount field. The user can still edit the amount or tap "Re-pick the total" to choose a different number from the full list — sorted by category priority, with excluded (TAX / DATE / PERCENTAGE / etc.) candidates shown at the bottom with a small "(excluded: ...)" tag.

> **Note:** the `TotalVerifier` class (10-run ensemble, sub+tax sanity check, entered-vs-circled cross-check) is still in `match/` for the test pipeline and unit tests, but the edit-screen UI no longer runs it. Plaid integration isn't on the roadmap for the next release, and the verdict panel was the source of more confusion than it was worth. The two classifier stages (`PriceClassifier`, `LinearLearner`) are still used by `TestPipelineActivity` for diagnostic dumps.

### How the merchant guess works

`ReceiptParser.guessMerchant` produces a raw guess (first non-junk caps line) and `MerchantClassifier.predict(raw)` canonicalises it against `app/src/main/assets/merchants.json` — a curated list of about 100 common US merchants with case-insensitive aliases (e.g. "WHOLE FOODS" / "WFM" / "Whole Foods Market" all match "Whole Foods Market"). The classifier scores each entry by `weight × best_alias_match`, where whole-string alias matches score 1.0 and token-substring matches score a hit/total ratio, and returns the top match with a [0,1] confidence. `EditReceiptActivity` uses the canonical name when the confidence clears 0.40 and leaves the parsed string alone below that threshold, so noisy or unfamiliar merchants don't get rewritten into something wrong. Adding a new merchant is a JSON edit, not a code change.

## Project layout

```
androidscanner/
├── app/
│   ├── build.gradle                          # AGP 8.7, minSdk 26, target 34, Java 17
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml               # 12 activities
│       ├── assets/merchants.json             # ~100 US merchants, aliases, weights, categories
│       ├── java/com/example/receipttracker/
│       │   ├── ReceiptTrackerApp.java         # Logger + DB init; pre-loads MerchantClassifier
│       │   ├── data/                          # Room: AppDatabase, Receipt, BankTransaction, DAOs
│       │   ├── export/ReceiptExporter.java    # JSON + JPEG writer
│       │   ├── log/Logger.java                # ring-buffered file+logcat logger
│       │   ├── match/
│       │   │   ├── LogisticRegression.java    # shared train() / predict() / sigmoid()
│       │   │   ├── PriceClassifier.java       # stage 1: is this a price?
│       │   │   ├── LinearLearner.java         # stage 2: is this the total? (11 features)
│       │   │   └── TotalVerifier.java         # combines both + cross-check + sanity + ensemble (test pipeline only)
│       │   ├── ocr/
│       │   │   ├── ReceiptOcr.java            # ML Kit wrapper; recognizeText + recognizeWithBoxes
│       │   │   ├── ReceiptParser.java         # merchant/date/line items/numbers; pickCircledCandidate
│       │   │   ├── DetectedNumber.java        # POJO + NumberCategory.classify()
│       │   │   ├── NumberCategory.java        # 12 categories: TOTAL, SUBTOTAL, LINE_ITEM, TAX, PERCENTAGE, ...
│       │   │   ├── VisualSignalDetector.java  # yellow-highlight + pen-circle pixel scoring
│       │   │   ├── MerchantClassifier.java    # JSON-backed canonical-name + category guess
│       │   │   ├── ParsedReceipt.java
│       │   │   └── ReceiptImageStore.java     # JPEG write + sampled decode
│       │   └── ui/
│       │       ├── MainActivity.java
│       │       ├── receipts/                  # EditReceiptActivity, ReceiptListActivity
│       │       ├── scan/ScanReceiptActivity.java
│       │       ├── match/MatchActivity.java, MatchEngine.java
│       │       ├── transactions/              # AddTransactionActivity, TransactionListActivity
│       │       ├── export/ExportActivity.java
│       │       └── debug/                     # TestPipelineActivity, LogsActivity
│       └── res/
│           ├── layout/                        # 11 activity layouts
│           └── values/                        # strings, colors, themes
├── build.gradle
├── settings.gradle
├── gradle.properties
├── gradlew, gradlew.bat
├── gradle/wrapper/                            # gradle wrapper jar + properties
├── build-and-install.ps1
└── docs/                                      # this README, ARCHITECTURE.md, CHANGELOG.md, samples
```

## Privacy & local-first

- OCR runs on-device via ML Kit's bundled model.
- All receipts, transactions, and photos are stored in app-private storage (`/data/data/com.example.receipttracker/files/`).
- JSON export writes to the public Documents folder; you have to trigger it from the Export screen.
- No network permissions are declared, no analytics SDK, no third-party network calls.
- `Logger` writes to logcat and `/files/logs/app.log` (5MB rolling). Logs may contain raw OCR text — that's the user's data, but it does not leave the device.

## Limitations

- The classifiers are trained on a small in-code dataset of synthetic receipts plus the noisy test image. They are best-effort heuristics, not real ML. If a real receipt has a shape we haven't seen, the model will likely misclassify — the user can always override the total in the amount field.
- The price classifier's `hasNoiseKeyword` feature is hand-rolled; new credit-card-slip formats may need more keywords. Add them to `PriceClassifier.NOISE_KEYWORDS` and the model will retrain on next launch.
- No currency conversion, no foreign-locale merchant names, no multi-receipt batch scanning.

## License

MIT — see [LICENSE](LICENSE).
