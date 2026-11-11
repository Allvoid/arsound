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

    /**
     * Puts a finished file in place of the old one in one step. Where a rename does not replace a file (not on
     * Android, but where the tests run), the old one is set aside first and put back if the new one still cannot be
     * moved: removing it instead lost the file kept in place whenever the rename failed for another cause (no room,
     * no access), as the second try failed for the same cause.
     */
    public static void replace(File from, File to) throws IOException {
        if (!from.isFile()) throw new IOException("Nothing to move: " + from.getName());
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                java.nio.file.Files.move(from.toPath(), to.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (IOException | UnsupportedOperationException ex) {
                // A file system without an atomic move: the rename below.
            }
        }
        if (from.renameTo(to)) return;
        File aside = new File(to.getPath() + ".old");
        //noinspection ResultOfMethodCallIgnored
        aside.delete();
        // Only a file is set aside: a folder in the way stays where it is.
        if (!to.isFile() || !to.renameTo(aside)) throw new IOException("Could not rename " + from.getName());
        if (!from.renameTo(to)) {
            //noinspection ResultOfMethodCallIgnored
            aside.renameTo(to);
            throw new IOException("Could not rename " + from.getName());
        }
        //noinspection ResultOfMethodCallIgnored
        aside.delete();
    }
}
