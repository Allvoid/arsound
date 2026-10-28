package app.revanced.extension.soundcloud.local;

import java.util.List;

/**
 * Where the imported files go among the likes read from the database. One implementation per way of ordering,
 * so the screen code never asks which sort order is chosen.
 */
interface LikePlacement {
    /**
     * @param imported The imported files, in the order they should keep.
     * @param stored   The rows of the database.
     * @return A new list with both.
     */
    <T> List<T> place(List<T> imported, List<T> stored);
}
