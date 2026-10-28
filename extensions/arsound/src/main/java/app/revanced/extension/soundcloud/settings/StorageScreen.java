package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadFolder;
import app.revanced.extension.soundcloud.download.DownloadIndex;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.download.MusicAccess;
import app.revanced.extension.soundcloud.download.UnplayedDownloads;
import app.revanced.extension.soundcloud.player.StreamCache;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/** Downloaded tracks: the space they take, the folder, finding, cleaning and removing them. */
final class StorageScreen extends SectionScreen {
    static final String ID = "storage";

    StorageScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Память и загрузки", "Storage and downloads");
    }

    @Override
    String description() {
        return text("Сколько места занимают скачанные треки, кэш потоков и текстов, удаление",
                "Space taken by downloaded tracks, the stream and lyrics caches, removing them");
    }

    @Override
    String iconKey() {
        return "storage";
    }

    @Override
    String summary() {
        return text("Скачанные треки, кэш, удаление", "Downloaded tracks, cache, removing");
    }

    @Override
    String keywords() {
        return text("хранилище место диск кэш скачанные загрузки", "storage space disk cache downloads");
    }

    /** The main screen row says how much the downloads and the caches take, once it is counted. */
    @Override
    void loadSummary(TextView summary) {
        Utils.runOnBackgroundThread(() -> {
            long[] usage = downloadsUsage();
            long cache = StreamCache.usedBytes();
            String line = text("Скачано ", "Downloaded ") + Formatter.formatShortFileSize(activity, usage[1])
                    + text(" · кэш ", " · cache ") + Formatter.formatShortFileSize(activity, cache);
            Utils.runOnMainThread(() -> summary.setText(line));
        });
    }

    /** Android's confirmation for removing downloaded files the app cannot remove by itself. */
    private static final int REQUEST_DELETE_DOWNLOADS = 2;
    /** The system's folder picker, opened for the folder new downloads are saved to. */
    private static final int REQUEST_DOWNLOAD_FOLDER = 3;

    /** The description of the "delete all downloads" row: shows the space once it is counted. */
    private TextView downloadsDescription;
    /** The description of the row of the download folder: the folder, and a warning when it cannot be used. */
    private TextView downloadFolderDescription;
    /** The description of the row of extra copies of downloaded tracks. */
    private TextView duplicatesDescription;
    /** The description of the row of the tracks whose folder cannot be reached. */
    private TextView lostDescription;

    /** Downloaded tracks: how much space they take, and removing all of them at once. */
    @Override
    void fill(LinearLayout list) {
        list.addView(new StorageUsage(ui).create());

        list.addView(ui.createSubHeading(text("Скачанные треки", "Downloaded tracks")));
        // Where new tracks are saved; tracks already downloaded stay where they are.
        ActionRow folderRow = ui.createActionRow(text("Папка для скачанных треков", "Download folder"), downloadFolderExplanation(),
                v -> chooseDownloadFolder());
        folderRow.icon("folder").chevron();
        downloadFolderDescription = folderRow.description();
        list.addView(folderRow);
        showDownloadFolder();
        // Files the app lost the record of (a reinstall, an update signed with another key).
        list.addView(ui.createActionRow(text("Найти скачанные треки", "Find downloaded tracks"),
                text("Ищет в папках скачивания файлы треков, скачанных раньше, о которых приложение не помнит: "
                                + "после переустановки или обновления с другой подписью. Нужен доступ к музыке.",
                        "Looks in the download folders for files of tracks downloaded earlier that the app has no "
                                + "record of: after a reinstall or an update signed with another key. Needs access to music."),
                v -> findDownloads()).icon("import").chevron().brief(text("Ищет файлы, о которых приложение забыло после переустановки.",
                "Looks for files the app forgot about after a reinstall.")));

        addAutoCleanup(list);

        list.addView(ui.createSubHeading(text("Освободить место", "Free up space")));
        // The copies that downloading the same tracks again and again left behind.
        ActionRow duplicatesRow = ui.createActionRow(text("Лишние копии скачанных треков", "Extra copies of downloaded tracks"), "",
                v -> confirmRemoveDuplicates());
        duplicatesRow.icon("duplicates");
        duplicatesDescription = duplicatesRow.description();
        list.addView(duplicatesRow);
        countDuplicates();
        // A card that is gone for good would keep its tracks as downloaded for ever: they can be forgotten here.
        ActionRow lostRow = ui.createActionRow(text("Треки в недоступной папке", "Tracks in an unavailable folder"),
                text("Забыть скачанные треки из папки, которой больше нет: карта SD извлечена насовсем или доступ к папке потерян.",
                        "Forget downloaded tracks of a folder that is gone: the SD card was taken out for good or the access to "
                                + "the folder was lost."),
                v -> confirmForgetLost());
        lostRow.icon("block");
        lostDescription = lostRow.description();
        list.addView(lostRow);
        countLost();
        CacheRows.addStreamCache(ui, list);
        CacheRows.addLyricsCache(ui, list);

        list.addView(ui.createSubHeading(text("Удаление", "Removal")));
        ActionRow deleteRow = ui.createActionRow(text("Удалить все скачанные треки", "Delete all downloaded tracks"), "",
                v -> confirmDeleteDownloads());
        deleteRow.icon("trash").danger();
        downloadsDescription = deleteRow.description();
        list.addView(deleteRow);
        countDownloads();
    }

    /** Deleting the files of tracks that were not played for a chosen time. */
    private void addAutoCleanup(LinearLayout list) {
        list.addView(ui.createSubHeading(text("Автоочистка", "Auto cleanup")));
        // Unplayed tracks: off by default; their files are deleted, which the texts say plainly.
        SubRows unplayedOptions = ui.createSubRows();
        TextView[] unplayedDescription = new TextView[1];
        View unplayedToggle = ui.createToggleRow(
                text("Удалять файлы неслушанных треков", "Delete files of unplayed tracks"),
                text("Если скачанный трек не слушали дольше срока ниже, его файл удаляется с телефона. Трек остаётся "
                                + "в SoundCloud: его можно слушать из сети и скачать снова. Импортированные с телефона "
                                + "треки не затрагиваются, если не включить это ниже. Проверка — при запуске приложения.",
                        "If a downloaded track was not played for longer than the period below, its file is deleted from "
                                + "the phone. The track stays in SoundCloud: it can be streamed and downloaded again. "
                                + "Tracks imported from the phone are not touched unless turned on below. "
                                + "Checked when the app starts."),
                UnplayedDownloads.isEnabled(),
                (button, checked) -> {
                    Settings.putBoolean(UnplayedDownloads.ENABLED, checked);
                    unplayedOptions.setVisibility(checked ? View.VISIBLE : View.GONE);
                    countUnplayed(unplayedDescription[0]);
                }
        ).icon("clock").brief(text("Файл трека, который долго не слушали, удаляется с телефона.",
                "The file of a track not played for a long time is deleted."));
        list.addView(unplayedToggle);
        ActionRow periodRow = ui.createActionRow(text("Срок", "Period"), "", v -> chooseUnplayedPeriod(unplayedDescription[0]));
        unplayedDescription[0] = periodRow.description();
        unplayedOptions.addView(periodRow);
        unplayedOptions.addView(ui.createToggleRow(
                text("Удалять и импортированные треки", "Delete imported tracks too"),
                text("Треки, импортированные с телефона, тоже удаляются по этому сроку. Выключено — они не "
                                + "удаляются никогда. Удалённый трек можно импортировать снова, если исходный файл "
                                + "ещё есть на телефоне. Копии скачанных треков, которые удалены в SoundCloud, не "
                                + "удаляются никогда.",
                        "Tracks imported from the phone are deleted by the same period too. Off: they are never "
                                + "deleted. A deleted track can be imported again while its original file is still "
                                + "on the phone. Copies of downloaded tracks that SoundCloud deleted are never "
                                + "deleted."),
                UnplayedDownloads.isImportedIncluded(),
                (button, checked) -> {
                    Settings.putBoolean(UnplayedDownloads.IMPORTED, checked);
                    countUnplayed(unplayedDescription[0]);
                }
        ).brief(text("Импортированные с телефона тоже удаляются по этому сроку.",
                "Tracks imported from the phone are deleted by the same period.")));
        unplayedOptions.setVisibility(UnplayedDownloads.isEnabled()
                ? View.VISIBLE : View.GONE);
        list.addView(unplayedOptions);
        countUnplayed(unplayedDescription[0]);
    }

    private static String downloadFolderExplanation() {
        return text("Новые треки сохраняются сюда: в любую папку на телефоне или на SD-карте. Уже скачанные остаются на "
                        + "месте и играют как раньше.",
                "New tracks are saved here: to any folder on the phone or on an SD card. Tracks already downloaded stay "
                        + "where they are and keep playing.");
    }

    /** The folder new tracks go to, and a warning under it when that folder cannot be used right now. */
    private void showDownloadFolder() {
        String folder = DownloadIndex.label();
        downloadFolderDescription.setText(folder + "\n" + downloadFolderExplanation());
        ui.fillInBackground(() -> {
            if (DownloadFolder.isAvailable(activity)) return;
            Utils.runOnMainThread(() -> downloadFolderDescription.setText(folder + "\n" + text(
                    "Сейчас эта папка недоступна: карта извлечена, папка удалена или доступ отозван. Новые треки "
                            + "сохраняются в «" + DownloadFolder.defaultLabel() + "», пока вы не выберете папку заново.",
                    "This folder is not available now: the card is out, the folder is gone or its access was taken back. "
                            + "New tracks are saved to " + DownloadFolder.defaultLabel() + " until you pick the folder again.")));
        });
    }

    private void chooseDownloadFolder() {
        boolean custom = !DownloadFolder.isDefault();
        String[] choices = custom
                ? new String[]{text("Выбрать другую папку…", "Pick another folder…"),
                text("Вернуть «" + DownloadFolder.defaultLabel() + "»", "Back to " + DownloadFolder.defaultLabel())}
                : new String[]{text("Выбрать папку…", "Pick a folder…")};
        new ArsoundDialog(activity)
                .setTitle(text("Папка для скачанных треков", "Download folder"))
                .setMessage(text("Сейчас: «" + DownloadIndex.label() + "». Можно выбрать любую папку, в том числе на "
                                + "SD-карте. Скачанные раньше треки остаются на месте и играют как раньше; в новую папку "
                                + "попадут только следующие.",
                        "Now: " + DownloadIndex.label() + ". Any folder can be picked, also one on an SD card. Tracks "
                                + "downloaded earlier stay where they are and keep playing; only the next ones go to the "
                                + "new folder."))
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) pickDownloadFolder();
                    else resetDownloadFolder();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void pickDownloadFolder() {
        try {
            activity.startActivityForResult(DownloadFolder.pickerIntent(), REQUEST_DOWNLOAD_FOLDER);
        } catch (ActivityNotFoundException | SecurityException ex) {
            // Some firmware has no system document picker.
            Logger.printException(() -> "Could not open the folder picker", ex);
            Toast.makeText(activity, text("Не найдено приложение для выбора папки", "No app to pick a folder was found"),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void resetDownloadFolder() {
        Utils.runOnBackgroundThread(() -> {
            DownloadFolder.reset(activity);
            Utils.runOnMainThread(() -> {
                Toast.makeText(activity, text("Новые треки сохраняются в «" + DownloadIndex.label() + "»",
                        "New tracks are saved to " + DownloadIndex.label()), Toast.LENGTH_LONG).show();
                if (downloadFolderDescription != null) showDownloadFolder();
            });
        });
    }

    /** After the system's folder picker: the chosen folder becomes the one new tracks go to. */
    private void downloadFolderPicked(Uri tree) {
        Utils.runOnBackgroundThread(() -> {
            String problem = DownloadFolder.choose(activity, tree);
            Utils.runOnMainThread(() -> {
                if (problem != null) {
                    Toast.makeText(activity, problem, Toast.LENGTH_LONG).show();
                    return;
                }
                // The screen can be a new one that does not show the folder row (turned while the picker was open).
                Toast.makeText(activity, text("Новые треки сохраняются в «" + DownloadIndex.label() + "»",
                        "New tracks are saved to " + DownloadIndex.label()), Toast.LENGTH_LONG).show();
                if (downloadFolderDescription != null) showDownloadFolder();
            });
        });
    }

    /** The chosen period and how many tracks fall under it now. */
    private void countUnplayed(TextView description) {
        String period = Numerals.days(UnplayedDownloads.days());
        description.setText(period);
        ui.fillInBackground(() -> {
            List<UnplayedDownloads.Unplayed> tracks = UnplayedDownloads.unplayed();
            long bytes = 0;
            for (UnplayedDownloads.Unplayed track : tracks) {
                bytes += track.size;
            }
            String size = Formatter.formatShortFileSize(activity, bytes);
            Utils.runOnMainThread(() -> description.setText(period + "\n" + (tracks.isEmpty()
                    ? text("Сейчас под это не попадает ни один трек.", "No track falls under it now.")
                    : text("Сейчас под это попадает: " + Numerals.russianTracks(tracks.size()) + ", " + size + ".",
                    "Falls under it now: " + tracks.size() + (tracks.size() == 1 ? " track, " : " tracks, ") + size + "."))));
        });
    }

    private void chooseUnplayedPeriod(TextView description) {
        long[] presets = {7, 30, 90, 365};
        String[] labels = {Numerals.days(7), Numerals.days(30), Numerals.days(90), Numerals.days(365),
                text("Своё число дней…", "Custom number of days…")};
        new ArsoundDialog(activity)
                .setTitle(text("Не слушали дольше", "Not played for longer than"))
                .setItems(labels, (dialog, which) -> {
                    if (which < presets.length) {
                        Settings.putLong(UnplayedDownloads.DAYS, presets[which]);
                        countUnplayed(description);
                    } else {
                        enterUnplayedDays(description);
                    }
                })
                .show();
    }

    private void enterUnplayedDays(TextView description) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint(text("От 1 до 365", "From 1 to 365"));
        input.setText(String.valueOf(UnplayedDownloads.days()));
        // Typing replaces the current value.
        input.setSelectAllOnFocus(true);
        input.requestFocus();
        LinearLayout form = new LinearLayout(activity);
        form.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), 0);
        form.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new ArsoundDialog(activity)
                .setTitle(text("Сколько дней", "How many days"))
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    long days;
                    try {
                        days = Long.parseLong(input.getText().toString().trim());
                    } catch (NumberFormatException ex) {
                        days = 0;
                    }
                    if (days < 1 || days > UnplayedDownloads.MAX_DAYS) {
                        Toast.makeText(activity, text("Нужно число от 1 до 365", "Enter a number from 1 to 365"),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    Settings.putLong(UnplayedDownloads.DAYS, days);
                    countUnplayed(description);
                })
                .show();
    }

    /** The downloaded tracks: how many, and the bytes of their files that are there. */
    static long[] downloadsUsage() {
        Set<String> ids = DownloadTrackPatch.getDownloadedTrackIds();
        long bytes = 0;
        for (String id : ids) {
            DownloadIndex.Entry file = DownloadIndex.known(id);
            if (file != null) bytes += file.size();
        }
        return new long[]{ids.size(), bytes};
    }

    private void countDownloads() {
        downloadsDescription.setText(text("Считаю…", "Counting…"));
        ui.fillInBackground(() -> {
            long[] usage = downloadsUsage();
            String size = Formatter.formatShortFileSize(activity, usage[1]);
            String place = DownloadIndex.downloadsPlace();
            Utils.runOnMainThread(() -> downloadsDescription.setText(usage[0] == 0
                    ? text("Скачанных треков нет.", "No downloaded tracks.")
                    : text("Сейчас: " + Numerals.russianTracks(usage[0]) + ", " + size + " в «" + place + "». Треки останутся в "
                                    + "SoundCloud: их можно слушать из сети и скачать снова.",
                            "Now: " + usage[0] + (usage[0] == 1 ? " track, " : " tracks, ") + size
                                    + " in " + place + ". The tracks stay in SoundCloud: they can be streamed and "
                                    + "downloaded again.")));
        });
    }

    private void confirmDeleteDownloads() {
        Utils.runOnBackgroundThread(() -> {
            long[] usage = downloadsUsage();
            if (usage[0] == 0) return;
            String size = Formatter.formatShortFileSize(activity, usage[1]);
            String place = DownloadIndex.downloadsPlace();
            Utils.runOnMainThread(() -> new ArsoundDialog(activity)
                    .setTitle(text("Удалить все скачанные треки?", "Delete all downloaded tracks?"))
                    .setMessage(text("Удалятся файлы: " + Numerals.russianTracks(usage[0]) + ", " + size + " из «" + place + "». "
                                    + "Без интернета эти треки играть не будут. Импортированные с телефона не затрагиваются.",
                            "The files of " + usage[0] + (usage[0] == 1 ? " track" : " tracks") + " (" + size
                                    + ") are deleted from " + place + ". These tracks will not play without a connection. "
                                    + "Tracks imported from the phone are not touched."))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(text("Удалить", "Delete"), (dialog, which) -> deleteAllDownloads())
                    .show());
        });
    }

    /**
     * Downloads whose files the app could not remove and handed to Android's confirmation. Static, so the answer is
     * still matched with them if the screen is recreated while that dialog is open.
     */
    private static List<String> undeletedDownloadIds = new ArrayList<>();

    /**
     * Removes every downloaded file. Files made before a reinstall belong to the old install for Android, so the app
     * may not remove them itself: those are handed to Android's own confirmation (Android 11 and later).
     * A track whose file is still there stays in the list, until Android has removed the file.
     */
    private void deleteAllDownloads() {
        downloadsDescription.setText(text("Удаляю…", "Deleting…"));
        Utils.runOnBackgroundThread(() -> {
            List<Uri> uris = new ArrayList<>();
            List<String> keptIds = new ArrayList<>();
            int deleted = 0;
            for (String id : DownloadTrackPatch.getDownloadedTrackIds()) {
                DownloadIndex.Entry file = DownloadIndex.known(id);
                boolean removed = DownloadTrackPatch.deleteDownload(activity, id);
                if (removed) {
                    deleted++;
                    continue;
                }
                // The file stays: it belongs to an earlier install, so only Android's own confirmation removes it.
                keptIds.add(id);
                Uri row = file == null ? null : file.rowUri(activity);
                // Only the media store can ask the user about a file; a file of a picked folder is not removed that way.
                if (row != null && MediaStore.AUTHORITY.equals(row.getAuthority())) uris.add(row);
            }
            int done = deleted;
            // A file in a folder that cannot be reached is not "left by an earlier install": it is told apart.
            DownloadIndex.Lost lost = DownloadIndex.lost(activity);
            int unreachable = lost.countIn(keptIds);
            Utils.runOnMainThread(() -> {
                Toast.makeText(activity, text("Удалено: " + Numerals.russianTracks(done), "Deleted: " + done), Toast.LENGTH_SHORT).show();
                if (unreachable > 0) {
                    Toast.makeText(activity, text("Не удалось удалить треков: " + unreachable + ". Папка «" + lost.places
                                    + "» недоступна: верните карту или забудьте эти треки в строке «Треки в недоступной "
                                    + "папке» ниже.",
                            "Could not delete " + unreachable + (unreachable == 1 ? " track" : " tracks") + ": the folder "
                                    + lost.places + " is not available. Put the card back, or forget these tracks in the "
                                    + "row \"Tracks in an unavailable folder\" below."), Toast.LENGTH_LONG).show();
                }
                boolean asked = false;
                if (MediaDeleteConsent.canAsk(uris)) {
                    undeletedDownloadIds = keptIds;
                    asked = MediaDeleteConsent.ask(activity, uris, REQUEST_DELETE_DOWNLOADS,
                            "Could not ask to delete the old downloads");
                    if (!asked) undeletedDownloadIds = new ArrayList<>();
                }
                if (!asked && keptIds.size() > unreachable) {
                    int remaining = keptIds.size() - unreachable;
                    Toast.makeText(activity, text("Не удалось удалить файлов: " + remaining
                                    + ". Разрешите доступ к музыке или удалите их в файловом менеджере.",
                            "Could not delete " + remaining + (remaining == 1 ? " file" : " files")
                                    + ": allow access to music or remove them in a file manager."),
                            Toast.LENGTH_LONG).show();
                }
                countDownloads();
                countDuplicates();
                countLost();
            });
        });
    }

    /** Looks for the files of downloads the app has no record of; asks for access to music if that is what is missing. */
    private void findDownloads() {
        Toast.makeText(activity, text("Ищу…", "Looking…"), Toast.LENGTH_SHORT).show();
        Utils.runOnBackgroundThread(() -> {
            int found = DownloadIndex.adoptManually(activity);
            boolean granted = MusicAccess.isGranted(activity);
            Utils.runOnMainThread(() -> {
                if (found > 0) {
                    Toast.makeText(activity, text("Найдено треков: " + found, "Tracks found: " + found), Toast.LENGTH_LONG).show();
                    countDownloads();
                    countDuplicates();
                } else if (granted) {
                    Toast.makeText(activity, text("Новых скачанных треков не найдено", "No new downloaded tracks found"),
                            Toast.LENGTH_LONG).show();
                } else {
                    // The files of an earlier install are listed by Android only to an app that may read music.
                    new ArsoundDialog(activity)
                            .setTitle(text("Новых треков не найдено", "No new tracks found"))
                            .setMessage(text("Без доступа к музыке видны только файлы этой установки Arsound. Разрешите "
                                            + "доступ, чтобы найти и файлы, скачанные до переустановки.",
                                    "Without access to music only the files of this install of Arsound are visible. "
                                            + "Allow access to find the files downloaded before a reinstall too."))
                            .setNegativeButton(text("Не сейчас", "Not now"), null)
                            .setPositiveButton(text("Разрешить", "Allow"), (dialog, which) -> activity.requestPermissions(
                                    new String[]{MusicAccess.permission()}, 0x4152))
                            .show();
                }
            });
        });
    }

    private void countDuplicates() {
        if (duplicatesDescription == null) return;
        duplicatesDescription.setText(text("Считаю…", "Counting…"));
        ui.fillInBackground(() -> {
            DownloadIndex.Duplicates duplicates = DownloadIndex.duplicates();
            String size = Formatter.formatShortFileSize(activity, duplicates.bytes);
            Utils.runOnMainThread(() -> duplicatesDescription.setText(duplicates.files == 0
                    ? text("Лишних копий нет.", "No extra copies.")
                    : text("Файлов: " + duplicates.files + ", " + size + ". Повторные копии уже скачанных треков: остались от "
                                    + "скачивания одних и тех же треков снова и снова. Сами треки остаются.",
                            "Files: " + duplicates.files + ", " + size + ". Repeated copies of tracks that are already "
                                    + "downloaded, left by downloading the same tracks again and again. The tracks stay.")));
        });
    }

    private void countLost() {
        if (lostDescription == null) return;
        lostDescription.setText(text("Считаю…", "Counting…"));
        ui.fillInBackground(() -> {
            DownloadIndex.Lost lost = DownloadIndex.lost(activity);
            Utils.runOnMainThread(() -> lostDescription.setText(lost.tracks == 0
                    ? text("Таких треков нет: все папки скачанных треков доступны.",
                    "No such tracks: every folder of downloaded tracks is available.")
                    : text("Треков: " + lost.tracks + " в «" + lost.places + "». Папка недоступна: карта извлечена, папка "
                                    + "удалена или доступ отозван. Пока она не вернётся, эти треки без интернета не играют. "
                                    + "Если папки больше нет, нажмите, чтобы забыть эти треки.",
                            "Tracks: " + lost.tracks + " in " + lost.places + ". The folder is not available: the card is "
                                    + "out, the folder is gone or its access was taken back. These tracks do not play without "
                                    + "a connection until it is back. If the folder is gone for good, tap to forget these "
                                    + "tracks.")));
        });
    }

    private void confirmForgetLost() {
        Utils.runOnBackgroundThread(() -> {
            DownloadIndex.Lost lost = DownloadIndex.lost(activity);
            if (lost.tracks == 0) {
                Utils.runOnMainThread(() -> Toast.makeText(activity, text("Таких треков нет", "There are no such tracks"),
                        Toast.LENGTH_SHORT).show());
                return;
            }
            Utils.runOnMainThread(() -> new ArsoundDialog(activity)
                    .setTitle(text("Забыть треки из недоступной папки?", "Forget the tracks of an unavailable folder?"))
                    .setMessage(text("Arsound перестанет считать скачанными " + Numerals.russianTracks(lost.tracks) + " из «"
                                    + lost.places + "». Файлы остаются в папке, ничего не удаляется. Если карта ещё вернётся, "
                                    + "лучше подождать: тогда треки заиграют сами. Любой из этих треков можно скачать заново.",
                            "Arsound stops counting " + lost.tracks + (lost.tracks == 1 ? " track" : " tracks") + " in "
                                    + lost.places + " as downloaded. The files stay in the folder, nothing is deleted. If the "
                                    + "card may still come back, better wait: the tracks then play again by themselves. Any "
                                    + "of these tracks can be downloaded again."))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(text("Забыть", "Forget"), (dialog, which) -> forgetLost(lost))
                    .show());
        });
    }

    private void forgetLost(DownloadIndex.Lost lost) {
        Utils.runOnBackgroundThread(() -> {
            int forgotten = DownloadIndex.forgetLost(lost);
            Utils.runOnMainThread(() -> {
                Toast.makeText(activity, text("Забыто треков: " + forgotten, "Tracks forgotten: " + forgotten),
                        Toast.LENGTH_SHORT).show();
                if (downloadsDescription != null) countDownloads();
                countLost();
            });
        });
    }

    private void confirmRemoveDuplicates() {
        Utils.runOnBackgroundThread(() -> {
            DownloadIndex.Duplicates duplicates = DownloadIndex.duplicates();
            if (duplicates.files == 0) {
                Utils.runOnMainThread(() -> Toast.makeText(activity, text("Лишних копий нет", "There are no extra copies"),
                        Toast.LENGTH_SHORT).show());
                return;
            }
            String size = Formatter.formatShortFileSize(activity, duplicates.bytes);
            Utils.runOnMainThread(() -> new ArsoundDialog(activity)
                    .setTitle(text("Удалить лишние копии?", "Delete the extra copies?"))
                    .setMessage(text("Удалятся файлов: " + duplicates.files + " (" + size + "). Это повторные копии треков, "
                                    + "которые уже скачаны, сами треки останутся и будут играть из своих файлов.",
                            "Files to delete: " + duplicates.files + " (" + size + "). They are repeated copies of "
                                    + "tracks that are already downloaded; the tracks stay and play from their own files."))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(text("Удалить", "Delete"), (dialog, which) -> removeDuplicates(duplicates))
                    .show());
        });
    }

    private void removeDuplicates(DownloadIndex.Duplicates duplicates) {
        Utils.runOnBackgroundThread(() -> {
            List<Uri> needsConsent = new ArrayList<>();
            int removed = DownloadIndex.removeDuplicates(activity, duplicates, needsConsent);
            Utils.runOnMainThread(() -> {
                Toast.makeText(activity, text("Удалено файлов: " + removed, "Files deleted: " + removed), Toast.LENGTH_SHORT).show();
                boolean asked = false;
                if (MediaDeleteConsent.canAsk(needsConsent)) {
                    // No track is waiting for the answer: the copies are not what the tracks play from.
                    undeletedDownloadIds = new ArrayList<>();
                    asked = MediaDeleteConsent.ask(activity, needsConsent, REQUEST_DELETE_DOWNLOADS,
                            "Could not ask to delete the extra copies");
                }
                if (!asked && !needsConsent.isEmpty()) {
                    int remaining = needsConsent.size();
                    Toast.makeText(activity, text("Не удалось удалить файлов: " + remaining
                                    + ". Они остались от прежней установки: удалите их в файловом менеджере.",
                            "Could not delete " + remaining + (remaining == 1 ? " file" : " files")
                                    + ": they are left by an earlier install, remove them in a file manager."),
                            Toast.LENGTH_LONG).show();
                }
                countDownloads();
                countDuplicates();
            });
        });
    }

    /** After Android's delete dialog: tracks whose files are gone are forgotten, the others stay in the list. */
    private void forgetRemovedDownloads() {
        List<String> ids = undeletedDownloadIds;
        undeletedDownloadIds = new ArrayList<>();
        DownloadIndex.refreshAsync();
        if (ids.isEmpty()) {
            if (downloadsDescription != null) countDownloads();
            countDuplicates();
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            for (String id : ids) {
                try {
                    DownloadTrackPatch.deleteDownload(activity, id);
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not update the downloaded track " + id, ex);
                }
            }
            Utils.runOnMainThread(() -> {
                if (downloadsDescription != null) countDownloads();
                countDuplicates();
            });
        });
    }

    @Override
    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_DELETE_DOWNLOADS) {
            forgetRemovedDownloads();
        } else if (requestCode == REQUEST_DOWNLOAD_FOLDER) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) downloadFolderPicked(data.getData());
        }
    }
}
