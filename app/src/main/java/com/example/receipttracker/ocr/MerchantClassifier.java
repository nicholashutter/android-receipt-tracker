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
 * this classifier "which of our known merchants is this most
 * likely to be?" so we can normalise "WHOLE FOODS" / "WFM" /
 * "Whole Foods Market" all to "Whole Foods Market".</p>
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


    private static final String TAG = "MerchantClf";

    private static final String ASSET = "merchants.json";


    private static volatile List<Entry> entries = Collections.emptyList();

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

        try (InputStream is = appContext.getAssets().open(ASSET);

             BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();

            String line;
            while ((line = r.readLine()) != null) sb.append(line);

            JSONObject root = new JSONObject(sb.toString());

            JSONArray arr = root.optJSONArray("merchants");

            List<Entry> out = new ArrayList<>();

            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);

                    String name = o.optString("name", "");

                    if (name.isEmpty()) continue;

                    double weight = o.optDouble("weight", 0.0);

                    String category = o.optString("category", "");

                    List<String> aliases = new ArrayList<>();

                    aliases.add(name.toLowerCase(Locale.US));

                    JSONArray aliasArr = o.optJSONArray("aliases");

                    if (aliasArr != null) {
                        for (int j = 0; j < aliasArr.length(); j++) {
                            String a = aliasArr.optString(j, "").toLowerCase(Locale.US).trim();

                            if (!a.isEmpty()) aliases.add(a);
                        }
                    }

                    out.add(new Entry(name, category, weight, aliases));
                }
            }

            entries = out;

            loaded = true;

            long ms = System.currentTimeMillis() - t0;

            Logger.i(TAG, "Loaded " + out.size() + " merchants from " + ASSET + " in " + ms + "ms");
        } catch (IOException | JSONException e) {
            Logger.e(TAG, "Failed to load " + ASSET, e);

            entries = Collections.emptyList();

            loaded = true; // don't keep retrying
        }
    }


    /**
     * Test seam: replace the loaded list with a hand-rolled one. Used
     * by unit tests; app code should not call this.
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

        String normalised = normalise(rawMerchant);

        if (normalised.isEmpty()) return null;

        String[] tokens = normalised.split("\\s+");

        if (tokens.length == 0) return null;

        List<Scored> scored = new ArrayList<>();

        double maxRaw = 0;

        for (Entry e : entries) {
            double s = scoreEntry(e, normalised, tokens);

            if (s > 0) {
                scored.add(new Scored(e, s));

                if (s > maxRaw) maxRaw = s;
            }
        }

        if (scored.isEmpty()) return null;

        // Normalise to a probability over the candidates. We use a
        // softmax-ish transform on the raw scores so that the top
        // prediction has a confidence close to 1.0 only when the gap
        // between it and the runner-up is large.
        scored.sort(new Comparator<Scored>() {
            @Override
            public int compare(Scored a, Scored b) { return Double.compare(b.score, a.score); }
        });

        double top = scored.get(0).score;

        // Confidence: how much the top beats the rest, scaled by
        // top's own weight. A clear winner (big gap, high weight) gets
        // close to 1.0; a tight race gets ~0.5.
        double runnerUpGap;

        if (scored.size() > 1) {
            runnerUpGap = (top - scored.get(1).score) / Math.max(0.01, top);
        } else {
            runnerUpGap = 1.0;
        }

        double confidence = Math.min(0.99, scored.get(0).entry.weight * (0.55 + 0.45 * runnerUpGap));

        Logger.i(TAG, "predict('" + rawMerchant + "') -> " + scored.get(0).entry.name
                + "  conf=" + String.format("%.2f", confidence)
                + "  runner-up-gap=" + String.format("%.2f", runnerUpGap));

        for (int i = 0; i < Math.min(3, scored.size()); i++) {
            Logger.d(TAG, "  #" + (i + 1) + "  " + scored.get(i).entry.name
                    + "  score=" + String.format("%.3f", scored.get(i).score));
        }

        return new Prediction(scored.get(0).entry.name, scored.get(0).entry.category,
                confidence, rawMerchant);
    }


    /**
     * Returns the top N predictions, sorted by score desc. Used by
     * the editor's "did we get the merchant right?" affordance.
     */
    @NonNull
    public static List<Prediction> topN(@NonNull String rawMerchant, int n) {
        if (rawMerchant == null || rawMerchant.trim().isEmpty() || entries.isEmpty()) {
            return Collections.emptyList();
        }

        String normalised = normalise(rawMerchant);

        if (normalised.isEmpty()) return Collections.emptyList();

        String[] tokens = normalised.split("\\s+");

        List<Scored> scored = new ArrayList<>();

        for (Entry e : entries) {
            double s = scoreEntry(e, normalised, tokens);

            if (s > 0) scored.add(new Scored(e, s));
        }

        scored.sort((a, b) -> Double.compare(b.score, a.score));

        int k = Math.min(n, scored.size());

        List<Prediction> out = new ArrayList<>();

        for (int i = 0; i < k; i++) {
            Scored s = scored.get(i);

            out.add(new Prediction(s.entry.name, s.entry.category, s.score, rawMerchant));
        }

        return out;
    }


    /** Sum-of-soft-matches, weighted by the merchant's popularity. */
    private static double scoreEntry(Entry e, String normalised, String[] tokens) {
        // For each alias, see how many of the parsed tokens it
        // covers. Use a soft match so partial aliases (e.g. parsed
        // "WFM" vs alias "wfm") still count.
        double aliasScore = 0;

        for (String alias : e.aliases) {
            if (alias.isEmpty()) continue;

            // Whole-alias substring match is the strongest signal.
            if (normalised.contains(alias) || alias.contains(normalised)) {
                aliasScore = Math.max(aliasScore, 1.0);

                continue;
            }

            // Token-level match: how many of our tokens are present in
            // the alias (or vice versa).
            int hits = 0;

            for (String t : tokens) {
                if (t.length() < 2) continue;

                if (alias.contains(t) || t.contains(alias)) hits++;
            }

            if (hits > 0) {
                double s = (double) hits / Math.max(1, Math.max(tokens.length, alias.split("\\s+").length));

                aliasScore = Math.max(aliasScore, s);
            }
        }

        return aliasScore * e.weight;
    }


    @NonNull
    private static String normalise(@NonNull String s) {
        // Lowercase, strip punctuation that OCR commonly mangles, collapse spaces.
        String lower = s.toLowerCase(Locale.US);

        StringBuilder sb = new StringBuilder(lower.length());

        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);

            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c)) {
                sb.append(c);
            } else {
                sb.append(' ');
            }
        }

        // Collapse multiple spaces.
        String out = sb.toString().replaceAll("\\s+", " ").trim();

        return out;
    }


    private static final class Scored {
        final Entry entry;

        final double score;

        Scored(Entry entry, double score) { this.entry = entry; this.score = score; }
    }
}
