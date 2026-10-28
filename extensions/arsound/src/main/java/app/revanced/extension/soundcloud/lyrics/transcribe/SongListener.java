package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Listens to the windows of one song with a loaded {@link WhisperRunner} and adds the lines it makes out. It keeps
 * the language of the song: fixed by the settings, or, when the settings leave it to Whisper, found from the first
 * window with enough words and then fixed, because Whisper is more accurate when it is told the language.
 * <p>
 * Whisper's own guess is not trusted blindly: a language other than Russian, English or the phone's (Japanese in a
 * Russian rap, say) is replaced by Russian (English in an English app) and the window is listened to again. A window
 * in which the decoder fell into a loop is listened to again in two halves, because a short piece rarely loops. See
 * {@link LanguagePolicy} and {@link TextCleaner}. Driven by {@link TranscriptionJob}; no Android classes here.
 */
final class SongListener {
    /** How many words a window needs before its language is trusted as the song's. */
    static final int WORDS_TO_TRUST_LANGUAGE = 6;
    /** A window shorter than this is not listened to again in halves. */
    private static final int MIN_SPLIT = 12 * Chunker.RATE;

    private final WhisperRunner runner;
    private final boolean autoLanguage;
    private final String deviceLanguage;
    private final boolean russianApp;
    private final List<String> lines;
    private final List<Long> times;
    /** The language of the song; empty while it is left to Whisper. */
    private String language;

    /**
     * @param language     The language the runner was loaded with: "" or a code.
     * @param autoLanguage Whether the settings leave the language to Whisper (then it is fixed once it is found).
     * @param lines        The lines so far; this class adds to them, together with {@code times}.
     */
    SongListener(WhisperRunner runner, String language, boolean autoLanguage, String deviceLanguage,
                 boolean russianApp, List<String> lines, List<Long> times) {
        this.runner = runner;
        this.language = language;
        this.autoLanguage = autoLanguage;
        this.deviceLanguage = deviceLanguage;
        this.russianApp = russianApp;
        this.lines = lines;
        this.times = times;
    }

    /** The language of the song so far; empty if it was not found out. */
    String language() {
        return language;
    }

    /** Listens to the samples from {@code start} to {@code end} (at most 30 seconds) and adds the lines. */
    void listen(float[] pcm, int start, int end) throws IOException {
        listen(pcm, start, end, true);
    }

    private void listen(float[] pcm, int start, int end, boolean mayHalve) throws IOException {
        if (Chunker.isSilent(pcm, start, end)) return;
        float[] window = Arrays.copyOfRange(pcm, start, end);
        WhisperRunner.Heard heard = runner.hear(window);
        boolean searching = autoLanguage && language.isEmpty();
        String trusted = "";
        if (searching) {
            String pick = LanguagePolicy.pick(heard.language, deviceLanguage, russianApp);
            if (pick.equals(heard.language)) {
                trusted = pick;
            } else {
                // Whisper named a language nobody expects here: fix the usual one and listen again.
                fix(pick);
                heard = runner.hear(window);
                searching = false;
            }
        }
        TextCleaner.Cleaned cleaned = TextCleaner.inspect(heard.text, LanguagePolicy.scriptsFor(language, deviceLanguage));
        if (cleaned.dropped == TextCleaner.Dropped.LOOP && mayHalve && end - start >= MIN_SPLIT) {
            int middle = Chunker.splitPoint(pcm, start, end);
            listen(pcm, start, middle, false);
            listen(pcm, middle, end, false);
            return;
        }
        if (cleaned.dropped != null) return;
        if (searching && !trusted.isEmpty() && cleaned.words() >= WORDS_TO_TRUST_LANGUAGE) fix(trusted);
        add(pcm, start, end, cleaned.text);
    }

    private void fix(String code) throws IOException {
        language = code;
        runner.fixLanguage(code);
    }

    private void add(float[] pcm, int start, int end, String text) {
        List<String> windowLines = TextCleaner.withoutRepeatOfPrevious(TextCleaner.splitLines(text), lines);
        if (windowLines.isEmpty()) return;
        int[] span = Chunker.activeSpan(pcm, start, end);
        long notBefore = times.isEmpty() ? 0 : times.get(times.size() - 1) + 1;
        long[] stamps = LineTimer.spread(windowLines, Chunker.toMs(span[0]), Chunker.toMs(span[1]), notBefore);
        for (int i = 0; i < windowLines.size(); i++) {
            lines.add(windowLines.get(i));
            times.add(stamps[i]);
        }
    }
}
