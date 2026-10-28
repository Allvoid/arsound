package app.revanced.extension.soundcloud.settings.search;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StemmerTest {
    private static void sameStem(String expected, String... words) {
        for (String word : words) assertEquals(word, expected, Stemmer.stem(Text.normalize(word)));
    }

    @Test
    public void russianNounsLoseTheirEndings() {
        sameStem("памят", "память", "памяти", "памятью");
        sameStem("хранилищ", "хранилище");
        sameStem("загрузк", "загрузка", "загрузки", "загрузок");
        sameStem("уведомлн", "уведомление", "уведомления", "уведомлении");
        sameStem("реклам", "реклама", "рекламу", "рекламы");
        sameStem("батаре", "батарея", "батареи");
        sameStem("обо", "обои", "обоев", "обоям");
    }

    @Test
    public void russianEndingsIaAndIiGiveOneStem() {
        sameStem("верс", "версия", "версии", "версий");
        sameStem("функц", "функция", "функции");
    }

    @Test
    public void aFleetingVowelBeforeNIsDropped() {
        sameStem("песн", "песня", "песни", "песен", "песней");
        sameStem("ремн", "ремень", "ремни");
    }

    @Test
    public void russianAdjectivesAndShortWords() {
        sameStem("тем", "тема", "темы", "темой");
        sameStem("темн", "тёмная", "темный");
        sameStem("сет", "сеть", "сети");
        assertEquals("сетев", Stemmer.stem("сетевой"));
        // Words of three letters and shorter stay as they are.
        assertEquals("кэш", Stemmer.stem("кэш"));
        assertEquals("для", Stemmer.stem("для"));
    }

    @Test
    public void downloadedAndDownloadsMeet() {
        sameStem("скачан", "скачано", "скачанные");
    }

    @Test
    public void englishWordsLosePluralsAndSuffixes() {
        sameStem("download", "downloads", "downloaded", "download");
        sameStem("them", "theme", "themes");
        sameStem("cach", "cache", "caching", "caches");
        sameStem("play", "player", "players", "playing");
        assertEquals("stor", Stemmer.stem("storing"));
        sameStem("battery", "battery", "batteries");
    }

    @Test
    public void englishSuffixesAreCutTwice() {
        sameStem("buff", "buffer", "buffers", "buffering", "buffered");
        sameStem("filt", "filter", "filtering");
    }

    @Test
    public void shortAndMixedWordsStayAsTheyAre() {
        assertEquals("dns", Stemmer.stem("dns"));
        assertEquals("ip", Stemmer.stem("ip"));
        assertEquals("18+", Stemmer.stem("18+"));
        assertEquals("mp3", Stemmer.stem("mp3"));
        assertEquals("", Stemmer.stem(""));
    }
}
