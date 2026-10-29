package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SongListenerTimingTest {
    /** A model that hears the given words in the next window, letters 40 ms apart, as GigaAM tells them. */
    private static final class Scripted implements Recognizer {
        private final List<Recognizer.Heard> answers = new ArrayList<>();

        /** Words and pauses: a String is a word, a Long the pause in ms before the next word. */
        Scripted then(long startMs, Object... wordsAndPauses) {
            List<String> tokens = new ArrayList<>();
            List<Float> seconds = new ArrayList<>();
            long at = startMs;
            for (int i = 0; i < wordsAndPauses.length; i++) {
                String word = (String) wordsAndPauses[i];
                if (!tokens.isEmpty()) {
                    tokens.add(" ");
                    seconds.add(at / 1000f);
                }
                for (char letter : word.toCharArray()) {
                    tokens.add(String.valueOf(letter));
                    seconds.add(at / 1000f);
                    at += 40;
                }
                long pause = i + 1 < wordsAndPauses.length && wordsAndPauses[i + 1] instanceof Long
                        ? (Long) wordsAndPauses[++i] : 120;
                at += pause;
            }
            float[] moments = new float[seconds.size()];
            for (int i = 0; i < moments.length; i++) moments[i] = seconds.get(i);
            StringBuilder text = new StringBuilder();
            for (Object part : wordsAndPauses) {
                if (part instanceof String) text.append(text.length() > 0 ? " " : "").append(part);
            }
            answers.add(new Recognizer.Heard(text.toString(), "ru",
                    HeardWord.fromTokens(tokens.toArray(new String[0]), moments)));
            return this;
        }

        @Override
        public Heard hear(float[] samples) {
            return answers.remove(0);
        }

        @Override
        public void fixLanguage(String language) {
        }

        @Override
        public void close() {
        }
    }

    private static float[] loud(int seconds) {
        float[] pcm = new float[seconds * Chunker.RATE];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (float) (0.3 * Math.sin(i * 0.05));
        return pcm;
    }

    @Test
    public void linesStartWithTheirFirstWord() throws Exception {
        List<String> lines = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        Scripted model = new Scripted().then(2_000, "вся", "моя", "жизнь", "говно", 760L, "но", "мне", "хорошо");
        new SongListener(model, "ru", false, false, "ru", true, lines, times).listen(loud(20), 0, 20 * Chunker.RATE);
        assertEquals(Arrays.asList("вся моя жизнь говно", "но мне хорошо"), lines);
        assertEquals(2_000 - SongListener.LEAD_MS, (long) times.get(0));
    }

    @Test
    public void aPhraseCutByTheWindowBorderIsJoined() throws Exception {
        List<String> lines = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        float[] pcm = loud(40);
        // The first window ends 90 ms after "купаться"; the next one goes on with "в тюрьме" right away.
        Scripted model = new Scripted()
                .then(18_000, "но", "мне", "хорошо", 700L, "купаться")
                .then(0, "в", "тюрьме", 900L, "вся", "моя", "жизнь");
        SongListener ear = new SongListener(model, "ru", false, false, "ru", true, lines, times);
        int border = Chunker.RATE * 18_000 / 1000 + Chunker.RATE * 1_750 / 1000;
        ear.listen(pcm, 0, border);
        ear.listen(pcm, border, 40 * Chunker.RATE);
        assertEquals(Arrays.asList("но мне хорошо", "купаться в тюрьме", "вся моя жизнь"), lines);
    }
}
