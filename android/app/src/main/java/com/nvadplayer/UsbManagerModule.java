package com.nvadplayer;

import android.content.ContentUris;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.util.Log;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UsbManagerModule extends ReactContextBaseJavaModule {
    private static final String TAG = "UsbManagerModule";
    private static final long USB_DEBOUNCE_MS = 250L;
    private static final long USB_MOUNT_SETTLE_RESCAN_MS = 2000L;
    private static final String ADS_DIR_NAME = "nvsign";
    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList(
            ".mp4",
            ".m4v",
            ".mov",
            ".mkv",
            ".avi", ".3gp", ".3g2", ".wmv", ".flv", ".mpg", ".mpeg",
            ".ts", ".mts", ".m2ts", ".vob", ".ogv", ".asf", ".f4v",
            ".webm",
            ".movie",
            ".jpg",
            ".jpeg",
            ".png", ".webp", ".gif", ".bmp", ".tif", ".tiff", ".heic", ".heif",
            ".avif", ".jfif", ".svg"
    );

    private final ReactApplicationContext reactContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor();
    private final Object stateLock = new Object();

    private BroadcastReceiver usbReceiver;
    private Runnable pendingScanRunnable;
    private UsbState lastState = UsbState.empty();

    UsbManagerModule(ReactApplicationContext context) {
        super(context);
        this.reactContext = context;
        registerUsbReceiver();
        scheduleScan("init");
    }

    @NonNull
    @Override
    public String getName() {
        return "UsbManagerModule";
    }

    @ReactMethod
    public void addListener(String eventName) {
        // Required by NativeEventEmitter on Android.
    }

    @ReactMethod
    public void removeListeners(double count) {
        // Required by NativeEventEmitter on Android.
    }

    @ReactMethod
    public void refreshUsbState(Promise promise) {
        scanExecutor.execute(() -> {
            UsbState state = scanUsbState();
            updateState(state, "manual-refresh");
            promise.resolve(toWritableMap(state));
        });
    }

    @ReactMethod
    public void getCurrentUsbState(Promise promise) {
        synchronized (stateLock) {
            promise.resolve(toWritableMap(lastState));
        }
    }

    private void registerUsbReceiver() {
        if (usbReceiver != null) return;
        usbReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent != null ? intent.getAction() : "";
                scheduleScan(String.valueOf(action == null ? "usb-event" : action));
                if (Intent.ACTION_MEDIA_MOUNTED.equals(action) || Intent.ACTION_MEDIA_CHECKING.equals(action)) {
                    mainHandler.postDelayed(
                            () -> scheduleScan(String.valueOf(action == null ? "usb-settle" : action) + "-settled"),
                            USB_MOUNT_SETTLE_RESCAN_MS
                    );
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_REMOVED);
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        filter.addAction(Intent.ACTION_MEDIA_EJECT);
        filter.addAction(Intent.ACTION_MEDIA_BAD_REMOVAL);
        filter.addDataScheme("file");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            reactContext.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            reactContext.registerReceiver(usbReceiver, filter);
        }
    }

    private void scheduleScan(String reason) {
        if (pendingScanRunnable != null) {
            mainHandler.removeCallbacks(pendingScanRunnable);
        }
        pendingScanRunnable = () -> scanExecutor.execute(() -> {
            UsbState state = scanUsbState();
            updateState(state, reason);
        });
        mainHandler.postDelayed(pendingScanRunnable, USB_DEBOUNCE_MS);
    }

    private void updateState(UsbState nextState, String reason) {
        boolean changed;
        synchronized (stateLock) {
            changed = !lastState.sameAs(nextState);
            lastState = nextState;
        }
        Log.d(
                TAG,
                "updateState reason=" + reason
                        + " changed=" + changed
                        + " mounted=" + nextState.mounted
                        + " playable=" + nextState.hasPlayableMedia
                        + " mountPath=" + nextState.mountPath
                        + " playlistSize=" + nextState.playlist.size()
        );
        if (!changed) return;
        WritableMap payload = toWritableMap(nextState);
        payload.putString("reason", String.valueOf(reason == null ? "" : reason));
        emit("usbMediaStateChanged", payload);
    }

    private void emit(String eventName, WritableMap payload) {
        try {
            if (!reactContext.hasActiveReactInstance()) return;
            reactContext
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(eventName, payload);
        } catch (Exception ignored) {
        }
    }

    private UsbState scanUsbState() {
        List<File> mounts = resolveCandidateMounts();
        Log.d(TAG, "scanUsbState mountCount=" + mounts.size());
        if (mounts.isEmpty()) {
            return UsbState.empty();
        }

        boolean mounted = false;
        List<String> checkedMounts = new ArrayList<>();
        for (File mountRoot : mounts) {
            if (mountRoot == null) continue;
            String mountPath = mountRoot.getAbsolutePath();
            checkedMounts.add(mountPath);
            mounted = true;
            Log.d(TAG, "checking mount=" + mountPath);

            List<UsbMediaItem> mediaStoreFiles = queryMediaStorePlaylist(mountRoot);
            Log.d(TAG, "mediaStore count for " + mountPath + " = " + mediaStoreFiles.size());
            if (!mediaStoreFiles.isEmpty()) {
                return UsbState.withMediaItems(mountPath, checkedMounts, mediaStoreFiles);
            }

            List<UsbMediaItem> documentFiles = queryDocumentsProviderPlaylist(mountRoot);
            Log.d(TAG, "documents count for " + mountPath + " = " + documentFiles.size());
            if (!documentFiles.isEmpty()) {
                return UsbState.withMediaItems(mountPath, checkedMounts, documentFiles);
            }

            File adsDir = resolveAdsDirectory(mountRoot);
            Log.d(
                    TAG,
                    "raw folder path=" + adsDir.getAbsolutePath()
                            + " exists=" + adsDir.exists()
                            + " isDir=" + adsDir.isDirectory()
                            + " canRead=" + adsDir.canRead()
            );
            if (!adsDir.exists() || !adsDir.isDirectory()) {
                continue;
            }
            List<File> playableFiles = collectPlayableFiles(adsDir);
            Log.d(TAG, "raw file count for " + mountPath + " = " + playableFiles.size());
            if (!playableFiles.isEmpty()) {
                return UsbState.withPlaylist(mountPath, checkedMounts, playableFiles);
            }
        }

        return UsbState.noPlayableMedia(checkedMounts, mounted);
    }

    private List<UsbMediaItem> queryMediaStorePlaylist(File mountRoot) {
        List<UsbMediaItem> results = new ArrayList<>();
        if (mountRoot == null) return results;

        try {
            ContentResolver resolver = reactContext.getContentResolver();
            String mountName = mountRoot.getName();
            Set<String> volumeNames = new HashSet<>();
            String normalizedMountName = String.valueOf(mountName == null ? "" : mountName).trim();
            String normalizedMountNameLower = normalizedMountName.toLowerCase(Locale.US);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Collection<String> discovered = MediaStore.getExternalVolumeNames(reactContext);
                    if (discovered != null) {
                        for (String name : discovered) {
                            if (name == null || name.trim().isEmpty()) continue;
                            String candidate = name.trim();
                            Log.d(TAG, "MediaStore volume=" + candidate);
                            if (normalizedMountNameLower.equals(candidate.toLowerCase(Locale.US))) {
                                volumeNames.add(candidate);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            // Aggregate external volume is often the only portable way to see removable media on TV builds.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                volumeNames.add(MediaStore.VOLUME_EXTERNAL);
            } else {
                volumeNames.add("external");
            }
            if (!normalizedMountName.isEmpty()) {
                volumeNames.add(normalizedMountName);
            }

            if (volumeNames.isEmpty()) {
                return results;
            }

            List<String> projectionColumns = new ArrayList<>(Arrays.asList(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.SIZE,
                    MediaStore.Files.FileColumns.DATE_MODIFIED,
                    MediaStore.Files.FileColumns.RELATIVE_PATH
            ));
            projectionColumns.add(MediaStore.MediaColumns.DATA);
            String[] projection = projectionColumns.toArray(new String[0]);
            String selection =
                    "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=? OR " +
                    MediaStore.Files.FileColumns.MEDIA_TYPE + "=?) AND (" +
                    "LOWER(COALESCE(" + MediaStore.Files.FileColumns.RELATIVE_PATH + ", '')) LIKE ? OR " +
                    "LOWER(COALESCE(" + MediaStore.MediaColumns.DATA + ", '')) LIKE ?" +
                    ")";
            String pathPrefix = mountRoot.getAbsolutePath().replace('\\', '/').toLowerCase(Locale.US)
                    + "/" + ADS_DIR_NAME.toLowerCase(Locale.US) + "/%";
            String[] selectionArgs = new String[] {
                    String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                    String.valueOf(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO),
                    "nvsign/%",
                    pathPrefix
            };

            for (String volumeName : volumeNames) {
                Uri collection = MediaStore.Files.getContentUri(volumeName);
                Cursor cursor = null;
                try {
                    cursor = resolver.query(
                            collection,
                            projection,
                            selection,
                            selectionArgs,
                            MediaStore.Files.FileColumns.DATE_MODIFIED + " ASC"
                    );
                    if (cursor == null) continue;

                    int idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
                    int nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME);
                    int mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE);
                    int sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE);
                    int modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED);
                    int relativePathIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH);
                    int dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idIndex);
                        String name = cursor.getString(nameIndex);
                        String mimeType = cursor.getString(mimeIndex);
                        long size = cursor.getLong(sizeIndex);
                        long modifiedSeconds = cursor.getLong(modifiedIndex);
                        String relativePath = cursor.getString(relativePathIndex);
                        String dataPath = dataIndex >= 0 ? cursor.getString(dataIndex) : "";

                        if (!isAdsMediaLocation(relativePath, dataPath, mountRoot)) continue;
                        String lowerName = String.valueOf(name == null ? "" : name).toLowerCase(Locale.US);
                        if (!isSupportedFile(lowerName)) continue;
                        if (size <= 0L) continue;

                        Uri contentUri = ContentUris.withAppendedId(collection, id);
                        results.add(new UsbMediaItem(
                                String.valueOf(name == null ? "" : name),
                                "usb://" + volumeName + "/" + String.valueOf(name == null ? "" : name),
                                contentUri.toString(),
                                String.valueOf(dataPath == null ? "" : dataPath),
                                normalizeMimeType(name, mimeType),
                                size,
                                modifiedSeconds > 0L ? modifiedSeconds * 1000L : 0L
                        ));
                    }
                } catch (Exception ignored) {
                    Log.w(TAG, "MediaStore query failed for volume=" + volumeName, ignored);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }

                if (!results.isEmpty()) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }

        Collections.sort(results, Comparator.comparing(item -> item.name.toLowerCase(Locale.US)));
        return results;
    }

    private List<UsbMediaItem> queryDocumentsProviderPlaylist(File mountRoot) {
        List<UsbMediaItem> results = new ArrayList<>();
        if (mountRoot == null) return results;

        String rootId = String.valueOf(mountRoot.getName() == null ? "" : mountRoot.getName()).trim();
        if (rootId.isEmpty()) return results;

        String authority = "com.android.externalstorage.documents";
        String adsDocumentId = rootId + ":" + ADS_DIR_NAME;

        try {
            ContentResolver resolver = reactContext.getContentResolver();
            Uri childrenUri = DocumentsContract.buildChildDocumentsUri(authority, adsDocumentId);
            String[] projection = new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED
            };
            Cursor cursor = null;
            try {
                cursor = resolver.query(childrenUri, projection, null, null, null);
                if (cursor == null) {
                    return results;
                }
                int docIdIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE);
                int modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED);

                while (cursor.moveToNext()) {
                    String documentId = cursor.getString(docIdIndex);
                    String name = cursor.getString(nameIndex);
                    String mimeType = cursor.getString(mimeIndex);
                    long size = cursor.getLong(sizeIndex);
                    long modifiedMs = cursor.getLong(modifiedIndex);

                    String lowerName = String.valueOf(name == null ? "" : name).toLowerCase(Locale.US);
                    if (!isSupportedFile(lowerName)) continue;
                    if (size <= 0L) continue;
                    Uri documentUri = DocumentsContract.buildDocumentUri(authority, documentId);
                    results.add(new UsbMediaItem(
                            String.valueOf(name == null ? "" : name),
                            "usbdoc://" + documentId,
                            documentUri.toString(),
                            "",
                            normalizeMimeType(name, mimeType),
                            size,
                            modifiedMs
                    ));
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "DocumentsProvider query failed for " + adsDocumentId, error);
        }

        Collections.sort(results, Comparator.comparing(item -> item.name.toLowerCase(Locale.US)));
        return results;
    }

    private List<File> resolveCandidateMounts() {
        List<File> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            StorageManager storageManager =
                    (StorageManager) reactContext.getSystemService(Context.STORAGE_SERVICE);
            if (storageManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                List<StorageVolume> volumes = storageManager.getStorageVolumes();
                for (StorageVolume volume : volumes) {
                    if (volume == null || volume.isPrimary()) continue;
                    String state = volume.getState();
                    if (!"mounted".equalsIgnoreCase(state) && !"mounted_ro".equalsIgnoreCase(state)) {
                        continue;
                    }
                    File dir = volume.getDirectory();
                    if (dir != null && dir.exists()) {
                        addCandidateMount(results, seen, dir);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        List<File> fallbackRoots = Arrays.asList(
                new File("/storage"),
                new File("/mnt/media_rw")
        );
        for (File root : fallbackRoots) {
            File[] children = root.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child == null || !child.isDirectory()) continue;
                String name = child.getName();
                if ("emulated".equalsIgnoreCase(name) || "self".equalsIgnoreCase(name)) continue;
                addCandidateMount(results, seen, child);
            }
        }

        Collections.sort(results, Comparator.comparing(File::getAbsolutePath));
        for (File result : results) {
            if (result != null) {
                Log.d(TAG, "candidate mount=" + result.getAbsolutePath());
            }
        }
        return results;
    }

    private List<File> collectPlayableFiles(File adsDir) {
        List<File> collected = new ArrayList<>();
        collectPlayableFilesRecursive(adsDir, collected);
        Collections.sort(collected, Comparator.comparing(file -> file.getAbsolutePath().toLowerCase(Locale.US)));
        return collected;
    }

    private void collectPlayableFilesRecursive(File dir, List<File> collected) {
        if (dir == null || collected == null) return;
        if (!dir.exists() || !dir.isDirectory()) return;

        File[] children = dir.listFiles();
        if (children == null) {
            Log.d(TAG, "listFiles returned null for " + dir.getAbsolutePath());
            return;
        }
        Log.d(TAG, "listFiles count=" + children.length + " for " + dir.getAbsolutePath());
        Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File child : children) {
            if (child == null) continue;
            if (child.isDirectory()) {
                collectPlayableFilesRecursive(child, collected);
                continue;
            }
            if (!child.isFile() || !child.canRead()) continue;
            String lowerName = child.getName().toLowerCase(Locale.US);
            if (!isSupportedFile(lowerName)) continue;
            if (child.length() <= 0L) continue;
            Log.d(TAG, "accepted raw file=" + child.getAbsolutePath());
            collected.add(child);
        }
    }

    private File resolveAdsDirectory(File mountRoot) {
        if (mountRoot == null) return new File("/", ADS_DIR_NAME);
        File exact = new File(mountRoot, ADS_DIR_NAME);
        if (exact.exists() && exact.isDirectory()) {
            return exact;
        }
        File[] children = mountRoot.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child == null || !child.isDirectory()) continue;
                if (ADS_DIR_NAME.equalsIgnoreCase(child.getName())) {
                    return child;
                }
            }
        }
        return exact;
    }

    private void addCandidateMount(List<File> results, Set<String> seen, File dir) {
        if (dir == null || results == null || seen == null) return;
        String path = dir.getAbsolutePath();
        if (TextUtils.isEmpty(path)) return;
        String normalized = path.replace('\\', '/').trim().toLowerCase(Locale.US);
        if (seen.contains(normalized)) return;
        seen.add(normalized);
        results.add(dir);
    }

    private boolean isSupportedFile(String lowerName) {
        for (String extension : SUPPORTED_EXTENSIONS) {
            if (lowerName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAdsRelativePath(String relativePath) {
        String normalized = String.valueOf(relativePath == null ? "" : relativePath)
                .replace('\\', '/')
                .trim();
        if (normalized.isEmpty()) return false;
        String lower = normalized.toLowerCase(Locale.US);
        return "nvsign".equals(lower) || "nvsign/".equals(lower) || lower.startsWith("nvsign/");
    }

    private boolean isAdsDataPath(String dataPath, File mountRoot) {
        String normalizedPath = String.valueOf(dataPath == null ? "" : dataPath)
                .replace('\\', '/')
                .trim()
                .toLowerCase(Locale.US);
        if (normalizedPath.isEmpty() || mountRoot == null) return false;
        String mountPrefix = mountRoot.getAbsolutePath()
                .replace('\\', '/')
                .trim()
                .toLowerCase(Locale.US);
        if (mountPrefix.isEmpty()) return false;
        return normalizedPath.startsWith(mountPrefix + "/" + ADS_DIR_NAME.toLowerCase(Locale.US) + "/");
    }

    private boolean isAdsMediaLocation(String relativePath, String dataPath, File mountRoot) {
        return isAdsRelativePath(relativePath) || isAdsDataPath(dataPath, mountRoot);
    }

    private WritableMap toWritableMap(UsbState state) {
        WritableMap payload = Arguments.createMap();
        payload.putBoolean("mounted", state.mounted);
        payload.putBoolean("hasPlayableMedia", state.hasPlayableMedia);
        payload.putString("mountPath", state.mountPath);

        WritableArray mountPaths = Arguments.createArray();
        for (String path : state.mountPaths) {
            mountPaths.pushString(path);
        }
        payload.putArray("mountPaths", mountPaths);

        WritableArray playlist = Arguments.createArray();
        for (UsbMediaItem item : state.playlist) {
            WritableMap entry = Arguments.createMap();
            entry.putString("name", item.name);
            entry.putString("originalName", item.name);
            entry.putString("url", item.url);
            entry.putString("remoteUrl", item.remoteUrl);
            entry.putString("localPath", item.localPath);
            entry.putString("type", item.mimeType);
            entry.putDouble("size", (double) item.size);
            entry.putDouble("mtimeMs", (double) item.mtimeMs);
            entry.putDouble("section", 1d);
            entry.putString("sourceId", "usb");
            playlist.pushMap(entry);
        }
        payload.putArray("playlist", playlist);
        return payload;
    }

    private static class UsbState {
        final boolean mounted;
        final boolean hasPlayableMedia;
        final String mountPath;
        final List<String> mountPaths;
        final List<UsbMediaItem> playlist;

        UsbState(
                boolean mounted,
                boolean hasPlayableMedia,
                String mountPath,
                List<String> mountPaths,
                List<UsbMediaItem> playlist
        ) {
            this.mounted = mounted;
            this.hasPlayableMedia = hasPlayableMedia;
            this.mountPath = mountPath == null ? "" : mountPath;
            this.mountPaths = mountPaths == null ? new ArrayList<>() : mountPaths;
            this.playlist = playlist == null ? new ArrayList<>() : playlist;
        }

        static UsbState empty() {
            return new UsbState(false, false, "", new ArrayList<>(), new ArrayList<>());
        }

        static UsbState noPlayableMedia(List<String> mountPaths, boolean mounted) {
            String firstMount = mountPaths != null && !mountPaths.isEmpty() ? mountPaths.get(0) : "";
            return new UsbState(mounted, false, firstMount, mountPaths, new ArrayList<>());
        }

        static UsbState withPlaylist(String mountPath, List<String> mountPaths, List<File> files) {
            List<UsbMediaItem> playlist = new ArrayList<>();
            for (File file : files) {
                String name = file.getName();
                String absolutePath = file.getAbsolutePath();
                playlist.add(new UsbMediaItem(
                        name,
                        "usb://" + absolutePath,
                        Uri.fromFile(file).toString(),
                        absolutePath,
                        resolveMimeType(name),
                        file.length(),
                        file.lastModified()
                ));
            }
            return new UsbState(true, !playlist.isEmpty(), mountPath, mountPaths, playlist);
        }

        static UsbState withMediaItems(String mountPath, List<String> mountPaths, List<UsbMediaItem> playlist) {
            return new UsbState(true, playlist != null && !playlist.isEmpty(), mountPath, mountPaths, playlist);
        }

        boolean sameAs(UsbState other) {
            if (other == null) return false;
            if (mounted != other.mounted) return false;
            if (hasPlayableMedia != other.hasPlayableMedia) return false;
            if (!mountPath.equals(other.mountPath)) return false;
            if (mountPaths.size() != other.mountPaths.size()) return false;
            if (playlist.size() != other.playlist.size()) return false;
            for (int i = 0; i < mountPaths.size(); i += 1) {
                if (!mountPaths.get(i).equals(other.mountPaths.get(i))) return false;
            }
            for (int i = 0; i < playlist.size(); i += 1) {
                if (!playlist.get(i).sameAs(other.playlist.get(i))) return false;
            }
            return true;
        }
    }

    private static class UsbMediaItem {
        final String name;
        final String url;
        final String remoteUrl;
        final String localPath;
        final String mimeType;
        final long size;
        final long mtimeMs;

        UsbMediaItem(
                String name,
                String url,
                String remoteUrl,
                String localPath,
                String mimeType,
                long size,
                long mtimeMs
        ) {
            this.name = name == null ? "" : name;
            this.url = url == null ? "" : url;
            this.remoteUrl = remoteUrl == null ? "" : remoteUrl;
            this.localPath = localPath == null ? "" : localPath;
            this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
            this.size = size;
            this.mtimeMs = mtimeMs;
        }

        boolean sameAs(UsbMediaItem other) {
            if (other == null) return false;
            return name.equals(other.name)
                    && url.equals(other.url)
                    && remoteUrl.equals(other.remoteUrl)
                    && localPath.equals(other.localPath)
                    && mimeType.equals(other.mimeType)
                    && size == other.size
                    && mtimeMs == other.mtimeMs;
        }
    }

    private static String resolveMimeType(String fileName) {
        String lower = String.valueOf(fileName == null ? "" : fileName).toLowerCase(Locale.US);
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".m4v")) return "video/mp4";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".movie")) return "video/*";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    private static String normalizeMimeType(String fileName, String mimeType) {
        String safeMimeType = String.valueOf(mimeType == null ? "" : mimeType).trim();
        if (!safeMimeType.isEmpty()) return safeMimeType;
        return resolveMimeType(fileName);
    }
}
