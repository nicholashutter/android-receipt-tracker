# Receipt Tracker — current state

Last reviewed against the source tree at `C:\Development\FullStack\androidscanner\` (the merged project + export landing folder; the export landing is the subdirectory `androidreciepts/`). 22 Java files, 23 resource files. AsyncTask has been fully replaced with `Executor` + `LiveData` across all 7 activities.

## What it is

A local-first Android app that scans paper receipts with on-device OCR, lets the user correct the parsed fields, stores both receipts and manually-entered bank charges in a Room database, suggests matches between the two by amount + date, and exports each receipt as a JPEG + JSON sidecar into a user-picked folder (typically the `androidreciepts/` subdirectory of this project, synced back to the dev box). No network, no account, no cloud SDK.

## What's built and ready

- **Camera capture with CameraX.** Preview + `ImageCapture`, JPEG-downscaled to 1600px on the long edge before OCR. Permission requested with `ActivityResultContracts.RequestPermission`. Gallery fallback via `ActivityResultContracts.GetContent`. See `C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\ui\scan\ScanReceiptActivity.java:104` (camera bind) and `:165` (downscale).
- **On-device OCR via ML Kit Latin recognizer.** Synchronous wrapper around `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)` with a 20-second `CountDownLatch`. See `C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\ocr\ReceiptOcr.java:29`.
- **Heuristic parser for merchant, date, total.** See "OCR / parsing approach" below.
- **Editable receipt form.** Merchant, amount, date (DatePicker), notes, optional raw-text drawer, thumbnail. Validates merchant + positive amount. Preserves `matchGroupId` and `createdAt` when editing. Delete cascades by clearing the bank-transaction side of the match group. `C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\ui\receipts\EditReceiptActivity.java:170` (save) and `:209` (delete with cascade).
- **Local image store.** Files land in `<filesDir>/receipts/receipt_<yyyyMMdd_HHmmss_SSS>.jpg` and are wiped on uninstall. Sampled decode for thumbnails. `C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\ocr\ReceiptImageStore.java:28`.
- **Room DB with two entities.** `receipts` and `bank_transactions` in `receipt_tracker.db`, v1, `fallbackToDestructiveMigration`. `C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\data\AppDatabase.java:9`.
- **Receipt list with match-status chip + thumbnail.** Tapping a row opens the edit screen. `C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\ui\receipts\ReceiptListActivity.java:65`.
- **Bank transaction add/list screen.** Date picker, description, amount, optional account. Edit-in-place by tapping a row. `C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\ui\transactions\AddTransactionActivity.java:21` and `TransactionListActivity.java:23`.
- **Greedy matching engine.** See "Matching engine" below.
- **Match screen with three sections.** Unmatched-receipts-with-suggestions, unmatched bank transactions, and a matched list. Tapping a suggestion confirms with a fresh UUID; tapping "Unmatch" on a row clears both sides. `C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\ui\match\MatchActivity.java:29`.
- **Export to user-picked folder via SAF.** `OpenDocumentTree` returns a tree URI; permission is persisted with `takePersistableUriPermission`. Two modes: "export newest" and "export all". Writes `.jpg` (if a photo was saved) and `.json` per receipt. `C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\ui\export\ExportActivity.java:23` and `C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\export\ReceiptExporter.java:41`.
- **Main screen with live counts.** Big buttons for the five primary flows, plus counts of receipts/transactions refreshed automatically via `LiveData`. `C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\ui\MainActivity.java:21`.
- **USD currency / date formatting helper.** `MoneyUtils.format` produces `$23.45`; `MoneyUtils.formatDate` produces `MMM d, yyyy`. `C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\util\MoneyUtils.java:8`.
- **Shared executor pool.** `AppExecutors` (disk + main) for all one-shot DB work and post-back. `C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\util\AppExecutors.java:1`.
- **One-click build & install.** `build-and-install.ps1` boots the gradle wrapper if needed, builds, picks an adb device, installs, launches. Wired up as the default VS Code build task (Ctrl+Shift+B) via `.vscode/tasks.json`.

## Architecture in 60 seconds

The runtime flow is: `MainActivity` -> `ScanReceiptActivity` (camera or gallery) -> `ReceiptOcr` + `ReceiptParser` produce a `ParsedReceipt` -> `EditReceiptActivity` lets the user confirm/correct -> save writes a `Receipt` row plus a JPEG in the app's private `files/receipts/`. Separately, the user adds `BankTransaction` rows by hand. `MatchActivity` reads both tables, calls `MatchEngine.suggest` to pair unmatched rows, and on tap writes the same UUID into both tables. `ExportActivity` walks every `Receipt` and lets `ReceiptExporter` write a `.jpg` (if present) and a `.json` sidecar to the SAF-picked folder (typically the `androidreciepts/` subfolder of this project).

```
+--------------------------------------------------------------+
|  UI (Activities)                                              |
|  MainActivity, Scan / Edit / List, AddTx, ListTx, Match,     |
|  Export                                                       |
+--------------------------------------------------------------+
                       |                |                |
                       v                v                v
+-------------------+  +---------------+  +--------------+
|  ocr/             |  |  match/       |  |  export/     |
|  ReceiptOcr       |  |  MatchEngine  |  |  ReceiptExp. |
|  ReceiptParser    |  |               |  |              |
|  ReceiptImageStore|  |               |  |              |
+-------------------+  +---------------+  +--------------+
                       |                |
                       v                v
+--------------------------------------------------------------+
|  data/   Room: AppDatabase  ReceiptDao  BankTransactionDao    |
|          entities: Receipt, BankTransaction (matchGroupId)    |
+--------------------------------------------------------------+
                       |
                       v
+--------------------------------------------------------------+
|  Storage  /data/data/com.example.receipttracker/              |
|           databases/receipt_tracker.db                        |
|           files/receipts/receipt_<ts>.jpg                     |
|           SAF tree (user-picked, anywhere on device)          |
+--------------------------------------------------------------+
```

## Data model

Two Room entities, both keyed by autoincrement `id` (not stable across reinstalls). The shared column that ties them together is `matchGroupId`, a nullable UUID string. When both rows hold the same non-null UUID, the match is live. Either side can be cleared independently.

`Receipt` (`C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\data\Receipt.java`):

```java
@Entity(tableName = "receipts")
public class Receipt {
    @PrimaryKey(autoGenerate = true) public long id;
    @Nullable public String merchant;
    public long dateMillis;          // epoch millis at local midnight
    public double amount;            // always positive
    @Nullable public String photoPath; // absolute path inside filesDir/receipts
    @Nullable public String rawText;   // full OCR text, for debugging
    @Nullable public String notes;
    public long createdAt;
    @Nullable public String matchGroupId;
}
```

`BankTransaction` (`C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\data\BankTransaction.java`):

```java
@Entity(tableName = "bank_transactions")
public class BankTransaction {
    @PrimaryKey(autoGenerate = true) public long id;
    public String description;
    public long dateMillis;
    public double amount;
    @Nullable public String account;
    public long createdAt;
    @Nullable public String matchGroupId;
}
```

`matchGroupId` is generated fresh on every confirmed match in `MatchActivity.confirmMatch` (`C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\ui\match\MatchActivity.java:206`) via `UUID.randomUUID().toString()`. Both DAOs expose `setMatchGroup` and `clearMatchGroup` queries so the cascade on delete (`EditReceiptActivity.java:209` -> `:222`) doesn't have to mutate the `BankTransaction` row through a `getById`+`update` round trip.

Why a UUID and not just a foreign key? Because there is no real "receipt owns transaction" relationship - either side can be added or deleted first, and the link needs to survive independently. The UUID is the cheapest way to do a soft, symmetric pointer that serializes cleanly into the exported JSON.

## OCR / parsing approach

`ReceiptOcr` runs ML Kit's on-device Latin recognizer over a downscaled `Bitmap` and returns the full text as a single newline-joined string. Latency is bounded by a 20-second `CountDownLatch`; on timeout it returns `null` and the scan screen toasts "Couldn't read any text from that image".

`ReceiptParser` (`C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\ocr\ReceiptParser.java`) then does three independent best-effort passes on the raw text:

- **Merchant.** Look at the first six non-empty lines, skip "junk" lines (matching `receipt|invoice|order|tax id|vat|tel|phone|address|street|store|register|terminal|trans|server|table|check|cashier|thank you|www|http|email|@`), skip lines that look like a price, skip very short or very long lines. Return the first such line that has at least 2 uppercase characters (receipts almost always print the merchant in caps). Fall back to "first non-junk line near the top" if nothing has enough caps. The heuristic is bias-on-the-side-of-extracting: a slightly wrong merchant is fine because the user fixes it.
- **Date.** Try five regexes in order: `YYYY-MM-DD`, `MM/DD/YYYY` (or `DD/MM/YYYY` inferred when first number > 12), `MM-DD-YYYY`, `5 Jan 2024`, `Jan 5, 2024`. Two-digit years are mapped to 20xx. The result is normalized to local-midnight epoch millis. No timezone intelligence.
- **Total.** Pass 1: scan every line that contains a keyword (`total`, `grand total`, `amount due`, `balance due`, `amount`, `sum`, `to pay`); on each such line take the **last** decimal number - so `Subtotal 5.00 / Tax 0.40 / Total 5.40` yields 5.40. Pass 2 (fallback): the largest decimal anywhere on the receipt. `extractNumbers` strips `$`, `,`, and spaces, and discards integers without a decimal point so quantity lines like `1 $5.00` don't pollute the answer.

Known failure modes, in priority order:

- If a receipt has a promotional "TOTAL SAVINGS" line before a real "GRAND TOTAL", the parser may pick the savings figure. The keyword list does not exclude "savings" / "discount" - easy to add.
- Date disambiguation is purely numeric (`a > 12` implies day-first). US receipts almost always use `MM/DD/YYYY`, but if the date is the 13th-31st the parser flips correctly; if the date is the 1st-12th and the locale is European, it will be wrong.
- Merchants with non-Latin scripts (Chinese, Korean, etc.) are not handled because the ML Kit model is Latin-only. The bundled model is `text-recognition:16.0.0`.
- Money without a decimal point (e.g. an integer-priced item like `5`) is dropped by `extractNumbers`. That's deliberate for totals but means cash-register-only receipts with no decimals anywhere are unscannable.
- Item-level lines can leak into the "amount" fallback when no total keyword is present - the second pass just picks the largest number. The user must correct it.

## Matching engine

`MatchEngine` (`C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\match\MatchEngine.java`) is a pure-function, no-I/O greedy suggester. Constants are:

```java
public static final double AMOUNT_TOLERANCE = 1.00;
public static final long   DATE_TOLERANCE_MS = 3L * 24 * 60 * 60 * 1000;
```

Algorithm:

1. Take the list of currently-unmatched receipts and the list of currently-unmatched bank transactions.
2. Sort receipts by amount **descending** so the most specific (largest, fewest candidates) get first pick.
3. For each receipt, scan all transactions and keep those where `|amount delta| <= AMOUNT_TOLERANCE` AND `|date delta| <= DATE_TOLERANCE_MS`. Amounts are compared in integer cents (`toCents` rounds) to dodge float comparison drift.
4. Score candidates by smallest amount delta, breaking ties on smallest date delta. Mark the chosen transaction as taken so no other receipt is suggested against it in the same pass.
5. Each receipt that finds no candidate is returned as a `Suggestion` with `best == null`; the UI renders it as "No close bank transaction found".

Greedy means this is not a globally optimal matching. With 5 receipts and 5 transactions, it's usually fine; with 50/50 there will be cases where a better cross-pairing exists. The user can always unlink and re-link manually.

The match screen renders three sections in this order: "Unmatched receipts" (with suggestions inline), "Unmatched bank transactions" (a flat list), and "Matched" (pairs grouped by `matchGroupId`). Confirming a suggestion writes the new UUID into both tables. The matched list is built by bucketing receipts by `matchGroupId` and looking up the bank-transaction side in a `HashMap` - a missing bank transaction (shouldn't happen, but possible if you delete the tx row) just gets dropped silently.

## Export pipeline

`ReceiptExporter.export(ctx, folder, receipt)` (`C:\Development\FullStack\androidscanner\app\src\main\java\com\example\receipttracker\export\ReceiptExporter.java:41`) writes, into the SAF `DocumentFile` tree the user picked:

- `<base>.jpg` - a copy of the original photo at `receipt.photoPath`, if present (MIME `image/jpeg`).
- `<base>.json` - the parsed metadata + raw OCR text + match linkage (MIME `application/json`).

The base name is `receipt_<id, zero-padded to 6>_<yyyyMMdd>` of the receipt's `dateMillis` (see `baseNameFor` at `:35`). Re-running the export overwrites both files - that's the supported way to refresh the JSON after a merchant correction.

The matching export landing now lives at `C:\Development\FullStack\androidscanner\androidreciepts\` (merged into the project on 2026-08-06; previously was a sibling at `C:\Development\FullStack\androidreciepts\`). The user picks that subfolder on the device via SAF (Drive / Dropbox / SD card / `Documents/androidreciepts/` synced to the dev box / manual `adb pull`). The JSON schema is documented in `C:\Development\FullStack\androidscanner\androidreciepts\schema.md`; the implementation lives in `ReceiptExporter.toJson` at `:67`.

A sample receipt sidecar (one of two included in `C:\Development\FullStack\androidscanner\androidreciepts\sample\`):

```json
{
  "id": 1,
  "merchant": "WHOLE FOODS MARKET",
  "amount": 23.45,
  "amountFormatted": "$23.45",
  "dateMillis": 1754006400000,
  "date": "Aug 1, 2026",
  "notes": null,
  "matchGroupId": "8f4e2a10-1b3c-4d4e-9f5a-7b6c8d9e0f1a",
  "image": "receipt_000001_20260801.jpg",
  "rawText": "WHOLE FOODS MARKET\n123 Main St\n(555) 123-4567\n\nBananas              1.99\nAlmond Milk          3.49\nBread               4.50\nChicken Salad      11.49\nTax                  1.98\n\nTOTAL              $23.45\n\n08/01/2026  12:34 PM\nThank you!\n",
  "createdAt": 1754006800000
}
```

`ExportActivity` exposes two buttons: "Export newest" (single receipt, used to dry-run the SAF permission) and "Export ALL" (bulk, reports `ok` / `failed` counts). It does not currently show a per-file error log on failure - failures are silently counted.

## File tree

Abbreviated - the full tree has 22 `.java` files, 23 resource XMLs, and the gradle/VS Code plumbing, listed below only by package / folder.

```
C:\Development\FullStack\androidscanner\
|-- README.md
|-- STATE.md
|-- build.gradle                       # AGP 8.2.2, Gradle 8.4
|-- settings.gradle
|-- gradle.properties
|-- gradle\wrapper\gradle-wrapper.properties    # jar not committed (see Build & run)
|-- .gitignore
|-- .vscode\tasks.json                 # one-click build & install task
|-- build-and-install.ps1              # bootstrap + assembleDebug + adb install + launch
|-- app\
|   |-- build.gradle                   # deps: Room 2.6.1, ML Kit text-recognition 16.0.0,
|   |                                 #        CameraX 1.3.1, Material 1.11, lifecycle-livedata 2.6.2
|   |-- proguard-rules.pro
|   `-- src\main\
|       |-- AndroidManifest.xml        # CAMERA perm; 7 activities declared
|       |-- res\
|       |   |-- layout\                # 13 layouts (8 activities + 5 items)
|       |   |-- values\                # strings, colors, themes
|       |   |-- drawable\              # launcher background/foreground
|       |   |-- mipmap-anydpi-v26\     # launcher icons
|       |   `-- xml\                   # backup + data-extraction rules
|       `-- java\com\example\receipttracker\
|           |-- ReceiptTrackerApp.java        # Application; warms up DB
|           |-- data\
|           |   |-- AppDatabase.java          # Room v1, fallbackToDestructiveMigration
|           |   |-- Receipt.java              # @Entity receipts
|           |   |-- ReceiptDao.java           # sync + LiveData query variants
|           |   |-- BankTransaction.java      # @Entity bank_transactions
|           |   `-- BankTransactionDao.java   # sync + LiveData + countLive()
|           |-- ocr\
|           |   |-- ReceiptOcr.java           # ML Kit wrapper (sync via CountDownLatch)
|           |   |-- ReceiptParser.java       # merchant/date/amount heuristics
|           |   |-- ParsedReceipt.java       # DTO
|           |   `-- ReceiptImageStore.java   # <filesDir>/receipts/, sample decode
|           |-- match\
|           |   `-- MatchEngine.java         # AMOUNT_TOLERANCE, DATE_TOLERANCE_MS
|           |-- export\
|           |   `-- ReceiptExporter.java     # SAF writer (jpg + json)
|           |-- util\
|           |   |-- MoneyUtils.java          # USD format, "MMM d, yyyy" date format
|           |   `-- AppExecutors.java        # diskIO() + mainThread() shared pool
|           `-- ui\
|               |-- MainActivity.java
|               |-- scan\ScanReceiptActivity.java
|               |-- receipts\
|               |   |-- ReceiptListActivity.java
|               |   `-- EditReceiptActivity.java
|               |-- transactions\
|               |   |-- TransactionListActivity.java
|               |   `-- AddTransactionActivity.java
|               |-- match\MatchActivity.java
|               `-- export\ExportActivity.java
`-- androidreciepts\                   # merged export landing (was sibling before 2026-08-06)
    |-- README.md
    |-- schema.md
    `-- sample\
        |-- receipt_000001_20260801.json
        `-- receipt_000002_20260803.json
```

## Build & run

Concrete setup that matches the on-disk environment:

- **JDK.** `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` (Temurin 21.0.11+10, LTS). Set `JAVA_HOME` to this path before invoking Gradle. Note: `app\build.gradle` declares `sourceCompatibility JavaVersion.VERSION_17` - JDK 21 can cross-compile to 17 bytecode, so the toolchain combo works. AGP 8.2.2 runs on JDK 17 or 21.
- **Android SDK.** `C:\Users\nicho\AppData\Local\Android\Sdk`. Set `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) to this. Required installed components: `platforms/android-34` (project `compileSdk`), `build-tools/34.0.0` (or any 34+), `platform-tools` (for `adb`), and `emulator` + `system-images` for the AVD. All present on this machine.
- **Emulator.** AVD is `OpenFPS_API36` (API 36, google_apis) at `C:\Users\nicho\.android\avd\OpenFPS_API36.avd`. The project targets `targetSdk 34` so it installs and runs fine on this image.
- **Gradle wrapper.** `gradle/wrapper/gradle-wrapper.jar` is intentionally not committed. The first run of `build-and-install.ps1` fetches Gradle 8.4 from `services.gradle.org` into `%TEMP%\rt-gradle-bootstrap`, runs `gradle wrapper` from it to lay down the proper wrapper jar (and `gradlew.bat`), then cleans up. After that, the standard `./gradlew assembleDebug` works.

**One click from VS Code.** Open the project folder in VS Code, press **Ctrl+Shift+B** (or use **Terminal -> Run Build Task**). The default task `Build & Install on Device` runs the script. Output streams into the shared terminal panel. The second task `Clean (gradlew clean)` is also wired up for when you want a fresh `app\build/`.

**Or from a terminal:**

```bat
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot
set ANDROID_SDK_ROOT=C:\Users\nicho\AppData\Local\Android\Sdk
cd C:\Development\FullStack\androidscanner
.\gradlew.bat assembleDebug
```

The APK lands at `C:\Development\FullStack\androidscanner\app\build\outputs\apk\debug\app-debug.apk`.

Install on the emulator:

```bat
%ANDROID_SDK_ROOT%\emulator\emulator.exe -avd OpenFPS_API36
adb install -r C:\Development\FullStack\androidscanner\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.receipttracker/.ui.MainActivity
```

To pull the on-device DB for inspection:

```bat
adb exec-out run-as com.example.receipttracker cat databases/receipt_tracker.db > rt.db
```

## Known limitations / honest gaps

- **`gradle-wrapper.jar` is not in the repo.** First `gradlew` invocation will fail until `build-and-install.ps1` (or Android Studio's first sync, or `gradle wrapper --gradle-version 8.4` from a system Gradle) fetches it. By design.
- **Latin-script OCR only.** `text-recognition:16.0.0` with `TextRecognizerOptions.DEFAULT_OPTIONS` - receipts in CJK, Cyrillic, Arabic, etc. will produce empty or garbled `rawText` and the parser will return `null` for everything.
- **US-centric parser and formatter.** `MoneyUtils` hard-codes `Locale.US` and USD currency. `ReceiptParser` only understands USD-style amounts and US-date-style formats. EU dates, GBP/EUR, and group-separator-locale variations (e.g. `1.234,56`) are not handled.
- **No CSV / OFX / QIF import for bank transactions.** Adding transactions is a manual form per row. The schema and the SAF flow are already there to bolt this on.
- **No multi-photo per receipt.** One JPEG per row. Adding a `receipt_photos` table is a 30-line Room migration.
- **No tests.** No `androidTest` or `test` source set in the project tree. The matching, OCR, and parser logic are all unit-testable and would benefit from coverage.
- **Greedy matching is not globally optimal.** Good enough for 5-10 unmatched pairs, not for 50+.
- **`MatchActivity.MatchedRow` loop drops orphan receipts.** If a `Receipt` has a `matchGroupId` whose `BankTransaction` was deleted, the receipt disappears from the matched list - there's no UI to "recover" it. Same for orphan transactions. The data is still in the DB, just not surfaced.
- **`EditReceiptActivity.deleteReceipt` does an O(n) scan over `bank_transactions`** to find the matching side of the match group. Fine at small N, ugly at 10k+. A `getByMatchGroup` DAO query would fix it.
- **No file-level error log on export.** `ExportActivity.exportAll` shows `ok` / `failed` counts but does not list which receipt failed or why. The exception is swallowed inside the loop.
- **`fallbackToDestructiveMigration` on the Room DB.** Schema version is hard-pinned to 1 with no migration path. Any future column add will wipe user data unless you write a `Migration`. Fine for v1, dangerous to forget later.
- **`targetSdk 34` on a new build in 2026.** Already a year behind current Play Store requirements. Worth bumping along with an AGP upgrade.
- **No proguard / R8 tuning in release.** `minifyEnabled false` in `app/build.gradle`. Fine for a debug-only project, would need work before publishing.
- **App icon is a placeholder vector.** `mipmap-anydpi-v26` is a stock adaptive icon (dark green with a white "receipt" glyph). Replace before publishing.
- **`build-and-install.ps1` silently swallows per-receipt export errors.** The `try/catch` in `exportAll` only counts failures; it doesn't collect which receipts failed. Add an `errorList` accumulator if you need that level of detail.

## Recommended next steps

1. ~~**Replace `AsyncTask` with coroutines or `Executor` + `LiveData`.**~~ **DONE** - landed via `AppExecutors` + Room `LiveData` queries; see "Known limitations" above.
2. **Add unit tests around `ReceiptParser` and `MatchEngine`.** Both are pure functions with well-defined inputs/outputs. A JUnit suite of ~30 receipt fixtures would catch regressions when heuristics change, and would make the failure-mode list above a lot more falsifiable. Add an `app/src/test` source set and a `testImplementation 'junit:junit:4.13.2'` line.
3. **Bank-statement import via SAF.** Same UI shape as Export: pick a file, stream-parse, preview, save. CSV first (cheap), OFX/OFXv2 second (XML). Reuses the same `BankTransactionDao` and the same `takePersistableUriPermission` plumbing already in `ExportActivity`. High value because hand-entering 30+ transactions is the current bottleneck.
4. **Bump the toolchain.** Update AGP, Gradle, `compileSdk`/`targetSdk`, and Android Studio. While doing it, write Room migration scaffolding (or at least set `exportSchema true` and check the JSON in) so the next schema change doesn't nuke local data. Update the README's JDK 17 note to JDK 21 while you're there.
5. **Multi-photo per receipt.** Add a `receipt_photos` table (1:N from `receipts`), let the edit screen attach a second shot (e.g. the back of a receipt for warranty info), and update the exporter to emit all photos with suffixed filenames (`receipt_<id>_<yyyyMMdd>_2.jpg`). The parser side stays the same.
