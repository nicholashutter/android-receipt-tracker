# android-receipt-tracker

Local-first Android app for scanning paper receipts with on-device ML Kit OCR, Room storage, and a two-stage price+total classifier (logistic regression) for cross-verifying the marked total.

Everything happens on the device — no cloud OCR, no third-party receipt APIs, no Plaid. The app is a Java 17 libGDX-style project (vanilla Android, Gradle 8.10, Android Gradle Plugin 8.7) and is built against `minSdk 26 / target 34`.

## Features

- **Camera capture** of a paper receipt (CameraX, downscales to 1600px on the long edge before saving).
- **On-device OCR** via Google ML Kit (`com.google.mlkit:text-recognition:16.0.0`). No network round-trip — your receipt never leaves the phone.
- **Receipt parser** (`ReceiptParser`) that pulls merchant, date, line items, subtotal, tax, tip, total out of the raw OCR text using a hand-tuned heuristic plus regex. Surfaces every detected number (with the line it came from and the keyword it was near) so the user can pick which one is the total.
- **Two-stage classifier** for cross-checking the marked total:
  1. **PriceClassifier** — binary logistic regression that drops OCR noise: dates, phone numbers, auth codes, transaction IDs, card numbers, expiration dates, suggested tips, "Version 1.5.20" lines, etc.
  2. **LinearLearner** (aka TotalLearner) — binary logistic regression that, given only the surviving prices, scores each one for P(is the real total) based on `hasTotalKeyword`, `hasComponentKeyword`, `isLargest`, `lineInBottomHalf`, `hasDecimal`, `belowSubtotal`, `closeToSubPlusTax`, `looksLikeDate`, `looksLikeCode`.
- **Entered-vs-circled cross-check + sub+tax sanity check.** When the user marks a number AND types an amount, the verifier runs the classifier on both, compares them to the heuristic `subtotal+tax+tip` prediction, and picks the winner via 3-way majority vote. The "source" of the recommendation (`circled`, `entered`, `model-best`, `sanity-wins`, `model+sanity`) is surfaced in the UI.
- **Manual bank-transaction entry** (no Plaid/CSV/OFX) plus an automatic matcher that suggests pairings between entered transactions and saved receipts.
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
                      └─► ReceiptParser.extractAllNumbers(rawText)
                           │  • heuristic merchant / date / line items
                           │  • bidirectional keyword propagation for subtotal/tax/tip/total
                           │    (handles OCR reading "Subtotal\n44.50" as "44.50\nSubtotal")
                           └─► List<DetectedNumber> { value, line, lineIndex, keyword }
                                └─► EditReceiptActivity
                                     │  • user edits merchant/date/amount
                                     │  • user taps "Pick & verify the total"
                                     │    → AlertDialog lists every DetectedNumber
                                     │    → tap one  →  TotalVerifier.verify(picked, all, entered)
                                     │  • user taps Save
                                     │    → runSanityCheckBeforeSave() runs TotalVerifier again
                                     │      with current entered amount, then writes to Room
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

9 features → P(this price is the real total):

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
| bias | -2.95 |

Run only on the prices that survived stage 1.

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

## Project layout

```
androidscanner/
├── app/
│   ├── build.gradle                          # AGP 8.7, minSdk 26, target 34, Java 17
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml               # 11 activities
│       ├── java/com/example/receipttracker/
│       │   ├── ReceiptTrackerApp.java         # Logger + DB init
│       │   ├── data/                          # Room: AppDatabase, Receipt, BankTransaction, DAOs
│       │   ├── export/ReceiptExporter.java    # JSON + JPEG writer
│       │   ├── log/Logger.java                # ring-buffered file+logcat logger
│       │   ├── match/
│       │   │   ├── LogisticRegression.java    # shared train() / predict() / sigmoid()
│       │   │   ├── PriceClassifier.java       # stage 1: is this a price?
│       │   │   ├── LinearLearner.java         # stage 2: is this the total?
│       │   │   └── TotalVerifier.java         # combines both + cross-check + sanity
│       │   ├── ocr/
│       │   │   ├── ReceiptOcr.java            # ML Kit wrapper, block-Y sorting
│       │   │   ├── ReceiptParser.java         # merchant/date/line items/numbers
│       │   │   ├── DetectedNumber.java        # POJO { value, line, lineIndex, keyword }
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
