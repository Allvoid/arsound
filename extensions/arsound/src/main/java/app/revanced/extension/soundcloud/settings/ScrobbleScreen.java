package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.scrobble.Scrobbler;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * Scrobbling: the tracks listened to are sent to ListenBrainz and to Last.fm. Both are off until the user connects an
 * account: ListenBrainz with a token pasted from its settings page, Last.fm by approving the app on last.fm in the
 * browser (the app never sees the password, only the name of the account). The rule and the texts are those of
 * Arsound Desktop.
 */
final class ScrobbleScreen extends SectionScreen {
    static final String ID = "scrobble";

    ScrobbleScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Скробблинг", "Scrobbling");
    }

    @Override
    String description() {
        return text("Отправка прослушанного в ListenBrainz и Last.fm", "Send what you listen to to ListenBrainz and Last.fm");
    }

    @Override
    String summary() {
        boolean listenBrainz = Scrobbler.isEnabled(Scrobbler.Service.LISTENBRAINZ);
        boolean lastFm = Scrobbler.isEnabled(Scrobbler.Service.LASTFM);
        if (!listenBrainz && !lastFm) return text("Выключен", "Off");
        if (listenBrainz && lastFm) return "ListenBrainz, Last.fm";
        return listenBrainz ? "ListenBrainz" : "Last.fm";
    }

    @Override
    String iconKey() {
        return "stats";
    }

    @Override
    String keywords() {
        return text("скробблинг скроббл listenbrainz last.fm lastfm лист брейнз ласт фм прослушивания история токен",
                "scrobble scrobbling listenbrainz last.fm lastfm listens history token");
    }

    @Override
    void fill(LinearLayout list) {
        TextView note = ui.createText("Body.Secondary", text(
                "Трек засчитывается, когда прослушана половина его длины или 4 минуты, и он не короче 30 секунд. "
                        + "Трек, который играет сейчас, тоже отправляется. Уходят только исполнитель, название, длина "
                        + "и время начала. Если связи нет, прослушанное ждёт в очереди и уходит позже.",
                "A track counts when half of its length or 4 minutes have been heard, and it is at least 30 seconds "
                        + "long. The track playing now is sent too. Only the artist, the title, the length and the start "
                        + "time go out. Without a connection the listens wait in a queue and are sent later."));
        note.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(10));
        list.addView(note);

        // ListenBrainz
        list.addView(ui.createSubHeading("ListenBrainz"));
        list.addView(ui.createToggleRow(
                text("Скробблинг в ListenBrainz", "Scrobble to ListenBrainz"),
                text("Отправляет на listenbrainz.org прослушанные треки (половина или 4 минуты) и тот, что играет сейчас. "
                                + "Нужен токен ниже. По умолчанию выключено.",
                        "Sends the tracks you played (half of them or 4 minutes) and the one playing now to listenbrainz.org. "
                                + "Needs the token below. Off by default."),
                Scrobbler.isEnabled(Scrobbler.Service.LISTENBRAINZ),
                (button, checked) -> Scrobbler.setEnabled(Scrobbler.Service.LISTENBRAINZ, checked)
        ).icon("stats"));
        ActionRow tokenRow = ui.createActionRow(
                text("Токен ListenBrainz", "ListenBrainz token"),
                tokenSummary(),
                v -> {
                });
        tokenRow.setOnClickListener(v -> editToken(() -> tokenRow.description().setText(tokenSummary())));
        tokenRow.icon("account");
        list.addView(tokenRow);

        // Last.fm
        list.addView(ui.createSubHeading("Last.fm"));
        list.addView(ui.createToggleRow(
                text("Скробблинг в Last.fm", "Scrobble to Last.fm"),
                text("Отправляет на last.fm прослушанные треки (половина или 4 минуты) и тот, что играет сейчас. "
                                + "Нужно подключить аккаунт ниже. По умолчанию выключено.",
                        "Sends the tracks you played (half of them or 4 minutes) and the one playing now to last.fm. "
                                + "Connect the account below. Off by default."),
                Scrobbler.isEnabled(Scrobbler.Service.LASTFM),
                (button, checked) -> Scrobbler.setEnabled(Scrobbler.Service.LASTFM, checked)
        ).icon("stats"));
        ActionRow accountRow = ui.createActionRow(
                text("Аккаунт Last.fm", "Last.fm account"),
                lastFmSummary(),
                v -> {
                });
        accountRow.setOnClickListener(v -> lastFmAccount(() -> accountRow.description().setText(lastFmSummary())));
        accountRow.icon("account");
        list.addView(accountRow);

        TextView queue = ui.createText("Body.Secondary", queueNote());
        queue.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), 0);
        list.addView(queue);
    }

    private static String queueNote() {
        int waiting = Scrobbler.pending(Scrobbler.Service.LISTENBRAINZ) + Scrobbler.pending(Scrobbler.Service.LASTFM);
        return waiting == 0 ? "" : text("Ждут отправки: " + waiting, "Waiting to be sent: " + waiting);
    }

    private static String tokenSummary() {
        if (!Scrobbler.isConnected(Scrobbler.Service.LISTENBRAINZ)) {
            return text("Не задан. Токен берётся на listenbrainz.org/settings.",
                    "Not set. The token comes from listenbrainz.org/settings.");
        }
        String user = Scrobbler.user(Scrobbler.Service.LISTENBRAINZ);
        return user.isEmpty() ? text("Задан. Хранится только на этом телефоне.", "Set. It stays on this phone only.")
                : text("Аккаунт: ", "Account: ") + user;
    }

    private static String lastFmSummary() {
        if (!Scrobbler.isLastFmAvailable()) {
            return text("В этой сборке нет ключа Last.fm, подключить нельзя.",
                    "This build has no Last.fm key, so it cannot be connected.");
        }
        if (!Scrobbler.isConnected(Scrobbler.Service.LASTFM)) {
            return text("Не подключён. Нажмите: откроется last.fm, там нужно нажать «Разрешить».",
                    "Not connected. Tap: last.fm opens in the browser, where you press Allow.");
        }
        String user = Scrobbler.user(Scrobbler.Service.LASTFM);
        return (user.isEmpty() ? text("Подключён", "Connected") : text("Аккаунт: ", "Account: ") + user)
                + text(". Нажмите, чтобы отключить.", ". Tap to disconnect.");
    }

    private boolean alive() {
        return !ui.activity.isFinishing() && !ui.activity.isDestroyed();
    }

    private void setResult(TextView result, String message, boolean good) {
        result.setTextColor(ArsoundTheme.palette(ui.activity, good ? "special" : "error"));
        result.setText(message);
    }

    private TextView createResult() {
        TextView result = ui.createText("Body.Secondary", "");
        result.setTag(ArsoundDialog.KEEP_COLORS);
        result.setTextColor(ArsoundTheme.palette(ui.activity, "secondary"));
        result.setPadding(0, ui.dp(10), 0, 0);
        return result;
    }

    /** The token field: the token is checked with the service before it is kept. */
    private void editToken(Runnable onChanged) {
        LinearLayout form = ui.createColumn();
        form.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), 0);
        EditText field = new EditText(ui.activity);
        field.setSingleLine(true);
        field.setHint(text("Токен со страницы listenbrainz.org/settings", "Token from listenbrainz.org/settings"));
        // Shown as dots; the keyboard must not learn or suggest it.
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        form.addView(field);
        TextView result = createResult();
        form.addView(result);

        ArsoundDialog dialog = new ArsoundDialog(ui.activity)
                .setTitle(text("Токен ListenBrainz", "ListenBrainz token"))
                .setMessage(text("Ваш пользовательский токен со страницы listenbrainz.org/settings. Он хранится только на этом "
                                + "телефоне и уходит только на listenbrainz.org.",
                        "Your user token from listenbrainz.org/settings. It stays on this phone and goes only to listenbrainz.org."))
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                // Stays open when the token is refused, so what was typed is not lost.
                .setPositiveButtonKeepingOpen(text("Сохранить", "Save"), (d, which) -> {
                    String token = field.getText().toString().trim();
                    if (token.isEmpty()) {
                        setResult(result, text("Сначала вставьте токен.", "Paste the token first."), false);
                        return;
                    }
                    setResult(result, text("Проверяю…", "Checking…"), true);
                    Utils.runOnBackgroundThread(() -> {
                        Scrobbler.Check check = Scrobbler.saveListenBrainzToken(token);
                        Utils.runOnMainThread(() -> {
                            if (!alive()) return;
                            if (check.status == Scrobbler.Status.OK) {
                                onChanged.run();
                                d.dismiss();
                            } else if (check.status == Scrobbler.Status.INVALID) {
                                setResult(result, text("ListenBrainz не знает этот токен.", "ListenBrainz does not know this token."), false);
                            } else {
                                setResult(result, text("Не удалось проверить: нет связи с ListenBrainz.",
                                        "Could not check: no connection to ListenBrainz."), false);
                            }
                        });
                    });
                });
        if (Scrobbler.isConnected(Scrobbler.Service.LISTENBRAINZ)) {
            dialog.setNeutralButton(text("Отключить", "Disconnect"), (d, which) -> {
                int waiting = Scrobbler.pending(Scrobbler.Service.LISTENBRAINZ);
                new ArsoundDialog(ui.activity)
                        .setTitle(text("Токен ListenBrainz", "ListenBrainz token"))
                        .setMessage(text("Отключить ListenBrainz? Токен будет забыт на этом телефоне, а то, что ждало отправки"
                                        + (waiting > 0 ? " (" + waiting + ")" : "") + ", не уйдёт.",
                                "Disconnect ListenBrainz? The token is forgotten on this phone, and what waited to be sent"
                                        + (waiting > 0 ? " (" + waiting + ")" : "") + " is not sent."))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(text("Отключить", "Disconnect"), (confirm, button) -> {
                            Scrobbler.disconnect(Scrobbler.Service.LISTENBRAINZ);
                            onChanged.run();
                        })
                        .show();
            });
        }
        Dialog shown = dialog.show();
        if (shown.getWindow() != null) {
            shown.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    /** Disconnects a connected account, or starts the approval on last.fm. */
    private void lastFmAccount(Runnable onChanged) {
        if (!Scrobbler.isLastFmAvailable()) {
            new ArsoundDialog(ui.activity)
                    .setTitle(text("Last.fm недоступен", "Last.fm is not available"))
                    .setMessage(text("В эту сборку не вшит ключ Last.fm, поэтому подключить аккаунт нельзя.",
                            "This build has no Last.fm key built in, so an account cannot be connected."))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        if (Scrobbler.isConnected(Scrobbler.Service.LASTFM)) {
            new ArsoundDialog(ui.activity)
                    .setTitle(text("Аккаунт Last.fm", "Last.fm account"))
                    .setMessage(text("Отключить аккаунт? Подключение будет забыто на этом телефоне, а то, что ждало отправки, не уйдёт.",
                            "Disconnect the account? The connection is forgotten on this phone, and what waited to be sent is not sent."))
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(text("Отключить", "Disconnect"), (d, which) -> {
                        Scrobbler.disconnect(Scrobbler.Service.LASTFM);
                        onChanged.run();
                    })
                    .show();
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            Scrobbler.Check begin = Scrobbler.lastFmBegin();
            Utils.runOnMainThread(() -> {
                if (!alive()) return;
                if (begin.status != Scrobbler.Status.OK) {
                    new ArsoundDialog(ui.activity)
                            .setTitle(text("Не удалось начать", "Could not start"))
                            .setMessage(begin.status == Scrobbler.Status.OFFLINE
                                    ? text("Нет связи с Last.fm.", "No connection to Last.fm.")
                                    : text("Last.fm отказал в начале подключения.", "Last.fm refused to start the connection."))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                    return;
                }
                approve(begin, onChanged);
            });
        });
    }

    /** Opens the last.fm page and waits for the user to press Allow there and come back. */
    private void approve(Scrobbler.Check begin, Runnable onChanged) {
        try {
            ui.activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(begin.value)));
        } catch (RuntimeException ex) {
            // No browser: the page address is shown below so it can be opened by hand.
        }
        LinearLayout form = ui.createColumn();
        form.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), 0);
        TextView result = createResult();
        form.addView(result);
        new ArsoundDialog(ui.activity)
                .setTitle(text("Подключение Last.fm", "Connect Last.fm"))
                .setMessage(text("На открывшейся странице last.fm нажмите «Разрешить», затем вернитесь сюда и нажмите «Готово». "
                                + "Пароль приложение не видит.",
                        "On the last.fm page that opened, press Allow, then come back here and press Done. "
                                + "The app never sees your password."))
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButtonKeepingOpen(text("Готово", "Done"), (d, which) -> {
                    setResult(result, text("Проверяю…", "Checking…"), true);
                    Utils.runOnBackgroundThread(() -> {
                        Scrobbler.Check check = Scrobbler.lastFmFinish(begin.token);
                        Utils.runOnMainThread(() -> {
                            if (!alive()) return;
                            switch (check.status) {
                                case OK:
                                    onChanged.run();
                                    d.dismiss();
                                    break;
                                case NOT_APPROVED:
                                    setResult(result, text("Last.fm ещё не получил разрешение: нажмите «Разрешить» на его странице.",
                                            "Last.fm has not been allowed yet: press Allow on its page."), false);
                                    break;
                                case OFFLINE:
                                    setResult(result, text("Нет связи с Last.fm.", "No connection to Last.fm."), false);
                                    break;
                                default:
                                    setResult(result, text("Время разрешения вышло. Закройте окно и начните заново.",
                                            "The approval has run out. Close this window and start again."), false);
                                    break;
                            }
                        });
                    });
                })
                .show();
    }
}
