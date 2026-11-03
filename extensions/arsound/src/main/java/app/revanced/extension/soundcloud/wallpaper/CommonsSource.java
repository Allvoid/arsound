package app.revanced.extension.soundcloud.wallpaper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;

import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.shared.Lang;

/**
 * Wikimedia Commons as a wallpaper source: the free picture library of Wikipedia, searched through the MediaWiki Action
 * API of commons.wikimedia.org. No key and no account are needed; the service asks callers to name themselves
 * ({@link WallpaperHttp#userAgent}) and to be polite, and its limits are per IP address.
 * <p>
 * Only pictures the community marked as featured or as quality images are offered, because Commons has no safe search.
 * The request and the answer are plain text handled by {@link CommonsRequest} and {@link CommonsResponse}; this class adds
 * the pace (a call a second), the region guard, and at most two pictures read at once.
 */
public final class CommonsSource implements WallpaperSource {
    public static final CommonsSource INSTANCE = new CommonsSource();

    /** A call of the API at most this often. */
    private static final long REQUEST_GAP_MS = 1_000;
    /** The API answers with a page of 30 files and their metadata: about 100 KB; far below this. */
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    /** Pictures read at once from the Wikimedia servers, thumbnails and downloads together. */
    private static final int PARALLEL_IMAGES = 2;

    private final RequestPacer pacer = new RequestPacer(REQUEST_GAP_MS);
    private final Semaphore gate = new Semaphore(PARALLEL_IMAGES, true);

    private CommonsSource() {
    }

    @Override
    public String id() {
        return "commons";
    }

    @Override
    public String name() {
        return "Wikimedia Commons";
    }

    @Override
    public boolean curatedOnly() {
        return true;
    }

    @Override
    public int activeFilters(Wallhaven.Query query) {
        return CommonsRules.activeFilters(query.portrait);
    }

    @Override
    public boolean ownsHost(String address) {
        return CommonsRules.isImageHost(address);
    }

    @Override
    public Semaphore imageGate() {
        return gate;
    }

    @Override
    public Page search(Wallhaven.Query query, int page) throws IOException {
        RegionGuard.throwIfBlockedAnyHost();
        WallhavenRules.Shape shape = WallhavenRules.shape(query.portrait, query.widened);
        String address = CommonsRequest.API + "?" + CommonsRequest.params(query.text, query.commonsSet, shape, page);
        pacer.pause();
        return CommonsResponse.parse(WallpaperHttp.readText(name(), address, MAX_JSON_BYTES), shape, page, this);
    }

    @Override
    public List<Line> lines(WallpaperItem item) {
        List<Line> lines = new ArrayList<>();
        // The credit Commons asks for: who made it, under which licence, and where it is from. A tap opens the file's page.
        lines.add(new Line(CommonsRules.creditLine(item.author, item.licence, Lang.russian()),
                item.pageUrl.isEmpty() ? null : item.pageUrl));
        return lines;
    }
}
