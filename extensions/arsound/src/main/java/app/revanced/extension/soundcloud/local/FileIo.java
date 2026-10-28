package app.revanced.extension.soundcloud.local;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Pattern;

/** Small file helpers shared by the imports, downloads and covers. */
final class FileIo {
    private static final int BUFFER_BYTES = 64 * 1024;
    /** Characters a file name cannot hold on the shared storage. */
    private static final Pattern FORBIDDEN_IN_NAMES = Pattern.compile("[\\\\/:*?\"<>|]");

    private FileIo() {
    }

    /** The name with the characters a file name cannot hold replaced. */
    static String safeName(String name) {
        return FORBIDDEN_IN_NAMES.matcher(name).replaceAll("_");
    }

    /** Copies the stream to the end. The streams are not closed. */
    static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_BYTES];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }
}
