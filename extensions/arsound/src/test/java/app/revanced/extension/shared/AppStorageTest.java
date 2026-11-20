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

    @Test
    public void anEarlyAnswerWithTheOwnFolderStaysForTheRun() throws Exception {
        // No shared folder yet: the own folder answers, and the cache must not move to the shared one later.
        assertEquals(true, AppStorage.pinsOwnFolder(new File[0]));
        assertEquals(true, AppStorage.pinsOwnFolder(new File[]{null}));
        assertEquals(false, AppStorage.pinsOwnFolder(new File[]{temp.newFolder("shared")}));
    }
}
