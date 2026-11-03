package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.Test;

public class LikeChoicesTest {
    private static final File A = new File("/imported/a.mp3");
    private static final File B = new File("/imported/b.mp3");
    private static final File C = new File("/imported/c.mp3");

    private static final class MemoryStorage implements LikeChoices.Storage {
        LikeChoices.Snapshot saved;
        Set<String> hidden;
        int saves;

        MemoryStorage(LikeChoices.Snapshot saved, Set<String> hidden) {
            this.saved = saved;
            this.hidden = hidden;
        }

        @Override
        public LikeChoices.Snapshot load() {
            return saved;
        }

        @Override
        public Set<String> legacyHidden() {
            return hidden;
        }

        @Override
        public void save(LikeChoices.Snapshot snapshot) {
            saved = snapshot;
            saves++;
        }
    }

    private static MemoryStorage fresh() {
        return new MemoryStorage(null, Collections.emptySet());
    }

    @Test
    public void aNewFileIsLikedWhenTheSettingIsOn() {
        LikeChoices choices = new LikeChoices(fresh());
        choices.sync(Collections.emptyList(), true);
        choices.sync(Arrays.asList(A), true);
        assertTrue(choices.isLiked(A));
    }

    @Test
    public void aNewFileIsNotLikedWhenTheSettingIsOff() {
        MemoryStorage storage = new MemoryStorage(new LikeChoices.Snapshot(set(), set(), set()), null);
        LikeChoices choices = new LikeChoices(storage);
        choices.sync(Arrays.asList(A), false);
        assertFalse(choices.isLiked(A));
    }

    @Test
    public void theUserCanLikeAFileThatWasNotLikedOnImport() {
        MemoryStorage storage = new MemoryStorage(new LikeChoices.Snapshot(set(), set(), set()), null);
        LikeChoices choices = new LikeChoices(storage);
        choices.sync(Arrays.asList(A), false);
        assertTrue(choices.like(A));
        assertTrue(choices.isLiked(A));
        assertFalse(choices.like(A));
    }

    @Test
    public void takingTheLikeAwayLeavesTheFileUnliked() {
        LikeChoices choices = new LikeChoices(fresh());
        choices.sync(Arrays.asList(A), true);
        assertTrue(choices.unlike(A));
        assertFalse(choices.isLiked(A));
        assertFalse(choices.unlike(A));
    }

    @Test
    public void changingTheSettingDoesNotTouchFilesAlreadySeen() {
        MemoryStorage storage = new MemoryStorage(new LikeChoices.Snapshot(set(), set(), set()), null);
        LikeChoices off = new LikeChoices(storage);
        off.sync(Arrays.asList(A), false);
        // A later run with the setting on: A was seen with it off, so it is not liked now.
        LikeChoices on = new LikeChoices(storage);
        on.sync(Arrays.asList(A, B), true);
        assertFalse(on.isLiked(A));
        assertTrue(on.isLiked(B));
    }

    @Test
    public void theLikesOfTheOlderVersionAreKeptOnTheFirstRun() {
        MemoryStorage storage = new MemoryStorage(null, set(B.getPath()));
        LikeChoices choices = new LikeChoices(storage);
        choices.sync(Arrays.asList(A, B, C), false);
        assertTrue(choices.isLiked(A));
        assertFalse(choices.isLiked(B));
        assertTrue(choices.isLiked(C));
        assertTrue(storage.saved.known.containsAll(Arrays.asList(A.getPath(), B.getPath(), C.getPath())));
    }

    @Test
    public void likingTheUndecidedSkipsWhatTheUserTookAway() {
        MemoryStorage storage = new MemoryStorage(new LikeChoices.Snapshot(set(), set(), set()), null);
        LikeChoices choices = new LikeChoices(storage);
        List<File> files = Arrays.asList(A, B, C);
        choices.sync(files, false);
        choices.like(A);
        choices.unlike(B);
        assertEquals(1, choices.undecidedCount(files));
        assertEquals(1, choices.likeUndecided(files));
        assertTrue(choices.isLiked(C));
        assertFalse(choices.isLiked(B));
        assertEquals(0, choices.undecidedCount(files));
    }

    @Test
    public void aDeletedFileIsForgottenSoANewFileWithTheSameNameIsNew() {
        LikeChoices choices = new LikeChoices(fresh());
        choices.sync(Arrays.asList(A), true);
        choices.unlike(A);
        choices.forget(A);
        choices.sync(Arrays.asList(A), true);
        assertTrue(choices.isLiked(A));
    }

    @Test
    public void everyChangeIsSaved() {
        MemoryStorage storage = fresh();
        LikeChoices choices = new LikeChoices(storage);
        choices.sync(Arrays.asList(A), true);
        int afterSync = storage.saves;
        choices.unlike(A);
        assertEquals(afterSync + 1, storage.saves);
        assertTrue(storage.saved.unliked.contains(A.getPath()));
        assertFalse(storage.saved.liked.contains(A.getPath()));
    }

    private static Set<String> set(String... paths) {
        return new HashSet<>(Arrays.asList(paths));
    }
}
