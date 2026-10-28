package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

import app.revanced.extension.soundcloud.shared.AtomicFiles;

/**
 * The recognised texts on the phone, one file per track and model, in the app's own files folder (not the cache: the
 * system may empty a cache when space runs short, and recognising a song costs minutes of work).
 * <p>
 * A file holds a finished transcript, or a partial one that the next try continues. The oldest are removed when
 * there are more than {@value #MAX_FILES} or they take more than 12 MB. No Android classes, so it is tested on the JVM.
 * Used by {@link TranscriptionManager} and {@link TranscriptionJob}.
 */
final class TranscriptStore {
    private static final int MAX_FILES = 2000;
    private static final long MAX_BYTES = 12L * 1024 * 1024;
    /** A name longer than this is replaced by a hash: the urn of an imported file carries its whole path. */
    private static final int MAX_NAME = 80;

    private final File directory;
    private int writesSincePrune;

    TranscriptStore(File directory) {
        this.directory = directory;
    }

    /** The transcript of a track made with a model, in any state; null if there is none or the file is no good. */
    synchronized Transcript read(String urn, String modelId) {
        return readFile(file(urn, modelId));
    }

    /**
     * The finished transcript to show for a track: the one of the preferred model if there is one, else one of
     * another model that hears at least as well (the text of the light model does not stand in for the accurate one).
     * A transcript made for a track of another length (uploaded again under the same urn) does not count.
     */
    synchronized Transcript best(String urn, String preferredModelId, long durationMs) {
        Transcript preferred = read(urn, preferredModelId);
        if (isUsable(preferred, durationMs)) return preferred;
        AsrModel preferredModel = AsrModel.fromId(preferredModelId);
        for (AsrModel model : AsrModel.values()) {
            if (model.id.equals(preferredModelId)) continue;
            if (preferredModel != null && model.quality() < preferredModel.quality()) continue;
            Transcript other = read(urn, model.id);
            if (isUsable(other, durationMs)) return other;
        }
        return null;
    }

    private static boolean isUsable(Transcript transcript, long durationMs) {
        return transcript != null && transcript.isDone() && transcript.lines.length > 0
                && Math.abs(transcript.durationMs - durationMs) <= 2000;
    }

    synchronized void write(Transcript transcript) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No folder " + directory);
        File target = file(transcript.urn, transcript.modelId);
        AtomicFiles.write(new File(directory, target.getName() + ".tmp"), target,
                TranscriptCodec.encode(transcript).getBytes(StandardCharsets.UTF_8));
        if (++writesSincePrune >= 25) {
            writesSincePrune = 0;
            prune();
        }
    }

    synchronized void delete(String urn, String modelId) {
        //noinspection ResultOfMethodCallIgnored
        file(urn, modelId).delete();
    }

    /** How many finished transcripts are stored (those with words). */
    synchronized int doneCount() {
        int count = 0;
        File[] files = directory.listFiles();
        if (files == null) return 0;
        for (File file : files) {
            Transcript transcript = readFile(file);
            if (transcript != null && transcript.isDone()) count++;
        }
        return count;
    }

    /** Space everything here takes, in bytes. */
    synchronized long size() {
        long total = 0;
        File[] files = directory.listFiles();
        if (files == null) return 0;
        for (File file : files) total += file.length();
        return total;
    }

    synchronized void clear() {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private void prune() {
        File[] files = directory.listFiles();
        if (files == null) return;
        long total = 0;
        for (File file : files) total += file.length();
        if (files.length <= MAX_FILES && total <= MAX_BYTES) return;
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        int count = files.length;
        for (File file : files) {
            if (count <= MAX_FILES * 9 / 10 && total <= MAX_BYTES * 9 / 10) break;
            total -= file.length();
            count--;
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    File file(String urn, String modelId) {
        return new File(directory, safeName(urn) + "." + modelId + ".txt");
    }

    /** The urn with every character that a file name does not like turned into an underscore; a long one is hashed. */
    static String safeName(String urn) {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < urn.length(); i++) {
            char c = urn.charAt(i);
            name.append(c < 128 && (Character.isLetterOrDigit(c) || c == '-' || c == '_') ? c : '_');
        }
        if (name.length() <= MAX_NAME) return name.toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(urn.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder("h");
            for (byte b : digest) hex.append(String.format("%02x", b & 0xFF));
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            return name.substring(0, MAX_NAME);
        }
    }

    private static Transcript readFile(File file) {
        if (!file.isFile()) return null;
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            return TranscriptCodec.decode(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            return null;
        }
    }
}
