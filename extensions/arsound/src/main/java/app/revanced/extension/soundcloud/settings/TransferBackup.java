package app.revanced.extension.soundcloud.settings;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * A settings file that was read and checked: what it holds, typed the way SharedPreferences wants it, and how many
 * values were left out. Made by {@link SettingsTransfer#parse}; nothing has been written to the phone yet.
 */
final class TransferBackup {
    /** The layout version of the file. */
    final int formatVersion;
    /** The Arsound version that made it, "" if the file does not say (or says something odd). */
    final String appVersion;
    /** The day it was made as yyyy-MM-dd, "" if the file does not say. */
    final String date;
    /**
     * Preferences file name to its values: key to a Boolean, Integer, Long, Float, String or Set of String. Only files
     * that travel, and in them only keys that travel. A file the settings file lists with no values is here too, with an
     * empty map: it was empty when the file was made.
     */
    final Map<String, Map<String, Object>> files;
    /** Values the app never takes over from a file: sign-ins and keys, state of the other phone, files it does not know. */
    final int ignored;
    /** Values whose shape does not match their type (a text under "boolean", a fraction under "int"). */
    final int wrongType;
    /**
     * Preferences file name to the keys the file names with a value that was left out. They are not missing from the
     * file: this phone keeps its own value for them.
     */
    final Map<String, Set<String>> leftOut;

    TransferBackup(int formatVersion, String appVersion, String date, Map<String, Map<String, Object>> files,
                   int ignored, int wrongType) {
        this(formatVersion, appVersion, date, files, ignored, wrongType, Collections.emptyMap());
    }

    TransferBackup(int formatVersion, String appVersion, String date, Map<String, Map<String, Object>> files,
                   int ignored, int wrongType, Map<String, Set<String>> leftOut) {
        this.formatVersion = formatVersion;
        this.appVersion = appVersion;
        this.date = date;
        this.files = Collections.unmodifiableMap(files);
        this.ignored = ignored;
        this.wrongType = wrongType;
        this.leftOut = Collections.unmodifiableMap(leftOut);
    }

    /** How many values the file brings. */
    int valueCount() {
        int count = 0;
        for (Map<String, Object> values : files.values()) count += values.size();
        return count;
    }
}
