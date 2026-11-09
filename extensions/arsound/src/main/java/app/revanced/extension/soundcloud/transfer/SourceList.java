package app.revanced.extension.soundcloud.transfer;

/** A list of the signed-in account of a source (its likes or a playlist), as shown before it is read. */
public final class SourceList {
    /** The count is not known before the list is read. */
    public static final int UNKNOWN_COUNT = -1;

    public final TransferLink link;
    public final String title;
    /** How many tracks it holds, or {@link #UNKNOWN_COUNT}. */
    public final int trackCount;
    /** The likes of the account (drawn with another icon), not a playlist. */
    public final boolean likes;

    SourceList(TransferLink link, String title, int trackCount, boolean likes) {
        this.link = link;
        this.title = title;
        this.trackCount = trackCount;
        this.likes = likes;
    }
}
