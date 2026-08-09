package com.example.receipttracker.ocr;


import androidx.annotation.Nullable;


import com.example.receipttracker.log.Logger;


import java.text.ParseException;

import java.text.SimpleDateFormat;

import java.util.Calendar;

import java.util.Date;

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


    private static final Pattern DATE_PATTERNS[] = new Pattern[] {
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
        long t0 = System.currentTimeMillis();

        ParsedReceipt out = new ParsedReceipt();

        if (rawText == null || rawText.trim().isEmpty()) {
            Logger.w("Parser", "parse() called with empty text");

            return out;
        }


        String[] lines = rawText.split("\\r?\\n");

        Logger.i("Parser", "Parsing " + lines.length + " lines, " + rawText.length() + " chars");


        out.merchant = guessMerchant(lines);

        if (out.merchant == null) {
            out.merchantPrediction = null;
        } else {
            out.merchantPrediction = MerchantClassifier.predict(out.merchant);
        }

        out.dateMillis = guessDate(rawText);

        out.amount = guessAmount(lines);

        out.rawText = rawText;


        long ms = System.currentTimeMillis() - t0;

        String predDisplay;

        if (out.merchantPrediction == null) {
            predDisplay = "(none)";
        } else {
            predDisplay = out.merchantPrediction.name;
        }

        Logger.i("Parser", "Result: merchant='" + out.merchant
                + "', merchantPred=" + predDisplay
                + ", dateMillis=" + out.dateMillis
                + ", amount=" + out.amount + "  (" + ms + "ms)");

        return out;
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


    private static int countUpper(String s) {
        int n = 0;

        for (int i = 0; i < s.length(); i++) {
            if (Character.isUpperCase(s.charAt(i))) n++;
        }

        return n;
    }


    private static String cleanLine(String s) {
        // Collapse internal whitespace.
        return s.replaceAll("\\s+", " ").trim();
    }


    // ---------- date ----------

    @Nullable
    private static Long guessDate(String text) {
        Logger.d("Parser", "Date: trying " + DATE_PATTERNS.length + " regex patterns");

        for (int i = 0; i < DATE_PATTERNS.length; i++) {
            Pattern p = DATE_PATTERNS[i];

            Matcher m = p.matcher(text);

            if (m.find()) {
                Long ts = tryParseDate(m);

                if (ts != null) {
                    Logger.i("Parser", "Date matched pattern #" + i + ": matched='"
                            + m.group() + "' -> millis=" + ts);

                    return ts;
                }
            }
        }

        Logger.w("Parser", "Date: no pattern matched");

        return null;
    }


    @Nullable
    private static Long tryParseDate(Matcher m) {
        try {
            int y, mo, d;

            String g0 = m.group(1);

            switch (m.groupCount()) {
                case 3:
                    String g1 = m.group(2);

                    String g2 = m.group(3);

                    // YYYY-MM-DD
                    if (g0.length() == 4 && isAllDigits(g0)) {
                        y = Integer.parseInt(g0);

                        mo = Integer.parseInt(g1);

                        d = Integer.parseInt(g2);
                    } else if (isMonthToken(g0)) {
                        // "5 Jan 2024" - day, month-name, year
                        d = Integer.parseInt(g0);

                        mo = monthIndex(g1);

                        y = normaliseYear(Integer.parseInt(g2));
                    } else if (isMonthToken(g1)) {
                        // "Jan 5, 2024" - month-name, day, year
                        mo = monthIndex(g0);

                        d = Integer.parseInt(g1);

                        y = normaliseYear(Integer.parseInt(g2));
                    } else {
                        // Assume MM/DD/YYYY
                        int a = Integer.parseInt(g0);

                        int b = Integer.parseInt(g1);

                        y = normaliseYear(Integer.parseInt(g2));

                        if (a > 12) { // clearly day-first
                            d = a; mo = b;
                        } else {
                            mo = a; d = b;
                        }
                    }

                    break;

                default:
                    return null;
            }

            return toMidnightMillis(y, mo, d);
        } catch (Exception e) {
            return null;
        }
    }


    private static boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }

        return !s.isEmpty();
    }


    private static boolean isMonthToken(String s) {
        return s != null && Pattern.compile("(?i)^(" + MONTHS + ")[a-z]*$").matcher(s).matches();
    }


    private static int monthIndex(String name) {
        String n = name.toLowerCase();

        if (n.startsWith("jan")) return 1;

        if (n.startsWith("feb")) return 2;

        if (n.startsWith("mar")) return 3;

        if (n.startsWith("apr")) return 4;

        if (n.startsWith("may")) return 5;

        if (n.startsWith("jun")) return 6;

        if (n.startsWith("jul")) return 7;

        if (n.startsWith("aug")) return 8;

        if (n.startsWith("sep")) return 9;

        if (n.startsWith("oct")) return 10;

        if (n.startsWith("nov")) return 11;

        if (n.startsWith("dec")) return 12;

        return 1;
    }


    private static int normaliseYear(int y) {
        if (y < 100) {
            return 2000 + y;
        }

        return y;
    }


    private static long toMidnightMillis(int y, int m, int d) {
        Calendar c = Calendar.getInstance(TimeZone.getDefault(), Locale.US);

        c.clear();

        c.set(y, m - 1, d, 0, 0, 0);

        return c.getTimeInMillis();
    }


    // ---------- amount ----------

    @Nullable
    private static Double guessAmount(String[] lines) {
        // Pass 1: lines containing a "total" keyword - take the LAST number on that line
        // (so "Subtotal 5.00 / Tax 0.40 / Total 5.40" yields 5.40).
        Logger.d("Parser", "Amount pass 1: scanning for 'total' keyword");

        double best = -1;

        boolean foundTotal = false;

        String winningLine = null;

        for (String raw : lines) {
            String line = raw.trim();

            if (line.isEmpty()) continue;

            if (!TOTAL_KEYWORD.matcher(line).find()) continue;

            List<String> nums = extractNumbers(line);

            if (nums.isEmpty()) {
                Logger.d("Parser", "  total-keyword line w/o number: '" + line + "'");

                continue;
            }

            double candidate = parseLastNumber(nums);

            Logger.d("Parser", "  total line='" + line + "' -> candidate=" + candidate
                    + " (from nums=" + nums + ")");

            if (candidate > 0 && (candidate > best || !foundTotal)) {
                best = candidate;

                foundTotal = true;

                winningLine = line;
            }
        }

        if (foundTotal) {
            Logger.i("Parser", "Amount chosen: " + best + " from line '" + winningLine + "'");

            return best;
        }


        // Pass 2: no total keyword - pick the largest decimal in the receipt.
        Logger.d("Parser", "Amount pass 2: no total-keyword match, falling back to largest decimal");

        double largest = -1;

        String largestLine = null;

        for (String raw : lines) {
            String line = raw.trim();

            for (String n : extractNumbers(line)) {
                try {
                    double v = Double.parseDouble(n);

                    if (v > largest) {
                        largest = v;

                        largestLine = line;
                    }
                } catch (NumberFormatException ignored) { }
            }
        }

        if (largest > 0) {
            Logger.i("Parser", "Amount chosen (fallback): " + largest + " from line '" + largestLine + "'");

            return largest;
        }

        Logger.w("Parser", "No amount detected at all");

        return null;
    }


    private static double parseLastNumber(List<String> nums) {
        return Double.parseDouble(nums.get(nums.size() - 1));
    }


    private static List<String> extractNumbers(String line) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();

        Matcher m = MONEY.matcher(line);
        while (m.find()) {
            String n = m.group().replace("$", "").replace(",", "").replace(" ", "").trim();

            if (n.isEmpty()) continue;

            // Drop lone "1" / "12" that often come from quantity lines like "1 $5.00".
            if (!n.contains(".")) continue;

            out.add(n);
        }

        return out;
    }


    // ---------- public: extract all numbers for the verifier ----------

    /** Recognised keywords for verifier cross-checks (lowercased). */
    private static final java.util.List<String> VERIFIER_KEYWORDS =
            java.util.Arrays.asList("subtotal", "tax", "tip", "total", "amount", "balance", "due");


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
    public static java.util.List<DetectedNumber> extractAllNumbers(String rawText) {
        java.util.List<DetectedNumber> out = new java.util.ArrayList<>();

        if (rawText == null || rawText.trim().isEmpty()) return out;

        String[] lines = rawText.split("\\r?\\n");

        String[] lineKeywords = new String[lines.length];

        boolean[] hasNumber = new boolean[lines.length];

        for (int i = 0; i < lines.length; i++) {
            hasNumber[i] = !extractNumbers(lines[i].trim()).isEmpty();
        }

        for (int i = 0; i < lines.length; i++) {
            if (!hasNumber[i]) continue;

            String line = lines[i].trim();

            // Already a keyword on this line? Done.
            if (detectVerifierKeyword(line) != null) continue;

            // Otherwise search the immediately adjacent non-blank lines.
            // Walk left first, then right — each direction stops at the
            // first non-blank line (whether it's a number or just text).
            String kw = nearestKeyword(lines, hasNumber, i, -1);

            if (kw == null) kw = nearestKeyword(lines, hasNumber, i, +1);

            if (kw != null) lineKeywords[i] = kw;
        }

        for (int i = 0; i < lines.length; i++) {
            if (!hasNumber[i]) continue;

            String line = lines[i].trim();

            // Same-line keyword wins over adjacent propagation.
            String own = detectVerifierKeyword(line);

            String keyword;

            if (own != null) {
                keyword = own;
            } else {
                keyword = lineKeywords[i];
            }

            List<String> nums = extractNumbers(line);

            for (String n : nums) {
                try {
                    double v = Double.parseDouble(n);

                    if (v > 0) {
                        out.add(new DetectedNumber(v, line, i, keyword));
                    }
                } catch (NumberFormatException ignored) { }
            }
        }

        Logger.i("Parser", "extractAllNumbers: " + out.size()
                + " numbers from " + lines.length + " lines");

        return out;
    }


    /**
     * Walks from index {@code start} in direction {@code dir} (+1 or -1)
     * looking for a keyword-only line. Skips blank lines, stops as soon
     * as it hits a non-blank, non-keyword line or another number line.
     */
    @Nullable
    private static String nearestKeyword(String[] lines, boolean[] hasNumber, int start, int dir) {
        int j = start + dir;
        while (j >= 0 && j < lines.length) {
            String neighbor = lines[j].trim();

            if (neighbor.isEmpty()) { j += dir; continue; }

            if (hasNumber[j]) return null;          // hit another number — stop

            String kw = detectVerifierKeyword(neighbor);

            if (kw != null) return kw;              // found a keyword line

            return null;                            // hit text, stop
        }

        return null;
    }


    @Nullable
    private static String detectVerifierKeyword(String line) {
        String lower = line.toLowerCase();

        for (String k : VERIFIER_KEYWORDS) {
            if (lower.contains(k)) return k;
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
    public static java.util.List<DetectedNumber> extractAllNumbersWithVisualSignals(
            @Nullable android.graphics.Bitmap bitmap,
            java.util.List<ReceiptOcr.OcrLine> ocrLines) {
        java.util.List<DetectedNumber> out = new java.util.ArrayList<>();

        if (ocrLines == null || ocrLines.isEmpty()) return out;


        int n = ocrLines.size();

        String[] lineTexts = new String[n];

        boolean[] hasNumber = new boolean[n];

        for (int i = 0; i < n; i++) {
            String t = ocrLines.get(i).text;

            if (t == null) {
                lineTexts[i] = "";
            } else {
                lineTexts[i] = t;
            }

            hasNumber[i] = !extractNumbers(lineTexts[i].trim()).isEmpty();
        }

        // Same keyword-propagation as the text-only pass.
        String[] lineKeywords = new String[n];

        for (int i = 0; i < n; i++) {
            if (!hasNumber[i]) continue;

            String line = lineTexts[i].trim();

            if (detectVerifierKeyword(line) != null) continue;

            String kw = nearestKeyword(lineTexts, hasNumber, i, -1);

            if (kw == null) kw = nearestKeyword(lineTexts, hasNumber, i, +1);

            if (kw != null) lineKeywords[i] = kw;
        }


        int emphasisedCount = 0;

        for (int i = 0; i < n; i++) {
            if (!hasNumber[i]) continue;

            String line = lineTexts[i].trim();

            String own = detectVerifierKeyword(line);

            String keyword;

            if (own != null) {
                keyword = own;
            } else {
                keyword = lineKeywords[i];
            }

            ReceiptOcr.OcrLine ocrLine = ocrLines.get(i);


            // Try element-level first.
            boolean usedElement = false;

            if (ocrLine.elements != null) {
                for (ReceiptOcr.OcrElement el : ocrLine.elements) {
                    String elText;

                    if (el.text == null) {
                        elText = "";
                    } else {
                        elText = el.text.trim();
                    }

                    java.util.List<String> nums = extractNumbers(elText);

                    for (String num : nums) {
                        try {
                            double v = Double.parseDouble(num);

                            if (v <= 0) continue;

                            VisualSignalDetector.Signals sig = (bitmap != null && el.bbox != null)
                                    ? VisualSignalDetector.detect(bitmap, el.bbox)
                                    : new VisualSignalDetector.Signals(0f, 0f);

                            out.add(new DetectedNumber(v, line, i, keyword,
                                    sig.highlightScore, sig.circleScore, el.bbox));

                            if (sig.isEmphasised()) emphasisedCount++;

                            usedElement = true;
                        } catch (NumberFormatException ignored) { }
                    }
                }
            }

            if (usedElement) continue;


            // Fallback: line-level bbox for every money match on this
            // line. This handles the case where ML Kit emitted the
            // whole line as one element.
            java.util.List<String> nums = extractNumbers(line);

            VisualSignalDetector.Signals sig = (bitmap != null && ocrLine.bbox != null)
                    ? VisualSignalDetector.detect(bitmap, ocrLine.bbox)
                    : new VisualSignalDetector.Signals(0f, 0f);

            for (String num : nums) {
                try {
                    double v = Double.parseDouble(num);

                    if (v <= 0) continue;

                    out.add(new DetectedNumber(v, line, i, keyword,
                            sig.highlightScore, sig.circleScore, ocrLine.bbox));

                    if (sig.isEmphasised()) emphasisedCount++;
                } catch (NumberFormatException ignored) { }
            }
        }


        Logger.i("Parser", "extractAllNumbersWithVisualSignals: " + out.size()
                + " numbers from " + n + " lines; " + emphasisedCount + " visually emphasised");

        return out;
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


        // Priority 0: a visually-emphasised number (yellow highlighter
        // or pen circle). This is the strongest "user marked this on
        // purpose" signal we have — the user went out of their way
        // to draw attention to it. Return the highest-emphasis one.
        DetectedNumber emphasised = pickMostEmphasised(numbers);

        if (emphasised != null) {
            Logger.i("Parser", "pickCircledCandidate: VISUALLY EMPHASISED -> $"
                    + emphasised.value + " (line " + emphasised.lineIndex
                    + " hl=" + String.format("%.2f", emphasised.highlightScore)
                    + " cr=" + String.format("%.2f", emphasised.circleScore) + ")");

            return emphasised;
        }


        // Priority 1: a number on a TOTAL keyword line. Real receipts
        // almost always print "TOTAL  47.83" with the total number
        // on the same line, and that's also what a user circles 90%
        // of the time.
        for (DetectedNumber n : numbers) {
            if (n.keyword != null && isTotalKeyword(n.keyword)) {
                Logger.i("Parser", "pickCircledCandidate: TOTAL-line match -> $"
                        + n.value + " (line " + n.lineIndex + " kw=" + n.keyword + ")");

                return n;
            }
        }


        // Priority 2: the largest number in the bottom half of the
        // receipt (the totals block lives at the bottom in 99% of
        // printed receipts). Excludes non-decimal noise like
        // "TxnID: 348332" by only looking at lines that pass
        // PriceClassifier (handled in the verifier pass).
        int maxLine = 0;

        for (DetectedNumber n : numbers) {
            if (n.lineIndex > maxLine) maxLine = n.lineIndex;
        }

        int half = maxLine / 2;

        DetectedNumber bottomLargest = null;

        for (DetectedNumber n : numbers) {
            if (n.lineIndex < half) continue;

            if (bottomLargest == null || n.value > bottomLargest.value) {
                bottomLargest = n;
            }
        }

        if (bottomLargest != null) {
            Logger.i("Parser", "pickCircledCandidate: bottom-half largest -> $"
                    + bottomLargest.value + " (line " + bottomLargest.lineIndex + ")");

            return bottomLargest;
        }


        // Priority 3: the largest number on the whole receipt. Last
        // resort — gives the user a defensible default.
        DetectedNumber largest = numbers.get(0);

        for (DetectedNumber n : numbers) {
            if (n.value > largest.value) largest = n;
        }

        Logger.i("Parser", "pickCircledCandidate: receipt-wide largest -> $"
                + largest.value + " (line " + largest.lineIndex + ")");

        return largest;
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

        for (DetectedNumber n : numbers) {
            if (!n.isVisuallyEmphasised()) continue;

            float score = (float) (n.highlightScore * 0.66 + n.circleScore * 0.34);

            // Tiny tie-breaker: numbers on a TOTAL keyword line win
            // ties so we don't pick an emphasised subtotal over a
            // non-emphasised total.
            if (n.keyword != null && isTotalKeyword(n.keyword)) score += 0.01f;

            if (best == null || score > bestScore) {
                best = n;

                bestScore = score;
            }
        }

        return best;
    }


    private static boolean isTotalKeyword(String kw) {
        return "total".equals(kw) || "amount".equals(kw)
                || "balance".equals(kw) || "due".equals(kw)
                || "sum".equals(kw) || "to pay".equals(kw);
    }
}
