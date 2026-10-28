package app.revanced.extension.soundcloud.settings;

/**
 * Cuts the long description of a setting into a short summary for under the title and the full text for the "?" sheet.
 * Pure text work with no Android, so it is tested on the JVM. Used by SettingsUi when it makes a row.
 */
final class SettingText {
    /** A summary longer than this is cut: it has to fit two lines of a small phone. */
    static final int SUMMARY_LIMIT = 80;

    /** What stands under the title. */
    final String summary;
    /** The whole text for the sheet behind the "?"; null when the summary already says everything. */
    final String details;

    private SettingText(String summary, String details) {
        this.summary = summary;
        this.details = details;
    }

    /**
     * The first sentence is the summary when it is short; a longer one is cut at a word with "…". The whole text goes
     * to the details whenever the summary is not all of it. Text with a line break is a value with an explanation
     * under it (see SettingsUi.createChoiceRow): it is left alone.
     */
    static SettingText split(String description) {
        if (description == null) return new SettingText("", null);
        String text = description.trim().replaceAll("[ \\t]+", " ");
        if (text.length() <= SUMMARY_LIMIT || text.indexOf('\n') >= 0) return new SettingText(text, null);

        int end = firstSentenceEnd(text);
        if (end > 0 && end <= SUMMARY_LIMIT && end < text.length()) {
            return new SettingText(text.substring(0, end), text);
        }
        int cut = text.lastIndexOf(' ', SUMMARY_LIMIT);
        if (cut < SUMMARY_LIMIT / 2) cut = SUMMARY_LIMIT;
        String summary = text.substring(0, cut).replaceAll("[\\s,;:\\-—]+$", "") + "…";
        return new SettingText(summary, text);
    }

    /** The index just after the first ". ", "! " or "? ", or -1. A full stop inside "lrclib.net" is not a sentence end. */
    private static int firstSentenceEnd(String text) {
        for (int i = 0; i + 1 < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && text.charAt(i + 1) == ' ') return i + 1;
        }
        return -1;
    }
}
