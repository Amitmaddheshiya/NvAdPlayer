package com.nvadplayer;

import android.graphics.Color;
import android.net.Uri;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.events.RCTEventEmitter;

import java.io.File;

import android.view.LayoutInflater;

public class NativeVideoPlayerView extends FrameLayout implements Player.Listener {
    private static final String TAG = "NativeVideoPlayerView";
    private final ReactContext reactContext;
    private PlayerView playerView;
    private ExoPlayer player;

    private String srcPath = "";
    private boolean muted = false;
    private boolean repeat = false;
    private boolean paused = false;
    private boolean isSecondary = false;
    private String resizeMode = "stretch";

    private boolean isPrepared = false;

    public NativeVideoPlayerView(@NonNull ReactContext context) {
        super(context);
        this.reactContext = context;
        initView();
    }

    private void initView() {
        setBackgroundColor(Color.BLACK);

        LayoutInflater.from(getContext()).inflate(R.layout.native_video_player_view, this, true);
        playerView = findViewById(R.id.native_video_player_surface);
        if (playerView != null) {
            playerView.setKeepScreenOn(true);
            playerView.setKeepContentOnPlayerReset(false);
        }

        initializePlayer();
    }

    private void initializePlayer() {
        if (player != null) return;

        try {
            // Track Selector - Resolution and Bitrate Optimization for TV Performance
            DefaultTrackSelector trackSelector = new DefaultTrackSelector(getContext());
            if (isSecondary) {
                // Secondary sections decode at 640x360 max to prevent TV CPU overheating & OS reboots
                trackSelector.setParameters(trackSelector.buildUponParameters()
                        .setMaxVideoSize(640, 360)
                        .setMaxVideoBitrate(1500000));
            }

            // Memory-Optimized Load Control
            int minBuffer = 1000;
            int maxBuffer = isSecondary ? 3000 : 8000;
            int targetBufferBytes = isSecondary ? 2 * 1024 * 1024 : 8 * 1024 * 1024; // 2MB for secondary, 8MB for primary

            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                            minBuffer,
                            maxBuffer,
                            250,     // bufferForPlaybackMs
                            500      // bufferForPlaybackAfterRebufferMs
                    )
                    .setTargetBufferBytes(targetBufferBytes)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .setBackBuffer(0, false)
                    .build();

            // Network Configuration
            DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(20000)
                    .setReadTimeoutMs(120000);

            DefaultDataSource.Factory upstreamFactory = new DefaultDataSource.Factory(getContext(), httpFactory);

            // Renderers Factory with Software Decoder Fallback enabled
            DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(getContext())
                    .setEnableDecoderFallback(true);

            player = new ExoPlayer.Builder(getContext(), renderersFactory)
                    .setTrackSelector(trackSelector)
                    .setLoadControl(loadControl)
                    .setMediaSourceFactory(new DefaultMediaSourceFactory(upstreamFactory))
                    .build();

            player.addListener(this);
            player.setVolume(muted ? 0f : 1f);
            player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
            playerView.setPlayer(player);

            applyResizeMode();
            preparePlayerSource();
        } catch (Exception e) {
            Log.e(TAG, "Error initializing ExoPlayer", e);
        }
    }

    private void preparePlayerSource() {
        if (player == null || srcPath == null || srcPath.isEmpty()) return;

        Uri uri;
        if (srcPath.startsWith("http://") || srcPath.startsWith("https://") || srcPath.startsWith("file://")) {
            uri = Uri.parse(srcPath);
        } else {
            uri = Uri.fromFile(new File(srcPath));
        }

        MediaItem mediaItem = MediaItem.fromUri(uri);
        player.setMediaItem(mediaItem);
        player.prepare();
        player.setPlayWhenReady(!paused);
        isPrepared = true;
    }

    public void setSrcPath(String path) {
        if (path == null || path.equals(this.srcPath)) return;
        this.srcPath = path;
        if (player != null) {
            preparePlayerSource();
        }
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
        if (player != null) {
            player.setVolume(muted ? 0f : 1f);
        }
    }

    public void setIsSecondary(boolean secondary) {
        if (this.isSecondary == secondary) return;
        this.isSecondary = secondary;
        if (player != null) {
            releasePlayer();
            initializePlayer();
        }
    }

    public void setRepeat(boolean repeat) {
        this.repeat = repeat;
        if (player != null) {
            player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        }
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        if (player != null && isPrepared) {
            player.setPlayWhenReady(!paused);
        }
    }

    public void setResizeMode(String mode) {
        this.resizeMode = mode;
        applyResizeMode();
    }

    private void applyResizeMode() {
        if (playerView == null) return;
        int mode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
        if ("cover".equalsIgnoreCase(resizeMode)) {
            mode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM;
        } else if ("contain".equalsIgnoreCase(resizeMode)) {
            mode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
        } else if ("stretch".equalsIgnoreCase(resizeMode)) {
            mode = AspectRatioFrameLayout.RESIZE_MODE_FILL;
        }
        playerView.setResizeMode(mode);
    }

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        if (playbackState == Player.STATE_ENDED) {
            dispatchEvent("onVideoEnd", null);
        }
    }

    @Override
    public void onPlayerError(@NonNull PlaybackException error) {
        Log.e(TAG, "ExoPlayer Error: " + error.getMessage(), error);
        WritableMap payload = Arguments.createMap();
        payload.putString("error", error.getMessage());
        dispatchEvent("onVideoError", payload);
    }

    private void dispatchEvent(String eventName, @Nullable WritableMap eventData) {
        WritableMap data = eventData != null ? eventData : Arguments.createMap();
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                eventName,
                data
        );
    }

    public void releasePlayer() {
        if (player != null) {
            player.removeListener(this);
            player.release();
            player = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        releasePlayer();
        super.onDetachedFromWindow();
    }
}
