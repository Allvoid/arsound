package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import java.util.Calendar;
import java.util.Locale;

/** How the sleep timer writes times: a clock time, a span of minutes. No Android, so it runs in the JVM tests. */
final class TimerFormat {
    private TimerFormat() {
    }

    /** "23:05": the hour without a leading zero, the minutes with one. */
    static String clock(long at) {
        Calendar time = Calendar.getInstance();
        time.setTimeInMillis(at);
        return String.format(Locale.ROOT, "%d:%02d", time.get(Calendar.HOUR_OF_DAY), time.get(Calendar.MINUTE));
    }

    /** The whole minutes from {@code now} to {@code at}, rounded to the nearest and at least one. */
    static int minutesUntil(long at, long now) {
        return (int) Math.max(1, Math.round((at - now) / 60_000.0));
    }

    /** "1 h 15 min", "45 min". */
    static String duration(int minutes) {
        int hours = minutes / 60, rest = minutes % 60;
        if (hours == 0) return rest + text(" мин", " min");
        if (rest == 0) return hours + text(" ч", " h");
        return hours + text(" ч ", " h ") + rest + text(" мин", " min");
    }
}
