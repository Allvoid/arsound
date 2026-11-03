package app.revanced.extension.soundcloud.recommendations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Puts the offers of the sources of the wave in order. A track that several seeds or several sources point to comes
 * first; a track the person switched off in the first seconds, disliked, or already had in this wave never comes; a
 * track the person liked comes rarely (a wave is for finding, not for repeating). Equal scores come in a shuffled order,
 * so two waves from the same taste differ. No Android here, so it can be tested on the JVM.
 */
final class WaveMixer {
    /** One offer: a track (by its urn) a source pointed to, and how strongly. */
    static final class Offer {
        final String urn;
        final double score;

        Offer(String urn, double score) {
            this.urn = urn;
            this.score = score;
        }
    }

    private final Set<String> never;
    private final Set<String> liked;
    private final Random random;

    /**
     * @param never  Tracks that must not come: skipped, disliked, already in the wave.
     * @param liked  Tracks the person liked: kept one time in four.
     * @param random Gives the choices; a test gives a fixed one.
     */
    WaveMixer(Collection<String> never, Collection<String> liked, Random random) {
        this.never = new java.util.HashSet<>(never);
        this.liked = new java.util.HashSet<>(liked);
        this.random = random;
    }

    /** The urns in the order to play, at most {@code limit}. */
    List<String> mix(List<Offer> offers, int limit) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (Offer offer : offers) {
            if (offer.urn == null || offer.urn.isEmpty() || never.contains(offer.urn)) continue;
            scores.merge(offer.urn, offer.score, Double::sum);
        }
        Map<String, Double> ranked = new HashMap<>();
        for (Map.Entry<String, Double> entry : scores.entrySet()) {
            if (liked.contains(entry.getKey()) && random.nextInt(4) != 0) continue;
            ranked.put(entry.getKey(), entry.getValue() + random.nextDouble() * 0.9);
        }
        List<Map.Entry<String, Double>> order = new ArrayList<>(ranked.entrySet());
        order.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<String> urns = new ArrayList<>();
        for (Map.Entry<String, Double> entry : order) {
            if (urns.size() >= limit) break;
            urns.add(entry.getKey());
        }
        return urns;
    }

    /**
     * Picks seeds by weight without repeating one: the playing track weighs most, then the liked ones, then the heard.
     *
     * @param pool Seed urns with their weights (all above zero).
     */
    static List<String> pickSeeds(Map<String, Integer> pool, int count, Random random) {
        Map<String, Integer> left = new LinkedHashMap<>(pool);
        List<String> picked = new ArrayList<>();
        while (picked.size() < count && !left.isEmpty()) {
            int total = 0;
            for (int weight : left.values()) total += weight;
            int point = random.nextInt(total);
            String chosen = null;
            for (Map.Entry<String, Integer> entry : left.entrySet()) {
                point -= entry.getValue();
                chosen = entry.getKey();
                if (point < 0) break;
            }
            picked.add(chosen);
            left.remove(chosen);
        }
        return picked;
    }
}
