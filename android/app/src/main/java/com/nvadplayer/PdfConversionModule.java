package com.nvadplayer;

import android.graphics.Bitmap;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Converts PDFs on disk so React Native never has to load document contents into JS memory. */
public class PdfConversionModule extends ReactContextBaseJavaModule {
    private static final String TAG = "PdfConversionModule";
    private static final int JPEG_QUALITY = 92;
    private static final int MAX_RENDER_DIMENSION = 2048;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    PdfConversionModule(ReactApplicationContext context) {
        super(context);
    }

    @NonNull
    @Override
    public String getName() {
        return "PdfConversionModule";
    }

    /**
     * Processes only the supported nvsign sections. The operation is intentionally serialized:
     * a USB/media scan cannot expose half-rendered pages to the player.
     */
    @ReactMethod
    public void convertSectionPdfs(String nvsignPath, Promise promise) {
        executor.execute(() -> {
            try {
                int converted = convertPdfs(new File(nvsignPath));
                promise.resolve(converted);
            } catch (Exception error) {
                Log.e(TAG, "PDF conversion scan failed", error);
                promise.reject("PDF_CONVERSION_SCAN_FAILED", error);
            }
        });
    }

    @ReactMethod public void addListener(String eventName) { }
    @ReactMethod public void removeListeners(double count) { }

    private int convertPdfs(File nvsignDir) {
        if (!nvsignDir.isDirectory()) return 0;
        int converted = 0;
        boolean foundPdf = false;
        boolean allSuccessful = true;
        File[] sections = nvsignDir.listFiles(file -> file.isDirectory()
                && file.getName().toLowerCase(Locale.ROOT).matches("section[123]"));
        if (sections == null) return 0;
        Arrays.sort(sections, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File section : sections) {
            File[] files = section.listFiles(file -> file.isFile()
                    && file.getName().toLowerCase(Locale.ROOT).endsWith(".pdf"));
            if (files == null) continue;
            Arrays.sort(files, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
            for (File pdf : files) {
                foundPdf = true;
                if (convertPdf(pdf)) converted++; else allSuccessful = false;
            }
        }
        if (foundPdf) emitProgress(allSuccessful ? "complete" : "failed", "", allSuccessful ? 100 : 0, 0, 0);
        return converted;
    }

    private boolean convertPdf(File pdf) {
        String filename = pdf.getName();
        String baseName = filename.substring(0, filename.length() - 4);
        File section = pdf.getParentFile();
        File staging = new File(section, "." + baseName + ".pdf-rendering");
        deleteRecursively(staging); // Previous interrupted run: source PDF is still authoritative.
        if (!staging.mkdirs()) {
            Log.e(TAG, "Cannot create staging directory for " + pdf);
            return false;
        }

        ParcelFileDescriptor descriptor = null;
        PdfRenderer renderer = null;
        List<File> stagedPages = new ArrayList<>();
        try {
            descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(descriptor);
            int pageCount = renderer.getPageCount();
            if (pageCount < 1) throw new IOException("PDF has no pages");
            emitProgress("rendering", filename, 0, 0, pageCount);
            int digits = Math.max(3, String.valueOf(pageCount).length());
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                PdfRenderer.Page page = renderer.openPage(pageIndex);
                Bitmap bitmap = null;
                try {
                    float scale = Math.min(1f, (float) MAX_RENDER_DIMENSION
                            / Math.max(page.getWidth(), page.getHeight()));
                    int width = Math.max(1, Math.round(page.getWidth() * scale));
                    int height = Math.max(1, Math.round(page.getHeight() * scale));
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    bitmap.eraseColor(0xFFFFFFFF);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    File output = new File(staging, String.format(Locale.ROOT,
                            "%s_%0" + digits + "d.jpg", baseName, pageIndex + 1));
                    try (FileOutputStream stream = new FileOutputStream(output)) {
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                            throw new IOException("Could not encode page " + (pageIndex + 1));
                        }
                    }
                    if (output.length() == 0) throw new IOException("Empty rendered page");
                    stagedPages.add(output);
                    emitProgress("rendering", filename,
                            Math.round(((pageIndex + 1) * 100f) / pageCount), pageIndex + 1, pageCount);
                } finally {
                    if (bitmap != null) bitmap.recycle();
                    page.close();
                }
            }

            // Verify every page before changing the section or deleting the source PDF.
            if (stagedPages.size() != pageCount) throw new IOException("Rendered page count mismatch");
            for (File page : stagedPages) {
                if (!page.isFile() || page.length() == 0) throw new IOException("Missing rendered page");
            }

            for (File stagedPage : stagedPages) {
                File target = new File(section, stagedPage.getName());
                if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target.getName());
                if (!stagedPage.renameTo(target)) throw new IOException("Cannot publish " + target.getName());
            }
            if (!pdf.delete()) throw new IOException("Pages were rendered, but original PDF could not be deleted");
            Log.i(TAG, "Converted " + pdf + " into " + pageCount + " JPG pages");
            return true;
        } catch (Exception error) {
            // The original is deliberately retained on every failure. A later scan safely retries it.
            Log.e(TAG, "Keeping PDF after conversion failure: " + pdf, error);
            return false;
        } finally {
            if (renderer != null) renderer.close();
            if (descriptor != null) {
                try { descriptor.close(); } catch (IOException ignored) { }
            }
            deleteRecursively(staging);
        }
    }

    private void deleteRecursively(File file) {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) Log.w(TAG, "Could not clean temporary file " + file);
    }

    private void emitProgress(String status, String file, int percent, int currentPage, int totalPages) {
        WritableMap event = Arguments.createMap();
        event.putString("status", status);
        event.putString("file", file);
        event.putInt("percent", percent);
        event.putInt("currentPage", currentPage);
        event.putInt("totalPages", totalPages);
        getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("pdfConversionProgress", event);
    }
}
