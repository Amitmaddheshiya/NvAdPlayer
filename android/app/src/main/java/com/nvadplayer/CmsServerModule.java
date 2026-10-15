package com.nvadplayer;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableMap;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class CmsServerModule extends ReactContextBaseJavaModule {
    private static final String TAG = "CmsServerModule";
    private final ReactApplicationContext reactContext;
    private EmbeddedCmsServer server;

    private static ToneGenerator toneGenerator;

    public CmsServerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;
    }

    @Override
    public String getName() {
        return "CmsServerModule";
    }

    @ReactMethod
    public void playSirenAlarm() {
        try {
            if (toneGenerator == null) {
                toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            }
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 5000);
        } catch (Exception e) {
            Log.e(TAG, "Error playing siren alarm", e);
        }
    }

    @ReactMethod
    public void stopSirenAlarm() {
        try {
            if (toneGenerator != null) {
                toneGenerator.stopTone();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error stopping siren alarm", e);
        }
    }

    @ReactMethod
    public void startServer(Promise promise) {
        try {
            if (server == null) {
                server = new EmbeddedCmsServer(reactContext, EmbeddedCmsServer.DEFAULT_PORT);
                server.start(600000, false); // 10 minutes timeout for large file uploads
                Log.d(TAG, "Embedded HTTP CMS Server started on port 9090");
            }
            String url = "http://" + getLocalIpAddress() + ":9090";
            String qrCode = generateQrCodeBase64(url, 200, 200);

            WritableMap res = Arguments.createMap();
            res.putString("url", url);
            res.putString("qrCode", qrCode);
            promise.resolve(res);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start CMS Server", e);
            promise.reject("START_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void stopServer(Promise promise) {
        try {
            if (server != null) {
                server.stop();
                server = null;
            }
            promise.resolve(true);
        } catch (Exception e) {
            promise.reject("STOP_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void getSavedConfig(Promise promise) {
        try {
            EmbeddedCmsServer configServer = server != null ? server : new EmbeddedCmsServer(reactContext, EmbeddedCmsServer.DEFAULT_PORT);
            promise.resolve(configServer.getSavedConfigJson());
        } catch (Exception e) {
            promise.reject("CONFIG_READ_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void getSavedLayout(Promise promise) {
        try {
            EmbeddedCmsServer configServer = server != null ? server : new EmbeddedCmsServer(reactContext, EmbeddedCmsServer.DEFAULT_PORT);
            promise.resolve(configServer.getSavedLayoutJson());
        } catch (Exception e) {
            promise.reject("LAYOUT_READ_ERROR", e.getMessage());
        }
    }

    @ReactMethod
    public void getIpAddress(Promise promise) {
        try {
            String url = "http://" + getLocalIpAddress() + ":9090";
            String qrCode = generateQrCodeBase64(url, 200, 200);

            WritableMap res = Arguments.createMap();
            res.putString("url", url);
            res.putString("qrCode", qrCode);
            promise.resolve(res);
        } catch (Exception e) {
            promise.reject("IP_ERROR", e.getMessage());
        }
    }

    private String generateQrCodeBase64(String text, int width, int height) {
        try {
            BitMatrix bitMatrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height);
            int matrixWidth = bitMatrix.getWidth();
            int matrixHeight = bitMatrix.getHeight();
            int[] pixels = new int[matrixWidth * matrixHeight];
            for (int y = 0; y < matrixHeight; y++) {
                int offset = y * matrixWidth;
                for (int x = 0; x < matrixWidth; x++) {
                    pixels[offset + x] = bitMatrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(matrixWidth, matrixHeight, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, matrixWidth, 0, 0, matrixWidth, matrixHeight);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
            return "data:image/png;base64," + Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Error generating QR code", e);
            return "";
        }
    }

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
}
