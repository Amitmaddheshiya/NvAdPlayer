package com.nvadplayer;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VideoFrameExtractorModule extends ReactContextBaseJavaModule {
    private static final String TAG = "VideoFrameExtractor";
    private final ReactApplicationContext reactContext;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public VideoFrameExtractorModule(ReactApplicationContext context) {
        super(context);
        this.reactContext = context;
    }

    @NonNull
    @Override
    public String getName() {
        return "VideoFrameExtractorModule";
    }

    @ReactMethod
    public void extractVideoFrames(String videoPath, int targetFps, Promise promise) {
        executor.execute(() -> {
            try {
                if (videoPath == null || videoPath.isEmpty()) {
                    promise.reject("INVALID_PATH", "Video path is null or empty");
                    return;
                }

                File videoFile = new File(videoPath);
                if (!videoFile.exists()) {
                    promise.reject("FILE_NOT_FOUND", "Video file does not exist: " + videoPath);
                    return;
                }

                int fps = targetFps <= 0 ? 15 : targetFps;
                String fileHash = getMd5Hash(videoPath + "_" + videoFile.length() + "_" + videoFile.lastModified() + "_fps" + fps);

                File cacheDir = new File(reactContext.getCacheDir(), "frame_cache/" + fileHash);
                if (!cacheDir.exists()) {
                    cacheDir.mkdirs();
                }

                // Check if frames already exist in cache
                File[] existingFiles = cacheDir.listFiles();
                if (existingFiles != null && existingFiles.length > 5) {
                    WritableArray frameArray = Arguments.createArray();
                    List<File> sortedFiles = new ArrayList<>();
                    for (File f : existingFiles) {
                        if (f.getName().endsWith(".jpg")) {
                            sortedFiles.add(f);
                        }
                    }
                    Collections.sort(sortedFiles, Comparator.comparing(File::getName));

                    for (File f : sortedFiles) {
                        frameArray.pushString("file://" + f.getAbsolutePath());
                    }

                    WritableMap result = Arguments.createMap();
                    result.putArray("framePaths", frameArray);
                    result.putInt("totalFrames", sortedFiles.size());
                    result.putInt("fps", fps);
                    result.putBoolean("cached", true);
                    promise.resolve(result);
                    return;
                }

                // Extract frames using MediaMetadataRetriever
                MediaMetadataRetriever retriever = new MediaMetadataRetriever();
                try {
                    retriever.setDataSource(videoPath);
                } catch (Exception e) {
                    promise.reject("RETRIEVER_ERROR", "Failed to load video dataSource: " + e.getMessage());
                    return;
                }

                String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = durationStr != null ? Long.parseLong(durationStr) : 0;
                if (durationMs <= 0) {
                    retriever.release();
                    promise.reject("INVALID_DURATION", "Invalid video duration: " + durationMs);
                    return;
                }

                // Sample 10-12 keyframes across duration for ultra-fast 100ms extraction
                long maxExtractMs = Math.min(durationMs, 60000);
                int totalSampleFrames = 10;
                long frameIntervalUs = (maxExtractMs * 1000L) / totalSampleFrames;

                WritableArray frameArray = Arguments.createArray();
                int frameIndex = 0;

                for (int i = 0; i < totalSampleFrames; i++) {
                    long timeUs = i * frameIntervalUs;
                    Bitmap bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_NEXT_SYNC);
                    if (bitmap == null) {
                        bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    }

                    if (bitmap != null) {
                        // Downscale bitmap to 640x360 for fast disk write & rendering
                        int targetW = bitmap.getWidth();
                        int targetH = bitmap.getHeight();
                        if (targetW > 640) {
                            targetH = (int) (((double) targetH / targetW) * 640);
                            targetW = 640;
                            bitmap = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
                        }

                        File frameFile = new File(cacheDir, String.format("frame_%04d.jpg", frameIndex));
                        try (OutputStream out = new FileOutputStream(frameFile)) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, out);
                            frameArray.pushString("file://" + frameFile.getAbsolutePath());
                            frameIndex++;
                        } catch (Exception e) {
                            Log.w(TAG, "Error saving frame " + frameIndex, e);
                        }
                    }
                }

                retriever.release();

                WritableMap result = Arguments.createMap();
                result.putArray("framePaths", frameArray);
                result.putInt("totalFrames", frameIndex);
                result.putInt("fps", fps);
                result.putDouble("durationMs", (double) durationMs);
                result.putBoolean("cached", false);

                promise.resolve(result);
            } catch (Exception e) {
                Log.e(TAG, "Frame extraction error", e);
                promise.reject("EXTRACTION_FAILED", e.getMessage(), e);
            }
        });
    }

    private String getMd5Hash(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return String.valueOf(input.hashCode());
        }
    }
}
