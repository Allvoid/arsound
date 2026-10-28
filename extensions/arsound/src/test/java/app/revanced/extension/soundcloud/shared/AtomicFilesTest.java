package app.revanced.extension.soundcloud.shared;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AtomicFilesTest {
    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void writeCreatesTheTargetAndLeavesNoTemporaryFile() throws IOException {
        File target = new File(folder.getRoot(), "answer.json");
        File temporary = new File(folder.getRoot(), "answer.json.tmp");
        AtomicFiles.write(temporary, target, bytes("{}"));
        assertArrayEquals(bytes("{}"), Files.readAllBytes(target.toPath()));
        assertFalse(temporary.exists());
    }

    @Test
    public void writeReplacesAnOlderFile() throws IOException {
        File target = folder.newFile("cover.jpg");
        Files.write(target.toPath(), bytes("old, longer than the new one"));
        AtomicFiles.write(new File(folder.getRoot(), "cover.tmp"), target, bytes("new"));
        assertEquals("new", text(target));
    }

    @Test
    public void failedWriteRemovesTheTemporaryFile() throws IOException {
        // A non-empty folder cannot be replaced by a file, so the move fails after the bytes were written.
        File blocked = folder.newFolder("blocked");
        Files.write(new File(blocked, "inside").toPath(), bytes("x"));
        File temporary = new File(folder.getRoot(), "blocked.tmp");
        try {
            AtomicFiles.write(temporary, blocked, bytes("new"));
            fail("the move onto a non-empty folder should fail");
        } catch (IOException expected) {
            // The target is untouched.
        }
        assertFalse(temporary.exists());
        assertTrue(new File(blocked, "inside").isFile());
    }

    @Test
    public void replaceMovesTheFileOverAnExistingOne() throws IOException {
        File from = folder.newFile("from");
        File to = folder.newFile("to");
        Files.write(from.toPath(), bytes("fresh"));
        Files.write(to.toPath(), bytes("stale"));
        AtomicFiles.replace(from, to);
        assertFalse(from.exists());
        assertEquals("fresh", text(to));
    }

    @Test
    public void replaceFailsWhenThereIsNothingToMove() {
        File missing = new File(folder.getRoot(), "missing");
        try {
            AtomicFiles.replace(missing, new File(folder.getRoot(), "to"));
            fail("a missing source cannot be moved");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("missing"));
        }
    }
}
