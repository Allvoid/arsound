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
        List<String> plan = new ArrayList<>(urns.size());
        for (int i = urns.size() - 1; i >= 0; i--) {
            if (!alreadyLiked.contains(urns.get(i))) plan.add(urns.get(i));
        }
        return plan;
    }
}
