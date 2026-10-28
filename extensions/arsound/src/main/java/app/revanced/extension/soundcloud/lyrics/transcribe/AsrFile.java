package app.revanced.extension.soundcloud.lyrics.transcribe;

/** One file to download: where it comes from, what it is called on the phone, how big it is and its SHA-256. */
final class AsrFile {
    final String name;
    final String url;
    final long size;
    /** Lower-case hex of the SHA-256 the finished file must have; written in the code, never read from the server. */
    final String sha256;

    AsrFile(String name, String url, long size, String sha256) {
        this.name = name;
        this.url = url;
        this.size = size;
        this.sha256 = sha256;
    }
}
