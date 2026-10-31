package app.revanced.extension.soundcloud.shared;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * A preferences file whose changes reach the disk together, at most once per {@link #FLUSH_MS}. Android writes the
 * whole file again on every {@code apply()}, so a file with a record per track (thousands of keys) was written in full
 * for every finished download, several times over. Reads see every change at once; only the disk lags.
 * <p>
 * All access to the file must go through the one wrapper ({@link #of}): a reader that opens the file itself would not
 * see the changes still waiting. A change that waits when the process is killed is lost, as one queued by
 * {@code apply()} can be; the files kept this way are ones the app can rebuild (the downloads are found again by the
 * listing of the folder).
 */
public final class BatchedPreferences implements SharedPreferences {
    /** How long changes wait to be written together. */
    static final long FLUSH_MS = 2_000;
    /** Stands for a key removed by a change that waits. */
    private static final Object REMOVED = new Object();
    private static final Map<SharedPreferences, BatchedPreferences> WRAPPERS = new WeakHashMap<>();

    private final SharedPreferences disk;
    /** The changes not on the disk yet: a value, or {@link #REMOVED}. Guarded by this. */
    private final Map<String, Object> pending = new HashMap<>();
    private boolean flushPosted;
    private final Runnable flush = this::flush;

    private BatchedPreferences(SharedPreferences disk) {
        this.disk = disk;
    }

    /** The one wrapper of this file; null for null. */
    public static SharedPreferences of(SharedPreferences disk) {
        if (disk == null) return null;
        synchronized (WRAPPERS) {
            BatchedPreferences wrapper = WRAPPERS.get(disk);
            if (wrapper == null) WRAPPERS.put(disk, wrapper = new BatchedPreferences(disk));
            return wrapper;
        }
    }

    /** Writes the changes of every file that wait: a screen left the front, and the app may be ended in the background. */
    public static void flushAll() {
        java.util.List<BatchedPreferences> all;
        synchronized (WRAPPERS) {
            all = new java.util.ArrayList<>(WRAPPERS.values());
        }
        for (BatchedPreferences wrapper : all) wrapper.flush();
    }

    /** Writes the changes that wait now; for the moment before the app may be ended. */
    public void flush() {
        Map<String, Object> changes;
        synchronized (this) {
            flushPosted = false;
            if (pending.isEmpty()) return;
            changes = new HashMap<>(pending);
            pending.clear();
            Editor editor = disk.edit();
            for (Map.Entry<String, Object> change : changes.entrySet()) put(editor, change.getKey(), change.getValue());
            // Applied under the lock: a read in between would find the change neither waiting nor in the file.
            editor.apply();
        }
    }

    @SuppressWarnings("unchecked")
    private static void put(Editor editor, String key, Object value) {
        if (value == REMOVED) editor.remove(key);
        else if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
    }

    private synchronized void merge(Map<String, Object> changes, boolean clear) {
        if (clear) {
            // Everything goes, on the disk and waiting; the edit's own changes come after, as with clear().
            pending.clear();
            for (String key : disk.getAll().keySet()) pending.put(key, REMOVED);
        }
        pending.putAll(changes);
        if (!flushPosted) {
            flushPosted = true;
            Utils.runOnMainThreadDelayed(flush, FLUSH_MS);
        }
    }

    /** The waiting change of a key, null when none waits. */
    private synchronized Object waiting(String key) {
        return pending.get(key);
    }

    @Override
    public synchronized Map<String, ?> getAll() {
        Map<String, Object> all = new HashMap<>(disk.getAll());
        for (Map.Entry<String, Object> change : pending.entrySet()) {
            if (change.getValue() == REMOVED) all.remove(change.getKey());
            else all.put(change.getKey(), change.getValue());
        }
        return all;
    }

    @Override
    public String getString(String key, String defValue) {
        synchronized (this) {
            Object value = waiting(key);
            if (value == null) return disk.getString(key, defValue);
            return value instanceof String ? (String) value : defValue;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key, Set<String> defValues) {
        synchronized (this) {
            Object value = waiting(key);
            if (value == null) return disk.getStringSet(key, defValues);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
        }
    }

    @Override
    public int getInt(String key, int defValue) {
        synchronized (this) {
            Object value = waiting(key);
            if (value == null) return disk.getInt(key, defValue);
            return value instanceof Integer ? (Integer) value : defValue;
        }
    }

    @Override
    public long getLong(String key, long defValue) {
        synchronized (this) {
            Object value = waiting(key);
            if (value == null) return disk.getLong(key, defValue);
            return value instanceof Long ? (Long) value : defValue;
        }
    }

    @Override
    public float getFloat(String key, float defValue) {
        synchronized (this) {
            Object value = waiting(key);
            if (value == null) return disk.getFloat(key, defValue);
            return value instanceof Float ? (Float) value : defValue;
        }
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        synchronized (this) {
            Object value = waiting(key);
            if (value == null) return disk.getBoolean(key, defValue);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }
    }

    @Override
    public boolean contains(String key) {
        synchronized (this) {
            Object value = waiting(key);
            return value == null ? disk.contains(key) : value != REMOVED;
        }
    }

    @Override
    public Editor edit() {
        return new BatchEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        // Told when the changes reach the file.
        disk.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        disk.unregisterOnSharedPreferenceChangeListener(listener);
    }

    /** Collects the changes of one edit; they wait together with the others until the next flush. */
    private final class BatchEditor implements Editor {
        private final Map<String, Object> changes = new HashMap<>();
        private boolean clear;

        @Override
        public Editor putString(String key, String value) {
            changes.put(key, value == null ? REMOVED : value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> values) {
            changes.put(key, values == null ? REMOVED : new HashSet<>(values));
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            changes.put(key, REMOVED);
            return this;
        }

        @Override
        public Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            merge(changes, clear);
            try {
                synchronized (BatchedPreferences.this) {
                    Map<String, Object> now = new HashMap<>(pending);
                    pending.clear();
                    Editor editor = disk.edit();
                    for (Map.Entry<String, Object> change : now.entrySet()) put(editor, change.getKey(), change.getValue());
                    return editor.commit();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Preferences: could not write", ex);
                return false;
            }
        }

        @Override
        public void apply() {
            merge(changes, clear);
        }
    }
}
