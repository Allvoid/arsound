package app.revanced.extension.soundcloud.transfer;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class YandexMusicOrderTest {
    private static SourceTrack track(String id) {
        return new SourceTrack(id, Collections.singletonList("Artist"), "Title " + id, null, 0);
    }

    private static List<String> ids(List<SourceTrack> tracks) {
        List<String> ids = new ArrayList<>();
        for (SourceTrack track : tracks) ids.add(track.id);
        return ids;
    }

    @Test
    public void tracksAskedForByKeyTakeTheirOwnPlaces() {
        // The playlist: a whole track, two keys (one with its album), a whole track.
        List<Object> slots = Arrays.<Object>asList(track("1"), "2:77", "3", track("4"));
        List<SourceTrack> asked = Arrays.asList(track("2"), track("3"));
        assertEquals(Arrays.asList("1", "2", "3", "4"), ids(YandexMusic.inOrder(slots, asked)));
    }

    @Test
    public void aKeyWithoutATrackIsLeftOut() {
        List<Object> slots = Arrays.<Object>asList("1", track("2"), "3");
        assertEquals(Arrays.asList("2", "3"), ids(YandexMusic.inOrder(slots, Collections.singletonList(track("3")))));
    }

    @Test
    public void onlyKeysKeepTheOrderOfTheKeys() {
        // The service answers in its own order.
        List<Object> slots = Arrays.<Object>asList("5", "6", "7");
        List<SourceTrack> asked = Arrays.asList(track("7"), track("5"), track("6"));
        assertEquals(Arrays.asList("5", "6", "7"), ids(YandexMusic.inOrder(slots, asked)));
    }

    @Test
    public void aTrackListedTwiceStaysTwice() {
        List<Object> slots = Arrays.<Object>asList("5", track("1"), "5");
        assertEquals(Arrays.asList("5", "1", "5"), ids(YandexMusic.inOrder(slots, Collections.singletonList(track("5")))));
    }
}
