package com.example.receipttracker.export;


import android.content.Context;


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
 *   {@code <baseName>.jpg}   - the original photo
 *   {@code <baseName>.json}  - parsed metadata + raw OCR text
 *
 * <p>into a SAF tree (DocumentFile). The folder is whatever the user
 * picked (Downloads, an external SD card, an attached USB drive, etc.)
 * - typically a path on the dev machine once that folder is synced.</p>
 */
public final class ReceiptExporter {

    private static final String FILE_DATE_PATTERN = "yyyyMMdd";
    private static final int JPEG_QUALITY = 90;
    private static final String JSON_INDENT = "  ";

    private static final String MIME_JPEG = "image/jpeg";
    private static final String MIME_JSON = "application/json";
    private static final String ENCODING_UTF8 = "UTF-8";

    private static final SimpleDateFormat FILE_DATE_FORMAT =
            new SimpleDateFormat(FILE_DATE_PATTERN, Locale.US);


    private ReceiptExporter() {}


    public static String baseNameFor(final Receipt receipt) {
        // receipt_<index>_<yyyyMMdd>  (e.g. receipt_00042_20260801)
        final String datePart = FILE_DATE_FORMAT.format(new Date(receipt.dateMillis));
        return String.format(Locale.US, "receipt_%06d_%s", receipt.id, datePart);
    }


    public static void export(Context appContext, DocumentFile targetFolder, Receipt receipt) throws Exception {
        final String baseName = baseNameFor(receipt);
        writePhotoIfPresent(appContext, targetFolder, receipt, baseName);
        writeJsonFile(appContext, targetFolder, receipt, baseName);
    }


    private static void writePhotoIfPresent(
            Context appContext, DocumentFile targetFolder, Receipt receipt, String baseName) throws Exception {
        if (receipt.photoPath == null) return;

        final File photoFile = new File(receipt.photoPath);
        if (!photoFile.exists()) return;

        final DocumentFile photoDoc = targetFolder.createFile(MIME_JPEG, baseName + ".jpg");
        if (photoDoc == null) {
            throw new IllegalStateException("Could not create photo file");
        }
        try (FileInputStream input = new FileInputStream(photoFile);
             OutputStream output = appContext.getContentResolver().openOutputStream(photoDoc.getUri())) {
            if (output == null) {
                throw new IllegalStateException("Could not open output stream for photo");
            }
            copyStream(input, output);
        }
    }


    private static void writeJsonFile(
            Context appContext, DocumentFile targetFolder, Receipt receipt, String baseName) throws Exception {
        final DocumentFile jsonDoc = targetFolder.createFile(MIME_JSON, baseName + ".json");
        if (jsonDoc == null) {
            throw new IllegalStateException("Could not create JSON file");
        }
        try (OutputStream output = appContext.getContentResolver().openOutputStream(jsonDoc.getUri())) {
            if (output == null) {
                throw new IllegalStateException("Could not open output stream for JSON");
            }
            final String jsonText = toJson(receipt).toString(2);
            output.write(jsonText.getBytes(ENCODING_UTF8));
            output.flush();
        }
    }


    @NonNull
    public static JSONObject toJson(final Receipt receipt) throws JSONException {
        final JSONObject json = new JSONObject();
        json.put("id", receipt.id);
        json.put("merchant", receipt.merchant);
        json.put("amount", receipt.amount);
        json.put("amountFormatted", MoneyUtils.format(receipt.amount));
        json.put("dateMillis", receipt.dateMillis);
        json.put("date", MoneyUtils.formatDate(receipt.dateMillis));
        json.put("notes", receipt.notes);
        json.put("matchGroupId", receipt.matchGroupId);
        // Preserve the pre-refactor semantics: JSONObject.put with a
        // Java null value REMOVES the key, so a receipt with no photo
        // has no "image" entry at all.
        if (receipt.photoPath == null) {
            json.put("image", (Object) null);
        } else {
            json.put("image", baseNameFor(receipt) + ".jpg");
        }
        json.put("rawText", receipt.rawText);
        json.put("createdAt", receipt.createdAt);
        return json;
    }


    private static void copyStream(FileInputStream input, OutputStream output) throws Exception {
        final byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) > 0) {
            output.write(buffer, 0, bytesRead);
        }
        output.flush();
    }
}
