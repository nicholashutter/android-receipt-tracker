package com.example.receipttracker.export;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.documentfile.provider.DocumentFile;

import com.example.receipttracker.data.Receipt;
import com.example.receipttracker.util.MoneyUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes a single receipt as:
 *   <baseName>.jpg   - the original photo
 *   <baseName>.json  - parsed metadata + raw OCR text
 *
 * into a SAF tree (DocumentFile). The folder is whatever the user picked
 * (Downloads, an external SD card, an attached USB drive, etc.) - typically
 * a path on the dev machine once that folder is synced.
 */
public final class ReceiptExporter {

    private ReceiptExporter() {}

    public static String baseNameFor(Receipt r) {
        // receipt_<index>_<yyyyMMdd>  (e.g. receipt_00042_20260801)
        String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(r.dateMillis));
        return String.format(Locale.US, "receipt_%06d_%s", r.id, date);
    }

    public static void export(Context ctx, DocumentFile folder, Receipt r) throws Exception {
        String base = baseNameFor(r);
        // 1. Photo (if present)
        if (r.photoPath != null) {
            File f = new File(r.photoPath);
            if (f.exists()) {
                DocumentFile photo = folder.createFile("image/jpeg", base + ".jpg");
                if (photo == null) throw new IllegalStateException("Could not create photo file");
                try (FileInputStream in = new FileInputStream(f);
                     OutputStream out = ctx.getContentResolver().openOutputStream(photo.getUri())) {
                    if (out == null) throw new IllegalStateException("Could not open output stream for photo");
                    copy(in, out);
                }
            }
        }
        // 2. JSON
        DocumentFile json = folder.createFile("application/json", base + ".json");
        if (json == null) throw new IllegalStateException("Could not create JSON file");
        try (OutputStream out = ctx.getContentResolver().openOutputStream(json.getUri())) {
            if (out == null) throw new IllegalStateException("Could not open output stream for JSON");
            out.write(toJson(r).toString(2).getBytes("UTF-8"));
            out.flush();
        }
    }

    @NonNull
    public static JSONObject toJson(Receipt r) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", r.id);
        o.put("merchant", r.merchant);
        o.put("amount", r.amount);
        o.put("amountFormatted", MoneyUtils.format(r.amount));
        o.put("dateMillis", r.dateMillis);
        o.put("date", MoneyUtils.formatDate(r.dateMillis));
        o.put("notes", r.notes);
        o.put("matchGroupId", r.matchGroupId);
        o.put("image", r.photoPath == null ? null
                : baseNameFor(r) + ".jpg");
        o.put("rawText", r.rawText);
        o.put("createdAt", r.createdAt);
        return o;
    }

    private static void copy(FileInputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }
}
