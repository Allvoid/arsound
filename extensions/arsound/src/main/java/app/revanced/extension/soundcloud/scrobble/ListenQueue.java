package app.revanced.extension.soundcloud.scrobble;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The listens one service has not taken yet. At most {@link #MAX} wait (the oldest go first); the queue belongs to one
 * account (a digest of its token or session key), so listens made under one account are never sent to another. Kept on
 * the disk as JSON so that a session offline loses nothing. Pure: no Android, no clock. Not thread safe.
 */
final class ListenQueue {
    static final int MAX = 100;

    private final Deque<ScrobbleListen> listens = new ArrayDeque<>();
    /** Who the waiting listens belong to; "" when none wait. */
    private String owner = "";

    int size() {
        return listens.size();
    }

    boolean isEmpty() {
        return listens.isEmpty();
    }

    String owner() {
        return owner;
    }

    ScrobbleListen peek() {
        return listens.peekFirst();
    }

    /** Adds a finished listen made under {@code ownerDigest}; what waited under another account is let go first. */
    void add(ScrobbleListen listen, String ownerDigest) {
        // The account changed and the flush that clears the old listens has not run yet: the new listen must not be lost with them.
        if (!listens.isEmpty() && !owner.equals(ownerDigest)) listens.clear();
        owner = ownerDigest;
        listens.addLast(listen);
        while (listens.size() > MAX) listens.removeFirst();
    }

    /** Removes the front listen if it is {@code sent} (the queue may have been cleared while it was on its way). */
    boolean removeFront(ScrobbleListen sent) {
        ScrobbleListen front = listens.peekFirst();
        if (front == null || !front.same(sent)) return false;
        listens.removeFirst();
        if (listens.isEmpty()) owner = "";
        return true;
    }

    void clear() {
        listens.clear();
        owner = "";
    }

    /** Forgets what waits when it was not made under this account. @return True if something was let go. */
    boolean keepOnlyFor(String ownerDigest) {
        if (!listens.isEmpty() && !owner.equals(ownerDigest)) {
            clear();
            return true;
        }
        return false;
    }

    /** Drops listens started before {@code oldestSeconds} (Last.fm refuses what is older than two weeks). @return True if any was dropped. */
    boolean dropOlderThan(long oldestSeconds) {
        boolean dropped = listens.removeIf(listen -> listen.listenedAt < oldestSeconds);
        if (listens.isEmpty()) owner = "";
        return dropped;
    }

    /** The queue as JSON: {@code {"o": owner, "l": [listens]}}. */
    JSONObject toJson() {
        JSONArray list = new JSONArray();
        for (ScrobbleListen listen : listens) list.put(listen.toJson());
        try {
            return new JSONObject().put("o", owner).put("l", list);
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Reads a queue saved by {@link #toJson}; a damaged entry is skipped, damaged text gives an empty queue. */
    static ListenQueue fromJson(String text) {
        ListenQueue queue = new ListenQueue();
        if (text == null || text.isEmpty()) return queue;
        try {
            JSONObject object = new JSONObject(text);
            JSONArray list = object.optJSONArray("l");
            if (list == null) return queue;
            List<ScrobbleListen> read = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                ScrobbleListen listen = ScrobbleListen.fromJson(list.optJSONObject(i));
                if (listen != null) read.add(listen);
            }
            for (ScrobbleListen listen : read) queue.listens.addLast(listen);
            while (queue.listens.size() > MAX) queue.listens.removeFirst();
            if (!queue.listens.isEmpty()) queue.owner = object.optString("o", "");
        } catch (JSONException ex) {
            return new ListenQueue();
        }
        return queue;
    }

    /** A digest of the credential, to tell whose listens wait without keeping the credential in the queue. */
    static String ownerOf(String credential) {
        if (credential == null || credential.isEmpty()) return "";
        return Md5.hex("arsound-scrobble:" + credential);
    }

    /** MD5 as lower-case hex, the form Last.fm signatures and the owner digest use. */
    static final class Md5 {
        private Md5() {
        }

        static String hex(String text) {
            try {
                byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
                StringBuilder out = new StringBuilder();
                for (byte b : digest) {
                    out.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
                }
                return out.toString();
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }
}
