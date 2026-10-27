package app.revanced.extension.soundcloud.download;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

/**
 * Writes the title and artist of a track into the file itself, as the tags music players read.
 * <p>
 * A file in the media store is tagged through its row ({@link MediaTags}). A file in a folder the user picked has no
 * row the app may change, and a player that lists such a folder would show the file names only. So the tags go into
 * the bytes that are copied there: an ID3 tag in front of an mp3, and an "ilst" box inside the "moov" box of an
 * m4a. Only a file whose layout is understood is touched; every other file, and every doubt, means the file is
 * copied as it is.
 */
final class FileTags {
    private static final int MOOV = code("moov");
    private static final int MOOF = code("moof");
    private static final int MVEX = code("mvex");
    private static final int UDTA = code("udta");
    private static final int TRAK = code("trak");
    private static final int MDIA = code("mdia");
    private static final int MINF = code("minf");
    private static final int STBL = code("stbl");
    private static final int STCO = code("stco");
    private static final int CO64 = code("co64");
    /** The "(c)nam" and "(c)ART" items of an iTunes style tag list. */
    private static final int ITEM_TITLE = 0xA96E616D;
    private static final int ITEM_ARTIST = 0xA9415254;
    /** A tag is for a title and a name, not for a text: the longest piece kept. */
    private static final int MAX_CHARS = 500;
    private static final long MAX_MOOV_BYTES = 32L * 1024 * 1024;

    private FileTags() {
    }

    /** A copy of a file with tags in it, ready to be written. */
    interface Tagged {
        void writeTo(OutputStream output) throws IOException;
    }

    /**
     * @param fileName The name the file is saved under; its extension tells the format.
     * @return The tagged copy, or null if there is nothing to write or the file is of a kind that is not tagged here
     * (the caller copies it as it is).
     * @throws IOException If the file cannot be read; the caller then copies it as it is too.
     */
    static Tagged plan(File source, String fileName, String title, String artist) throws IOException {
        String cleanTitle = clean(title);
        String cleanArtist = clean(artist);
        if (cleanTitle.isEmpty() && cleanArtist.isEmpty()) return null;
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3")) return planId3(source, cleanTitle, cleanArtist);
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) return planMp4(source, cleanTitle, cleanArtist);
        return null;
    }

    private static String clean(String text) {
        if (text == null) return "";
        String cleaned = text.replaceAll("[\\u0000-\\u001F\\u007F]", " ").trim();
        if (cleaned.length() > MAX_CHARS) cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(0, MAX_CHARS)).trim();
        return cleaned;
    }

    // region mp3

    private static Tagged planId3(File source, String title, String artist) throws IOException {
        try (InputStream input = new FileInputStream(source)) {
            byte[] head = new byte[3];
            // A tag the uploader wrote is theirs: it stays.
            if (input.read(head) == 3 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') return null;
        }
        byte[] header = id3Header(title, artist);
        if (header == null) return null;
        return output -> {
            output.write(header);
            copyRange(source, 0, source.length(), output);
        };
    }

    /**
     * The tag to put in front of an mp3 that has none, for a file that is written as it arrives and cannot be planned
     * as a whole.
     *
     * @return The bytes of the tag, or null if there is nothing to write.
     */
    static byte[] id3Header(String title, String artist) {
        String cleanTitle = clean(title);
        String cleanArtist = clean(artist);
        if (cleanTitle.isEmpty() && cleanArtist.isEmpty()) return null;
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        if (!cleanTitle.isEmpty()) id3Frame(frames, "TIT2", cleanTitle);
        if (!cleanArtist.isEmpty()) id3Frame(frames, "TPE1", cleanArtist);
        byte[] body = frames.toByteArray();
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        // ID3v2.3, the version every player reads; its text is UTF-16 with a byte order mark.
        tag.write(new byte[]{'I', 'D', '3', 3, 0, 0}, 0, 6);
        int size = body.length;
        tag.write(new byte[]{(byte) ((size >> 21) & 0x7F), (byte) ((size >> 14) & 0x7F), (byte) ((size >> 7) & 0x7F),
                (byte) (size & 0x7F)}, 0, 4);
        tag.write(body, 0, body.length);
        return tag.toByteArray();
    }

    private static void id3Frame(ByteArrayOutputStream frames, String id, String text) {
        byte[] characters = text.getBytes(StandardCharsets.UTF_16LE);
        int size = 1 + 2 + characters.length;
        byte[] header = {(byte) id.charAt(0), (byte) id.charAt(1), (byte) id.charAt(2), (byte) id.charAt(3),
                (byte) (size >>> 24), (byte) (size >>> 16), (byte) (size >>> 8), (byte) size, 0, 0};
        frames.write(header, 0, header.length);
        // Encoding 1: UTF-16 with a byte order mark.
        frames.write(1);
        frames.write(0xFF);
        frames.write(0xFE);
        frames.write(characters, 0, characters.length);
    }

    // endregion

    // region m4a

    private static Tagged planMp4(File source, String title, String artist) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(source, "r")) {
            long length = file.length();
            long moovStart = -1;
            long moovSize = -1;
            long position = 0;
            while (position + 8 <= length) {
                file.seek(position);
                long size = file.readInt() & 0xFFFFFFFFL;
                int type = file.readInt();
                long header = 8;
                if (size == 1) {
                    size = file.readLong();
                    header = 16;
                } else if (size == 0) {
                    size = length - position;
                }
                // A box that does not fit the file: the file is damaged, and is not touched.
                if (size < header || position + size > length) return null;
                // A file in fragments keeps its offsets in every fragment, which are not followed here.
                if (type == MOOF) return null;
                if (type == MOOV) {
                    if (header != 8 || moovStart >= 0) return null;
                    moovStart = position;
                    moovSize = size;
                }
                position += size;
            }
            if (moovStart < 0 || moovSize > MAX_MOOV_BYTES) return null;

            byte[] moov = new byte[(int) moovSize];
            file.seek(moovStart);
            file.readFully(moov);
            // A tag list that is there is not duplicated, and a movie in fragments has none of the offsets patched below.
            if (hasChild(moov, MVEX) || hasChild(moov, UDTA)) return null;

            byte[] udta = udta(title, artist);
            byte[] patched = Arrays.copyOf(moov, moov.length + udta.length);
            System.arraycopy(udta, 0, patched, moov.length, udta.length);
            putInt(patched, 0, patched.length);
            // The new box grows the "moov" box: whatever comes after it moves, and the offsets of the samples with it.
            long moovEnd = moovStart + moovSize;
            if (!shiftOffsets(patched, 8, moov.length, moovEnd, udta.length)) return null;

            long start = moovStart;
            return output -> {
                copyRange(source, 0, start, output);
                output.write(patched);
                copyRange(source, moovEnd, length, output);
            };
        }
    }

    private static boolean hasChild(byte[] moov, int type) {
        int at = 8;
        while (at + 8 <= moov.length) {
            long size = getInt(moov, at) & 0xFFFFFFFFL;
            if (size < 8 || at + size > moov.length) return false;
            if (getInt(moov, at + 4) == type) return true;
            at += (int) size;
        }
        return false;
    }

    /**
     * Adds {@code delta} to every sample offset that points behind {@code moovEnd}.
     *
     * @return False if a box is not what it should be, or an offset no longer fits (then the file is left alone).
     */
    private static boolean shiftOffsets(byte[] data, int from, int to, long moovEnd, int delta) {
        int at = from;
        while (at + 8 <= to) {
            long size = getInt(data, at) & 0xFFFFFFFFL;
            if (size < 8 || at + size > to) return false;
            int end = (int) (at + size);
            int type = getInt(data, at + 4);
            if (type == TRAK || type == MDIA || type == MINF || type == STBL) {
                if (!shiftOffsets(data, at + 8, end, moovEnd, delta)) return false;
            } else if (type == STCO || type == CO64) {
                boolean wide = type == CO64;
                int step = wide ? 8 : 4;
                if (end - at < 16) return false;
                long count = getInt(data, at + 12) & 0xFFFFFFFFL;
                if (at + 16 + count * step > end) return false;
                for (int i = 0; i < count; i++) {
                    int entry = at + 16 + i * step;
                    long offset = wide ? getLong(data, entry) : getInt(data, entry) & 0xFFFFFFFFL;
                    if (offset < moovEnd) continue;
                    offset += delta;
                    if (wide) {
                        putLong(data, entry, offset);
                    } else {
                        if (offset > 0xFFFFFFFFL) return false;
                        putInt(data, entry, (int) offset);
                    }
                }
            }
            at = end;
        }
        return at == to;
    }

    /** {@code udta > meta > hdlr, ilst}: the layout iTunes and every player after it read. */
    private static byte[] udta(String title, String artist) {
        ByteArrayOutputStream items = new ByteArrayOutputStream();
        if (!title.isEmpty()) writeItem(items, ITEM_TITLE, title);
        if (!artist.isEmpty()) writeItem(items, ITEM_ARTIST, artist);
        byte[] ilst = box(code("ilst"), items.toByteArray());

        ByteArrayOutputStream handler = new ByteArrayOutputStream();
        // Version and flags, predefined, the handler "mdir", and what is reserved (iTunes writes "appl" there).
        byte[] fixed = {0, 0, 0, 0, 0, 0, 0, 0, 'm', 'd', 'i', 'r', 'a', 'p', 'p', 'l', 0, 0, 0, 0, 0, 0, 0, 0, 0};
        handler.write(fixed, 0, fixed.length);
        byte[] hdlr = box(code("hdlr"), handler.toByteArray());

        ByteArrayOutputStream meta = new ByteArrayOutputStream();
        meta.write(new byte[]{0, 0, 0, 0}, 0, 4);
        meta.write(hdlr, 0, hdlr.length);
        meta.write(ilst, 0, ilst.length);
        return box(UDTA, box(code("meta"), meta.toByteArray()));
    }

    private static void writeItem(ByteArrayOutputStream items, int type, String text) {
        byte[] value = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        // The type of the value (1: UTF-8 text) and the locale.
        data.write(new byte[]{0, 0, 0, 1, 0, 0, 0, 0}, 0, 8);
        data.write(value, 0, value.length);
        byte[] item = box(type, box(code("data"), data.toByteArray()));
        items.write(item, 0, item.length);
    }

    private static byte[] box(int type, byte[] content) {
        byte[] result = new byte[8 + content.length];
        putInt(result, 0, result.length);
        putInt(result, 4, type);
        System.arraycopy(content, 0, result, 8, content.length);
        return result;
    }

    // endregion

    private static int code(String type) {
        return (type.charAt(0) << 24) | (type.charAt(1) << 16) | (type.charAt(2) << 8) | type.charAt(3);
    }

    private static int getInt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16) | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    private static long getLong(byte[] data, int at) {
        return ((long) getInt(data, at) << 32) | (getInt(data, at + 4) & 0xFFFFFFFFL);
    }

    private static void putInt(byte[] data, int at, int value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }

    private static void putLong(byte[] data, int at, long value) {
        putInt(data, at, (int) (value >>> 32));
        putInt(data, at + 4, (int) value);
    }

    /** Copies the bytes {@code from} up to {@code to} of a file. */
    private static void copyRange(File source, long from, long to, OutputStream output) throws IOException {
        try (FileInputStream input = new FileInputStream(source)) {
            input.getChannel().position(from);
            byte[] buffer = new byte[64 * 1024];
            long left = to - from;
            while (left > 0) {
                int read = input.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (read < 0) throw new IOException("The file ended " + left + " bytes early");
                output.write(buffer, 0, read);
                left -= read;
            }
        }
    }
}
