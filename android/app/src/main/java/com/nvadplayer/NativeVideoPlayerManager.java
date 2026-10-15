package com.nvadplayer;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.common.MapBuilder;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

import java.util.Map;

public class NativeVideoPlayerManager extends SimpleViewManager<NativeVideoPlayerView> {
    public static final String REACT_CLASS = "NativeVideoPlayerView";

    @NonNull
    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @NonNull
    @Override
    protected NativeVideoPlayerView createViewInstance(@NonNull ThemedReactContext reactContext) {
        return new NativeVideoPlayerView(reactContext);
    }

    @ReactProp(name = "srcPath")
    public void setSrcPath(NativeVideoPlayerView view, @Nullable String path) {
        view.setSrcPath(path);
    }

    @ReactProp(name = "muted", defaultBoolean = false)
    public void setMuted(NativeVideoPlayerView view, boolean muted) {
        view.setMuted(muted);
    }

    @ReactProp(name = "isSecondary", defaultBoolean = false)
    public void setIsSecondary(NativeVideoPlayerView view, boolean isSecondary) {
        view.setIsSecondary(isSecondary);
    }

    @ReactProp(name = "repeat", defaultBoolean = false)
    public void setRepeat(NativeVideoPlayerView view, boolean repeat) {
        view.setRepeat(repeat);
    }

    @ReactProp(name = "paused", defaultBoolean = false)
    public void setPaused(NativeVideoPlayerView view, boolean paused) {
        view.setPaused(paused);
    }

    @ReactProp(name = "resizeMode")
    public void setResizeMode(NativeVideoPlayerView view, @Nullable String mode) {
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
