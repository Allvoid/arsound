package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class PlaylistOverflowTest {
    private static final int LIMIT = 500;

    /** Urn texts {@code soundcloud:tracks:<from>} to {@code <to>}, both ends included. */
    private static List<String> tracks(int from, int to) {
        List<String> list = new ArrayList<>();
        for (int id = from; id <= to; id++) list.add("soundcloud:tracks:" + id);
        return list;
    }

    private static List<String> track(int id) {
        return tracks(id, id);
    }

    @SafeVarargs
    private static Set<String> saved(List<String>... parts) {
        Set<String> set = new LinkedHashSet<>();
        for (List<String> part : parts) set.addAll(part);
        return set;
    }

    @Test
    public void aShortPlaylistMovesNothing() {
        assertTrue(PlaylistOverflow.beyondLimit(saved(tracks(1, 10), track(11)), tracks(1, 10), LIMIT).isEmpty());
        assertTrue(PlaylistOverflow.beyondLimit(saved(tracks(1, 10)), null, LIMIT).isEmpty());
    }

    @Test
    public void aPlaylistExactlyAtTheLimitMovesNothing() {
        assertTrue(PlaylistOverflow.beyondLimit(saved(tracks(1, 499), track(500)), tracks(1, 499), LIMIT).isEmpty());
    }

    @Test
    public void removingATrackFromALongerServerPlaylistMovesNothing() {
        // The server holds 520; the edit saves 519 of them. All 519 go to the server as they are.
        List<String> before = tracks(1, 520);
        Set<String> after = saved(tracks(1, 99), tracks(101, 520));
        assertEquals(519, after.size());
        assertTrue(PlaylistOverflow.beyondLimit(after, before, LIMIT).isEmpty());
    }

    @Test
    public void aNewOrderOfALongerServerPlaylistMovesNothing() {
        List<String> before = tracks(1, 520);
        List<String> reversed = new ArrayList<>(before);
        Collections.reverse(reversed);
        assertTrue(PlaylistOverflow.beyondLimit(saved(reversed), before, LIMIT).isEmpty());
    }

    @Test
    public void aTrackAddedToALongerServerPlaylistStaysOnThePhone() {
        // The server holds 520 already, so the new one does not fit and the server list is saved as it was.
        assertEquals(track(900), PlaylistOverflow.beyondLimit(saved(tracks(1, 520), track(900)), tracks(1, 520), LIMIT));
    }

    @Test
    public void aTrackAddedToAFullPlaylistStaysOnThePhone() {
        assertEquals(track(900), PlaylistOverflow.beyondLimit(saved(tracks(1, 500), track(900)), tracks(1, 500), LIMIT));
    }

    @Test
    public void onlyTheTracksPastTheLimitMove() {
        // 499 on the server, 3 added: the first new one fits, the other two do not.
        List<String> added = tracks(901, 903);
        assertEquals(tracks(902, 903), PlaylistOverflow.beyondLimit(saved(tracks(1, 499), added), tracks(1, 499), LIMIT));
    }

    @Test
    public void newTracksAtTheFrontAreTheOnesMovedNotTheOldTail() {
        // SoundCloud's "Add music" puts the picked tracks before the playlist's own. 495 + 10 new: 5 new do not fit.
        List<String> added = tracks(901, 910);
        List<String> before = tracks(1, 495);
        assertEquals(tracks(906, 910), PlaylistOverflow.beyondLimit(saved(added, before), before, LIMIT));
    }

    @Test
    public void aPickedTrackTheServerAlreadyHasIsNotNew() {
        // Picked again: track 1 comes first in the saved list but the server has it, so only track 900 is new.
        List<String> before = tracks(1, 500);
        assertEquals(track(900), PlaylistOverflow.beyondLimit(saved(track(1), track(900), before), before, LIMIT));
    }

    @Test
    public void removingAndAddingInOneEditCountsWhatRemains() {
        // 520 on the server, 30 taken out, 20 added: 490 stay, so 10 of the new ones fit and 10 do not.
        List<String> added = tracks(901, 920);
        Set<String> after = saved(tracks(1, 490), added);
        assertEquals(tracks(911, 920), PlaylistOverflow.beyondLimit(after, tracks(1, 520), LIMIT));
    }

    @Test
    public void aNewPlaylistTakesTheFirstFiveHundred() {
        List<String> all = tracks(1, 503);
        assertEquals(tracks(501, 503), PlaylistOverflow.beyondLimit(saved(all), Collections.<String>emptyList(), LIMIT));
    }

    @Test
    public void withoutTheServerListTheTracksPastTheLimitStayOnThePhone() {
        assertEquals(tracks(501, 503), PlaylistOverflow.beyondLimit(saved(tracks(1, 503)), null, LIMIT));
    }

    @Test
    public void tracksAreToldApartByTheirTextNotByTheirClass() {
        // The saved list holds urn objects of another class than the server list's; the same text is the same track.
        List<Object> savedObjects = new ArrayList<>();
        for (String text : tracks(1, 500)) savedObjects.add(new StringBuilder(text));
        savedObjects.add(new StringBuilder("soundcloud:tracks:900"));
        List<Object> overflow = PlaylistOverflow.beyondLimit(savedObjects, tracks(1, 500), LIMIT);
        assertEquals(1, overflow.size());
        assertEquals("soundcloud:tracks:900", overflow.get(0).toString());
    }

    @Test
    public void theAnswerKeepsTheOrderOfTheSavedList() {
        List<String> added = Arrays.asList("soundcloud:tracks:903", "soundcloud:tracks:901", "soundcloud:tracks:902");
        List<String> before = tracks(1, 500);
        assertEquals(added, PlaylistOverflow.beyondLimit(saved(added, before), before, LIMIT));
    }

    @Test
    public void theInputsAreNotChanged() {
        List<String> before = tracks(1, 500);
        Set<String> after = saved(before, track(900));
        PlaylistOverflow.beyondLimit(after, before, LIMIT);
        assertEquals(501, after.size());
        assertEquals(tracks(1, 500), before);
    }
}
