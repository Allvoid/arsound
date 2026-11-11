package app.revanced.extension.soundcloud.transfer;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Which tracks of a transfer get a like, and in what order. */
final class LikePlan {
    private LikePlan() {
    }

    /**
     * The tracks to like: from the last to the first, because the newest like is on top of the likes, and the list
     * then reads there as it did in Yandex Music. A track already liked is left alone: a like again would move it up
     * and add one to its count.
     */
    static List<String> of(List<String> urns, Set<String> alreadyLiked) {
        return of(urns, alreadyLiked, Integer.MAX_VALUE);
    }

    /**
     * The same, for at most {@code limit} tracks: the first ones of the list that are not liked yet. SoundCloud limits
     * the likes of an account (a long list given at once is taken in part, and the account is then blocked from liking
     * for a while), so a transfer gives a few and leaves the rest in the playlist. The few are the head of the list, and
     * the first of them still ends on top of the likes.
     */
    static List<String> of(List<String> urns, Set<String> alreadyLiked, int limit) {
        List<String> head = new ArrayList<>();
        for (String urn : urns) {
            if (head.size() >= limit) break;
            if (!alreadyLiked.contains(urn)) head.add(urn);
        }
        java.util.Collections.reverse(head);
        return head;
    }
}
