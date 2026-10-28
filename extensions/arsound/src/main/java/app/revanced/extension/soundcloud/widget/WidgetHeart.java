package app.revanced.extension.soundcloud.widget;

import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.RemoteViews;

import java.lang.reflect.Field;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Reflect;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The heart of SoundCloud's player widget on the home screen, in the colour of the playing track's cover.
 * <p>
 * Every update of the widget comes from one method that builds its {@code RemoteViews}. The launcher draws them, and
 * it never sees the theme the app puts on its own resources, so the colour has to be part of the {@code RemoteViews}:
 * a colour filter on the heart, sent with every update. The launcher keeps the last filter it was given, so an
 * update that wants SoundCloud's own heart sends a transparent one. The outlined heart (not liked) and the filled one
 * (liked) both take the colour, their shapes still tell them apart.
 * <p>
 * A grey or black-and-white cover has no colour to take, and neither has a track without a cover: the heart then
 * takes the accent of the chosen Arsound theme, and with SoundCloud's own look it stays SoundCloud's own heart.
 * <p>
 * The card behind the heart is light in the phone's day look and dark in its night look, so the colour is kept to
 * the luminance that stands out from the card of the look the phone has now. The colour comes from the cover
 * ({@link CoverColor}) and is fitted to the card ({@link HeartColor}); the widget and the look of the phone are
 * {@link WidgetLook}.
 */
@SuppressWarnings("unused")
public final class WidgetHeart {
    /** The heart takes the cover's colour. On by default. */
    public static final String COVER_COLOR_KEY = "widget_heart_cover";

    /** The id of the heart in the widget's layout. */
    private static final String HEART_ID = "btn_like";

    private WidgetHeart() {
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(COVER_COLOR_KEY, true);
    }

    /**
     * Has the widget on the home screen built again, so a change of the setting shows at once and not with the next
     * track. It is the update the system sends when the widget is added; with no widget on the screen nothing happens.
     */
    public static void refresh(Context context) {
        WidgetLook.refresh(context);
    }

    /**
     * Has the widget built again when the phone flips between its day and night look. Called once, when the app
     * starts.
     */
    public static void watchLook(Application application) {
        WidgetLook.watch(application);
    }

    /** The colour chosen at the start of the update running on this thread, kept for its end. */
    private static final ThreadLocal<Integer> chosen = new ThreadLocal<>();

    /**
     * Injection point, at the start of the method that builds the widget. The colour is chosen here, while the
     * builder still holds the cover.
     *
     * @param builder SoundCloud's {@code PlayerWidgetRemoteViewsBuilder}: the track, and the cover it loaded.
     * @param context The app, whose theme is the fallback.
     */
    public static void begin(Object builder, Context context) {
        chosen.remove();
        try {
            chosen.set(heartColor(builder, context));
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget heart: could not choose the colour", ex);
        }
    }

    /**
     * Injection point, before the method that builds the widget returns it.
     *
     * @param views The finished widget.
     */
    public static void tint(RemoteViews views) {
        Integer color = chosen.get();
        chosen.remove();
        try {
            int heart = heartId();
            if (heart == 0 || views == null) return;
            // Sent every time, transparent when the heart should look as SoundCloud draws it.
            views.setInt(heart, "setColorFilter", color == null ? Color.TRANSPARENT : color);
        } catch (Throwable ex) {
            Logger.printException(() -> "Widget heart: could not colour the heart", ex);
        }
    }

    private static volatile int heartId;

    private static int heartId() {
        int known = heartId;
        if (known == 0) {
            known = Utils.getResourceIdentifier(ResourceType.ID, HEART_ID);
            heartId = known;
        }
        return known;
    }

    /** @return The colour of the heart, or transparent for SoundCloud's own. */
    private static int heartColor(Object builder, Context context) throws Exception {
        if (!isEnabled()) return Color.TRANSPARENT;
        boolean night = WidgetLook.isNight();
        float[] hsv = CoverColor.hsv(coverOf(builder));
        int color = hsv == null ? 0 : HeartColor.fit(hsv[0], hsv[1], hsv[2], night);
        String source = "cover";
        if (color == 0) {
            color = themeColor(context, night);
            source = "theme";
        }
        int result = color;
        String from = source;
        Logger.printDebug(() -> "Widget heart: " + (result == 0
                ? "SoundCloud's own"
                : String.format("#%06X from the ", result & 0xFFFFFF) + from));
        return result;
    }

    private static volatile Field coverField;

    /** The cover of the builder: the only bitmap it holds. */
    private static Bitmap coverOf(Object builder) throws Exception {
        Field field = coverField;
        if (field == null) {
            field = Reflect.field(builder.getClass(), Reflect.typeIs(Bitmap.class));
            if (field == null) return null;
            coverField = field;
        }
        return (Bitmap) field.get(builder);
    }

    /** The accent of the chosen theme in the colours the heart is kept to, or 0 for SoundCloud's own look. */
    private static int themeColor(Context context, boolean night) {
        if (context == null || ArsoundTheme.SOUNDCLOUD.equals(ArsoundTheme.current())) return 0;
        float[] hsv = new float[3];
        Color.colorToHSV(ArsoundTheme.palette(context, "special"), hsv);
        return HeartColor.fit(hsv[0], hsv[1], hsv[2], night);
    }
}
