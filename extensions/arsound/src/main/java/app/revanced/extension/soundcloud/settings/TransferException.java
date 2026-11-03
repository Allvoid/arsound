package app.revanced.extension.soundcloud.settings;

/**
 * Why a settings file cannot be used. Carries only the reason; the words the user reads are in {@link TransferTexts}, so
 * the pure conversion in {@link SettingsTransfer} stays free of the app language.
 */
final class TransferException extends Exception {
    /** What is wrong with the file. */
    enum Reason {
        /** Not JSON at all: cut off, damaged, or some other kind of file. */
        NOT_JSON,
        /** Good JSON, but not made by "Export settings" (another program's file). */
        NOT_SETTINGS,
        /** Made by a newer Arsound whose file layout this version does not know. */
        NEWER_FORMAT,
        /** A settings file with no value this version can take over. */
        NOTHING_TO_IMPORT,
        /** Larger than any real settings file, see {@link SettingsTransfer#MAX_BYTES}. */
        TOO_BIG
    }

    final Reason reason;

    TransferException(Reason reason, String detail) {
        super(reason + ": " + detail);
        this.reason = reason;
    }

    TransferException(Reason reason) {
        this(reason, "");
    }
}
