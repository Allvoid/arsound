package app.revanced.extension.soundcloud.local;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import app.revanced.extension.shared.Utils;

/**
 * An order the user arranged by dragging, kept on this phone as one preferences value: the urns from first to
 * last, one per line. {@link TrackOrder} keeps one for every playlist, {@link PlaylistOrder} one for the library.
 */
final class ManualOrder {
    /** Where an item that is not in the saved order yet (it came later) goes. */
    enum Unknown {
        FIRST(-1),
        LAST(Integer.MAX_VALUE);

        private final int position;

        Unknown(int position) {
            this.position = position;
        }
    }

    /**
     * Orders saved while a drag goes on, by key: a drag saves at every step (so the lists SoundCloud diffs against
     * stay in the order on screen), and each save wrote the whole file. They are read from here and written to the
     * file together a moment after the last step.
     */
    private static final Map<String, String> pending = new HashMap<>();
    private static boolean flushPosted;
    private static final long FLUSH_MS = 500;

    private final String key;
    private final Unknown unknown;

    ManualOrder(String key, Unknown unknown) {
        this.key = key;
        this.unknown = unknown;
    }

    /** Forgets every saved order whose key starts with the prefix. */
    static void forgetAll(String keyPrefix) {
        synchronized (pending) {
            pending.keySet().removeIf(name -> name.startsWith(keyPrefix));
        }
        SharedPreferences preferences = LocalPrefs.open();
        if (preferences == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (String name : preferences.getAll().keySet()) {
            if (name.startsWith(keyPrefix)) editor.remove(name);
        }
        editor.apply();
    }

    /** Writes the orders that wait to the file. */
    private static void flush() {
        Map<String, String> due;
        synchronized (pending) {
            flushPosted = false;
            if (pending.isEmpty()) return;
            due = new HashMap<>(pending);
            pending.clear();
        }
        SharedPreferences preferences = LocalPrefs.open();
        if (preferences == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, String> order : due.entrySet()) editor.putString(order.getKey(), order.getValue());
        editor.apply();
    }

    /** The saved urns, first to last; empty if nothing is saved. */
    List<String> read() {
        String value;
        synchronized (pending) {
            value = pending.get(key);
        }
        if (value == null) {
            SharedPreferences preferences = LocalPrefs.open();
            value = preferences == null ? "" : preferences.getString(key, "");
        }
        List<String> order = new ArrayList<>();
        for (String urn : value.split("\n")) if (!urn.isEmpty()) order.add(urn);
        return order;
    }

    /** Saves the urns as the order, first to last. */
    void save(List<?> urns) {
        StringBuilder order = new StringBuilder();
        for (Object urn : urns) order.append(urn).append('\n');
        synchronized (pending) {
            pending.put(key, order.toString());
            if (flushPosted) return;
            flushPosted = true;
        }
        Utils.runOnMainThreadDelayed(() -> Utils.runOnBackgroundThread(ManualOrder::flush), FLUSH_MS);
    }

    /** Forgets the order: the items follow their natural order again. */
    void clear() {
        synchronized (pending) {
            pending.remove(key);
        }
        SharedPreferences preferences = LocalPrefs.open();
        if (preferences != null) preferences.edit().remove(key).apply();
    }

    /**
     * The items in the saved order. A stable sort: items that are not in the order keep their relative order, all
     * before or all after the arranged ones, as {@link Unknown} says.
     *
     * @param keyOf The urn of an item, which is what the order holds.
     */
    <T> List<T> sort(List<T> items, List<String> order, Function<T, String> keyOf) {
        Map<String, Integer> positions = new HashMap<>();
        for (int i = 0; i < order.size(); i++) positions.put(order.get(i), i);
        // The key of each item is read once (by reflection, for SoundCloud's items), not twice per comparison.
        int[] rank = new int[items.size()];
        Integer[] indexes = new Integer[items.size()];
        for (int i = 0; i < items.size(); i++) {
            rank[i] = positions.getOrDefault(keyOf.apply(items.get(i)), unknown.position);
            indexes[i] = i;
        }
        java.util.Arrays.sort(indexes, (first, second) -> Integer.compare(rank[first], rank[second]));
        List<T> result = new ArrayList<>(items.size());
        for (Integer index : indexes) result.add(items.get(index));
        return result;
    }
}
