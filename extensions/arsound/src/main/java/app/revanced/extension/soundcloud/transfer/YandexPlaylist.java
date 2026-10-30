package app.revanced.extension.soundcloud.transfer;

/** A list of the signed-in Yandex account (its likes or a playlist), as shown before it is read. */
public final class YandexPlaylist {
    public final YandexLink link;
    public final String title;
    public final int trackCount;

    YandexPlaylist(YandexLink link, String title, int trackCount) {
        this.link = link;
        this.title = title;
        this.trackCount = trackCount;
    }
}
