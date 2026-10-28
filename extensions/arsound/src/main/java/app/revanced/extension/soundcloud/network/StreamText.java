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

    /** Reads the stream to its end as UTF-8. The caller closes it. */
    public static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_BYTES];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
