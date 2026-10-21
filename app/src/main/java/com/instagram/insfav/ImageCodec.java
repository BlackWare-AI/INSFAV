package com.instagram.insfav;

import android.content.ContentResolver;
import android.graphics.*;
import android.net.Uri;
import java.io.*;

/** Bounded local decoding. Every source stream is closed before returning. */
final class ImageCodec {
    static InputStream open(ContentResolver resolver, Uri uri) throws IOException {
        InputStream source = resolver.openInputStream(uri);
        if (source == null) throw new IOException("Image is not readable");
        return new FilterInputStream(source) {
            long left = 64L * 1024 * 1024;
            @Override public int read() throws IOException {if (left <= 0) throw new IOException("Image size limit"); int n = super.read(); if (n >= 0) left--; return n;}
            @Override public int read(byte[] b, int offset, int length) throws IOException {
                if (left <= 0) throw new IOException("Image size limit");
                int n = in.read(b, offset, (int)Math.min(length, left)); if (n > 0) left -= n; return n;
            }
        };
    }
    static BitmapFactory.Options bounds(ContentResolver resolver, Uri uri) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        try (InputStream input = open(resolver, uri)) {BitmapFactory.decodeStream(input, null, options);}
        int w = options.outWidth, h = options.outHeight;
        if (w <= 0 || h <= 0 || w > 32768 || h > 32768 || (long)w * h > 100000000L ||
            !("image/jpeg".equals(options.outMimeType) || "image/png".equals(options.outMimeType) || "image/webp".equals(options.outMimeType))) throw new IOException("Unsupported or invalid image");
        return options;
    }
    static Bitmap decode(ContentResolver resolver, Uri uri, BitmapFactory.Options bounds, int target) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > target) options.inSampleSize *= 2;
        try (InputStream input = open(resolver, uri)) {
            Bitmap image = BitmapFactory.decodeStream(input, null, options);
            if (image == null) throw new IOException("Image decoding failed"); return image;
        }
    }
    static boolean validate(ContentResolver resolver, Uri uri, BitmapFactory.Options bounds) {
        try {Bitmap image = decode(resolver, uri, bounds, 128); image.recycle(); return true;}
        catch (IOException | RuntimeException | OutOfMemoryError error) {return false;}
    }
    static byte[] render(ContentResolver resolver, Uri uri, boolean full) throws IOException {
        BitmapFactory.Options bounds = bounds(resolver, uri); int target = full ? 2048 : 128;
        Bitmap source = decode(resolver, uri, bounds, target), result = null, square = null;
        try {
            if (full) {
                float factor = Math.min(1f, target / (float)Math.max(source.getWidth(), source.getHeight()));
                result = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * factor)), Math.max(1, Math.round(source.getHeight() * factor)), true);
            } else {
                int side = Math.min(source.getWidth(), source.getHeight());
                square = Bitmap.createBitmap(source, (source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side);
                result = Bitmap.createScaledBitmap(square, 128, 128, true);
            }
            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (!result.compress(full ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.WEBP, full ? 100 : 82, output)) throw new IOException("Image encoding failed");
                return output.toByteArray();
            }
        } finally {
            if (result != null && result != source) result.recycle();
            if (square != null && square != source && square != result) square.recycle(); source.recycle();
        }
    }
}
