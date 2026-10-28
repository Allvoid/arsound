package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.util.ArrayList;
import java.util.List;

/**
 * The text form of a {@link Transcript}: a few {@code key=value} lines, a line with three dashes, then one line per
 * line of the song, "milliseconds, a tab, the words". Plain text instead of JSON so that it is read without Android's
 * JSON classes and a half-written file is easy to tell from a whole one. Used by {@link TranscriptStore}.
 */
final class TranscriptCodec {
    private static final String MAGIC = "ARSOUND-TRANSCRIPT 1";
    private static final String SEPARATOR = "---";

    private TranscriptCodec() {
    }

    static String encode(Transcript transcript) {
        StringBuilder out = new StringBuilder();
        out.append(MAGIC).append('\n');
        out.append("urn=").append(oneLine(transcript.urn)).append('\n');
        out.append("model=").append(oneLine(transcript.modelId)).append('\n');
        out.append("state=").append(transcript.state.word).append('\n');
        out.append("lang=").append(oneLine(transcript.language)).append('\n');
        out.append("duration=").append(transcript.durationMs).append('\n');
        out.append("at=").append(transcript.createdAt).append('\n');
        out.append("done=").append(transcript.windowsDone).append('\n');
        out.append("total=").append(transcript.windowsTotal).append('\n');
        out.append("resume=").append(transcript.resumeMs).append('\n');
        out.append(SEPARATOR).append('\n');
        for (int i = 0; i < transcript.lines.length; i++) {
            out.append(transcript.times[i]).append('\t').append(oneLine(transcript.lines[i])).append('\n');
        }
        return out.toString();
    }

    /** @return The transcript, or null if the text is not one (a file cut short, another format). */
    static Transcript decode(String text) {
        if (text == null) return null;
        String[] rows = text.split("\n", -1);
        if (rows.length == 0 || !MAGIC.equals(rows[0])) return null;
        String urn = "";
        String model = "";
        String language = "";
        Transcript.State state = null;
        long duration = 0;
        long at = 0;
        int done = 0;
        int total = 0;
        long resume = 0;
        int i = 1;
        boolean separated = false;
        for (; i < rows.length; i++) {
            if (SEPARATOR.equals(rows[i])) {
                separated = true;
                i++;
                break;
            }
            int equals = rows[i].indexOf('=');
            if (equals < 0) continue;
            String key = rows[i].substring(0, equals);
            String value = rows[i].substring(equals + 1);
            try {
                switch (key) {
                    case "urn": urn = value; break;
                    case "model": model = value; break;
                    case "state": state = Transcript.State.fromWord(value); break;
                    case "lang": language = value; break;
                    case "duration": duration = Long.parseLong(value); break;
                    case "at": at = Long.parseLong(value); break;
                    case "done": done = Integer.parseInt(value); break;
                    case "total": total = Integer.parseInt(value); break;
                    case "resume": resume = Long.parseLong(value); break;
                    default: break;
                }
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (!separated || state == null || model.isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        List<Long> times = new ArrayList<>();
        for (; i < rows.length; i++) {
            if (rows[i].isEmpty()) continue;
            int tab = rows[i].indexOf('\t');
            if (tab <= 0) return null;
            try {
                times.add(Long.parseLong(rows[i].substring(0, tab)));
            } catch (NumberFormatException ex) {
                return null;
            }
            lines.add(rows[i].substring(tab + 1));
        }
        long[] stamps = new long[times.size()];
        for (int k = 0; k < stamps.length; k++) stamps[k] = times.get(k);
        return new Transcript(urn, model, state, language, duration, at, done, total, resume,
                lines.toArray(new String[0]), stamps);
    }

    /** Line breaks and tabs would break the format; they become spaces. */
    private static String oneLine(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    }
}
