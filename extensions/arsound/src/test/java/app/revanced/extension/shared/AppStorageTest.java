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
        // Android gives null early in the start: the probe gives no answer (settle turns it into the first, kept one).
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
    public void aLaterCallKeepsTheFirstAnswer() throws Exception {
        // An early start without the folder: unusable, and it stays so when the folder shows up later.
        boolean first = AppStorage.settle(null, AppStorage.probe(null));
        assertEquals(false, first);
        assertEquals(false, AppStorage.settle(first, AppStorage.probe(temp.newFolder("shared"))));
        // And the other way round.
        assertEquals(true, AppStorage.settle(true, AppStorage.probe(temp.newFolder("again"))));
        // The kept answer "usable" never gives a folder that is not there now (a card taken out, a locked storage).
        assertEquals(false, AppStorage.settle(true, AppStorage.probe(null)));
        assertEquals(true, AppStorage.settle(null, AppStorage.probe(temp.newFolder("later"))));
    }
}
