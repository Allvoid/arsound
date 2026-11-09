package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.transfer.SourceList;
import app.revanced.extension.soundcloud.transfer.TransferJob;
import app.revanced.extension.soundcloud.transfer.TransferLink;
import app.revanced.extension.soundcloud.transfer.TransferRecord;
import app.revanced.extension.soundcloud.transfer.TransferSource;

/**
 * "Transfer from other services": a link of Yandex Music, VK or YouTube Music (told apart by the link itself), or a
 * list of the account signed in to one of them, becomes a private SoundCloud playlist. The account part shows one
 * service at a time, picked by its one choice row. The parts that change (the account's lists, the transfer in
 * progress, the transfers done) are pages of their own, filled again when their state changes.
 */
final class TransferScreen extends ScrollingScreen implements TransferJob.Listener {
    static final String ID = "yandex_transfer";

    private LinearLayout progressPart;
    private LinearLayout accountPart;
    private LinearLayout historyPart;
    /** The service whose account is shown, {@link TransferSource#id} kept in the settings. */
    static final String SOURCE_SETTING = "transfer_source";

    /** The account's lists, null until they are read. */
    private List<SourceList> library;
    private boolean libraryFailed;
    /** The service no longer takes the sign-in: the sign-in row is offered again. */
    private boolean libraryExpired;
    private TransferSource source;
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
        source = TransferSource.byId(Settings.getString(SOURCE_SETTING, "yandex"));
        list.addView(ui.createSectionHeader("import", text("Перенос из других сервисов", "Transfer from other services"),
                text("Плейлист или лайки из Яндекс Музыки, VK или YouTube Music становятся приватным плейлистом SoundCloud",
                        "A playlist or likes from Yandex Music, VK or YouTube Music become a private SoundCloud playlist")));

        progressPart = SettingsPage.nested(ui);
        list.addView(progressPart);

        list.addView(ui.createSubHeading(text("По ссылке", "By link")));
        list.addView(ui.createActionRow(
                text("Вставить ссылку", "Paste a link"),
                text("Плейлист или альбом Яндекс Музыки, VK или YouTube Music, «Мне нравится» открытого профиля "
                                + "Яндекса: «⋯» → «Поделиться» → «Скопировать ссылку». Ссылки VK читаются после входа в VK. "
                                + "Arsound найдёт каждый трек в SoundCloud и соберёт найденные в приватный плейлист с "
                                + "тем же названием и порядком.",
                        "A playlist or an album of Yandex Music, VK or YouTube Music, the likes of an open Yandex "
                                + "profile: \"⋯\" → \"Share\" → \"Copy link\". VK links are read after signing in to "
                                + "VK. Arsound finds each track on SoundCloud and puts the ones found into a private "
                                + "playlist with the same name and order."),
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
        resetLibrary();
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
        input.setHint(text("Ссылка на плейлист", "Playlist link"));
        String clip = clipboardText();
        if (TransferSource.findLink(clip) != null) input.setText(clip.trim());
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dimen("spacing_m"), ui.dp(8), ui.dimen("spacing_m"), 0);
        box.addView(input);
        CheckBox like = likeTick();
        box.addView(likeOption(like));
        new ArsoundDialog(activity)
                .setTitle(text("Ссылка на плейлист", "Playlist link"))
                .setView(box)
                .setPositiveButtonKeepingOpen(text("Перенести", "Transfer"), (dialog, which) -> {
                    TransferLink link = TransferSource.findLink(input.getText().toString());
                    if (link == null) {
                        Toast.makeText(activity, text("Это не ссылка на плейлист Яндекс Музыки, VK или YouTube Music",
                                "This is not a link to a Yandex Music, VK or YouTube Music playlist"), Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (link.source().needsSignInForLinks() && !link.source().isSignedIn()) {
                        Toast.makeText(activity, text("VK отдаёт музыку только после входа: выберите VK в «Сервис» ниже и войдите",
                                "VK gives music only after signing in: pick VK under \"Service\" below and sign in"),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    dialog.dismiss();
                    start(link, like.isChecked());
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

    /** Off until the user asks: a like shows on the profile, while the playlist stays private. */
    private CheckBox likeTick() {
        CheckBox tick = new CheckBox(activity);
        tick.setText(text("Лайкнуть все найденные треки", "Like all the tracks found"));
        tick.setTextColor(ArsoundTheme.palette(activity, "primary"));
        tick.setButtonTintList(ColorStateList.valueOf(ArsoundTheme.palette(activity, "special")));
        return tick;
    }

    /** The tick with a line under it on what it does. */
    private LinearLayout likeOption(CheckBox tick) {
        LinearLayout option = new LinearLayout(activity);
        option.setOrientation(LinearLayout.VERTICAL);
        option.addView(tick);
        TextView note = new TextView(activity);
        note.setText(text("Для тех, кто слушает через лайки. Они видны в вашем профиле SoundCloud, плейлист остаётся "
                        + "приватным.\n\nНестабильно: SoundCloud блокирует лайки, поставленные автоматически, и "
                        + "может принять лишь часть. Надёжнее ставить лайки вручную.",
                "For those who listen through their likes. They show on your SoundCloud profile, the playlist stays "
                        + "private.\n\nUnstable: SoundCloud blocks likes given automatically and may take only a "
                        + "part. It is more reliable to like by hand."));
        note.setTextColor(ArsoundTheme.palette(activity, "secondary"));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(ui.dp(4), 0, 0, ui.dp(8));
        option.addView(note);
        return option;
    }

    private void start(TransferLink link, boolean like) {
        if (!TransferJob.start(link, like)) refuseWhileRunning();
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
        String[] marked = interrupted;
        String title = marked[1].isEmpty() ? text("Перенос", "Transfer") : marked[1];
        boolean like = Boolean.parseBoolean(marked[2]);
        progressPart.addView(ui.createSubHeading(text("Сейчас", "Now")));
        ActionRow row = ui.createActionRow(title, text("Прерван: приложение закрылось во время переноса. Нажмите, чтобы "
                + "продолжить; найденное и созданные плейлисты не повторятся.", "Interrupted: the app was closed during "
                + "the transfer. Tap to go on; what was found and the playlists made are not done again."), v ->
                new ArsoundDialog(activity)
                        .setTitle(text("Продолжить перенос?", "Go on with the transfer?"))
                        .setMessage(like ? title + text("\n\nТреки будут лайкнуты.", "\n\nThe tracks will be liked.") : title)
                        .setPositiveButton(text("Продолжить", "Go on"), (dialog, which) -> {
                            interrupted = null;
                            if (!TransferJob.resume(marked)) refuseWhileRunning();
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

    private void resetLibrary() {
        library = null;
        libraryFailed = false;
        libraryExpired = false;
        // A read still under way is of the account shown before: it is dropped.
        libraryGeneration++;
    }

    /** The one choice of this screen: whose account is shown below it. */
    private ActionRow sourceRow() {
        List<TransferSource> sources = TransferSource.all();
        String[] labels = new String[sources.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = sources.get(i).name();
        ActionRow row = ui.createActionRow(text("Сервис", "Service"), "", v -> new ArsoundDialog(activity)
                .setTitle(text("Сервис", "Service"))
                .setSingleChoiceItems(labels, sources.indexOf(source), (dialog, which) -> {
                    dialog.dismiss();
                    if (sources.get(which) == source) return;
                    source = sources.get(which);
                    Settings.putString(SOURCE_SETTING, source.id());
                    resetLibrary();
                    fillAccount();
                })
                .show());
        row.value(source.name());
        row.icon("import");
        return row;
    }

    private void fillAccount() {
        accountPart.removeAllViews();
        accountPart.addView(ui.createSubHeading(text("Ваш аккаунт", "Your account")));
        accountPart.addView(sourceRow());
        TransferSource shown = source;
        if (!shown.isSignedIn() || libraryExpired) {
            accountPart.addView(ui.createActionRow(shown.signInTitle(), libraryExpired
                            ? text("Вход устарел: войдите ещё раз.", "The sign-in expired: sign in again.")
                            : shown.signInNote(),
                    v -> {
                        signingIn = true;
                        shown.signIn(activity);
                    }
            ).icon("account").chevron());
            if (!shown.isSignedIn()) return;
        } else if (library == null && !libraryFailed) {
            accountPart.addView(ui.createActionRow(text("Читаю ваши списки…", "Reading your lists…"), "", v -> {
            }).icon("music"));
            loadLibrary(shown);
        } else if (libraryFailed) {
            accountPart.addView(ui.createActionRow(text("Списки не прочитались", "Could not read the lists"),
                    text("Нажмите, чтобы попробовать снова.", "Tap to try again."), v -> {
                        libraryFailed = false;
                        fillAccount();
                    }).icon("reset"));
        } else {
            for (SourceList playlist : library) {
                String count = playlist.trackCount == SourceList.UNKNOWN_COUNT ? "" : Numerals.tracks(playlist.trackCount);
                accountPart.addView(ui.createActionRow(playlist.title, count,
                        v -> confirmTransfer(playlist)).icon(playlist.likes ? "recs" : "music"));
            }
        }
        String name = shown.accountName();
        accountPart.addView(ui.createActionRow(
                shown.signOutTitle(),
                name.isEmpty() ? text("Вход выполнен.", "Signed in.") : text("Вход выполнен: ", "Signed in: ") + name + ".",
                v -> {
                    shown.signOut();
                    resetLibrary();
                    fillAccount();
                    Toast.makeText(activity, text("Вы вышли: ", "Signed out: ") + shown.name(), Toast.LENGTH_SHORT).show();
                }
        ).icon("account"));
    }

    private void loadLibrary(TransferSource shown) {
        int generation = ++libraryGeneration;
        Utils.runOnBackgroundThread(() -> {
            List<SourceList> lists = null;
            boolean failed = false;
            try {
                lists = shown.library();
            } catch (Exception ex) {
                Logger.printInfo(() -> "Transfer: could not read the lists of " + shown.id() + ": " + ex);
                failed = true;
            }
            List<SourceList> read = lists;
            boolean readFailed = failed;
            Utils.runOnMainThread(() -> {
                if (generation != libraryGeneration || activity.isFinishing()) return;
                library = read;
                libraryFailed = readFailed;
                // Yandex and VK forget a sign-in that expired; YouTube Music's stays the search's until it is renewed.
                libraryExpired = read == null && !readFailed;
                if (libraryExpired) {
                    Toast.makeText(activity, text("Вход устарел: войдите ещё раз", "The sign-in expired: sign in again"),
                            Toast.LENGTH_LONG).show();
                }
                fillAccount();
            });
        });
    }

    private void confirmTransfer(SourceList playlist) {
        if (refuseWhileRunning()) return;
        if (playlist.trackCount == 0) {
            Toast.makeText(activity, text("В этом списке нет треков", "This list has no tracks"), Toast.LENGTH_SHORT).show();
            return;
        }
        CheckBox like = likeTick();
        LinearLayout option = likeOption(like);
        option.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), 0);
        boolean counted = playlist.trackCount != SourceList.UNKNOWN_COUNT;
        new ArsoundDialog(activity)
                .setTitle(text("Перенести «", "Transfer \"") + playlist.title + text("»?", "\"?"))
                .setMessage(text("Arsound найдёт " + (counted ? Numerals.russianTracks(playlist.trackCount) : "треки")
                                + " в SoundCloud и соберёт найденные в приватный плейлист. Это займёт пару минут на "
                                + "каждую сотню; можно закрыть настройки.",
                        "Arsound will look for " + (counted ? Numerals.tracks(playlist.trackCount) : "the tracks")
                                + " on SoundCloud and put the ones found into a private playlist. It takes a couple of "
                                + "minutes per hundred; the settings can be closed."))
                .setView(option)
                .setPositiveButton(text("Перенести", "Transfer"), (dialog, which) -> start(playlist.link, like.isChecked()))
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
            TransferLink from = TransferSource.linkOfKey(record.key);
            String line = (from == null ? "" : from.source().name() + " · ")
                    + text("В плейлисте ", "In the playlist ") + record.found + text(" из ", " of ") + record.total
                    + " · " + format.format(new Date(record.time)) + ". "
                    + (record.liked == TransferRecord.LIKES_NOT_ASKED ? "" : text("В лайках: ", "In likes: ") + record.liked + text(" из ", " of ") + record.found + ". ")
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
