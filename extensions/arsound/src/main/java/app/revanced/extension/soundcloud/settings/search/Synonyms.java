package app.revanced.extension.soundcloud.settings.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@link SynonymTable} turned into lookups by stem. Used by {@link QueryTerm} to widen a query word, and by
 * {@link Suggestions} to offer neighbouring words.
 */
public final class Synonyms {
    private static final int MIN_PREFIX = 4;
    private static final Synonyms STANDARD = new Synonyms(SynonymTable.GROUPS);

    /** One group of the table. */
    private static final class Group {
        final String id;
        final Set<String> coreStems = new LinkedHashSet<>();
        final Set<String> relatedStems = new LinkedHashSet<>();
        final List<String> coreWords = new ArrayList<>();
        final List<String> relatedWords = new ArrayList<>();
        /** Phrases as "stemA stemB", which the made-up phrase tokens of the rows carry as their stem. */
        final Set<String> corePhrases = new LinkedHashSet<>();
        final Set<String> relatedPhrases = new LinkedHashSet<>();

        Group(String id) {
            this.id = id;
        }
    }

    private final List<Group> groups = new ArrayList<>();
    /** Single-word members: stem to the groups it stands in, each as the group index (core) or its negation minus one. */
    private final Map<String, List<Integer>> membership = new HashMap<>();
    /** Two-word phrases ("stemA stemB") to the groups they stand in. */
    private final Map<String, List<Integer>> phrases = new HashMap<>();

    /** The table of the app. */
    public static Synonyms standard() {
        return STANDARD;
    }

    Synonyms(String[][] table) {
        for (String[] row : table) {
            Group group = new Group(row[0]);
            int index = groups.size();
            groups.add(group);
            read(row[1], group, index, true);
            read(row[2], group, index, false);
        }
    }

    private void read(String words, Group group, int index, boolean core) {
        for (String member : words.trim().split("\\s+")) {
            if (member.isEmpty()) continue;
            if (member.indexOf('_') >= 0) {
                String[] parts = member.split("_");
                if (parts.length != 2) throw new IllegalArgumentException("Only two-word phrases: " + member);
                String key = Stemmer.stem(Text.normalize(parts[0])) + " " + Stemmer.stem(Text.normalize(parts[1]));
                phrases.computeIfAbsent(key, k -> new ArrayList<>()).add(code(index, core));
                (core ? group.corePhrases : group.relatedPhrases).add(key);
                continue;
            }
            String word = Text.normalize(member);
            String stem = Stemmer.stem(word);
            (core ? group.coreStems : group.relatedStems).add(stem);
            (core ? group.coreWords : group.relatedWords).add(word);
            membership.computeIfAbsent(stem, k -> new ArrayList<>()).add(code(index, core));
        }
    }

    private static int code(int group, boolean core) {
        return core ? group : -group - 1;
    }

    /** What a stem is worth as a query word: the stems of its group mates and how close each of them is. */
    Map<String, MatchKind> expand(String stem) {
        Map<String, MatchKind> result = new HashMap<>();
        List<Integer> exact = membership.get(stem);
        if (exact != null) addAll(result, exact);
        // A word still being typed: "хран" reaches "хранилище".
        if (stem.length() >= MIN_PREFIX) {
            for (Map.Entry<String, List<Integer>> member : membership.entrySet()) {
                if (member.getKey().length() > stem.length() && member.getKey().startsWith(stem)) addAll(result, member.getValue());
            }
        }
        return result;
    }

    /** As {@link #expand} for a two-word phrase of the query; null if no group has the phrase. */
    Map<String, MatchKind> expandPhrase(String firstStem, String secondStem) {
        List<Integer> codes = phrases.get(firstStem + " " + secondStem);
        if (codes == null) return null;
        Map<String, MatchKind> result = new HashMap<>();
        addAll(result, codes);
        return result;
    }

    private void addAll(Map<String, MatchKind> result, List<Integer> codes) {
        for (int code : codes) {
            boolean core = code >= 0;
            Group group = groups.get(core ? code : -code - 1);
            for (String target : group.coreStems) put(result, target, core ? MatchKind.SYNONYM : MatchKind.RELATED);
            for (String target : group.corePhrases) put(result, target, core ? MatchKind.SYNONYM : MatchKind.RELATED);
            if (core) {
                for (String target : group.relatedStems) put(result, target, MatchKind.RELATED);
                for (String target : group.relatedPhrases) put(result, target, MatchKind.RELATED);
            }
        }
    }

    private static void put(Map<String, MatchKind> result, String stem, MatchKind kind) {
        MatchKind old = result.get(stem);
        if (old == null || kind.factor > old.factor) result.put(stem, kind);
    }

    /** Adds a made-up token after the real ones for every pair of neighbouring words that is a phrase of the table. */
    void appendPhraseTokens(List<Token> tokens) {
        int real = tokens.size();
        for (int i = 0; i + 1 < real; i++) {
            Token first = tokens.get(i);
            Token second = tokens.get(i + 1);
            String key = first.stem + " " + second.stem;
            if (phrases.containsKey(key)) tokens.add(new Token(first.start, second.end, first.norm + " " + second.norm, key, true));
        }
    }

    /** Every single word of the table, for the vocabulary of typo correction: stem to the word as written. */
    Map<String, String> vocabulary() {
        Map<String, String> words = new HashMap<>();
        for (Group group : groups) {
            for (String word : group.coreWords) words.putIfAbsent(Stemmer.stem(word), word);
            for (String word : group.relatedWords) words.putIfAbsent(Stemmer.stem(word), word);
        }
        return words;
    }

    /** The words that stand with this stem in a group, core words of the groups first. Phrases are left out. */
    List<String> neighbours(String stem) {
        Set<String> words = new LinkedHashSet<>();
        List<Integer> exact = membership.get(stem);
        if (exact == null) return new ArrayList<>();
        for (int code : exact) {
            boolean core = code >= 0;
            Group group = groups.get(core ? code : -code - 1);
            words.addAll(group.coreWords);
            if (core) words.addAll(group.relatedWords);
        }
        return new ArrayList<>(words);
    }

    // region For the tests of the table

    int groupCount() {
        return groups.size();
    }

    String groupId(int index) {
        return groups.get(index).id;
    }

    List<String> coreWords(int index) {
        return groups.get(index).coreWords;
    }

    List<String> relatedWords(int index) {
        return groups.get(index).relatedWords;
    }

    /** In how many groups the stem is a core word. */
    int coreGroupsOf(String stem) {
        int count = 0;
        for (Group group : groups) if (group.coreStems.contains(stem)) count++;
        return count;
    }

    // endregion
}
