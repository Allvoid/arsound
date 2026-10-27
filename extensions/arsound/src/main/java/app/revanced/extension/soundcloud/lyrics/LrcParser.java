package app.revanced.extension.soundcloud.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the lyrics LRCLIB sends: the synced kind ("[01:23.45] text") and the plain kind.
 * <p>
 * LRCLIB's own files are tidy, but the format is loose and uploads are by hand: a stamp may have one to three digits
 * after the dot or none, a line may carry several stamps, word stamps in angle brackets may be inside the text, and
 * a file may shift all stamps with an offset.
 */
final class LrcParser {
    private static final Pattern LINE = Pattern.compile("^\\s*((?:\\[\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?\\]\\s*)+)(.*)$");
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("^\\s*\\[offset\\s*:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern WORD_STAMP = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern LINE_BREAK = Pattern.compile("\r\n|\n|\r");

    /** A file with fewer sung lines is a fragment, not a song to follow. */
    private static final int MIN_LINES = 3;

    private LrcParser() {
    }

    private static final class Entry {
        final long time;
        final String text;

        Entry(long time, String text) {
            this.time = time;
            this.text = text;
        }
    }

    /** @return The lines with their times, or null if the text is not usable synced lyrics. */
    static Lyrics parseSynced(String lrc) {
        if (lrc == null || lrc.trim().isEmpty()) return null;
        List<Entry> entries = new ArrayList<>();
        long offset = 0;
        for (String raw : LINE_BREAK.split(lrc)) {
            Matcher offsetMatch = OFFSET.matcher(raw);
            if (offsetMatch.find()) {
                try {
                    offset = Long.parseLong(offsetMatch.group(1).replace("+", ""));
                } catch (NumberFormatException ignored) {
                    // A broken offset is no offset.
                }
                continue;
            }
            Matcher line = LINE.matcher(raw);
            if (!line.matches()) continue;
            String text = clean(line.group(2));
            Matcher stamps = STAMP.matcher(line.group(1));
            while (stamps.find()) {
                entries.add(new Entry(time(stamps), text));
            }
        }
        if (entries.isEmpty()) return null;

        // A positive offset means the words come earlier than the stamps say.
        List<Entry> shifted = new ArrayList<>(entries.size());
        for (Entry entry : entries) shifted.add(new Entry(Math.max(0, entry.time - offset), entry.text));
        // Stable: lines with the same stamp keep their order.
        Collections.sort(shifted, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.time < b.time ? -1 : a.time == b.time ? 0 : 1;
            }
        });

        // One pause for any number of empty lines in a row, and none before the first or after the last line.
        List<Entry> kept = new ArrayList<>(shifted.size());
        int sung = 0;
        for (Entry entry : shifted) {
            boolean pause = entry.text.isEmpty();
            if (pause && (kept.isEmpty() || kept.get(kept.size() - 1).text.isEmpty())) continue;
            kept.add(entry);
            if (!pause) sung++;
        }
        while (!kept.isEmpty() && kept.get(kept.size() - 1).text.isEmpty()) kept.remove(kept.size() - 1);
        if (sung < MIN_LINES) return null;

        String[] lines = new String[kept.size()];
        long[] times = new long[kept.size()];
        for (int i = 0; i < lines.length; i++) {
            lines[i] = kept.get(i).text;
            times[i] = kept.get(i).time;
        }
        return new Lyrics(lines, times);
    }

    /** @return The lines of a text without timings, or null if there is no text. */
    static Lyrics parsePlain(String text) {
        if (text == null || text.trim().isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        for (String raw : LINE_BREAK.split(text)) {
            String line = clean(raw);
            // Blank lines between verses stay as one gap; none at the ends.
            if (line.isEmpty() && (lines.isEmpty() || lines.get(lines.size() - 1).isEmpty())) continue;
            lines.add(line);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        if (lines.isEmpty()) return null;
        return new Lyrics(lines.toArray(new String[0]), null);
    }

    private static String clean(String text) {
        String result = WORD_STAMP.matcher(text).replaceAll("");
        return SPACES.matcher(result).replaceAll(" ").trim();
    }

    private static long time(Matcher stamp) {
        long minutes = Long.parseLong(stamp.group(1));
        long seconds = Long.parseLong(stamp.group(2));
        long fraction = 0;
        String digits = stamp.group(3);
        if (digits != null) {
            fraction = Long.parseLong(digits);
            if (digits.length() == 1) fraction *= 100;
            else if (digits.length() == 2) fraction *= 10;
        }
        return (minutes * 60 + seconds) * 1000 + fraction;
    }
}
