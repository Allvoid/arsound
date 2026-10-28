package app.revanced.extension.soundcloud.download;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * The kinds of audio file a download can be, told apart by the extension of the name. Each knows its extension, the
 * type the storage needs to be told (it has to match the extension), and how the title and artist are put into a
 * file of its kind, see {@link FileTags}.
 */
enum AudioFormat {
    MP3(".mp3", "audio/mpeg") {
        @Override
        FileTags.Tagged tagged(File source, String title, String artist) throws IOException {
            return FileTags.planId3(source, title, artist);
        }
    },
    M4A(".m4a", "audio/mp4") {
        @Override
        FileTags.Tagged tagged(File source, String title, String artist) throws IOException {
            return FileTags.planMp4(source, title, artist);
        }
    },
    /** The system names the type of an mp4 file itself, so no type is fixed here. */
    MP4(".mp4", null) {
        @Override
        FileTags.Tagged tagged(File source, String title, String artist) throws IOException {
            return FileTags.planMp4(source, title, artist);
        }
    },
    AAC(".aac", "audio/aac"),
    OPUS(".opus", "audio/ogg");

    private final String extension;
    private final String mimeType;

    AudioFormat(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    /** The type to tell the storage, or null to leave it to the system. */
    String mimeType() {
        return mimeType;
    }

    /**
     * A copy of the file with the title and artist in it.
     *
     * @return The tagged copy, or null for a file that is not tagged here (the caller copies it as it is).
     */
    FileTags.Tagged tagged(File source, String title, String artist) throws IOException {
        return null;
    }

    /** The format of a file with this name, the letters of the extension counted as they are; null if not one of these. */
    static AudioFormat ofName(String fileName) {
        for (AudioFormat format : values()) {
            if (fileName.endsWith(format.extension)) return format;
        }
        return null;
    }

    /** The same, also for an extension in capitals. */
    static AudioFormat ofNameIgnoringCase(String fileName) {
        return ofName(fileName.toLowerCase(Locale.ROOT));
    }
}
