package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

public class SearchWordsTest {
    // region Single words

    @Test
    public void theWordsPeopleSearchWallpapersForAreTranslated() {
        assertEquals("space", SearchWords.english("космос"));
        assertEquals("cat", SearchWords.english("кот"));
        assertEquals("mountains", SearchWords.english("горы"));
        assertEquals("sea", SearchWords.english("море"));
        assertEquals("ocean", SearchWords.english("океан"));
        assertEquals("forest", SearchWords.english("лес"));
        assertEquals("sunset", SearchWords.english("закат"));
        assertEquals("girl", SearchWords.english("девушка"));
        assertEquals("car", SearchWords.english("машина"));
        assertEquals("anime", SearchWords.english("аниме"));
        assertEquals("minimalism", SearchWords.english("минимализм"));
        assertEquals("neon", SearchWords.english("неон"));
        assertEquals("cyberpunk", SearchWords.english("киберпанк"));
        assertEquals("dragon", SearchWords.english("дракон"));
        // Wallhaven answers 0 for "amoled"; the service has pictures for what such a wallpaper is: black.
        assertEquals("black background", SearchWords.english("амолед"));
        assertEquals("cherry blossom", SearchWords.english("сакура"));
    }

    @Test
    public void colourWordsAreTranslated() {
        assertEquals("black", SearchWords.english("чёрный"));
        assertEquals("white", SearchWords.english("белый"));
        assertEquals("red", SearchWords.english("красный"));
        assertEquals("blue", SearchWords.english("синий"));
        assertEquals("green", SearchWords.english("зелёный"));
        assertEquals("yellow", SearchWords.english("жёлтый"));
        assertEquals("dark", SearchWords.english("тёмный"));
    }

    @Test
    public void theCaseOfTheLettersDoesNotMatter() {
        assertEquals("space", SearchWords.english("КОСМОС"));
        assertEquals("space", SearchWords.english("Космос"));
        assertEquals("space", SearchWords.english("кОсМоС"));
        assertEquals("night city", SearchWords.english("НОЧНОЙ ГОРОД"));
    }

    @Test
    public void theLetterYoIsReadAsYe() {
        assertEquals("stars", SearchWords.english("звёзды"));
        assertEquals("stars", SearchWords.english("звезды"));
        assertEquals("dark", SearchWords.english("Тёмный"));
        assertEquals("dark", SearchWords.english("темный"));
        assertEquals("black", SearchWords.english("черный"));
        assertEquals("hedgehog", SearchWords.english("Ёж"));
        assertEquals("airplane", SearchWords.english("самолет"));
    }

    // endregion

    // region Endings

    @Test
    public void everyCaseOfANounGivesTheSameWord() {
        assertEquals("space", SearchWords.english("космоса"));
        assertEquals("space", SearchWords.english("космосе"));
        assertEquals("space", SearchWords.english("космосом"));
        assertEquals("space", SearchWords.english("космосу"));
        assertEquals("mountains", SearchWords.english("горы"));
        assertEquals("mountains", SearchWords.english("гору"));
        assertEquals("mountains", SearchWords.english("горах"));
        assertEquals("mountains", SearchWords.english("горами"));
    }

    @Test
    public void catsAreFoundInEveryForm() {
        for (String form : new String[]{"кот", "коты", "кота", "коту", "котом", "котов", "кошка", "кошки", "кошку",
                "кошкой", "кошек", "котик", "котики"}) {
            assertEquals(form, "cat", SearchWords.english(form));
        }
    }

    @Test
    public void adjectivesLoseTheirGenderAndNumber() {
        for (String form : new String[]{"красивый", "красивая", "красивое", "красивые", "красивого", "красивой"}) {
            assertEquals(form, "beautiful", SearchWords.english(form));
        }
        for (String form : new String[]{"тёмный", "тёмная", "тёмное", "тёмные", "тёмного", "тёмной"}) {
            assertEquals(form, "dark", SearchWords.english(form));
        }
    }

    @Test
    public void irregularFormsTheDictionaryListsAreFound() {
        assertEquals("lion", SearchWords.english("лев"));
        assertEquals("lion", SearchWords.english("льва"));
        assertEquals("fire", SearchWords.english("огонь"));
        assertEquals("fire", SearchWords.english("огня"));
        assertEquals("eagle", SearchWords.english("орёл"));
        assertEquals("eagle", SearchWords.english("орла"));
        assertEquals("wind", SearchWords.english("ветра"));
        assertEquals("kitten", SearchWords.english("котёнка"));
    }

    @Test
    public void theShortestStemIsThreeLetters() {
        // "моря" is "мор" plus an ending: three letters are enough. A word of two letters is never cut.
        assertEquals("sea", SearchWords.english("моря"));
        assertEquals("ле", SearchWords.english("ле"));
    }

    // endregion

    // region Words that share a stem

    @Test
    public void flowersAndTheColourAreNotTheSameWord() {
        for (String form : new String[]{"цветок", "цветка", "цветку", "цветки", "цветков", "цветы", "Цветы", "цветов",
                "цветам", "цветами", "цветах", "цветочек", "цветочки", "цветочков", "цветочный", "цветочные"}) {
            assertEquals(form, "flowers", SearchWords.english(form));
        }
        // The colour is not "flowers": it is the colour (Wallhaven has thousands of pictures for both words).
        assertEquals("color", SearchWords.english("цвет"));
        assertEquals("color", SearchWords.english("Цвет"));
        assertEquals("color", SearchWords.english("цвету"));
        assertEquals("color", SearchWords.english("цветом"));
        assertEquals("color", SearchWords.english("цвете"));
        assertEquals("colors", SearchWords.english("цвета"));
        assertEquals("red color", SearchWords.english("красный цвет"));
        assertEquals("bright colors", SearchWords.english("яркие цвета"));
        assertEquals("beautiful flowers", SearchWords.english("красивые цветы"));
        assertEquals("colorful", SearchWords.english("цветной"));
    }

    @Test
    public void brownAndAStormAreNotTheSameWord() {
        for (String form : new String[]{"бурый", "бурая", "бурое", "бурые", "бурого", "бурой", "бурому", "бурым",
                "бурую", "бурыми", "бурых", "буром"}) {
            assertEquals(form, "brown", SearchWords.english(form));
        }
        for (String form : new String[]{"буря", "бури", "бурю", "бурей", "бурь", "бурями", "Буря"}) {
            assertEquals(form, "storm", SearchWords.english(form));
        }
        assertEquals("brown bear", SearchWords.english("бурый медведь"));
    }

    @Test
    public void iceAndALadyAreNotTheSameWord() {
        assertEquals("lady", SearchWords.english("леди"));
        assertEquals("lady", SearchWords.english("Леди"));
        assertEquals("anime lady", SearchWords.english("аниме леди"));
        for (String form : new String[]{"лёд", "лед", "льда", "льду", "льдом", "льды", "ледяной", "ледяные"}) {
            assertEquals(form, "ice", SearchWords.english(form));
        }
        assertEquals("ice lady", SearchWords.english("лёд и леди"));
    }

    @Test
    public void summerIsNotTheWordForYears() {
        for (String form : new String[]{"лето", "лета", "лету", "летом", "лете", "летний", "летняя", "летние"}) {
            assertEquals(form, "summer", SearchWords.english(form));
        }
        assertEquals("лет", SearchWords.english("лет"));
        assertEquals("лёт", SearchWords.english("лёт"));
        assertEquals("сто лет", SearchWords.english("сто лет"));
    }

    @Test
    public void aLionIsNotLeft() {
        for (String form : new String[]{"лев", "Лев", "льва", "льву", "львом", "львы"}) {
            assertEquals(form, "lion", SearchWords.english(form));
        }
        for (String word : new String[]{"левый", "левая", "левое", "левой", "левые"}) {
            assertEquals(word, word, SearchWords.english(word));
        }
    }

    @Test
    public void aFieldIsNotAFloor() {
        for (String form : new String[]{"поле", "поля", "полю", "полем", "полей", "полям", "полями", "полях"}) {
            assertEquals(form, "field", SearchWords.english(form));
        }
        for (String word : new String[]{"пол", "пола", "полу", "полом", "полы"}) {
            assertEquals(word, word, SearchWords.english(word));
        }
    }

    @Test
    public void anIslandIsNotSharp() {
        for (String form : new String[]{"остров", "острова", "острову", "островом", "острове", "островов", "островам",
                "островами", "островах"}) {
            assertEquals(form, "island", SearchWords.english(form));
        }
        for (String word : new String[]{"острый", "острая", "острое", "острые"}) {
            assertEquals(word, word, SearchWords.english(word));
        }
    }

    @Test
    public void chinaInEveryCaseIsNotAWhale() {
        for (String form : new String[]{"китай", "Китая", "Китае", "Китаю", "Китаем"}) {
            assertEquals(form, "china", SearchWords.english(form));
        }
        for (String form : new String[]{"кит", "кита", "киту", "киты", "китом", "китов"}) {
            assertEquals(form, "whale", SearchWords.english(form));
        }
        assertEquals("mountains china", SearchWords.english("горы Китая"));
    }

    @Test
    public void aPigeonIsNotLightBlue() {
        for (String form : new String[]{"голубь", "голуби", "голубя", "голубю", "голубем", "голубе", "голубей",
                "голубям", "голубями", "голубях"}) {
            assertEquals(form, "pigeon", SearchWords.english(form));
        }
        for (String form : new String[]{"голубой", "голубая", "голубое", "голубые", "голубого", "голубым"}) {
            assertEquals(form, "light blue", SearchWords.english(form));
        }
        assertEquals("light blue sky", SearchWords.english("голубое небо"));
    }

    @Test
    public void aMangoIsNotManga() {
        assertEquals("mango", SearchWords.english("манго"));
        for (String form : new String[]{"манга", "манги", "мангу", "манге", "мангой"}) {
            assertEquals(form, "manga", SearchWords.english(form));
        }
    }

    @Test
    public void grayIsNotASeriesOrSulphur() {
        for (String form : new String[]{"серый", "серая", "серое", "серые", "серого", "серой", "серому", "серым",
                "серую", "серыми", "серых", "сером"}) {
            assertEquals(form, "gray", SearchWords.english(form));
        }
        for (String word : new String[]{"серия", "серии", "серию", "сера", "серы"}) {
            assertEquals(word, word, SearchWords.english(word));
        }
    }

    @Test
    public void aRoadIsNotExpensive() {
        for (String form : new String[]{"дорога", "дороги", "дорогу", "дороге", "дорог", "дорогам", "дорогами",
                "дорогах"}) {
            assertEquals(form, "road", SearchWords.english(form));
        }
        for (String word : new String[]{"дорогой", "дорогая", "дорогое", "дорогие", "дорого"}) {
            assertEquals(word, word, SearchWords.english(word));
        }
    }

    @Test
    public void theLosOfLosAngelesIsNotAMoose() {
        assertEquals("los angeles", SearchWords.english("Лос-Анджелес"));
        assertEquals("los angeles", SearchWords.english("лос анджелес"));
        assertEquals("los angeles", SearchWords.english("в Лос-Анджелесе"));
        assertEquals("los angeles sunset", SearchWords.english("Лос-Анджелес закат"));
        for (String form : new String[]{"лось", "лося", "лоси"}) {
            assertEquals(form, "moose", SearchWords.english(form));
        }
    }

    @Test
    public void aTurtleIsNotASkullInEveryCase() {
        for (String form : new String[]{"черепаха", "черепахи", "черепаху", "черепахе", "черепахой", "черепах",
                "черепахах", "черепахами"}) {
            assertEquals(form, "turtle", SearchWords.english(form));
        }
        for (String form : new String[]{"череп", "черепа", "черепу", "черепом"}) {
            assertEquals(form, "skull", SearchWords.english(form));
        }
    }

    @Test
    public void commonWordsThatOnlyLookLikeADictionaryWordAreKept() {
        // "кот" and "котёл", "лес" and "лестница", "луна" and "лук": a different word is not cut down to a known stem.
        for (String word : new String[]{"котёл", "котла", "лестница", "лестницы", "мирный", "мирное", "сонный",
                "лук"}) {
            assertEquals(word, word, SearchWords.english(word));
        }
        assertEquals("cat", SearchWords.english("кот"));
        assertEquals("forest", SearchWords.english("лес"));
        assertEquals("moon", SearchWords.english("луна"));
        assertEquals("meadow", SearchWords.english("луг"));
        // The night and what is "of the night" mean the same, so they share a stem on purpose.
        assertEquals("night", SearchWords.english("ночь"));
        assertEquals("night", SearchWords.english("ночи"));
        assertEquals("night", SearchWords.english("ночной"));
    }

    // endregion

    // region Phrases

    @Test
    public void aPhraseIsTranslatedAsOne() {
        assertEquals("night city", SearchWords.english("ночной город"));
        assertEquals("starry sky", SearchWords.english("звёздное небо"));
        assertEquals("new year", SearchWords.english("новый год"));
        assertEquals("black hole", SearchWords.english("чёрная дыра"));
        assertEquals("black background", SearchWords.english("чёрный фон"));
        assertEquals("black background", SearchWords.english("обои на чёрном фоне"));
        assertEquals("milky way", SearchWords.english("млечный путь"));
        assertEquals("aurora", SearchWords.english("северное сияние"));
    }

    @Test
    public void aPhraseHasEndingsToo() {
        assertEquals("night city", SearchWords.english("ночного города"));
        assertEquals("night city", SearchWords.english("ночные города"));
        assertEquals("night city", SearchWords.english("в ночном городе"));
        assertEquals("starry sky", SearchWords.english("звёздного неба"));
        assertEquals("aurora", SearchWords.english("северного сияния"));
    }

    @Test
    public void aPhraseBeatsItsSingleWords() {
        assertEquals("night", SearchWords.english("ночной"));
        assertEquals("city", SearchWords.english("город"));
        assertEquals("night city", SearchWords.english("ночной город"));
        assertEquals("beautiful night city", SearchWords.english("красивый ночной город"));
        assertEquals("night city sunset", SearchWords.english("ночной город закат"));
    }

    @Test
    public void extraSpacesInsideAPhraseDoNotBreakIt() {
        assertEquals("night city", SearchWords.english("ночной   город"));
        assertEquals("night city", SearchWords.english("Ночной  Город"));
    }

    @Test
    public void aCommaBreaksAPhraseIntoItsWords() {
        assertEquals("night, city", SearchWords.english("ночной, город"));
    }

    @Test
    public void aHyphenAndASpaceInsideAPhraseAreTheSame() {
        assertEquals("pixel art", SearchWords.english("пиксель-арт"));
        assertEquals("pixel art", SearchWords.english("пиксель арт"));
        assertEquals("pixel art", SearchWords.english("Пиксель-Арт"));
        assertEquals("black and white", SearchWords.english("чёрно-белый"));
        assertEquals("black and white", SearchWords.english("чёрно-белое"));
        assertEquals("anime girl", SearchWords.english("аниме-девушка"));
        assertEquals("anime girl", SearchWords.english("аниме девушки"));
    }

    @Test
    public void aHyphenNextToAKnownWordBecomesASpace() {
        assertEquals("anime Иннокентий", SearchWords.english("аниме-Иннокентий"));
    }

    // endregion

    // region Mixed and unknown text

    @Test
    public void russianAndEnglishWordsMixInOneText() {
        assertEquals("space wallpaper", SearchWords.english("космос wallpaper"));
        assertEquals("anime cat", SearchWords.english("anime кошка"));
        assertEquals("4k beautiful mountains", SearchWords.english("4k красивые горы"));
        assertEquals("hd cat 1080p", SearchWords.english("hd кот 1080p"));
        assertEquals("Ghibli forest", SearchWords.english("Ghibli лес"));
    }

    @Test
    public void unknownRussianWordsAndNamesAreKeptAsTyped() {
        assertEquals("Иннокентий", SearchWords.english("Иннокентий"));
        assertEquals("space Иннокентий", SearchWords.english("космос Иннокентий"));
        assertEquals("Ленинград sunset", SearchWords.english("Ленинград закат"));
        assertEquals("кто-то", SearchWords.english("кто-то"));
        // A Ukrainian word is Cyrillic too, but the dictionary is Russian.
        assertEquals("кіт", SearchWords.english("кіт"));
    }

    @Test
    public void aWholeSentenceIsTranslatedWordByWord() {
        assertEquals("beautiful dark forest winter", SearchWords.english("красивый тёмный лес зимой"));
        assertEquals("white cat snow", SearchWords.english("белая кошка снег"));
    }

    @Test
    public void spacesAndPunctuationStayWhereTheyWere() {
        assertEquals("space, mountains!", SearchWords.english("космос, горы!"));
        assertEquals("  space   mountains ", SearchWords.english("  космос   горы "));
        assertEquals("(space)", SearchWords.english("(космос)"));
        assertEquals("space\tsea", SearchWords.english("космос\tморе"));
    }

    @Test
    public void searchOperatorsInFrontOfAWordAreKept() {
        assertEquals("-space", SearchWords.english("-космос"));
        assertEquals("mountains -space", SearchWords.english("горы -космос"));
        assertEquals("#cat", SearchWords.english("#кот"));
        assertEquals("+cat", SearchWords.english("+кот"));
    }

    @Test
    public void aWordGluedToALatinLetterADigitOrAnAtSignIsLeftAlone() {
        assertEquals("4к", SearchWords.english("4к"));
        assertEquals("кот2", SearchWords.english("кот2"));
        assertEquals("@кот", SearchWords.english("@кот"));
        assertEquals("cat_кот", SearchWords.english("cat_кот"));
        assertEquals("4к space", SearchWords.english("4к космос"));
    }

    // endregion

    // region Filler

    @Test
    public void fillerWordsAreDropped() {
        assertEquals("mountains sea", SearchWords.english("горы и море"));
        assertEquals("beautiful mountains sunset", SearchWords.english("красивые горы на закате"));
        assertEquals("mountains", SearchWords.english("в горах"));
        assertEquals("space", SearchWords.english("обои космос"));
        assertEquals("space", SearchWords.english("космос обои на телефон"));
        assertEquals("cat", SearchWords.english("фон для кота"));
    }

    @Test
    public void aFillerWordAtTheEdgeTakesItsSpaceWithIt() {
        assertEquals("mountains", SearchWords.english("горы и"));
        assertEquals("mountains", SearchWords.english("и горы"));
        assertEquals("mountains", SearchWords.english("горы и в"));
        assertEquals("mountains sea", SearchWords.english("горы и в море"));
        assertEquals("mountains, sea", SearchWords.english("горы, и море"));
    }

    @Test
    public void aTextOfNothingButFillerStaysAsTyped() {
        assertEquals("обои", SearchWords.english("обои"));
        assertEquals("обои на телефон", SearchWords.english("обои на телефон"));
        assertEquals("и", SearchWords.english("и"));
    }

    @Test
    public void aFillerWordNotSetApartBySpacesStays() {
        assertEquals("(и) mountains", SearchWords.english("(и) горы"));
        assertEquals("mountains и, sea", SearchWords.english("горы и, море"));
        assertEquals("4к space", SearchWords.english("обои 4к космос"));
        assertEquals("4к", SearchWords.english("4к обои"));
    }

    @Test
    public void aWordThatOnlyStartsLikeAFillerWordIsNotFiller() {
        assertEquals("field", SearchWords.english("поле"));
        assertEquals("подарок", SearchWords.english("подарок"));
        assertEquals("пода", SearchWords.english("пода"));
    }

    // endregion

    // region No Russian

    @Test
    public void aTextWithoutCyrillicIsReturnedAsItIs() {
        for (String text : new String[]{"space", "4k wallpaper", "cat -dog", "#anime", "123", "ünïcode", "日本語"}) {
            assertSame(text, SearchWords.english(text));
        }
    }

    @Test
    public void anEmptyOrBlankTextIsReturnedAsItIs() {
        assertSame("", SearchWords.english(""));
        assertEquals("   ", SearchWords.english("   "));
        assertNull(SearchWords.english(null));
    }

    // endregion

    // region Dictionary

    @Test
    public void theDictionaryHasEnoughEntries() {
        assertTrue("entries: " + SearchWords.entries().size(), SearchWords.entries().size() >= 250);
    }

    @Test
    public void noWordIsGivenTwoTranslations() {
        assertTrue(SearchWords.clashes().toString(), SearchWords.clashes().isEmpty());
    }

    @Test
    public void everyEntryTranslatesToItsOwnEnglish() {
        for (Map.Entry<String, String> entry : SearchWords.entries().entrySet()) {
            assertEquals(entry.getKey(), entry.getValue(), SearchWords.english(entry.getKey()));
        }
    }

    @Test
    public void theEnglishIsPlainLowerCaseWords() {
        for (String english : SearchWords.entries().values()) {
            assertTrue(english, english.matches("[a-z][a-z -]*[a-z]"));
        }
    }

    // endregion

    @Test
    public void theFirstHalfOfACompoundColourAndABloomAreKnown() {
        String grayBlue = SearchWords.english("серо-голубой");
        assertTrue(grayBlue, grayBlue.startsWith("gray") && grayBlue.endsWith("light blue"));
        assertEquals("bloom", SearchWords.english("цветение"));
        assertEquals("colorful", SearchWords.english("цветной"));
    }
}
