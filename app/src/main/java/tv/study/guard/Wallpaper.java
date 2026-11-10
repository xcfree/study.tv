package tv.study.guard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;

final class Wallpaper extends FrameLayout implements TextureView.SurfaceTextureListener {
    private final Store store;
    private final SharedPreferences.OnSharedPreferenceChangeListener onChange = (prefs, key) -> {
        if (Store.WALLPAPER_REVISION.equals(key)) updateMedia();
    };
    private TextureView texture;
    private File video;
    private MediaPlayer player;
    private boolean active = true;
    private boolean videoPaused;
    private boolean playerPrepared;
    private int videoWidth, videoHeight;

    Wallpaper(Context context, Store store, boolean dimmed) {
        super(context);
        this.store = store;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setBackground(TvUi.backdrop());
        setDimmed(dimmed);
    }

    void setDimmed(boolean dimmed) {
        setForeground(dimmed ? TvUi.wallpaperShade() : null);
    }

    void setVideoPaused(boolean paused) {
        if (videoPaused == paused) return;
        videoPaused = paused;
        if (!playerPrepared) return;
        try {
            if (paused) player.pause();
            else player.start();
        } catch (IllegalStateException e) {
            hideVideo();
        }
    }

    void setActive(boolean active) {
        if (this.active == active) return;
        this.active = active;
        if (isAttachedToWindow()) updateMedia();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        store.prefs.registerOnSharedPreferenceChangeListener(onChange);
        updateMedia();
    }

    @Override
    protected void onDetachedFromWindow() {
        store.prefs.unregisterOnSharedPreferenceChangeListener(onChange);
        clearMedia();
        super.onDetachedFromWindow();
    }

    private void updateMedia() {
        clearMedia();
        if (!active) return;
        Context context = getContext();
        String kind = store.wallpaperKind();
        if (LocalContent.Kind.IMAGE.name().equals(kind)) {
            Bitmap bitmap = LocalContent.readImage(LocalContent.file(context, LocalContent.Kind.IMAGE), 1920, 1080);
            if (bitmap != null) {
                ImageView image = new ImageView(context);
                image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                image.setImageBitmap(bitmap);
                addView(image, new LayoutParams(TvUi.MATCH, TvUi.MATCH));
                return;
            }
        } else if (LocalContent.Kind.VIDEO.name().equals(kind) && isHardwareAccelerated()) {
            video = LocalContent.file(context, LocalContent.Kind.VIDEO);
            if (video.isFile()) {
                texture = new TextureView(context);
                texture.setOpaque(false);
                texture.setSurfaceTextureListener(this);
                addView(texture, new LayoutParams(TvUi.MATCH, TvUi.MATCH));
                return;
            }
        }
        showDefaultImage();
    }

    private void showDefaultImage() {
        ImageView image = new ImageView(getContext());
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImageResource(R.drawable.default_wallpaper);
        addView(image, new LayoutParams(TvUi.MATCH, TvUi.MATCH));
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        player = new MediaPlayer();
        Surface surface = new Surface(surfaceTexture);
        try {
            player.setSurface(surface);
            player.setDataSource(video.getPath());
            player.setVolume(0f, 0f);
            player.setLooping(true);
            player.setOnVideoSizeChangedListener((media, w, h) -> {
                if (media != player) return;
                videoWidth = w;
                videoHeight = h;
                crop();
            });
            player.setOnPreparedListener(media -> {
                if (media != player) return;
                playerPrepared = true;
                if (!videoPaused) media.start();
            });
            player.setOnErrorListener((media, what, extra) -> {
                if (media == player) hideVideo();
                return true;
            });
            player.prepareAsync();
        } catch (IOException | RuntimeException e) {
            hideVideo();
        } finally {
            surface.release();
        }
    }

    private void crop() {
        if (texture.getWidth() == 0 || texture.getHeight() == 0 || videoWidth == 0 || videoHeight == 0) return;
        float viewRatio = (float) texture.getWidth() / texture.getHeight();
        float videoRatio = (float) videoWidth / videoHeight;
        Matrix matrix = new Matrix();
        matrix.setScale(Math.max(1f, videoRatio / viewRatio), Math.max(1f, viewRatio / videoRatio),
                texture.getWidth() / 2f, texture.getHeight() / 2f);
        texture.setTransform(matrix);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        crop();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {}

    private void clearMedia() {
        release();
        if (texture != null) texture.setSurfaceTextureListener(null);
        removeAllViews();
        texture = null;
        videoWidth = videoHeight = 0;
    }

    private void release() {
        playerPrepared = false;
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void hideVideo() {
        release();
        texture.setVisibility(View.INVISIBLE);
        showDefaultImage();
    }
}
