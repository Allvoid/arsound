package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TrackSpeedListTest {
    private static final String TRACK = "soundcloud:tracks:1";

    @Test
    public void aTrackWithoutASpeedOfItsOwnHasNone() {
        assertNull(new TrackSpeedList().get(TRACK));
        assertNull(new TrackSpeedList().get(null));
    }

    @Test
    public void theSpeedSetIsTheSpeedGot() {
        TrackSpeedList list = new TrackSpeedList();
        list.put(TRACK, VersionSpeed.SLOWED);
        assertEquals(VersionSpeed.SLOWED, list.get(TRACK));
        list.put(TRACK, VersionSpeed.SPEED_UP);
        assertEquals(VersionSpeed.SPEED_UP, list.get(TRACK));
        assertEquals(1, list.size());
    }

    @Test
    public void normalIsAChoiceOfItsOwnNotTheLackOfOne() {
        TrackSpeedList list = new TrackSpeedList();
        list.put(TRACK, VersionSpeed.NORMAL);
        assertEquals(VersionSpeed.NORMAL, list.get(TRACK));
    }

    @Test
    public void nullTakesTheSpeedAway() {
        TrackSpeedList list = new TrackSpeedList();
        list.put(TRACK, VersionSpeed.SLOWED);
        list.put(TRACK, null);
        assertNull(list.get(TRACK));
        assertEquals(0, list.size());
    }

    @Test
    public void theStoredTextReadsBackTheSame() {
        TrackSpeedList list = new TrackSpeedList();
        list.put(TRACK, VersionSpeed.SLOWED);
        list.put("file:/storage/emulated/0/Music/a \"b\".mp3", VersionSpeed.SPEED_UP);
        list.put("soundcloud:tracks:3", VersionSpeed.NORMAL);
        TrackSpeedList read = TrackSpeedList.parse(list.format());
        assertEquals(3, read.size());
        assertEquals(VersionSpeed.SLOWED, read.get(TRACK));
        assertEquals(VersionSpeed.SPEED_UP, read.get("file:/storage/emulated/0/Music/a \"b\".mp3"));
        assertEquals(VersionSpeed.NORMAL, read.get("soundcloud:tracks:3"));
        assertEquals(list.format(), read.format());
    }

    @Test
    public void oneMoreThanTheLimitPushesOutTheOneSetLongestAgo() {
        TrackSpeedList list = new TrackSpeedList();
        for (int i = 0; i < TrackSpeedList.MAX_TRACKS; i++) list.put("soundcloud:tracks:" + i, VersionSpeed.SLOWED);
        // Set again: it is the newest now, so the next one out is track 1.
        list.put("soundcloud:tracks:0", VersionSpeed.SPEED_UP);
        list.put("soundcloud:tracks:new", VersionSpeed.SLOWED);
        assertEquals(TrackSpeedList.MAX_TRACKS, list.size());
        assertEquals(VersionSpeed.SPEED_UP, list.get("soundcloud:tracks:0"));
        assertNull(list.get("soundcloud:tracks:1"));
        assertEquals(VersionSpeed.SLOWED, list.get("soundcloud:tracks:new"));
    }

    @Test
    public void aTextThatCannotBeReadIsAnEmptyList() {
        assertEquals(0, TrackSpeedList.parse(null).size());
        assertEquals(0, TrackSpeedList.parse("").size());
        assertEquals(0, TrackSpeedList.parse("not json").size());
        assertEquals(0, TrackSpeedList.parse("{\"a\":1}").size());
    }

    @Test
    public void entriesThatMakeNoSenseAreLeftOut() {
        TrackSpeedList list = TrackSpeedList.parse(
                "[[\"soundcloud:tracks:1\",\"slowed\"],[\"soundcloud:tracks:2\",\"warp\"],[\"\",\"slowed\"],[\"x\"],5]");
        assertEquals(1, list.size());
        assertEquals(VersionSpeed.SLOWED, list.get("soundcloud:tracks:1"));
    }
}
