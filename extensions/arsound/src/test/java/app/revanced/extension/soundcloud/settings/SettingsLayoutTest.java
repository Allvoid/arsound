package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.junit.Test;

public class SettingsLayoutTest {
    @Test
    public void everySectionStandsInExactlyOneGroup() {
        Set<String> seen = new HashSet<>();
        for (SettingsLayout.Group group : SettingsLayout.groups()) {
            for (String id : group.sectionIds) assertTrue("«" + id + "» is in two groups", seen.add(id));
        }
        assertEquals(14, seen.size());
    }

    @Test
    public void theLastGroupIsTheQuietOneWithoutAHeading() {
        List<SettingsLayout.Group> groups = SettingsLayout.groups();
        assertNull(groups.get(groups.size() - 1).heading());
        assertEquals(DeveloperScreen.ID, groups.get(groups.size() - 1).sectionIds.get(0));
        for (int i = 0; i < groups.size() - 1; i++) assertTrue(groups.get(i).heading() != null);
    }

    @Test
    public void whatPeopleChangeMostComesFirst() {
        SettingsLayout.Group first = SettingsLayout.groups().get(0);
        assertArrayEquals(new String[]{AppearanceScreen.ID, BottomBarScreen.ID, PlaybackScreen.ID, EqualizerScreen.ID}, first.sectionIds.toArray());
    }

    @Test
    public void headingsFollowTheLanguage() {
        Locale old = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("ru"));
            assertEquals("Главное", SettingsLayout.groups().get(0).heading());
            Locale.setDefault(Locale.ENGLISH);
            assertEquals("Main", SettingsLayout.groups().get(0).heading());
        } finally {
            Locale.setDefault(old);
        }
    }
}
