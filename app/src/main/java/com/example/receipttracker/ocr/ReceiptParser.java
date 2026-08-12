package com.example.receipttracker.ocr;


import androidx.annotation.Nullable;


import com.example.receipttracker.log.Logger;


import java.util.ArrayList;

import java.util.Arrays;

import java.util.Calendar;

import java.util.List;

import java.util.Locale;

import java.util.TimeZone;

import java.util.regex.Matcher;

import java.util.regex.Pattern;


/**
 * Heuristic parser that takes a blob of OCR text and tries to pull out the
 * merchant, date and total. All fields are best-effort.
 *
 * It is intentionally permissive and bias-on-the-side-of-extracting:
 * missing a value is worse than occasionally getting it slightly wrong,
 * because the user can correct anything in the edit screen.
 */
public final class ReceiptParser {

    /** Keywords that mark a line as likely containing the receipt total. */
    private static final Pattern TOTAL_KEYWORD = Pattern.compile(
            "(?i)\\b(grand\\s*total|total|amount\\s*due|balance\\s*due|amount|sum|to\\s*pay)\\b"
    );


    /** "Junk" lines we never want to mistake for a merchant. */
    private static final Pattern JUNK_LINE = Pattern.compile(
            "(?i)^\\s*(receipt|invoice|order|tax\\s*id|vat|#|tel|phone|address|street|store|register|terminal|trans|server|table|check|cashier|thank\\s*you|www\\.|http|email|@).*"
    );


    /** USD-style money: optional $, digits, optional comma separators, dot decimal. */
    private static final Pattern MONEY = Pattern.compile(
            "\\$?\\s*\\d{1,3}(?:[,\\s]\\d{3})*(?:\\.\\d{2})|\\$?\\s*\\d+\\.\\d{2}"
    );


    private static final Pattern[] DATE_PATTERNS = new Pattern[] {
            // 2024-01-05, 2024/1/5
            Pattern.compile("\\b(20\\d{2})[-/](\\d{1,2})[-/](\\d{1,2})\\b"),
            // 01/05/2024, 1/5/24
            Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})/(\\d{2,4})\\b"),
            // 01-05-2024
            Pattern.compile("\\b(\\d{1,2})-(\\d{1,2})-(\\d{2,4})\\b"),
            // 5 Jan 2024 / Jan 5, 2024
            Pattern.compile("\\b(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+(\\d{2,4})\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+(\\d{1,2}),?\\s+(\\d{2,4})\\b", Pattern.CASE_INSENSITIVE),
    };


    private static final String MONTHS =
            "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec";


    private ReceiptParser() {}


    public static ParsedReceipt parse(String rawText) {
        final long startMillis = System.currentTimeMillis();

        if (rawText == null || rawText.trim().isEmpty()) {
            Logger.w("Parser", "parse() called with empty text");
            return ParsedReceipt.EMPTY;
        }


        final String[] lines = rawText.split("\\r?\\n");
        Logger.i("Parser", "Parsing " + lines.length + " lines, " + rawText.length() + " chars");


        final String merchantGuess = guessMerchant(lines);

        final MerchantClassifier.Prediction merchantPrediction;
        if (merchantGuess == null) {
            merchantPrediction = null;
        } else {
            merchantPrediction = MerchantClassifier.predict(merchantGuess);
        }

        final ParsedReceipt parsed = ParsedReceipt.EMPTY
                .withMerchant(merchantGuess)
                .withMerchantPrediction(merchantPrediction)
                .withDateMillis(guessDate(rawText))
                .withAmount(guessAmount(lines))
                .withRawText(rawText);


        final long elapsedMs = System.currentTimeMillis() - startMillis;

        final String predDisplay;
        if (parsed.merchantPrediction == null) {
            predDisplay = "(none)";
        } else {
            predDisplay = parsed.merchantPrediction.name;
        }

        Logger.i("Parser", "Result: merchant='" + parsed.merchant
                + "', merchantPred=" + predDisplay
                + ", dateMillis=" + parsed.dateMillis
                + ", amount=" + parsed.amount + "  (" + elapsedMs + "ms)");

        return parsed;
    }


    // ---------- merchant ----------

    @Nullable
    private static String guessMerchant(String[] lines) {
        // Take the first 6 non-empty lines that aren't pure junk.
        Logger.d("Parser", "Merchant: scanning top 6 non-empty lines");

        int inspected = 0;

        for (String raw : lines) {
            String line = raw.trim();

            if (line.isEmpty()) continue;

            inspected++;

            if (inspected > 6) break;

            if (JUNK_LINE.matcher(line).matches()) continue;

            if (MONEY.matcher(line).find()) continue; // skip lines that are just a price

            if (line.length() < 2) continue;

            if (line.length() > 60) continue;

            // Prefer the line that has the most uppercase letters - receipts usually
            // render the merchant in caps.
            if (countUpper(line) >= 2) {
                Logger.i("Parser", "Merchant chosen (caps): '" + cleanLine(line) + "'");

                return cleanLine(line);
            }
        }

        // Fallback: any non-junk line near the top.
        Logger.d("Parser", "Merchant: no all-caps line found, falling back to first non-junk");

        for (String raw : lines) {
            String line = raw.trim();

            if (line.isEmpty()) continue;

            if (JUNK_LINE.matcher(line).matches()) continue;

            if (MONEY.matcher(line).find()) continue;

            if (line.length() < 2) continue;

            Logger.i("Parser", "Merchant chosen (fallback): '" + cleanLine(line) + "'");

            return cleanLine(line);
        }

        Logger.w("Parser", "Merchant: nothing usable in the top of the receipt");

        return null;
    }


    private static int countUpper(String input) {
        int upperCount = 0;

        for (int index = 0; index < input.length(); index++) {
            if (Character.isUpperCase(input.charAt(index))) upperCount++;
        }

        return upperCount;
    }


    private static String cleanLine(String input) {
        // Collapse internal whitespace.
        return input.replaceAll("\\s+", " ").trim();
    }


    // ---------- date ----------

    @Nullable
    private static Long guessDate(String text) {
        Logger.d("Parser", "Date: trying " + DATE_PATTERNS.length + " regex patterns");

        for (int patternIndex = 0; patternIndex < DATE_PATTERNS.length; patternIndex++) {
            final Pattern pattern = DATE_PATTERNS[patternIndex];

            final Matcher matcher = pattern.matcher(text);

            if (matcher.find()) {
                final Long timestamp = tryParseDate(matcher);

                if (timestamp != null) {
                    Logger.i("Parser", "Date matched pattern #" + patternIndex + ": matched='"
                            + matcher.group() + "' -> millis=" + timestamp);

                    return timestamp;
                }
            }
        }

        Logger.w("Parser", "Date: no pattern matched");

        return null;
    }


    @Nullable
    private static Long tryParseDate(Matcher matcher) {
        try {
            final int year;

            final int month;

            final int day;

            final String firstGroup = matcher.group(1);

            switch (matcher.groupCount()) {
                case 3:
                    final String secondGroup = matcher.group(2);

                    final String thirdGroup = matcher.group(3);

                    // YYYY-MM-DD
                    if (firstGroup.length() == 4 && isAllDigits(firstGroup)) {
                        year = Integer.parseInt(firstGroup);

                        month = Integer.parseInt(secondGroup);

                        day = Integer.parseInt(thirdGroup);
                    } else if (isMonthToken(firstGroup)) {
                        // "Jan 5, 2024" - month-name, day, year (pattern 5)
                        month = monthIndex(firstGroup);

                        day = Integer.parseInt(secondGroup);

                        year = normaliseYear(Integer.parseInt(thirdGroup));
                    } else if (isMonthToken(secondGroup)) {
                        // "5 Jan 2024" - day, month-name, year (pattern 4)
                        day = Integer.parseInt(firstGroup);

                        month = monthIndex(secondGroup);

                        year = normaliseYear(Integer.parseInt(thirdGroup));
                    } else {
                        // Assume MM/DD/YYYY
                        final int firstValue = Integer.parseInt(firstGroup);

                        final int secondValue = Integer.parseInt(secondGroup);

                        year = normaliseYear(Integer.parseInt(thirdGroup));

                        if (firstValue > 12) { // clearly day-first
                            day = firstValue;
                            month = secondValue;
                        } else {
                            month = firstValue;
                            day = secondValue;
                        }
                    }

                    break;

                default:
                    return null;
            }

            return toMidnightMillis(year, month, day);
        } catch (Exception e) {
            return null;
        }
    }


    private static boolean isAllDigits(String input) {
        for (int index = 0; index < input.length(); index++) {
            if (!Character.isDigit(input.charAt(index))) return false;
        }

        return !input.isEmpty();
    }


    private static boolean isMonthToken(String token) {
        return token != null && Pattern.compile("(?i)^(" + MONTHS + ")[a-z]*$").matcher(token).matches();
    }


    private static int monthIndex(String name) {
        final String lowerName = name.toLowerCase();

        if (lowerName.startsWith("jan")) return 1;

        if (lowerName.startsWith("feb")) return 2;

        if (lowerName.startsWith("mar")) return 3;

        if (lowerName.startsWith("apr")) return 4;

        if (lowerName.startsWith("may")) return 5;

        if (lowerName.startsWith("jun")) return 6;

        if (lowerName.startsWith("jul")) return 7;

        if (lowerName.startsWith("aug")) return 8;

        if (lowerName.startsWith("sep")) return 9;

        if (lowerName.startsWith("oct")) return 10;

        if (lowerName.startsWith("nov")) return 11;

        if (lowerName.startsWith("dec")) return 12;

        return 1;
    }


    private static int normaliseYear(int year) {
        if (year < 100) {
            return 2000 + year;
        }

        return year;
    }


    private static long toMidnightMillis(int year, int month, int day) {
        final Calendar calendar = Calendar.getInstance(TimeZone.getDefault(), Locale.US);

        calendar.clear();

        calendar.set(year, month - 1, day, 0, 0, 0);

        return calendar.getTimeInMillis();
    }


    // ---------- amount ----------

    @Nullable
    private static Double guessAmount(String[] lines) {
        // Pass 1: lines containing a "total" keyword - take the LAST number on that line
        // (so "Subtotal 5.00 / Tax 0.40 / Total 5.40" yields 5.40).
        Logger.d("Parser", "Amount pass 1: scanning for 'total' keyword");

        double bestAmount = -1;

        boolean foundTotal = false;

        String winningLine = null;

        for (String raw : lines) {
            String line = raw.trim();

            if (line.isEmpty()) continue;

            if (!TOTAL_KEYWORD.matcher(line).find()) continue;

            final List<String> numbers = extractNumbers(line);

            if (numbers.isEmpty()) {
                Logger.d("Parser", "  total-keyword line w/o number: '" + line + "'");

                continue;
            }

            final double candidate = parseLastNumber(numbers);

            Logger.d("Parser", "  total line='" + line + "' -> candidate=" + candidate
                    + " (from numbers=" + numbers + ")");

            if (candidate > 0 && (candidate > bestAmount || !foundTotal)) {
                bestAmount = candidate;

                foundTotal = true;

                winningLine = line;
            }
        }

        if (foundTotal) {
            Logger.i("Parser", "Amount chosen: " + bestAmount + " from line '" + winningLine + "'");

            return bestAmount;
        }


        // Pass 2: no total keyword - pick the largest decimal in the receipt.
        Logger.d("Parser", "Amount pass 2: no total-keyword match, falling back to largest decimal");

        double largestAmount = -1;

        String largestLine = null;

        for (String raw : lines) {
            String line = raw.trim();

            for (String numberText : extractNumbers(line)) {
                try {
                    final double value = Double.parseDouble(numberText);

                    if (value > largestAmount) {
                        largestAmount = value;

                        largestLine = line;
                    }
                } catch (NumberFormatException ignored) { }
            }
        }

        if (largestAmount > 0) {
            Logger.i("Parser", "Amount chosen (fallback): " + largestAmount + " from line '" + largestLine + "'");

            return largestAmount;
        }

        Logger.w("Parser", "No amount detected at all");

        return null;
    }


    private static double parseLastNumber(List<String> numbers) {
        return Double.parseDouble(numbers.get(numbers.size() - 1));
    }


    private static List<String> extractNumbers(String line) {
        final List<String> matches = new ArrayList<>();

        final Matcher matcher = MONEY.matcher(line);
        while (matcher.find()) {
            final String rawNumber = matcher.group().replace("$", "").replace(",", "").replace(" ", "").trim();

            if (rawNumber.isEmpty()) continue;

            // Drop lone "1" / "12" that often come from quantity lines like "1 $5.00".
            if (!rawNumber.contains(".")) continue;

            matches.add(rawNumber);
        }

        return matches;
    }


    // ---------- public: extract all numbers for the verifier ----------

    /** Recognised keywords for verifier cross-checks (lowercased). */
    private static final List<String> VERIFIER_KEYWORDS =
            Arrays.asList("subtotal", "tax", "tip", "total", "amount", "balance", "due");


    /**
     * Walks the entire OCR text and emits one DetectedNumber per
     * money-shaped match, each tagged with the line it came from, the
     * line index, and (if the line contains a verifier keyword) the
     * keyword itself. Used by TotalVerifier.
     *
     * <p>Keyword association: a "subtotal / tax / tip / total" keyword
     * on a line that has no number of its own is associated with the
     * nearest number line in either direction. The search stops as soon
     * as it hits a non-blank, non-keyword line OR another number line,
     * which keeps a stray "TOTAL" header on a credit-card slip from
     * leaking down to a "Version 1.5.20" line 14 rows later.</p>
     */
    public static List<DetectedNumber> extractAllNumbers(String rawText) {
        final List<DetectedNumber> numbers = new ArrayList<>();

        if (rawText == null || rawText.trim().isEmpty()) return numbers;

        final String[] lines = rawText.split("\\r?\\n");

        final String[] lineKeywords = new String[lines.length];

        final boolean[] hasNumber = new boolean[lines.length];

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            hasNumber[lineIndex] = !extractNumbers(lines[lineIndex].trim()).isEmpty();
        }

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            if (!hasNumber[lineIndex]) continue;

            final String line = lines[lineIndex].trim();

            // Already a keyword on this line? Done.
            if (detectVerifierKeyword(line) != null) continue;

            // Otherwise search the immediately adjacent non-blank lines.
            // Walk left first, then right — each direction stops at the
            // first non-blank line (whether it's a number or just text).
            String keyword = nearestKeyword(lines, hasNumber, lineIndex, -1);

            if (keyword == null) keyword = nearestKeyword(lines, hasNumber, lineIndex, +1);

            if (keyword != null) lineKeywords[lineIndex] = keyword;
        }

        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
            if (!hasNumber[lineIndex]) continue;

            final String line = lines[lineIndex].trim();

            // Same-line keyword wins over adjacent propagation.
            final String ownKeyword = detectVerifierKeyword(line);

            final String effectiveKeyword;
            if (ownKeyword != null) {
                effectiveKeyword = ownKeyword;
            } else {
                effectiveKeyword = lineKeywords[lineIndex];
            }

            final List<String> numberStrings = extractNumbers(line);

            for (String numberString : numberStrings) {
                try {
                    final double value = Double.parseDouble(numberString);

                    if (value > 0) {
                        numbers.add(new DetectedNumber(value, line, lineIndex, effectiveKeyword));
                    }
                } catch (NumberFormatException ignored) { }
            }
        }

        Logger.i("Parser", "extractAllNumbers: " + numbers.size()
                + " numbers from " + lines.length + " lines");

        return numbers;
    }


    /**
     * Walks from index {@code start} in direction {@code dir} (+1 or -1)
     * looking for a keyword-only line. Skips blank lines, stops as soon
     * as it hits a non-blank, non-keyword line or another number line.
     */
    @Nullable
    private static String nearestKeyword(String[] lines, boolean[] hasNumber, int start, int dir) {
        int cursor = start + dir;
        while (cursor >= 0 && cursor < lines.length) {
            final String neighbor = lines[cursor].trim();

            if (neighbor.isEmpty()) {
                cursor += dir;
                continue;
            }

            if (hasNumber[cursor]) return null;          // hit another number — stop

            final String keyword = detectVerifierKeyword(neighbor);

            if (keyword != null) return keyword;          // found a keyword line

            return null;                                 // hit text, stop
        }

        return null;
    }


    @Nullable
    private static String detectVerifierKeyword(String line) {
        final String lowered = line.toLowerCase();

        for (String candidate : VERIFIER_KEYWORDS) {
            if (lowered.contains(candidate)) return candidate;
        }

        return null;
    }


    // ---------- public: extract all numbers WITH visual signals ----------

    /**
     * Same logic as {@link #extractAllNumbers(String)}, but walks the
     * structured OCR lines (with bounding boxes) so each emitted
     * DetectedNumber can carry its visual-signal scores (yellow
     * highlighter, pen circle) sampled from the source bitmap.
     *
     * <p>Pairing rule: for each line that contains money-shaped
     * matches, we look through that line's OCR elements and pair each
     * money match to the element whose text contains the value. The
     * element's bounding box drives the visual-signal detector. If a
     * line has no element-level matches (the whole line was emitted as
     * one element by ML Kit) we fall back to the line's bounding box
     * for every money match on that line.</p>
     *
     * <p>If {@code bitmap} is null, the visual-signal scores default
     * to 0.0 (the auto-pick still works, just without the highlighted
     * priority).</p>
     */
    public static List<DetectedNumber> extractAllNumbersWithVisualSignals(
            @Nullable android.graphics.Bitmap bitmap,
            List<ReceiptOcr.OcrLine> ocrLines) {
        final List<DetectedNumber> numbers = new ArrayList<>();

        if (ocrLines == null || ocrLines.isEmpty()) return numbers;


        final int lineCount = ocrLines.size();

        final String[] lineTexts = new String[lineCount];

        final boolean[] hasNumber = new boolean[lineCount];

        for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
            final String lineText = ocrLines.get(lineIndex).text;

            if (lineText == null) {
                lineTexts[lineIndex] = "";
            } else {
                lineTexts[lineIndex] = lineText;
            }

            hasNumber[lineIndex] = !extractNumbers(lineTexts[lineIndex].trim()).isEmpty();
        }

        // Same keyword-propagation as the text-only pass.
        final String[] lineKeywords = new String[lineCount];

        for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
            if (!hasNumber[lineIndex]) continue;

            final String line = lineTexts[lineIndex].trim();

            if (detectVerifierKeyword(line) != null) continue;

            String keyword = nearestKeyword(lineTexts, hasNumber, lineIndex, -1);

            if (keyword == null) keyword = nearestKeyword(lineTexts, hasNumber, lineIndex, +1);

            if (keyword != null) lineKeywords[lineIndex] = keyword;
        }


        int emphasisedCount = 0;

        for (int lineIndex = 0; lineIndex < lineCount; lineIndex++) {
            if (!hasNumber[lineIndex]) continue;

            final String line = lineTexts[lineIndex].trim();

            final String ownKeyword = detectVerifierKeyword(line);

            final String effectiveKeyword;
            if (ownKeyword != null) {
                effectiveKeyword = ownKeyword;
            } else {
                effectiveKeyword = lineKeywords[lineIndex];
            }

            final ReceiptOcr.OcrLine ocrLine = ocrLines.get(lineIndex);


            // Try element-level first.
            boolean usedElement = false;

            if (ocrLine.elements != null) {
                for (ReceiptOcr.OcrElement el : ocrLine.elements) {
                    final String elementText;
                    if (el.text == null) {
                        elementText = "";
                    } else {
                        elementText = el.text.trim();
                    }

                    final List<String> numberStrings = extractNumbers(elementText);

                    for (String numberString : numberStrings) {
                        try {
                            final double value = Double.parseDouble(numberString);

                            if (value <= 0) continue;

                            final VisualSignalDetector.Signals signals = (bitmap != null && el.bbox != null)
                                    ? VisualSignalDetector.detect(bitmap, el.bbox)
                                    : new VisualSignalDetector.Signals(0f, 0f);

                            numbers.add(new DetectedNumber(value, line, lineIndex, effectiveKeyword,
                                    signals.highlightScore, signals.circleScore, el.bbox));

                            if (signals.isEmphasised()) emphasisedCount++;

                            usedElement = true;
                        } catch (NumberFormatException ignored) { }
                    }
                }
            }

            if (usedElement) continue;


            // Fallback: line-level bbox for every money match on this
            // line. This handles the case where ML Kit emitted the
            // whole line as one element.
            final List<String> numberStrings = extractNumbers(line);

            final VisualSignalDetector.Signals signals = (bitmap != null && ocrLine.bbox != null)
                    ? VisualSignalDetector.detect(bitmap, ocrLine.bbox)
                    : new VisualSignalDetector.Signals(0f, 0f);

            for (String numberString : numberStrings) {
                try {
                    final double value = Double.parseDouble(numberString);

                    if (value <= 0) continue;

                    numbers.add(new DetectedNumber(value, line, lineIndex, effectiveKeyword,
                            signals.highlightScore, signals.circleScore, ocrLine.bbox));

                    if (signals.isEmphasised()) emphasisedCount++;
                } catch (NumberFormatException ignored) { }
            }
        }


        Logger.i("Parser", "extractAllNumbersWithVisualSignals: " + numbers.size()
                + " numbers from " + lineCount + " lines; " + emphasisedCount + " visually emphasised");

        return numbers;
    }


    // ---------- auto-pick "the circled total" ----------

    /**
     * Pick the most likely "circled" number on a receipt. This is what
     * the user would have circled with their pen — so the heuristic
     * favours the number on a TOTAL-keyword line first, then falls
     * back to the largest number in the bottom half (the part of the
     * receipt that contains the totals block on most layouts), then
     * the largest number on the whole receipt.
     *
     * <p>Returns null if the input list is empty.</p>
     *
     * <p>Why this exists: the user shouldn't have to re-pick the total
     * the OCR almost certainly got right. We treat the OCR's first
     * guess as the default and let the user correct it.</p>
     *
     * <p>If any number has been visually emphasised (yellow highlight
     * or pen circle), it gets a *strong* boost — those signals are
     * worth more than all the text heuristics combined, because a
     * human deliberately marked that number for the OCR to find.</p>
     */
    @Nullable
    public static DetectedNumber pickCircledCandidate(List<DetectedNumber> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            Logger.w("Parser", "pickCircledCandidate: no numbers to choose from");

            return null;
        }

        // Category filter: every number on the receipt is classified
        // into a NumberCategory (TOTAL, SUBTOTAL, TAX, PERCENTAGE,
        // DATE, PHONE, AUTH_CODE, etc.). Only TOTAL, SUBTOTAL, and
        // LINE_ITEM are candidates for "the total" — a tax percentage
        // like 9.25% or a date like 12/25/24 used to win because the
        // old code picked the largest number with a decimal, which
        // is exactly the wrong heuristic.
        final List<DetectedNumber> totalCandidates = new ArrayList<>();
        for (DetectedNumber candidate : numbers) {
            final NumberCategory category = candidate.classify();
            if (category == NumberCategory.TOTAL
                    || category == NumberCategory.SUBTOTAL
                    || category == NumberCategory.LINE_ITEM) {
                totalCandidates.add(candidate);
            }
        }

        if (totalCandidates.isEmpty()) {
            Logger.w("Parser", "pickCircledCandidate: no TOTAL/SUBTOTAL/LINE_ITEM candidates after category filter");

            return null;
        }

        Logger.i("Parser", "pickCircledCandidate: " + totalCandidates.size()
                + "/" + numbers.size() + " candidates passed category filter");

        // Priority 0: a visually-emphasised number (yellow highlighter
        // or pen circle). This is the strongest "user marked this on
        // purpose" signal we have — the user went out of their way
        // to draw attention to it. Return the highest-emphasis one.
        final DetectedNumber emphasised = pickMostEmphasised(totalCandidates);

        if (emphasised != null) {
            Logger.i("Parser", "pickCircledCandidate: VISUALLY EMPHASISED -> $"
                    + emphasised.value + " (line " + emphasised.lineIndex
                    + " hl=" + String.format("%.2f", emphasised.highlightScore)
                    + " cr=" + String.format("%.2f", emphasised.circleScore) + ")");

            return emphasised;
        }

        // Priority 1: a number classified as TOTAL.
        for (DetectedNumber candidate : totalCandidates) {
            if (candidate.classify() == NumberCategory.TOTAL) {
                Logger.i("Parser", "pickCircledCandidate: TOTAL-category match -> $"
                        + candidate.value + " (line " + candidate.lineIndex
                        + " kw=" + candidate.keyword + ")");

                return candidate;
            }
        }

        // Priority 1b: a number on a legacy TOTAL keyword line (the
        // keyword field is still used by the parser pass; classify()
        // would also catch this, but we keep the explicit fast-path
        // for safety).
        for (DetectedNumber candidate : totalCandidates) {
            if (candidate.keyword != null && isTotalKeyword(candidate.keyword)) {
                Logger.i("Parser", "pickCircledCandidate: TOTAL-keyword match -> $"
                        + candidate.value + " (line " + candidate.lineIndex + " kw=" + candidate.keyword + ")");

                return candidate;
            }
        }

        // Priority 2: a number classified as SUBTOTAL.
        for (DetectedNumber candidate : totalCandidates) {
            if (candidate.classify() == NumberCategory.SUBTOTAL) {
                Logger.i("Parser", "pickCircledCandidate: SUBTOTAL-category match -> $"
                        + candidate.value + " (line " + candidate.lineIndex + ")");

                return candidate;
            }
        }

        // Priority 3: the largest amount in the bottom half of the receipt.
        // The bottom half is where the totals block lives on 99% of printed
        // receipts. No decimal-vs-integer filter here — the category
        // classifier has already removed AUTH_CODE/QUANTITY/YEAR for us,
        // and the IEEE-754 round-trip of $50.00 → 50.0 makes a value-shape
        // check unreliable (8.00 == 8.0 in Java math).
        int maxLine = 0;

        for (DetectedNumber candidate : totalCandidates) {
            if (candidate.lineIndex > maxLine) maxLine = candidate.lineIndex;
        }

        final int halfIndex = maxLine / 2;

        DetectedNumber bottomLargest = null;

        for (DetectedNumber candidate : totalCandidates) {
            if (candidate.lineIndex < halfIndex) continue;
            if (bottomLargest == null || candidate.value > bottomLargest.value) {
                bottomLargest = candidate;
            }
        }

        if (bottomLargest != null) {
            Logger.i("Parser", "pickCircledCandidate: bottom-half largest -> $"
                    + bottomLargest.value + " (line " + bottomLargest.lineIndex + ")");

            return bottomLargest;
        }

        // Priority 4: the largest amount anywhere on the receipt.
        // Last resort when nothing else qualifies.
        DetectedNumber largest = null;

        for (DetectedNumber candidate : totalCandidates) {
            if (largest == null || candidate.value > largest.value) {
                largest = candidate;
            }
        }

        // Priority 5: absolute largest among total candidates (in case
        // every candidate is an integer for some reason).
        if (largest == null) {
            for (DetectedNumber candidate : totalCandidates) {
                if (largest == null || candidate.value > largest.value) {
                    largest = candidate;
                }
            }
        }

        if (largest != null) {
            Logger.i("Parser", "pickCircledCandidate: receipt-wide largest -> $"
                    + largest.value + " (line " + largest.lineIndex + ")");

            return largest;
        }

        return null;
    }


    /**
     * Picks the number with the highest combined visual-signal score
     * across the list. Returns null if nothing crosses the per-signal
     * threshold (highlights need 0.20, circles 0.25). Among ties, the
     * one closer to a TOTAL keyword wins, then the higher-value one.
     */
    @Nullable
    private static DetectedNumber pickMostEmphasised(List<DetectedNumber> numbers) {
        DetectedNumber best = null;

        float bestScore = 0f;

        for (DetectedNumber candidate : numbers) {
            if (!candidate.isVisuallyEmphasised()) continue;

            float combinedScore = (float) (candidate.highlightScore * 0.66 + candidate.circleScore * 0.34);

            // Tiny tie-breaker: numbers on a TOTAL keyword line win
            // ties so we don't pick an emphasised subtotal over a
            // non-emphasised total.
            if (candidate.keyword != null && isTotalKeyword(candidate.keyword)) combinedScore += 0.01f;

            if (best == null || combinedScore > bestScore) {
                best = candidate;

                bestScore = combinedScore;
            }
        }

        return best;
    }


    private static boolean isTotalKeyword(String keyword) {
        return "total".equals(keyword) || "amount".equals(keyword)
                || "balance".equals(keyword) || "due".equals(keyword)
                || "sum".equals(keyword) || "to pay".equals(keyword);
    }
}
