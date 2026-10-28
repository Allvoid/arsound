package app.revanced.extension.soundcloud.region;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class GeoLabelTest {
    private static final int LOCK = 0x7f0802f3;
    private static final int NO_WIFI = 0x7f0802f4;

    /** Shaped like SoundCloud's label types: only the class name and a text and an icon id matter. */
    private static final class MetaLabelType {
        static final class IconWithText {
            final String text;
            final int icon;

            IconWithText(String text, int icon) {
                this.text = text;
                this.icon = icon;
            }
        }

        static final class Likes {
            final long count;
            final int icon = LOCK;

            Likes(long count) {
                this.count = count;
            }
        }
    }

    @Test
    public void findsTheLockWhateverTheLanguage() {
        assertTrue(GeoLabel.contains(Collections.singletonList(new MetaLabelType.IconWithText("Недоступно в вашей стране", LOCK)), LOCK));
        assertTrue(GeoLabel.contains(Collections.singletonList(new MetaLabelType.IconWithText("Not available in your country", LOCK)), LOCK));
    }

    @Test
    public void findsTheLockAmongOtherLabels() {
        List<Object> labels = new ArrayList<>(Arrays.asList(
                new MetaLabelType.Likes(5), null, new MetaLabelType.IconWithText("No Wi-Fi", NO_WIFI),
                new MetaLabelType.IconWithText("Blocked", LOCK)));
        assertTrue(GeoLabel.contains(labels, LOCK));
    }

    @Test
    public void otherIconsAreNotTheLabel() {
        assertFalse(GeoLabel.contains(Collections.singletonList(new MetaLabelType.IconWithText("No Wi-Fi", NO_WIFI)), LOCK));
        // A label of another type is not looked into, even when it holds the same number.
        assertFalse(GeoLabel.contains(Collections.singletonList(new MetaLabelType.Likes(3)), LOCK));
    }

    @Test
    public void nothingToFind() {
        assertFalse(GeoLabel.contains(null, LOCK));
        assertFalse(GeoLabel.contains(Collections.emptyList(), LOCK));
        // The app has no lock icon: no label can be told.
        assertFalse(GeoLabel.contains(Collections.singletonList(new MetaLabelType.IconWithText("x", 0)), 0));
    }
}
