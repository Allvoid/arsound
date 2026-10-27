package app.revanced.extension.soundcloud.lyrics;

import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;

import app.revanced.extension.shared.Logger;

/**
 * Where the patched player calls into the lyrics: when a player page is filled with a track, and when the player
 * slides open or shut. Both run on the main thread, often (every state of the track, every frame of a slide), and a
 * throw here would take the player down with it, so they do little and catch everything.
 */
@SuppressWarnings("unused")
public final class LyricsHooks {
    private static final String TRACK_ITEM = "com.soundcloud.android.foundation.domain.tracks.TrackItem";
    private static final String URN = "com.soundcloud.android.foundation.domain.Urn";

    /** What the state of a page is made of, found once: the fields are named by the obfuscator, the types are not. */
    private static Class<?> stateClass;
    /** A state class with no track in it (an ad), so it is not searched again on every bind. */
    private static Class<?> trackless;
    private static Field trackField;
    private static Field currentField;
    private static Method getTitle;
    private static Method getCreator;
    private static Method getDuration;
    private static Method getUrn;
    private static Method getContent;

    private LyricsHooks() {
    }

    /**
     * Injection point. The start of the player's method that fills a page with a track.
     *
     * @param view The page, or the view the player wraps it in.
     * @param item The page's state ({@code PlayerTrackState} for a track, something else for an ad).
     */
    public static void onBind(Object view, Object item) {
        try {
            if (!(view instanceof View)) return;
            View page = LyricsLayer.pageOf((View) view);
            if (page == null) return;
            // Switched off in the settings: pages that have no layer yet get none, and no button.
            LyricsLayer layer = LyricsManager.isEnabled() ? LyricsLayer.of(page) : LyricsLayer.existing(page);
            if (layer == null) {
                LyricsLayer.hideButton(page);
                return;
            }
            if (!read(item, layer)) layer.bind(null, null, null, 0, false);
        } catch (Throwable ex) {
            Logger.printException(() -> "Lyrics: could not bind a player page", ex);
        }
    }

    /**
     * Injection point. The start of the player's method that sets how far a page is open.
     *
     * @param fraction 0 for the mini player, 1 for the full screen.
     */
    public static void onSlide(View view, float fraction) {
        try {
            View page = LyricsLayer.pageOf(view);
            if (page == null) return;
            // Switched off: no page gets a layer from a slide either, but one that has it keeps being told.
            LyricsLayer layer = LyricsManager.isEnabled() ? LyricsLayer.of(page) : LyricsLayer.existing(page);
            if (layer != null) layer.onSlide(fraction);
        } catch (Throwable ex) {
            Logger.printException(() -> "Lyrics: could not follow the player sliding", ex);
        }
    }

    /** Hands the track of the page's state to the layer. @return False if the state holds no track. */
    private static boolean read(Object item, LyricsLayer layer) throws Exception {
        if (item == null) return false;
        Class<?> type = item.getClass();
        if (type == trackless) return false;
        if (type != stateClass && !resolve(type)) {
            trackless = type;
            return false;
        }
        Object track = trackField.get(item);
        if (track == null) return false;
        if (getTitle == null) {
            Class<?> trackType = Class.forName(TRACK_ITEM);
            getTitle = trackType.getMethod("getTitle");
            getCreator = trackType.getMethod("getCreatorName");
            getDuration = trackType.getMethod("getFullDuration");
            getUrn = trackType.getMethod("getUrn");
            // On the abstract class, which is public: the classes of the URNs themselves may not be.
            getContent = Class.forName(URN).getMethod("getContent");
        }
        Object urn = getUrn.invoke(track);
        if (urn == null) return false;
        String content = (String) getContent.invoke(urn);
        if (content == null) return false;
        boolean current = currentField != null && currentField.getBoolean(item);
        layer.bind(content, (String) getTitle.invoke(track), (String) getCreator.invoke(track),
                (long) getDuration.invoke(track), current);
        return true;
    }

    /** Finds the track and the "is the playing one" fields of a state class. False for a state without a track. */
    private static boolean resolve(Class<?> type) throws Exception {
        Class<?> trackType = Class.forName(TRACK_ITEM);
        Field found = null;
        Field[] fields = type.getDeclaredFields();
        // The dex orders fields by name; the booleans of the state are "is current" and then "is in front".
        Arrays.sort(fields, new Comparator<Field>() {
            @Override
            public int compare(Field a, Field b) {
                return a.getName().compareTo(b.getName());
            }
        });
        Field flag = null;
        for (Field field : fields) {
            if (found == null && trackType.isAssignableFrom(field.getType())) found = field;
            if (flag == null && field.getType() == boolean.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                flag = field;
            }
        }
        if (found == null) return false;
        found.setAccessible(true);
        if (flag != null) flag.setAccessible(true);
        trackField = found;
        currentField = flag;
        stateClass = type;
        return true;
    }
}
