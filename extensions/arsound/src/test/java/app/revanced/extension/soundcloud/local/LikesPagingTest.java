package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LikesPagingTest {
    private static final String RECENT = "RECENT|";

    @Test
    public void laterPageIsMovedBackByTheFilesOfTheFirstPage() {
        LikesPaging paging = new LikesPaging();
        // The first page showed 3 files and 50 rows: SoundCloud asks for offset 53, the database row is 50.
        paging.firstPage(RECENT, 3);
        assertEquals(50, paging.databaseOffset(RECENT, 53));
        assertEquals(100, paging.databaseOffset(RECENT, 103));
    }

    @Test
    public void listWithoutFilesIsLeftAlone() {
        LikesPaging paging = new LikesPaging();
        paging.firstPage(RECENT, 0);
        assertEquals(50, paging.databaseOffset(RECENT, 50));
    }

    @Test
    public void listWhoseFirstPageWasNotSeenIsLeftAlone() {
        assertEquals(50, new LikesPaging().databaseOffset(RECENT, 50));
    }

    @Test
    public void filesImportedLaterDoNotMoveTheListThatIsAlreadyOnScreen() {
        LikesPaging paging = new LikesPaging();
        paging.firstPage(RECENT, 3);
        // Nothing is told when a file arrives afterwards: the link to the next page was made from the first draw.
        assertEquals(50, paging.databaseOffset(RECENT, 53));
    }

    @Test
    public void aFirstPageDrawnAgainReplacesTheNumber() {
        LikesPaging paging = new LikesPaging();
        paging.firstPage(RECENT, 3);
        paging.firstPage(RECENT, 5);
        assertEquals(50, paging.databaseOffset(RECENT, 55));
    }

    @Test
    public void everyListHasItsOwnNumber() {
        LikesPaging paging = new LikesPaging();
        paging.firstPage("RECENT|", 4);
        paging.firstPage("TITLE|", 4);
        paging.firstPage("RECENT|rock", 1);
        assertEquals(50, paging.databaseOffset("RECENT|rock", 51));
        assertEquals(46, paging.databaseOffset("RECENT|", 50));
        assertEquals(46, paging.databaseOffset("TITLE|", 50));
    }

    @Test
    public void offsetNeverGoesBelowZero() {
        LikesPaging paging = new LikesPaging();
        paging.firstPage(RECENT, 10);
        assertEquals(0, paging.databaseOffset(RECENT, 4));
    }

    @Test
    public void rowsOfAllPagesAddUpWithoutGapsOrRepeats() {
        // 130 rows in the database, 7 files, pages of 50: the offsets for the database must be 0, 50, 100.
        LikesPaging paging = new LikesPaging();
        int files = 7;
        int shown = 0;
        int databaseRows = 0;
        paging.firstPage(RECENT, files);
        shown += files + Math.min(50, 130);
        databaseRows += Math.min(50, 130);
        while (true) {
            int offset = paging.databaseOffset(RECENT, shown);
            assertEquals(databaseRows, offset);
            int page = Math.max(0, Math.min(50, 130 - offset));
            if (page == 0) break;
            shown += page;
            databaseRows += page;
        }
        assertEquals(130, databaseRows);
    }

    @Test
    public void oldestListIsForgottenAfterManySearches() {
        LikesPaging paging = new LikesPaging();
        paging.firstPage("RECENT|first", 2);
        // Room for hundreds of lists: forty searches keep the first one.
        for (int i = 0; i < 40; i++) paging.firstPage("RECENT|query" + i, 1);
        assertEquals(50, paging.databaseOffset("RECENT|first", 52));
        for (int i = 40; i < 600; i++) paging.firstPage("RECENT|query" + i, 1);
        // Forgotten after many more: nothing to move back any more.
        assertEquals(52, paging.databaseOffset("RECENT|first", 52));
        assertEquals(51, paging.databaseOffset("RECENT|query599", 52));
    }
}
