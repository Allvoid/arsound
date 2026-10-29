package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Listens to the windows of one song with a loaded {@link Recognizer} and adds the lines it makes out. It keeps
 * the language of the song: fixed by the settings, or, when the settings leave it to Whisper, found from the first
 * window with enough words and then fixed, because Whisper is more accurate when it is told the language.
 * <p>
 * Whisper's own guess is not trusted blindly: a language other than Russian, English or the phone's (Japanese in a
 * Russian rap, say) is replaced by Russian (English in an English app) and the window is listened to again. When the
 * user said the song is in some other language ({@link SongLanguage#OTHER}), any language Whisper finds is believed. A window
 * in which the decoder fell into a loop is listened to again in two halves, because a short piece rarely loops. See
 * {@link LanguagePolicy} and {@link TextCleaner}.
 * <p>
 * A model that tells when its words are sung (GigaAM) gets lines cut at the singer's pauses, each starting with its
 * first word ({@link PhraseLines}); a phrase that a window border cut in two is joined again. Whisper's lines are cut
 * at its punctuation and spread over the window ({@link LineTimer}). Driven by {@link TranscriptionJob}; no Android
 * classes here.
 */
final class SongListener {
    /** How many words a window needs before its language is trusted as the song's. */
    static final int WORDS_TO_TRUST_LANGUAGE = 6;
    /** A window shorter than this is not listened to again in halves. */
    private static final int MIN_SPLIT = 12 * Chunker.RATE;
    /** A timed line lights up this much before its first word, so the eye is there when the voice comes. */
    static final long LEAD_MS = 150;

    private final Recognizer runner;
    private final boolean autoLanguage;
    /** Whether any language Whisper finds is believed, not only the expected ones. */
    private final boolean believeAny;
    private final String deviceLanguage;
    private final boolean russianApp;
    private final List<String> lines;
    private final List<Long> times;
    /** The language of the song; empty while it is left to Whisper. */
    private String language;
    /**
     * When the last word of the last timed line was sung, from the start of the song; -1 when the last line was not
     * timed by its words. A window that goes on with the same phrase continues that line.
     */
    private long lastWordEndMs = -1;

    /**
     * @param language     The language the runner was loaded with: "" or a code.
     * @param autoLanguage Whether the language is left to Whisper (then it is fixed once it is found).
     * @param believeAny   Whether any language Whisper finds is believed (the user said it is neither Russian nor
     *                     English); otherwise only Russian, English and the phone's language are.
     * @param lines        The lines so far; this class adds to them, together with {@code times}.
     */
    SongListener(Recognizer runner, String language, boolean autoLanguage, boolean believeAny, String deviceLanguage,
                 boolean russianApp, List<String> lines, List<Long> times) {
        this.runner = runner;
        this.language = language;
        this.autoLanguage = autoLanguage;
        this.believeAny = believeAny;
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
        Recognizer.Heard heard = runner.hear(window);
        boolean searching = autoLanguage && language.isEmpty();
        String trusted = "";
        if (searching) {
            String pick = believeAny ? heard.language : LanguagePolicy.pick(heard.language, deviceLanguage, russianApp);
            if (pick.equals(heard.language)) {
                trusted = pick;
            } else {
                // Whisper named a language nobody expects here: fix the usual one and listen again.
                fix(pick);
                heard = runner.hear(window);
                searching = false;
            }
        }
        TextCleaner.Cleaned cleaned = TextCleaner.inspect(heard.text, scripts());
        if (cleaned.dropped == TextCleaner.Dropped.LOOP && mayHalve && end - start >= MIN_SPLIT) {
            int middle = Chunker.splitPoint(pcm, start, end);
            listen(pcm, start, middle, false);
            listen(pcm, middle, end, false);
            return;
        }
        if (cleaned.dropped != null) return;
        if (searching && !trusted.isEmpty() && cleaned.words() >= WORDS_TO_TRUST_LANGUAGE) fix(trusted);
        List<HeardWord> words = heard.words.isEmpty() ? null : HeardWord.keptIn(heard.words, cleaned.text);
        if (words != null) {
            addTimed(Chunker.toMs(start), words);
        } else {
            add(pcm, start, end, cleaned.text);
        }
    }

    /** The letters a window may be written in: those of the song's language, of any language while it is searched. */
    private Set<LanguagePolicy.Script> scripts() {
        if (believeAny && language.isEmpty()) return EnumSet.allOf(LanguagePolicy.Script.class);
        return LanguagePolicy.scriptsFor(language, deviceLanguage);
    }

    private void fix(String code) throws IOException {
        language = code;
        runner.fixLanguage(code);
    }

    /** Lines cut at the pauses of the words, each starting with its first word. */
    private void addTimed(long windowMs, List<HeardWord> words) {
        List<PhraseLines.Line> windowLines = PhraseLines.cut(words);
        int first = 0;
        // The window border fell inside a phrase: its first words go on the last line.
        PhraseLines.Line opening = windowLines.get(0);
        if (lastWordEndMs >= 0 && !lines.isEmpty() && windowMs + opening.startMs() - lastWordEndMs < PhraseLines.PAUSE_MS) {
            String joined = lines.get(lines.size() - 1) + " " + opening.text();
            if (joined.length() <= PhraseLines.MAX_LINE) {
                lines.set(lines.size() - 1, joined);
                lastWordEndMs = windowMs + opening.endMs();
                first = 1;
            }
        }
        long previous = times.isEmpty() ? -1 : times.get(times.size() - 1);
        for (int i = first; i < windowLines.size(); i++) {
            PhraseLines.Line line = windowLines.get(i);
            // A line repeating the one before is kept: it is a chorus, GigaAM does not echo the last window as Whisper
            // does. Never earlier than the line before, so the order of the lines is the order of the clock.
            long time = Math.max(previous + 1, Math.max(0, windowMs + line.startMs() - LEAD_MS));
            lines.add(line.text());
            times.add(time);
            previous = time;
            lastWordEndMs = windowMs + line.endMs();
        }
    }

    /** Lines cut at the punctuation and spread over the part of the window where sound is. */
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
        lastWordEndMs = -1;
    }
}
