package app.revanced.extension.soundcloud.region;

import java.lang.reflect.Field;
import java.util.List;

import app.revanced.extension.soundcloud.shared.Reflect;

/**
 * Tells the label "Not available in your country" among the labels of a track. SoundCloud draws it as an icon with a
 * text ({@code MetaLabelType.IconWithText}); the text changes with the language, but the icon is always the same
 * lock, so the icon tells it. The names of the fields are shrunk, so the field is found by its type.
 */
final class GeoLabel {
    private static final String ICON_WITH_TEXT = "MetaLabelType$IconWithText";

    private static volatile Field iconField;

    private GeoLabel() {
    }

    /**
     * @param labels The {@code MetaLabelType} values of a label line.
     * @param icon   The drawable id of the lock icon; 0 when the app does not have it.
     */
    static boolean contains(List<?> labels, int icon) {
        if (labels == null || icon == 0) return false;
        for (Object label : labels) {
            if (label == null || !label.getClass().getName().endsWith(ICON_WITH_TEXT)) continue;
            try {
                Field field = iconField;
                if (field == null || field.getDeclaringClass() != label.getClass()) {
                    field = Reflect.field(label.getClass(), f -> f.getType() == int.class && !Reflect.isStatic(f));
                    iconField = field;
                }
                if (field != null && field.getInt(label) == icon) return true;
            } catch (IllegalAccessException ex) {
                return false;
            }
        }
        return false;
    }
}
