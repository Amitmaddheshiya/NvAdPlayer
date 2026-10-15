package com.nvadplayer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

import java.util.Map;

public class AndroidNativeMediaPlayerManager extends SimpleViewManager<AndroidNativeMediaPlayerView> {
    public static final String REACT_CLASS = "AndroidNativeMediaPlayerView";

    @NonNull
    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @NonNull
    @Override
    protected AndroidNativeMediaPlayerView createViewInstance(@NonNull ThemedReactContext reactContext) {
        return new AndroidNativeMediaPlayerView(reactContext);
    }

    @ReactProp(name = "srcPath")
    public void setSrcPath(AndroidNativeMediaPlayerView view, @Nullable String path) {
        view.setSrcPath(path);
    }

    @ReactProp(name = "muted", defaultBoolean = true)
    public void setMuted(AndroidNativeMediaPlayerView view, boolean muted) {
        view.setMuted(muted);
    }

    @ReactProp(name = "repeat", defaultBoolean = true)
    public void setRepeat(AndroidNativeMediaPlayerView view, boolean repeat) {
        view.setRepeat(repeat);
    }

    @ReactProp(name = "paused", defaultBoolean = false)
    public void setPaused(AndroidNativeMediaPlayerView view, boolean paused) {
        view.setPaused(paused);
    }

    @ReactProp(name = "resizeMode")
    public void setResizeMode(AndroidNativeMediaPlayerView view, @Nullable String mode) {
        view.setResizeMode(mode);
    }

    @Nullable
    @Override
    public Map<String, Object> getExportedCustomDirectEventTypeConstants() {
        return MapBuilder.<String, Object>builder()
                .put("onVideoEnd", MapBuilder.of("registrationName", "onVideoEnd"))
                .put("onVideoError", MapBuilder.of("registrationName", "onVideoError"))
                .build();
    }
}
