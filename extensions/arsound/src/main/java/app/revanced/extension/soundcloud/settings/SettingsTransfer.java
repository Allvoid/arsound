package app.revanced.extension.soundcloud.settings;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The settings file: turns the values of the preferences files into JSON and back, and works out what importing it
 * changes. Pure Java with no Android, so the whole format is tested on the JVM; the reading and writing of the real
 * preferences and files is {@code TransferFiles}, the choice of what travels is {@link TransferPlan}.
 * <p>
 * The file (pretty printed, in UTF-8):
 * <pre>
 * {
 *   "format": "arsound-settings",
 *   "formatVersion": 1,
 *   "appVersion": "1.0.1",
 *   "exportedOn": "2026-11-03",
 *   "files": {
 *     "revanced_soundcloud": {
 *       "boolean": {"crossfade": true},
 *       "int": {...}, "long": {...}, "float": {...}, "string": {...},
 *       "stringSet": {"some_key": ["a", "b"]}
 *     },
 *     "arsound_wave": {}
 *   }
 * }
 * </pre>
 * The type of every value is the name of the section it stands in, so a number never turns from a long to an int on the
 * way (SharedPreferences throws if a key is read as another type than it was saved). Only the sections with values are
 * written. A file of the list is written even when empty: importing it then empties that file on the other phone.
 * <p>
 * What is read back is checked, not trusted: the file may be damaged, hand-edited or from another program. Files the app
 * does not know are ignored (a newer version may have more), and so is every key {@link TransferPlan} says never travels,
 * even if someone put it in; keys the app does not know inside a known file are kept.
 */
final class SettingsTransfer {
    static final String FORMAT = "arsound-settings";
    /** The layout of the file this version writes and reads. A bigger number in a file is a newer Arsound's. */
    static final int FORMAT_VERSION = 1;
    /** The most a settings file may hold, in bytes. A real one is tens of kilobytes. */
    static final int MAX_BYTES = 4 * 1024 * 1024;

    /** The names of the value types, in the order the sections are written. */
    private static final String[] TYPES = {"boolean", "int", "long", "float", "string", "stringSet"};

    private SettingsTransfer() {
    }

    // region Naming

    /** The name offered for the file, such as {@code arsound-settings-2026-11-03.json}. */
    static String fileName(String date) {
        return "arsound-settings-" + date + ".json";
    }

    /** The day as yyyy-MM-dd in the given zone (the phone's, so the name matches the user's calendar). */
    static String dateOf(long millis, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(zone);
        return format.format(new Date(millis));
    }

    // endregion

    // region Types

    /**
     * The name of the type of a value as SharedPreferences gives it, or null for a type it never gives. A set of
     * strings is one type whatever its class.
     */
    static String typeOf(Object value) {
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Integer) return "int";
        if (value instanceof Long) return "long";
        if (value instanceof Float) return "float";
        if (value instanceof String) return "string";
        if (value instanceof Set) return "stringSet";
        return null;
    }

    // endregion

    // region Export

    /**
     * The settings file for the current values.
     *
     * @param current    Preferences file name to its values, as {@code SharedPreferences.getAll()} gives them; files
     *                   that do not travel and keys that do not are left out here, whatever is passed in.
     * @param appVersion The Arsound version, for the user to see on import.
     * @param date       The day, yyyy-MM-dd.
     */
    static TransferExport export(Map<String, ? extends Map<String, ?>> current, String appVersion, String date) {
        StringBuilder out = new StringBuilder(4096);
        out.append("{\n  \"format\": ");
        TransferJson.quote(out, FORMAT);
        out.append(",\n  \"formatVersion\": ").append(FORMAT_VERSION);
        out.append(",\n  \"appVersion\": ");
        TransferJson.quote(out, appVersion);
        out.append(",\n  \"exportedOn\": ");
        TransferJson.quote(out, date);
        out.append(",\n  \"files\": {");

        int written = 0, skipped = 0;
        boolean firstFile = true;
        for (TransferPlan.Source source : TransferPlan.SOURCES) {
            Map<String, List<String[]>> sections = new LinkedHashMap<>();
            for (String type : TYPES) sections.put(type, new ArrayList<>());

            // Sorted, so two exports of the same settings are the same file.
            Map<String, Object> sorted = new TreeMap<>();
            Map<String, ?> values = current.get(source.file);
            if (values != null) sorted.putAll(values);
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!source.carries(entry.getKey())) continue;
                String text = jsonOf(entry.getValue());
                String type = typeOf(entry.getValue());
                if (text == null || type == null) {
                    skipped++;
                    continue;
                }
                sections.get(type).add(new String[]{entry.getKey(), text});
                written++;
            }

            out.append(firstFile ? "\n    " : ",\n    ");
            firstFile = false;
            TransferJson.quote(out, source.file);
            out.append(": {");
            boolean firstSection = true;
            for (Map.Entry<String, List<String[]>> section : sections.entrySet()) {
                if (section.getValue().isEmpty()) continue;
                out.append(firstSection ? "\n      " : ",\n      ");
                firstSection = false;
                TransferJson.quote(out, section.getKey());
                out.append(": {");
                boolean firstValue = true;
                for (String[] pair : section.getValue()) {
                    out.append(firstValue ? "\n        " : ",\n        ");
                    firstValue = false;
                    TransferJson.quote(out, pair[0]);
                    out.append(": ").append(pair[1]);
                }
                out.append("\n      }");
            }
            out.append(firstSection ? "}" : "\n    }");
        }
        out.append(firstFile ? "}\n}\n" : "\n  }\n}\n");
        return new TransferExport(out.toString(), written, skipped);
    }

    /** The JSON of one value, or null if JSON cannot say it (NaN and infinity). */
    private static String jsonOf(Object value) {
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long) return value.toString();
        if (value instanceof Float) {
            float number = (Float) value;
            // Float.toString gives the shortest text that reads back as the same float.
            return Float.isNaN(number) || Float.isInfinite(number) ? null : Float.toString(number);
        }
        if (value instanceof String) {
            StringBuilder text = new StringBuilder();
            TransferJson.quote(text, (String) value);
            return text.toString();
        }
        if (value instanceof Set) {
            List<String> items = new ArrayList<>();
            for (Object item : (Set<?>) value) {
                if (!(item instanceof String)) return null;
                items.add((String) item);
            }
            Collections.sort(items);
            StringBuilder text = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) text.append(", ");
                TransferJson.quote(text, items.get(i));
            }
            return text.append(']').toString();
        }
        return null;
    }

    // endregion

    // region Import

    /**
     * Reads and checks a settings file.
     *
     * @throws TransferException The file is not JSON ({@code NOT_JSON}), not a settings file ({@code NOT_SETTINGS}), from
     *                           a newer layout ({@code NEWER_FORMAT}), too big, or brings nothing this version can take.
     */
    static TransferBackup parse(String text) throws TransferException {
        if (text.length() > MAX_BYTES) throw new TransferException(TransferException.Reason.TOO_BIG, "text of " + text.length());
        Object root = TransferJson.parse(text);
        Map<String, Object> top = objectOf(root);
        if (top == null || !FORMAT.equals(top.get("format"))) {
            throw new TransferException(TransferException.Reason.NOT_SETTINGS, "no format mark");
        }
        Object version = top.get("formatVersion");
        if (!(version instanceof Long) || (Long) version < 1) {
            throw new TransferException(TransferException.Reason.NOT_SETTINGS, "no format version");
        }
        if ((Long) version > FORMAT_VERSION) {
            throw new TransferException(TransferException.Reason.NEWER_FORMAT, "version " + version);
        }
        Map<String, Object> files = objectOf(top.get("files"));
        if (files == null) throw new TransferException(TransferException.Reason.NOT_SETTINGS, "no files");

        Map<String, Map<String, Object>> kept = new LinkedHashMap<>();
        int[] counts = new int[2];
        for (Map.Entry<String, Object> file : files.entrySet()) {
            Map<String, Object> sections = objectOf(file.getValue());
            if (sections == null) {
                counts[1]++;
                continue;
            }
            TransferPlan.Source source = TransferPlan.find(file.getKey());
            if (source == null) {
                // A newer version's file, or one that must not be written from outside (a sign-in).
                counts[0] += countValues(sections);
                continue;
            }
            readFile(source, sections, kept.computeIfAbsent(source.file, name -> new LinkedHashMap<>()), counts);
        }

        TransferBackup backup = new TransferBackup((int) (long) (Long) version, shortText(top.get("appVersion")),
                dayOf(top.get("exportedOn")), kept, counts[0], counts[1]);
        if (backup.valueCount() == 0) throw new TransferException(TransferException.Reason.NOTHING_TO_IMPORT, "no values");
        return backup;
    }

    /** Reads the typed sections of one file into {@code into}; {@code counts} is {ignored, wrongType}. */
    private static void readFile(TransferPlan.Source source, Map<String, Object> sections, Map<String, Object> into, int[] counts) {
        for (Map.Entry<String, Object> section : sections.entrySet()) {
            Map<String, Object> values = objectOf(section.getValue());
            if (values == null) {
                counts[1]++;
                continue;
            }
            if (!isKnownType(section.getKey())) {
                // A type a newer version added.
                counts[0] += values.size();
                continue;
            }
            for (Map.Entry<String, Object> value : values.entrySet()) {
                if (!source.carries(value.getKey())) {
                    counts[0]++;
                    continue;
                }
                Object typed = convert(section.getKey(), value.getValue());
                // The same key in two sections cannot be told apart: the first stays, the other counts as wrong.
                if (typed == null || into.containsKey(value.getKey())) {
                    counts[1]++;
                    continue;
                }
                into.put(value.getKey(), typed);
            }
        }
    }

    /** A value of the JSON turned into the type its section names, or null if it is not that type. */
    private static Object convert(String type, Object value) {
        switch (type) {
            case "boolean":
                return value instanceof Boolean ? value : null;
            case "int":
                return value instanceof Long && (Long) value >= Integer.MIN_VALUE && (Long) value <= Integer.MAX_VALUE
                        ? (Object) (int) (long) (Long) value : null;
            case "long":
                return value instanceof Long ? value : null;
            case "float":
                if (!(value instanceof Number)) return null;
                // Read from the text, not narrowed from a double: rounding twice can land one float off.
                float number = Float.parseFloat(value.toString());
                return Float.isNaN(number) || Float.isInfinite(number) ? null : (Object) number;
            case "string":
                return value instanceof String ? value : null;
            case "stringSet":
                if (!(value instanceof List)) return null;
                Set<String> items = new TreeSet<>();
                for (Object item : (List<?>) value) {
                    if (!(item instanceof String)) return null;
                    items.add((String) item);
                }
                return items;
            default:
                return null;
        }
    }

    private static boolean isKnownType(String name) {
        for (String type : TYPES) {
            if (type.equals(name)) return true;
        }
        return false;
    }

    /** How many values stand in the sections of a file we do not take. */
    private static int countValues(Map<String, Object> sections) {
        int count = 0;
        for (Object section : sections.values()) {
            Map<String, Object> values = objectOf(section);
            count += values == null ? 1 : values.size();
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectOf(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    /** A short plain text for showing to the user: letters, digits and . - _ + only, at most 32; else "". */
    private static String shortText(Object value) {
        if (!(value instanceof String)) return "";
        String text = (String) value;
        return text.length() <= 32 && text.matches("[0-9A-Za-z.+_-]*") ? text : "";
    }

    /** The day as yyyy-MM-dd, or "" if the file says something else. */
    private static String dayOf(Object value) {
        return value instanceof String && ((String) value).matches("\\d{4}-\\d{2}-\\d{2}") ? (String) value : "";
    }

    /**
     * What importing does here. The file's state wins for every key that travels: its values are written, and the keys
     * this phone has but the file lacks go back to their defaults (the file was made when they were at the default). A
     * value is not written where this phone keeps that key as another type, which would make the app crash when it reads
     * it; the key stays as it is.
     *
     * @param current Preferences file name to its values on this phone, as {@code getAll()} gives them.
     */
    static TransferChanges plan(TransferBackup backup, Map<String, ? extends Map<String, ?>> current) {
        TransferChanges changes = new TransferChanges();
        for (Map.Entry<String, Map<String, Object>> file : backup.files.entrySet()) {
            TransferPlan.Source source = TransferPlan.find(file.getKey());
            if (source == null) continue;
            Map<String, ?> here = current.get(file.getKey());
            if (here == null) here = Collections.emptyMap();

            for (Map.Entry<String, Object> value : file.getValue().entrySet()) {
                Object old = here.get(value.getKey());
                if (old != null && !typeOf(value.getValue()).equals(typeOf(old))) {
                    changes.typeConflicts++;
                    continue;
                }
                changes.writesOf(file.getKey()).put(value.getKey(), value.getValue());
            }
            for (String key : here.keySet()) {
                if (source.carries(key) && !file.getValue().containsKey(key)) changes.removalsOf(file.getKey()).add(key);
            }
        }
        return changes;
    }

    // endregion
}
