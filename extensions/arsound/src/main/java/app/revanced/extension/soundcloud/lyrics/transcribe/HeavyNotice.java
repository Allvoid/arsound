package app.revanced.extension.soundcloud.lyrics.transcribe;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.ActivityManager;
import android.content.Context;

import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/**
 * The warning before the first download of the recognition: it is heavy work for a phone, slow even on a flagship
 * and hardly bearable on a weak one. Shown once, before the models are downloaded, from the lyrics page and from the
 * settings alike; on a phone that looks weak, the warning says so plainly.
 */
public final class HeavyNotice {
    private static final String SEEN_KEY = "lyrics_ai_heavy_notice_seen";
    /** Below this much memory, or with fewer cores, the phone counts as weak for the recognition. */
    private static final long WEAK_MEMORY_BYTES = 7L * 1024 * 1024 * 1024;
    private static final int WEAK_CORES = 8;

    private HeavyNotice() {
    }

    /** Whether the warning still has to be shown. */
    public static boolean needed() {
        return !Settings.getBoolean(SEEN_KEY, false);
    }

    /** The user agreed to a dialog that carried the warning ({@link #points}). */
    public static void markSeen() {
        Settings.putBoolean(SEEN_KEY, true);
    }

    /**
     * Shows the warning if it was not agreed to yet, and runs {@code onAgree} when the user goes on (at once, if the
     * warning was agreed to before).
     */
    public static void showThen(Context context, long downloadBytes, Runnable onAgree) {
        if (!needed()) {
            onAgree.run();
            return;
        }
        new ArsoundDialog(context)
                .setTitle(text("Это тяжёлая функция", "This is heavy work"))
                .setMessage(text("Нейросеть слушает песню прямо на телефоне и нагружает его почти как тяжёлая игра.\n\n",
                        "The neural network listens to the song right on the phone and loads it almost like a heavy "
                                + "game.\n\n") + points(context) + "\n\n"
                        + text("Будет скачано " + Sizes.format(downloadBytes) + ", один раз.",
                        Sizes.format(downloadBytes) + " will be downloaded, once."))
                .setPositiveButton(text("Понятно, продолжить", "Understood, go on"), (dialog, which) -> {
                    markSeen();
                    onAgree.run();
                })
                .setNegativeButton(text("Не сейчас", "Not now"), null)
                .show();
    }

    /** The points of the warning, with a line about this phone if it looks weak. Also part of the lyrics page's offer. */
    public static String points(Context context) {
        StringBuilder body = new StringBuilder();
        body.append(text("• Нужен мощный телефон: от 8 ГБ памяти и флагманский процессор последних лет. На средних и "
                        + "слабых телефонах распознавание идёт в разы дольше самой песни, телефон сильно греется, "
                        + "приложение может тормозить или закрываться.\n",
                "• It needs a powerful phone: 8 GB of memory or more and a flagship processor of recent years. On "
                        + "middle and weak phones the recognition takes several times longer than the song, the phone "
                        + "gets hot, and the app may lag or close.\n"));
        body.append(text("• Даже на флагмане это небыстро: сначала одна нейросеть отделяет голос от музыки, потом "
                        + "другая записывает слова. На Galaxy S25 Ultra песня в 3 минуты 17 секунд обрабатывается около "
                        + "5,5 минуты, на телефонах попроще — в разы дольше. Нужно около 1,5 ГБ свободной памяти.\n",
                "• Even on a flagship it is not quick: first one network takes the voice out of the music, then another "
                        + "writes down the words. On a Galaxy S25 Ultra a song of 3 minutes 17 seconds takes about 5.5 "
                        + "minutes, on simpler phones several times longer. About 1.5 GB of free memory is needed.\n"));
        body.append(text("• Тратит заряд: лучше на зарядке.\n", "• It uses the battery: better while charging.\n"));
        body.append(text("• Песню можно поставить на паузу или включить другую: распознавание продолжится, а готовый "
                        + "текст подсветится по времени с любого места.",
                "• The song can be paused or another one played: the recognition goes on, and the finished text is "
                        + "highlighted in time from any point."));
        String weak = weakness(context);
        if (weak != null) body.append("\n\n").append(weak);
        return body.toString();
    }

    /** What makes this phone weak for the work, or null if it looks fit. */
    private static String weakness(Context context) {
        long memory = totalMemory(context);
        int cores = Runtime.getRuntime().availableProcessors();
        if (memory >= WEAK_MEMORY_BYTES && cores >= WEAK_CORES) return null;
        String gigabytes = memory > 0 ? String.valueOf(Math.round(memory / (1024.0 * 1024 * 1024))) : "?";
        return text("Внимание: у этого телефона " + gigabytes + " ГБ памяти и " + cores + " ядер процессора. Скорее "
                        + "всего, распознавание будет очень медленным, а телефон будет сильно греться.",
                "Note: this phone has " + gigabytes + " GB of memory and " + cores + " processor cores. The recognition "
                        + "will most likely be very slow, and the phone will get hot.");
    }

    private static long totalMemory(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return -1;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return info.totalMem;
    }
}
