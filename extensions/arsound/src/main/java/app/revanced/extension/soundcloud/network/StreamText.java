package app.revanced.extension.soundcloud.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Reads the body of an HTTP answer as text. */
public final class StreamText {
    private static final int BUFFER_BYTES = 16 * 1024;

    private StreamText() {
    }

    /** The most a text answer may be: a page or a list is far less, and a runaway answer must not fill the memory. */
    static final int MAX_BYTES = 16 * 1024 * 1024;

    /** Reads the stream to its end as UTF-8, at most {@link #MAX_BYTES}. The caller closes it. */
    public static String readUtf8(InputStream input) throws IOException {
        // Decoded as it comes into one builder: the bytes, their copy and the text were three copies at once.
        java.io.Reader reader = new java.io.InputStreamReader(input, StandardCharsets.UTF_8);
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[BUFFER_BYTES];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            text.append(buffer, 0, read);
            if (text.length() > MAX_BYTES) throw new IOException("The answer is longer than " + MAX_BYTES + " characters");
        }
        return text.toString();
    }
}
