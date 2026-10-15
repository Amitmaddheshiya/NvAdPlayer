package com.nvadplayer;

import android.app.PendingIntent;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.os.Process;
import android.util.Log;
import android.util.Base64;
import android.net.wifi.WifiManager;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import org.json.JSONArray;
import org.json.JSONObject;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Method;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

public class EmbeddedCmsServer extends NanoHTTPD {
    private static final String TAG = "EmbeddedCmsServer";
    public static final int DEFAULT_PORT = 9090;
    private final ReactApplicationContext reactContext;
    private final ConcurrentHashMap<String, JSONObject> discoveredDevices = new ConcurrentHashMap<>();
    private Thread udpBroadcastThread;
    private Thread udpListenerThread;
    private WifiManager.MulticastLock multicastLock;
    private boolean isRunning = false;
    private static final int DISCOVERY_PORT = 9091;
    private static final long DEVICE_EXPIRY_MS = 15_000L;


    private String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                for (InetAddress addr : addrs) {
                    if (!addr.isLoopbackAddress()) {
                        String sAddr = addr.getHostAddress();
                        boolean isIPv4 = sAddr.indexOf(':') < 0;
                        if (isIPv4) {
                            return sAddr;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    public void startUdpDiscovery() {
        if (isRunning) return;
        isRunning = true;
        try {
            WifiManager wifi = (WifiManager) reactContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                multicastLock = wifi.createMulticastLock(TAG + "-discovery");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "Unable to acquire multicast lock", e);
        }
        udpBroadcastThread = new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket();
                socket.setBroadcast(true);
                while (isRunning) {
                    try {
                        String ip = getLocalIpAddress();
                        JSONObject data = new JSONObject();
                        data.put("ip", ip);
                        data.put("model", Build.MODEL);
                        data.put("name", readConfigJson().optString("deviceName", Build.MODEL));
                        data.put("port", DEFAULT_PORT);
                        data.put("id", Build.MANUFACTURER + "-" + Build.MODEL + "-" + ip);
                        byte[] buffer = data.toString().getBytes();
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
                        socket.send(packet);
                    } catch (Exception ignored) {}
                    Thread.sleep(3000);
                }
                socket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error in UDP Broadcast Thread", e);
            }
        });
        udpBroadcastThread.start();

        udpListenerThread = new Thread(() -> {
            try {
                DatagramSocket socket = new DatagramSocket(DISCOVERY_PORT, InetAddress.getByName("0.0.0.0"));
                socket.setBroadcast(true);
                byte[] buffer = new byte[1024];
                while (isRunning) {
                    try {
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        socket.receive(packet);
                        String message = new String(packet.getData(), 0, packet.getLength());
                        JSONObject data = new JSONObject(message);
                        data.put("lastSeen", System.currentTimeMillis());
                        discoveredDevices.put(data.getString("ip"), data);

                        // Cleanup old devices
                        long now = System.currentTimeMillis();
                        for (String key : discoveredDevices.keySet()) {
                            JSONObject d = discoveredDevices.get(key);
                            if (now - d.optLong("lastSeen", now) > DEVICE_EXPIRY_MS) {
                                discoveredDevices.remove(key);
                            }
                        }
                    } catch (Exception ignored) {}
                }
                socket.close();
            } catch (Exception e) {
                Log.e(TAG, "Error in UDP Listener Thread", e);
            }
        });
        udpListenerThread.start();
    }

    public void stopUdpDiscovery() {
        isRunning = false;
        if (udpBroadcastThread != null) udpBroadcastThread.interrupt();
        if (udpListenerThread != null) udpListenerThread.interrupt();
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {}
        multicastLock = null;
    }

    private JSONArray getActiveDevices() {
        long now = System.currentTimeMillis();
        JSONObject self = new JSONObject();
        try {
            String ip = getLocalIpAddress();
            self.put("id", Build.MANUFACTURER + "-" + Build.MODEL + "-" + ip);
            self.put("ip", ip);
            self.put("model", Build.MODEL);
            self.put("name", readConfigJson().optString("deviceName", Build.MODEL));
            self.put("port", DEFAULT_PORT);
            self.put("lastSeen", now);
            discoveredDevices.put(ip, self);
        } catch (Exception ignored) {}

        JSONArray devices = new JSONArray();
        for (Map.Entry<String, JSONObject> entry : discoveredDevices.entrySet()) {
            JSONObject device = entry.getValue();
            if (now - device.optLong("lastSeen", now) <= DEVICE_EXPIRY_MS) {
                devices.put(device);
            } else {
                discoveredDevices.remove(entry.getKey(), device);
            }
        }
        return devices;
    }

    @Override
    public void start(int timeout, boolean daemon) throws java.io.IOException {
        super.start(timeout, daemon);
        startUdpDiscovery();
    }

    @Override
    public void stop() {
        stopUdpDiscovery();
        super.stop();
    }

    private String readTextFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            int size = fis.available();
            byte[] buffer = new byte[size];
            fis.read(buffer);
            return new String(buffer, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public EmbeddedCmsServer(ReactApplicationContext reactContext, int port) {
        super(port);
        this.reactContext = reactContext;
        try {
            File nvDir = getNvsignDir();
            File tmpDir = new File(nvDir, ".tmp");
            if (!tmpDir.exists()) {
                tmpDir.mkdirs();
            }
            System.setProperty("java.io.tmpdir", tmpDir.getAbsolutePath());
        } catch (Exception ignored) {}
    }

    private File getNvsignDir() {
        File extDir = Environment.getExternalStorageDirectory();
        File nvsign = new File(extDir, "nvsign");
        if (!nvsign.exists()) {
            nvsign.mkdirs();
        }
        return nvsign;
    }

    private File getConfigFile() {
        return new File(getNvsignDir(), "config.json");
    }

    /** Internal storage survives even when external-storage access is delayed at boot. */
    private File getPrivateConfigFile() {
        return new File(reactContext.getFilesDir(), "config.json");
    }

    private JSONObject readConfigFile(File file) {
        try {
            if (!file.exists() || file.length() <= 0) return null;
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] data = new byte[(int) file.length()];
                int offset = 0;
                while (offset < data.length) {
                    int count = fis.read(data, offset, data.length - offset);
                    if (count < 0) break;
                    offset += count;
                }
                return new JSONObject(new String(data, 0, offset, "UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "Ignoring invalid saved config: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    private JSONObject readConfigJson() {
        JSONObject externalConfig = readConfigFile(getConfigFile());
        if (externalConfig != null) return externalConfig;
        JSONObject privateConfig = readConfigFile(getPrivateConfigFile());
        if (privateConfig != null) return privateConfig;
        // Default Config
        JSONObject defaultConfig = new JSONObject();
        try {
            defaultConfig.put("slideDuration", 5000);
            defaultConfig.put("tickerText", "Thank You for Choosing NextView • Your Trusted Digital Signage Partner • Smart Displays. Professional Solutions. Reliable Support. • +91 92278 96944");
            defaultConfig.put("tickerTextColor", "#FFFFFF");
            defaultConfig.put("tickerBgColor", "#000000");
            defaultConfig.put("tickerPosition", "bottom");
            defaultConfig.put("tickerFontSize", 16);
            defaultConfig.put("tickerFontFamily", "sans-serif");
            defaultConfig.put("tickerSpeed", 5);
            defaultConfig.put("tickerDirection", "left");
            defaultConfig.put("mediaTransition", "fade");
            defaultConfig.put("usePendrive", false);
            defaultConfig.put("resizeMode", "stretch");
            defaultConfig.put("orientation", "horizontal");
            defaultConfig.put("layoutMode", "auto");
            defaultConfig.put("sectionRatio", "50_50");
            defaultConfig.put("showQrCode", true);
            defaultConfig.put("kioskMode", true);
        } catch (Exception ignored) {}
        return defaultConfig;
    }

    private void writeConfigJson(JSONObject config) {
        byte[] payload;
        try {
            payload = config.toString(2).getBytes("UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "Could not encode config", e);
            return;
        }
        writeConfigAtomically(getPrivateConfigFile(), payload);
        writeConfigAtomically(getConfigFile(), payload);
    }

    private void writeConfigAtomically(File target, byte[] payload) {
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(temp)) {
            fos.write(payload);
            fos.getFD().sync();
            if (target.exists() && !target.delete()) throw new java.io.IOException("Could not replace config");
            if (!temp.renameTo(target)) throw new java.io.IOException("Could not finalize config");
        } catch (Exception e) {
            Log.e(TAG, "Error writing config: " + target.getAbsolutePath(), e);
            if (temp.exists()) temp.delete();
        }
    }

    /** Decodes browser text sent as ASCII Base64 so emoji/symbols survive every HTTP parser. */
    private String decodeUtf8Base64(JSONObject data, String key) throws Exception {
        return new String(Base64.decode(data.getString(key), Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    /** Each ticker save toggles its trailing period: add when absent, remove when present. */
    private String toggleTickerTerminalPeriod(String value) {
        String text = value == null ? "" : value.trim();
        if (text.isEmpty()) return text;
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text + ".";
    }

    public String getSavedConfigJson() {
        return readConfigJson().toString();
    }

    public String getSavedLayoutJson() {
        File layoutFile = new File(getNvsignDir(), "custom_layout.json");
        return layoutFile.exists() ? readTextFile(layoutFile) : "{\"enabled\":false,\"zones\":[]}";
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        fileOrDirectory.delete();
    }

    private boolean isVideoFile(String fileName) {
        String name = String.valueOf(fileName == null ? "" : fileName).toLowerCase(java.util.Locale.US);
        return name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".mov")
                || name.endsWith(".mkv") || name.endsWith(".avi") || name.endsWith(".3gp")
                || name.endsWith(".3g2") || name.endsWith(".wmv") || name.endsWith(".flv")
                || name.endsWith(".mpg") || name.endsWith(".mpeg") || name.endsWith(".ts")
                || name.endsWith(".mts") || name.endsWith(".m2ts") || name.endsWith(".vob")
                || name.endsWith(".ogv") || name.endsWith(".asf") || name.endsWith(".f4v")
                || name.endsWith(".webm");
    }

    private boolean containsVideo(File sectionDir) {
        File[] files = sectionDir != null ? sectionDir.listFiles() : null;
        if (files == null) return false;
        for (File file : files) {
            if (file.isFile() && isVideoFile(file.getName())) return true;
        }
        return false;
    }

    /** A CMS may use video in two distinct sections; image-only sections are unrestricted. */
    private boolean canUploadVideoToSection(String section, String fileName) {
        if (!isVideoFile(fileName)) return true;
        File mediaRoot = getNvsignDir();
        File targetSection = new File(mediaRoot, section);
        // Existing video sections may receive more videos or replace a video.
        if (containsVideo(targetSection)) return true;

        int videoSectionCount = 0;
        File[] sections = mediaRoot.listFiles();
        if (sections != null) {
            for (File candidate : sections) {
                if (candidate.isDirectory() && containsVideo(candidate)) {
                    videoSectionCount++;
                }
            }
        }
        return videoSectionCount < 2;
    }

    private Response videoSectionLimitResponse() {
        return addCors(newFixedLengthResponse(
                Status.CONFLICT,
                "application/json",
                "{\"error\":\"Video uploads are allowed in only 2 sections. Delete videos from an existing video section before uploading here.\"}"
        ));
    }

    private Response handleStreamingUpload(IHTTPSession session) {
        try {
            // Get content-type and extract boundary
            String contentType = session.getHeaders().get("content-type");
            String boundary = null;
            if (contentType != null && contentType.contains("boundary=")) {
                boundary = contentType.substring(contentType.indexOf("boundary=") + 9);
                if (boundary.contains(";")) {
                    boundary = boundary.substring(0, boundary.indexOf(";"));
                }
                boundary = boundary.trim();
            }

            if (boundary == null) {
                Log.e(TAG, "No boundary found in content-type");
                return newFixedLengthResponse(Status.BAD_REQUEST, "application/json", "{\"error\":\"No boundary found\"}");
            }

            // Get section from query parameters
            Map<String, List<String>> params = session.getParameters();
            List<String> secList = params.get("section");
            String section = (secList != null && !secList.isEmpty()) ? secList.get(0) : "section1";

            // Get filename from query parameters
            List<String> fileNameList = params.get("filename");
            String fileName = (fileNameList != null && !fileNameList.isEmpty()) ? fileNameList.get(0) : "uploaded_large_file.mkv";
            fileName = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");

            if (!canUploadVideoToSection(section, fileName)) {
                return videoSectionLimitResponse();
            }

            File secDir = new File(getNvsignDir(), section);
            if (!secDir.exists()) {
                secDir.mkdirs();
            }

            File destFile = new File(secDir, fileName);
            if (destFile.exists()) {
                destFile.delete();
            }

            Log.d(TAG, "Starting multipart streaming upload to: " + destFile.getAbsolutePath() + ", boundary: " + boundary);

            // Parse multipart data and extract file content
            InputStream inputStream = session.getInputStream();
            byte[] boundaryBytes = ("\r\n--" + boundary).getBytes("UTF-8");
            byte[] endBoundaryBytes = ("\r\n--" + boundary + "--").getBytes("UTF-8");

            try (OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(destFile), 8388608)) {
                // Skip first boundary
                skipUntil(inputStream, boundaryBytes);
                skipLine(inputStream); // Skip \r\n after boundary

                // Skip headers until empty line
                skipHeaders(inputStream);

                // Now we're at the file data
                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytes = 0;

                while (true) {
                    // Read chunk
                    int pos = 0;
                    while (pos < buffer.length) {
                        int b = inputStream.read();
                        if (b == -1) break;

                        // Check for boundary
                        if (b == '\r' && pos > 0 && buffer[pos - 1] == '\n') {
                            // Potential boundary start
                            byte[] check = new byte[boundaryBytes.length];
                            check[0] = (byte) b;
                            int checkPos = 1;
                            while (checkPos < check.length) {
                                int cb = inputStream.read();
                                if (cb == -1) break;
                                check[checkPos++] = (byte) cb;
                            }

                            if (java.util.Arrays.equals(check, boundaryBytes)) {
                                // Found boundary, end of file
                                // Check if it's the end boundary
                                byte[] nextCheck = new byte[2];
                                nextCheck[0] = (byte) inputStream.read();
                                nextCheck[1] = (byte) inputStream.read();
                                if (nextCheck[0] == '-' && nextCheck[1] == '-') {
                                    // End boundary, we're done
                                    break;
                                } else {
                                    // Not end boundary, but we should be done with file
                                    break;
                                }
                            } else {
                                // Not a boundary, write the bytes we read
                                buffer[pos++] = (byte) b;
                                for (int k = 1; k < checkPos; k++) {
                                    if (pos < buffer.length) {
                                        buffer[pos++] = check[k];
                                    } else {
                                        out.write(buffer, 0, pos);
                                        out.write(check[k]);
                                        pos = 0;
                                        totalBytes += pos;
                                    }
                                }
                            }
                        } else {
                            buffer[pos++] = (byte) b;
                        }
                    }

                    if (pos > 0) {
                        out.write(buffer, 0, pos);
                        totalBytes += pos;
                        if (totalBytes % (200 * 1024 * 1024) == 0) {
                            Log.d(TAG, "Streamed " + (totalBytes / (1024 * 1024)) + " MB");
                        }
                    }

                    // Check if we hit end boundary
                    if (pos == 0) break;
                }

                out.flush();
                Log.d(TAG, "Streaming complete: " + (totalBytes / (1024 * 1024)) + " MB written");
            }

            emitEventToJS("media-updated", section);
            return newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true,\"file\":\"" + fileName + "\"}");
        } catch (Exception e) {
            Log.e(TAG, "Error in streaming upload", e);
            return newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", "{\"error\":\"Streaming upload failed: " + e.getMessage() + "\"}");
        }
    }

    private void skipUntil(InputStream in, byte[] pattern) throws Exception {
        int matchPos = 0;
        while (matchPos < pattern.length) {
            int b = in.read();
            if (b == -1) return;
            if (b == pattern[matchPos]) {
                matchPos++;
            } else {
                matchPos = 0;
            }
        }
    }

    private void skipLine(InputStream in) throws Exception {
        while (true) {
            int b = in.read();
            if (b == -1) return;
            if (b == '\n') return;
        }
    }

    private void skipHeaders(InputStream in) throws Exception {
        // Skip until empty line (\r\n\r\n)
        int prev = -1;
        while (true) {
            int b = in.read();
            if (b == -1) return;
            if (b == '\n' && prev == '\r') {
                // Check for another \r\n (end of headers)
                int next1 = in.read();
                int next2 = in.read();
                if (next1 == '\r' && next2 == '\n') {
                    return;
                } else {
                    // Not end of headers, continue
                    prev = next2;
                }
            }
            prev = b;
        }
    }

    public void emitEventToJS(String eventName, String payloadJson) {
        if (reactContext != null && reactContext.hasActiveCatalystInstance()) {
            try {
                WritableMap map = Arguments.createMap();
                map.putString("type", eventName);
                map.putString("payload", payloadJson);
                reactContext
                        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                        .emit("embeddedCmsEvent", map);
            } catch (Exception e) {
                Log.e(TAG, "Error emitting event to JS", e);
            }
        }
    }

    private Response addCors(Response res) {
        res.addHeader("Access-Control-Allow-Origin", "*");
        res.addHeader("Access-Control-Allow-Headers", "origin, content-type, accept, authorization");
        res.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD");
        res.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        res.addHeader("Pragma", "no-cache");
        return res;
    }
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if (Method.OPTIONS.equals(method)) {
            Response res = newFixedLengthResponse(Status.OK, "text/plain", "");
            res.addHeader("Access-Control-Allow-Origin", "*");
            res.addHeader("Access-Control-Allow-Headers", "origin, content-type, accept, authorization");
            res.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, HEAD");
            return res;
        }

        try {
            if (Method.GET.equals(method) && uri.equals("/api/devices")) {
                return addCors(newFixedLengthResponse(Status.OK, "application/json", getActiveDevices().toString()));
            }
            if (Method.GET.equals(method) && uri.equals("/")) {
                return addCors(newFixedLengthResponse(Status.OK, "text/html", getCmsHtml()));
            }

            if (Method.GET.equals(method) && uri.equals("/api/config")) {
                JSONObject config = readConfigJson();
                return addCors(newFixedLengthResponse(Status.OK, "application/json", config.toString()));
            }

            if (Method.POST.equals(method) && uri.equals("/api/config")) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String postData = files.get("postData");
                if (postData != null) {
                    JSONObject updates = new JSONObject(postData);
                    JSONObject newConfig = readConfigJson();
                    Iterator<String> keys = updates.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        if (key.equals("tickerTextBase64")) continue;
                        Object value = updates.get(key);

                        if (key.equals("tickerText") && value instanceof String) {
                            String tickerText = (String) value;
                            newConfig.put(key, toggleTickerTerminalPeriod(tickerText));
                        } else {
                            newConfig.put(key, value);
                        }
                    }
                    if (updates.has("tickerTextBase64")) {
                        newConfig.put("tickerText", toggleTickerTerminalPeriod(decodeUtf8Base64(updates, "tickerTextBase64")));
                    }
                    writeConfigJson(newConfig);
                    // Match the reference CMS: orientation is a native device
                    // command, not only a JS state update that may arrive late.
                    MainActivity.applyCmsOrientation(newConfig.optString("orientation", "horizontal"));
                    emitEventToJS("config-updated", newConfig.toString());
                    emitEventToJS("media-updated", "config-save");
                    return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}"));
                }
            }

            if (Method.POST.equals(method) && uri.equals("/api/device-name")) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String postData = files.get("postData");
                if (postData != null) {
                    String name = new JSONObject(postData).optString("name", "").trim();
                    if (name.isEmpty() || name.length() > 64) {
                        return addCors(newFixedLengthResponse(Status.BAD_REQUEST, "application/json", "{\"error\":\"TV name must be 1-64 characters\"}"));
                    }
                    JSONObject config = readConfigJson();
                    config.put("deviceName", name);
                    writeConfigJson(config);
                    return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}"));
                }
            }

            if (Method.POST.equals(method) && uri.equals("/api/reset-settings")) {
                File configFile = getConfigFile();
                if (configFile.exists()) configFile.delete();
                File layoutFile = new File(getNvsignDir(), "custom_layout.json");
                if (layoutFile.exists()) layoutFile.delete();
                JSONObject defaultConfig = readConfigJson();
                writeConfigJson(defaultConfig);
                emitEventToJS("config-updated", defaultConfig.toString());
                emitEventToJS("layout-updated", "{\"enabled\":false,\"zones\":[]}");
                emitEventToJS("clear-emergency", "{}");
                return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}"));
            }

            if (Method.GET.equals(method) && uri.equals("/api/media")) {
                File nvsign = getNvsignDir();
                JSONObject result = new JSONObject();
                File[] listDirs = nvsign.listFiles();
                if (listDirs != null) {
                    Arrays.sort(listDirs, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                    for (File secDir : listDirs) {
                        if (secDir.isDirectory() && !secDir.getName().startsWith(".")) {
                            String secName = secDir.getName();
                            JSONArray fileArr = new JSONArray();
                            File[] list = secDir.listFiles();
                            if (list != null) {
                                // Read _order.json if present
                                List<String> customOrder = new ArrayList<>();
                                File orderFile = new File(secDir, "_order.json");
                                if (orderFile.exists()) {
                                    try {
                                        String orderStr = readTextFile(orderFile);
                                        JSONArray orderJson = new JSONArray(orderStr);
                                        for (int i = 0; i < orderJson.length(); i++) {
                                            customOrder.add(orderJson.getString(i));
                                        }
                                    } catch (Exception ignored) {}
                                }

                                List<File> validFiles = new ArrayList<>();
                                for (File f : list) {
                                    if (f.isFile() && !f.getName().startsWith(".") && !f.getName().equalsIgnoreCase("_order.json")) {
                                        validFiles.add(f);
                                    }
                                }

                                if (!customOrder.isEmpty()) {
                                    validFiles.sort((a, b) -> {
                                        int idxA = customOrder.indexOf(a.getName());
                                        int idxB = customOrder.indexOf(b.getName());
                                        if (idxA != -1 && idxB != -1) return Integer.compare(idxA, idxB);
                                        if (idxA != -1) return -1;
                                        if (idxB != -1) return 1;
                                        return a.getName().compareToIgnoreCase(b.getName());
                                    });
                                } else {
                                    validFiles.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                                }

                                for (File f : validFiles) {
                                    JSONObject fObj = new JSONObject();
                                    fObj.put("name", f.getName());
                                    fObj.put("size", f.length());
                                    fObj.put("path", f.getAbsolutePath());
                                    fileArr.put(fObj);
                                }
                            }
                            result.put(secName, fileArr);
                        }
                    }
                }
                return addCors(newFixedLengthResponse(Status.OK, "application/json", result.toString()));
            }

            if (Method.POST.equals(method) && uri.equals("/api/reorder-media")) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String postData = files.get("postData");
                if (postData != null) {
                    JSONObject body = new JSONObject(postData);
                    String section = body.optString("section", "section1");
                    JSONArray fileOrder = body.optJSONArray("fileOrder");
                    if (fileOrder != null) {
                        File secDir = new File(getNvsignDir(), section);
                        if (secDir.exists() && secDir.isDirectory()) {
                            File orderFile = new File(secDir, "_order.json");
                            try (FileWriter writer = new FileWriter(orderFile)) {
                                writer.write(fileOrder.toString(2));
                            }
                            emitEventToJS("media-updated", section);
                            return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}"));
                        }
                    }
                }
                return addCors(newFixedLengthResponse(Status.BAD_REQUEST, "application/json", "{\"error\":\"Failed to save file order\"}"));
            }

            if (Method.POST.equals(method) && uri.equals("/api/restart-app")) {
                try {
                    Intent launchIntent = reactContext.getPackageManager().getLaunchIntentForPackage(reactContext.getPackageName());
                    if (launchIntent != null) {
                        launchIntent.addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                            Intent.FLAG_ACTIVITY_SINGLE_TOP |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                        );
                        PendingIntent pendingIntent = PendingIntent.getActivity(
                            reactContext,
                            9999,
                            launchIntent,
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE :
                                PendingIntent.FLAG_UPDATE_CURRENT
                        );
                        AlarmManager alarmManager = (AlarmManager) reactContext.getSystemService(Context.ALARM_SERVICE);
                        long restartAt = SystemClock.elapsedRealtime() + 1200L;
                        if (alarmManager != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, restartAt, pendingIntent);
                            } else {
                                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, restartAt, pendingIntent);
                            }
                        } else {
                            pendingIntent.send();
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error triggering restart launch intent", e);
                }
                new Thread(() -> {
                    try { Thread.sleep(700); } catch (Exception ignored) {}
                    Process.killProcess(Process.myPid());
                }).start();
                return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}"));
            }

            if (Method.POST.equals(method) && uri.equals("/api/emergency-alert")) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String postData = files.get("postData");
                if (postData != null) {
                    JSONObject alert = new JSONObject(postData);
                    if (alert.has("titleBase64")) alert.put("title", decodeUtf8Base64(alert, "titleBase64"));
                    if (alert.has("messageBase64")) alert.put("message", decodeUtf8Base64(alert, "messageBase64"));
                    emitEventToJS("emergency-alert", alert.toString());
                    return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}"));
                }
            }

            if (Method.POST.equals(method) && uri.equals("/api/clear-emergency")) {
                emitEventToJS("clear-emergency", "{}");
                return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}"));
            }

            if (Method.POST.equals(method) && uri.equals("/api/save-layout")) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String postData = files.get("postData");
                if (postData != null) {
                    File layoutFile = new File(getNvsignDir(), "custom_layout.json");
                    try (FileWriter writer = new FileWriter(layoutFile)) {
                        writer.write(postData);
                    } catch (Exception e) {
                        Log.e(TAG, "Error saving custom layout", e);
                    }
                    emitEventToJS("layout-updated", postData);
                    return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}"));
                }
            }

            if (Method.GET.equals(method) && uri.equals("/api/get-layout")) {
                File layoutFile = new File(getNvsignDir(), "custom_layout.json");
                if (layoutFile.exists()) {
                    String layoutContent = readTextFile(layoutFile);
                    return addCors(newFixedLengthResponse(Status.OK, "application/json", layoutContent));
                }
                return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"enabled\":false,\"zones\":[]}"));
            }

            if (Method.POST.equals(method) && uri.equals("/api/create-section")) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String postData = files.get("postData");
                if (postData != null) {
                    JSONObject body = new JSONObject(postData);
                    String sectionName = body.optString("sectionName", "").trim().toLowerCase();
                    if (!sectionName.isEmpty()) {
                        sectionName = sectionName.replaceAll("[^a-zA-Z0-9_-]", "");
                        File newSecDir = new File(getNvsignDir(), sectionName);
                        if (!newSecDir.exists()) {
                            newSecDir.mkdirs();
                        }
                        emitEventToJS("media-updated", sectionName);
                        return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}"));
                    }
                }
            }

            if (Method.POST.equals(method) && uri.equals("/api/rename-section")) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String postData = files.get("postData");
                if (postData != null) {
                    JSONObject body = new JSONObject(postData);
                    String oldName = body.optString("oldName", "").trim();
                    String newName = body.optString("newName", "").trim().toLowerCase();
                    newName = newName.replaceAll("[^a-zA-Z0-9_-]", "");
                    if (!oldName.isEmpty() && !newName.isEmpty()) {
                        File oldSecDir = new File(getNvsignDir(), oldName);
                        File newSecDir = new File(getNvsignDir(), newName);
                        if (oldSecDir.exists() && oldSecDir.isDirectory()) {
                            boolean success = oldSecDir.renameTo(newSecDir);
                            if (success) {
                                emitEventToJS("media-updated", newName);
                                return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}"));
                            }
                        }
                    }
                }
                return addCors(newFixedLengthResponse(Status.BAD_REQUEST, "application/json", "{\"error\":\"Failed to rename section\"}"));
            }

            if (Method.POST.equals(method) && uri.equals("/api/delete-section")) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String postData = files.get("postData");
                if (postData != null) {
                    JSONObject body = new JSONObject(postData);
                    String sectionName = body.optString("sectionName", "").trim();
                    if (!sectionName.isEmpty()) {
                        File secDir = new File(getNvsignDir(), sectionName);
                        if (secDir.exists() && secDir.isDirectory()) {
                            deleteRecursive(secDir);
                        }
                        emitEventToJS("media-updated", sectionName);
                        return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}"));
                    }
                }
            }

            if (Method.POST.equals(method) && uri.equals("/api/upload")) {
                try {
                    // Check content length first
                    String contentLengthStr = session.getHeaders().get("content-length");
                    long contentLength = contentLengthStr != null ? Long.parseLong(contentLengthStr) : 0;

                    Log.d(TAG, "Upload request received, content-length: " + (contentLength / (1024 * 1024)) + " MB");

                    // For large files (>500MB), use direct streaming to avoid parseBody issues
                    if (contentLength > 500 * 1024 * 1024) {
                        Log.d(TAG, "Using direct streaming for large file");
                        return handleStreamingUpload(session);
                    }

                    // For smaller files, use standard parseBody
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    Map<String, List<String>> parameters = session.getParameters();

                    List<String> secList = parameters.get("section");
                    String section = (secList != null && !secList.isEmpty()) ? secList.get(0) : "section1";

                    List<String> fileNames = parameters.get("file");
                    String originalFileName = (fileNames != null && !fileNames.isEmpty()) ? fileNames.get(0) : "uploaded_media.mp4";

                    originalFileName = originalFileName.replaceAll("[^a-zA-Z0-9._-]", "_");

                    if (!canUploadVideoToSection(section, originalFileName)) {
                        return videoSectionLimitResponse();
                    }

                    File secDir = new File(getNvsignDir(), section);
                    if (!secDir.exists()) {
                        secDir.mkdirs();
                    }

                    String tmpPath = files.get("file");
                    if (tmpPath != null) {
                        File tmpFile = new File(tmpPath);
                        File destFile = new File(secDir, originalFileName);

                        Log.d(TAG, "Processing upload: " + originalFileName + " (" + (tmpFile.length() / (1024 * 1024)) + " MB)");

                        if (destFile.exists()) {
                            destFile.delete();
                        }

                        boolean moved = tmpFile.renameTo(destFile);
                        if (!moved) {
                            Log.d(TAG, "Rename failed, using copy method");
                            try (InputStream in = new java.io.BufferedInputStream(new FileInputStream(tmpFile), 4194304);
                                 OutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(destFile), 4194304)) {
                                byte[] buf = new byte[4194304];
                                int len;
                                long totalBytes = 0;
                                while ((len = in.read(buf)) > 0) {
                                    out.write(buf, 0, len);
                                    totalBytes += len;
                                    if (totalBytes % (100 * 1024 * 1024) == 0) {
                                        Log.d(TAG, "Copied " + (totalBytes / (1024 * 1024)) + " MB");
                                    }
                                }
                                out.flush();
                                Log.d(TAG, "File copy complete: " + (totalBytes / (1024 * 1024)) + " MB");
                            } catch (Exception e) {
                                Log.e(TAG, "Error saving uploaded file", e);
                                return addCors(newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", "{\"error\":\"File save failed: " + e.getMessage() + "\"}"));
                            }
                            try { tmpFile.delete(); } catch (Exception ignored) {}
                        }

                        emitEventToJS("media-updated", section);
                        return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true,\"file\":\"" + originalFileName + "\"}"));
                    } else {
                        return addCors(newFixedLengthResponse(Status.BAD_REQUEST, "application/json", "{\"error\":\"No file data received\"}"));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error in file upload", e);
                    return addCors(newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", "{\"error\":\"Upload failed: " + e.getMessage() + "\"}"));
                }
            }

            if (Method.POST.equals(method) && uri.equals("/api/delete")) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String postData = files.get("postData");
                if (postData != null) {
                    JSONObject body = new JSONObject(postData);
                    String section = body.optString("section", "section1");
                    String fileName = body.optString("fileName", "");

                    File fileToDelete = new File(new File(getNvsignDir(), section), fileName);
                    if (fileToDelete.exists()) {
                        fileToDelete.delete();
                        emitEventToJS("media-updated", section);
                        return addCors(newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}"));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error serving HTTP request", e);
            return addCors(newFixedLengthResponse(Status.INTERNAL_ERROR, "application/json", "{\"error\":\"" + e.getMessage() + "\"}"));
        }

        return addCors(newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "404 Not Found"));
    }

    private String getCmsHtml() {
        // Append each fragment at runtime; Java limits a single class-file string constant to 65,535 bytes.
        StringBuilder html = new StringBuilder(98304);
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n");
        html.append("<head>\n");
        html.append("  <meta charset=\"UTF-8\">\n");
        html.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("  <title>NvAdPlayer Control Center & CMS</title>\n");
        html.append("  <link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=Montserrat:wght@600;700&family=Oswald:wght@600&family=Poppins:wght@600&display=swap\" rel=\"stylesheet\">\n");
        html.append("  <style>\n");
        html.append("    :root {\n");
        html.append("      --bg-primary: #0c0d12;\n");
        html.append("      --bg-card: #131520;\n");
        html.append("      --bg-input: #0d0e15;\n");
        html.append("      --text-main: #f8fafc;\n");
        html.append("      --text-muted: #94a3b8;\n");
        html.append("      --border: #1e293b;\n");
        html.append("      --accent: #6366f1;\n");
        html.append("      --accent-hover: #4f46e5;\n");
        html.append("      --ip-badge-bg: #1e293b;\n");
        html.append("      --upload-bg: #0d0e15;\n");
        html.append("    }\n");
        html.append("    body.light-theme {\n");
        html.append("      --bg-primary: #f8fafc;\n");
        html.append("      --bg-card: #ffffff;\n");
        html.append("      --bg-input: #f1f5f9;\n");
        html.append("      --text-main: #0f172a;\n");
        html.append("      --text-muted: #475569;\n");
        html.append("      --border: #cbd5e1;\n");
        html.append("      --accent: #4f46e5;\n");
        html.append("      --accent-hover: #4338ca;\n");
        html.append("      --ip-badge-bg: #e2e8f0;\n");
        html.append("      --upload-bg: #f8fafc;\n");
        html.append("    }\n");
        html.append("    * { box-sizing: border-box; margin: 0; padding: 0; font-family: 'Inter', sans-serif; transition: background-color 0.2s, color 0.2s, border-color 0.2s; }\n");
        html.append("    body { background-color: var(--bg-primary); color: var(--text-main); padding: 24px; min-height: 100vh; }\n");
        html.append("    .container { max-width: 1000px; margin: 0 auto; }\n");
        html.append("    .header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid var(--border); gap: 12px; flex-wrap: wrap; }\n");
        html.append("    .header-right { display: flex; align-items: center; gap: 12px; }\n");
        html.append("    .header h1 { font-size: 24px; font-weight: 800; background: linear-gradient(135deg, #38bdf8, #818cf8); -webkit-background-clip: text; -webkit-text-fill-color: transparent; }\n");
        html.append("    .ip-badge { background-color: var(--ip-badge-bg); border: 1px solid var(--border); color: #38bdf8; font-size: 13px; font-weight: 600; padding: 8px 14px; border-radius: 8px; }\n");
        html.append("    .theme-toggle-btn { background-color: var(--bg-card); border: 1px solid var(--border); color: var(--text-main); font-size: 13px; font-weight: 600; padding: 8px 14px; border-radius: 8px; cursor: pointer; display: flex; align-items: center; gap: 6px; }\n");
        html.append("    .theme-toggle-btn:hover { border-color: #38bdf8; }\n");
        html.append("    .tabs { display: flex; gap: 12px; margin-bottom: 24px; }\n");
        html.append("    .tab-btn { background-color: var(--bg-card); border: 1.5px solid var(--border); color: var(--text-muted); padding: 12px 20px; border-radius: 10px; font-size: 14px; font-weight: 600; cursor: pointer; transition: all 0.2s; }\n");
        html.append("    .tab-btn.active { background-color: var(--accent); border-color: var(--accent); color: #ffffff; box-shadow: 0 4px 12px rgba(99, 102, 241, 0.3); }\n");
        html.append("    .panel { display: none; background-color: var(--bg-card); border: 1px solid var(--border); border-radius: 16px; padding: 24px; margin-bottom: 24px; }\n");
        html.append("    .panel.active { display: block; }\n");
        html.append("    .config-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 20px; }\n");
        html.append("    .full-width { grid-column: 1 / -1; }\n");
        html.append("    .form-group { margin-bottom: 0; }\n");
        html.append("    .form-label { display: block; font-size: 13px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; margin-bottom: 8px; }\n");
        html.append("    .form-input, select, textarea { width: 100%; background-color: var(--bg-input); border: 1px solid var(--border); color: var(--text-main); padding: 12px; border-radius: 8px; font-size: 14px; outline: none; transition: border 0.2s; }\n");
        html.append("    .form-input:focus, select:focus, textarea:focus { border-color: #38bdf8; }\n");
        html.append("    .color-picker-group { display: flex; gap: 10px; align-items: center; }\n");
        html.append("    .color-picker-swatch { width: 46px; height: 46px; border: 1px solid var(--border); border-radius: 8px; cursor: pointer; padding: 0; background: none; flex-shrink: 0; }\n");
        html.append("    .layout-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(170px, 1fr)); gap: 14px; margin-top: 8px; }\n");
        html.append("    .layout-card { background-color: var(--bg-input); border: 2px solid var(--border); border-radius: 12px; padding: 12px; cursor: pointer; text-align: center; transition: all 0.2s; }\n");
        html.append("    .layout-card:hover { border-color: #38bdf8; transform: translateY(-2px); }\n");
        html.append("    .layout-card.active { border-color: #38bdf8; background-color: rgba(56, 189, 248, 0.12); box-shadow: 0 0 14px rgba(56, 189, 248, 0.25); }\n");
        html.append("    .layout-preview { width: 100%; height: 75px; background-color: var(--bg-card); border: 1px solid var(--border); border-radius: 8px; margin-bottom: 8px; padding: 4px; display: flex; gap: 4px; box-sizing: border-box; }\n");
        html.append("    .layout-preview div { background-color: rgba(99, 102, 241, 0.25); border: 1px solid #6366f1; border-radius: 4px; color: var(--text-main); font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; }\n");
        html.append("    .layout-preview.grid-auto { display: grid; grid-template-columns: 1fr 1fr; grid-template-rows: 1fr 1fr; }\n");
        html.append("    .layout-preview.stack-vert { flex-direction: column; }\n");
        html.append("    .layout-preview.stack-vert div { flex: 1; width: 100%; }\n");
        html.append("    .layout-preview.stack-horiz { flex-direction: row; }\n");
        html.append("    .layout-preview.stack-horiz div { flex: 1; height: 100%; }\n");
        html.append("    .layout-preview.top2-bot1 { flex-direction: column; gap: 4px; }\n");
        html.append("    .layout-preview.top2-bot1 .top-row { display: flex; gap: 4px; flex: 1; width: 100%; background: none; border: none; padding: 0; }\n");
        html.append("    .layout-preview.top2-bot1 .top-row div { flex: 1; height: 100%; }\n");
        html.append("    .layout-preview.top2-bot1 .bot-row { flex: 1; width: 100%; }\n");
        html.append("    .layout-preview.top1-bot2 { flex-direction: column; gap: 4px; }\n");
        html.append("    .layout-preview.top1-bot2 .top-row { flex: 1; width: 100%; }\n");
        html.append("    .layout-preview.top1-bot2 .bot-row { display: flex; gap: 4px; flex: 1; width: 100%; background: none; border: none; padding: 0; }\n");
        html.append("    .layout-preview.top1-bot2 .bot-row div { flex: 1; height: 100%; }\n");
        html.append("    .layout-title { font-size: 12px; font-weight: 700; color: var(--text-main); }\n");
        html.append("    .ratio-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px; margin-top: 8px; }\n");
        html.append("    .ratio-card { background-color: var(--bg-input); border: 2px solid var(--border); border-radius: 10px; padding: 10px; cursor: pointer; text-align: center; transition: all 0.2s; }\n");
        html.append("    .ratio-card:hover { border-color: #38bdf8; transform: translateY(-2px); }\n");
        html.append("    .ratio-card.active { border-color: #38bdf8; background-color: rgba(56, 189, 248, 0.12); box-shadow: 0 0 12px rgba(56, 189, 248, 0.25); }\n");
        html.append("    .ratio-bar { display: flex; gap: 3px; height: 36px; width: 100%; margin-bottom: 6px; }\n");
        html.append("    .ratio-bar div { background-color: rgba(99, 102, 241, 0.3); border: 1px solid #6366f1; border-radius: 4px; color: var(--text-main); font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; }\n");
        html.append("    .ratio-title { font-size: 11px; font-weight: 700; color: var(--text-main); }\n");
        html.append("    .btn-primary { background-color: var(--accent); border: none; color: #ffffff; font-size: 15px; font-weight: 700; padding: 14px 28px; border-radius: 10px; cursor: pointer; transition: background 0.2s; width: 100%; margin-top: 10px; }\n");
        html.append("    .btn-primary:hover { background-color: var(--accent-hover); }\n");
        html.append("    .sec-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; flex-wrap: wrap; gap: 12px; }\n");
        html.append("    .sec-tabs { display: flex; gap: 8px; margin-bottom: 16px; overflow-x: auto; flex-wrap: wrap; }\n");
        html.append("    .sec-tab { background-color: var(--bg-input); border: 1px solid var(--border); color: var(--text-muted); padding: 10px 16px; border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; }\n");
        html.append("    .sec-tab.active { background-color: #0284c7; border-color: #0284c7; color: #ffffff; }\n");
        html.append("    .btn-action { background-color: #10b981; border: none; color: #fff; padding: 8px 14px; border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; }\n");
        html.append("    .btn-edit { background-color: #f59e0b; border: none; color: #fff; padding: 8px 14px; border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; }\n");
        html.append("    .btn-danger { background-color: #ef4444; border: none; color: #fff; padding: 8px 14px; border-radius: 8px; font-size: 13px; font-weight: 600; cursor: pointer; }\n");
        html.append("    .upload-box { border: 2px dashed var(--border); border-radius: 12px; padding: 32px; text-align: center; background-color: var(--upload-bg); cursor: pointer; margin-bottom: 20px; transition: all 0.2s; }\n");
        html.append("    .upload-box:hover, .upload-box.dragover { border-color: #38bdf8; background-color: var(--bg-card); }\n");
        html.append("    .progress-box { background-color: var(--bg-input); border: 1px solid var(--border); border-radius: 12px; padding: 16px; margin-bottom: 20px; display: none; }\n");
        html.append("    .progress-info { display: flex; justify-content: space-between; font-size: 14px; font-weight: 700; margin-bottom: 8px; color: var(--text-main); }\n");
        html.append("    .progress-track { width: 100%; height: 12px; background-color: var(--border); border-radius: 6px; overflow: hidden; margin-bottom: 8px; }\n");
        html.append("    .progress-fill { height: 100%; background: linear-gradient(90deg, #38bdf8, #6366f1); width: 0%; transition: width 0.15s ease-out; }\n");
        html.append("    .progress-sub { font-size: 12px; color: var(--text-muted); }\n");
        html.append("    .file-list { display: flex; flex-direction: column; gap: 10px; }\n");
        html.append("    .file-item { display: flex; align-items: center; justify-content: space-between; background-color: var(--bg-input); border: 1px solid var(--border); padding: 12px 16px; border-radius: 8px; }\n");
        html.append("    .file-info { display: flex; align-items: center; gap: 12px; }\n");
        html.append("    .file-name { font-size: 14px; font-weight: 600; color: var(--text-main); }\n");
        html.append("    .file-size { font-size: 12px; color: var(--text-muted); }\n");
        html.append("    .btn-del { background-color: #ef4444; border: none; color: #fff; padding: 6px 12px; border-radius: 6px; font-size: 12px; font-weight: 600; cursor: pointer; }\n");
        html.append("    .canvas-container { display: flex; gap: 20px; flex-wrap: wrap; margin-top: 16px; }\n");
        html.append("    .canvas-screen { flex: 2; min-width: 320px; aspect-ratio: 16/9; background: #000; border: 2px solid var(--accent); border-radius: 12px; position: relative; overflow: hidden; touch-action: none; }\n");
        html.append("    .canvas-controls { flex: 1; min-width: 280px; background: var(--bg-input); border: 1px solid var(--border); padding: 16px; border-radius: 12px; }\n");
        html.append("    .zone-box { position: absolute; border: 2px dashed #38bdf8; background: rgba(56, 189, 248, 0.2); border-radius: 6px; display: flex; align-items: center; justify-content: center; color: #fff; font-weight: bold; font-size: 12px; cursor: move; user-select: none; box-sizing: border-box; touch-action: none; }\n");
        html.append("    .zone-box.active { border: 2px solid #4ade80; background: rgba(74, 222, 128, 0.25); box-shadow: 0 0 10px rgba(74,222,128,0.5); }\n");
        html.append("    .resize-handle { position: absolute; right: -10px; bottom: -10px; width: 28px; height: 28px; background: #4ade80; border: 2px solid #ffffff; border-radius: 50%; cursor: se-resize; z-index: 10; touch-action: none; box-sizing: border-box; }\n");
        html.append("    .emergency-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 16px; margin-top: 16px; }\n");
        html.append("    .emergency-card { background: var(--bg-input); border: 2px solid var(--border); padding: 18px; border-radius: 12px; cursor: pointer; transition: all 0.2s; }\n");
        html.append("    .emergency-card:hover, .emergency-card.active { border-color: #ef4444; background: rgba(239, 68, 68, 0.15); }\n");
        html.append("    .alert-success { background-color: rgba(34, 197, 94, 0.15); border: 1px solid #22c55e; color: #4ade80; }\n");
        html.append("    .tv-target-bar { margin: 0 0 16px; padding: 12px; border: 1px solid var(--border); border-radius: 10px; background: var(--bg-card); }\n");
        html.append("    .tv-target-title { display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; font-size:13px; font-weight:700; }\n");
        html.append("    .tv-target-actions { display:flex; gap:8px; flex-wrap:wrap; }\n");
        html.append("    .tv-target-actions button { border:0; border-radius:6px; padding:6px 9px; font-weight:700; cursor:pointer; background:#0284c7; color:#fff; }\n");
        html.append("    .tv-target-actions button:last-child { background:#64748b; }\n");
        html.append("    .tv-target-list { display:flex; gap:8px; flex-wrap:wrap; margin-top:10px; }\n");
        html.append("    .tv-target-chip { display:flex; align-items:center; gap:6px; padding:7px 9px; border:1px solid var(--border); border-radius:7px; font-size:12px; cursor:pointer; }\n");
        html.append("    .tv-target-chip input { accent-color:#0284c7; }\n");
        html.append("    @media (max-width: 768px) {\n");
        html.append("      body { padding: 12px; }\n");
        html.append("      .container { max-width: 100%; }\n");
        html.append("      .header { flex-direction: column; align-items: flex-start; gap: 12px; }\n");
        html.append("      .header-right { flex-wrap: wrap; width: 100%; }\n");
        html.append("      .header h1 { font-size: 18px; }\n");
        html.append("      .ip-badge { font-size: 11px; padding: 6px 10px; }\n");
        html.append("      .theme-toggle-btn { font-size: 11px; padding: 6px 10px; }\n");
        html.append("      .tabs { flex-wrap: wrap; gap: 8px; }\n");
        html.append("      .tab-btn { font-size: 12px; padding: 10px 14px; flex: 1 1 calc(50% - 4px); min-width: 140px; }\n");
        html.append("      .panel { padding: 16px; }\n");
        html.append("      .config-grid { grid-template-columns: 1fr; gap: 16px; }\n");
        html.append("      .layout-grid { grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 10px; }\n");
        html.append("      .ratio-grid { grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 10px; }\n");
        html.append("      .emergency-grid { grid-template-columns: 1fr; gap: 12px; }\n");
        html.append("      .canvas-container { flex-direction: column; }\n");
        html.append("      .canvas-screen { min-width: 100%; }\n");
        html.append("      .canvas-controls { min-width: 100%; }\n");
        html.append("      .sec-header { flex-direction: column; align-items: flex-start; }\n");
        html.append("      .sec-tabs { flex-wrap: nowrap; overflow-x: auto; -webkit-overflow-scrolling: touch; }\n");
        html.append("      .sec-tab { white-space: nowrap; }\n");
        html.append("      .btn-action, .btn-edit, .btn-danger { font-size: 12px; padding: 8px 12px; }\n");
        html.append("      .btn-primary { font-size: 14px; padding: 12px 20px; }\n");
        html.append("      .upload-box { padding: 20px; }\n");
        html.append("      .upload-box p:first-child { font-size: 28px; }\n");
        html.append("      .upload-box p:nth-child(2) { font-size: 14px; }\n");
        html.append("      .file-item { flex-direction: column; align-items: flex-start; gap: 8px; }\n");
        html.append("      .file-info { width: 100%; }\n");
        html.append("      .btn-del { width: 100%; }\n");
        html.append("      .form-label { font-size: 12px; }\n");
        html.append("      .form-input, select, textarea { font-size: 13px; padding: 10px; }\n");
        html.append("      .layout-preview { height: 60px; }\n");
        html.append("      .ratio-bar { height: 30px; }\n");
        html.append("    }\n");
        html.append("    @media (max-width: 480px) {\n");
        html.append("      body { padding: 8px; }\n");
        html.append("      .header h1 { font-size: 16px; }\n");
        html.append("      .tab-btn { font-size: 11px; padding: 8px 10px; flex: 1 1 100%; min-width: auto; }\n");
        html.append("      .panel { padding: 12px; }\n");
        html.append("      .layout-grid { grid-template-columns: 1fr 1fr; gap: 8px; }\n");
        html.append("      .ratio-grid { grid-template-columns: 1fr 1fr; gap: 8px; }\n");
        html.append("      .btn-action, .btn-edit, .btn-danger { font-size: 11px; padding: 6px 10px; }\n");
        html.append("      .btn-primary { font-size: 13px; padding: 10px 16px; }\n");
        html.append("      .upload-box { padding: 16px; }\n");
        html.append("      .upload-box p:first-child { font-size: 24px; }\n");
        html.append("      .upload-box p:nth-child(2) { font-size: 13px; }\n");
        html.append("      .progress-box { padding: 12px; }\n");
        html.append("      .layout-preview { height: 50px; }\n");
        html.append("      .ratio-bar { height: 26px; }\n");
        html.append("      .color-picker-swatch { width: 40px; height: 40px; }\n");
        html.append("    }\n");
        html.append("  </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("  <div class=\"container\">\n");
        html.append("    <div class=\"header\">\n");
        html.append("      <h1>📺 NvAd Control Center</h1>\n");
        html.append("      <div class=\"header-right\">\n");
        html.append("        <button class=\"btn-action\" style=\"background:#dc2626; border:none; font-size:12px; padding:6px 12px; cursor:pointer;\" onclick=\"resetTvSettings()\">↺ Reset Settings</button>\n");
        html.append("        <button class=\"btn-action\" style=\"background:#ea580c; border:none; font-size:12px; padding:6px 12px; cursor:pointer;\" onclick=\"restartTvApp()\">🔄 Restart TV Player</button>\n");
        html.append("        <button class=\"theme-toggle-btn\" id=\"themeBtn\" onclick=\"toggleTheme()\">\n");
        html.append("          <span id=\"themeIcon\">☀️</span> <span id=\"themeLabel\">Light Mode</span>\n");
        html.append("        </button>\n");
        html.append("        <div class=\"ip-badge\" id=\"cmsUrl\">CMS Web Port: 9090</div>\n");
        html.append("      </div>\n");
        html.append("    </div>\n");
        html.append("    <div class=\"tv-target-bar\">\n");
        html.append("      <div class=\"tv-target-title\"><span id=\"tvTargetSummary\">Loading TVs on this network...</span><div class=\"tv-target-actions\"><button type=\"button\" onclick=\"selectAllTvs()\">All TVs</button><button type=\"button\" onclick=\"clearAllTvs()\">Clear all</button></div></div>\n");
        html.append("      <div class=\"tv-target-list\" id=\"tvTargetList\"></div>\n");
        html.append("    </div>\n");
        html.append("    <div class=\"tabs\">\n");
        html.append("      <button class=\"tab-btn active\" onclick=\"switchTab('configTab', this)\">⚙️ Player Settings</button>\n");
        html.append("      <button class=\"tab-btn\" onclick=\"switchTab('mediaTab', this)\">📁 Section Media Manager</button>\n");
        html.append("      <button class=\"tab-btn\" onclick=\"switchTab('canvasTab', this)\">🎨 Visual Canvas Builder</button>\n");
        html.append("      <button class=\"tab-btn\" onclick=\"switchTab('eabsTab', this)\">🚨 Emergency Alerts (EABS)</button>\n");
        html.append("      <button class=\"tab-btn\" onclick=\"switchTab('widgetsTab', this)\">🌐 Live Widgets & Streams</button>\n");
        html.append("    </div>\n");
        html.append("    <div id=\"alertMsg\" class=\"alert alert-success\">Settings updated successfully!</div>\n");
        html.append("    <!-- Config Settings Panel -->\n");
        html.append("    <div id=\"configTab\" class=\"panel active\">\n");
        html.append("      <form id=\"configForm\" onsubmit=\"saveConfig(event)\">\n");
        html.append("        <div class=\"config-grid\">\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">Screen Orientation</label>\n");
        html.append("            <select id=\"orientation\">\n");
        html.append("              <option value=\"horizontal\">Horizontal (Landscape 0°)</option>\n");
        html.append("              <option value=\"reverse-horizontal\">Reverse Horizontal (180°)</option>\n");
        html.append("              <option value=\"vertical\">Vertical (Portrait 90°)</option>\n");
        html.append("              <option value=\"reverse-vertical\">Reverse Vertical (-90°)</option>\n");
        html.append("            </select>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">Slide Duration (Seconds)</label>\n");
        html.append("            <select id=\"slideDuration\">\n");
        html.append("              <option value=\"3000\">3 Seconds</option>\n");
        html.append("              <option value=\"5000\">5 Seconds</option>\n");
        html.append("              <option value=\"10000\">10 Seconds</option>\n");
        html.append("              <option value=\"15000\">15 Seconds</option>\n");
        html.append("              <option value=\"30000\">30 Seconds</option>\n");
        html.append("            </select>\n");
        html.append("          </div>\n");
        html.append("          <!-- Layout Options Grid -->\n");
        html.append("          <div class=\"full-width\">\n");
        html.append("            <label class=\"form-label\">Multi-Section Screen Layout Options</label>\n");
        html.append("            <p style=\"font-size: 12px; color: var(--text-muted); margin-bottom: 8px;\">Select how sections will be arranged on TV screen:</p>\n");
        html.append("            <div class=\"layout-grid\">\n");
        html.append("              <div class=\"layout-card active\" data-layout=\"auto\" onclick=\"selectLayoutCard('auto', this)\">\n");
        html.append("                <div class=\"layout-preview grid-auto\"><div>1</div><div>2</div><div>3</div><div>4</div></div>\n");
        html.append("                <div class=\"layout-title\">Auto / Responsive Grid</div>\n");
        html.append("              </div>\n");
        html.append("              <div class=\"layout-card\" data-layout=\"stack_vertical\" onclick=\"selectLayoutCard('stack_vertical', this)\">\n");
        html.append("                <div class=\"layout-preview stack-vert\"><div>1</div><div>2</div><div>3</div></div>\n");
        html.append("                <div class=\"layout-title\">Stack Vertical</div>\n");
        html.append("              </div>\n");
        html.append("              <div class=\"layout-card\" data-layout=\"stack_horizontal\" onclick=\"selectLayoutCard('stack_horizontal', this)\">\n");
        html.append("                <div class=\"layout-preview stack-horiz\"><div>1</div><div>2</div><div>3</div></div>\n");
        html.append("                <div class=\"layout-title\">Stack Horizontal</div>\n");
        html.append("              </div>\n");
        html.append("              <div class=\"layout-card\" data-layout=\"top2_bottom1\" onclick=\"selectLayoutCard('top2_bottom1', this)\">\n");
        html.append("                <div class=\"layout-preview top2-bot1\"><div class=\"top-row\"><div>1</div><div>2</div></div><div class=\"bot-row\">3</div></div>\n");
        html.append("                <div class=\"layout-title\">Top 2 / Bottom 1</div>\n");
        html.append("              </div>\n");
        html.append("              <div class=\"layout-card\" data-layout=\"top1_bottom2\" onclick=\"selectLayoutCard('top1_bottom2', this)\">\n");
        html.append("                <div class=\"layout-preview top1-bot2\"><div class=\"top-row\">1</div><div class=\"bot-row\"><div>2</div><div>3</div></div></div>\n");
        html.append("                <div class=\"layout-title\">Top 1 / Bottom 2</div>\n");
        html.append("              </div>\n");
        html.append("            </div>\n");
        html.append("            <input type=\"hidden\" id=\"layoutMode\" value=\"auto\">\n");
        html.append("          </div>\n");
        html.append("          <!-- Section Ratio Options -->\n");
        html.append("          <div class=\"full-width\">\n");
        html.append("            <label class=\"form-label\">Screen Section Ratio Proportions (5 Options)</label>\n");
        html.append("            <p style=\"font-size: 12px; color: var(--text-muted); margin-bottom: 8px;\">Set size proportion split ratio between sections:</p>\n");
        html.append("            <div class=\"ratio-grid\">\n");
        html.append("              <div class=\"ratio-card active\" data-ratio=\"50_50\" onclick=\"selectRatioCard('50_50', this)\">\n");
        html.append("                <div class=\"ratio-bar\"><div style=\"flex: 5;\">50%</div><div style=\"flex: 5;\">50%</div></div>\n");
        html.append("                <div class=\"ratio-title\">50 : 50 (Equal Split)</div>\n");
        html.append("              </div>\n");
        html.append("              <div class=\"ratio-card\" data-ratio=\"60_40\" onclick=\"selectRatioCard('60_40', this)\">\n");
        html.append("                <div class=\"ratio-bar\"><div style=\"flex: 6;\">60%</div><div style=\"flex: 4;\">40%</div></div>\n");
        html.append("                <div class=\"ratio-title\">60 : 40 (Primary Main)</div>\n");
        html.append("              </div>\n");
        html.append("              <div class=\"ratio-card\" data-ratio=\"70_30\" onclick=\"selectRatioCard('70_30', this)\">\n");
        html.append("                <div class=\"ratio-bar\"><div style=\"flex: 7;\">70%</div><div style=\"flex: 3;\">30%</div></div>\n");
        html.append("                <div class=\"ratio-title\">70 : 30 (Wide Main)</div>\n");
        html.append("              </div>\n");
        html.append("              <div class=\"ratio-card\" data-ratio=\"40_60\" onclick=\"selectRatioCard('40_60', this)\">\n");
        html.append("                <div class=\"ratio-bar\"><div style=\"flex: 4;\">40%</div><div style=\"flex: 6;\">60%</div></div>\n");
        html.append("                <div class=\"ratio-title\">40 : 60 (Primary Secondary)</div>\n");
        html.append("              </div>\n");
        html.append("              <div class=\"ratio-card\" data-ratio=\"30_70\" onclick=\"selectRatioCard('30_70', this)\">\n");
        html.append("                <div class=\"ratio-bar\"><div style=\"flex: 3;\">30%</div><div style=\"flex: 7;\">70%</div></div>\n");
        html.append("                <div class=\"ratio-title\">30 : 70 (Wide Secondary)</div>\n");
        html.append("              </div>\n");
        html.append("            </div>\n");
        html.append("            <input type=\"hidden\" id=\"sectionRatio\" value=\"50_50\">\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group full-width\">\n");
        html.append("            <label class=\"form-label\">Ticker Text (News Marquee)</label>\n");
        html.append("            <textarea id=\"tickerText\" rows=\"2\" placeholder=\"Enter ticker news text...\"></textarea>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">Ticker Text Color</label>\n");
        html.append("            <div class=\"color-picker-group\">\n");
        html.append("              <input type=\"color\" id=\"tickerTextColorPicker\" class=\"color-picker-swatch\" value=\"#FFFFFF\" oninput=\"syncColorInput('tickerTextColorPicker', 'tickerTextColor')\">\n");
        html.append("              <input type=\"text\" id=\"tickerTextColor\" class=\"form-input\" value=\"#FFFFFF\" placeholder=\"#FFFFFF\" oninput=\"syncColorPicker('tickerTextColor', 'tickerTextColorPicker')\">\n");
        html.append("            </div>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">Ticker Background Color</label>\n");
        html.append("            <div class=\"color-picker-group\">\n");
        html.append("              <input type=\"color\" id=\"tickerBgColorPicker\" class=\"color-picker-swatch\" value=\"#000000\" oninput=\"syncColorInput('tickerBgColorPicker', 'tickerBgColor')\">\n");
        html.append("              <input type=\"text\" id=\"tickerBgColor\" class=\"form-input\" value=\"#000000\" placeholder=\"#000000 or transparent\" oninput=\"syncColorPicker('tickerBgColor', 'tickerBgColorPicker')\">\n");
        html.append("            </div>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">Ticker Position</label>\n");
        html.append("            <select id=\"tickerPosition\">\n");
        html.append("              <option value=\"bottom\">Bottom</option>\n");
        html.append("              <option value=\"middle\">Middle (Center Screen)</option>\n");
        html.append("              <option value=\"top\">Top</option>\n");
        html.append("            </select>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">Ticker Font Size (px)</label>\n");
        html.append("            <select id=\"tickerFontSize\">\n");
        html.append("              <option value=\"16\">Small (16px)</option>\n");
        html.append("              <option value=\"24\">Medium (24px)</option>\n");
        html.append("              <option value=\"32\">Large (32px)</option>\n");
        html.append("              <option value=\"40\">X-Large (40px)</option>\n");
        html.append("            </select>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">Ticker Font Family (10 Options)</label>\n");
        html.append("            <select id=\"tickerFontFamily\">\n");
        html.append("              <option value=\"sans-serif\">Sans-Serif (Default Standard)</option>\n");
        html.append("              <option value=\"serif\">Serif (Classic Times/Georgia)</option>\n");
        html.append("              <option value=\"monospace\">Monospace (Fixed Width Code)</option>\n");
        html.append("              <option value=\"sans-serif-condensed\">Sans Condensed</option>\n");
        html.append("              <option value=\"sans-serif-smallcaps\">Sans Small Caps</option>\n");
        html.append("              <option value=\"sans-serif-light\">Sans Light</option>\n");
        html.append("              <option value=\"sans-serif-medium\">Sans Medium</option>\n");
        html.append("              <option value=\"sans-serif-thin\">Sans Thin</option>\n");
        html.append("              <option value=\"sans-serif-black\">Sans Black</option>\n");
        html.append("              <option value=\"casual\">Casual</option>\n");
        html.append("            </select>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">Ticker Direction</label><select id=\"tickerDirection\"><option value=\"left\">Right to Left</option><option value=\"right\">Left to Right</option></select>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">Media Transition</label><select id=\"mediaTransition\"><option value=\"none\">None</option><option value=\"fade\">Fade</option><option value=\"slide_left\">Slide Left</option><option value=\"slide_right\">Slide Right</option><option value=\"slide_up\">Slide Up</option><option value=\"slide_down\">Slide Down</option><option value=\"zoom_in\">Zoom In</option><option value=\"zoom_out\">Zoom Out</option><option value=\"flip\">Flip</option><option value=\"rotate\">Rotate</option></select>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">Ticker Speed: <span id=\"tickerSpeedValue\">5</span></label>\n");
        html.append("            <input type=\"range\" id=\"tickerSpeed\" min=\"1\" max=\"10\" step=\"1\" value=\"5\" style=\"width:100%\" oninput=\"document.getElementById('tickerSpeedValue').textContent=this.value\">\n");
        html.append("            <div style=\"display:flex;justify-content:space-between;font-size:11px;color:#94a3b8\"><span>Slower</span><span>Faster</span></div>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">Video Resize Mode</label>\n");
        html.append("            <select id=\"resizeMode\">\n");
        html.append("              <option value=\"stretch\">Stretch (Full Screen Fill)</option>\n");
        html.append("              <option value=\"contain\">Contain (Aspect Fit)</option>\n");
        html.append("              <option value=\"cover\">Cover (Aspect Crop)</option>\n");
        html.append("            </select>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">USB Pendrive Preference</label>\n");
        html.append("            <select id=\"usePendrive\">\n");
        html.append("              <option value=\"false\">USB Auto Priority (recommended)</option>\n");
        html.append("              <option value=\"true\">USB Auto Priority (legacy compatible)</option>\n");
        html.append("            </select>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">Show QR Code & TV IP on Screen</label>\n");
        html.append("            <select id=\"showQrCode\">\n");
        html.append("              <option value=\"true\">SHOW (Display QR & TV IP Badge)</option>\n");
        html.append("              <option value=\"false\">HIDE (Hide QR Badge)</option>\n");
        html.append("            </select>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"form-group\">\n");
        html.append("            <label class=\"form-label\">🔒 Kiosk Lock Mode (Prevent Sleep & Exit)</label>\n");
        html.append("            <select id=\"kioskMode\">\n");
        html.append("              <option value=\"true\">🔒 LOCKED (ON - Keep Screen Awake & Disable Exit/Back Buttons)</option>\n");
        html.append("              <option value=\"false\">🔓 UNLOCKED (OFF - Allow Settings & Normal Exit)</option>\n");
        html.append("            </select>\n");
        html.append("          </div>\n");
        html.append("          <div class=\"full-width\">\n");
        html.append("            <button type=\"submit\" class=\"btn-primary\">💾 Save Settings & Update TV</button>\n");
        html.append("            <div id=\"saveSuccessMsg\" style=\"display:none; margin-top:12px; padding:12px 16px; background:rgba(34,197,94,0.15); border:1px solid #22c55e; color:#4ade80; border-radius:8px; font-weight:700; text-align:center; font-size:14px;\">✅ Settings & Layout Ratio updated instantly on TV!</div>\n");
        html.append("          </div>\n");
        html.append("        </div>\n");
        html.append("      </form>\n");
        html.append("    </div>\n");
        html.append("    <!-- Media Manager Panel -->\n");
        html.append("    <div id=\"mediaTab\" class=\"panel\">\n");
        html.append("      <div class=\"tv-target-bar\" style=\"margin-bottom:16px;\">\n");
        html.append("        <div class=\"tv-target-title\"><span>Section TV (single-TV mode)</span><span id=\"sectionTvHint\"></span></div>\n");
        html.append("        <select id=\"sectionTvSelector\" class=\"form-input\" style=\"margin-top:10px;\" onchange=\"setSectionTv(this.value)\"></select>\n");
        html.append("      </div>\n");
        html.append("      <div class=\"sec-header\">\n");
        html.append("        <h3 style=\"font-size: 16px; font-weight: 700;\">Sections Manager</h3>\n");
        html.append("        <div style=\"display: flex; gap: 8px; flex-wrap: wrap;\">\n");
        html.append("          <button class=\"btn-action\" onclick=\"createNewSection()\">➕ Add New Section</button>\n");
        html.append("          <button class=\"btn-edit\" onclick=\"renameCurrentSection()\">✏️ Rename Section</button>\n");
        html.append("          <button class=\"btn-danger\" onclick=\"deleteCurrentSection()\">🗑️ Delete Current Section</button>\n");
        html.append("        </div>\n");
        html.append("      </div>\n");
        html.append("      <div class=\"sec-tabs\" id=\"secTabContainer\"></div>\n");
        html.append("      <div class=\"upload-box\" id=\"uploadBox\" onclick=\"document.getElementById('fileInput').click()\">\n");
        html.append("        <p style=\"font-size: 36px; margin-bottom: 8px;\">📤</p>\n");
        html.append("        <p style=\"font-size: 16px; font-weight: 700;\">Click or Drag & Drop Multiple Files to Upload</p>\n");
        html.append("        <p style=\"font-size: 13px; color: var(--text-muted); margin-top: 6px;\">Select or Drop 1 or more Videos & Images together!</p>\n");
        html.append("        <input type=\"file\" id=\"fileInput\" multiple accept=\"image/*,video/*,.jpg,.jpeg,.png,.webp,.gif,.bmp,.tiff,.svg,.heic,.jfif,.mp4,.mkv,.mov,.avi,.webm,.ts\" style=\"display: none\" onchange=\"uploadFiles(this.files)\">\n");
        html.append("      </div>\n");
        html.append("      <!-- Progress Bar Container -->\n");
        html.append("      <div class=\"progress-box\" id=\"progressBox\">\n");
        html.append("        <div class=\"progress-info\">\n");
        html.append("          <span id=\"progressTitle\">Uploading files...</span>\n");
        html.append("          <span id=\"progressPercent\">0%</span>\n");
        html.append("        </div>\n");
        html.append("        <div class=\"progress-track\">\n");
        html.append("          <div class=\"progress-fill\" id=\"progressBar\"></div>\n");
        html.append("        </div>\n");
        html.append("        <div class=\"progress-sub\" id=\"progressSub\">0 of 0 files uploaded</div>\n");
        html.append("      </div>\n");
        html.append("      <h3 style=\"font-size: 16px; font-weight: 700; margin-bottom: 12px;\" id=\"secTitle\">Files in Section</h3>\n");
        html.append("      <div class=\"file-list\" id=\"fileList\">Loading files...</div>\n");
        html.append("    </div>\n");
        html.append("    <!-- Visual Canvas Layout Builder Panel -->\n");
        html.append("    <div id=\"canvasTab\" class=\"panel\">\n");
        html.append("      <div class=\"sec-header\">\n");
        html.append("        <h3 style=\"font-size: 16px; font-weight: 700;\">🎨 Visual Drag & Drop Canvas Layout Builder</h3>\n");
        html.append("        <div style=\"display: flex; gap: 8px; flex-wrap: wrap;\">\n");
        html.append("          <button class=\"btn-action\" onclick=\"addCanvasZone('media')\">📹 Add Media Zone</button>\n");
        html.append("          <button class=\"btn-action\" style=\"background:#8b5cf6;\" onclick=\"addCanvasZone('stream')\">📺 Add Live Stream</button>\n");
        html.append("          <button class=\"btn-action\" style=\"background:#0284c7;\" onclick=\"addCanvasZone('weather')\">☀️ Add Weather</button>\n");
        html.append("          <button class=\"btn-action\" style=\"background:#d97706;\" onclick=\"addCanvasZone('clock')\">🕒 Add Clock</button>\n");
        html.append("        </div>\n");
        html.append("      </div>\n");
        html.append("      <div style=\"margin-bottom:12px; display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:10px;\">\n");
        html.append("        <label style=\"font-weight:bold; font-size:14px; display:flex; align-items:center; gap:8px;\">\n");
        html.append("          <input type=\"checkbox\" id=\"customLayoutEnabled\" style=\"width:18px; height:18px;\"> Enable Custom Visual Canvas Layout on TV\n");
        html.append("        </label>\n");
        html.append("        <div style=\"display:flex; gap:6px;\">\n");
        html.append("          <button class=\"btn-action\" style=\"background:#10b981;\" onclick=\"saveCanvasLayout()\">💾 Save Visual Layout</button>\n");
        html.append("        </div>\n");
        html.append("      </div>\n");
        html.append("      <div class=\"canvas-container\">\n");
        html.append("        <div class=\"canvas-screen\" id=\"canvasScreen\"></div>\n");
        html.append("        <div class=\"canvas-controls\" id=\"zonePropEditor\">\n");
        html.append("          <h4 style=\"margin-bottom:12px; color:var(--accent);\">Selected Zone Properties</h4>\n");
        html.append("          <p id=\"noZoneSelected\" style=\"color:var(--text-muted); font-size:13px;\">Click on any zone box inside the 16:9 canvas mockup to edit its position, size & layer.</p>\n");
        html.append("          <div id=\"zoneFields\" style=\"display:none;\">\n");
        html.append("            <div class=\"form-group\"><label class=\"form-label\">Zone Title / Type</label><input type=\"text\" id=\"zoneName\" class=\"form-input\" onchange=\"updateSelectedZone()\"></div>\n");
        html.append("            <div class=\"form-group\"><label class=\"form-label\">Left Position (%)</label><input type=\"number\" id=\"zoneLeft\" class=\"form-input\" min=\"0\" max=\"100\" onchange=\"updateSelectedZone()\"></div>\n");
        html.append("            <div class=\"form-group\"><label class=\"form-label\">Top Position (%)</label><input type=\"number\" id=\"zoneTop\" class=\"form-input\" min=\"0\" max=\"100\" onchange=\"updateSelectedZone()\"></div>\n");
        html.append("            <div class=\"form-group\"><label class=\"form-label\">Width (%)</label><input type=\"number\" id=\"zoneWidth\" class=\"form-input\" min=\"5\" max=\"100\" onchange=\"updateSelectedZone()\"></div>\n");
        html.append("            <div class=\"form-group\"><label class=\"form-label\">Height (%)</label><input type=\"number\" id=\"zoneHeight\" class=\"form-input\" min=\"5\" max=\"100\" onchange=\"updateSelectedZone()\"></div>\n");
        html.append("            <div class=\"form-group\"><label class=\"form-label\">Layer Z-Index</label><input type=\"number\" id=\"zoneZIndex\" class=\"form-input\" min=\"1\" max=\"99\" onchange=\"updateSelectedZone()\"></div>\n");
        html.append("            <button class=\"btn-danger\" style=\"width:100%; margin-top:12px;\" onclick=\"deleteSelectedZone()\">🗑️ Delete Zone</button>\n");
        html.append("          </div>\n");
        html.append("        </div>\n");
        html.append("      </div>\n");
        html.append("    </div>\n");
        html.append("    <!-- Emergency Alert Broadcast System Panel -->\n");
        html.append("    <div id=\"eabsTab\" class=\"panel\">\n");
        html.append("      <div class=\"sec-header\">\n");
        html.append("        <h3 style=\"font-size: 16px; font-weight: 700; color: #ef4444;\">🚨 Emergency Alert Broadcast System (EABS)</h3>\n");
        html.append("        <button class=\"btn-action\" style=\"background:#22c55e;\" onclick=\"clearEmergencyAlert()\">✅ Clear Emergency & Resume Normal Playback</button>\n");
        html.append("      </div>\n");
        html.append("      <p style=\"font-size:13px; color:var(--text-muted); margin-bottom:16px;\">Trigger high-priority full-screen emergency alerts instantly across all TV displays connected to the network.</p>\n");
        html.append("      <div class=\"emergency-grid\">\n");
        html.append("        <div class=\"emergency-card active\" onclick=\"selectEmergencyType('FIRE', this)\">\n");
        html.append("          <h3 style=\"color:#ef4444;\">🔥 Fire Emergency Alert</h3>\n");
        html.append("          <p>Displays full-screen red flashing fire warning with audio siren alarm.</p>\n");
        html.append("        </div>\n");
        html.append("        <div class=\"emergency-card\" onclick=\"selectEmergencyType('EVACUATION', this)\">\n");
        html.append("          <h3 style=\"color:#f97316;\">🚨 Security Evacuation Order</h3>\n");
        html.append("          <p>Displays urgent evacuation instructions with siren alert.</p>\n");
        html.append("        </div>\n");
        html.append("        <div class=\"emergency-card\" onclick=\"selectEmergencyType('CUSTOM', this)\">\n");
        html.append("          <h3 style=\"color:#38bdf8;\">📢 Custom Priority Announcement</h3>\n");
        html.append("          <p>Displays custom broadcast banner text over video screen.</p>\n");
        html.append("        </div>\n");
        html.append("      </div>\n");
        html.append("      <div style=\"margin-top:20px; background:var(--bg-input); padding:20px; border-radius:12px; border:1px solid var(--border);\">\n");
        html.append("        <h4 style=\"margin-bottom:12px;\">Alert Broadcast Configuration</h4>\n");
        html.append("        <div class=\"form-group full-width\">\n");
        html.append("          <label class=\"form-label\">Emergency Announcement Text</label>\n");
        html.append("          <textarea id=\"emergencyMessage\" rows=\"3\" class=\"form-input\" style=\"font-weight:bold; font-size:15px; color:#ef4444;\">🔥 FIRE EMERGENCY! PLEASE EVACUATE THE BUILDING IMMEDIATELY VIA STAIRWELL EXITS!</textarea>\n");
        html.append("        </div>\n");
        html.append("        <div class=\"form-group\" style=\"margin-top:12px;\">\n");
        html.append("          <label class=\"form-label\">Sound Siren Alarm</label>\n");
        html.append("          <select id=\"emergencySound\" class=\"form-input\">\n");
        html.append("            <option value=\"true\">🔊 Play Loud Siren Sound on TV Speakers</option>\n");
        html.append("            <option value=\"false\">🔇 Silent Alert Only</option>\n");
        html.append("          </select>\n");
        html.append("        </div>\n");
        html.append("        <button class=\"btn-danger\" style=\"width:100%; margin-top:16px; padding:14px; font-size:16px; font-weight:bold;\" onclick=\"triggerEmergencyAlert()\">🚨 BROADCAST EMERGENCY ALERT TO ALL TV SCREENS NOW</button>\n");
        html.append("      </div>\n");
        html.append("    </div>\n");
        html.append("    <!-- Live Widgets & Stream Config Panel -->\n");
        html.append("    <div id=\"widgetsTab\" class=\"panel\">\n");
        html.append("      <h3 style=\"font-size: 16px; font-weight: 700; margin-bottom:16px;\">🌐 Live Widgets & Stream Feed Configuration</h3>\n");
        html.append("      <div class=\"config-grid\">\n");
        html.append("        <div class=\"form-group\">\n");
        html.append("          <label class=\"form-label\">☀️ Live Weather City</label>\n");
        html.append("          <input type=\"text\" id=\"weatherCity\" class=\"form-input\" value=\"Delhi\" placeholder=\"e.g. Delhi, Mumbai, Dubai, New York\">\n");
        html.append("        </div>\n");
        html.append("        <div class=\"form-group\">\n");
        html.append("          <label class=\"form-label\">Temperature Unit</label>\n");
        html.append("          <select id=\"weatherUnit\" class=\"form-input\">\n");
        html.append("            <option value=\"celsius\">°C (Celsius)</option>\n");
        html.append("            <option value=\"fahrenheit\">°F (Fahrenheit)</option>\n");
        html.append("          </select>\n");
        html.append("        </div>\n");
        html.append("        <div class=\"form-group full-width\">\n");
        html.append("          <label class=\"form-label\">📺 Website, YouTube or Live Stream URL</label>\n");
        html.append("          <input type=\"url\" id=\"liveStreamUrl\" class=\"form-input\" value=\"\" placeholder=\"e.g. https://example.com, YouTube link, or .m3u8 stream\">\n");
        html.append("        </div>\n");
        html.append("        <div class=\"form-group\">\n");
        html.append("          <label class=\"form-label\">🕒 Clock Time Format</label>\n");
        html.append("          <select id=\"clockFormat\" class=\"form-input\">\n");
        html.append("            <option value=\"12h\">12-Hour (10:30 AM)</option>\n");
        html.append("            <option value=\"24h\">24-Hour (22:30)</option>\n");
        html.append("          </select>\n");
        html.append("        </div>\n");
        html.append("        <div class=\"form-group\">\n");
        html.append("          <label class=\"form-label\">🕋 Prayer Times Calculation Location</label>\n");
        html.append("          <input type=\"text\" id=\"prayerCity\" class=\"form-input\" value=\"Delhi\" placeholder=\"e.g. Delhi, Dubai, Istanbul\">\n");
        html.append("        </div>\n");
        html.append("        <div class=\"full-width\">\n");
        html.append("          <button class=\"btn-primary\" onclick=\"saveWidgetsConfig()\">💾 Save Live Widgets & Stream Configuration</button>\n");
        html.append("        </div>\n");
        html.append("      </div>\n");
        html.append("    </div>\n");
        html.append("  </div>\n");
        html.append("  <script>\n");
        html.append("    let currentSection = 'section1';\n");
        html.append("    let canvasZones = [];\n");
        html.append("    let selectedZoneIndex = -1;\n");
        html.append("    let selectedEmergency = 'FIRE';\n");
        html.append("    let knownTvs = [];\n");
        html.append("    const selectedTvs = new Set();\n");
        html.append("    let sectionTvIp = window.location.hostname;\n");
        html.append("    let targetSelectionInitialized = false;\n");
        html.append("    function utf8Base64(value) { const bytes = new TextEncoder().encode(value || ''); let binary = ''; bytes.forEach(byte => binary += String.fromCharCode(byte)); return btoa(binary); }\n");
        html.append("    function targetUrl(ip, path) { return 'http://' + ip + ':9090' + path; }\n");
        html.append("    function selectedTvIps() { return Array.from(selectedTvs); }\n");
        html.append("    function tvLabel(tv) { return (tv.name || tv.model || 'Android TV') + ' (' + tv.ip + ')'; }\n");
        html.append("    function escapeHtml(value) { const div = document.createElement('div'); div.textContent = value; return div.innerHTML; }\n");
        html.append("    function renderTvTargets() {\n");
        html.append("      const list = document.getElementById('tvTargetList');\n");
        html.append("      const summary = document.getElementById('tvTargetSummary');\n");
        html.append("      summary.textContent = selectedTvs.size + ' of ' + knownTvs.length + ' TV(s) selected';\n");
        html.append("      list.innerHTML = knownTvs.map(tv => '<div class=\"tv-target-chip\"><label><input type=\"checkbox\" ' + (selectedTvs.has(tv.ip) ? 'checked' : '') + ' onchange=\"toggleTv(\\\'' + tv.ip + '\\\', this.checked)\">' + escapeHtml(tvLabel(tv)) + '</label><button type=\"button\" title=\"Rename TV\" style=\"margin-left:4px;border:0;background:transparent;cursor:pointer;\" onclick=\"renameTv(\\\'' + tv.ip + '\\\')\">✏️</button></div>').join('') || '<span>No TV found yet. Keep all TVs on the same Wi-Fi.</span>';\n");
        html.append("    }\n");
        html.append("    function renderSectionTvSelector() {\n");
        html.append("      const selector = document.getElementById('sectionTvSelector'); if (!selector) return;\n");
        html.append("      if (!knownTvs.some(tv => tv.ip === sectionTvIp)) sectionTvIp = window.location.hostname;\n");
        html.append("      selector.innerHTML = knownTvs.map(tv => '<option value=\"' + tv.ip + '\" ' + (tv.ip === sectionTvIp ? 'selected' : '') + '>' + escapeHtml(tvLabel(tv)) + '</option>').join('');\n");
        html.append("      document.getElementById('sectionTvHint').textContent = 'Folders/media only for this TV';\n");
        html.append("    }\n");
        html.append("    function setSectionTv(ip) { sectionTvIp = ip || window.location.hostname; currentSection = 'section1'; fetchMedia(); }\n");
        html.append("    function sectionFetch(path, options) {\n");
        html.append("      const request = Object.assign({ method: 'GET' }, options || {}); request.headers = Object.assign({ 'Content-Type': 'application/json' }, request.headers || {});\n");
        html.append("      return fetch(targetUrl(sectionTvIp || window.location.hostname, path), request);\n");
        html.append("    }\n");
        html.append("    async function renameTv(ip) {\n");
        html.append("      const tv = knownTvs.find(item => item.ip === ip); const name = prompt('TV name:', tv ? (tv.name || tv.model || '') : '');\n");
        html.append("      if (name === null) return; const trimmed = name.trim(); if (!trimmed || trimmed.length > 64) { alert('TV name must be 1-64 characters.'); return; }\n");
        html.append("      const response = await fetch(targetUrl(ip, '/api/device-name'), { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: trimmed }) });\n");
        html.append("      if (!response.ok) { showAlert('TV name could not be updated.'); return; }\n");
        html.append("      knownTvs = knownTvs.map(item => item.ip === ip ? Object.assign({}, item, { name: trimmed }) : item); renderTvTargets(); renderSectionTvSelector(); showAlert('TV renamed successfully.');\n");
        html.append("    }\n");
        html.append("    function toggleTv(ip, checked) { if (checked) selectedTvs.add(ip); else selectedTvs.delete(ip); renderTvTargets(); }\n");
        html.append("    function selectAllTvs() { knownTvs.forEach(tv => selectedTvs.add(tv.ip)); renderTvTargets(); }\n");
        html.append("    function clearAllTvs() { selectedTvs.clear(); renderTvTargets(); }\n");
        html.append("    async function refreshTvTargets() {\n");
        html.append("      try {\n");
        html.append("        const response = await fetch('/api/devices');\n");
        html.append("        const devices = await response.json();\n");
        html.append("        knownTvs = Array.isArray(devices) ? devices.filter(tv => tv && tv.ip) : [];\n");
        html.append("        const localIp = window.location.hostname;\n");
        html.append("        if (!targetSelectionInitialized && knownTvs.some(tv => tv.ip === localIp)) { selectedTvs.add(localIp); targetSelectionInitialized = true; }\n");
        html.append("        Array.from(selectedTvs).forEach(ip => { if (!knownTvs.some(tv => tv.ip === ip)) selectedTvs.delete(ip); });\n");
        html.append("        renderTvTargets();\n");
        html.append("        renderSectionTvSelector();\n");
        html.append("      } catch (error) { document.getElementById('tvTargetSummary').textContent = 'TV discovery unavailable; retrying...'; }\n");
        html.append("    }\n");
        html.append("    async function multiFetch(path, options) {\n");
        html.append("      const targets = selectedTvIps();\n");
        html.append("      if (!targets.length) { showAlert('Select at least one TV before applying changes.'); throw new Error('No TV selected'); }\n");
        html.append("      const request = Object.assign({ method: 'GET' }, options || {});\n");
        html.append("      request.headers = Object.assign({ 'Content-Type': 'application/json' }, request.headers || {});\n");
        html.append("      const results = await Promise.allSettled(targets.map(ip => fetch(targetUrl(ip, path), request)));\n");
        html.append("      const failed = results.filter(r => r.status !== 'fulfilled' || !r.value.ok);\n");
        html.append("      if (failed.length) { showAlert((targets.length - failed.length) + '/' + targets.length + ' TV(s) updated. Check network connection.'); throw new Error('Some TVs could not be updated'); }\n");
        html.append("      return results[0].value;\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    async function fetchLayout() {\n");
        html.append("      try {\n");
        html.append("        const res = await fetch('/api/get-layout');\n");
        html.append("        const data = await res.json();\n");
        html.append("        if (data && data.zones) {\n");
        html.append("          canvasZones = data.zones;\n");
        html.append("          document.getElementById('customLayoutEnabled').checked = !!data.enabled;\n");
        html.append("        }\n");
        html.append("      } catch (e) {}\n");
        html.append("      if (!canvasZones || canvasZones.length === 0) {\n");
        html.append("        canvasZones = [\n");
        html.append("          { id: 'zone_1', name: 'Main Media Zone', type: 'media', left: 0, top: 0, width: 70, height: 100, zIndex: 1 },\n");
        html.append("          { id: 'zone_2', name: 'Live Stream Zone', type: 'stream', left: 70, top: 0, width: 30, height: 50, zIndex: 1 },\n");
        html.append("          { id: 'zone_3', name: 'Weather & Clock', type: 'weather', left: 70, top: 50, width: 30, height: 50, zIndex: 1 }\n");
        html.append("        ];\n");
        html.append("      }\n");
        html.append("      renderCanvasMockup();\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    function addCanvasZone(type) {\n");
        html.append("      const id = 'zone_' + (canvasZones.length + 1);\n");
        html.append("      const name = type.toUpperCase() + ' Zone ' + (canvasZones.length + 1);\n");
        html.append("      canvasZones.push({ id, name, type, left: 10, top: 10, width: 30, height: 30, zIndex: 1 });\n");
        html.append("      selectedZoneIndex = canvasZones.length - 1;\n");
        html.append("      renderCanvasMockup();\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    function canvasPoint(e) { return (e.touches && e.touches[0]) || (e.changedTouches && e.changedTouches[0]) || e; }\n");
        html.append("    function startDragZone(e, idx) {\n");
        html.append("      if (e.target && e.target.classList && e.target.classList.contains('resize-handle')) return;\n");
        html.append("      e.stopPropagation();\n");
        html.append("      e.preventDefault();\n");
        html.append("      const point = canvasPoint(e);\n");
        html.append("      selectedZoneIndex = idx;\n");
        html.append("      activeDragZone = canvasZones[idx];\n");
        html.append("      dragStartX = point.clientX;\n");
        html.append("      dragStartY = point.clientY;\n");
        html.append("      initialLeft = activeDragZone.left;\n");
        html.append("      initialTop = activeDragZone.top;\n");
        html.append("      markSelectedCanvasZone();\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    function startResizeZone(e, idx) {\n");
        html.append("      e.stopPropagation();\n");
        html.append("      e.preventDefault();\n");
        html.append("      const point = canvasPoint(e);\n");
        html.append("      selectedZoneIndex = idx;\n");
        html.append("      activeResizeZone = canvasZones[idx];\n");
        html.append("      dragStartX = point.clientX;\n");
        html.append("      dragStartY = point.clientY;\n");
        html.append("      initialWidth = activeResizeZone.width;\n");
        html.append("      initialHeight = activeResizeZone.height;\n");
        html.append("      markSelectedCanvasZone();\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    function moveCanvasInteraction(e) {\n");
        html.append("      const point = canvasPoint(e);\n");
        html.append("      if (!activeDragZone && !activeResizeZone) return;\n");
        html.append("      const screen = document.getElementById('canvasScreen');\n");
        html.append("      if (!screen) return;\n");
        html.append("      const rect = screen.getBoundingClientRect();\n");
        html.append("      if (!rect || rect.width === 0 || rect.height === 0) return;\n");
        html.append("\n");
        html.append("      if (activeDragZone) {\n");
        html.append("        const deltaX = ((point.clientX - dragStartX) / rect.width) * 100;\n");
        html.append("        const deltaY = ((point.clientY - dragStartY) / rect.height) * 100;\n");
        html.append("        let newLeft = Math.round((initialLeft + deltaX) * 10) / 10;\n");
        html.append("        let newTop = Math.round((initialTop + deltaY) * 10) / 10;\n");
        html.append("        newLeft = Math.max(0, Math.min(100 - activeDragZone.width, newLeft));\n");
        html.append("        newTop = Math.max(0, Math.min(100 - activeDragZone.height, newTop));\n");
        html.append("        activeDragZone.left = newLeft;\n");
        html.append("        activeDragZone.top = newTop;\n");
        html.append("        updateActiveCanvasZone(activeDragZone);\n");
        html.append("      } else if (activeResizeZone) {\n");
        html.append("        const deltaX = ((point.clientX - dragStartX) / rect.width) * 100;\n");
        html.append("        const deltaY = ((point.clientY - dragStartY) / rect.height) * 100;\n");
        html.append("        let newW = Math.round((initialWidth + deltaX) * 10) / 10;\n");
        html.append("        let newH = Math.round((initialHeight + deltaY) * 10) / 10;\n");
        html.append("        newW = Math.max(5, Math.min(100 - activeResizeZone.left, newW));\n");
        html.append("        newH = Math.max(5, Math.min(100 - activeResizeZone.top, newH));\n");
        html.append("        activeResizeZone.width = newW;\n");
        html.append("        activeResizeZone.height = newH;\n");
        html.append("        updateActiveCanvasZone(activeResizeZone);\n");
        html.append("      }\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    function stopCanvasInteraction() {\n");
        html.append("      const wasInteracting = !!activeDragZone || !!activeResizeZone;\n");
        html.append("      activeDragZone = null;\n");
        html.append("      activeResizeZone = null;\n");
        html.append("      if (wasInteracting) renderCanvasMockup();\n");
        html.append("    }\n");
        html.append("    window.addEventListener('mousemove', moveCanvasInteraction);\n");
        html.append("    window.addEventListener('mouseup', stopCanvasInteraction);\n");
        html.append("    window.addEventListener('touchmove', function(e) { if (activeDragZone || activeResizeZone) { e.preventDefault(); moveCanvasInteraction(e); } }, { passive: false });\n");
        html.append("    window.addEventListener('touchend', stopCanvasInteraction);\n");
        html.append("    window.addEventListener('touchcancel', stopCanvasInteraction);\n");
        html.append("\n");
        html.append("    function markSelectedCanvasZone() { document.querySelectorAll('.zone-box').forEach((el, index) => el.classList.toggle('active', index === selectedZoneIndex)); }\n");
        html.append("    function updateActiveCanvasZone(zone) { const index = canvasZones.indexOf(zone); const el = document.querySelector('.zone-box[data-idx=\"' + index + '\"]'); if (el) { el.style.left = zone.left + '%'; el.style.top = zone.top + '%'; el.style.width = zone.width + '%'; el.style.height = zone.height + '%'; } }\n");
        html.append("    function renderCanvasMockup() {\n");
        html.append("      const screen = document.getElementById('canvasScreen');\n");
        html.append("      if (!screen) return;\n");
        html.append("      screen.innerHTML = canvasZones.map((z, idx) => `\n");
        html.append("        <div class=\"zone-box ${idx === selectedZoneIndex ? 'active' : ''}\" data-idx=\"${idx}\" style=\"left:${z.left}%; top:${z.top}%; width:${z.width}%; height:${z.height}%; z-index:${z.zIndex};\" onmousedown=\"startDragZone(event, ${idx})\" ontouchstart=\"startDragZone(event, ${idx})\">\n");
        html.append("          ${z.name}\n");
        html.append("          <div class=\"resize-handle\" data-idx=\"${idx}\" title=\"Drag to resize\" onmousedown=\"startResizeZone(event, ${idx})\" ontouchstart=\"startResizeZone(event, ${idx})\"></div>\n");
        html.append("        </div>\n");
        html.append("      `).join('');\n");
        html.append("\n");
        html.append("      const fields = document.getElementById('zoneFields');\n");
        html.append("      const noZone = document.getElementById('noZoneSelected');\n");
        html.append("      if (selectedZoneIndex >= 0 && selectedZoneIndex < canvasZones.length) {\n");
        html.append("        const z = canvasZones[selectedZoneIndex];\n");
        html.append("        fields.style.display = 'block';\n");
        html.append("        noZone.style.display = 'none';\n");
        html.append("        document.getElementById('zoneName').value = z.name || '';\n");
        html.append("        document.getElementById('zoneLeft').value = z.left !== undefined ? z.left : 0;\n");
        html.append("        document.getElementById('zoneTop').value = z.top !== undefined ? z.top : 0;\n");
        html.append("        document.getElementById('zoneWidth').value = z.width !== undefined ? z.width : 30;\n");
        html.append("        document.getElementById('zoneHeight').value = z.height !== undefined ? z.height : 30;\n");
        html.append("        document.getElementById('zoneZIndex').value = z.zIndex !== undefined ? z.zIndex : 1;\n");
        html.append("      } else {\n");
        html.append("        fields.style.display = 'none';\n");
        html.append("        noZone.style.display = 'block';\n");
        html.append("      }\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    function selectCanvasZone(idx) {\n");
        html.append("      selectedZoneIndex = idx;\n");
        html.append("      renderCanvasMockup();\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    function updateSelectedZone() {\n");
        html.append("      if (selectedZoneIndex < 0 || selectedZoneIndex >= canvasZones.length) return;\n");
        html.append("      const z = canvasZones[selectedZoneIndex];\n");
        html.append("      z.name = document.getElementById('zoneName').value;\n");
        html.append("      z.left = parseFloat(document.getElementById('zoneLeft').value) || 0;\n");
        html.append("      z.top = parseFloat(document.getElementById('zoneTop').value) || 0;\n");
        html.append("      z.width = parseFloat(document.getElementById('zoneWidth').value) || 10;\n");
        html.append("      z.height = parseFloat(document.getElementById('zoneHeight').value) || 10;\n");
        html.append("      z.zIndex = parseInt(document.getElementById('zoneZIndex').value) || 1;\n");
        html.append("      renderCanvasMockup();\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    function deleteSelectedZone() {\n");
        html.append("      if (selectedZoneIndex < 0 || selectedZoneIndex >= canvasZones.length) {\n");
        html.append("        alert('Please select a zone box first by clicking on it inside the 16:9 canvas!');\n");
        html.append("        return;\n");
        html.append("      }\n");
        html.append("      canvasZones.splice(selectedZoneIndex, 1);\n");
        html.append("      selectedZoneIndex = canvasZones.length > 0 ? 0 : -1;\n");
        html.append("      renderCanvasMockup();\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    async function saveCanvasLayout() {\n");
        html.append("      const enabled = document.getElementById('customLayoutEnabled').checked;\n");
        html.append("      const payload = { enabled: enabled, zones: canvasZones };\n");
        html.append("      await multiFetch('/api/save-layout', {\n");
        html.append("        method: 'POST',\n");
        html.append("        body: JSON.stringify(payload)\n");
        html.append("      });\n");
        html.append("      showAlert('Visual Canvas Layout saved to TV! TV will display custom zones.');\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    function selectEmergencyType(type, cardEl) {\n");
        html.append("      selectedEmergency = type;\n");
        html.append("      document.querySelectorAll('.emergency-card').forEach(c => c.classList.remove('active'));\n");
        html.append("      if (cardEl) cardEl.classList.add('active');\n");
        html.append("      const msgEl = document.getElementById('emergencyMessage');\n");
        html.append("      if (type === 'FIRE') {\n");
        html.append("        msgEl.value = '🔥 FIRE EMERGENCY! PLEASE EVACUATE THE BUILDING IMMEDIATELY VIA STAIRWELL EXITS!';\n");
        html.append("      } else if (type === 'EVACUATION') {\n");
        html.append("        msgEl.value = '🚨 SECURITY EVACUATION ORDER! PROCEED CALMLY TO NEAREST EMERGENCY ASSEMBLY POINT!';\n");
        html.append("      } else {\n");
        html.append("        msgEl.value = '📢 URGENT ANNOUNCEMENT: ATTENTION ALL VISITORS AND STAFF!';\n");
        html.append("      }\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    async function triggerEmergencyAlert() {\n");
        html.append("      const msg = document.getElementById('emergencyMessage').value;\n");
        html.append("      const sound = document.getElementById('emergencySound').value === 'true';\n");
        html.append("      const payload = { type: selectedEmergency, titleBase64: utf8Base64(selectedEmergency + ' ALERT'), messageBase64: utf8Base64(msg), sound: sound };\n");
        html.append("      await multiFetch('/api/emergency-alert', {\n");
        html.append("        method: 'POST',\n");
        html.append("        body: JSON.stringify(payload)\n");
        html.append("      });\n");
        html.append("      showAlert('🚨 EMERGENCY ALERT BROADCASTED TO ALL TV SCREENS!');\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    async function clearEmergencyAlert() {\n");
        html.append("      await multiFetch('/api/clear-emergency', { method: 'POST' });\n");
        html.append("      showAlert('✅ Emergency Alert Cleared! Normal TV playback resumed.');\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    async function saveWidgetsConfig() {\n");
        html.append("      const cfg = {\n");
        html.append("        weatherCity: document.getElementById('weatherCity').value,\n");
        html.append("        weatherUnit: document.getElementById('weatherUnit').value,\n");
        html.append("        liveStreamUrl: document.getElementById('liveStreamUrl').value.trim(),\n");
        html.append("        clockFormat: document.getElementById('clockFormat').value,\n");
        html.append("        prayerCity: document.getElementById('prayerCity').value\n");
        html.append("      };\n");
        html.append("      await multiFetch('/api/config', {\n");
        html.append("        method: 'POST',\n");
        html.append("        body: JSON.stringify(cfg)\n");
        html.append("      });\n");
        html.append("      showAlert('Live Widgets & Stream configuration updated on TV!');\n");
        html.append("    }\n");
        html.append("\n");
        html.append("    async function restartTvApp() {\n");
        html.append("      if (!confirm('Are you sure you want to restart the TV Player App?')) return;\n");
        html.append("      showAlert('Restarting TV Player... App will reload on screen in 2 seconds.');\n");
        html.append("      await multiFetch('/api/restart-app', { method: 'POST' });\n");
        html.append("    }\n");
        html.append("    async function resetTvSettings() {\n");
        html.append("      if (!confirm('Reset all player settings and canvas layout? Section media files will be kept.')) return;\n");
        html.append("      const res = await multiFetch('/api/reset-settings', { method: 'POST' });\n");
        html.append("      if (!res.ok) { showAlert('Could not reset settings. Please try again.'); return; }\n");
        html.append("      await fetchConfig();\n");
        html.append("      await fetchLayout();\n");
        html.append("      showAlert('Player settings and canvas layout reset. Section media files were kept.');\n");
        html.append("    }\n");
        html.append("    window.onload = function() {\n");
        html.append("      initTheme();\n");
        html.append("      refreshTvTargets();\n");
        html.append("      setInterval(refreshTvTargets, 5000);\n");
        html.append("      fetchConfig();\n");
        html.append("      fetchMedia();\n");
        html.append("      fetchLayout();\n");
        html.append("      initDragAndDrop();\n");
        html.append("      initCanvasMouseEvents();\n");
        html.append("    };\n");
        html.append("    function initTheme() {\n");
        html.append("      const savedTheme = localStorage.getItem('cms_theme') || 'dark';\n");
        html.append("      if (savedTheme === 'light') {\n");
        html.append("        document.body.classList.add('light-theme');\n");
        html.append("        document.getElementById('themeIcon').innerText = '🌙';\n");
        html.append("        document.getElementById('themeLabel').innerText = 'Dark Mode';\n");
        html.append("      } else {\n");
        html.append("        document.body.classList.remove('light-theme');\n");
        html.append("        document.getElementById('themeIcon').innerText = '☀️';\n");
        html.append("        document.getElementById('themeLabel').innerText = 'Light Mode';\n");
        html.append("      }\n");
        html.append("    }\n");
        html.append("    function toggleTheme() {\n");
        html.append("      const isLight = document.body.classList.toggle('light-theme');\n");
        html.append("      if (isLight) {\n");
        html.append("        localStorage.setItem('cms_theme', 'light');\n");
        html.append("        document.getElementById('themeIcon').innerText = '🌙';\n");
        html.append("        document.getElementById('themeLabel').innerText = 'Dark Mode';\n");
        html.append("      } else {\n");
        html.append("        localStorage.setItem('cms_theme', 'dark');\n");
        html.append("        document.getElementById('themeIcon').innerText = '☀️';\n");
        html.append("        document.getElementById('themeLabel').innerText = 'Light Mode';\n");
        html.append("      }\n");
        html.append("    }\n");
        html.append("    function selectLayoutCard(layout, cardEl) {\n");
        html.append("      document.querySelectorAll('.layout-card').forEach(c => c.classList.remove('active'));\n");
        html.append("      cardEl.classList.add('active');\n");
        html.append("      document.getElementById('layoutMode').value = layout;\n");
        html.append("    }\n");
        html.append("    function selectRatioCard(ratio, cardEl) {\n");
        html.append("      document.querySelectorAll('.ratio-card').forEach(c => c.classList.remove('active'));\n");
        html.append("      cardEl.classList.add('active');\n");
        html.append("      document.getElementById('sectionRatio').value = ratio;\n");
        html.append("    }\n");
        html.append("    function syncColorInput(pickerId, textId) {\n");
        html.append("      document.getElementById(textId).value = document.getElementById(pickerId).value.toUpperCase();\n");
        html.append("    }\n");
        html.append("    function syncColorPicker(textId, pickerId) {\n");
        html.append("      const val = document.getElementById(textId).value.trim();\n");
        html.append("      if (/^#[0-9A-Fa-f]{6}$/.test(val)) {\n");
        html.append("        document.getElementById(pickerId).value = val;\n");
        html.append("      }\n");
        html.append("    }\n");
        html.append("    function initDragAndDrop() {\n");
        html.append("      const dropBox = document.getElementById('uploadBox');\n");
        html.append("      if (!dropBox) return;\n");
        html.append("      ['dragenter', 'dragover', 'dragleave', 'drop'].forEach(evt => {\n");
        html.append("        dropBox.addEventListener(evt, preventDefaults, false);\n");
        html.append("        document.body.addEventListener(evt, preventDefaults, false);\n");
        html.append("      });\n");
        html.append("      ['dragenter', 'dragover'].forEach(evt => {\n");
        html.append("        dropBox.addEventListener(evt, () => dropBox.classList.add('dragover'), false);\n");
        html.append("      });\n");
        html.append("      ['dragleave', 'drop'].forEach(evt => {\n");
        html.append("        dropBox.addEventListener(evt, () => dropBox.classList.remove('dragover'), false);\n");
        html.append("      });\n");
        html.append("      dropBox.addEventListener('drop', handleDrop, false);\n");
        html.append("    }\n");
        html.append("    function preventDefaults(e) {\n");
        html.append("      e.preventDefault();\n");
        html.append("      e.stopPropagation();\n");
        html.append("    }\n");
        html.append("    function handleDrop(e) {\n");
        html.append("      const dt = e.dataTransfer;\n");
        html.append("      const files = dt ? dt.files : null;\n");
        html.append("      if (files && files.length > 0) {\n");
        html.append("        uploadFiles(files);\n");
        html.append("      }\n");
        html.append("    }\n");
        html.append("    function switchTab(tabId, btn) {\n");
        html.append("      document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));\n");
        html.append("      document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));\n");
        html.append("      btn.classList.add('active');\n");
        html.append("      document.getElementById(tabId).classList.add('active');\n");
        html.append("    }\n");
        html.append("    function selectSection(sec) {\n");
        html.append("      currentSection = sec;\n");
        html.append("      document.querySelectorAll('.sec-tab').forEach(b => {\n");
        html.append("        b.classList.toggle('active', b.dataset.sec === sec);\n");
        html.append("      });\n");
        html.append("      document.getElementById('secTitle').innerText = 'Files in ' + sec.toUpperCase();\n");
        html.append("      renderFiles();\n");
        html.append("    }\n");
        html.append("    async function fetchConfig() {\n");
        html.append("      const res = await fetch('/api/config?ts=' + Date.now(), { cache: 'no-store' });\n");
        html.append("      const cfg = await res.json();\n");
        html.append("      document.getElementById('orientation').value = cfg.orientation || 'horizontal';\n");
        html.append("      document.getElementById('slideDuration').value = cfg.slideDuration || 5000;\n");
        html.append("      document.getElementById('tickerText').value = cfg.tickerText || '';\n");
        html.append("      document.getElementById('tickerTextColor').value = cfg.tickerTextColor || '#FFFFFF';\n");
        html.append("      syncColorPicker('tickerTextColor', 'tickerTextColorPicker');\n");
        html.append("      document.getElementById('tickerBgColor').value = cfg.tickerBgColor || '#000000';\n");
        html.append("      syncColorPicker('tickerBgColor', 'tickerBgColorPicker');\n");
        html.append("      document.getElementById('tickerPosition').value = cfg.tickerPosition || 'bottom';\n");
        html.append("      document.getElementById('tickerFontSize').value = cfg.tickerFontSize || 16;\n");
        html.append("      document.getElementById('tickerFontFamily').value = cfg.tickerFontFamily || 'sans-serif';\n");
        html.append("      document.getElementById('tickerSpeed').value = cfg.tickerSpeed || 5;\n");
        html.append("      document.getElementById('tickerSpeedValue').textContent = cfg.tickerSpeed || 5;\n");
        html.append("      document.getElementById('tickerDirection').value = cfg.tickerDirection || 'left';\n");
        html.append("      document.getElementById('mediaTransition').value = cfg.mediaTransition || 'fade';\n");
        html.append("      document.getElementById('resizeMode').value = cfg.resizeMode || 'stretch';\n");
        html.append("      document.getElementById('usePendrive').value = cfg.usePendrive ? 'true' : 'false';\n");
        html.append("      document.getElementById('showQrCode').value = cfg.showQrCode !== false ? 'true' : 'false';\n");
        html.append("      document.getElementById('kioskMode').value = cfg.kioskMode !== false ? 'true' : 'false';\n");
        html.append("      document.getElementById('weatherCity').value = cfg.weatherCity || 'Delhi';\n");
        html.append("      document.getElementById('weatherUnit').value = cfg.weatherUnit || 'celsius';\n");
        html.append("      document.getElementById('liveStreamUrl').value = cfg.liveStreamUrl || '';\n");
        html.append("      document.getElementById('clockFormat').value = cfg.clockFormat || '12h';\n");
        html.append("      document.getElementById('prayerCity').value = cfg.prayerCity || 'Delhi';\n");
        html.append("      const layout = cfg.layoutMode || 'auto';\n");
        html.append("      document.getElementById('layoutMode').value = layout;\n");
        html.append("      document.querySelectorAll('.layout-card').forEach(c => {\n");
        html.append("        c.classList.toggle('active', c.dataset.layout === layout);\n");
        html.append("      });\n");
        html.append("      const ratio = cfg.sectionRatio || '50_50';\n");
        html.append("      document.getElementById('sectionRatio').value = ratio;\n");
        html.append("      document.querySelectorAll('.ratio-card').forEach(c => {\n");
        html.append("        c.classList.toggle('active', c.dataset.ratio === ratio);\n");
        html.append("      });\n");
        html.append("    }\n");
        html.append("    async function saveConfig(e) {\n");
        html.append("      e.preventDefault();\n");
        html.append("      const cfg = {\n");
        html.append("        orientation: document.getElementById('orientation').value,\n");
        html.append("        slideDuration: parseInt(document.getElementById('slideDuration').value, 10),\n");
        html.append("        tickerText: document.getElementById('tickerText').value,\n");
        html.append("        tickerTextColor: document.getElementById('tickerTextColor').value,\n");
        html.append("        tickerBgColor: document.getElementById('tickerBgColor').value,\n");
        html.append("        tickerPosition: document.getElementById('tickerPosition').value,\n");
        html.append("        tickerFontSize: parseInt(document.getElementById('tickerFontSize').value, 10),\n");
        html.append("        tickerFontFamily: document.getElementById('tickerFontFamily').value,\n");
        html.append("        tickerSpeed: parseInt(document.getElementById('tickerSpeed').value, 10),\n");
        html.append("        tickerDirection: document.getElementById('tickerDirection').value,\n");
        html.append("        mediaTransition: document.getElementById('mediaTransition').value,\n");
        html.append("        resizeMode: document.getElementById('resizeMode').value,\n");
        html.append("        usePendrive: document.getElementById('usePendrive').value === 'true',\n");
        html.append("        showQrCode: document.getElementById('showQrCode').value === 'true',\n");
        html.append("        kioskMode: document.getElementById('kioskMode').value === 'true',\n");
        html.append("        layoutMode: document.getElementById('layoutMode').value,\n");
        html.append("        sectionRatio: document.getElementById('sectionRatio').value\n");
        html.append("      };\n");
        html.append("      cfg.tickerTextBase64 = utf8Base64(cfg.tickerText);\n");
        html.append("      delete cfg.tickerText;\n");
        html.append("      await multiFetch('/api/config', {\n");
        html.append("        method: 'POST',\n");
        html.append("        body: JSON.stringify(cfg)\n");
        html.append("      });\n");
        html.append("      var msgEl = document.getElementById('saveSuccessMsg');\n");
        html.append("      if (msgEl) {\n");
        html.append("        msgEl.style.display = 'block';\n");
        html.append("        setTimeout(function() { msgEl.style.display = 'none'; }, 4000);\n");
        html.append("      }\n");
        html.append("      fetchConfig();\n");
        html.append("    }\n");
        html.append("    async function fetchMedia() {\n");
        html.append("      const res = await sectionFetch('/api/media?ts=' + Date.now(), { cache: 'no-store' });\n");
        html.append("      allMedia = await res.json();\n");
        html.append("      renderSectionTabs();\n");
        html.append("      renderFiles();\n");
        html.append("    }\n");
        html.append("    function renderSectionTabs() {\n");
        html.append("      const sections = Object.keys(allMedia);\n");
        html.append("      if (sections.length === 0) sections.push('section1');\n");
        html.append("      if (!sections.includes(currentSection)) currentSection = sections[0];\n");
        html.append("      const tabContainer = document.getElementById('secTabContainer');\n");
        html.append("      tabContainer.innerHTML = sections.map(s => `\n");
        html.append("        <button class=\"sec-tab ${s === currentSection ? 'active' : ''}\" data-sec=\"${s}\" onclick=\"selectSection('${s}')\">${s.toUpperCase()}</button>\n");
        html.append("      `).join('');\n");
        html.append("      document.getElementById('secTitle').innerText = 'Files in ' + currentSection.toUpperCase();\n");
        html.append("    }\n");
        html.append("    let draggedIndex = null;\n");
        html.append("    function handleDragStart(e, idx) {\n");
        html.append("      draggedIndex = idx;\n");
        html.append("      if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move';\n");
        html.append("    }\n");
        html.append("    function handleDragOver(e) {\n");
        html.append("      e.preventDefault();\n");
        html.append("      if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';\n");
        html.append("    }\n");
        html.append("    function handleDropFile(e, targetIdx) {\n");
        html.append("      e.preventDefault();\n");
        html.append("      if (draggedIndex === null || draggedIndex === targetIdx) return;\n");
        html.append("      const files = allMedia[currentSection] || [];\n");
        html.append("      const movedItem = files.splice(draggedIndex, 1)[0];\n");
        html.append("      files.splice(targetIdx, 0, movedItem);\n");
        html.append("      draggedIndex = null;\n");
        html.append("      saveFileOrder(files);\n");
        html.append("    }\n");
        html.append("    function moveFileOrder(idx, direction) {\n");
        html.append("      const files = allMedia[currentSection] || [];\n");
        html.append("      const newIdx = idx + direction;\n");
        html.append("      if (newIdx < 0 || newIdx >= files.length) return;\n");
        html.append("      const movedItem = files.splice(idx, 1)[0];\n");
        html.append("      files.splice(newIdx, 0, movedItem);\n");
        html.append("      saveFileOrder(files);\n");
        html.append("    }\n");
        html.append("    async function saveFileOrder(files) {\n");
        html.append("      allMedia[currentSection] = files;\n");
        html.append("      renderFiles();\n");
        html.append("      const fileNames = files.map(f => f.name);\n");
        html.append("      await sectionFetch('/api/reorder-media', {\n");
        html.append("        method: 'POST',\n");
        html.append("        body: JSON.stringify({ section: currentSection, fileOrder: fileNames })\n");
        html.append("      });\n");
        html.append("      showAlert('Play sequence updated! TV Player will play files in the new order.');\n");
        html.append("    }\n");
        html.append("    function renderFiles() {\n");
        html.append("      const files = allMedia[currentSection] || [];\n");
        html.append("      const listEl = document.getElementById('fileList');\n");
        html.append("      if (files.length === 0) {\n");
        html.append("        listEl.innerHTML = '<p style=\"color: var(--text-muted); font-size: 14px;\">No files in this section.</p>';\n");
        html.append("        return;\n");
        html.append("      }\n");
        html.append("      listEl.innerHTML = files.map((f, idx) => `\n");
        html.append("        <div class=\"file-item\" draggable=\"true\" data-index=\"${idx}\" ondragstart=\"handleDragStart(event, ${idx})\" ondragover=\"handleDragOver(event)\" ondrop=\"handleDropFile(event, ${idx})\" style=\"cursor:move; user-select:none;\">\n");
        html.append("          <div class=\"file-info\" style=\"display:flex; align-items:center;\">\n");
        html.append("            <span style=\"font-size:18px; margin-right:8px; color:var(--text-muted);\" title=\"Drag to reorder\">⋮⋮</span>\n");
        html.append("            <span style=\"font-weight:bold; color:var(--accent); font-size:12px; margin-right:8px; min-width:24px;\">#${idx + 1}</span>\n");
        html.append("            <span>📄</span>\n");
        html.append("            <div style=\"margin-left:8px;\">\n");
        html.append("              <div class=\"file-name\">${f.name}</div>\n");
        html.append("              <div class=\"file-size\">${(f.size / (1024*1024)).toFixed(2)} MB</div>\n");
        html.append("            </div>\n");
        html.append("          </div>\n");
        html.append("          <div style=\"display:flex; gap:6px; align-items:center;\">\n");
        html.append("            <button class=\"btn-action\" style=\"padding:4px 10px; font-size:13px;\" onclick=\"moveFileOrder(${idx}, -1)\" ${idx === 0 ? 'disabled style=\"opacity:0.3;\"' : ''}>⬆️</button>\n");
        html.append("            <button class=\"btn-action\" style=\"padding:4px 10px; font-size:13px;\" onclick=\"moveFileOrder(${idx}, 1)\" ${idx === files.length - 1 ? 'disabled style=\"opacity:0.3;\"' : ''}>⬇️</button>\n");
        html.append("            <button class=\"btn-del\" onclick=\"deleteFile('${f.name}')\">🗑️ Delete</button>\n");
        html.append("          </div>\n");
        html.append("        </div>\n");
        html.append("      `).join('');\n");
        html.append("    }\n");
        html.append("    async function createNewSection() {\n");
        html.append("      const name = prompt('Enter new section folder name (e.g. section5):');\n");
        html.append("      if (!name) return;\n");
        html.append("      await sectionFetch('/api/create-section', {\n");
        html.append("        method: 'POST',\n");
        html.append("        body: JSON.stringify({ sectionName: name })\n");
        html.append("      });\n");
        html.append("      showAlert('New section created in nvsign folder!');\n");
        html.append("      fetchMedia();\n");
        html.append("    }\n");
        html.append("    async function renameCurrentSection() {\n");
        html.append("      const newName = prompt('Enter new name for section ' + currentSection.toUpperCase() + ':', currentSection);\n");
        html.append("      if (!newName || newName.trim() === '' || newName.trim().toLowerCase() === currentSection.toLowerCase()) return;\n");
        html.append("      const res = await sectionFetch('/api/rename-section', {\n");
        html.append("        method: 'POST',\n");
        html.append("        body: JSON.stringify({ oldName: currentSection, newName: newName })\n");
        html.append("      });\n");
        html.append("      const data = await res.json();\n");
        html.append("      if (data.success) {\n");
        html.append("        currentSection = newName.trim().toLowerCase().replaceAll(/[^a-zA-Z0-9_-]/g, '');\n");
        html.append("        showAlert('Section renamed successfully!');\n");
        html.append("        fetchMedia();\n");
        html.append("      } else {\n");
        html.append("        alert('Failed to rename section: ' + (data.error || 'Unknown error'));\n");
        html.append("      }\n");
        html.append("    }\n");
        html.append("    async function deleteCurrentSection() {\n");
        html.append("      if (!confirm('Are you sure you want to delete ' + currentSection.toUpperCase() + ' and all its files?')) return;\n");
        html.append("      await sectionFetch('/api/delete-section', {\n");
        html.append("        method: 'POST',\n");
        html.append("        body: JSON.stringify({ sectionName: currentSection })\n");
        html.append("      });\n");
        html.append("      showAlert('Section deleted!');\n");
        html.append("      fetchMedia();\n");
        html.append("    }\n");
        html.append("    function uploadFiles(fileList) {\n");
        html.append("      if (!fileList || fileList.length === 0) return;\n");
        html.append("      const files = Array.from(fileList);\n");
        html.append("      const videoFilePattern = /\\.(mp4|m4v|mov|mkv|avi|3gp|3g2|wmv|flv|mpg|mpeg|ts|mts|m2ts|vob|ogv|asf|f4v|webm)$/i;\n");
        html.append("      const includesVideo = files.some(file => videoFilePattern.test(file.name || ''));\n");
        html.append("      const currentSectionHasVideo = (allMedia[currentSection] || []).some(file => videoFilePattern.test(file.name || ''));\n");
        html.append("      const otherVideoSections = Object.keys(allMedia).filter(section => section !== currentSection && (allMedia[section] || []).some(file => videoFilePattern.test(file.name || ''))).length;\n");
        html.append("      if (includesVideo && !currentSectionHasVideo && otherVideoSections >= 2) { showAlert('Video uploads are allowed in only 2 sections. Delete videos from an existing video section before uploading here.'); return; }\n");
        html.append("      const totalFiles = files.length;\n");
        html.append("      let completedFiles = 0;\n");
        html.append("      let totalBytes = 0;\n");
        html.append("      files.forEach(f => totalBytes += f.size);\n");
        html.append("      let uploadedBytesPrior = 0;\n");
        html.append("\n");
        html.append("      const progressBox = document.getElementById('progressBox');\n");
        html.append("      const progressBar = document.getElementById('progressBar');\n");
        html.append("      const progressPercent = document.getElementById('progressPercent');\n");
        html.append("      const progressTitle = document.getElementById('progressTitle');\n");
        html.append("      const progressSub = document.getElementById('progressSub');\n");
        html.append("\n");
        html.append("      progressBox.style.display = 'block';\n");
        html.append("      progressBar.style.width = '0%';\n");
        html.append("      progressPercent.innerText = '0%';\n");
        html.append("      progressTitle.innerText = `Uploading to ${currentSection.toUpperCase()}...`;\n");
        html.append("      progressSub.innerText = `0 of ${totalFiles} files uploaded`;\n");
        html.append("\n");
        html.append("      function uploadNext(index) {\n");
        html.append("        if (index >= totalFiles) {\n");
        html.append("          progressBox.style.display = 'none';\n");
        html.append("          showAlert(`Successfully uploaded all ${totalFiles} file(s) to ${currentSection.toUpperCase()}!`);\n");
        html.append("          document.getElementById('fileInput').value = '';\n");
        html.append("          fetchMedia();\n");
        html.append("          return;\n");
        html.append("        }\n");
        html.append("        const file = files[index];\n");
        html.append("        const formData = new FormData();\n");
        html.append("        formData.append('section', currentSection);\n");
        html.append("        formData.append('file', file, file.name);\n");
        html.append("\n");
        html.append("        const ips = [sectionTvIp || window.location.hostname];\n");
        html.append("        let uploadsDone = 0;\n");
        html.append("        let uploadFailed = false;\n");
        html.append("        ips.forEach(ip => {\n");
        html.append("          const xhr = new XMLHttpRequest();\n");
        html.append("          const uploadPath = '/api/upload?section=' + encodeURIComponent(currentSection) + '&filename=' + encodeURIComponent(file.name);\n");
        html.append("          xhr.open('POST', targetUrl(ip, uploadPath), true);\n");
        html.append("          xhr.upload.onprogress = function(e) {\n");
        html.append("            if (e.lengthComputable && ip === ips[0]) {\n");
        html.append("              const overallPercent = Math.min(100, Math.round(((uploadedBytesPrior + e.loaded) / totalBytes) * 100));\n");
        html.append("              progressBar.style.width = overallPercent + '%';\n");
        html.append("              progressPercent.innerText = overallPercent + '%';\n");
        html.append("              progressTitle.innerText = `Uploading (${index + 1}/${totalFiles}): ${file.name}`;\n");
        html.append("              progressSub.innerText = `${index} of ${totalFiles} complete`;\n");
        html.append("            }\n");
        html.append("          };\n");
        html.append("          let uploadError = '';\n");
        html.append("          const finishUpload = function(ok, errorMessage) {\n");
        html.append("            uploadsDone++; if (!ok) { uploadFailed = true; if (!uploadError && errorMessage) uploadError = errorMessage; }\n");
        html.append("            if (uploadsDone !== ips.length) return;\n");
        html.append("            if (uploadFailed) { showAlert(uploadError || `Upload failed on one or more TVs for ${file.name}.`); progressBox.style.display = 'none'; return; }\n");
        html.append("            uploadedBytesPrior += file.size; completedFiles++; uploadNext(index + 1);\n");
        html.append("          };\n");
        html.append("          xhr.onload = function() { let errorMessage = ''; if (xhr.status < 200 || xhr.status >= 300) { try { errorMessage = (JSON.parse(xhr.responseText || '{}').error || ''); } catch (_) {} } finishUpload(xhr.status >= 200 && xhr.status < 300, errorMessage); };\n");
        html.append("          xhr.onerror = function() { finishUpload(false, 'Upload failed. Check the TV connection and try again.'); };\n");
        html.append("          xhr.send(formData);\n");
        html.append("        });\n");
        html.append("      }\n");
        html.append("      uploadNext(0);\n");
        html.append("    }\n");
        html.append("    async function deleteFile(fileName) {\n");
        html.append("      if (!confirm('Delete ' + fileName + '?')) return;\n");
        html.append("      await sectionFetch('/api/delete', {\n");
        html.append("        method: 'POST',\n");
        html.append("        body: JSON.stringify({ section: currentSection, fileName: fileName })\n");
        html.append("      });\n");
        html.append("      showAlert('File deleted!');\n");
        html.append("      fetchMedia();\n");
        html.append("    }\n");
        html.append("    function showAlert(msg) {\n");
        html.append("      const alert = document.getElementById('alertMsg');\n");
        html.append("      alert.innerText = msg;\n");
        html.append("      alert.style.display = 'block';\n");
        html.append("      setTimeout(() => alert.style.display = 'none', 4000);\n");
        html.append("    }\n");
        html.append("  </script>\n");
        html.append("</body>\n");
        html.append("</html>");
        return html.toString();
    }
}
