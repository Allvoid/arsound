package app.revanced.extension.soundcloud.lyrics;

/** Stops a lookup between requests (and between the steps of a wait), for a track that is not the one wanted any more. */
interface LookupCancel {
    boolean isCancelled();
}
