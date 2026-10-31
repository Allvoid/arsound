package app.revanced.extension.soundcloud.local;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Page arithmetic for "Your likes" with imported files on top.
 * <p>
 * SoundCloud pages the likes by offset: the next page starts at the number of rows the first page showed. The
 * first page now also shows imported files, which are not in the database, so every later offset is too big by
 * that number and has to be moved back, or as many real likes would be skipped.
 * <p>
 * The number is the one the first page showed when it was first drawn, per list (sort order and search text):
 * SoundCloud builds the link to the next page from that first draw only, and files that finish loading later
 * change the list without moving it. No Android here, so it can be tested on the JVM.
 */
final class LikesPaging {
    /**
     * Lists remembered at once: every search text is a list of its own. A list whose number was dropped while it was
     * still paged skipped likes, so the room is ample: an entry is a short text and a number.
     */
    private static final int REMEMBERED_LISTS = 512;

    /** List key to the number of imported files on its first page, least recently used dropped first. */
    private final Map<String, Integer> importedOnFirstPage = new LinkedHashMap<String, Integer>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > REMEMBERED_LISTS;
        }
    };

    /**
     * The first page of a list is drawn.
     *
     * @param key           The list: sort order and search text.
     * @param importedCount The imported files in it, which the database does not know.
     */
    synchronized void firstPage(String key, int importedCount) {
        importedOnFirstPage.put(key, importedCount);
    }

    /**
     * @param key    The list: sort order and search text.
     * @param offset The offset SoundCloud asks for, counting the imported files of the first page.
     * @return The offset of the database rows. A list whose first page was not seen (the option was off then)
     * has nothing to move back.
     */
    synchronized int databaseOffset(String key, int offset) {
        return Math.max(0, offset - importedOnFirstPage.getOrDefault(key, 0));
    }
}
