package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.util.Collections;
import java.util.List;

/**
 * What to tell the user before a recognition starts, worked out by {@link TranscriptionManager#advise}: a reason it
 * cannot start at all, things to warn about, whether the fast model would fit, and what must be downloaded first.
 */
public final class Advice {
    /** Why the work must not start (not enough memory); null when it may. */
    public final String blocker;
    /** Whether the fast model would fit in the memory that is free, when the chosen one does not. */
    public final boolean lighterModelFits;
    /** Things the user may want to think about: low battery, power saving. Empty when all is well. */
    public final List<String> warnings;
    /** What has to be downloaded first, in bytes; 0 when the library and the model are on the phone. */
    public final long downloadBytes;
    /** Whether the network in use is metered (mobile data), which matters when something has to be downloaded. */
    public final boolean metered;

    Advice(String blocker, boolean lighterModelFits, List<String> warnings, long downloadBytes, boolean metered) {
        this.blocker = blocker;
        this.lighterModelFits = lighterModelFits;
        this.warnings = Collections.unmodifiableList(warnings);
        this.downloadBytes = downloadBytes;
        this.metered = metered;
    }
}
