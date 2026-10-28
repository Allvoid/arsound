package app.revanced.extension.soundcloud.shared;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Putting a file in place in one step, so a reader sees the old file or the whole new one and never half of it. */
public final class AtomicFiles {
    private AtomicFiles() {
    }

    /**
     * Writes the bytes to {@code temporary}, then moves it onto {@code target}. The temporary file lies beside the
     * target (the same folder), as a move across folders is not one step. If anything fails the temporary file is
     * removed and the target stays as it was.
     */
    public static void write(File temporary, File target, byte[] bytes) throws IOException {
        boolean moved = false;
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(bytes);
            }
            replace(temporary, target);
            moved = true;
        } finally {
            if (!moved) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
            }
        }
    }

    /** Puts a finished file in place of the old one in one step. Where a rename does not replace a file, the old one is removed first. */
    public static void replace(File from, File to) throws IOException {
        if (from.renameTo(to)) return;
        //noinspection ResultOfMethodCallIgnored
        to.delete();
        if (!from.renameTo(to)) throw new IOException("Could not rename " + from.getName());
    }
}
