package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TrackFxListTest {
    private static final String TRACK = "soundcloud:tracks:7";

    @Test
    public void aTrackWithoutAnythingOfItsOwnFollowsTheOthers() {
        assertSame(TrackFxList.Own.NONE, new TrackFxList().get(TRACK));
        assertSame(TrackFxList.Own.NONE, new TrackFxList().get(null));
    }

    @Test
    public void reverbAndBassAreKeptApart() {
        TrackFxList list = new TrackFxList();
        list.put(TRACK, VersionStrength.HIGH, null);
        assertEquals(VersionStrength.HIGH, list.get(TRACK).reverb);
        assertNull(list.get(TRACK).bass);
        list.put(TRACK, VersionStrength.HIGH, VersionStrength.LOW);
        assertEquals(VersionStrength.LOW, list.get(TRACK).bass);
        assertEquals(1, list.size());
    }

    @Test
    public void offIsAChoiceOfItsOwnNotTheLackOfOne() {
        TrackFxList list = new TrackFxList();
        list.put(TRACK, VersionStrength.OFF, null);
        assertEquals(VersionStrength.OFF, list.get(TRACK).reverb);
        assertEquals(1, list.size());
    }

    @Test
    public void nothingForBothTakesTheTrackAway() {
        TrackFxList list = new TrackFxList();
        list.put(TRACK, VersionStrength.MID, VersionStrength.MID);
        list.put(TRACK, null, null);
        assertTrue(list.get(TRACK).isEmpty());
        assertEquals(0, list.size());
    }

    @Test
    public void theTextKeepsTheListAndItsOrder() {
        TrackFxList list = new TrackFxList();
        list.put("soundcloud:tracks:1", VersionStrength.LOW, null);
        list.put("soundcloud:tracks:2", null, VersionStrength.OFF);
        list.put("soundcloud:tracks:3", VersionStrength.HIGH, VersionStrength.MID);
        TrackFxList again = TrackFxList.parse(list.format());
        assertEquals(list.format(), again.format());
        assertEquals(VersionStrength.LOW, again.get("soundcloud:tracks:1").reverb);
        assertNull(again.get("soundcloud:tracks:1").bass);
        assertNull(again.get("soundcloud:tracks:2").reverb);
        assertEquals(VersionStrength.OFF, again.get("soundcloud:tracks:2").bass);
        assertEquals(VersionStrength.MID, again.get("soundcloud:tracks:3").bass);
    }

    @Test
    public void aTextThatCannotBeReadIsAnEmptyList() {
        assertEquals(0, TrackFxList.parse("not json").size());
        assertEquals(0, TrackFxList.parse("").size());
        assertEquals(0, TrackFxList.parse(null).size());
        assertEquals(0, TrackFxList.parse("[[\"a\",\"x\",\"y\"],[\"b\"]]").size());
    }

    @Test
    public void theOldestTracksGoWhenThereAreTooMany() {
        TrackFxList list = new TrackFxList();
        for (int i = 0; i < TrackFxList.MAX_TRACKS + 3; i++) list.put("soundcloud:tracks:" + i, VersionStrength.LOW, null);
        assertEquals(TrackFxList.MAX_TRACKS, list.size());
        assertTrue(list.get("soundcloud:tracks:0").isEmpty());
        assertEquals(VersionStrength.LOW, list.get("soundcloud:tracks:" + (TrackFxList.MAX_TRACKS + 2)).reverb);
    }

    @Test
    public void theTracksOwnStrengthComesBeforeTheCommonOne() {
        assertEquals(VersionStrength.HIGH, TrackFxList.effective(VersionStrength.HIGH, VersionStrength.LOW));
        assertEquals(VersionStrength.OFF, TrackFxList.effective(VersionStrength.OFF, VersionStrength.LOW));
        assertEquals(VersionStrength.LOW, TrackFxList.effective(null, VersionStrength.LOW));
    }
}
