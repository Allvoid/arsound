package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;

public class SettingsTransferTest {
    private static final String SETTINGS = "revanced_soundcloud";

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static Set<String> set(String... items) {
        return new HashSet<>(Arrays.asList(items));
    }

    private static Map<String, Map<String, ?>> phone(String file, Map<String, Object> values) {
        Map<String, Map<String, ?>> files = new LinkedHashMap<>();
        files.put(file, values);
        return files;
    }

    private static void assertRefused(String json, TransferException.Reason reason) {
        try {
            SettingsTransfer.parse(json);
            fail("accepted: " + json);
        } catch (TransferException ex) {
            assertEquals(reason, ex.reason);
        }
    }

    /** A settings file with one file section holding the given typed sections, as text. */
    private static String fileWith(String file, String sections) {
        return "{\"format\": \"arsound-settings\", \"formatVersion\": 1, \"files\": {\"" + file + "\": {" + sections + "}}}";
    }

    // region Export and the way back

    @Test
    public void everyKindOfValueComesBackTheSame() throws Exception {
        Map<String, Object> values = map(
                "a_boolean", true, "another_boolean", false,
                "an_int", 42, "a_negative_int", -7, "int_max", Integer.MAX_VALUE, "int_min", Integer.MIN_VALUE,
                "a_long", 8L, "long_max", Long.MAX_VALUE, "long_min", Long.MIN_VALUE,
                "a_float", 0.1f, "a_big_float", 3.4028235E38f, "a_whole_float", 2f,
                "a_string", "pine", "an_empty_string", "",
                "a_set", set("b", "a", "c"), "an_empty_set", new HashSet<String>());
        TransferExport export = SettingsTransfer.export(phone(SETTINGS, values), "1.0.1", "2026-11-03");
        assertEquals(values.size(), export.valueCount);
        assertEquals(0, export.skipped);

        Map<String, Object> back = SettingsTransfer.parse(export.json).files.get(SETTINGS);
        assertEquals(values, back);
        // equals() alone would not tell an Integer from a Long: the types are compared too.
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            assertEquals(entry.getKey(), SettingsTransfer.typeOf(entry.getValue()), SettingsTransfer.typeOf(back.get(entry.getKey())));
        }
        assertTrue(back.get("an_int") instanceof Integer);
        assertTrue(back.get("a_long") instanceof Long);
        assertTrue(back.get("a_float") instanceof Float);
        assertTrue(back.get("a_set") instanceof Set);
    }

    @Test
    public void aSmallNumberKeptAsALongIsNotTurnedIntoAnInt() throws Exception {
        TransferBackup backup = SettingsTransfer.parse(
                SettingsTransfer.export(phone(SETTINGS, map("size", 5L, "count", 5)), "1", "2026-11-03").json);
        assertEquals(Long.valueOf(5), backup.files.get(SETTINGS).get("size"));
        assertEquals(Integer.valueOf(5), backup.files.get(SETTINGS).get("count"));
    }

    @Test
    public void floatsComeBackBitForBit() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        float[] tricky = {0.1f, -0.1f, 1e-10f, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE, Float.MIN_NORMAL, 0f, 16777217f,
                1f / 3f, 100f, 1.17549435E-38f};
        for (int i = 0; i < tricky.length; i++) values.put("tricky_" + i, tricky[i]);
        Random random = new Random(7);
        for (int i = 0; i < 3000; i++) {
            float f = Float.intBitsToFloat(random.nextInt());
            if (!Float.isNaN(f) && !Float.isInfinite(f)) values.put("random_" + i, f);
        }
        TransferBackup backup = SettingsTransfer.parse(SettingsTransfer.export(phone(SETTINGS, values), "1", "2026-11-03").json);
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            Object back = backup.files.get(SETTINGS).get(entry.getKey());
            assertEquals(entry.getKey(), Float.floatToIntBits((Float) entry.getValue()), Float.floatToIntBits((Float) back));
        }
    }

    @Test
    public void aFloatJsonCannotSayIsLeftOutAndCounted() throws Exception {
        TransferExport export = SettingsTransfer.export(
                phone(SETTINGS, map("nan", Float.NaN, "inf", Float.POSITIVE_INFINITY, "fine", 1.5f)), "1", "2026-11-03");
        assertEquals(1, export.valueCount);
        assertEquals(2, export.skipped);
        assertEquals(map("fine", 1.5f), SettingsTransfer.parse(export.json).files.get(SETTINGS));
    }

    @Test
    public void awkwardTextSurvives() throws Exception {
        Map<String, Object> values = map(
                "quotes", "say \"hi\" and \\ go", "lines", "one\ntwo\r\nthree\tfour",
                "russian", "Привет, мир — «тест»",
                "emoji", new String(Character.toChars(0x1F3B5)) + " note", "control", "a\u0001b\u001fc", "json", "{\"a\": [1, 2]}", "html", "<b>&amp;</b>",
                "a\"odd\\key\n", "value of an odd key", "soundcloud:tracks:99", "A title | with: marks");
        TransferBackup backup = SettingsTransfer.parse(SettingsTransfer.export(phone("arsound_dislikes", values), "1", "2026-11-03").json);
        assertEquals(values, backup.files.get("arsound_dislikes"));
    }

    @Test
    public void aLongTextAndALargeSetSurvive() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 20000; i++) text.append("soundcloud:tracks:").append(i).append('\n');
        Set<String> big = new HashSet<>();
        for (int i = 0; i < 5000; i++) big.add("item " + i);
        Map<String, Object> values = map("playlist_order", text.toString(), "big_set", big);
        TransferBackup backup = SettingsTransfer.parse(SettingsTransfer.export(phone(SETTINGS, values), "1", "2026-11-03").json);
        assertEquals(values, backup.files.get(SETTINGS));
    }

    @Test
    public void theFileIsTheSameWhateverTheOrderOfTheValues() {
        Map<String, Object> first = map("b", 1, "a", true, "c", set("y", "x"));
        Map<String, Object> second = map("c", set("x", "y"), "a", true, "b", 1);
        assertEquals(SettingsTransfer.export(phone(SETTINGS, first), "1", "2026-11-03").json,
                SettingsTransfer.export(phone(SETTINGS, second), "1", "2026-11-03").json);
    }

    @Test
    public void theFileLooksAsDocumented() {
        Map<String, Map<String, ?>> current = new LinkedHashMap<>();
        current.put(SETTINGS, map("crossfade", true, "arsound_theme", "pine", "stream_cache_size", 1073741824L));
        current.put("arsound_wave", map("ledger", "sabc=1"));
        String expected = "{\n"
                + "  \"format\": \"arsound-settings\",\n"
                + "  \"formatVersion\": 1,\n"
                + "  \"appVersion\": \"1.0.1\",\n"
                + "  \"exportedOn\": \"2026-11-03\",\n"
                + "  \"files\": {\n"
                + "    \"revanced_soundcloud\": {\n"
                + "      \"boolean\": {\n"
                + "        \"crossfade\": true\n"
                + "      },\n"
                + "      \"long\": {\n"
                + "        \"stream_cache_size\": 1073741824\n"
                + "      },\n"
                + "      \"string\": {\n"
                + "        \"arsound_theme\": \"pine\"\n"
                + "      }\n"
                + "    },\n"
                + "    \"arsound_dislikes\": {},\n"
                + "    \"arsound_dislike_songs\": {},\n"
                + "    \"arsound_wave\": {\n"
                + "      \"string\": {\n"
                + "        \"ledger\": \"sabc=1\"\n"
                + "      }\n"
                + "    },\n"
                + "    \"arsound_sleep_timer\": {},\n"
                + "    \"arsound_local_additions\": {}\n"
                + "  }\n"
                + "}\n";
        assertEquals(expected, SettingsTransfer.export(current, "1.0.1", "2026-11-03").json);
    }

    @Test
    public void everyFileOfThePlanIsListedEvenWhenEmpty() {
        String json = SettingsTransfer.export(Collections.<String, Map<String, ?>>emptyMap(), "1", "2026-11-03").json;
        for (TransferPlan.Source source : TransferPlan.SOURCES) assertTrue(source.file, json.contains("\"" + source.file + "\": {}"));
        // And yet an export of nothing is not something to import.
        assertRefused(json, TransferException.Reason.NOTHING_TO_IMPORT);
    }

    // endregion

    // region What never leaves

    @Test
    public void signInsAndKeysNeverGoIntoTheFile() {
        Map<String, Map<String, ?>> current = new LinkedHashMap<>();
        current.put("arsound_yandex", map("token", "SECRET-YANDEX", "expires", 5L, "name", "SECRET-NAME"));
        current.put("arsound_youtube_account", map("cookies", "SECRET-COOKIES"));
        current.put(SETTINGS, map(
                "arsound_wallhaven_key", "SECRET-WALLHAVEN", "arsound_wallhaven_adult", true, "arsound_wallhaven_last", "SECRET-QUERY",
                "someone_token", "SECRET-TOKEN-KEY", "refresh_Session", "SECRET-SESSION", "x_api_key", "SECRET-API",
                "password", "SECRET-PASSWORD", "auth_cookie", "SECRET-COOKIE", "arsound_theme", "pine"));
        String json = SettingsTransfer.export(current, "1", "2026-11-03").json;
        assertFalse(json, json.contains("SECRET"));
        assertFalse(json.contains("arsound_yandex"));
        assertFalse(json.contains("arsound_youtube_account"));
        assertTrue(json.contains("\"arsound_theme\": \"pine\""));
    }

    @Test
    public void whatBelongsToThisPhoneStaysOnIt() throws Exception {
        Map<String, Map<String, ?>> current = new LinkedHashMap<>();
        current.put(SETTINGS, map(
                "download_folder", "tree:content://x", "arsound_theme_forced_night", "yes", "arsound_splash_style", "Arsound.Splash.Pine",
                "stream_cache_used", 5L, "stream_cache_available", 6L, "stream_cache_clear", true, "arsound_wallpaper_enabled", true,
                "file_logging", true, "developer_mode", true, "developer_network_delay_seconds", 3, "playback_log", true,
                "lyrics_ai_heavy_notice_seen", true, "crossfade", true));
        current.put("arsound_sleep_timer", map("stop_at", 123L, "at_clock", true));
        current.put("arsound_local_additions", map("additions", "{}", "titles", "{}", "likes_liked", set("file:1"),
                "saved_playlist_urn", "soundcloud:playlists:1", "playlist_order", "a\nb", "track_order_p", "c\nd"));
        current.put("arsound_for_you", map("playlist_urn", "soundcloud:playlists:2"));
        current.put("arsound_own_playlists", map("urns", set("soundcloud:playlists:2")));
        current.put("revanced_soundcloud_downloads", map("track", "file"));
        TransferBackup backup = SettingsTransfer.parse(SettingsTransfer.export(current, "1", "2026-11-03").json);

        assertEquals(map("crossfade", true), backup.files.get(SETTINGS));
        assertEquals(map("at_clock", true), backup.files.get("arsound_sleep_timer"));
        assertEquals(map("playlist_order", "a\nb", "track_order_p", "c\nd"), backup.files.get("arsound_local_additions"));
        assertFalse(backup.files.containsKey("arsound_for_you"));
        assertFalse(backup.files.containsKey("arsound_own_playlists"));
        assertFalse(backup.files.containsKey("revanced_soundcloud_downloads"));
    }

    // endregion

    // region Refusing files

    @Test
    public void aDamagedFileIsRefused() {
        String good = SettingsTransfer.export(phone(SETTINGS, map("crossfade", true)), "1", "2026-11-03").json;
        assertRefused(good.substring(0, good.length() / 2), TransferException.Reason.NOT_JSON);
        assertRefused("", TransferException.Reason.NOT_JSON);
        assertRefused("not json at all", TransferException.Reason.NOT_JSON);
        assertRefused(good + good, TransferException.Reason.NOT_JSON);
    }

    @Test
    public void aFileNestedFarTooDeepIsNotJsonAndNeverCrashes() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 100_000; i++) deep.append('[');
        assertRefused(deep.toString(), TransferException.Reason.NOT_JSON);
        // A little deeper than the limit is refused too, a file at the limit is read (and then found to be no settings file).
        StringBuilder atLimit = new StringBuilder(), over = new StringBuilder();
        for (int i = 0; i <= SettingsTransfer.MAX_JSON_DEPTH; i++) atLimit.append('[');
        for (int i = 0; i <= SettingsTransfer.MAX_JSON_DEPTH; i++) atLimit.append(']');
        for (int i = 0; i <= SettingsTransfer.MAX_JSON_DEPTH + 1; i++) over.append('[');
        for (int i = 0; i <= SettingsTransfer.MAX_JSON_DEPTH + 1; i++) over.append(']');
        assertRefused(atLimit.toString(), TransferException.Reason.NOT_SETTINGS);
        assertRefused(over.toString(), TransferException.Reason.NOT_JSON);
    }

    @Test
    public void aFileOfAnotherProgramIsRefused() {
        assertRefused("{\"some\": \"thing\"}", TransferException.Reason.NOT_SETTINGS);
        assertRefused("[1, 2, 3]", TransferException.Reason.NOT_SETTINGS);
        assertRefused("\"text\"", TransferException.Reason.NOT_SETTINGS);
        assertRefused("5", TransferException.Reason.NOT_SETTINGS);
        assertRefused("null", TransferException.Reason.NOT_SETTINGS);
        assertRefused("{\"format\": \"other\", \"formatVersion\": 1, \"files\": {}}", TransferException.Reason.NOT_SETTINGS);
        assertRefused("{\"formatVersion\": 1, \"files\": {}}", TransferException.Reason.NOT_SETTINGS);
    }

    @Test
    public void aBrokenHeaderIsRefused() {
        assertRefused("{\"format\": \"arsound-settings\", \"files\": {}}", TransferException.Reason.NOT_SETTINGS);
        assertRefused("{\"format\": \"arsound-settings\", \"formatVersion\": \"1\", \"files\": {}}", TransferException.Reason.NOT_SETTINGS);
        assertRefused("{\"format\": \"arsound-settings\", \"formatVersion\": 1.5, \"files\": {}}", TransferException.Reason.NOT_SETTINGS);
        assertRefused("{\"format\": \"arsound-settings\", \"formatVersion\": 0, \"files\": {}}", TransferException.Reason.NOT_SETTINGS);
        assertRefused("{\"format\": \"arsound-settings\", \"formatVersion\": -1, \"files\": {}}", TransferException.Reason.NOT_SETTINGS);
        assertRefused("{\"format\": \"arsound-settings\", \"formatVersion\": 1}", TransferException.Reason.NOT_SETTINGS);
        assertRefused("{\"format\": \"arsound-settings\", \"formatVersion\": 1, \"files\": []}", TransferException.Reason.NOT_SETTINGS);
    }

    @Test
    public void aNewerLayoutIsRefusedWithItsOwnReason() {
        assertRefused("{\"format\": \"arsound-settings\", \"formatVersion\": 2, \"files\": {}}", TransferException.Reason.NEWER_FORMAT);
        assertRefused("{\"format\": \"arsound-settings\", \"formatVersion\": 99, \"files\": {}}", TransferException.Reason.NEWER_FORMAT);
    }

    @Test
    public void aFileBeyondTheLimitIsRefused() {
        StringBuilder big = new StringBuilder("{\"format\": \"arsound-settings\", \"formatVersion\": 1, \"files\": {}, \"pad\": \"");
        while (big.length() <= SettingsTransfer.MAX_BYTES) big.append("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        big.append("\"}");
        assertRefused(big.toString(), TransferException.Reason.TOO_BIG);
    }

    @Test
    public void aFileWithNothingToTakeIsRefused() {
        assertRefused("{\"format\": \"arsound-settings\", \"formatVersion\": 1, \"files\": {}}", TransferException.Reason.NOTHING_TO_IMPORT);
        assertRefused(fileWith(SETTINGS, ""), TransferException.Reason.NOTHING_TO_IMPORT);
        assertRefused(fileWith(SETTINGS, "\"string\": {\"download_folder\": \"x\", \"my_token\": \"y\"}"),
                TransferException.Reason.NOTHING_TO_IMPORT);
        assertRefused(fileWith("arsound_yandex", "\"string\": {\"token\": \"x\"}"), TransferException.Reason.NOTHING_TO_IMPORT);
    }

    // endregion

    // region Reading what is in a file

    @Test
    public void theHeaderIsReadForTheUserToSee() throws Exception {
        TransferBackup backup = SettingsTransfer.parse("{\"format\": \"arsound-settings\", \"formatVersion\": 1, \"appVersion\": \"1.0.1-rc.1\", "
                + "\"exportedOn\": \"2026-11-03\", \"files\": {\"" + SETTINGS + "\": {\"boolean\": {\"a\": true}}}}");
        assertEquals(1, backup.formatVersion);
        assertEquals("1.0.1-rc.1", backup.appVersion);
        assertEquals("2026-11-03", backup.date);
        assertEquals(1, backup.valueCount());
    }

    @Test
    public void anOddHeaderIsDroppedNotShown() throws Exception {
        TransferBackup backup = SettingsTransfer.parse("{\"format\": \"arsound-settings\", \"formatVersion\": 1, \"appVersion\": \"1.0 <script>\", "
                + "\"exportedOn\": \"yesterday\", \"files\": {\"" + SETTINGS + "\": {\"boolean\": {\"a\": true}}}}");
        assertEquals("", backup.appVersion);
        assertEquals("", backup.date);
        assertEquals("", SettingsTransfer.parse("{\"format\": \"arsound-settings\", \"formatVersion\": 1, \"appVersion\": 5, "
                + "\"exportedOn\": null, \"files\": {\"" + SETTINGS + "\": {\"boolean\": {\"a\": true}}}}").appVersion);
    }

    @Test
    public void aHandWrittenFileWithoutTheHeaderExtrasIsFine() throws Exception {
        TransferBackup backup = SettingsTransfer.parse(String.valueOf((char) 0xFEFF) + fileWith(SETTINGS, "\"boolean\": {\"a\": true}"));
        assertEquals("", backup.appVersion);
        assertEquals("", backup.date);
        assertEquals(map("a", true), backup.files.get(SETTINGS));
    }

    @Test
    public void valuesOfTheWrongShapeAreSkippedAndCounted() throws Exception {
        TransferBackup backup = SettingsTransfer.parse(fileWith(SETTINGS,
                "\"boolean\": {\"bad_bool\": \"yes\", \"ok_bool\": true},"
                        + "\"int\": {\"fraction\": 1.5, \"too_big\": 3000000000, \"ok_int\": 7, \"text_int\": \"7\"},"
                        + "\"long\": {\"bad_long\": \"x\", \"ok_long\": 3000000000},"
                        + "\"float\": {\"bad_float\": \"x\", \"whole_float\": 1, \"huge\": 1e999},"
                        + "\"string\": {\"bad_string\": 5, \"ok_string\": \"x\"},"
                        + "\"stringSet\": {\"mixed\": [\"a\", 1], \"not_a_list\": \"a\", \"ok_set\": [\"a\", \"b\", \"a\"]}"));
        assertEquals(map("ok_bool", true, "ok_int", 7, "ok_long", 3000000000L, "whole_float", 1f, "ok_string", "x",
                "ok_set", set("a", "b")), backup.files.get(SETTINGS));
        // bad_bool, fraction, too_big, text_int, bad_long, bad_float, huge, bad_string, mixed, not_a_list
        assertEquals(10, backup.wrongType);
        assertEquals(0, backup.ignored);
    }

    @Test
    public void aSectionThatIsNotAnObjectCountsAsWrong() throws Exception {
        TransferBackup backup = SettingsTransfer.parse(fileWith(SETTINGS, "\"boolean\": [true], \"string\": {\"a\": \"b\"}"));
        assertEquals(map("a", "b"), backup.files.get(SETTINGS));
        assertEquals(1, backup.wrongType);
    }

    @Test
    public void aFileEntryThatIsNotAnObjectIsSkipped() throws Exception {
        TransferBackup backup = SettingsTransfer.parse("{\"format\": \"arsound-settings\", \"formatVersion\": 1, \"files\": {\"" + SETTINGS
                + "\": 5, \"arsound_wave\": {\"string\": {\"ledger\": \"x\"}}}}");
        assertFalse(backup.files.containsKey(SETTINGS));
        assertEquals(1, backup.wrongType);
        assertEquals(map("ledger", "x"), backup.files.get("arsound_wave"));
    }

    @Test
    public void aKeyFromANewerVersionIsKept() throws Exception {
        TransferBackup backup = SettingsTransfer.parse(fileWith(SETTINGS,
                "\"boolean\": {\"setting_of_the_future\": true}, \"string\": {\"another_one\": \"v\"}"));
        assertEquals(map("setting_of_the_future", true, "another_one", "v"), backup.files.get(SETTINGS));
        assertEquals(0, backup.ignored);
    }

    @Test
    public void aFileOrATypeFromANewerVersionIsIgnoredAndCounted() throws Exception {
        TransferBackup backup = SettingsTransfer.parse("{\"format\": \"arsound-settings\", \"formatVersion\": 1, \"files\": {"
                + "\"arsound_future_file\": {\"boolean\": {\"x\": true, \"y\": false}, \"string\": {\"z\": \"1\"}},"
                + "\"" + SETTINGS + "\": {\"boolean\": {\"a\": true}, \"complex\": {\"c1\": [1], \"c2\": [2]}}}}");
        assertFalse(backup.files.containsKey("arsound_future_file"));
        assertEquals(map("a", true), backup.files.get(SETTINGS));
        assertEquals(5, backup.ignored);
        assertEquals(0, backup.wrongType);
    }

    @Test
    public void aSignInPutIntoAFileIsIgnored() throws Exception {
        TransferBackup backup = SettingsTransfer.parse("{\"format\": \"arsound-settings\", \"formatVersion\": 1, \"files\": {"
                + "\"arsound_yandex\": {\"string\": {\"token\": \"ATTACKER\"}},"
                + "\"arsound_youtube_account\": {\"string\": {\"cookies\": \"ATTACKER\"}},"
                + "\"" + SETTINGS + "\": {\"string\": {\"arsound_wallhaven_key\": \"ATTACKER\", \"some_token\": \"ATTACKER\", "
                + "\"arsound_theme\": \"pine\"}}}}");
        assertEquals(map("arsound_theme", "pine"), backup.files.get(SETTINGS));
        assertFalse(backup.files.containsKey("arsound_yandex"));
        assertFalse(backup.files.containsKey("arsound_youtube_account"));
        assertEquals(4, backup.ignored);
    }

    @Test
    public void thePhonesOwnKeysAreNotTakenFromAFile() throws Exception {
        TransferBackup backup = SettingsTransfer.parse(fileWith(SETTINGS,
                "\"string\": {\"download_folder\": \"x\", \"arsound_splash_style\": \"x\", \"arsound_theme\": \"pine\"},"
                        + "\"boolean\": {\"arsound_theme_forced_night\": true, \"developer_mode\": true}"));
        assertEquals(map("arsound_theme", "pine"), backup.files.get(SETTINGS));
        assertEquals(4, backup.ignored);
    }

    @Test
    public void onlyTheKeysOfAPartialFileAreTaken() throws Exception {
        TransferBackup backup = SettingsTransfer.parse("{\"format\": \"arsound-settings\", \"formatVersion\": 1, \"files\": {"
                + "\"arsound_sleep_timer\": {\"long\": {\"stop_at\": 99}, \"boolean\": {\"at_clock\": true}},"
                + "\"arsound_local_additions\": {\"string\": {\"additions\": \"{}\", \"playlist_order\": \"a\"}}}}");
        assertEquals(map("at_clock", true), backup.files.get("arsound_sleep_timer"));
        assertEquals(map("playlist_order", "a"), backup.files.get("arsound_local_additions"));
        assertEquals(2, backup.ignored);
    }

    @Test
    public void theSameKeyInTwoSectionsKeepsTheFirst() throws Exception {
        TransferBackup backup = SettingsTransfer.parse(fileWith(SETTINGS, "\"boolean\": {\"a\": true}, \"string\": {\"a\": \"x\"}"));
        assertEquals(map("a", true), backup.files.get(SETTINGS));
        assertEquals(1, backup.wrongType);
    }

    // endregion

    // region What importing changes

    /** A backup built by exporting: the values pass through the real writer and reader. */
    private static TransferBackup backupOf(Object... fileAndValues) throws Exception {
        Map<String, Map<String, ?>> current = new LinkedHashMap<>();
        for (int i = 0; i < fileAndValues.length; i += 2) {
            @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) fileAndValues[i + 1];
            current.put((String) fileAndValues[i], values);
        }
        return SettingsTransfer.parse(SettingsTransfer.export(current, "1", "2026-11-03").json);
    }

    @Test
    public void theFileWinsOnEveryKeyThatTravels() throws Exception {
        TransferBackup backup = backupOf(SETTINGS, map("a", true, "b", "new"));
        Map<String, Map<String, ?>> here = phone(SETTINGS,
                map("a", false, "old_only", 3L, "download_folder", "tree:keep", "my_token", "keep", "developer_mode", true));
        TransferChanges changes = SettingsTransfer.plan(backup, here);
        assertEquals(map("a", true, "b", "new"), changes.writes.get(SETTINGS));
        // Gone back to their default: only the keys that travel; the phone's own and the secrets stay.
        assertEquals(Collections.singleton("old_only"), changes.removals.get(SETTINGS));
        assertEquals(2, changes.writeCount());
        assertEquals(1, changes.removeCount());
        assertEquals(0, changes.typeConflicts);
    }

    @Test
    public void aValueLeftOutOfTheFileKeepsThePhonesOwn() throws Exception {
        TransferBackup backup = SettingsTransfer.parse(fileWith(SETTINGS, "\"boolean\": {\"crossfade\": \"yes\", \"ok\": true}"));
        TransferChanges changes = SettingsTransfer.plan(backup, phone(SETTINGS, map("crossfade", true, "gone", true)));
        assertEquals(Collections.singleton("gone"), changes.removals.get(SETTINGS));
    }

    @Test
    public void aKeyThePhoneKeepsAsAnotherTypeIsLeftAlone() throws Exception {
        TransferBackup backup = backupOf(SETTINGS, map("size", 5, "fine", true));
        Map<String, Map<String, ?>> here = phone(SETTINGS, map("size", 7L, "fine", false));
        TransferChanges changes = SettingsTransfer.plan(backup, here);
        assertEquals(map("fine", true), changes.writes.get(SETTINGS));
        assertEquals(1, changes.typeConflicts);
        // It is neither written nor removed.
        assertNull(changes.removals.get(SETTINGS));
    }

    @Test
    public void aFileListedEmptyEmptiesThePhonesFile() throws Exception {
        TransferBackup backup = backupOf(SETTINGS, map("a", true), "arsound_wave", map());
        Map<String, Map<String, ?>> here = new LinkedHashMap<>();
        here.put(SETTINGS, map("a", true));
        here.put("arsound_wave", map("ledger", "sold=1"));
        TransferChanges changes = SettingsTransfer.plan(backup, here);
        assertEquals(Collections.singleton("ledger"), changes.removals.get("arsound_wave"));
    }

    @Test
    public void aFileTheBackupDoesNotListIsNotTouched() throws Exception {
        TransferBackup backup = SettingsTransfer.parse(fileWith(SETTINGS, "\"boolean\": {\"a\": true}"));
        Map<String, Map<String, ?>> here = new LinkedHashMap<>();
        here.put(SETTINGS, map("a", false));
        here.put("arsound_wave", map("ledger", "sold=1"));
        here.put("arsound_dislikes", map("soundcloud:tracks:1", "A song"));
        TransferChanges changes = SettingsTransfer.plan(backup, here);
        assertFalse(changes.removals.containsKey("arsound_wave"));
        assertFalse(changes.removals.containsKey("arsound_dislikes"));
        assertFalse(changes.writes.containsKey("arsound_wave"));
    }

    @Test
    public void aRunningSleepTimerAndTheOtherLocalDataStay() throws Exception {
        TransferBackup backup = backupOf("arsound_sleep_timer", map("at_clock", false),
                "arsound_local_additions", map("playlist_order", "c"));
        Map<String, Map<String, ?>> here = new LinkedHashMap<>();
        here.put("arsound_sleep_timer", map("stop_at", 123L, "at_clock", true));
        here.put("arsound_local_additions", map("additions", "{\"p\":[\"file\"]}", "titles", "{}", "playlist_order", "a\nb",
                "track_order_old", "x", "likes_liked", set("file:1")));
        TransferChanges changes = SettingsTransfer.plan(backup, here);
        assertEquals(map("at_clock", false), changes.writes.get("arsound_sleep_timer"));
        assertNull(changes.removals.get("arsound_sleep_timer"));
        assertEquals(map("playlist_order", "c"), changes.writes.get("arsound_local_additions"));
        assertEquals(Collections.singleton("track_order_old"), changes.removals.get("arsound_local_additions"));
    }

    @Test
    public void exportedSettingsLandOnAnotherPhoneAsTheyWere() throws Exception {
        Map<String, Map<String, ?>> old = new LinkedHashMap<>();
        old.put(SETTINGS, map("arsound_theme", "pine", "crossfade", true, "crossfade_seconds", 9L, "slowed_percent", 85L,
                "stream_cache_size", 1073741824L, "an_int", 4, "a_float", 0.25f, "arsound_wallhaven_key", "SECRET",
                "download_folder", "tree:old phone"));
        old.put("arsound_dislikes", map("soundcloud:tracks:1", "One", "soundcloud:tracks:2", "Two"));
        old.put("arsound_wave", map("ledger", "sone=1 htwo=2"));
        old.put("arsound_yandex", map("token", "SECRET"));

        Map<String, Map<String, Object>> fresh = new LinkedHashMap<>();
        fresh.put(SETTINGS, map("arsound_theme", "city", "left_over", true, "download_folder", "tree:new phone"));
        fresh.put("arsound_dislikes", map("soundcloud:tracks:9", "Nine"));

        TransferBackup backup = SettingsTransfer.parse(SettingsTransfer.export(old, "1.0.1", "2026-11-03").json);
        TransferChanges changes = SettingsTransfer.plan(backup, fresh);
        for (Map.Entry<String, Map<String, Object>> write : changes.writes.entrySet()) {
            fresh.computeIfAbsent(write.getKey(), name -> new HashMap<>()).putAll(write.getValue());
        }
        for (Map.Entry<String, Set<String>> removal : changes.removals.entrySet()) {
            fresh.get(removal.getKey()).keySet().removeAll(removal.getValue());
        }

        assertEquals(map("arsound_theme", "pine", "crossfade", true, "crossfade_seconds", 9L, "slowed_percent", 85L,
                "stream_cache_size", 1073741824L, "an_int", 4, "a_float", 0.25f, "download_folder", "tree:new phone"),
                fresh.get(SETTINGS));
        assertEquals(old.get("arsound_dislikes"), fresh.get("arsound_dislikes"));
        assertEquals(old.get("arsound_wave"), fresh.get("arsound_wave"));
        assertFalse(fresh.containsKey("arsound_yandex"));
    }

    // endregion

    // region Names

    @Test
    public void theFileNameCarriesTheDay() {
        assertEquals("arsound-settings-2026-11-03.json", SettingsTransfer.fileName("2026-11-03"));
    }

    @Test
    public void theDayFollowsTheZoneOfThePhone() {
        long evening = 1793748600000L; // 2026-11-03 23:30 UTC
        assertEquals("2026-11-03", SettingsTransfer.dateOf(evening, TimeZone.getTimeZone("UTC")));
        assertEquals("2026-11-04", SettingsTransfer.dateOf(evening, TimeZone.getTimeZone("GMT+05:00")));
        assertEquals("1970-01-01", SettingsTransfer.dateOf(0, TimeZone.getTimeZone("UTC")));
    }

    @Test
    public void theTypeNamesMatchSharedPreferences() {
        assertEquals("boolean", SettingsTransfer.typeOf(true));
        assertEquals("int", SettingsTransfer.typeOf(1));
        assertEquals("long", SettingsTransfer.typeOf(1L));
        assertEquals("float", SettingsTransfer.typeOf(1f));
        assertEquals("string", SettingsTransfer.typeOf("x"));
        assertEquals("stringSet", SettingsTransfer.typeOf(new HashSet<String>()));
        assertNull(SettingsTransfer.typeOf(1.0));
        assertNull(SettingsTransfer.typeOf(null));
    }

    // endregion
}
