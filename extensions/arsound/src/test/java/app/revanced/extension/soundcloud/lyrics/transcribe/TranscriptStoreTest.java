package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class TranscriptStoreTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    private TranscriptStore store;

    @Before
    public void openStore() throws IOException {
        store = new TranscriptStore(new File(temporary.newFolder(), "transcripts"));
    }

    private static Transcript make(String urn, String model, Transcript.State state, long duration, String... lines) {
        long[] times = new long[lines.length];
        for (int i = 0; i < times.length; i++) times[i] = 1000L * i;
        return new Transcript(urn, model, state, "ru", duration, 123456789L, 2, 7, 54_000, lines, times);
    }

    @Test
    public void aTranscriptComesBackAsItWasWritten() throws IOException {
        store.write(make("123", "small", Transcript.State.DONE, 200_000, "первая строка", "second line"));
        Transcript back = store.read("123", "small");
        assertNotNull(back);
        assertEquals(Transcript.State.DONE, back.state);
        assertEquals("ru", back.language);
        assertEquals(200_000, back.durationMs);
        assertEquals(2, back.windowsDone);
        assertEquals(7, back.windowsTotal);
        assertEquals(54_000, back.resumeMs);
        assertArrayEquals(new String[]{"первая строка", "second line"}, back.lines);
        assertArrayEquals(new long[]{0, 1000}, back.times);
    }

    @Test
    public void aTabOrLineBreakInTheWordsCannotBreakTheFile() throws IOException {
        store.write(make("5", "base", Transcript.State.DONE, 100_000, "one\ttwo\nthree"));
        assertArrayEquals(new String[]{"one two three"}, store.read("5", "base").lines);
    }

    @Test
    public void aDamagedFileIsNoTranscript() throws IOException {
        store.write(make("9", "small", Transcript.State.DONE, 100_000, "x"));
        File file = store.file("9", "small");
        Files.write(file.toPath(), "garbage".getBytes(StandardCharsets.UTF_8));
        assertNull(store.read("9", "small"));
        Files.write(file.toPath(), "ARSOUND-TRANSCRIPT 1\nstate=done\nmodel=small\n".getBytes(StandardCharsets.UTF_8));
        assertNull("cut before the separator", store.read("9", "small"));
    }

    @Test
    public void aWorseModelsTextIsNotShownForTheAccurateOne() throws IOException {
        store.write(make("7", "base", Transcript.State.DONE, 100_000, "from base"));
        // With the accurate model chosen, the song is offered again instead of showing the fast model's guesses.
        assertNull(store.best("7", "small", 100_000));
        store.write(make("7", "small", Transcript.State.DONE, 100_000, "from small"));
        assertEquals("from small", store.best("7", "small", 100_000).lines[0]);
        assertEquals("from base", store.best("7", "base", 100_000).lines[0]);
    }

    @Test
    public void aBetterModelsTextIsShownForTheFastOne() throws IOException {
        store.write(make("8", "small", Transcript.State.DONE, 100_000, "from small"));
        assertEquals("from small", store.best("8", "base", 100_000).lines[0]);
    }

    @Test
    public void partialAndFailedTranscriptsAreNotShown() throws IOException {
        store.write(make("1", "small", Transcript.State.PARTIAL, 100_000, "half"));
        assertNull(store.best("1", "small", 100_000));
        store.write(make("2", "small", Transcript.State.FAILED, 100_000));
        assertNull(store.best("2", "small", 100_000));
        assertEquals(Transcript.State.FAILED, store.read("2", "small").state);
    }

    @Test
    public void aTrackOfAnotherLengthDoesNotTakeTheTranscript() throws IOException {
        store.write(make("3", "small", Transcript.State.DONE, 200_000, "x"));
        assertNotNull(store.best("3", "small", 201_500));
        assertNull(store.best("3", "small", 230_000));
    }

    @Test
    public void countsAndClearingLookAtFinishedOnes() throws IOException {
        store.write(make("1", "small", Transcript.State.DONE, 100_000, "a"));
        store.write(make("2", "small", Transcript.State.PARTIAL, 100_000, "b"));
        store.write(make("3", "base", Transcript.State.DONE, 100_000, "c"));
        assertEquals(2, store.doneCount());
        assertTrue(store.size() > 0);
        store.clear();
        assertEquals(0, store.doneCount());
        assertEquals(0, store.size());
    }

    @Test
    public void deletingOneLeavesTheOthers() throws IOException {
        store.write(make("1", "small", Transcript.State.DONE, 100_000, "a"));
        store.write(make("1", "base", Transcript.State.DONE, 100_000, "a"));
        store.delete("1", "small");
        assertNull(store.read("1", "small"));
        assertNotNull(store.read("1", "base"));
    }

    @Test
    public void aLongImportedUrnGetsAShortName() {
        StringBuilder urn = new StringBuilder("local_");
        for (int i = 0; i < 400; i++) urn.append("QUJD");
        String name = TranscriptStore.safeName(urn.toString());
        assertTrue(name, name.length() < 60);
        assertEquals(name, TranscriptStore.safeName(urn.toString()));
        assertEquals("123_456", TranscriptStore.safeName("123:456"));
    }
}
