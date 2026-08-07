package com.example.receipttracker.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import com.example.receipttracker.log.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Owns the on-disk location of receipt images. Files live under
 * <app files dir>/receipts/ so they are wiped on uninstall and never escape
 * the app sandbox.
 */
public final class ReceiptImageStore {

    private static final String DIR_NAME = "receipts";
    private static final SimpleDateFormat NAME_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);

    private ReceiptImageStore() {}

    public static File dir(Context ctx) {
        File f = new File(ctx.getFilesDir(), DIR_NAME);
        if (!f.exists()) //noinspection ResultOfMethodCallIgnored
            f.mkdirs();
        return f;
    }

    /** Copy an image picked from the gallery into the app's private storage. */
    public static File importFromUri(Context ctx, Uri uri) throws Exception {
        Logger.section("IMAGE IMPORT");
        Logger.i("Image", "Importing from URI: " + uri);
        File target = newFile(ctx);
        long bytes = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IllegalStateException("Cannot open uri " + uri);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                bytes += n;
            }
        }
        Logger.i("Image", "Imported " + bytes + " bytes -> " + target.getAbsolutePath()
                + "  (exists=" + target.exists() + ", sizeOnDisk=" + target.length() + ")");
        Logger.section("IMAGE IMPORT END");
        return target;
    }

    /** Save a captured bitmap as a JPEG. */
    public static File saveBitmap(Context ctx, Bitmap bitmap) throws Exception {
        Logger.section("IMAGE SAVE");
        Logger.i("Image", "Saving bitmap: " + bitmap.getWidth() + "x" + bitmap.getHeight()
                + " config=" + bitmap.getConfig());
        File target = newFile(ctx);
        try (FileOutputStream out = new FileOutputStream(target)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
        }
        Logger.i("Image", "Saved to " + target.getAbsolutePath()
                + "  (sizeOnDisk=" + target.length() + " bytes)");
        Logger.section("IMAGE SAVE END");
        return target;
    }

    public static Bitmap decodeSampled(String path, int reqWidth, int reqHeight) {
        Logger.d("Image", "decodeSampled path=" + path + " req=" + reqWidth + "x" + reqHeight);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);
        Logger.d("Image", "  source bounds: " + opts.outWidth + "x" + opts.outHeight);
        opts.inSampleSize = calcSampleSize(opts, reqWidth, reqHeight);
        Logger.d("Image", "  inSampleSize=" + opts.inSampleSize);
        opts.inJustDecodeBounds = false;
        Bitmap bmp = BitmapFactory.decodeFile(path, opts);
        if (bmp == null) {
            Logger.w("Image", "  decode returned null!");
        } else {
            Logger.d("Image", "  decoded: " + bmp.getWidth() + "x" + bmp.getHeight());
        }
        return bmp;
    }

    private static int calcSampleSize(BitmapFactory.Options opts, int reqW, int reqH) {
        int h = opts.outHeight, w = opts.outWidth;
        int sample = 1;
        if (h > reqH || w > reqW) {
            int halfH = h / 2, halfW = w / 2;
            while ((halfH / sample) >= reqH && (halfW / sample) >= reqW) {
                sample *= 2;
            }
        }
        return sample;
    }

    private static File newFile(Context ctx) {
        String name = "receipt_" + NAME_FMT.format(new Date()) + ".jpg";
        return new File(dir(ctx), name);
    }
}
