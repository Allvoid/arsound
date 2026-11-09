package app.revanced.extension.soundcloud.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** The shared part of a transfer, run with a made-up source and a made-up SoundCloud. */
public class TrackSearchTest {
    /** A source that gives a fixed collection: what any of the real ones gives the job. */
    private static final class FakeSource extends TransferSource {
        private final SourceCollection collection;

        FakeSource(SourceCollection collection) {
            this.collection = collection;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public String name() {
            return "Fake";
        }

        @Override
        public String fromName() {
            return "from Fake";
        }

        @Override
        public TransferLink find(String text) {
            return text.startsWith("fake:") ? new SourceLink(this, "fake", "PLAYLIST", null, text.substring(5), null) : null;
        }

        @Override
        TransferLink fromKey(String key) {
            return SourceLink.fromKey(this, "fake", key);
        }

        @Override
        SourceCollection load(TransferLink link) {
            return collection;
        }

        @Override
        String notAvailable(TransferLink link) {
            return "";
        }

        @Override
        public boolean isSignedIn() {
            return false;
        }

        @Override
        public String accountName() {
            return "";
        }

        @Override
        public void signIn(Context context) {
        }

        @Override
        public void signOut() {
        }

        @Override
        public String signInTitle() {
            return "";
        }

        @Override
        public String signInNote() {
            return "";
        }

        @Override
        public String signOutTitle() {
            return "";
        }

        @Override
        public List<SourceList> library() {
            return null;
        }
    }

    private static SourceTrack track(String id, String artist, String title) {
        return new SourceTrack(id, Collections.singletonList(artist), title, null, 200_000);
    }

    /** Remembers the progress and the saves. */
    private static final class Recorder implements TrackSearch.Progress {
        int checked;
        int found;
        int saves;

        @Override
        public void onTrack(int checked, int found) {
            this.checked = checked;
            this.found = found;
        }

        @Override
        public void save(JSONObject matches) {
            saves++;
        }
    }

    private static List<SourceTrack> read(FakeSource source) throws Exception {
        TransferLink link = source.find("fake:list");
        assertEquals("fake:PLAYLIST::list:", link.key());
        return source.load(link).tracks;
    }

    @Test
    public void foundTracksKeepTheOrderOfTheCollectionEachOnce() throws Exception {
        FakeSource source = new FakeSource(new SourceCollection("List", Arrays.asList(
                track("yt:1", "A", "One"), track("yt:2", "B", "Two"), track("yt:3", "C", "Three"),
                track("yt:4", "A", "One again"))));
        Map<String, String> soundCloud = new ConcurrentHashMap<>();
        soundCloud.put("One", "soundcloud:tracks:11");
        soundCloud.put("Three", "soundcloud:tracks:33");
        soundCloud.put("One again", "soundcloud:tracks:11");
        JSONObject matches = new JSONObject();
        List<String> missing = new ArrayList<>();
        Recorder progress = new Recorder();

        List<String> urns = new TrackSearch(3, " (net)").run(read(source), matches,
                track -> soundCloud.getOrDefault(track.title, ""), missing, progress);

        assertEquals(Arrays.asList("soundcloud:tracks:11", "soundcloud:tracks:33"), urns);
        assertEquals(Collections.singletonList("B — Two"), missing);
        assertEquals(4, progress.checked);
        assertEquals(2, progress.found);
        assertTrue(progress.saves >= 1);
        // Found and not found are both kept; a miss as an empty urn.
        assertEquals("", matches.getJSONObject("yt:2").getString("urn"));
        assertEquals("soundcloud:tracks:33", matches.getJSONObject("yt:3").getString("urn"));
    }

    @Test
    public void keptMatchesAreNotSearchedAgain() throws Exception {
        FakeSource source = new FakeSource(new SourceCollection("List", Arrays.asList(
                track("vk:1_1", "A", "One"), track("vk:1_2", "B", "Two"))));
        JSONObject matches = new JSONObject()
                .put("vk:1_1", new JSONObject().put("urn", "soundcloud:tracks:7").put("time", 1))
                .put("vk:1_2", new JSONObject().put("urn", "").put("time", 1));
        AtomicInteger searches = new AtomicInteger();

        List<String> missing = new ArrayList<>();
        List<String> urns = new TrackSearch(2, " (net)").run(read(source), matches, track -> {
            searches.incrementAndGet();
            return "soundcloud:tracks:999";
        }, missing, new Recorder());

        assertEquals(0, searches.get());
        assertEquals(Collections.singletonList("soundcloud:tracks:7"), urns);
        assertEquals(Collections.singletonList("B — Two"), missing);
    }

    @Test
    public void aFailedSearchIsNotRememberedAsMissing() throws Exception {
        FakeSource source = new FakeSource(new SourceCollection("List", Arrays.asList(
                track("yt:1", "A", "One"), track("yt:2", "B", "Two"))));
        JSONObject matches = new JSONObject();
        List<String> missing = new ArrayList<>();
        List<String> urns = new TrackSearch(1, " (net)").run(read(source), matches, track -> {
            if (track.title.equals("One")) throw new IOException("timeout");
            return "soundcloud:tracks:2";
        }, missing, new Recorder());

        assertEquals(Collections.singletonList("soundcloud:tracks:2"), urns);
        assertEquals(Collections.singletonList("A — One (net)"), missing);
        assertFalse(matches.has("yt:1"));
    }

    @Test
    public void manyFailuresInARowStopTheSearch() throws Exception {
        List<SourceTrack> tracks = new ArrayList<>();
        for (int i = 0; i < TrackSearch.UNREACHABLE_FAILURES + 2; i++) tracks.add(track("yt:" + i, "A", "Song " + i));
        FakeSource source = new FakeSource(new SourceCollection("List", tracks));
        Recorder progress = new Recorder();
        try {
            new TrackSearch(2, " (net)").run(read(source), new JSONObject(), track -> {
                throw new IOException("offline");
            }, new ArrayList<>(), progress);
            fail("The search should stop");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("unreachable"));
        }
        // What was found is still written when the search stops.
        assertEquals(1, progress.saves);
    }
}
