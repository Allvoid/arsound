package app.revanced.extension.soundcloud.transfer;

/** What a transfer reads: a collection of one {@link TransferSource}, by a link or from the signed-in account. */
public interface TransferLink {
    /**
     * One key per collection, to tell a collection transferred before and to go on with an interrupted transfer. It
     * starts with the source's own prefix, so {@link TransferSource#linkOfKey} finds the source again; the keys of
     * Yandex Music have none, as before the other sources came.
     */
    String key();

    TransferSource source();
}
