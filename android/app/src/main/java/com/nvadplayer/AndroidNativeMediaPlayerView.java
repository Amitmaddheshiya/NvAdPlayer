package com.nvadplayer;

import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import java.io.File;

public class AndroidNativeMediaPlayerView extends FrameLayout implements TextureView.SurfaceTextureListener {
    private static final String TAG = "AndroidMediaPlayerView";
    private final ReactContext reactContext;
    private TextureView textureView;
    private MediaPlayer mediaPlayer;
    private Surface surface;

    private String srcPath = "";
    private boolean muted = true;
    private boolean repeat = true;
    private boolean paused = false;
    private String resizeMode = "stretch";

    public AndroidNativeMediaPlayerView(@NonNull ReactContext context) {
        super(context);
        this.reactContext = context;
        initView();
    }

    private void initView() {
        setBackgroundColor(Color.BLACK);
        textureView = new TextureView(getContext());
        textureView.setLayoutParams(new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        textureView.setSurfaceTextureListener(this);
        addView(textureView);
    }

    private void setupMediaPlayer() {
        if (srcPath == null || srcPath.isEmpty() || surface == null) return;
        releaseMediaPlayer();

        try {
            mediaPlayer = new MediaPlayer();
            Uri uri;
            if (srcPath.startsWith("http://") || srcPath.startsWith("https://") || srcPath.startsWith("file://")) {
                uri = Uri.parse(srcPath);
            } else {
                uri = Uri.fromFile(new File(srcPath));
            }

            mediaPlayer.setDataSource(getContext(), uri);
            mediaPlayer.setSurface(surface);
            mediaPlayer.setLooping(repeat);
            if (muted) {
                mediaPlayer.setVolume(0f, 0f);
            } else {
                mediaPlayer.setVolume(1f, 1f);
            }

            mediaPlayer.setOnPreparedListener(mp -> {
                if (!paused) {
                    mp.start();
                }
            });

            mediaPlayer.setOnCompletionListener(mp -> {
                dispatchEvent("onVideoEnd", null);
            });

            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                WritableMap payload = Arguments.createMap();
                payload.putString("error", "MediaPlayer error code: " + what);
                dispatchEvent("onVideoError", payload);
                return true;
            });

            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Error setting up Android MediaPlayer", e);
        }
    }

    public void setSrcPath(String path) {
        if (path == null || path.equals(this.srcPath)) return;
        this.srcPath = path;
        setupMediaPlayer();
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (mediaPlayer != null) {
            mediaPlayer.setVolume(muted ? 0f : 1f, muted ? 0f : 1f);
        }
    }

    public void setRepeat(boolean repeat) {
        this.repeat = repeat;
        if (mediaPlayer != null) {
            mediaPlayer.setLooping(repeat);
        }
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (mediaPlayer != null) {
            try {
                if (paused && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                } else if (!paused && !mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
            } catch (Exception ignored) {}
        }
    }

    public void setResizeMode(String mode) {
        this.resizeMode = mode;
    }

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        this.surface = new Surface(surfaceTexture);
        setupMediaPlayer();
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {}

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
        releaseMediaPlayer();
        if (this.surface != null) {
            this.surface.release();
            this.surface = null;
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {}

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
    }

    private void dispatchEvent(String eventName, WritableMap eventData) {
        WritableMap data = eventData != null ? eventData : Arguments.createMap();
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                eventName,
                data
        );
    }
}
