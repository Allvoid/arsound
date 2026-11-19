package app.revanced.extension.soundcloud.wallpaper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Copies a file the user picked into the cache, but no further than a limit: a file that is too big is refused after
 * the limit's worth of bytes, not after all of it. Plain Java, so the rule can be tested off the phone.
 */
public final class BoundedCopy {
    private BoundedCopy() {
    }

    /**
     * @return The number of bytes copied, or -1 when the input is longer than the limit. The copy stops at the chunk
     * that goes over the limit, and that chunk is not written.
     */
    public static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) > 0) {
            total += read;
            if (total > limit) return -1;
            output.write(buffer, 0, read);
        }
        return total;
    }
}
