package app.revanced.extension.soundcloud.stats;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.graphics.Bitmap;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.player.ListeningStats;
import app.revanced.extension.soundcloud.player.YearReview;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/**
 * "Year in review" on the statistics screen: a list of the years with listening and "this period" (the period chosen
 * on the screen), then the card as a picture with a button that shares it. The numbers are counted in the background.
 */
public final class ReviewDialogs {
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    private ReviewDialogs() {
    }

    /**
     * @param periodDays The period of the statistics screen in days, 0 for all time.
     * @param accent     The accent colour of the app, for the card.
     */
    public static void open(Activity activity, long periodDays, int accent) {
        TimeZone zone = TimeZone.getDefault();
        Utils.showToastShort(text("Считаю итоги…", "Counting the review…"));
        Utils.runOnBackgroundThread(() -> {
            try {
                // The years come with the answer for the period: one pass over the history.
                YearReview.Result period = ListeningStats.review(periodStart(periodDays), Long.MAX_VALUE, zone);
                Utils.runOnMainThread(() -> {
                    if (gone(activity)) return;
                    choose(activity, period, periodDays, accent, zone);
                });
            } catch (Exception ex) {
                Logger.printException(() -> "Year in review: could not count", ex);
                Utils.runOnMainThread(() -> Utils.showToastLong(text("Не удалось посчитать итоги", "The review could not be counted")));
            }
        });
    }

    private static void choose(Activity activity, YearReview.Result period, long periodDays, int accent, TimeZone zone) {
        if (period.years.isEmpty()) {
            new ArsoundDialog(activity)
                    .setTitle(text("Итоги", "Year in review"))
                    .setMessage(text("Прослушиваний пока нет: итоги появятся, когда накопится статистика.",
                            "There is no listening yet: the review appears once the statistics have something."))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        List<String> labels = new ArrayList<>();
        for (int year : period.years) labels.add(text(year + " год", String.valueOf(year)));
        labels.add(text("Этот период", "This period"));
        new ArsoundDialog(activity)
                .setTitle(text("Итоги", "Year in review"))
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    if (which < period.years.size()) showYear(activity, period.years.get(which), accent, zone);
                    else showPeriod(activity, period, periodDays, accent);
                })
                .setNegativeButton(text("Закрыть", "Close"), null)
                .show();
    }

    private static void showYear(Activity activity, int year, int accent, TimeZone zone) {
        long[] bounds = YearReview.yearBounds(year, zone);
        Utils.showToastShort(text("Считаю итоги…", "Counting the review…"));
        Utils.runOnBackgroundThread(() -> {
            try {
                YearReview.Result result = ListeningStats.review(bounds[0], bounds[1], zone);
                Bitmap card = ReviewCard.render(result, String.valueOf(year), accent);
                Utils.runOnMainThread(() -> showCard(activity, card, "arsound-" + year, result.isEmpty()));
            } catch (Exception ex) {
                Logger.printException(() -> "Year in review: could not draw", ex);
                Utils.runOnMainThread(() -> Utils.showToastLong(text("Не удалось посчитать итоги", "The review could not be counted")));
            }
        });
    }

    private static void showPeriod(Activity activity, YearReview.Result period, long periodDays, int accent) {
        String title = periodTitle(periodDays);
        Utils.runOnBackgroundThread(() -> {
            try {
                Bitmap card = ReviewCard.render(period, title, accent);
                Utils.runOnMainThread(() -> showCard(activity, card, "arsound-review", period.isEmpty()));
            } catch (Exception ex) {
                Logger.printException(() -> "Year in review: could not draw", ex);
                Utils.runOnMainThread(() -> Utils.showToastLong(text("Не удалось посчитать итоги", "The review could not be counted")));
            }
        });
    }

    private static void showCard(Activity activity, Bitmap card, String name, boolean empty) {
        if (gone(activity)) return;
        ImageView picture = new ImageView(activity);
        picture.setImageBitmap(card);
        picture.setAdjustViewBounds(true);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout box = new LinearLayout(activity);
        int side = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        box.setPadding(side, 0, side, 0);
        box.addView(picture, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ArsoundDialog dialog = new ArsoundDialog(activity)
                .setTitle(text("Итоги", "Year in review"))
                .setView(box)
                .setNegativeButton(text("Закрыть", "Close"), null);
        // Nothing to share when nothing was listened to.
        if (!empty) {
            dialog.setPositiveButtonKeepingOpen(text("Поделиться", "Share"), (d, which) -> ReviewShare.share(activity, card, name));
        }
        dialog.show();
    }

    private static long periodStart(long days) {
        return days == 0 ? 0 : System.currentTimeMillis() - days * DAY_MS;
    }

    private static String periodTitle(long days) {
        if (days == 0) return text("Всё время", "All time");
        if (days == 365) return text("Год", "Year");
        return text(days + " дней", days + " days");
    }

    private static boolean gone(Activity activity) {
        return activity.isFinishing() || activity.isDestroyed();
    }
}
