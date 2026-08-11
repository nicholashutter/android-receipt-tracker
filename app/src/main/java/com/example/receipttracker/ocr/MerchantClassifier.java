package com.example.receipttracker.ocr;


import android.content.Context;


import androidx.annotation.NonNull;

import androidx.annotation.Nullable;

import androidx.annotation.WorkerThread;


import com.example.receipttracker.log.Logger;


import org.json.JSONArray;

import org.json.JSONException;

import org.json.JSONObject;


import java.io.BufferedReader;

import java.io.IOException;

import java.io.InputStream;

import java.io.InputStreamReader;

import java.nio.charset.StandardCharsets;

import java.util.ArrayList;

import java.util.Collections;

import java.util.Comparator;

import java.util.List;

import java.util.Locale;


/**
 * ML-style merchant classifier backed by a flat JSON file of common
 * merchants. Designed as the second half of the two-stage pipeline:
 *
 * <p>Stage 1 — guess a merchant from the raw OCR text via heuristics
 * (caps line near the top, etc.). Stage 2 — given that guess, ask
 * this classifier "which of our known merchants is this most likely
 * to be?" so we can normalise "WHOLE FOODS" / "WFM" / "Whole Foods
 * Market" all to "Whole Foods Market".</p>
 *
 * <p>Scoring is a soft logistic-regression-style aggregate:</p>
 * <pre>
 *   P(merchant = X | tokens) ∝ weight(X) *
 *       product_over_aliases(
 *           max_over_tokens(alias(X, j), 1 if substring-match else 0)
 *       )
 * </pre>
 *
 * <p>The flat file is loaded once and cached. Adding a new merchant
 * is a JSON edit, not a code change.</p>
 */
public final class MerchantClassifier {

    private static final String LOG_TAG = "MerchantClf";
    private static final String ASSET_FILE = "merchants.json";
    private static final String ASSET_KEY_MERCHANTS = "merchants";
    private static final String ASSET_KEY_NAME = "name";
    private static final String ASSET_KEY_WEIGHT = "weight";
    private static final String ASSET_KEY_CATEGORY = "category";
    private static final String ASSET_KEY_ALIASES = "aliases";
    private static final String EMPTY_NAME = "";
    private static final String DEFAULT_WEIGHT_STRING = "0.0";
    private static final String DEFAULT_CATEGORY = "";
    private static final double SOFT_MAX_TIE_BREAK_BONUS = 0.01;
    private static final double MIN_DENOMINATOR_FOR_GAP = 0.01;
    private static final double CONFIDENCE_WEIGHT_BASE = 0.55;
    private static final double CONFIDENCE_WEIGHT_GAP = 0.45;
    private static final double MAX_CONFIDENCE = 0.99;


    /** One scored prediction. */
    public static final class Prediction {
        @NonNull public final String name;
        @NonNull public final String category;
        public final double confidence;
        /** Pre-classifier (raw OCR) merchant string, in case the UI wants to show it. */
        @NonNull public final String source;

        public Prediction(@NonNull String name, @NonNull String category,
                          double confidence, @NonNull String source) {
            this.name = name;
            this.category = category;
            this.confidence = confidence;
            this.source = source;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.US, "%s (%s, %.0f%%)  <- '%s'",
                    name, category, confidence * 100, source);
        }
    }


    // MUTABLE: loaded once at startup (volatile).
    private static volatile List<Entry> entries = Collections.emptyList();
    // MUTABLE: loaded once at startup (volatile).
    private static volatile boolean loaded = false;


    private MerchantClassifier() {}


    public static final class Entry {
        @NonNull public final String name;
        @NonNull public final String category;
        public final double weight;
        @NonNull public final List<String> aliases; // already lowercased

        public Entry(@NonNull String name, @NonNull String category, double weight,
                     @NonNull List<String> aliases) {
            this.name = name;
            this.category = category;
            this.weight = weight;
            this.aliases = aliases;
        }
    }


    /**
     * Eagerly load the JSON from app assets. Safe to call multiple
     * times; only the first call does the I/O.
     */
    @WorkerThread
    public static synchronized void load(@NonNull Context appContext) {
        if (loaded) return;
        long t0 = System.currentTimeMillis();
        try (InputStream input = appContext.getAssets().open(ASSET_FILE);
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {

            final String jsonText = readAll(reader);
            final JSONObject root = new JSONObject(jsonText);
            final JSONArray arr = root.optJSONArray(ASSET_KEY_MERCHANTS);
            final List<Entry> loadedEntries = parseEntries(arr);
            entries = loadedEntries;
            loaded = true;
            final long ms = System.currentTimeMillis() - t0;
            Logger.i(LOG_TAG, "Loaded " + loadedEntries.size() + " merchants from "
                    + ASSET_FILE + " in " + ms + "ms");
        } catch (IOException | JSONException loadFailure) {
            Logger.e(LOG_TAG, "Failed to load " + ASSET_FILE, loadFailure);
            entries = Collections.emptyList();
            loaded = true; // don't keep retrying
        }
    }


    private static String readAll(BufferedReader reader) throws IOException {
        final StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        return builder.toString();
    }


    private static List<Entry> parseEntries(JSONArray arr) throws JSONException {
        final List<Entry> parsed = new ArrayList<>();
        if (arr == null) return parsed;

        for (int i = 0; i < arr.length(); i++) {
            final JSONObject entry = arr.getJSONObject(i);
            final String name = entry.optString(ASSET_KEY_NAME, EMPTY_NAME);
            if (name.isEmpty()) continue;

            final double weight = entry.optDouble(ASSET_KEY_WEIGHT, 0.0);
            final String category = entry.optString(ASSET_KEY_CATEGORY, DEFAULT_CATEGORY);
            final List<String> aliases = buildAliasList(entry, name);
            parsed.add(new Entry(name, category, weight, aliases));
        }
        return parsed;
    }


    private static List<String> buildAliasList(JSONObject entry, String canonicalName) throws JSONException {
        final List<String> aliases = new ArrayList<>();
        aliases.add(canonicalName.toLowerCase(Locale.US));

        final JSONArray aliasArr = entry.optJSONArray(ASSET_KEY_ALIASES);
        if (aliasArr == null) return aliases;

        for (int j = 0; j < aliasArr.length(); j++) {
            final String alias = aliasArr.optString(j, EMPTY_NAME).toLowerCase(Locale.US).trim();
            if (!alias.isEmpty()) {
                aliases.add(alias);
            }
        }
        return aliases;
    }


    /**
     * Test seam: replace the loaded list with a hand-rolled one.
     * Used by unit tests; app code should not call this.
     */
    static void setEntriesForTest(@NonNull List<Entry> testEntries) {
        entries = testEntries;
        loaded = true;
    }


    /**
     * Predicts the canonical merchant name for a raw OCR string. The
     * input is expected to be a noisy guess from the parser — usually
     * the first all-caps line, with no spaces normalised. Returns
     * null if no merchant scores above 0.
     */
    @Nullable
    public static Prediction predict(@NonNull String rawMerchant) {
        if (rawMerchant == null || rawMerchant.trim().isEmpty()) return null;
        if (entries.isEmpty()) return null;

        final String normalised = normalise(rawMerchant);
        if (normalised.isEmpty()) return null;

        final String[] tokens = normalised.split("\\s+");
        if (tokens.length == 0) return null;

        final List<ScoredEntry> scored = scoreAll(normalised, tokens);
        if (scored.isEmpty()) return null;

        scored.sort(SCORED_BY_SCORE_DESC);
        final ScoredEntry best = scored.get(0);
        final double confidence = computeConfidence(best, scored);

        Logger.i(LOG_TAG, "predict('" + rawMerchant + "') -> " + best.entry.name
                + "  conf=" + String.format("%.2f", confidence)
                + "  runner-up-gap=" + String.format("%.2f", runnerUpGap(best, scored)));

        return new Prediction(best.entry.name, best.entry.category,
                confidence, rawMerchant);
    }


    /**
     * Returns the top N predictions, sorted by score desc. Used by
     * the editor's "did we get the merchant right?" affordance.
     */
    @NonNull
    public static List<Prediction> topN(@NonNull String rawMerchant, final int n) {
        if (rawMerchant == null || rawMerchant.trim().isEmpty() || entries.isEmpty()) {
            return Collections.emptyList();
        }

        final String normalised = normalise(rawMerchant);
        if (normalised.isEmpty()) return Collections.emptyList();

        final String[] tokens = normalised.split("\\s+");
        final List<ScoredEntry> scored = scoreAll(normalised, tokens);
        scored.sort(SCORED_BY_SCORE_DESC);

        final int limit = Math.min(n, scored.size());
        final List<Prediction> top = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            final ScoredEntry entry = scored.get(i);
            top.add(new Prediction(entry.entry.name, entry.entry.category,
                    entry.score, rawMerchant));
        }
        return top;
    }


    private static final Comparator<ScoredEntry> SCORED_BY_SCORE_DESC =
            (a, b) -> Double.compare(b.score, a.score);


    private static List<ScoredEntry> scoreAll(String normalised, String[] tokens) {
        final List<ScoredEntry> scored = new ArrayList<>();
        for (final Entry entry : entries) {
            final double s = scoreEntry(entry, normalised, tokens);
            if (s > 0.0) {
                scored.add(new ScoredEntry(entry, s));
            }
        }
        return scored;
    }


    /** Sum-of-soft-matches, weighted by the merchant's popularity. */
    private static double scoreEntry(Entry entry, String normalised, String[] tokens) {
        double aliasScore = 0.0;
        for (final String alias : entry.aliases) {
            if (alias.isEmpty()) continue;

            // Whole-alias substring match is the strongest signal.
            if (normalised.contains(alias) || alias.contains(normalised)) {
                aliasScore = Math.max(aliasScore, 1.0);
                continue;
            }

            // Token-level match: how many of our tokens are present
            // in the alias (or vice versa).
            final int hits = countTokenHits(alias, tokens);
            if (hits > 0) {
                final int aliasTokenCount = alias.split("\\s+").length;
                final int denom = Math.max(1, Math.max(tokens.length, aliasTokenCount));
                final double s = (double) hits / denom;
                aliasScore = Math.max(aliasScore, s);
            }
        }
        return aliasScore * entry.weight;
    }


    private static int countTokenHits(String alias, String[] tokens) {
        int hits = 0;
        for (final String token : tokens) {
            if (token.length() < 2) continue;
            if (alias.contains(token) || token.contains(alias)) hits++;
        }
        return hits;
    }


    private static double runnerUpGap(ScoredEntry best, List<ScoredEntry> scored) {
        if (scored.size() <= 1) return 1.0;
        final ScoredEntry runnerUp = scored.get(1);
        return (best.score - runnerUp.score) / Math.max(MIN_DENOMINATOR_FOR_GAP, best.score);
    }


    private static double computeConfidence(ScoredEntry best, List<ScoredEntry> scored) {
        final double gap = runnerUpGap(best, scored);
        final double rawConfidence = best.entry.weight
                * (CONFIDENCE_WEIGHT_BASE + CONFIDENCE_WEIGHT_GAP * gap);
        return Math.min(MAX_CONFIDENCE, rawConfidence);
    }


    @NonNull
    private static String normalise(final @NonNull String s) {
        // Lowercase, strip punctuation that OCR commonly mangles,
        // collapse spaces.
        final String lower = s.toLowerCase(Locale.US);
        final StringBuilder builder = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            final char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c)) {
                builder.append(c);
            } else {
                builder.append(' ');
            }
        }
        // Collapse multiple spaces.
        return builder.toString().replaceAll("\\s+", " ").trim();
    }


    private static final class ScoredEntry {
        final Entry entry;
        final double score;

        ScoredEntry(Entry entry, double score) {
            this.entry = entry;
            this.score = score;
        }
    }
}
