package app.revanced.extension.soundcloud.local;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

    private final String key;
    private final Unknown unknown;

    ManualOrder(String key, Unknown unknown) {
        this.key = key;
        this.unknown = unknown;
    }

    /** Forgets every saved order whose key starts with the prefix. */
    static void forgetAll(String keyPrefix) {
        SharedPreferences preferences = LocalPrefs.open();
        if (preferences == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        for (String name : preferences.getAll().keySet()) {
            if (name.startsWith(keyPrefix)) editor.remove(name);
        }
        editor.apply();
    }

    /** The saved urns, first to last; empty if nothing is saved. */
    List<String> read() {
        SharedPreferences preferences = LocalPrefs.open();
        String value = preferences == null ? "" : preferences.getString(key, "");
        List<String> order = new ArrayList<>();
        for (String urn : value.split("\n")) if (!urn.isEmpty()) order.add(urn);
        return order;
    }

    /** Saves the urns as the order, first to last. */
    void save(List<?> urns) {
        SharedPreferences preferences = LocalPrefs.open();
        if (preferences == null) return;
        StringBuilder order = new StringBuilder();
        for (Object urn : urns) order.append(urn).append('\n');
        preferences.edit().putString(key, order.toString()).apply();
    }

    /** Forgets the order: the items follow their natural order again. */
    void clear() {
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
        List<T> result = new ArrayList<>(items);
        Collections.sort(result, (first, second) -> Integer.compare(
                positions.getOrDefault(keyOf.apply(first), unknown.position),
                positions.getOrDefault(keyOf.apply(second), unknown.position)));
        return result;
    }
}
