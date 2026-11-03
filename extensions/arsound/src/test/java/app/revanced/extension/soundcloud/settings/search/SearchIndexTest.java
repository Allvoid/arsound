package app.revanced.extension.soundcloud.settings.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class SearchIndexTest {
    private static final int TOP = 8;

    private static SearchEntry row(String section, String title, String description) {
        return new SearchEntry("screen", section, title, description, false);
    }

    private static List<String> titles(List<Hit> hits) {
        List<String> titles = new ArrayList<>();
        for (Hit hit : hits) titles.add(hit.entry.title);
        return titles;
    }

    /** Every wanted title is among the best {@link #TOP} results of the query. */
    private static void assertFinds(SearchIndex index, String query, String... wanted) {
        List<String> top = titles(index.search(query, TOP));
        for (String title : wanted) assertTrue("«" + query + "» should find «" + title + "» but found " + top, top.contains(title));
    }

    // region The behaviour the search always had

    @Test
    public void everyWordOfTheQueryMustBeFound() {
        SearchIndex index = new SearchIndex();
        index.add(row("Network", "Custom DNS", "Resolve through a chosen server"));
        index.add(row("Network", "Check IP again", "Country of the IP"));

        List<Hit> found = index.search("dns server", 10);
        assertEquals(1, found.size());
        assertEquals("Custom DNS", found.get(0).entry.title);
        assertTrue(index.search("dns banana", 10).isEmpty());
    }

    @Test
    public void titleMatchesLeadAndEqualScoresKeepTheScreenOrder() {
        SearchIndex index = new SearchIndex();
        index.add(row("A", "Alpha", "mentions theme here"));
        index.add(row("B", "Theme", ""));
        index.add(row("C", "Theme animation", ""));
        index.add(row("D", "Another", "theme too"));

        List<String> found = titles(index.search("theme", 10));
        assertEquals(Arrays.asList("Theme", "Theme animation", "Alpha", "Another"), found);
    }

    @Test
    public void theLimitCutsTheList() {
        SearchIndex index = new SearchIndex();
        for (int i = 0; i < 5; i++) index.add(row("S", "Row " + i, ""));
        assertEquals(3, index.search("row", 3).size());
    }

    @Test
    public void yoIsEAndPunctuationIsNotPartOfAWord() {
        SearchIndex index = new SearchIndex();
        index.add(row("Раздел", "Всё сразу", "Дубликаты, ремиксы"));
        assertEquals(1, index.search("все", 10).size());
        assertEquals(1, index.search("дубликаты", 10).size());
        assertEquals(1, index.search("«ремиксы»", 10).size());
    }

    @Test
    public void hitsGiveTheRangesToColour() {
        SearchIndex index = new SearchIndex();
        index.add(row("Network", "Custom DNS", ""));
        List<int[]> ranges = index.search("dns cus", 10).get(0).titleRanges;
        assertEquals(2, ranges.size());
        assertEquals(7, ranges.get(0)[0]);
        assertEquals(10, ranges.get(0)[1]);
        assertEquals(0, ranges.get(1)[0]);
        assertEquals(3, ranges.get(1)[1]);
    }

    // endregion

    // region Words people use

    @Test
    public void memoryAndStorageAreTheSameThing() {
        SearchIndex index = SearchFixture.index();
        assertEquals("Память и загрузки", index.search("память", 5).get(0).entry.title);
        assertFinds(index, "память", "Память и загрузки", "Очистить кэш", "Папка для скачанных треков");
        assertEquals("Память и загрузки", index.search("хранилище", 5).get(0).entry.title);
        assertEquals(titles(index.search("память", 6)).subList(0, 3), titles(index.search("хранилище", 6)).subList(0, 3));
        assertFinds(index, "место", "Память и загрузки", "Лишние копии скачанных треков", "Очистить кэш");
        assertFinds(index, "storage", "Память и загрузки");
        assertFinds(index, "memory", "Память и загрузки");
        assertFinds(index, "кэш", "Очистить кэш", "Размер кэша");
    }

    @Test
    public void wallpaperHasManyNames() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"обои", "фон", "картинка", "wallpaper", "картинки"}) {
            assertFinds(index, query, "Показывать обои", "Обои Wallhaven");
        }
    }

    @Test
    public void soundWordsFindTheEqualizer() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"звук", "басс", "громкость", "eq", "эквалайзер"}) {
            assertFinds(index, query, "Эквалайзер", "Преамп");
        }
    }

    @Test
    public void networkWordsFindTheNetworkSection() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"интернет", "vpn", "прокси", "dns", "страна", "блокировка"}) {
            assertFinds(index, query, "Сеть");
        }
        assertFinds(index, "dns", "Свой DNS");
        assertFinds(index, "ip", "Не выходить в сеть с IP выбранных стран");
    }

    @Test
    public void batteryWords() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"батарея", "аккумулятор", "энергия", "battery", "экономия"}) {
            assertFinds(index, query, "Экономия батареи");
        }
    }

    @Test
    public void wholeCover() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"обложка", "обложку целиком", "вся обложка"}) {
            assertFinds(index, query, "Обложка целиком в плеере");
        }
    }

    @Test
    public void adsAndSubscription() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"реклама", "adblock", "баннер"}) {
            assertFinds(index, query, "Блокировать рекламу");
        }
        assertFinds(index, "баннер", "Скрыть баннер импорта плейлистов");
        for (String query : new String[]{"подписка", "premium", "go"}) {
            assertFinds(index, query, "Скрывать предложения подписки", "Upgrade (если есть)");
        }
    }

    @Test
    public void notificationsLanguageAndIcon() {
        SearchIndex index = SearchFixture.index();
        assertFinds(index, "уведомления", "Плеер в уведомлении под цвет трека");
        for (String query : new String[]{"язык", "language", "russian"}) assertFinds(index, query, "Язык приложения");
        for (String query : new String[]{"иконка", "значок", "ярлык"}) assertFinds(index, query, "Иконка приложения");
    }

    @Test
    public void downloadsLyricsAndLogs() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"скачать", "загрузки", "download"}) {
            assertFinds(index, query, "Папка для скачанных треков", "Кнопки скачивания через Arsound");
        }
        assertFinds(index, "текст песни", "Кнопка текста в плеере");
        assertFinds(index, "lyrics", "Кнопка текста в плеере", "Очистить кэш текстов");
        assertFinds(index, "слова", "Кнопка текста в плеере");
        for (String query : new String[]{"ошибка", "баг", "логи"}) assertFinds(index, query, "Сохранить журналы", "Журнал в файл");
    }

    @Test
    public void updatesResetDislikeAndPrivacy() {
        SearchIndex index = SearchFixture.index();
        assertFinds(index, "обновить", "Проверить обновления");
        assertFinds(index, "update", "Проверить обновления");
        assertFinds(index, "сброс", "Сбросить порядок", "Сбросить данные SoundCloud");
        for (String query : new String[]{"не нравится", "дизлайк"}) assertFinds(index, query, "«Не нравится» у треков");
        for (String query : new String[]{"слежка", "приватность"}) assertFinds(index, query, "Телеметрия");
        for (String query : new String[]{"18+", "nsfw"}) assertFinds(index, query, "Показывать 18+");
    }

    @Test
    public void aTwoWordPhraseIsOneWord() {
        SearchIndex index = new SearchIndex();
        index.add(row("A", "Сердечко в виджете", "Кнопка"));
        index.add(row("A", "Тема", ""));
        assertFinds(index, "рабочий стол", "Сердечко в виджете");
    }

    @Test
    public void importedFilesAreFoundByOwnWordsAndVersionByUpdates() {
        SearchIndex index = SearchFixture.index();
        assertFinds(index, "свои файлы", "Импортированные файлы");
        assertFinds(index, "импорт", "Импортированные файлы");
        assertFinds(index, "версия", "Проверить обновления");
        assertFinds(index, "wallhaven", "Обои Wallhaven");
    }

    @Test
    public void aTranscriptionRowIsFoundByTheWordsOfNeuralNetworks() {
        SearchIndex index = SearchFixture.index();
        index.add(row("Воспроизведение", "Распознавать слова песни", "Whisper превращает звук в текст на телефоне."));
        for (String query : new String[]{"whisper", "нейросеть", "распознавание", "транскрипция", "караоке"}) {
            assertFinds(index, query, "Распознавать слова песни");
        }
    }

    // endregion

    // region Typos and the wrong layout

    @Test
    public void typingMistakesAreForgiven() {
        SearchIndex index = SearchFixture.index();
        assertFinds(index, "прлеер", "Новый плеер (бета)");
        assertFinds(index, "эквалайзар", "Эквалайзер");
        assertFinds(index, "батрея", "Экономия батареи");
        assertFinds(index, "пмаять", "Память и загрузки");
    }

    @Test
    public void textTypedOnTheWrongLayoutIsRead() {
        SearchIndex index = SearchFixture.index();
        assertFinds(index, "gfvznm", "Память и загрузки");
        assertEquals(Reason.LAYOUT, index.search("gfvznm", 1).get(0).reason);
        assertFinds(index, "цфддзфзук", "Показывать обои");
    }

    @Test
    public void wrongLayoutWordsWithPunctuationKeysAreRead() {
        SearchIndex index = SearchFixture.index();
        // х, б, э, ю, ж, ъ sit on [ , ' . ; ] and the word splitter would cut the word at them.
        Hit storage = index.search("[hfybkbot", 5).get(0);
        assertEquals("Память и загрузки", storage.entry.title);
        assertEquals(Reason.LAYOUT, storage.reason);
        assertEquals("хранилище", storage.matchedWord);
        assertFinds(index, ",fnfhtz", "Экономия батареи");
        assertFinds(index, "j,jb", "Показывать обои", "Обои Wallhaven");
        assertFinds(index, "r'i", "Очистить кэш", "Размер кэша");
        assertEquals(Reason.LAYOUT, index.search("r'i", 1).get(0).reason);
    }

    @Test
    public void aPlainQueryWithATrailingCommaIsStillSearchedAsTyped() {
        SearchIndex index = SearchFixture.index();
        assertFinds(index, "dns,", "Свой DNS");
        assertFalse(index.search("dns,", 1).isEmpty());
        assertFalse(Reason.LAYOUT == index.search("dns,", 1).get(0).reason);
    }

    @Test
    public void aShortStemIsNotMatchedAgainstTheBeginningOfLongWords() {
        SearchIndex index = SearchFixture.index();
        // "папы" has the stem "пап", which used to match the beginning of "папка".
        assertFalse(index.correctionsFor("папы").contains("папка"));
        // A swapped pair in a short word is still forgiven.
        boolean cache = false;
        for (String word : index.correctionsFor("кшэ")) cache |= word.startsWith("кэш");
        assertTrue(cache);
    }

    @Test
    public void aMistakeIsNotCorrectedWhenTheWordIsKnown() {
        SearchIndex index = SearchFixture.index();
        assertTrue(index.search("dns banana", 10).isEmpty());
        assertTrue(index.search("zzzzzzzz", 10).isEmpty());
    }

    // endregion

    // region Ranking and the reason

    @Test
    public void directBeatsSynonymBeatsRelatedBeatsDescription() {
        SearchIndex index = new SearchIndex();
        index.add(row("S", "Хранилище", ""));
        index.add(row("S", "Память", ""));
        index.add(row("S", "Папка", ""));
        index.add(row("S", "Звук", "тут сказано про память телефона"));
        List<Hit> hits = index.search("хранилище", 10);
        assertEquals(Arrays.asList("Хранилище", "Память", "Папка", "Звук"), titles(hits));
        assertTrue(hits.get(0).score > hits.get(1).score);
        assertTrue(hits.get(1).score > hits.get(2).score);

        List<Hit> byMemory = index.search("память", 10);
        assertEquals("Память", byMemory.get(0).entry.title);
        assertEquals("Хранилище", byMemory.get(1).entry.title);
        assertEquals("Звук", byMemory.get(byMemory.size() - 1).entry.title);
    }

    @Test
    public void aRowNamedLikeTheQueryLeads() {
        SearchIndex index = new SearchIndex();
        index.add(row("A", "Тема анимации", ""));
        index.add(row("A", "Тема", ""));
        assertEquals("Тема", index.search("тема", 5).get(0).entry.title);
    }

    @Test
    public void theReasonTellsHowARowWasFound() {
        SearchIndex index = SearchFixture.index();
        Hit exact = index.search("кэш", 1).get(0);
        assertEquals(Reason.EXACT, exact.reason);
        Hit byMeaning = index.search("хранилище", 10).get(0);
        assertEquals(Reason.SYNONYM, byMeaning.reason);
        assertEquals("хранилище", byMeaning.queryWord);
        assertEquals("память", byMeaning.matchedWord);
        Hit typo = index.search("батрея", 10).get(0);
        assertEquals(Reason.FUZZY, typo.reason);
    }

    @Test
    public void aSynonymHitColoursTheWordThatMatched() {
        SearchIndex index = new SearchIndex();
        index.add(row("A", "Очистить кэш", ""));
        Hit hit = index.search("память", 5).get(0);
        // "очистить" is near "память" in meaning, "кэш" is the word that lies closest.
        boolean colouredCache = false;
        for (int[] range : hit.titleRanges) colouredCache |= range[0] == 9 && range[1] == 12;
        assertTrue(colouredCache);
    }

    @Test
    public void theSmoothTransitionHasManyNames() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"кроссфейд", "кросфейд", "crossfade", "затухание", "затухает трек",
                "плавный переход", "переход между треками", "fade", "склейка треков", "сведение", "gapless", "rhjccatql"}) {
            assertFinds(index, query, "Плавный переход между треками");
        }
    }

    @Test
    public void loadingTheNextTrackHasManyNames() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"предзагрузка", "подгрузка", "подгружать следующий", "буферизация",
                "preload", "prefetch", "следующий трек", "загружать заранее"}) {
            assertFinds(index, query, "Загружать следующий трек заранее");
        }
    }

    @Test
    public void commentsAreFoundHoweverTheyAreSpelt() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"комментарии", "комменты", "коменты", "коментарии", "comments",
                "убрать комментарии", "всплывающие", "popup", "отключить комменты"}) {
            assertFinds(index, query, "Скрыть всплывающие комментарии");
        }
    }

    @Test
    public void theGreetingIsFoundByItsWords() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"привет", "приветствие", "что послушаем", "greeting", "hello",
                "надпись на главной", "убрать привет"}) {
            assertFinds(index, query, "Без приветствия на главной");
        }
    }

    @Test
    public void theTopBarBackgroundHasManyNames() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"подложка", "шапка", "стекло", "liquid glass", "прозрачная панель", "toolbar",
                "полоса сверху"}) {
            assertFinds(index, query, "Подложка верхней панели");
        }
    }

    @Test
    public void theYandexTransferIsFound() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"яндекс", "яндекс музыка", "перенос", "перенести плейлист", "yandex",
                "transfer", "переехать с яндекса"}) {
            assertFinds(index, query, "Перенос из Яндекс Музыки");
        }
    }

    @Test
    public void linksAreFound() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"ссылки", "открывать ссылки", "links", "deeplink", "ссылка в браузере"}) {
            assertFinds(index, query, "Открывать ссылки SoundCloud в Arsound");
        }
    }

    @Test
    public void ownWallpaperIsFound() {
        SearchIndex index = SearchFixture.index();
        for (String query : new String[]{"своя картинка", "галерея", "фото на фон", "свои обои"}) {
            assertFinds(index, query, "Своя картинка из галереи");
        }
    }

    @Test
    public void keywordsHelpARowThatDoesNotSayTheWord() {
        SearchIndex index = new SearchIndex();
        SearchEntry theme = row("A", "Тема", "");
        theme.addKeywords("wallhaven пасхалка");
        index.add(theme);
        index.add(row("A", "Другое", ""));
        assertEquals("Тема", index.search("пасхалка", 5).get(0).entry.title);
    }

    @Test
    public void searchAnyFindsRowsWithOnlyOneOfTheWords() {
        SearchIndex index = SearchFixture.index();
        assertTrue(index.search("dns кабачок", 10).isEmpty());
        assertFalse(index.searchAny("dns кабачок", 10).isEmpty());
    }

    @Test
    public void manyQueriesAreFast() {
        SearchIndex index = SearchFixture.index();
        String[] queries = {"память", "обои", "gfvznm", "прлеер", "не нравится", "интернет", "кабачок", "dns"};
        index.search("warm up", 10);
        long start = System.nanoTime();
        for (int i = 0; i < 2000; i++) index.search(queries[i % queries.length], 30);
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue("2000 searches took " + millis + " ms", millis < 1000);
    }

    // endregion
}
