package app.revanced.extension.soundcloud.wallpaper;

import app.revanced.extension.soundcloud.settings.search.Stemmer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Puts the Russian words of a wallpaper search into English. Wallhaven only knows English tags: a search for "космос"
 * or "кот" finds nothing there, while "space" and "cat" find thousands. The owner searches in Russian, so the text is
 * translated word by word with a small built-in dictionary of the words people use for wallpapers (places, animals,
 * colours, styles, things).
 *
 * <p>What it does with the text:
 * <ul>
 * <li>A word is looked up as typed (lower case, "ё" read as "е"), then by its stem, so "космос", "космоса" and
 * "космосе" all give "space" and "кошка", "кошки" and "кот" give "cat". The stem is the one the settings search uses
 * ({@link Stemmer}), on both sides, so the dictionary lists each word once in any form. A stem is short, so now and
 * then it is also the stem of a different word ("цветы" and "цвет", "лёд" and "леди"); such words are listed in their
 * exact forms only, which are looked up before any stem.</li>
 * <li>Entries of several words go first: "ночной город" gives "night city", not "night" and "city" apart. The words of
 * an entry may be split by spaces or a hyphen ("пиксель-арт", "пиксель арт").</li>
 * <li>Filler words that mean nothing to a tag search ("и", "на", "для", "обои", "фон", "телефон"...) are dropped when
 * spaces set them apart, since "mountains and sea" would look for a tag called "and". A text that is nothing but
 * filler is left as typed, so a search never turns from something into nothing.</li>
 * <li>Everything else stays exactly as typed: unknown words and names, Latin words, numbers, spaces and punctuation.
 * A word glued to a letter or digit ("4к"), or to an "@", is never touched; a leading "-" or "#" is kept, so
 * "-космос" gives "-space" (the service's way to leave a tag out).</li>
 * </ul>
 *
 * <p>Pure text with no Android, so the JVM tests can check it. The tables are filled once when the class loads and are
 * never changed after that, so any thread may call it.
 */
public final class SearchWords {
    /** Whole dictionary entries by their normalised text ("ночной город"); the first entry for a key wins. */
    private static final Map<String, String> EXACT = new HashMap<>();
    /**
     * The same entries by the stems of their words ("ночн город"), for the forms the dictionary does not list. The
     * entries added with {@link #exactOnly} are not here.
     */
    private static final Map<String, String> STEMS = new HashMap<>();
    /** Words a tag search has no use for. Exact forms only: a stem match would swallow real words. */
    private static final Set<String> FILLER = new HashSet<>(Arrays.asList(
            "и", "а", "в", "во", "на", "с", "со", "к", "ко", "по", "у", "о", "об", "от", "из", "за", "для", "над",
            "под", "при", "про", "без", "или", "как", "что", "это",
            "обои", "обоев", "обоям", "фон", "фона", "фоны", "заставка", "заставки", "картинка", "картинки", "картинку",
            "телефон", "телефона", "телефону", "смартфон", "смартфона"));
    /** Two entries that give the same key or the same stem different English; the tests keep this empty. */
    private static final List<String> CLASHES = new ArrayList<>();
    /** The most words one entry has, so a lookup never tries a longer run than the dictionary can hold. */
    private static int maxWords = 1;

    static {
        addPlaces();
        addSeasonsAndLight();
        addColoursAndStyles();
        addObjectsAndThemes();
        addPeople();
        addAnimals();
        addCountriesAndCities();
    }

    private SearchWords() {
    }

    /**
     * The text with its Russian words put into English, for a Wallhaven search.
     *
     * @return The text unchanged when it has no Cyrillic letter (the same string); otherwise the text with each known
     * Russian word or phrase replaced, everything else as typed. Never empty for a text that was not blank.
     */
    public static String english(String text) {
        if (text == null || !hasCyrillic(text)) return text;
        return translate(text);
    }

    /** All dictionary entries (normalised key to English), for the tests. */
    static Map<String, String> entries() {
        return Collections.unmodifiableMap(EXACT);
    }

    /** Dictionary mistakes found while it was filled (a key or stem with two different translations), for the tests. */
    static List<String> clashes() {
        return Collections.unmodifiableList(CLASHES);
    }

    // region Translation

    /** One stretch of the text and what becomes of it. */
    private static final class Match {
        /** The first and the last Russian word of the stretch, counted among the Russian words of the text. */
        final int first, last;
        /** The English to write; null when the words stay as typed, empty when they are filler to drop. */
        final String english;

        Match(int first, int last, String english) {
            this.first = first;
            this.last = last;
            this.english = english;
        }

        boolean translated() {
            return english != null && !english.isEmpty();
        }

        boolean dropped() {
            return english != null && english.isEmpty();
        }
    }

    private static String translate(String text) {
        // The pieces alternate: what is between the words, a word, what is between, a word ... what is after. So the
        // word number w is piece 2w+1 and the gap before it is piece 2w.
        List<String> pieces = split(text);
        int words = pieces.size() / 2;
        String[] norm = new String[words];
        boolean[] glued = new boolean[words];
        for (int w = 0; w < words; w++) {
            norm[w] = normalise(pieces.get(2 * w + 1));
            glued[w] = isGlued(pieces.get(2 * w), pieces.get(2 * w + 2));
        }
        List<Match> matches = new ArrayList<>();
        for (int w = 0; w < words; ) {
            Match match = glued[w] ? null : matchAt(pieces, norm, glued, w);
            if (match == null) match = new Match(w, w, null);
            matches.add(match);
            w = match.last + 1;
        }
        String result = compose(pieces, matches);
        return result.trim().isEmpty() ? text : result;
    }

    /** The longest dictionary entry that starts at the word {@code from}, or null when none does. */
    private static Match matchAt(List<String> pieces, String[] norm, boolean[] glued, int from) {
        // How far a run of words can reach: neighbours split by spaces or one hyphen, none of them glued to something.
        int longest = 1;
        while (longest < maxWords && from + longest < norm.length && !glued[from + longest]
                && isJoint(pieces.get(2 * (from + longest)))) {
            longest++;
        }
        for (int count = longest; count >= 1; count--) {
            String english = lookup(norm, from, count);
            if (english == null) continue;
            // A filler word is only dropped when spaces set it apart; "(и)", "-и" or "и," stay as the user typed them.
            boolean apart = endsApart(pieces.get(2 * from)) && startsApart(pieces.get(2 * from + 2));
            if (english.isEmpty() && !apart) continue;
            return new Match(from, from + count - 1, english);
        }
        return null;
    }

    /**
     * The English of {@code count} words from {@code from}: as typed first, then by stems; "" for a filler word; null
     * when unknown.
     */
    private static String lookup(String[] norm, int from, int count) {
        String found = EXACT.get(join(norm, from, count, false));
        if (found != null) return found;
        if (count == 1 && FILLER.contains(norm[from])) return "";
        return STEMS.get(join(norm, from, count, true));
    }

    private static String join(String[] norm, int from, int count, boolean stems) {
        StringBuilder key = new StringBuilder();
        for (int i = from; i < from + count; i++) {
            if (i > from) key.append(' ');
            key.append(stems ? Stemmer.stem(norm[i]) : norm[i]);
        }
        return key.toString();
    }

    /** Writes the text again with each match replaced and every gap as it was typed. */
    private static String compose(List<String> pieces, List<Match> matches) {
        StringBuilder out = new StringBuilder(pieces.get(0));
        for (int m = 0; m < matches.size(); m++) {
            Match match = matches.get(m);
            boolean last = m == matches.size() - 1;
            String gap = pieces.get(2 * match.last + 2);
            if (match.dropped()) {
                // A dropped word takes the spaces after it with it; the last word takes the spaces before it instead.
                if (last) trimEnd(out);
                else gap = trimStart(gap);
            } else {
                out.append(match.translated() ? match.english : typed(pieces, match));
                // A hyphen between words of which one was translated is just a space to the service ("аниме-девушка").
                if (!last && gap.equals("-") && (match.translated() || matches.get(m + 1).translated())) gap = " ";
            }
            out.append(gap);
        }
        return out.toString();
    }

    /** The words of a match as the user typed them, gaps between them included. */
    private static String typed(List<String> pieces, Match match) {
        StringBuilder text = new StringBuilder();
        for (int i = 2 * match.first + 1; i <= 2 * match.last + 1; i++) text.append(pieces.get(i));
        return text.toString();
    }

    // endregion

    // region Text helpers

    /** Splits the text into alternating gaps and Russian words; it starts and ends with a gap (maybe empty). */
    private static List<String> split(String text) {
        List<String> pieces = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean cyrillic = isCyrillicLetter(c);
            if (cyrillic != inWord) {
                pieces.add(run.toString());
                run.setLength(0);
                inWord = cyrillic;
            }
            run.append(c);
        }
        pieces.add(run.toString());
        if (inWord) pieces.add("");
        return pieces;
    }

    private static boolean isCyrillicLetter(char c) {
        // The Cyrillic block, U+0400 to U+04FF, holds the Russian letters (and the letters of the sister alphabets).
        return c >= 'Ѐ' && c <= 'ӿ' && Character.isLetter(c);
    }

    private static boolean hasCyrillic(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isCyrillicLetter(text.charAt(i))) return true;
        }
        return false;
    }

    /** Lower case, "ё" as "е", a hyphen as a space (so "пиксель-арт" and "пиксель арт" are one entry). */
    private static String normalise(String word) {
        return word.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('-', ' ');
    }

    /**
     * Whether a Russian word is part of something that is not Russian: "4к" or "k4" is a size or a name, and "@имя" is
     * a user of the service. The gap before the word and the gap after it say so.
     */
    private static boolean isGlued(String before, String after) {
        return (!before.isEmpty() && glues(before.charAt(before.length() - 1)))
                || (!after.isEmpty() && glues(after.charAt(0)));
    }

    private static boolean glues(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '@';
    }

    /** Whether two words with this gap between them can be one dictionary entry: spaces only, or a single hyphen. */
    private static boolean isJoint(String gap) {
        return gap.equals("-") || (!gap.isEmpty() && isBlank(gap));
    }

    private static boolean isBlank(String gap) {
        for (int i = 0; i < gap.length(); i++) {
            if (!Character.isWhitespace(gap.charAt(i))) return false;
        }
        return true;
    }

    /** Whether a word after this gap is set apart from what comes before it: at the start, or after a space. */
    private static boolean endsApart(String gap) {
        return gap.isEmpty() || Character.isWhitespace(gap.charAt(gap.length() - 1));
    }

    /** Whether a word before this gap is set apart from what comes after it: at the end, or before a space. */
    private static boolean startsApart(String gap) {
        return gap.isEmpty() || Character.isWhitespace(gap.charAt(0));
    }

    private static String trimStart(String gap) {
        int start = 0;
        while (start < gap.length() && Character.isWhitespace(gap.charAt(start))) start++;
        return gap.substring(start);
    }

    private static void trimEnd(StringBuilder text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        text.setLength(end);
    }

    // endregion

    // region Dictionary

    /**
     * Adds one English word or phrase with the Russian words that mean it. Each Russian form is listed once; its other
     * endings are found by the stem. Irregular forms ("льва", "огня") get their own item, as the stem of the first
     * form does not reach them.
     */
    private static void add(String english, String... russian) {
        register(english, russian, true);
    }

    /**
     * Adds Russian forms that match only as typed, with no stem entry, so no other word can fall into them by its
     * ending. The stem of a word is short (three or four letters) and now and then it is also the stem of a different,
     * common word: the stem of "цветы" (flowers) is "цвет", the whole word for a colour, and the stem of "лев" (lion)
     * is the stem of "левый" (left). With a stem entry all of those would come out as flowers or a lion. So the forms
     * that are wanted are listed one by one here, and the others stay as typed.
     *
     * <p>An exact form is also looked up before any stem, so this is how a word is protected from the stem entry of
     * another one: "голубь" (a pigeon) is listed here next to "голубой" (light blue), whose stem it shares.
     */
    private static void exactOnly(String english, String... russian) {
        register(english, russian, false);
    }

    /**
     * {@link #exactOnly} for the forms of an adjective whose masculine ends in "ый": "бурый" is the root "бур" with
     * "ый", "ая", "ое", "ые" and the endings of the other cases.
     */
    private static void exactAdjective(String english, String root) {
        exactOnly(english, root + "ый", root + "ая", root + "ое", root + "ые", root + "ого", root + "ой", root + "ому",
                root + "ым", root + "ую", root + "ыми", root + "ых", root + "ом");
    }

    private static void register(String english, String[] russian, boolean withStem) {
        for (String form : russian) {
            String key = normalise(form);
            String[] words = key.split(" ");
            maxWords = Math.max(maxWords, words.length);
            remember(EXACT, key, english);
            if (withStem) remember(STEMS, join(words, 0, words.length, true), english);
        }
    }

    private static void remember(Map<String, String> table, String key, String english) {
        String known = table.putIfAbsent(key, english);
        if (known != null && !known.equals(english)) {
            CLASHES.add(key + ": " + known + " / " + english);
        }
    }

    private static void addPlaces() {
        add("mountains", "гора", "горный");
        add("sea", "море", "морской");
        add("ocean", "океан");
        add("forest", "лес", "лесной", "чаща");
        add("coast", "берег", "побережье");
        add("beach", "пляж");
        add("sunset", "закат");
        add("sunrise", "рассвет", "восход");
        add("sky", "небо", "небеса", "небесный");
        add("night sky", "ночное небо");
        add("clouds", "облако", "туча");
        add("stars", "звезда", "звёздный");
        add("starry sky", "звёздное небо");
        add("space", "космос", "космический");
        add("galaxy", "галактика");
        add("milky way", "млечный путь");
        add("solar system", "солнечная система");
        add("nebula", "туманность");
        add("planet", "планета");
        add("moon", "луна");
        add("sun", "солнце", "солнечный");
        add("earth", "земля");
        add("comet", "комета");
        add("meteor", "метеор", "метеорит");
        add("asteroid", "астероид");
        add("universe", "вселенная");
        add("black hole", "чёрная дыра");
        add("city", "город", "городской", "мегаполис");
        add("night city", "ночной город");
        add("cityscape", "городской пейзаж");
        add("skyscraper", "небоскрёб");
        add("street", "улица");
        add("bridge", "мост");
        add("building", "здание");
        add("architecture", "архитектура");
        add("tower", "башня");
        add("lighthouse", "маяк");
        add("temple", "храм");
        add("church", "церковь", "церкви");
        add("cathedral", "собор");
        add("castle", "замок");
        add("fortress", "крепость");
        add("palace", "дворец", "дворца");
        add("ruins", "руины", "развалины");
        add("pyramid", "пирамида");
        add("house", "дом", "домик");
        add("village", "деревня");
        // Exact forms only: the stem "дорог" is also "дорогой", "дорогая", "дорогие" (expensive), which stay as typed.
        exactOnly("road", "дорога", "дороги", "дорогу", "дороге", "дорог", "дорогам", "дорогами", "дорогах");
        add("highway", "шоссе", "трасса");
        add("path", "тропа", "тропинка");
        add("tunnel", "тоннель", "туннель");
        add("lake", "озеро");
        add("river", "река");
        add("waterfall", "водопад");
        add("stream", "ручей");
        add("pond", "пруд");
        add("swamp", "болото");
        add("bay", "залив");
        add("waves", "волна");
        // "остров" would stem to "остр", the stem of "острый" (sharp), so it is exact only.
        // "острова", "островов", "островом" and the other forms stem to "остров", which no other word has.
        exactOnly("island", "остров");
        add("island", "острова");
        add("volcano", "вулкан");
        add("glacier", "ледник");
        add("ice", "лёд", "льда", "ледяной");
        add("cave", "пещера");
        add("canyon", "каньон");
        add("valley", "долина");
        add("hills", "холм");
        // Exact forms only: the stem "пол" is also the floor ("пол", "пола", "полу", "полы"), which stays as typed.
        exactOnly("field", "поле", "поля", "полю", "полем", "полей", "полям", "полями", "полях");
        add("meadow", "луг");
        add("steppe", "степь");
        add("jungle", "джунгли");
        add("desert", "пустыня");
        add("dunes", "дюна", "бархан");
        add("sand", "песок");
        add("rocks", "скала");
        add("stone", "камень");
        add("garden", "сад");
        add("park", "парк");
        add("tropical", "тропический");
        add("rainbow", "радуга");
        add("lightning", "молния");
        add("rain", "дождь");
        add("fog", "туман");
        add("wind", "ветер", "ветра");
        add("storm", "гроза", "шторм", "буря");
        add("snow", "снег", "снежный");
        add("aurora", "северное сияние", "аврора");
        add("fire", "огонь", "огня", "пламя", "костёр", "костра");
        add("water", "вода");
        add("lava", "лава");
        add("smoke", "дым");
        add("bubbles", "пузырь");
        add("drops", "капля");
        add("nature", "природа");
        add("wildlife", "дикая природа");
        add("landscape", "пейзаж");
        add("panorama", "панорама");
        add("flowers", "цветок", "цветочек", "цветочный");
        // "цветы" would stem to "цвет", the stem of the colour "цвет" too, so the plural is listed form by form. The
        // colour itself is in addColoursAndStyles.
        exactOnly("flowers", "цветы", "цветов", "цветам", "цветами", "цветах");
        add("roses", "роза");
        add("tulips", "тюльпан");
        add("lotus", "лотос");
        add("lavender", "лаванда");
        add("sunflowers", "подсолнух");
        add("orchid", "орхидея");
        add("daisies", "ромашка");
        add("cherry blossom", "сакура");
        add("trees", "дерево");
        add("leaves", "лист", "листва");
        add("grass", "трава");
        add("moss", "мох", "мха");
        add("bamboo", "бамбук");
        add("pine trees", "сосна", "ель", "ёлка");
        add("palm trees", "пальма");
        add("oak", "дуб");
        add("birch", "берёза");
        add("cactus", "кактус");
        add("mushrooms", "гриб");
    }

    private static void addSeasonsAndLight() {
        add("winter", "зима", "зимний");
        add("autumn", "осень");
        add("spring", "весна", "весенний");
        // "лето" is exact only: its stem "лет" is also the word "лет" ("пять лет", years), which stays as typed.
        exactOnly("summer", "лето", "лета", "лету", "летом", "лете");
        add("summer", "летний");
        add("new year", "новый год", "новогодний");
        add("christmas", "рождество", "рождественский");
        add("halloween", "хэллоуин", "хеллоуин");
        add("night", "ночь", "ночной");
        add("morning", "утро", "утренний");
        add("evening", "вечер", "вечерний");
        add("twilight", "сумерки");
        add("silhouette", "силуэт");
        add("reflection", "отражение");
        add("light", "свет", "светлый");
        add("candle", "свеча");
    }

    private static void addColoursAndStyles() {
        add("dark", "тёмный", "тьма");
        add("gloomy", "мрачный");
        add("black", "чёрный");
        add("white", "белый");
        // Exact forms only: the stem "сер" is also "серия" (a series) and "сера" (sulphur), which stay as typed.
        exactAdjective("gray", "сер");
        // The first half of a compound colour: серо-голубой, серо-зелёный.
        exactOnly("gray", "серо");
        add("red", "красный");
        add("blue", "синий");
        add("light blue", "голубой");
        add("green", "зелёный");
        add("yellow", "жёлтый");
        add("orange", "оранжевый");
        add("purple", "фиолетовый", "пурпурный");
        add("pink", "розовый");
        add("brown", "коричневый");
        // "бурый" has the stem "бур" of "буря" (a storm). The storm keeps the stem, so the brown forms are listed.
        exactAdjective("brown", "бур");
        add("gold", "золото");
        add("silver", "серебро", "серебряный");
        add("turquoise", "бирюзовый");
        add("beige", "бежевый");
        add("black and white", "чёрно-белый");
        add("monochrome", "монохромный", "монохром");
        add("neon", "неон", "неоновый");
        add("colorful", "цветной", "разноцветный", "красочный");
        // "цветение" would stem to "цветн", the stem of "цветной": its forms are exact only.
        exactOnly("bloom", "цветение", "цветения", "цветению", "цветением", "цветении");
        // The colour as a word: left in Russian it would make the whole search find nothing, while Wallhaven has
        // thousands of pictures for "color" and "colors". Exact only, as its stem "цвет" is that of "цветы" (flowers).
        exactOnly("color", "цвет", "цвету", "цветом", "цвете");
        exactOnly("colors", "цвета");
        add("minimalism", "минимализм", "минималистичный", "минимальный");
        add("abstract", "абстракция", "абстрактный");
        add("anime", "аниме");
        add("manga", "манга");
        add("art", "арт", "искусство");
        add("pixel art", "пиксель-арт", "пиксельарт");
        add("pixel", "пиксель", "пиксельный");
        add("gradient", "градиент");
        // Wallhaven has no tag "amoled" (it answers 0); an AMOLED wallpaper is a mostly black one.
        add("black background", "амолед", "чёрный фон");
        add("beautiful", "красивый");
        add("bright", "яркий");
        add("cozy", "уютный");
        add("warm", "тёплый");
        add("cold", "холодный");
        add("simple", "простой");
        add("retro", "ретро");
        add("vintage", "винтаж");
        add("steampunk", "стимпанк");
        add("cyberpunk", "киберпанк");
        add("synthwave", "синтвейв", "ретровейв");
        add("vaporwave", "вейпорвейв", "вапорвейв");
        add("lofi", "лофи", "лоуфай");
        add("fantasy", "фэнтези", "фентези");
        add("sci-fi", "фантастика", "научная фантастика");
        add("surreal", "сюрреализм", "сюрреалистичный");
        add("psychedelic", "психоделика", "психоделический");
        add("geometric", "геометрия", "геометрический");
        add("pattern", "узор", "паттерн");
        add("texture", "текстура");
        add("marble", "мрамор");
        add("glass", "стекло");
        add("crystal", "кристалл");
        add("diamond", "алмаз", "бриллиант");
        add("vector", "вектор");
        add("cartoon", "мультфильм", "мультяшный");
        add("comic", "комикс");
        add("superhero", "супергерой");
        add("futuristic", "будущее", "футуризм", "футуристичный");
    }

    private static void addObjectsAndThemes() {
        add("car", "машина", "автомобиль", "авто");
        add("sports car", "спорткар", "спортивная машина");
        add("racing", "гонки");
        add("motorcycle", "мотоцикл", "байк");
        add("bicycle", "велосипед");
        add("airplane", "самолёт");
        add("helicopter", "вертолёт");
        add("train", "поезд");
        add("ship", "корабль");
        add("boat", "лодка");
        add("rocket", "ракета");
        add("astronaut", "космонавт", "астронавт");
        add("robot", "робот");
        add("technology", "технология", "техника");
        add("computer", "компьютер");
        add("matrix", "матрица");
        add("tank", "танк");
        add("weapon", "оружие");
        add("sword", "меч");
        add("armor", "доспехи");
        add("war", "война");
        add("music", "музыка", "музыкальный");
        add("guitar", "гитара");
        add("piano", "пианино", "рояль");
        add("violin", "скрипка");
        add("drums", "барабан");
        add("headphones", "наушники");
        add("vinyl", "винил");
        add("microphone", "микрофон");
        add("book", "книга");
        add("coffee", "кофе");
        add("tea", "чай");
        add("food", "еда", "еды");
        // "манго" would stem to "манг", the stem of "манга" (manga), so the fruit is exact only.
        exactOnly("mango", "манго");
        add("video games", "игра", "видеоигра", "компьютерная игра");
        add("love", "любовь", "любви");
        add("heart", "сердце");
        add("sports", "спорт");
        add("football", "футбол");
        add("basketball", "баскетбол");
        add("boxing", "бокс");
        add("dragon", "дракон");
        add("unicorn", "единорог");
        add("mermaid", "русалка");
        add("fairy", "фея", "феи");
        add("elf", "эльф");
        add("magic", "магия");
        add("wizard", "волшебник", "чародей");
        add("witch", "ведьма");
        add("vampire", "вампир");
        add("angel", "ангел");
        add("demon", "демон");
        add("ghost", "призрак");
        add("skull", "череп");
        add("zombie", "зомби");
        add("pirate", "пират");
    }

    private static void addPeople() {
        add("girl", "девушка", "девочка");
        add("anime girl", "аниме девушка");
        add("man", "парень", "мужчина");
        add("woman", "женщина");
        // "леди" would stem to "лед", the stem of "лёд" (ice); it never changes its form, so one item is enough.
        exactOnly("lady", "леди");
        add("kids", "ребёнок", "дети");
        add("people", "человек", "люди");
        add("face", "лицо");
        add("eyes", "глаз");
        add("hair", "волосы");
        add("portrait", "портрет");
        add("knight", "рыцарь");
        add("samurai", "самурай");
        add("ninja", "ниндзя");
        add("warrior", "воин");
    }

    private static void addAnimals() {
        add("animals", "животное", "зверь");
        add("cat", "кот", "кошка", "котик", "кошачий");
        add("kitten", "котёнок", "котята");
        add("dog", "собака", "пёс", "пса");
        add("puppy", "щенок");
        add("wolf", "волк");
        add("fox", "лиса", "лисица");
        add("bear", "медведь", "мишка");
        add("tiger", "тигр");
        // "лев" is exact only: its stem "лев" is also that of "левый" (left). The other forms stem from "льва".
        exactOnly("lion", "лев");
        add("lion", "льва");
        add("leopard", "леопард");
        add("panther", "пантера");
        add("cheetah", "гепард");
        add("deer", "олень");
        add("moose", "лось");
        add("horse", "конь", "лошадь");
        add("bird", "птица");
        // "голубь" shares its stem "голуб" with the colour "голубой" (light blue), so the bird is listed form by form.
        exactOnly("pigeon", "голубь", "голуби", "голубя", "голубю", "голубем", "голубе", "голубей", "голубям",
                "голубями", "голубях");
        add("eagle", "орёл", "орла");
        add("owl", "сова");
        add("raven", "ворон");
        add("parrot", "попугай");
        add("flamingo", "фламинго");
        add("swan", "лебедь");
        add("duck", "утка");
        add("butterfly", "бабочка");
        add("bee", "пчела");
        add("spider", "паук");
        add("snake", "змея");
        add("shark", "акула");
        add("dolphin", "дельфин");
        add("whale", "кит");
        add("jellyfish", "медуза");
        add("fish", "рыба", "рыбка");
        add("octopus", "осьминог");
        add("turtle", "черепаха");
        // The genitive plural "черепах" ends like the case ending "ах", so it would be cut to "череп" (a skull); the
        // other forms stem to "черепах" and are fine.
        exactOnly("turtle", "черепах");
        add("frog", "лягушка");
        add("crab", "краб");
        add("coral", "коралл");
        add("panda", "панда");
        add("rabbit", "кролик", "заяц", "зайца");
        add("squirrel", "белка");
        add("monkey", "обезьяна");
        add("elephant", "слон");
        add("giraffe", "жираф");
        add("zebra", "зебра");
        add("crocodile", "крокодил");
        add("dinosaur", "динозавр");
        add("raccoon", "енот");
        add("hedgehog", "ёж", "ёжик");
        add("penguin", "пингвин");
    }

    private static void addCountriesAndCities() {
        add("japan", "япония", "японский");
        add("tokyo", "токио");
        add("paris", "париж");
        add("london", "лондон");
        // Without this the "лос" of "Лос-Анджелес" is a moose ("лось").
        add("los angeles", "лос-анджелес");
        add("new york", "нью-йорк");
        add("moscow", "москва");
        add("russia", "россия");
        add("china", "китай");
        // "китая" ends like the adjective ending "ая", so it would be cut to "кит" (a whale) instead of "кита".
        exactOnly("china", "китая");
        add("iceland", "исландия");
        add("norway", "норвегия");
        add("switzerland", "швейцария");
        add("italy", "италия");
        add("venice", "венеция");
        add("egypt", "египет");
        add("alps", "альпы");
        add("himalayas", "гималаи");
    }

    // endregion
}
