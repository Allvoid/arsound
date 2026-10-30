package app.revanced.extension.soundcloud.download;

import static app.revanced.extension.soundcloud.download.DownloadTrackPatch.text;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * The note for a track that can never be downloaded: a Go+ track (the server serves an account without the
 * subscription only a 30-second preview) and a track blocked in the account's region. People kept asking for a way
 * around them, so the note says plainly that there is none, in Arsound or anywhere else, and that asking does not help.
 * Shown as a window when such a track is downloaded from its menu, and as a footnote of the playlist check.
 */
final class RestrictionNote {
    private RestrictionNote() {
    }

    /** Whether the track is one of those that no app can download. */
    static boolean isRestriction(TrackSource.Status status) {
        return status == TrackSource.Status.AUDIO_ONLY_PREVIEW || status == TrackSource.Status.SUBSCRIPTION_REQUIRED
                || status == TrackSource.Status.REGION_BLOCKED;
    }

    private static String title(TrackSource.Status status) {
        return status == TrackSource.Status.REGION_BLOCKED
                ? text("Трек закрыт в вашем регионе", "The track is blocked in your region")
                : text("Трек Go+ не скачать", "A Go+ track cannot be downloaded");
    }

    private static String message(TrackSource.Status status) {
        if (status == TrackSource.Status.REGION_BLOCKED) {
            return text("SoundCloud закрыл этот трек для страны, которую он определил у вашего аккаунта или подключения. "
                            + "Пока трек так помечен, сервер не отдаёт его целиком никому с вашего аккаунта: скачать его "
                            + "нельзя ни в Arsound, ни в другом моде, ни каким-либо ещё способом. Обойти это в приложении "
                            + "нельзя ни в каком виде, просить об этом не нужно.\n\n"
                            + "Пометку ставит сам SoundCloud. Что помогает её снять (VPN другой страны и сброс данных), "
                            + "написано под значком «?» рядом с надписью «Недоступно в вашей стране».",
                    "SoundCloud has blocked this track for the country it decided your account or connection is in. "
                            + "While the track is marked so, the server does not serve the whole of it to your account: "
                            + "it cannot be downloaded in Arsound, in another mod or in any other way. There is no way "
                            + "around it in an app, so there is no need to ask for one.\n\n"
                            + "SoundCloud puts the mark itself. What helps to have it lifted (a VPN of another country and "
                            + "a data reset) is under the \"?\" next to \"Not available in your country\".");
        }
        return text("Это трек по подписке SoundCloud Go+. Аккаунту без подписки сервер SoundCloud отдаёт только отрывок "
                        + "на 30 секунд, полного трека у приложения просто нет. Поэтому его нельзя скачать ни в Arsound, "
                        + "ни в другом моде, ни каким-либо ещё способом: обойти это нельзя ни в каком виде. Просить об этом "
                        + "не нужно: это ограничение сервера, а не приложения.",
                "This is a SoundCloud Go+ subscription track. To an account without the subscription the SoundCloud "
                        + "server serves only a 30-second preview, so the app never has the full track. It cannot be "
                        + "downloaded in Arsound, in another mod or in any other way: there is no way around it at all. "
                        + "There is no need to ask for one: it is a limit of the server, not of the app.");
    }

    /**
     * The window about the track, over the screen that is open; a toast with the title when no screen is.
     * Main thread.
     */
    static void show(Context context, TrackSource.Status status) {
        Activity activity = UpdateChecker.resumedActivity();
        if (activity == null || activity.isFinishing()) {
            Toast.makeText(context, title(status), Toast.LENGTH_LONG).show();
            return;
        }
        try {
            new ArsoundDialog(activity)
                    .setTitle(title(status))
                    .setMessage(message(status))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not explain why the track is not downloaded", ex);
            Toast.makeText(context, title(status), Toast.LENGTH_LONG).show();
        }
    }

    /** The footnote of the playlist check under the tracks that cannot be downloaded. */
    static String footnote(boolean goPlus, boolean region) {
        if (!goPlus && !region) return "";
        String which = goPlus && region
                ? text("Треки Go+ и треки, закрытые в вашем регионе,", "Go+ tracks and tracks blocked in your region")
                : goPlus ? text("Треки Go+", "Go+ tracks") : text("Треки, закрытые в вашем регионе,", "Tracks blocked in your region");
        return text("* " + which + " не скачать ни в Arsound, ни в другом моде, ни иначе: полного трека сервер "
                        + "SoundCloud не отдаёт. Обойти это нельзя ни в каком виде, просить об этом не нужно.",
                "* " + which + " cannot be downloaded in Arsound, in another mod or in any other way: the SoundCloud server "
                        + "does not serve the full track. There is no way around it at all, so there is no need to ask.");
    }
}
