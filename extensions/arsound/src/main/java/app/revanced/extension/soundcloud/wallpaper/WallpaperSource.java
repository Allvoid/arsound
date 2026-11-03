package app.revanced.extension.soundcloud.wallpaper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

/**
 * A place wallpapers can be browsed on: Wallhaven ({@link Wallhaven#SOURCE}) or Wikimedia Commons
 * ({@link CommonsSource#INSTANCE}). The catalogue screen, the grid, the download and the preview talk only to this
 * interface and to the pictures it hands out ({@link WallpaperItem}, which carries the source it came from), so a new
 * source is one class that implements this, with nothing copied from the others.
 * <p>
 * What a source must guarantee: it fetches only from its own hosts over https ({@link #ownsHost}), it keeps its pace
 * under the limit of its service, and nothing it does throws into SoundCloud.
 */
public interface WallpaperSource {
    /** A short name that never changes, saved in the settings: {@code wallhaven} or {@code commons}. */
    String id();

    /** The name the user reads, also on the chip of the source switch. */
    String name();

    /**
     * True when this source offers only chosen sets of pictures (Commons: featured, quality) and has none of Wallhaven's
     * extras: no categories, no 18+ mode, no API key, no choice of sorting. The screen then shows the sets instead.
     */
    boolean curatedOnly();

    /**
     * Searches one page. Blocks: call it off the main thread.
     *
     * @param query The search; its {@code text} is already put into English.
     * @param page  The page, from 1.
     * @throws Failure For an answer that is not a good one.
     */
    Page search(Wallhaven.Query query, int page) throws IOException;

    /** How many filters of this search differ from the usual, for the number on the filter button. */
    int activeFilters(Wallhaven.Query query);

    /** True for an address on this source's own hosts, over https. Nothing else is ever fetched for it. */
    boolean ownsHost(String address);

    /**
     * The lines the preview shows under the size of a picture: what is known of it and where it came from, with the
     * address a tap on the line opens (Wallhaven: the picture's page; Commons: the author and the licence, which open
     * the file's description page). Main thread.
     */
    List<Line> lines(WallpaperItem item);

    /**
     * Limits how many pictures are read from this source at once, or null for no limit. Every read takes a permit
     * for as long as it runs.
     */
    default Semaphore imageGate() {
        return null;
    }

    /** One page of the results. */
    final class Page {
        public final List<WallpaperItem> items = new ArrayList<>();
        public int page;
        /** The last page known; a source that cannot tell says the page after this one while there is more. */
        public int lastPage;
        public int total;
        public String seed = "";
    }

    /** A line of the preview and the address it opens, or null for a plain line. */
    final class Line {
        public final String text;
        public final String link;

        public Line(String text, String link) {
            this.text = text;
            this.link = link;
        }
    }

    /** The service refused or could not answer. */
    final class Failure extends IOException {
        public final int code;
        /** For an answer 429: how many seconds the service asked to wait, else 0. */
        public final int retryAfter;

        Failure(String service, int code, int retryAfter) {
            super(service + " HTTP " + code);
            this.code = code;
            this.retryAfter = retryAfter;
        }
    }
}
