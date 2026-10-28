package app.revanced.extension.soundcloud.lyrics;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;

import app.revanced.extension.soundcloud.shared.Reflect;

/**
 * Reads the track out of the state of a player page and hands it to a {@link LyricsLayer}.
 * <p>
 * What the state is made of is found once and kept: the fields are named by the obfuscator, the types are not.
 * Runs on the main thread only, from the bind hook.
 */
final class PlayerStateReader {
    private static final String TRACK_ITEM = "com.soundcloud.android.foundation.domain.tracks.TrackItem";
    private static final String URN = "com.soundcloud.android.foundation.domain.Urn";

    private Class<?> stateClass;
    /** A state class with no track in it (an ad), so it is not searched again on every bind. */
    private Class<?> trackless;
    private Field trackField;
    private Field currentField;
    private Method getTitle;
    private Method getCreator;
    private Method getDuration;
    private Method getUrn;
    private Method getContent;

    /** Hands the track of the page's state to the layer. @return False if the state holds no track. */
    boolean read(Object item, LyricsLayer layer) throws Exception {
        if (item == null) return false;
        Class<?> type = item.getClass();
        if (type == trackless) return false;
        if (type != stateClass && !resolve(type)) {
            trackless = type;
            return false;
        }
        Object track = trackField.get(item);
        if (track == null) return false;
        resolveTrackAccess();
        Object urn = getUrn.invoke(track);
        if (urn == null) return false;
        String content = (String) getContent.invoke(urn);
        if (content == null) return false;
        boolean current = currentField != null && currentField.getBoolean(item);
        layer.bind(content, (String) getTitle.invoke(track), (String) getCreator.invoke(track),
                (long) getDuration.invoke(track), current);
        return true;
    }

    /** The getters of a track and of its URN, found on the first track. */
    private void resolveTrackAccess() throws Exception {
        if (getTitle != null) return;
        Class<?> trackType = Class.forName(TRACK_ITEM);
        getTitle = trackType.getMethod("getTitle");
        getCreator = trackType.getMethod("getCreatorName");
        getDuration = trackType.getMethod("getFullDuration");
        getUrn = trackType.getMethod("getUrn");
        // On the abstract class, which is public: the classes of the URNs themselves may not be.
        getContent = Class.forName(URN).getMethod("getContent");
    }

    /** Finds the track and the "is the playing one" fields of a state class. False for a state without a track. */
    private boolean resolve(Class<?> type) throws Exception {
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
            if (flag == null && field.getType() == boolean.class && !Reflect.isStatic(field)) flag = field;
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
