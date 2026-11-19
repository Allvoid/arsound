package app.revanced.extension.soundcloud.lyrics;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.view.View;

import java.util.List;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.lyrics.transcribe.Advice;
import app.revanced.extension.soundcloud.lyrics.transcribe.AsrModel;
import app.revanced.extension.soundcloud.lyrics.transcribe.Failure;
import app.revanced.extension.soundcloud.lyrics.transcribe.HeavyNotice;
import app.revanced.extension.soundcloud.lyrics.transcribe.Sizes;
import app.revanced.extension.soundcloud.lyrics.transcribe.SongLanguage;
import app.revanced.extension.soundcloud.lyrics.transcribe.Transcript;
import app.revanced.extension.soundcloud.lyrics.transcribe.TranscriptionManager;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/**
 * The neural recognition on the lyrics page of one player page: the offer for a song without lyrics, the dialogs
 * before it starts (the language of the song, what it costs, the state of the phone, the mobile network), the progress
 * line and the result. A recognised text can be heard again in another language when the first answer was wrong
 * ({@link #redo}).
 * One lives in each {@link LyricsLayer}, which hands it the song when LRCLIB has none and shows its {@link AiPanel}.
 * <p>
 * The work itself is {@link TranscriptionManager}'s. This class only asks, tells and shows; it hears about the work
 * as its {@link TranscriptionManager.Listener}.
 */
final class LyricsAi implements TranscriptionManager.Listener {
    /** What the page does for the panel. */
    interface Host {
        /** The panel is what the page shows now. */
        void showAiPanel();

        /** The words have been recognised: the page shows them like any lyrics. */
        void showAiWords(Lyrics lyrics);

        /** The pasted words of the song were removed: the page looks the song up again. */
        void searchAgainAfterRemoval();
    }

    private final Host host;
    private final AiPanel panel;
    /** The song the panel is about; null when the page has none or lets it go. */
    private TrackInfo info;

    LyricsAi(Context context, int accent, Host host) {
        this.host = host;
        this.panel = new AiPanel(context, accent);
    }

    View view() {
        return panel;
    }

    /**
     * LRCLIB has no words for the song: offers the recognition, or shows its progress if it is under way.
     *
     * @return False if it cannot be offered (this phone cannot run it, or the song is too long or too short): the
     * page shows its plain message then.
     */
    boolean offerFor(TrackInfo track) {
        return offerFor(track, null);
    }

    /**
     * The same, after a search that failed (LRCLIB busy, a server that did not answer): the recognition is offered too,
     * and a tap on the message looks the song up again. Only "Could not load" was shown before, with no way to the
     * network, although LRCLIB is often busy for long.
     *
     * @param retry Looks the song up again; null when the search answered that there is no text.
     */
    boolean offerFor(TrackInfo track, Runnable retry) {
        if (!TranscriptionManager.canOffer(track.urn, track.durationMs)) return false;
        retrySearch = retry;
        info = track;
        panel.showProgress(preparing(), cancel);
        host.showAiPanel();
        // A recognition that finished while this page was elsewhere: the words are on the phone already. They are read
        // from the files off the main thread; the panel says "preparing" for that moment.
        lookUpThen(track, ready -> {
            if (ready != null) {
                LyricsRepository.rememberRecognised(track, ready);
                host.showAiWords(ready);
                return;
            }
            if (!TranscriptionManager.attach(track.urn, this)) showOffer();
        });
        return true;
    }

    /** Reads the stored words of the song off the main thread, then answers there, if the panel is still about it. */
    private void lookUpThen(TrackInfo track, java.util.function.Consumer<Lyrics> then) {
        Utils.runOnBackgroundThread(() -> {
            Lyrics ready = AiTranscripts.lookUp(track);
            Utils.runOnMainThread(() -> {
                if (info == track) then.accept(ready);
            });
        });
    }

    /** The page lets go of the song (another track, or the page closes): the work goes on, but nobody is told. */
    void release() {
        TranscriptionManager.detach(this);
        info = null;
    }

    // region The offer

    private final Runnable ask = new Runnable() {
        @Override
        public void run() {
            askLanguage(false);
        }
    };

    private final Runnable cancel = new Runnable() {
        @Override
        public void run() {
            TranscriptionManager.cancel();
        }
    };

    private void showOffer() {
        if (info == null) return;
        if (TranscriptionManager.isEnabled() && TranscriptionManager.failedBefore(info.urn)) {
            panel.show(text("Нейросеть не разобрала слова этой песни", "The network could not make out the words of this song"),
                    retrySearch, text("Попробовать ещё раз", "Try again"), ask);
        } else if (retrySearch != null) {
            panel.show(LyricsResult.Status.ERROR.message(), retrySearch, text("Распознать нейросетью", "Recognise with AI"), ask);
        } else {
            panel.show(LyricsResult.Status.MISSING.message(), ask, text("Распознать нейросетью", "Recognise with AI"), ask);
        }
        offerPaste();
    }

    /** Under the offer, the button that lets the user paste the words. */
    private void offerPaste() {
        final TrackInfo track = info;
        if (track == null) return;
        panel.offerPaste(text("Вставить текст", "Paste the lyrics"), () -> pasteDialog(track));
    }

    /**
     * The song has no lyrics and the network cannot be offered on this phone (or for this song): the message, and the
     * way to paste the words.
     *
     * @param retry Looks the song up again; null when the search answered that there is no text.
     */
    void offerPasteOnly(TrackInfo track, Runnable retry) {
        info = track;
        retrySearch = retry;
        panel.show(retry != null ? LyricsResult.Status.ERROR.message() : LyricsResult.Status.MISSING.message(), retry, null, null);
        offerPaste();
        host.showAiPanel();
    }

    /**
     * The dialog to paste the words of the song into, or to edit the ones pasted before. What is pasted is aligned
     * with the track (times by the words the network heard, or spread over the track) and kept; the page then shows it.
     */
    void pasteDialog(final TrackInfo track) {
        // The dialog is for the song on the page: the words it saves are shown on this page, so the page is this song.
        info = track;
        final Context context = panel.getContext();
        Utils.runOnBackgroundThread(() -> {
            final String before = PastedLyrics.rawText(track);
            Utils.runOnMainThread(() -> showPasteDialog(context, track, before, before != null));
        });
    }

    /**
     * @param startText The text to start with: the words as they were pasted, or the text the user typed when the save
     *                failed and the dialog comes back.
     * @param editing Whether the song already has pasted words (the dialog edits them, not pastes).
     */
    private void showPasteDialog(Context context, final TrackInfo track, String startText, boolean editing) {
        final android.widget.EditText input = new android.widget.EditText(context);
        // The minimum must not be more than the maximum, or the field is taller than the limit and pushes Save away.
        input.setMinLines(5);
        input.setMaxLines(7);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint(text("Текст песни, по строке на строку", "The lyrics, one line of the song per line"));
        input.setTextColor(app.revanced.extension.soundcloud.theme.ArsoundTheme.palette(context, "primary"));
        input.setHintTextColor(app.revanced.extension.soundcloud.theme.ArsoundTheme.palette(context, "secondary"));
        if (startText != null) input.setText(startText);
        android.widget.LinearLayout box = new android.widget.LinearLayout(context);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = Math.round(16 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        android.widget.TextView note = new android.widget.TextView(context);
        note.setText(text("Время строк подберётся само: по словам, которые услышала нейросеть, а если её текста нет, "
                        + "то примерно, по длине строк. Такой текст помечается «примерное время».",
                "The times of the lines are set for you: by the words the network heard, or, if there is no such text, "
                        + "roughly by the length of the lines. Such a text is marked \"approximate timing\"."));
        note.setTextColor(app.revanced.extension.soundcloud.theme.ArsoundTheme.palette(context, "secondary"));
        note.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(0, 0, 0, pad / 2);
        box.addView(note);
        android.widget.TextView fromClipboard = new android.widget.TextView(context);
        fromClipboard.setText(text("Вставить из буфера обмена", "Paste from the clipboard"));
        fromClipboard.setTextColor(app.revanced.extension.soundcloud.theme.ArsoundTheme.palette(context, "special"));
        fromClipboard.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        fromClipboard.setPadding(0, pad / 4, 0, pad / 2);
        fromClipboard.setOnClickListener(v -> {
            String clip = clipboardText(context);
            if (clip.isEmpty()) {
                Utils.showToastShort(text("В буфере нет текста", "The clipboard has no text"));
            } else {
                input.setText(clip);
                input.setSelection(clip.length());
            }
        });
        box.addView(fromClipboard);
        box.addView(input);
        android.app.Dialog shown = new ArsoundDialog(context)
                .setTitle(editing ? text("Изменить текст", "Edit the lyrics") : text("Вставить текст", "Paste the lyrics"))
                .setView(box)
                .setPositiveButton(text("Сохранить", "Save"), (dialog, which) -> {
                    hideKeyboard(input);
                    keepPasted(track, input.getText().toString(), editing);
                })
                .setNegativeButton(text("Отмена", "Cancel"), (dialog, which) -> hideKeyboard(input))
                // Back and a tap outside close the dialog too.
                .setOnDismissListener(dialog -> hideKeyboard(input))
                .show();
        // The window shrinks above the keyboard: a long text must not push the Save button under it.
        if (shown != null && shown.getWindow() != null) {
            shown.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    /**
     * Takes the keyboard down with the dialog: after it closes the focus goes to the comment field of the player, which
     * would keep the keyboard up and squeeze the page.
     */
    private static void hideKeyboard(android.view.View field) {
        try {
            android.view.inputmethod.InputMethodManager keyboard = (android.view.inputmethod.InputMethodManager)
                    field.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(field.getWindowToken(), 0);
        } catch (Throwable ignored) {
            // The keyboard stays: nothing worse than before.
        }
    }

    private static String clipboardText(Context context) {
        try {
            android.content.ClipboardManager manager = (android.content.ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            android.content.ClipData clip = manager == null ? null : manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence item = clip.getItemAt(0).coerceToText(context);
            return item == null ? "" : item.toString();
        } catch (Throwable ex) {
            return "";
        }
    }

    /** Whether the page is still on this song: a swipe to another track or a closed page is not. */
    private boolean stillOn(TrackInfo track) {
        return info != null && info.sameTrack(track);
    }

    /**
     * Keeps the text the dialog returned. The dialog has closed by now, so when the text cannot be kept it comes back
     * with the same text: nothing is typed again.
     *
     * @param editing Whether the song had pasted words before (the dialog was for editing them).
     */
    private void keepPasted(final TrackInfo track, final String pasted, final boolean editing) {
        if (pasted.trim().isEmpty()) {
            // Edit mode with the box cleared: the user means to take the text away, so removal is asked about first.
            if (editing) {
                removePasted(track);
            } else {
                Utils.showToastShort(text("Текст пустой: ничего не сохранено", "The text is empty: nothing was kept"));
            }
            return;
        }
        if (PastedLyrics.tooLong(pasted)) {
            Utils.showToastLong(text("Текст слишком длинный: не больше 20 000 знаков",
                    "The text is too long: at most 20,000 characters"));
            if (stillOn(track)) showPasteDialog(panel.getContext(), track, pasted, editing);
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            final Lyrics lyrics = PastedLyrics.save(track, pasted);
            Utils.runOnMainThread(() -> {
                if (lyrics == null) {
                    Utils.showToastLong(text("Не получилось сохранить текст", "Could not keep the text"));
                    if (stillOn(track)) showPasteDialog(panel.getContext(), track, pasted, editing);
                    return;
                }
                // The words are remembered for the song they were pasted for, whatever page is shown now.
                LyricsRepository.rememberPasted(track, lyrics);
                // A swipe to another song during the save must not show these words on that song's page.
                if (stillOn(track)) host.showAiWords(lyrics);
            });
        });
    }

    /**
     * The pasted words are taken away, once the user has confirmed it: what the search finds is shown again. A tap is
     * easy to make by accident while scrolling, so the dialog asks first.
     */
    void removePasted(final TrackInfo track) {
        new ArsoundDialog(panel.getContext())
                .setTitle(text("Удалить текст?", "Remove the text?"))
                .setMessage(text("Вставленный текст этой песни удалится с телефона. Вернуть его можно только вставкой заново.",
                        "The text pasted for this song will be deleted from the phone. It can come back only by pasting it again."))
                .setPositiveButton(text("Удалить", "Remove"), (dialog, which) -> removeConfirmed(track))
                .setNegativeButton(text("Отмена", "Cancel"), null)
                .show();
    }

    private void removeConfirmed(final TrackInfo track) {
        Utils.runOnBackgroundThread(() -> {
            final boolean removed = PastedLyrics.remove(track);
            Utils.runOnMainThread(() -> {
                // The file is still there: the words stay on the phone and on the page, and the user is told.
                if (!removed) {
                    Utils.showToastLong(text("Не получилось удалить текст: он остался на телефоне",
                            "Could not remove the text: it is still on the phone"));
                    return;
                }
                LyricsRepository.forget(track);
                host.searchAgainAfterRemoval();
            });
        });
    }

    /** Looks the song up again; set while the offer follows a failed search, null after "no text". */
    private Runnable retrySearch;

    /**
     * The words on screen are in the wrong language, the user says: asks the language again and hears the song anew.
     * The text stays until the new one is ready.
     */
    void redo(TrackInfo track) {
        if (TranscriptionManager.isRunningFor(track.urn)) return;
        info = track;
        askLanguage(true);
    }

    /** One recognition the user asked for: the language they named and the model that listens. */
    private static final class Request {
        final SongLanguage language;
        final AsrModel model;
        /** Whether the song is heard anew although it has a text. */
        final boolean again;

        Request(SongLanguage language, AsrModel model, boolean again) {
            this.language = language;
            this.model = model;
            this.again = again;
        }

        Request withModel(AsrModel lighter) {
            return new Request(language, lighter, again);
        }
    }

    /** The user wants the song recognised: the language comes first, then the checks and questions, in this order. */
    private void askLanguage(final boolean again) {
        if (info == null) return;
        final TrackInfo track = info;
        SongLanguage current = again ? languageOnScreen(track) : null;
        SongLanguage likely = current != null ? null : TranscriptionManager.suggestedLanguage(track.uploader, track.title);
        SongLanguageDialog.show(panel.getContext(), likely, current, language -> {
            if (info != track) return;
            TranscriptionManager.rememberLanguage(track.uploader, language);
            check(new Request(language, language.model(), again));
        });
    }

    /** The language the shown text was heard in; null if it does not say. Reads a small file. */
    private static SongLanguage languageOnScreen(TrackInfo track) {
        Transcript shown = TranscriptionManager.stored(track.urn, track.durationMs);
        return shown == null ? null : SongLanguage.ofHeard(shown.language);
    }

    private void check(Request request) {
        if (info == null) return;
        Advice advice = TranscriptionManager.advise(request.model);
        if (advice.blocker != null) {
            showBlocked(request, advice);
            return;
        }
        boolean ready = TranscriptionManager.isEnabled() && advice.downloadBytes == 0;
        if (!ready) {
            showOfferDialog(request, advice);
        } else if (!advice.warnings.isEmpty()) {
            showWarnings(request, advice);
        } else {
            proceed(request, advice);
        }
    }

    private void showOfferDialog(final Request request, final Advice advice) {
        StringBuilder body = new StringBuilder();
        body.append(retrySearch != null
                ? text("Текст этой песни сейчас не загрузился. ", "The lyrics of this song did not load now. ")
                : text("Текст этой песни не нашёлся. ", "No lyrics were found for this song. "));
        body.append(text("Нейросеть может послушать песню на вашем телефоне и записать слова. Это тяжёлая работа для "
                + "телефона, почти как тяжёлая игра.\n\n", "A neural network can listen to the song on your phone and "
                + "write down the words. It is heavy work for a phone, almost like a heavy game.\n\n"));
        body.append(HeavyNotice.points(panel.getContext())).append("\n");
        if (request.language == SongLanguage.RUSSIAN) {
            body.append(text("• Текст приблизительный: на русских песнях в нашей проверке верно около шести слов из десяти, в "
                    + "распевных припевах и при сильной обработке голоса бывает мусор.\n", "• The text is approximate: on "
                    + "Russian songs in our test about six words in ten came out right; sung-out choruses and heavily "
                    + "processed voices may come out as rubbish.\n"));
        } else {
            body.append(text("• Текст приблизительный: многоязычная нейросеть ошибается чаще русской, в распевных припевах "
                    + "и при сильной обработке голоса бывает мусор.\n", "• The text is approximate: the multilingual "
                    + "network makes more mistakes than the Russian one; sung-out choruses and heavily processed voices "
                    + "may come out as rubbish.\n"));
        }
        if (advice.downloadBytes > 0) {
            body.append(text("• Один раз нужно скачать модель и компоненты (" + Sizes.format(advice.downloadBytes)
                            + "). Интернет нужен для этого и, если песня не скачана, для её звука; сам звук никуда не отправляется.\n",
                    "• The model and components (" + Sizes.format(advice.downloadBytes) + ") are downloaded once. The "
                            + "internet is needed for that and, if the song is not downloaded, for its sound; the sound "
                            + "is sent nowhere.\n"));
        } else {
            body.append(text("• Модель уже скачана. Звук песни никуда не отправляется.\n",
                    "• The model is already downloaded. The sound of the song is sent nowhere.\n"));
        }
        body.append(text("• Результат сохранится на телефоне: повторно песню обрабатывать не придётся.",
                "• The result is kept on the phone: the song is not processed again."));
        appendWarnings(body, advice.warnings);
        new ArsoundDialog(panel.getContext())
                .setTitle(text("Распознать текст нейросетью?", "Recognise the lyrics with a neural network?"))
                .setMessage(body.toString())
                .setPositiveButton(TranscriptionManager.isEnabled()
                        ? text("Скачать и распознать", "Download and recognise")
                        : text("Включить и распознать", "Turn on and recognise"), (dialog, which) -> {
                    HeavyNotice.markSeen();
                    proceed(request, advice);
                })
                .setNegativeButton(text("Не сейчас", "Not now"), null)
                .show();
    }

    private void showWarnings(final Request request, final Advice advice) {
        StringBuilder body = new StringBuilder();
        appendWarnings(body, advice.warnings);
        new ArsoundDialog(panel.getContext())
                .setTitle(text("Распознать сейчас?", "Recognise now?"))
                .setMessage(body.toString().trim())
                .setPositiveButton(text("Распознать", "Recognise"), (dialog, which) -> proceed(request, advice))
                .setNegativeButton(text("Не сейчас", "Not now"), null)
                .show();
    }

    private static void appendWarnings(StringBuilder body, List<String> warnings) {
        for (String warning : warnings) body.append("\n\n").append(text("Внимание: ", "Note: ")).append(warning);
    }

    /** No memory for the model: the work does not start; the fast model is offered if it would fit. */
    private void showBlocked(final Request request, Advice advice) {
        ArsoundDialog dialog = new ArsoundDialog(panel.getContext())
                .setTitle(text("Распознавание не начнётся", "Recognition cannot start"))
                .setMessage(advice.blocker)
                .setNegativeButton(text("Закрыть", "Close"), null);
        if (advice.lighterModelFits) {
            dialog.setPositiveButton(text("Взять быструю модель (хуже)", "Use the fast model (worse)"),
                    (d, which) -> check(request.withModel(AsrModel.BASE)));
        }
        dialog.show();
    }

    /** The last question: a download on mobile data. Then the work starts. */
    private void proceed(final Request request, Advice advice) {
        if (advice.metered && advice.downloadBytes > 0) {
            new ArsoundDialog(panel.getContext())
                    .setTitle(text("Скачать по мобильной сети?", "Download over mobile data?"))
                    .setMessage(text("Сейчас используется мобильная или ограниченная сеть. Будет скачано "
                            + Sizes.format(advice.downloadBytes) + ".", "The network in use is mobile or limited. "
                            + Sizes.format(advice.downloadBytes) + " will be downloaded."))
                    .setPositiveButton(text("Скачать", "Download"), (dialog, which) -> start(request))
                    .setNegativeButton(text("Подождать Wi-Fi", "Wait for Wi-Fi"), null)
                    .show();
        } else {
            start(request);
        }
    }

    private void start(Request request) {
        if (info == null) return;
        TranscriptionManager.setEnabled(true);
        panel.showProgress(preparing(), cancel);
        // A text heard again: the page leaves the old words for the progress until the new ones come.
        host.showAiPanel();
        TranscriptionManager.start(info.urn, info.durationMs, request.language, request.model, request.again, this);
    }

    // endregion

    // region What the work tells

    private static String hint() {
        return text(" · нажмите, чтобы отменить", " · tap to cancel");
    }

    private static String preparing() {
        return text("Готовлю…", "Preparing…") + hint();
    }

    @Override
    public void onInstalling(int percent, boolean library) {
        if (info == null) return;
        String what = library ? text("Загружаю компоненты…", "Downloading components…")
                : text("Загружаю модель…", "Downloading the model…");
        panel.showProgress(what + " " + percent + "%" + hint(), cancel);
    }

    @Override
    public void onSeparating(int percent) {
        if (info == null) return;
        panel.showProgress(text("Отделяю голос от музыки… " + percent + "%", "Taking the voice out of the music… "
                + percent + "%") + hint(), cancel);
    }

    @Override
    public void onListening(int current, int total) {
        if (info == null) return;
        panel.showProgress(text("Распознаю… " + current + " из " + total, "Recognising… " + current + " of " + total)
                + hint(), cancel);
    }

    @Override
    public void onCooling() {
        if (info == null) return;
        panel.showProgress(text("Телефон нагрелся, делаю паузу…", "The phone is hot, pausing…") + hint(), cancel);
    }

    @Override
    public void onStopping() {
        if (info == null) return;
        // The piece being worked on runs to its end: a few seconds, during which the button would seem to do nothing.
        panel.showProgress(text("Останавливаю…", "Stopping…"), null);
    }

    @Override
    public void onWaiting() {
        if (info == null) return;
        panel.showProgress(text("Жду, пока остановится прошлое распознавание…",
                "Waiting for the earlier recognition to stop…"), cancel);
    }

    @Override
    public void onFinished(Transcript transcript) {
        if (info == null) return;
        if (!transcript.isDone()) {
            lookUpThen(info, earlier -> {
                if (earlier == null) {
                    showOffer();
                } else {
                    Utils.showToastLong(text("Нейросеть не разобрала слова на этом языке: оставлен прежний текст.",
                            "The network could not make out the words in this language: the earlier text stays."));
                    host.showAiWords(earlier);
                }
            });
            return;
        }
        Lyrics lyrics = AiTranscripts.toLyrics(transcript);
        LyricsRepository.rememberRecognised(info, lyrics);
        host.showAiWords(lyrics);
    }

    @Override
    public void onFailed(Failure failure) {
        if (info == null) return;
        if (failure.isRetryable()) {
            panel.show(failure.message(), null, text("Повторить", "Try again"), ask);
        } else {
            panel.showNote(failure.message());
        }
    }

    @Override
    public void onCancelled() {
        if (info == null) return;
        // A text heard again and stopped: the earlier words are still there.
        lookUpThen(info, earlier -> {
            if (earlier != null) host.showAiWords(earlier);
            else showOffer();
        });
    }

    // endregion
}
