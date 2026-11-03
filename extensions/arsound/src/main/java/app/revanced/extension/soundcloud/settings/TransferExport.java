package app.revanced.extension.soundcloud.settings;

/** The settings file just made: its text and how many values went into it. Made by {@link SettingsTransfer#export}. */
final class TransferExport {
    final String json;
    final int valueCount;
    /** Values that could not be written (a number that JSON has no way to say). */
    final int skipped;

    TransferExport(String json, int valueCount, int skipped) {
        this.json = json;
        this.valueCount = valueCount;
        this.skipped = skipped;
    }
}
