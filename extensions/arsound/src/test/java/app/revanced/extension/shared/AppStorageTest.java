package app.revanced.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class AppStorageTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void noFolderYetIsNoAnswerToKeep() {
        // Android gives null early in the start: that must not be kept as "cannot be used" for the whole run.
        assertNull(AppStorage.probe(null));
    }

    @Test
    public void aWritableFolderIsUsable() throws Exception {
        assertEquals(Boolean.TRUE, AppStorage.probe(temp.newFolder("files")));
    }

    @Test
    public void aFolderThatIsAFileIsNotUsable() throws Exception {
        File file = temp.newFile("not-a-folder");
        assertEquals(Boolean.FALSE, AppStorage.probe(file));
    }
}
