package app.revanced.extension.soundcloud.lyrics;

/**
 * What a line of the column is, and so how it looks: a line of synced words, a line of plain text, the dots of a pause
 * in the music, or the empty gap between two verses. Each kind knows its own size, spacing and fade; the column only
 * asks. Pure, so it runs in the JVM tests.
 */
enum LineKind {
    /** A line of words with a start time. */
    TIMED(1f, 9) {
        @Override
        float alpha(int distanceFromActive) {
            return fadeWithDistance(distanceFromActive);
        }

        @Override
        float scale(int distanceFromActive) {
            return shrinkWithDistance(distanceFromActive);
        }
    },
    /** A line of words to read; nothing is singing, so nothing is dimmed. */
    PLAIN(1f, 9) {
        @Override
        float alpha(int distanceFromActive) {
            return READABLE_ALPHA;
        }

        @Override
        float scale(int distanceFromActive) {
            return 1f;
        }
    },
    /** An empty line of synced lyrics: a pause in the music, shown as three small dots. */
    PAUSE(0.6f, 6) {
        @Override
        float alpha(int distanceFromActive) {
            float alpha = fadeWithDistance(distanceFromActive);
            return distanceFromActive != 0 ? alpha * 0.7f : alpha;
        }

        @Override
        float scale(int distanceFromActive) {
            return shrinkWithDistance(distanceFromActive);
        }
    },
    /** An empty line of plain text: only the gap between two verses, with no text at all. */
    GAP(1f, 0) {
        @Override
        float alpha(int distanceFromActive) {
            return READABLE_ALPHA;
        }

        @Override
        float scale(int distanceFromActive) {
            return 1f;
        }
    };

    private static final float READABLE_ALPHA = 0.92f;

    /** The text size as a share of the size of the words. */
    final float textScale;
    /** Space above and below the text, in dp. */
    final int verticalPaddingDp;

    LineKind(float textScale, int verticalPaddingDp) {
        this.textScale = textScale;
        this.verticalPaddingDp = verticalPaddingDp;
    }

    /** How opaque the line is, given how many lines it is from the one that sings. */
    abstract float alpha(int distanceFromActive);

    /** How large the line is drawn, 1 being full size. */
    abstract float scale(int distanceFromActive);

    /** What is written for the line: the dots for a pause, the line itself otherwise. */
    String text(String line) {
        return this == PAUSE ? "• • •" : line;
    }

    /** The kind of a line of a song. */
    static LineKind of(String line, boolean timed) {
        if (!line.isEmpty()) return timed ? TIMED : PLAIN;
        // With timings an empty line is a pause in the music. Without them it is only the gap between two verses, and
        // dots there would mark a pause that is not in the song.
        return timed ? PAUSE : GAP;
    }

    private static float fadeWithDistance(int distance) {
        return distance == 0 ? 1f : distance == 1 ? 0.62f : distance == 2 ? 0.46f : 0.34f;
    }

    private static float shrinkWithDistance(int distance) {
        return distance == 0 ? 1f : 0.88f;
    }
}
