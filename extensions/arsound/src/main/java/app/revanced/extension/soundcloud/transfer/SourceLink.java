package app.revanced.extension.soundcloud.transfer;

/**
 * A collection of VK or YouTube Music: its kind and up to three parts that name it. The key is
 * "prefix:KIND:owner:id:accessKey", empty parts left empty. Pure: no network and no Android.
 */
final class SourceLink implements TransferLink {
    private final TransferSource source;
    private final String prefix;
    final String kind;
    /** The owner (a VK user or community number), or null. */
    final String owner;
    /** The playlist's number or id, or null for the whole list of an owner. */
    final String id;
    /** VK's access key of a playlist shared by link, or null. */
    final String accessKey;

    SourceLink(TransferSource source, String prefix, String kind, String owner, String id, String accessKey) {
        this.source = source;
        this.prefix = prefix;
        this.kind = kind;
        this.owner = empty(owner) ? null : owner;
        this.id = empty(id) ? null : id;
        this.accessKey = empty(accessKey) ? null : accessKey;
    }

    /** The link of a key with this prefix, or null. */
    static SourceLink fromKey(TransferSource source, String prefix, String key) {
        if (key == null || !key.startsWith(prefix + ":")) return null;
        String[] parts = key.split(":", -1);
        if (parts.length != 5 || parts[1].isEmpty()) return null;
        return new SourceLink(source, prefix, parts[1], parts[2], parts[3], parts[4]);
    }

    @Override
    public String key() {
        return prefix + ":" + kind + ":" + part(owner) + ":" + part(id) + ":" + part(accessKey);
    }

    @Override
    public TransferSource source() {
        return source;
    }

    private static boolean empty(String value) {
        return value == null || value.isEmpty();
    }

    private static String part(String value) {
        return value == null ? "" : value;
    }

    @Override
    public String toString() {
        return key();
    }
}
