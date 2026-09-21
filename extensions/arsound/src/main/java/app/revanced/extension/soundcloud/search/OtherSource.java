package app.revanced.extension.soundcloud.search;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import app.arsound.shaded.newpipe.extractor.InfoItem;
import app.arsound.shaded.newpipe.extractor.NewPipe;
import app.arsound.shaded.newpipe.extractor.ServiceList;
import app.arsound.shaded.newpipe.extractor.StreamingService;
import app.arsound.shaded.newpipe.extractor.downloader.Downloader;
import app.arsound.shaded.newpipe.extractor.downloader.Request;
import app.arsound.shaded.newpipe.extractor.downloader.Response;
import app.arsound.shaded.newpipe.extractor.search.SearchInfo;
import app.arsound.shaded.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import app.arsound.shaded.newpipe.extractor.stream.AudioStream;
import app.arsound.shaded.newpipe.extractor.stream.StreamInfo;
import app.arsound.shaded.newpipe.extractor.stream.StreamInfoItem;

/**
 * Search and audio streams from YouTube Music, through NewPipeExtractor.
 * Used for tracks that SoundCloud does not let download. All calls block: run them off the main thread.
 */
public final class OtherSource {
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0";
    private static boolean initialized;

    private OtherSource() {
    }

    public static final class Track {
        public final String url;
        public final String title;
        public final String artist;
        public final long durationSeconds;

        Track(String url, String title, String artist, long durationSeconds) {
            this.url = url;
            this.title = title;
            this.artist = artist;
            this.durationSeconds = durationSeconds;
        }
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
                String artist = stream.getUploaderName();
                if (artist != null && artist.endsWith(" - Topic")) artist = artist.substring(0, artist.length() - 8);
                tracks.add(new Track(stream.getUrl(), stream.getName(), artist == null ? "" : artist, stream.getDuration()));
            }
            if (!tracks.isEmpty()) break;
        }
        return tracks;
    }

    /** The best audio stream that Android stores as .m4a, or the best of any kind. */
    public static AudioStream bestAudio(String url) throws Exception {
        StreamInfo info = StreamInfo.getInfo(service(), url);
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
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[?&]clen=(\\d+)").matcher(url);
        if (matcher.find()) return Long.parseLong(matcher.group(1));
        throw new IOException("No size in the stream link");
    }

    /**
     * Reads {@code length} bytes from {@code position}. YouTube answers 403 to a stream requested
     * without a range, so every read asks for its part with the {@code range} parameter.
     */
    public static byte[] readPart(String url, long position, int length) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(
                url + "&range=" + position + "-" + (position + length - 1)).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
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
                app.revanced.extension.shared.Logger.printInfo(() -> "Stream part refused: " + info);
                throw code == 403 ? new RefusedException(info) : new IOException(info);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(length);
            byte[] buffer = new byte[64 * 1024];
            try (InputStream input = connection.getInputStream()) {
                int read;
                while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
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
        private final Map<Long, byte[]> parts = new java.util.LinkedHashMap<Long, byte[]>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
                return size() > 24;
            }
        };

        public PartSource(String url) throws IOException {
            this.url = url;
            this.size = sizeOf(url);
        }

        @Override
        public synchronized int readAt(long position, byte[] buffer, int offset, int length) throws IOException {
            if (position >= size) return -1;
            long index = position / PART;
            byte[] part = parts.get(index);
            if (part == null) {
                long start = index * PART;
                part = readPart(url, start, (int) Math.min(PART, size - start));
                parts.put(index, part);
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
        public synchronized void close() {
            parts.clear();
        }
    }

    private static final class HttpDownloader extends Downloader {
        @Override
        public Response execute(Request request) throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(request.url()).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
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
            InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = "";
            if (input != null) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) bytes.write(buffer, 0, read);
                input.close();
                body = bytes.toString("UTF-8");
            }
            Map<String, List<String>> headers = new java.util.HashMap<>();
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() != null) headers.put(header.getKey(), header.getValue());
            }
            Response response = new Response(code, connection.getResponseMessage(), headers,
                    body, connection.getURL().toString());
            connection.disconnect();
            return response;
        }
    }
}
