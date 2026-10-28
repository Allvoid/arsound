package app.revanced.extension.soundcloud.debug;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Copies text line by line with the secrets hidden ({@link SecretRedactor}), and stops at a size limit. */
public final class RedactedCopy {
    private RedactedCopy() {
    }

    /**
     * Copies the lines of {@code in} to {@code out} as UTF-8, each with a closing line break. When the next line
     * would take the copy past {@code maxBytes}, writes a line saying so and stops.
     *
     * @return The number of bytes written.
     */
    public static long copy(InputStream in, OutputStream out, long maxBytes) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        long written = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            byte[] bytes = (SecretRedactor.redact(line) + "\n").getBytes(StandardCharsets.UTF_8);
            if (written + bytes.length > maxBytes) {
                byte[] note = ("[cut: the rest is longer than " + maxBytes / 1024 + " KB]\n").getBytes(StandardCharsets.UTF_8);
                out.write(note);
                return written + note.length;
            }
            out.write(bytes);
            written += bytes.length;
        }
        return written;
    }
}
