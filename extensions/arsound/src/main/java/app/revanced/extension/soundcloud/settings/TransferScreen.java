package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.transfer.TransferJob;
import app.revanced.extension.soundcloud.transfer.TransferRecord;
import app.revanced.extension.soundcloud.transfer.YandexAccount;
import app.revanced.extension.soundcloud.transfer.YandexLink;
import app.revanced.extension.soundcloud.transfer.YandexLoginActivity;
import app.revanced.extension.soundcloud.transfer.YandexPlaylist;

/**
 * "Transfer from Yandex Music": a link, or a list of the signed-in Yandex account, becomes a private SoundCloud
 * playlist. The parts that change (the account's lists, the transfer in progress, the transfers done) are pages of
 * their own, filled again when their state changes.
 */
final class TransferScreen extends ScrollingScreen implements TransferJob.Listener {
    static final String ID = "yandex_transfer";

    private LinearLayout progressPart;
    private LinearLayout accountPart;
    private LinearLayout historyPart;
    /** The account's lists, null until they are read. */
    private List<YandexPlaylist> library;
    private boolean libraryFailed;
    /** Bumped by every read of the lists, so an older read that finishes late is dropped. */
    private int libraryGeneration;
    private TransferJob.Phase shownPhase;
    /** The sign-in page was opened: the account part is filled again when the screen comes back. */
    private boolean signingIn;
    /** The row of the transfer under way, kept so a step of the progress only changes its text. */
    private ActionRow progressRow;
    /** Whether that row is of a running transfer (a failed one has another text and tap). */
    private boolean progressRowRunning;
    /** A transfer the app was closed in the middle of, key and title; read with the history, off the main thread. */
    private String[] interrupted;
    /** Bumped by every read of the history, so an older read that finishes late is dropped. */
    private int historyGeneration;

    TransferScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    View create() {
        LinearLayout[] holder = new LinearLayout[1];
        LinearLayout root = createScreen(holder);
        LinearLayout list = holder[0];
        list.addView(ui.createSectionHeader("import", text("Перенос из Яндекс Музыки", "Transfer from Yandex Music"),
                text("Плейлист или лайки становятся приватным плейлистом SoundCloud",
                        "A playlist or likes become a private SoundCloud playlist")));

        progressPart = SettingsPage.nested(ui);
        list.addView(progressPart);

        list.addView(ui.createSubHeading(text("По ссылке", "By link")));
        list.addView(ui.createActionRow(
                text("Вставить ссылку", "Paste a link"),
                text("Плейлист, альбом или «Мне нравится» открытого профиля. В Яндекс Музыке: «⋯» → «Поделиться» → "
                                + "«Скопировать ссылку». Arsound найдёт каждый трек в SoundCloud и соберёт найденные "
                                + "в приватный плейлист с тем же названием и порядком.",
                        "A playlist, an album or the likes of an open profile. In Yandex Music: \"⋯\" → \"Share\" → "
                                + "\"Copy link\". Arsound finds each track on SoundCloud and puts the ones found into a "
                                + "private playlist with the same name and order."),
                v -> askLink()
        ).icon("link"));

        accountPart = SettingsPage.nested(ui);
        list.addView(accountPart);
        historyPart = SettingsPage.nested(ui);
        list.addView(historyPart);

        TransferJob.addListener(this);
        fillProgress();
        fillAccount();
        fillHistory();
        return root;
    }

    @Override
    void onResume() {
        if (!signingIn) return;
        signingIn = false;
        library = null;
        libraryFailed = false;
        fillAccount();
    }

    @Override
    void onDestroy() {
        TransferJob.removeListener(this);
    }

    @Override
    public void onChanged() {
        fillProgress();
        TransferJob.Phase phase = TransferJob.phase();
        if (phase != shownPhase && phase == TransferJob.Phase.DONE) {
            // The finished transfer is the first row of "Transferred" now.
            fillHistory();
            Toast.makeText(activity, TransferJob.status(), Toast.LENGTH_LONG).show();
        }
        shownPhase = phase;
    }

    // region Link

    private void askLink() {
        if (refuseWhileRunning()) return;
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("https://music.yandex.ru/…");
        String clip = clipboardText();
        if (YandexLink.find(clip) != null) input.setText(clip.trim());
        FrameLayout box = new FrameLayout(activity);
        box.setPadding(ui.dimen("spacing_m"), ui.dp(8), ui.dimen("spacing_m"), 0);
        box.addView(input);
        new ArsoundDialog(activity)
                .setTitle(text("Ссылка из Яндекс Музыки", "Yandex Music link"))
                .setView(box)
                .setPositiveButtonKeepingOpen(text("Перенести", "Transfer"), (dialog, which) -> {
                    YandexLink link = YandexLink.find(input.getText().toString());
                    if (link == null) {
                        Toast.makeText(activity, text("Это не ссылка на плейлист, альбом или лайки Яндекс Музыки",
                                "This is not a link to a Yandex Music playlist, album or likes"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    start(link);
                })
                .setNegativeButton(text("Отмена", "Cancel"), null)
                .show();
        input.requestFocus();
    }

    private String clipboardText() {
        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;
            CharSequence value = clip.getItemAt(0).coerceToText(activity);
            return value == null ? null : value.toString();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private void start(YandexLink link) {
        if (!TransferJob.start(link)) refuseWhileRunning();
    }

    private boolean refuseWhileRunning() {
        if (!TransferJob.isRunning()) return false;
        Toast.makeText(activity, text("Перенос уже идёт: дождитесь его или остановите",
                "A transfer is already running: wait for it or stop it"), Toast.LENGTH_SHORT).show();
        return true;
    }

    // endregion

    // region Progress

    /**
     * The transfer running, or the one that failed, or the one the app was closed in the middle of; a finished one is
     * the first row of "Transferred". A step of a running transfer changes only the text of its row.
     */
    private void fillProgress() {
        boolean running = TransferJob.isRunning();
        String title = TransferJob.title().isEmpty() ? text("Перенос", "Transfer") : TransferJob.title();
        String status = TransferJob.status() + (running ? text(". Нажмите, чтобы остановить.", ". Tap to stop.") : "");
        if (running && progressRow != null && progressRowRunning && progressRow.getParent() == progressPart) {
            progressRow.title().setText(title);
            progressRow.description().setText(status);
            return;
        }
        progressPart.removeAllViews();
        progressRow = null;
        if (!running && TransferJob.phase() != TransferJob.Phase.FAILED) {
            if (TransferJob.phase() == TransferJob.Phase.IDLE && interrupted != null) fillInterrupted();
            return;
        }
        progressPart.addView(ui.createSubHeading(text("Сейчас", "Now")));
        ActionRow row = ui.createActionRow(title, status, v -> {
            if (!TransferJob.isRunning()) return;
            new ArsoundDialog(activity)
                    .setTitle(text("Остановить перенос?", "Stop the transfer?"))
                    .setMessage(text("Найденные треки запомнятся: если начать снова, их не придётся искать.",
                            "The tracks found are kept: starting again will not search for them."))
                    .setPositiveButton(text("Остановить", "Stop"), (dialog, which) -> TransferJob.cancel())
                    .setNegativeButton(text("Продолжить", "Keep going"), null)
                    .show();
        });
        row.icon(running ? "import" : "info");
        progressPart.addView(row);
        progressRow = row;
        progressRowRunning = running;
    }

    /** A transfer the app was closed in the middle of: a tap goes on with it, the playlists made are reused. */
    private void fillInterrupted() {
        String key = interrupted[0];
        String title = interrupted[1].isEmpty() ? text("Перенос", "Transfer") : interrupted[1];
        progressPart.addView(ui.createSubHeading(text("Сейчас", "Now")));
        ActionRow row = ui.createActionRow(title, text("Прерван: приложение закрылось во время переноса. Нажмите, чтобы "
                + "продолжить; найденное и созданные плейлисты не повторятся.", "Interrupted: the app was closed during "
                + "the transfer. Tap to go on; what was found and the playlists made are not done again."), v ->
                new ArsoundDialog(activity)
                        .setTitle(text("Продолжить перенос?", "Go on with the transfer?"))
                        .setMessage(title)
                        .setPositiveButton(text("Продолжить", "Go on"), (dialog, which) -> {
                            interrupted = null;
                            if (!TransferJob.resume(key)) refuseWhileRunning();
                        })
                        .setNeutralButton(text("Забыть", "Forget it"), (dialog, which) -> {
                            interrupted = null;
                            Utils.runOnBackgroundThread(TransferJob::forgetInterrupted);
                            fillProgress();
                        })
                        .setNegativeButton(text("Отмена", "Cancel"), null)
                        .show());
        progressPart.addView(row.icon("info"));
    }

    // endregion

    // region Account

    private void fillAccount() {
        accountPart.removeAllViews();
        accountPart.addView(ui.createSubHeading(text("Ваш аккаунт Яндекса", "Your Yandex account")));
        if (!YandexAccount.isSignedIn()) {
            accountPart.addView(ui.createActionRow(
                    text("Войти в Яндекс", "Sign in to Yandex"),
                    text("Для закрытых лайков и своих плейлистов: после входа они появятся здесь списком. Пароль "
                                    + "вводится на странице Яндекса, Arsound его не видит. Доступ хранится только на "
                                    + "этом телефоне и отправляется только в Яндекс Музыку.",
                            "For private likes and your own playlists: after signing in they are listed here. The "
                                    + "password is typed into Yandex's page; Arsound does not see it. The access stays "
                                    + "on this phone and goes only to Yandex Music."),
                    v -> {
                        signingIn = true;
                        YandexLoginActivity.start(activity);
                    }
            ).icon("account").chevron());
            return;
        }
        if (library == null && !libraryFailed) {
            accountPart.addView(ui.createActionRow(text("Читаю ваши списки…", "Reading your lists…"), "", v -> {
            }).icon("music"));
            loadLibrary();
        } else if (libraryFailed) {
            accountPart.addView(ui.createActionRow(text("Списки не прочитались", "Could not read the lists"),
                    text("Нажмите, чтобы попробовать снова.", "Tap to try again."), v -> {
                        libraryFailed = false;
                        fillAccount();
                    }).icon("reset"));
        } else {
            for (YandexPlaylist playlist : library) {
                accountPart.addView(ui.createActionRow(playlist.title, Numerals.tracks(playlist.trackCount),
                        v -> confirmTransfer(playlist)).icon(playlist.link.kind == YandexLink.Kind.LIKES ? "recs" : "music"));
            }
        }
        String name = YandexAccount.name();
        accountPart.addView(ui.createActionRow(
                text("Выйти из Яндекса", "Sign out of Yandex"),
                name.isEmpty() ? text("Вход выполнен.", "Signed in.") : text("Вход выполнен: ", "Signed in: ") + name + ".",
                v -> {
                    YandexAccount.signOut();
                    library = null;
                    fillAccount();
                    Toast.makeText(activity, text("Вы вышли из Яндекса", "Signed out of Yandex"), Toast.LENGTH_SHORT).show();
                }
        ).icon("account"));
    }

    private void loadLibrary() {
        int generation = ++libraryGeneration;
        Utils.runOnBackgroundThread(() -> {
            List<YandexPlaylist> lists = null;
            boolean failed = false;
            try {
                lists = YandexAccount.library();
            } catch (Exception ex) {
                Logger.printInfo(() -> "Transfer: could not read the Yandex lists: " + ex);
                failed = true;
            }
            List<YandexPlaylist> read = lists;
            boolean readFailed = failed;
            Utils.runOnMainThread(() -> {
                if (generation != libraryGeneration || activity.isFinishing()) return;
                if (read == null && !readFailed) {
                    // The sign-in expired and was forgotten.
                    Toast.makeText(activity, text("Вход в Яндекс устарел: войдите ещё раз",
                            "The Yandex sign-in expired: sign in again"), Toast.LENGTH_LONG).show();
                }
                library = read;
                libraryFailed = readFailed;
                fillAccount();
            });
        });
    }

    private void confirmTransfer(YandexPlaylist playlist) {
        if (refuseWhileRunning()) return;
        if (playlist.trackCount == 0) {
            Toast.makeText(activity, text("В этом списке нет треков", "This list has no tracks"), Toast.LENGTH_SHORT).show();
            return;
        }
        new ArsoundDialog(activity)
                .setTitle(text("Перенести «", "Transfer \"") + playlist.title + text("»?", "\"?"))
                .setMessage(text("Arsound найдёт " + Numerals.russianTracks(playlist.trackCount) + " в SoundCloud и соберёт "
                                + "найденные в приватный плейлист. Это займёт пару минут на каждую сотню; можно закрыть настройки.",
                        "Arsound will look for " + Numerals.tracks(playlist.trackCount) + " on SoundCloud and put the ones "
                                + "found into a private playlist. It takes a couple of minutes per hundred; the settings can be closed."))
                .setPositiveButton(text("Перенести", "Transfer"), (dialog, which) -> start(playlist.link))
                .setNegativeButton(text("Отмена", "Cancel"), null)
                .show();
    }

    // endregion

    // region History

    /** Reads the history (and a transfer left in the middle) off the main thread, then shows it. */
    private void fillHistory() {
        int generation = ++historyGeneration;
        Utils.runOnBackgroundThread(() -> {
            List<TransferRecord> history = TransferJob.history();
            String[] left = TransferJob.interrupted();
            Utils.runOnMainThread(() -> {
                if (generation != historyGeneration || activity.isFinishing()) return;
                boolean leftChanged = (left == null) != (interrupted == null);
                interrupted = left;
                showHistory(history);
                if (leftChanged) fillProgress();
            });
        });
    }

    private void showHistory(List<TransferRecord> history) {
        historyPart.removeAllViews();
        if (history.isEmpty()) return;
        historyPart.addView(ui.createSubHeading(text("Уже перенесено", "Transferred")));
        DateFormat format = DateFormat.getDateInstance(DateFormat.SHORT);
        for (TransferRecord record : history) {
            String line = text("В плейлисте ", "In the playlist ") + record.found + text(" из ", " of ") + record.total
                    + " · " + format.format(new Date(record.time)) + ". "
                    + (record.missing.isEmpty() ? text("Нашлись все.", "All were found.")
                    : text("Не нашлись " + record.missing.size() + ": нажмите, чтобы посмотреть.",
                    record.missing.size() + " not found: tap to see them."));
            ActionRow row = ui.createActionRow(record.title, line, v -> showRecord(record));
            row.setOnLongClickListener(v -> {
                askForget(record);
                return true;
            });
            historyPart.addView(row.icon("check"));
        }
    }

    /** Only the line in this list goes; the playlist on SoundCloud stays. */
    private void askForget(TransferRecord record) {
        new ArsoundDialog(activity)
                .setTitle(text("Убрать «", "Remove \"") + record.title + text("» из списка?", "\" from the list?"))
                .setMessage(text("Пропадёт только эта строка. Плейлист в SoundCloud останется.",
                        "Only this line goes. The playlist on SoundCloud stays."))
                .setPositiveButton(text("Убрать", "Remove"), (dialog, which) ->
                        Utils.runOnBackgroundThread(() -> {
                            TransferJob.forget(record);
                            Utils.runOnMainThread(this::fillHistory);
                        }))
                .setNegativeButton(text("Отмена", "Cancel"), null)
                .show();
    }

    /** The tracks not found, to look for another way; they can be copied as text. */
    private void showRecord(TransferRecord record) {
        if (record.missing.isEmpty()) {
            new ArsoundDialog(activity)
                    .setTitle(record.title)
                    .setMessage(text("Нашлись все " + Numerals.russianTracks(record.total) + ". Плейлист «" + record.title
                                    + "» — в вашей библиотеке SoundCloud.",
                            "All " + Numerals.tracks(record.total) + " were found. The playlist \"" + record.title
                                    + "\" is in your SoundCloud library."))
                    .setPositiveButton(text("Закрыть", "Close"), null)
                    .show();
            return;
        }
        StringBuilder missing = new StringBuilder();
        for (String label : record.missing) missing.append(label).append('\n');
        String intro = text(record.found + " из " + record.total + " уже в плейлисте «" + record.title + "» в вашей "
                        + "библиотеке SoundCloud. Этих " + record.missing.size() + " в SoundCloud не нашлось: их там нет "
                        + "или они залиты под другим названием. Список можно скопировать и поискать вручную.",
                record.found + " of " + record.total + " are already in the playlist \"" + record.title + "\" in your "
                        + "SoundCloud library. These " + record.missing.size() + " were not found on SoundCloud: they are "
                        + "not there or are uploaded under another name. Copy the list to look for them by hand.");
        new ArsoundDialog(activity)
                .setTitle(text("Не нашлись в SoundCloud", "Not found on SoundCloud"))
                .setMessage(intro + "\n\n" + missing.toString().trim())
                .setPositiveButton(text("Скопировать список", "Copy the list"), (d, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard == null) return;
                    clipboard.setPrimaryClip(ClipData.newPlainText(record.title, missing.toString().trim()));
                    Toast.makeText(activity, text("Список скопирован", "List copied"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(text("Закрыть", "Close"), null)
                .show();
    }

    // endregion
}
