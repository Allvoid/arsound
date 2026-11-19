package app.revanced.extension.soundcloud.wallpaper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.PowerManager;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * A wallpaper that moves: a video (mp4, webm, mkv: whatever {@link MediaPlayer} plays) or an animated picture (GIF,
 * animated WebP or AVIF). It is one layer put at the bottom of the main activity's window, over the still frame that
 * the window carries ({@link WallpaperDrawable}) and under every screen, which are clear while a wallpaper is shown.
 * <p>
 * The video plays on a loop with the sound off and takes no audio focus. It plays only while the main activity is on
 * the screen and the phone is not in battery saver mode: when the app is in the background, or any other screen of the
 * app is open over it, the player is released (the still frame shows), so a wallpaper costs no battery then.
 * <p>
 * The dim is a layer of the theme's surface colour over the media and follows the slider live. The blur is Android's
 * render effect on the media (Android 12 and later); before it the media is shown sharp.
 */
public final class MediaWallpaper {
    private static final Map<Activity, Layer> LAYERS = Collections.synchronizedMap(new WeakHashMap<>());

    private MediaWallpaper() {
    }

    /** Whether this phone can blur a moving wallpaper (the settings say so). */
    public static boolean canBlur() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** Called when an activity comes to the screen (from the theme's lifecycle). */
    public static void onActivityResumed(Activity activity) {
        try {
            if (!MainScreen.isMain(activity) || activity.isFinishing()) return;
            Layer layer = LAYERS.get(activity);
            if (layer == null) {
                if (!wanted()) return;
                layer = new Layer(activity);
                LAYERS.put(activity, layer);
            }
            layer.resume();
        } catch (Throwable ex) {
            Logger.printException(() -> "Wallpaper: could not start the moving wallpaper", ex);
        }
    }

    /** Called when an activity leaves the foreground. */
    public static void onActivityPaused(Activity activity) {
        try {
            Layer layer = LAYERS.get(activity);
            if (layer != null) layer.pause();
        } catch (Throwable ex) {
            Logger.printException(() -> "Wallpaper: could not stop the moving wallpaper", ex);
        }
    }

    /**
     * Called when an activity is destroyed (from the theme's lifecycle). The layer's entry is taken out here: the map
     * is weak on the activity, but the layer holds the activity, so the entry would keep every destroyed main screen
     * with its decoded frames until the process ends.
     */
    public static void onActivityDestroyed(Activity activity) {
        Layer layer;
        synchronized (LAYERS) {
            layer = LAYERS.remove(activity);
        }
        if (layer == null) return;
        try {
            layer.destroy();
        } catch (Throwable ex) {
            Logger.printException(() -> "Wallpaper: could not remove the moving wallpaper", ex);
        }
    }

    /** The dim, the blur or the file changed (see {@link WallpaperStore}): every layer follows. */
    static void onChanged() {
        Utils.runOnMainThread(() -> {
            List<Map.Entry<Activity, Layer>> copy;
            synchronized (LAYERS) {
                copy = new ArrayList<>(LAYERS.entrySet());
            }
            for (Map.Entry<Activity, Layer> entry : copy) {
                try {
                    entry.getValue().follow();
                } catch (Throwable ex) {
                    Logger.printException(() -> "Wallpaper: the moving wallpaper could not follow a change", ex);
                }
            }
        });
    }

    private static boolean wanted() {
        return WallpaperStore.isActive() && WallpaperStore.mediaFile() != null;
    }

    /** The layer in one activity's window. */
    private static final class Layer extends FrameLayout {
        private final Activity activity;
        private final View shade;
        /** The media view: a TextureView for a video, an ImageView for an animated picture; null while none. */
        private View media;
        private String stamp = "";
        private boolean resumed;
        /** Set by a failure on a background thread too, so it is volatile. */
        private volatile boolean broken;
        private boolean watchingSaver;
        /** The generation an animation is being decoded for, or -1 while none is: one decode per picture at a time. */
        private int decodingFor = -1;

        private MediaPlayer player;
        private Surface surface;
        private SurfaceTexture texture;
        private int videoWidth, videoHeight;
        private AnimatedImageDrawable animation;
        /** Bumped by every new file, so a picture decoded for an older one is dropped. */
        private int generation;

        Layer(Activity activity) {
            super(activity);
            this.activity = activity;
            setClickable(false);
            setFocusable(false);
            shade = new View(activity);
            addView(shade, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            decor.addView(this, 0, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        void resume() {
            resumed = true;
            watchSaver();
            follow();
        }

        void pause() {
            resumed = false;
            unwatchSaver();
            stopPlaying();
        }

        /** The activity is gone: the player, the decoded frames and the layer in its window go with it. */
        void destroy() {
            resumed = false;
            unwatchSaver();
            stopPlaying();
            dropMedia();
            ViewGroup parent = (ViewGroup) getParent();
            if (parent != null) parent.removeView(this);
        }

        /**
         * While the layer is on the screen, a change of battery saver stops or starts the moving picture at once: the
         * activity is not paused by the change, so without this the video kept decoding until the next visit.
         */
        private final BroadcastReceiver saverWatch = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!resumed) return;
                if (mayPlay()) startPlaying();
                else stopPlaying();
            }
        };

        private void watchSaver() {
            if (watchingSaver) return;
            try {
                activity.registerReceiver(saverWatch, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
                watchingSaver = true;
            } catch (Exception ex) {
                Logger.printException(() -> "Wallpaper: could not watch battery saver", ex);
            }
        }

        private void unwatchSaver() {
            if (!watchingSaver) return;
            watchingSaver = false;
            try {
                activity.unregisterReceiver(saverWatch);
            } catch (Exception ignored) {
                // Already gone.
            }
        }

        /** Brings the layer up to date with the files and the sliders, and plays when it may. */
        void follow() {
            if (!wanted()) {
                resumed = false;
                stopPlaying();
                dropMedia();
                setVisibility(GONE);
                return;
            }
            setVisibility(VISIBLE);
            File file = WallpaperStore.mediaFile();
            String now = file.getName() + "/" + file.lastModified() + "/" + file.length();
            if (!now.equals(stamp)) {
                stopPlaying();
                dropMedia();
                stamp = now;
                broken = false;
                buildMedia(file);
            }
            int surfaceColour = ArsoundTheme.palette(activity, "surface") | 0xFF000000;
            int alpha = Math.round(WallpaperStore.dim() * 2.55f);
            shade.setBackgroundColor(Color.argb(alpha, Color.red(surfaceColour), Color.green(surfaceColour), Color.blue(surfaceColour)));
            applyBlur();
            if (resumed) startPlaying();
        }

        private void buildMedia(File file) {
            generation++;
            if (WallpaperStore.isVideo()) {
                TextureView view = new TextureView(activity);
                view.setOpaque(false);
                view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                        texture = surfaceTexture;
                        if (resumed) startPlaying();
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                        applyCrop();
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                        stopPlaying();
                        texture = null;
                        return true;
                    }

                    @Override
                    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                    }
                });
                media = view;
            } else {
                ImageView view = new ImageView(activity);
                view.setScaleType(ImageView.ScaleType.CENTER_CROP);
                media = view;
            }
            addView(media, 0, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        private void dropMedia() {
            if (media != null) removeView(media);
            media = null;
            animation = null;
            texture = null;
            videoWidth = videoHeight = 0;
            stamp = "";
        }

        private void applyBlur() {
            if (media == null || !canBlur()) return;
            int blur = WallpaperStore.blur();
            if (blur <= 0) {
                media.setRenderEffect(null);
                return;
            }
            // The same strength as the baked picture's: relative to the width of the screen.
            float sigma = Math.max(0.5f, blur * 0.6f * activity.getResources().getDisplayMetrics().widthPixels / 1080f);
            media.setRenderEffect(RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP));
        }

        private boolean mayPlay() {
            if (!resumed || broken || media == null) return false;
            PowerManager power = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
            return power == null || !power.isPowerSaveMode();
        }

        private void startPlaying() {
            if (!mayPlay()) return;
            File file = WallpaperStore.mediaFile();
            if (file == null) return;
            if (media instanceof TextureView) startVideo(file);
            else startAnimation(file);
        }

        private void startVideo(File file) {
            if (player != null || texture == null) return;
            MediaPlayer created = new MediaPlayer();
            try {
                surface = new Surface(texture);
                created.setSurface(surface);
                created.setDataSource(file.getPath());
                created.setLooping(true);
                created.setVolume(0f, 0f);
                created.setOnVideoSizeChangedListener((mp, width, height) -> {
                    videoWidth = width;
                    videoHeight = height;
                    applyCrop();
                });
                created.setOnPreparedListener(mp -> {
                    if (player == mp && resumed) mp.start();
                });
                created.setOnErrorListener((mp, what, extra) -> {
                    Logger.printInfo(() -> "Wallpaper: the video stopped, " + what + "/" + extra);
                    broken = true;
                    stopPlaying();
                    return true;
                });
                player = created;
                created.prepareAsync();
            } catch (Exception | OutOfMemoryError ex) {
                Logger.printException(() -> "Wallpaper: could not play the video", ex);
                broken = true;
                player = created;
                stopPlaying();
            }
        }

        /** The video fills the screen the way a picture does: scaled to cover, the middle shown. */
        private void applyCrop() {
            if (!(media instanceof TextureView) || videoWidth <= 0 || videoHeight <= 0) return;
            float width = media.getWidth(), height = media.getHeight();
            if (width <= 0 || height <= 0) return;
            float cover = Math.max(width / videoWidth, height / videoHeight);
            Matrix matrix = new Matrix();
            matrix.setScale(cover * videoWidth / width, cover * videoHeight / height, width / 2f, height / 2f);
            ((TextureView) media).setTransform(matrix);
        }

        private void startAnimation(File file) {
            if (animation != null) {
                animation.start();
                return;
            }
            // Every slider step asks again while the first decode still runs: a second one would decode the frames twice.
            if (decodingFor == generation) return;
            int expected = generation;
            decodingFor = expected;
            Utils.runOnBackgroundThread(() -> {
                try {
                    int shortSide = Math.min(activity.getResources().getDisplayMetrics().widthPixels,
                            activity.getResources().getDisplayMetrics().heightPixels);
                    Drawable drawable = ImageDecoder.decodeDrawable(ImageDecoder.createSource(file), (decoder, info, source) -> {
                        // Not bigger than the screen needs: every frame of an animation costs memory.
                        int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                        float scale = Math.min(1f, shortSide / (float) Math.min(width, height));
                        decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                    });
                    Utils.runOnMainThread(() -> {
                        if (decodingFor == expected) decodingFor = -1;
                        if (expected != generation || animation != null || !(media instanceof ImageView)
                                || !(drawable instanceof AnimatedImageDrawable)) {
                            return;
                        }
                        animation = (AnimatedImageDrawable) drawable;
                        animation.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                        ((ImageView) media).setImageDrawable(animation);
                        if (mayPlay()) animation.start();
                    });
                } catch (Exception | OutOfMemoryError ex) {
                    Logger.printException(() -> "Wallpaper: could not play the animation", ex);
                    broken = true;
                    Utils.runOnMainThread(() -> {
                        if (decodingFor == expected) decodingFor = -1;
                    });
                }
            });
        }

        private void stopPlaying() {
            if (animation != null) {
                animation.stop();
            }
            MediaPlayer old = player;
            player = null;
            if (old != null) {
                try {
                    old.setOnPreparedListener(null);
                    old.setOnErrorListener(null);
                    old.reset();
                    old.release();
                } catch (Exception ignored) {
                    // Already released.
                }
            }
            if (surface != null) {
                surface.release();
                surface = null;
            }
        }
    }
}
