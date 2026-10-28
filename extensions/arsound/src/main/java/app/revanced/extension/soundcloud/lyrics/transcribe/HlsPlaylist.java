package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an HLS playlist (the list of pieces a streamed track is served in), with one strict rule: only pieces in the
 * clear or locked by AES-128 with a key that is an ordinary HTTPS address count. A key of any other kind (FairPlay,
 * Widevine, SAMPLE-AES, a key format of its own) makes the whole playlist unusable, because such a track is
 * protected and must not be listened to.
 * <p>
 * Pure text work; the pieces themselves are fetched by {@link HlsFetch}.
 */
final class HlsPlaylist {
    /** The playlist asks for something this code does not do, or for something that must not be done. */
    static final class Unsupported extends IOException {
        Unsupported(String message) {
            super(message);
        }
    }

    /** One piece of the track. */
    static final class Segment {
        final String url;
        /** AES key address, or null when the piece is in the clear. */
        final String keyUrl;
        /** The 16 bytes of the IV, or null when the sequence number of the piece makes it. */
        final byte[] iv;
        final boolean counterMode;
        final long sequence;
        /** The header of a fragmented MP4, which is never encrypted. */
        final boolean isInit;

        Segment(String url, String keyUrl, byte[] iv, boolean counterMode, long sequence, boolean isInit) {
            this.url = url;
            this.keyUrl = keyUrl;
            this.iv = iv;
            this.counterMode = counterMode;
            this.sequence = sequence;
            this.isInit = isInit;
        }
    }

    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)");

    /** For a master playlist: the address of the variant with the highest bandwidth. Null for a playlist of pieces. */
    final String variantUrl;
    final List<Segment> segments;

    private HlsPlaylist(String variantUrl, List<Segment> segments) {
        this.variantUrl = variantUrl;
        this.segments = segments;
    }

    /**
     * @param base The address the playlist was loaded from; relative addresses inside it are resolved against it.
     * @throws Unsupported For a key that marks the track as protected, and for anything not understood.
     */
    static HlsPlaylist parse(String text, String base) throws IOException {
        List<Segment> segments = new ArrayList<>();
        String keyUrl = null;
        byte[] iv = null;
        boolean counter = false;
        long sequence = 0;
        String variant = null;
        long variantBandwidth = -1;
        long pendingBandwidth = -1;

        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-SESSION-KEY:")) {
                String attributes = line.substring(line.indexOf(':') + 1);
                String method = "NONE";
                String format = "identity";
                String uri = null;
                byte[] ivValue = null;
                Matcher matcher = ATTRIBUTE.matcher(attributes);
                while (matcher.find()) {
                    String value = matcher.group(2).replace("\"", "").trim();
                    switch (matcher.group(1)) {
                        case "METHOD": method = value; break;
                        case "KEYFORMAT": format = value; break;
                        case "URI": uri = absolute(base, value); break;
                        case "IV": ivValue = parseHex(value); break;
                        default: break;
                    }
                }
                if (line.startsWith("#EXT-X-SESSION-KEY:")) {
                    // Not used for fetching, but a protected master playlist announces its protection here.
                    requireOpen(method, format, uri);
                    continue;
                }
                requireOpen(method, format, uri);
                keyUrl = "NONE".equals(method) ? null : uri;
                counter = method.contains("CTR");
                iv = ivValue;
            } else if (line.startsWith("#EXT-X-STREAM-INF:")) {
                pendingBandwidth = 0;
                Matcher matcher = ATTRIBUTE.matcher(line.substring(line.indexOf(':') + 1));
                while (matcher.find()) {
                    if ("BANDWIDTH".equals(matcher.group(1))) {
                        try {
                            pendingBandwidth = Long.parseLong(matcher.group(2).replace("\"", "").trim());
                        } catch (NumberFormatException ex) {
                            pendingBandwidth = 0;
                        }
                    }
                }
            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                try {
                    sequence = Long.parseLong(line.substring(line.indexOf(':') + 1).trim());
                } catch (NumberFormatException ex) {
                    // The default IV is made from this number: a guess would give noise.
                    throw new Unsupported("The playlist has an unreadable media sequence");
                }
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Matcher matcher = ATTRIBUTE.matcher(line.substring(line.indexOf(':') + 1));
                while (matcher.find()) {
                    if ("URI".equals(matcher.group(1))) {
                        segments.add(new Segment(absolute(base, matcher.group(2).replace("\"", "").trim()),
                                null, null, false, -1, true));
                    }
                }
            } else if (!line.startsWith("#")) {
                if (pendingBandwidth >= 0) {
                    if (pendingBandwidth > variantBandwidth) {
                        variant = absolute(base, line);
                        variantBandwidth = pendingBandwidth;
                    }
                    pendingBandwidth = -1;
                } else {
                    segments.add(new Segment(absolute(base, line), keyUrl, iv, counter, sequence++, false));
                }
            }
        }
        return new HlsPlaylist(segments.isEmpty() ? variant : null, segments);
    }

    /** Nothing but clear pieces and plain AES-128 (or AES-CTR) with a key at an HTTPS address is let through. */
    private static void requireOpen(String method, String format, String uri) throws Unsupported {
        if ("NONE".equals(method)) return;
        if (!"identity".equals(format)) throw new Unsupported("Protected stream: key format " + format);
        if (!"AES-128".equals(method) && !"AES-CTR".equals(method)) throw new Unsupported("Protected stream: method " + method);
        if (uri == null || !uri.startsWith("https://")) throw new Unsupported("Protected stream: the key is not an HTTPS address");
    }

    private static byte[] parseHex(String value) throws Unsupported {
        String digits = value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        if (digits.length() != 32) throw new Unsupported("The IV is not 16 bytes");
        byte[] bytes = new byte[16];
        try {
            for (int i = 0; i < 16; i++) bytes[i] = (byte) Integer.parseInt(digits.substring(i * 2, i * 2 + 2), 16);
        } catch (NumberFormatException ex) {
            throw new Unsupported("The IV is not hexadecimal");
        }
        return bytes;
    }

    private static String absolute(String base, String reference) {
        try {
            return new URL(new URL(base), reference).toString();
        } catch (java.net.MalformedURLException ex) {
            return reference;
        }
    }
}
