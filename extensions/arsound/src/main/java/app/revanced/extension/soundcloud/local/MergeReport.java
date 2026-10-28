package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Toasts;

/** What a merge did, and the message about it at the end. */
final class MergeReport {
    int added;
    int duplicates;
    int overLimit;
    final List<String> failed = new ArrayList<>();
    /** What went wrong, for the user; the playlist is unchanged unless {@link #touched}. */
    String error;
    /** The user stopped it before anything was saved. */
    boolean cancelled;
    boolean onPhoneOnly;
    boolean offline;
    /** Imported files that went into a playlist of the user: they stay on this phone. */
    int filesOnPhone;
    /** Tracks that could not be added on this phone after the playlist was saved. */
    int notOnPhone;
    /** The playlist was, or may have been, changed: the screens and the library have to read it again. */
    boolean touched;

    void show(Context activity, MergeCandidate target) {
        StringBuilder message = new StringBuilder();
        String title;
        if (error != null) {
            title = text("Не удалось совместить", "Could not merge");
            message.append(error);
            if (added > 0) message.append('\n').append(text("Добавлено ", "Added ")).append(LocalText.tracks(added));
        } else {
            title = added > 0 ? text("Плейлисты совмещены", "Playlists merged")
                    : failed.isEmpty() ? text("Нечего добавлять", "Nothing to add")
                    : text("Не удалось совместить", "Could not merge");
            message.append(text("Добавлено ", "Added ")).append(LocalText.tracks(added));
            if (duplicates > 0) {
                message.append(text(", " + duplicates + " уже были", ", " + duplicates + " were already there"));
            }
            if (overLimit > 0) {
                message.append('\n').append(text("Не поместилось: ", "Did not fit: ")).append(overLimit)
                        .append(text(" (в плейлисте не больше " + PlaylistMerge.MAX_TRACKS + " треков)",
                                " (a playlist holds " + PlaylistMerge.MAX_TRACKS + " tracks at most)"));
            }
            if (!failed.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (String name : failed) names.append(names.length() == 0 ? "" : ", ").append('«').append(name).append('»');
                message.append('\n').append(text("Не удалось прочитать: ", "Could not read: ")).append(names);
                message.append('\n').append(RegionGuard.blocksLastCountry()
                        ? text("Запросы к SoundCloud сейчас заблокированы защитой по региону.", "Requests to SoundCloud are blocked by the region guard now.")
                        : !isOnline() ? text("Нет сети: такие плейлисты читаются только с интернетом.", "No network: these playlists need the internet.")
                        : text("Проверьте сеть и повторите.", "Check the network and try again."));
            }
            if (notOnPhone > 0) {
                message.append('\n').append(text("Не удалось сохранить на телефоне: " + LocalText.tracks(notOnPhone) + ".",
                        "Could not save on this phone: " + LocalText.tracks(notOnPhone) + "."));
            }
            List<String> notes = new ArrayList<>();
            if (onPhoneOnly) {
                notes.add(text("«" + target.title + "» не ваш плейлист: треки добавлены только на этом телефоне и не попадут в SoundCloud.",
                        "\"" + target.title + "\" is not your playlist: the tracks are added on this phone only and do not go to SoundCloud."));
            }
            if (filesOnPhone > 0) {
                notes.add(text("Файлы с этого телефона (" + filesOnPhone + ") остаются только на нём и не попадут в SoundCloud.",
                        "The files from this phone (" + filesOnPhone + ") stay on this phone and do not go to SoundCloud."));
            }
            if (offline) {
                notes.add(text("Нет связи с SoundCloud (нет сети или включена защита по региону): изменения уйдут, когда она появится.",
                        "No connection to SoundCloud (no network, or the region guard is on): the changes go out when it is back."));
            }
            if (!notes.isEmpty()) message.append("\n\n").append(TextUtils.join("\n", notes));
        }

        boolean gone = activity instanceof Activity && (((Activity) activity).isFinishing() || ((Activity) activity).isDestroyed());
        if (gone) {
            // The screen was left while the merge ran: a dialog has no window to open in.
            Context app = Utils.getContext();
            if (app != null) Toasts.showLong(app, title + ". " + message.toString().split("\n")[0]);
            return;
        }
        new ArsoundDialog(activity).setTitle(title).setMessage(message.toString())
                .setPositiveButton(android.R.string.ok, null).show();
    }

    static boolean isOnline() {
        try {
            return Utils.isNetworkConnected();
        } catch (Exception ex) {
            return true;
        }
    }
}
