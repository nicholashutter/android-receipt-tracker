# Improving the total scanner — ideas report

> **Status:** brainstorming / ideas dump — not an implementation plan. Read end-to-end, then cherry-pick what to ship first.
> **Scope:** classification, text transcription, comprehension of the receipt as a whole.
> **Grounded in:** the current code as of August 2026, after the C-style refactor and the unit-test bank landed.

---

## 1. The pipeline as it stands today

A typical receipt scan goes through:

1. **Capture** — CameraX → a JPEG on disk.
2. **OCR** — `ReceiptOcr.recognizeWithBoxes(bitmap)` runs ML Kit Latin text recognition on-device and emits `OcrLine` / `OcrElement` records (line text + per-element bounding boxes).
3. **Number extraction** — `ReceiptParser.extractAllNumbersWithVisualSignals(bitmap, lines)` walks the structured OCR, pairs each money-shaped match to the OCR element whose text contains it, and runs `VisualSignalDetector.detect(bitmap, bbox)` on that element's bounding box to get a yellow-highlighter score and a pen-circle score.
4. **Category classification** — each detected number is routed through `NumberCategory.classify()` (12 categories: TOTAL, SUBTOTAL, LINE_ITEM, TAX, TIP, DISCOUNT, PERCENTAGE, DATE, PHONE, AUTH_CODE, QUANTITY, YEAR, OTHER). Rule-based: keyword + value-shape + line-text matches. Excludes PERCENTAGE / DATE / PHONE / AUTH_CODE / etc. from the auto-pick candidate pool.
5. **Auto-pick** — `ReceiptParser.pickCircledCandidate` falls back through visual emphasis → TOTAL-keyword line → bottom-half largest → whole-receipt largest, restricted to TOTAL/SUBTOTAL/LINE_ITEM candidates.
6. **UI** — `EditReceiptActivity` pre-fills the amount with the auto-pick value and lets the user re-pick from the full list (sorted by category + value desc) or type a manual override.

The two-stage classifier (`PriceClassifier` + `LinearLearner`) and the `TotalVerifier` ensemble are still in `match/` for the `TestPipelineActivity` debug screen and unit tests, but the editor doesn't invoke them. The previous "re-pick dialog" was a verifier-driven flow; the new one is a straightforward pick-list with no verification on top.

---

## 2. Known weak spots (from the refactor + test bank)

These are real findings, not speculation — surfaced by code review and the new test suite:

- **`ReceiptParser.tryParseDate` is broken for month-name dates** — the case logic for both `"5 Jan 2024"` and `"Jan 5, 2024"` throws on `Integer.parseInt("Jan")`; two tests in `ReceiptParserTest` are `@Disabled` to document this. (`app/src/main/java/com/example/receipttracker/ocr/ReceiptParser.java:tryParseDate`)
- **"Ensemble" is deterministic** — `LogisticRegression.trainOneEpoch` rotates training data by `hyperParams.epochs % trainingData.size()` per epoch, so re-running the model gives the same result. The 10 ensemble runs are not a real ensemble. (`app/src/main/java/com/example/receipttracker/match/LogisticRegression.java:119`)
- **Visual signals use a hand-rolled pixel ratio** — `VisualSignalDetector` is a yellow-hue + dark-ring heuristic with hard-coded thresholds (R≥0.78, G≥0.70, B≤0.40, dark luma ≤0.35, ring-margin 0.30). It misfires on logo text, watermark paper, and any partial circle that doesn't match the ring-vs-core ratio. (`app/src/main/java/com/example/receipttracker/ocr/VisualSignalDetector.java:51-76`)
- **No confidence calibration** — sigmoid outputs are reported as "confidence" but they are not calibrated probabilities. "70% confidence" doesn't mean "we're right 70% of the time."
- **Tiny training set** — `PriceClassifier.TRAINING_DATA` has ~30 examples, `LinearLearner.TRAINING_DATA` has ~20. Each visual-signal example exists at most twice.
- **No online learning** — every user re-pick (the "Re-pick" dialog in `EditReceiptActivity`) is thrown away after the form is saved. The most valuable training signal in the app.
- **No image quality gate** — we run the full OCR pipeline on blurry / dark / tilted photos and present the user with garbage, no warning.
- **Single-currency, single-locale** — hard-coded to USD and `en_US`. A €-denominated receipt would parse as a number with garbage symbols.
- **Line items are extracted and discarded** — `extractAllNumbers` produces all the line items (Bananas $1.99, Apples $2.50) but the verifier only looks at candidate totals. The item list is a free consistency check we're not using.
- **Receipt structure is ignored** — every line is treated independently. Real receipts have a layout (header / items / modifiers / total / footer) that we never learn.

---

## 3. Ideas

### 3.1 OCR / text transcription

#### 3.1.1 Preprocess before OCR
Deskew, denoise, contrast-normalize, adaptive-threshold binarize. ML Kit Latin v1 is sensitive to rotation > 2° and low-contrast photos.

- *Why:* even 5° of tilt drops ML Kit accuracy significantly. Deskewing is a few lines of OpenCV (or a small Java port).
- *Cost:* ~1 day for deskew + binarize, ~3-4 days for a full pipeline.
- *Risk:* over-processing can erase faint thermal-print text. Use a "do no harm" pre-check: if the image is already high-contrast, skip preprocessing.

#### 3.1.2 Use ML Kit's `DocumentScanner` API
It does perspective correction + enhancement in one call. Replaces the current "OCR whatever the user photographed" with "OCR the document, after ML Kit has straightened and contrast-stretched it."

- *Why:* receipt photos are almost always taken at an angle. A perspective-corrected input is the single biggest accuracy win you can get.
- *Cost:* ~1 day. It's a one-API call.
- *Risk:* ML Kit's scanner sometimes mis-detects the document boundary on busy backgrounds (a dark wallet, a textured table). Always fall back to the raw image.

#### 3.1.3 Image quality gate
Detect blurry / dark / small / tilted photos *before* running OCR, and warn the user with a "this might not scan well — try again?" toast.

- Blur: Laplacian variance. Below 100 = too blurry.
- Brightness: mean pixel intensity. Below 40 = too dark.
- Tilt: Hough line angle vs. horizontal. > 5° = too tilted.
- Size: long edge < 800px = too small.

- *Why:* a 4-second OCR run that returns garbage is a worse experience than a 0.5-second "please retake" prompt.
- *Cost:* ~1 day. No new dependencies.
- *Risk:* false positives on legitimately hard-to-photograph receipts (very long, very narrow). Soft warning, not a hard block.

#### 3.1.4 Multi-pass OCR with merge
Run ML Kit twice with different settings (e.g. default Latin + slanted-text mode) and merge. Or run once on the full image, once on the bottom-half crop, and keep the version that produced more confident lines.

- *Why:* different OCR settings catch different error patterns. The merge is free.
- *Cost:* 2x OCR latency (~3-4 sec total). Acceptable since we're not on the hot path.
- *Risk:* the merge logic is annoying to get right. Start with "use whichever pass produced the higher total-confidence sum" — that alone is a 5-10% win.

#### 3.1.5 Bundle `eng.traineddata` properly
The comment in `app/build.gradle:85-92` says to drop the file into `app/src/main/assets/tessdata/`. Right now that directory doesn't exist and the Tesseract path is dead. Either:

- Bundle the file in the APK (cost: ~22 MB APK growth, one-line config).
- Download it on first run from your own server (cost: ~1 day, ongoing server).
- Drop Tesseract entirely and rely on ML Kit + visual-signal fallback (cost: 0, accuracy hit on handwritten totals).

- *Why:* either commit to the fallback or remove it. The current state is "claimed in a comment, never tested."
- *Cost:* depends on which path. Bundle = 0 days. Download = 1 day. Remove = 0 days.

#### 3.1.6 Per-element confidence in parsing
ML Kit's `Text.Element` carries a `confidence` field. Currently we ignore it. Use it as a feature for the stage-1 classifier — a number with OCR confidence 0.95 is more likely a real price than one with 0.4.

- *Why:* the model has no idea which OCR tokens are reliable. Adding "OCR confidence" as a 12th feature is one line in `LinearLearner`.
- *Cost:* ~2 hours.
- *Risk:* low. ML Kit's confidence is well-calibrated within a single model version.

#### 3.1.7 Optional cloud-OCR backend
Google Cloud Vision API and Document AI for receipts are substantially better than ML Kit on real-world receipts. Free tier: 1000 requests/month, then ~$1.50 per 1000. The "receipt parser" model in Document AI is purpose-trained and returns structured fields (merchant, total, tax, line items) directly.

- *Why:* ceiling on local accuracy is maybe 85%. Cloud is closer to 95%.
- *Cost:* integration ~3-5 days. Ongoing: requires user consent + per-call cost.
- *Risk:* privacy. Receipts may contain PII (names on credit-card slips, last 4 of card). The consent flow needs to be honest about what gets uploaded.

### 3.2 Visual signal detection

#### 3.2.1 Real circle detection
Replace the ring-vs-core pixel ratio in `VisualSignalDetector` with a real circle/ellipse detector. Options:
- **Hough circle transform** (OpenCV / boofcv) — robust to partial circles, handles ink bleed.
- **RANSAC ellipse fitting** — better for non-circular pen strokes.
- **Contour-based** — find closed contours in the dark-pixel mask, score each by circularity (`4π·area / perimeter²`).

- *Why:* the current heuristic misfires on logo text, watermark, partial circles, and any time the user drew a sloppy oval.
- *Cost:* ~3-5 days with boofcv. The library is Apache-2 and ~3 MB.
- *Risk:* performance. Hough is O(n³) on the pixel grid. Sample down aggressively (the current `DEFAULT_SAMPLE_STRIDE = 2` is a good start).

#### 3.2.2 More mark types
Receipts in the wild use more than highlighter + pen circle:
- **Underline** — the most common way to mark a total. Detect by looking for a horizontal line of dark pixels in the bottom 1/3 of the number's bbox.
- **Check mark / X** — common on credit-card slips. Detect by looking for a single-pixel diagonal stroke.
- **Different pen colors** — blue pen, red pen, black pen. The current yellow-only test misses these.

- *Why:* underlines are the single most common user mark, and we don't detect them at all.
- *Cost:* ~2 days per mark type.

#### 3.2.3 Confidence in the visual signal
Don't emit just a score. Emit `(score, confidence)`. A dim yellow smudge in a low-res photo shouldn't carry the same weight as a fresh highlighter mark on a 1200dpi scan.

- *Why:* the stage-2 model currently treats `highlightScore=0.3` the same regardless of whether the bbox was 200x60 (high confidence) or 30x12 (low confidence — too few pixels to be sure).
- *Cost:* ~1 day. Add a `Signals.emphasisConfidence` field, plumb it into `LinearLearner` features.

#### 3.2.4 Combine visual signals with text context
Currently the model is supposed to learn "circled + on a TOTAL line = high confidence" from training data, but it can't because we have so few visual-signal examples. Hand-craft the prior: a simple score table

```
emphasised + on TOTAL line: 0.95
emphasised + on SUBTOTAL line: 0.40
emphasised + no keyword: 0.65
not emphasised + on TOTAL line: 0.55
not emphasised + no keyword: 0.10
```

— then let the model adjust the weights.

- *Why:* better cold-start behavior when the user has only a few training examples.
- *Cost:* ~1 day.

### 3.3 Classification (stage 1, stage 2, ensemble)

#### 3.3.1 Real training data
The current training sets are ~30 hand-built synthetic examples. They cover the obvious cases (date, phone, auth code, subtotal, total) but miss:
- "12.5%" tax line where 12.5 is the rate, not a price
- "Qty 3 @ $5.99" quantity-prefixed prices
- "Subtotal 12.50 / Disc -1.00" with a discount
- "Change 5.00" on a credit-card slip
- Thermal-print artifacts (faded digits, dots, vertical streaks)

- *Why:* synthetic data is biased. Real-world receipts will surface failure modes the synthetic set doesn't cover.
- *How:* add a "this is wrong, here's the right answer" affordance (see 3.5.1) and log corrections to a `corrections.json` file on the device. Periodically copy them to a server (with consent) and grow the training set.
- *Cost:* ~1 day for the affordance + logging. Data collection is ongoing.
- *Risk:* if the synthetic data is removed too early, the model regresses before the real data arrives. Keep both, weight real higher as it grows.

#### 3.3.2 Online learning from user corrections
Every "Re-pick" dialog in `EditReceiptActivity` is a free, labeled training example. Today we throw it away.

- *Path A (cheap):* log the correction to a file, retrain offline overnight, ship the new model in the next app update.
- *Path B (better):* update the model on-device with online gradient descent after each correction. MobileNet-style.
- *Path C (best):* federated learning — corrections stay on-device, only gradient updates go to a server.

- *Why:* receipt formats change. CVS prints differently than Walmart. A model trained on 2020 receipts is worse on 2026 receipts. Online learning keeps it current.
- *Cost:* path A = ~1 day, path B = ~1 week, path C = ~1 month.

#### 3.3.3 Better features
The 11 features in `LinearLearner.FEATURE_NAMES` are good but sparse. Add:
- **Position features** — line index / total lines, character column (right-aligned prices are usually totals).
- **Font size** — totals are usually larger. Compare bbox height to the median.
- **Has currency on same line** — `line.indexOf('$')`.
- **Decimal place count** — totals have `.XX`, quantities don't.
- **Whitespace context** — totals usually have right-padding; quantity prices don't.
- **Proximity to "TOTAL" keyword** — within N lines up or down.
- **OCR confidence** (per 3.1.6).

- *Why:* the model is starved for signal. With 11 binary features it can only carve the space into 2^11 = 2048 regions, most of which are degenerate.
- *Cost:* ~2 days.

#### 3.3.4 Replace linear models with a small neural net or boosted trees
A 2-layer MLP (10 → 16 → 1) or a small XGBoost model handles non-linearities the linear model can't.

- *Why:* the relationships aren't linear. `highlightScore × hasTotalKeyword` should be a strong positive signal; the linear model can only learn the two independently.
- *Cost:* ~1 week for the data side (collect enough real examples to train), ~1 week for the model.
- *Risk:* on-device inference cost. A 10 → 16 → 1 MLP runs in <0.1ms on a modern phone. XGBoost is heavier (~1-2ms).

#### 3.3.5 Probability calibration
Sigmoid outputs aren't probabilities. Apply **Platt scaling** (1-parameter) or **isotonic regression** (no parameters) on a held-out set.

After calibration, "60% confidence" really means "we're right 60% of the time."

- *Why:* lets you set a real threshold for "needs review" UI badges. Right now you can't.
- *Cost:* ~1 day. The calibration itself is 5 lines of code; the hard part is having a held-out set.

#### 3.3.6 Real ensemble
Current "ensemble" rotates training data by `epochs % size` per epoch — fancy single run. Replace with one of:
- **Bootstrap aggregating** — train 10 models on different bootstrap samples, average predictions.
- **Feature bagging** — train 10 models on different feature subsets.
- **Image crop bagging** — run the model on the full image, on the bottom half, on the totals block, and average.

- *Why:* a real ensemble reduces variance. Useful when the model is over-confident on a single input.
- *Cost:* bootstrap = ~1 day. Crop bagging = ~2 days.

#### 3.3.7 Per-merchant models
A Whole Foods receipt has the total on a different line than a Costco receipt. Cluster receipts by merchant, train a per-cluster model, fall back to the global model for unknown merchants.

- *Why:* 80% of receipts come from 20% of merchants. Optimizing for the top 20% is high-leverage.
- *Cost:* ~2 weeks. The hard part is the clustering + persistence.

### 3.4 Comprehension (understanding the receipt)

#### 3.4.1 Receipt structure parsing
Real receipts have a layout: header (merchant + address) → items → modifiers (subtotal, tax, tip) → total → footer (thank you, auth code, return policy). Train a layout model that classifies each line as one of these.

- *Why:* currently every line is treated independently. Knowing "this is the total line" vs "this is a footer line" lets us rule out auth codes and transaction IDs without a per-line classifier.
- *Cost:* ~2 weeks. The model is easy; the labeled data is hard.

#### 3.4.2 Item-list sum validation
We extract line items (Bananas $1.99, Apples $2.50, Subtotal $4.49) but currently throw them away. Sum the items, compare to the parsed total, flag if they disagree by more than $0.10.

- *Why:* a real consistency check. If the items sum to $4.49 and the parsed total is $4.85, the user should be told.
- *Cost:* ~2-3 days.
- *Risk:* discounts, taxes, tips, and item-vs-modifier classification are all error-prone. Use the sum check as a soft signal, not a hard reject.

#### 3.4.3 Subtotal + tax + tip as a hard constraint
If the receipt has labeled sub+tax+tip, the total MUST equal their sum. Currently the verifier does this check, but only at the post-classification stage. Use it as a hard constraint: if a candidate disagrees with the sum by >$0.10, demote it.

- *Why:* this is the strongest signal we have. Receipts are arithmetically consistent.
- *Cost:* ~1 day. Most of the logic is already there.

#### 3.4.4 Cross-field validation
- Date should be in the past 12 months.
- Amount should be > 0 and < $10,000 (flag expensive items for review).
- Merchant should match the file name / camera-roll metadata if available.

- *Why:* cheap sanity checks catch obvious bugs.
- *Cost:* ~1 day.

#### 3.4.5 Multi-receipt detection
A photo of a stack of receipts currently OCR's all of them as one. Detect blank-row boundaries, split into N receipts, parse each.

- *Why:* users scan in batches. Forcing them to take N photos is friction.
- *Cost:* ~1 month. The split logic is the hard part.

#### 3.4.6 Multi-currency support
Hard-coded to USD and `en_US` today. A €-denominated receipt would parse as a number with garbage symbols. Detect currency from symbols (€, £, ¥) and formats, format the parsed amount back into the right currency.

- *Why:* if the app is used outside the US, this is a blocker.
- *Cost:* ~1 week.

### 3.5 User-feedback loop

#### 3.5.1 "Did we get this right?" affordance
After the user accepts a parsed receipt, show a small "Was this right?" prompt. Store corrections as training data (with consent).

- *Why:* the only way to grow the training set in the wild.
- *Cost:* ~2 days for the UI + persistence.

#### 3.5.2 Active learning
When the verifier's confidence is in the 0.5-0.7 range, that's the most-uncertain prediction. Queue those for user review; their answer teaches the most.

A swipe-card UI on the receipts list: "We think this was the total: $47.83. Yes / No / Other."

- *Why:* the model gets the most out of each user interaction.
- *Cost:* ~3 days.

#### 3.5.3 Per-user personalization
A user who scans gas-station receipts has different needs than one who scans restaurant receipts. Track per-user accuracy metrics, offer a per-user fine-tune (an on-device model updated with their corrections).

- *Why:* one-size-fits-all is suboptimal.
- *Cost:* ~2 weeks (model + persistence + UI).

### 3.6 Architecture

#### 3.6.1 Model versioning + A/B testing
Move from `static final TRAINING_DATA` to an on-device model file with a version. Ship updates via app updates. Log every prediction with the model version; compare in the wild.

- *Why:* today you can't tell which model produced a prediction.
- *Cost:* ~1 week.

#### 3.6.2 Pluggable backends
Replace `ReceiptOcr` (single static class) with an `OcrEngine` interface and three implementations:
- `MlKitEngine` (current behavior)
- `CloudVisionEngine` (optional, opt-in)
- `TesseractEngine` (handwritten fallback, when bundled)

- *Why:* lets you swap OCR backends without touching the parser. Lets users opt into cloud OCR for hard receipts.
- *Cost:* ~3 days.

#### 3.6.3 Confidence thresholds with "needs review" UI
Below 0.6 confidence, show a yellow "please double-check" badge on the amount field. Below 0.3, show red and require confirmation before save.

- *Why:* a single number ("confidence") without a UI hook is useless. The threshold surfaces the uncertainty.
- *Cost:* ~2 days.

#### 3.6.4 Trace logging
Every prediction: log all 11 features, the score, the alternative scores, the chosen total, the visual signals, the OCR confidence. Lets you debug "why did the model pick this?" after the fact.

- *Why:* today the logs say "verdict: total=47.83, confidence=0.85". That's not enough to debug a wrong answer.
- *Cost:* ~2 days.

#### 3.6.5 On-device evaluation harness
A "Test this on my receipts" debug screen. User picks 5 receipts, app runs the model on each, shows P(isTotal) for every number, expected vs. actual.

- *Why:* before shipping a new model version, run it against the user's previous scans. If accuracy drops, abort the rollout.
- *Cost:* ~1 week.

---

## 4. Top 5 to do first

If I had to pick 5, in priority order:

| # | Idea | Effort | Why now |
|---|------|--------|---------|
| 1 | **Fix `tryParseDate` month-name bug** (already in test bank) | 1 hour | It's a known bug, the test documents it, just fix the day/month assignment. |
| 2 | **Real training data via user corrections** (3.5.1 + 3.3.1) | 1 day | Everything else is bottlenecked on this. |
| 3 | **Item-list sum validation** (3.4.2) | 2-3 days | Free consistency check, uses data we already have, big accuracy win. |
| 4 | **Image quality gate** (3.1.3) | 1 day | 0.5s warning vs. 4s OCR + garbage output. |
| 5 | **Replace visual signal heuristic with real circle detection** (3.2.1) | 3-5 days | The current pixel sampler is the weakest link in the "user marked the total" path. |

If you have a longer runway:

| # | Idea | Effort |
|---|------|--------|
| 6 | **Calibrated probabilities** (3.3.5) | 1 day |
| 7 | **Per-element OCR confidence as a feature** (3.1.6) | 2 hours |
| 8 | **Real ensemble** (3.3.6) | 1-2 days |
| 9 | **Cloud OCR as optional backend** (3.1.7) | 3-5 days |
| 10 | **Receipt structure parsing** (3.4.1) | 2 weeks |

---

## 5. What I'd skip

A few ideas that look good on paper but are likely to disappoint:

- **End-to-end neural receipt parser** (e.g. Donut, LayoutLM, Google's Pix2Struct) — works great in benchmarks, painful in production: 100MB+ models, slow on cheap phones, hard to debug, no graceful degradation. Local OCR + small classifier is more honest about what we know.
- **Manual feature engineering forever** — at some point you need a real model. The 11 features in `LinearLearner` are at the limit of what a logistic regression can use.
- **Tesseract as the primary OCR** — it's 22MB, slower, and worse than ML Kit on Latin print. Keep it as a fallback, not the default.

---

## 6. References

- **ML Kit Text Recognition v2** — better than v1 for receipts because of a larger model.
- **Google Document AI — Form Parser / Receipt Parser** — purpose-trained cloud model, ~5% better than ML Kit on real-world data.
- **boofcv** — Apache-2 Java vision library. Has Hough circle transform, contour finding, ellipse fitting.
- **Platt scaling** — `sigmoid(A·score + B)` post-hoc calibration, 2 parameters fit on a held-out set.
- **Isotonic regression** — more flexible calibration, no parameters, non-decreasing step function.
- **Bagging (bootstrap aggregating)** — the standard ensemble trick; 10 bootstrap samples, average the predictions.
- **XGBoost / LightGBM** — gradient-boosted trees, ~1-2ms on-device for small models.
- **"Data Quality Matters"** — most of the gain in any ML pipeline comes from data, not model complexity. The 3.3.1 + 3.5.1 ideas are the highest-leverage by far.

---

*Author: generated from a code audit on 2026-08-10. Numbers and file:line references are current as of that date; re-verify before implementing.*
