package com.nvadplayer;

import android.graphics.Color;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;

import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

public class SimpleWebViewManager extends SimpleViewManager<WebView> {
    public static final String REACT_CLASS = "SecondaryWebViewPlayer";

    @NonNull
    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @NonNull
    @Override
    protected WebView createViewInstance(@NonNull ThemedReactContext reactContext) {
        WebView webView = new WebView(reactContext);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        webView.setBackgroundColor(Color.BLACK);
        return webView;
    }

    @ReactProp(name = "videoPath")
    public void setVideoPath(WebView webView, String path) {
        if (path == null || path.isEmpty()) return;
        String fileUri = path.startsWith("file://") ? path : "file://" + path;
        String html = "<!DOCTYPE html><html><head><style>" +
                "* { margin:0; padding:0; background:#000; overflow:hidden; width:100%; height:100%; }" +
                "video { width:100vw; height:100vh; object-fit:fill; display:block; position:absolute; top:0; left:0; }" +
                "</style></head><body>" +
                "<video src=\"" + fileUri + "\" autoplay loop muted playsinline webkit-playsinline></video>" +
                "</body></html>";
        webView.loadDataWithBaseURL("file:///", html, "text/html", "UTF-8", null);
    }

    @ReactProp(name = "resizeMode")
    public void setResizeMode(WebView webView, String mode) {
        if (mode == null) return;
        String fit = "fill";
        if ("contain".equalsIgnoreCase(mode)) fit = "contain";
        else if ("cover".equalsIgnoreCase(mode)) fit = "cover";
        else if ("stretch".equalsIgnoreCase(mode)) fit = "fill";

        String js = "var v = document.querySelector('video'); if(v){ v.style.objectFit = '" + fit + "'; }";
        webView.evaluateJavascript(js, null);
    }
}
