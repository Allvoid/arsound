package app.revanced.extension.soundcloud.search;

import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.arsound.shaded.newpipe.extractor.Image;
import app.arsound.shaded.newpipe.extractor.InfoItem;
import app.arsound.shaded.newpipe.extractor.ListExtractor;
import app.arsound.shaded.newpipe.extractor.Page;
import app.arsound.shaded.newpipe.extractor.channel.ChannelInfo;
import app.arsound.shaded.newpipe.extractor.channel.ChannelInfoItem;
import app.arsound.shaded.newpipe.extractor.channel.tabs.ChannelTabInfo;
import app.arsound.shaded.newpipe.extractor.channel.tabs.ChannelTabs;
import app.arsound.shaded.newpipe.extractor.linkhandler.ListLinkHandler;
import app.arsound.shaded.newpipe.extractor.playlist.PlaylistInfo;
import app.arsound.shaded.newpipe.extractor.playlist.PlaylistInfoItem;
import app.arsound.shaded.newpipe.extractor.NewPipe;
import app.arsound.shaded.newpipe.extractor.ServiceList;
import app.arsound.shaded.newpipe.extractor.StreamingService;
import app.arsound.shaded.newpipe.extractor.downloader.Downloader;
import app.arsound.shaded.newpipe.extractor.downloader.Request;
import app.arsound.shaded.newpipe.extractor.downloader.Response;
import app.arsound.shaded.newpipe.extractor.exceptions.AgeRestrictedContentException;
import app.arsound.shaded.newpipe.extractor.exceptions.SignInConfirmNotBotException;
import app.arsound.shaded.newpipe.extractor.search.SearchInfo;
import app.arsound.shaded.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import app.arsound.shaded.newpipe.extractor.stream.AudioStream;
import app.arsound.shaded.newpipe.extractor.stream.StreamInfo;
import app.arsound.shaded.newpipe.extractor.stream.StreamInfoItem;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.network.StreamText;

/**
 * Search and audio streams from YouTube Music, through NewPipeExtractor.
 * Used for tracks that SoundCloud does not let download. All calls block: run them off the main thread.
 */
public final class OtherSource {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static boolean initialized;

    private OtherSource() {
    }

    public static final class Track {
        public final String url;
        public final String title;
        public final String artist;
        public final long durationSeconds;
        /** The album cover, or null. */
        public final String coverUrl;
        /** The same cover small, for the row of the list, or null. */
        public final String thumbUrl;

        Track(String url, String title, String artist, long durationSeconds, String coverUrl, String thumbUrl) {
            this.url = url;
            this.title = title;
            this.artist = artist;
            this.durationSeconds = durationSeconds;
            this.coverUrl = coverUrl;
            this.thumbUrl = thumbUrl;
        }
    }

    /** The side, in pixels, the pictures of the list rows are asked for in: a 64 dp cell on a dense screen. */
    private static final int THUMB_PX = 226;
    /** The side, in pixels, of the big cover: what the player of YouTube Music shows, and the top of an album page. */
    private static final int COVER_PX = 544;

    /**
     * A picture for a row of the list: YouTube Music sizes a cover or an avatar by its address, so the smallest one
     * that still fills the row is asked for instead of the 544 px one kept for the downloaded file.
     */
    static String thumbUrl(List<Image> images) {
        Image best = null;
        for (Image image : images) {
            if (best == null) {
                best = image;
                continue;
            }
            boolean bigEnough = image.getHeight() >= THUMB_PX;
            boolean bestBigEnough = best.getHeight() >= THUMB_PX;
            // The smallest picture that is big enough; while none is, the biggest.
            if (bigEnough && (!bestBigEnough || image.getHeight() < best.getHeight())
                    || !bigEnough && !bestBigEnough && image.getHeight() > best.getHeight()) {
                best = image;
            }
        }
        return best == null ? null : sized(best.getUrl(), THUMB_PX);
    }

    /**
     * The largest picture, for a place that shows it big: the cover kept in a downloaded file, the top of an album or
     * an artist page. YouTube Music covers and avatars are square and can be asked for in any size: 544 px is what
     * its own player shows. Video thumbnails are 16:9 frames and stay as they are.
     */
    static String coverUrl(List<Image> images) {
        Image best = null;
        for (Image image : images) if (best == null || image.getHeight() > best.getHeight()) best = image;
        return best == null ? null : sized(best.getUrl(), COVER_PX);
    }

    /** The address of a cover or an avatar that Google serves in any size, asked for in the given one. Others stay as they are. */
    private static String sized(String url, int px) {
        String resized = url.contains("googleusercontent.com")
                ? url.replaceAll("=w\\d+-h\\d+[^/]*$", "=w" + px + "-h" + px) : url;
        // Avatars come as "=sN-…" both from ggpht.com and from yt3.googleusercontent.com.
        if (resized.equals(url) && (url.contains("ggpht.com") || url.contains("googleusercontent.com"))) {
            resized = url.replaceAll("=s\\d+", "=s" + px);
        }
        return resized;
    }

    private static synchronized StreamingService service() {
        if (!initialized) {
            NewPipe.init(new HttpDownloader());
            initialized = true;
        }
        return ServiceList.YouTube;
    }

    public static List<Track> search(String query) throws Exception {
        StreamingService service = service();
        List<Track> tracks = new ArrayList<>();
        // Songs of YouTube Music first: they are clean album audio. Plain videos only if there are none.
        for (String filter : new String[]{YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, YoutubeSearchQueryHandlerFactory.VIDEOS}) {
            SearchInfo info = SearchInfo.getInfo(service,
                    service.getSearchQHFactory().fromQuery(query, Collections.singletonList(filter), ""));
            for (InfoItem item : info.getRelatedItems()) {
                if (!(item instanceof StreamInfoItem)) continue;
                StreamInfoItem stream = (StreamInfoItem) item;
                tracks.add(new Track(stream.getUrl(), stream.getName(), artistName(stream.getUploaderName()),
                        stream.getDuration(), coverUrl(stream.getThumbnails()), thumbUrl(stream.getThumbnails())));
            }
            if (!tracks.isEmpty()) break;
        }
        return tracks;
    }

    /**
     * What an album and an artist share: one picture asked for twice. The row of the list shows it small, the top of
     * the page big; the small one stretched to the top of the page would be a blur.
     */
    public abstract static class Pictured {
        /** The big picture for the top of the page, or null. */
        public final String coverUrl;
        /** The same picture small, for the row of the list, or null. */
        public final String thumbUrl;

        Pictured(String coverUrl, String thumbUrl) {
            this.coverUrl = coverUrl;
            this.thumbUrl = thumbUrl;
        }

        /** The picture for the top of the page: the big one, or the small one if that is all there is. */
        public String heroUrl() {
            return coverUrl != null ? coverUrl : thumbUrl;
        }
    }

    public static final class Album extends Pictured {
        public final String url;
        public final String title;
        public final String artist;
        public final long trackCount;

        Album(String url, String title, String artist, long trackCount, String coverUrl, String thumbUrl) {
            super(coverUrl, thumbUrl);
            this.url = url;
            this.title = title;
            this.artist = artist;
            this.trackCount = trackCount;
        }
    }

    public static final class Artist extends Pictured {
        public final String url;
        public final String name;

        Artist(String url, String name, String coverUrl, String thumbUrl) {
            super(coverUrl, thumbUrl);
            this.url = url;
            this.name = name;
        }
    }

    private static List<InfoItem> searchItems(String query, String filter) throws Exception {
        StreamingService service = service();
        return SearchInfo.getInfo(service,
                service.getSearchQHFactory().fromQuery(query, Collections.singletonList(filter), "")).getRelatedItems();
    }

    private static String artistName(String name) {
        if (name == null) return "";
        return name.endsWith(" - Topic") ? name.substring(0, name.length() - 8) : name;
    }

    private static Album toAlbum(InfoItem item, String fallbackArtist) {
        if (!(item instanceof PlaylistInfoItem)) return null;
        PlaylistInfoItem playlist = (PlaylistInfoItem) item;
        String artist = artistName(playlist.getUploaderName());
        return new Album(playlist.getUrl(), playlist.getName(), artist.isEmpty() ? fallbackArtist : artist,
                playlist.getStreamCount(), coverUrl(playlist.getThumbnails()), thumbUrl(playlist.getThumbnails()));
    }

    /** Albums and singles of YouTube Music. */
    public static List<Album> searchAlbums(String query) throws Exception {
        List<Album> albums = new ArrayList<>();
        for (InfoItem item : searchItems(query, YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS)) {
            Album album = toAlbum(item, "");
            if (album != null) albums.add(album);
        }
        return albums;
    }

    public static List<Artist> searchArtists(String query) throws Exception {
        List<Artist> artists = new ArrayList<>();
        for (InfoItem item : searchItems(query, YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS)) {
            if (item instanceof ChannelInfoItem) {
                artists.add(new Artist(item.getUrl(), item.getName(), coverUrl(item.getThumbnails()), thumbUrl(item.getThumbnails())));
            }
        }
        return artists;
    }

    /** Most continuation pages read for one album or artist. */
    private static final int MAX_PAGES = 30;

    /** The tracks of an album, in album order. */
    public static List<Track> albumTracks(Album album) throws Exception {
        StreamingService service = service();
        PlaylistInfo info = PlaylistInfo.getInfo(service, album.url);
        List<StreamInfoItem> items = new ArrayList<>(info.getRelatedItems());
        Page page = info.getNextPage();
        // A page without items ends the loop: a valid continuation that never fills would otherwise spin for ever.
        for (int pages = 0; Page.isValid(page) && items.size() < 500 && pages < MAX_PAGES; pages++) {
            ListExtractor.InfoItemsPage<StreamInfoItem> more = PlaylistInfo.getMoreItems(service, album.url, page);
            if (more.getItems().isEmpty()) break;
            items.addAll(more.getItems());
            page = more.getNextPage();
        }
        List<Track> tracks = new ArrayList<>();
        // Tracks of an album share its cover.
        String cover = coverUrl(info.getThumbnails());
        String thumb = album.thumbUrl != null ? album.thumbUrl : thumbUrl(info.getThumbnails());
        for (StreamInfoItem stream : items) {
            String artist = artistName(stream.getUploaderName());
            tracks.add(new Track(stream.getUrl(), stream.getName(), artist.isEmpty() ? album.artist : artist,
                    stream.getDuration(), cover != null ? cover : coverUrl(stream.getThumbnails()),
                    thumb != null ? thumb : thumbUrl(stream.getThumbnails())));
        }
        return tracks;
    }

    /** Albums and singles of an artist, from the releases tab of the artist's channel. */
    public static List<Album> artistAlbums(Artist artist) throws Exception {
        StreamingService service = service();
        ChannelInfo channel = ChannelInfo.getInfo(service, artist.url);
        List<Album> albums = new ArrayList<>();
        for (ListLinkHandler tab : channel.getTabs()) {
            if (!tab.getContentFilters().contains(ChannelTabs.ALBUMS)) continue;
            ChannelTabInfo info = ChannelTabInfo.getInfo(service, tab);
            List<InfoItem> items = new ArrayList<>(info.getRelatedItems());
            Page page = info.getNextPage();
            for (int pages = 0; Page.isValid(page) && items.size() < 300 && pages < MAX_PAGES; pages++) {
                ListExtractor.InfoItemsPage<InfoItem> more = ChannelTabInfo.getMoreItems(service, tab, page);
                if (more.getItems().isEmpty()) break;
                items.addAll(more.getItems());
                page = more.getNextPage();
            }
            for (InfoItem item : items) {
                Album album = toAlbum(item, artist.name);
                if (album != null) albums.add(album);
            }
        }
        return albums;
    }

    /** The best audio stream that Android stores as .m4a, or the best of any kind. */
    public static AudioStream bestAudio(String url) throws Exception {
        StreamInfo info;
        try {
            info = StreamInfo.getInfo(service(), url);
        } catch (AgeRestrictedContentException ex) {
            // Anonymous requests cannot get age-restricted tracks; a signed-in account can.
            return YouTubeAccount.audio(Uri.parse(url).getQueryParameter("v"));
        } catch (SignInConfirmNotBotException ex) {
            // YouTube blocks the app clients of NewPipe for some networks (VPN, data centre addresses).
            Logger.printInfo(() -> "YouTube asks to confirm this is not a bot, trying the web player: " + ex.getMessage());
            return YouTubeAccount.botCheckedAudio(Uri.parse(url).getQueryParameter("v"));
        }
        AudioStream best = null;
        AudioStream bestM4a = null;
        for (AudioStream stream : info.getAudioStreams()) {
            if (stream.getContent() == null || !stream.isUrl()) continue;
            if (best == null || stream.getAverageBitrate() > best.getAverageBitrate()) best = stream;
            if ("m4a".equals(extensionOf(stream))
                    && (bestM4a == null || stream.getAverageBitrate() > bestM4a.getAverageBitrate())) {
                bestM4a = stream;
            }
        }
        if (bestM4a != null) return bestM4a;
        if (best == null) throw new IOException("No audio streams");
        return best;
    }

    public static String extensionOf(AudioStream stream) {
        String mime = stream.getFormat() == null ? "" : stream.getFormat().getMimeType();
        if (mime.contains("mp4")) return "m4a";
        if (mime.contains("webm")) return "webm";
        return "mp3";
    }

    /**
     * YouTube binds a stream link to the IP address that asked for it. A VPN that sends requests
     * through different addresses gets 403 for the stream; a new link may land on the same address.
     */
    public static final class RefusedException extends IOException {
        RefusedException(String message) {
            super(message);
        }
    }

    public interface Progress {
        void onProgress(int percent);
    }

    /** The file size, which YouTube puts into the stream link. */
    public static long sizeOf(String url) throws IOException {
        Matcher matcher = Pattern.compile("[?&]clen=(\\d+)").matcher(url);
        if (matcher.find()) return Long.parseLong(matcher.group(1));
        throw new IOException("No size in the stream link");
    }

    /**
     * Reads {@code length} bytes from {@code position}. YouTube answers 403 to a stream requested
     * without a range, so every read asks for its part with the {@code range} parameter.
     */
    public static byte[] readPart(String url, long position, int length) throws IOException {
        RegionGuard.throwIfBlockedAnyHost();
        HttpURLConnection connection = (HttpURLConnection) new URL(
                url + "&range=" + position + "-" + (position + length - 1)).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        try {
            int code = connection.getResponseCode();
            if (code != 200 && code != 206) {
                String body = "";
                InputStream error = connection.getErrorStream();
                if (error != null) {
                    byte[] head = new byte[300];
                    int n = error.read(head);
                    if (n > 0) body = new String(head, 0, n, "UTF-8");
                }
                String info = "HTTP " + code + " " + body;
                Logger.printInfo(() -> "Stream part refused: " + info);
                throw code == 403 ? new RefusedException(info) : new IOException(info);
            }
            // A server that ignored the range sends the whole stream: written at the offset of the part, it would
            // put the beginning of the song in its middle. More than the part asked for is refused.
            long declared = connection.getContentLengthLong();
            if (declared > length) throw new IOException("The server sent " + declared + " bytes for a part of " + length);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(length);
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = connection.getInputStream()) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    bytes.write(buffer, 0, read);
                    if (bytes.size() > length) throw new IOException("The server sent more than the part of " + length);
                }
            }
            return bytes.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    /** Downloads a whole stream part by part. */
    public static void download(String url, OutputStream output, Progress progress) throws IOException {
        final int part = 1024 * 1024;
        long total = sizeOf(url);
        long done = 0;
        while (done < total) {
            // A download given up (a batch's time limit, a stop) ends at the next part instead of going on unseen.
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Download stopped");
            byte[] bytes = readPart(url, done, (int) Math.min(part, total - done));
            if (bytes.length == 0) throw new IOException("Empty part at " + done);
            output.write(bytes);
            done += bytes.length;
            progress.onProgress((int) (done * 100 / total));
        }
    }

    /** Lets the Android player read a stream through {@link #readPart}, with the parts it has read kept. */
    public static final class PartSource extends android.media.MediaDataSource {
        private static final int PART = 256 * 1024;
        private final String url;
        private final long size;
        private final Map<Long, byte[]> parts = new LinkedHashMap<Long, byte[]>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                return size() > 24;
            }
        };

        /** Set by {@link #close}: a read still on the network gives up when it comes back. */
        private volatile boolean closed;

        public PartSource(String url) throws IOException {
            this.url = url;
            this.size = sizeOf(url);
        }

        /**
         * Not synchronized across the network: the read of a part waits up to the read timeout, and a stop of the
         * preview (on the main thread) waited for it in close(). The kept parts are guarded on their own.
         */
        @Override
        public int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
            if (closed) throw new IOException("Closed");
            if (position >= size) return -1;
            long index = position / PART;
            byte[] part;
            synchronized (parts) {
                part = parts.get(index);
            }
            if (part == null) {
                long start = index * PART;
                part = readPart(url, start, (int) Math.min(PART, size - start));
                if (closed) throw new IOException("Closed");
                synchronized (parts) {
                    parts.put(index, part);
                }
            }
            int inPart = (int) (position - index * PART);
            int count = Math.min(length, part.length - inPart);
            if (count <= 0) return -1;
            System.arraycopy(part, inPart, buffer, offset, count);
            return count;
        }

        @Override
        public long getSize() {
            return size;
        }

        @Override
        public void close() {
            closed = true;
            synchronized (parts) {
                parts.clear();
            }
        }
    }

    private static final class HttpDownloader extends Downloader {
        @Override
        public Response execute(Request request) throws IOException {
            RegionGuard.throwIfBlockedAnyHost();
            HttpURLConnection connection = (HttpURLConnection) new URL(request.url()).openConnection();
            // Disconnects also when the request or the read of the body fails.
            try {
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestMethod(request.httpMethod());
                connection.setRequestProperty("User-Agent", USER_AGENT);
                for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
                    connection.setRequestProperty(header.getKey(), String.join(", ", header.getValue()));
                }
                byte[] data = request.dataToSend();
                if (data != null) {
                    connection.setDoOutput(true);
                    try (OutputStream output = connection.getOutputStream()) {
                        output.write(data);
                    }
                }
                int code = connection.getResponseCode();
                String body = "";
                try (InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                    if (input != null) body = StreamText.readUtf8(input);
                }
                Map<String, List<String>> headers = new HashMap<>();
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (header.getKey() != null) headers.put(header.getKey(), header.getValue());
                }
                return new Response(code, connection.getResponseMessage(), headers,
                        body, connection.getURL().toString());
            } finally {
                connection.disconnect();
            }
        }
    }
}
