package app.revanced.extension.soundcloud.lyrics;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.view.View;

import java.util.List;

import app.revanced.extension.soundcloud.lyrics.transcribe.Advice;
import app.revanced.extension.soundcloud.lyrics.transcribe.AsrModel;
import app.revanced.extension.soundcloud.lyrics.transcribe.Failure;
import app.revanced.extension.soundcloud.lyrics.transcribe.HeavyNotice;
import app.revanced.extension.soundcloud.lyrics.transcribe.Sizes;
import app.revanced.extension.soundcloud.lyrics.transcribe.Transcript;
import app.revanced.extension.soundcloud.lyrics.transcribe.TranscriptionManager;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/**
 * The neural recognition on the lyrics page of one player page: the offer for a song without lyrics, the dialogs
 * before it starts (what it costs, the state of the phone, the mobile network), the progress line and the result.
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
        if (!TranscriptionManager.canOffer(track.urn, track.durationMs)) return false;
        info = track;
        // A recognition that finished while this page was elsewhere: the words are on the phone already.
        Lyrics ready = AiTranscripts.lookUp(track);
        if (ready != null) {
            LyricsRepository.rememberRecognised(track, ready);
            host.showAiWords(ready);
            return true;
        }
        panel.showProgress(preparing(), cancel);
        if (!TranscriptionManager.attach(track.urn, this)) showOffer();
        host.showAiPanel();
        return true;
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
            ask();
        }
    };

    private final Runnable cancel = new Runnable() {
        @Override
        public void run() {
            TranscriptionManager.cancel();
        }
    };

    private final Runnable useAccurate = new Runnable() {
        @Override
        public void run() {
            TranscriptionManager.setChosenModel(AsrModel.GIGAAM);
            showOffer();
        }
    };

    private void showOffer() {
        if (info == null) return;
        if (TranscriptionManager.isEnabled() && TranscriptionManager.failedBefore(info.urn)) {
            panel.show(text("Нейросеть не разобрала слова этой песни", "The network could not make out the words of this song"),
                    null, text("Попробовать ещё раз", "Try again"), ask);
        } else {
            panel.show(LyricsResult.Status.MISSING.message(), ask, text("Распознать нейросетью", "Recognise with AI"), ask);
        }
        // The fast model is chosen: say that it makes many mistakes and offer the accurate one, rather than use it silently.
        if (TranscriptionManager.suggestsAccurateModel()) {
            panel.suggest(text("Выбрана быстрая модель: она часто ошибается. Перейти на точную?",
                    "The fast model is chosen and it makes many mistakes. Switch to the accurate one?"), useAccurate);
        }
    }

    /** The user wants the song recognised: the checks and questions come first, in this order. */
    private void ask() {
        if (info == null) return;
        Advice advice = TranscriptionManager.advise();
        if (advice.blocker != null) {
            showBlocked(advice);
            return;
        }
        boolean ready = TranscriptionManager.isEnabled() && advice.downloadBytes == 0;
        if (!ready) {
            showOfferDialog(advice);
        } else if (!advice.warnings.isEmpty()) {
            showWarnings(advice);
        } else {
            proceed(advice);
        }
    }

    private void showOfferDialog(final Advice advice) {
        StringBuilder body = new StringBuilder();
        body.append(text("Текст этой песни не нашёлся. Нейросеть может послушать песню на вашем телефоне и записать "
                + "слова. Это тяжёлая работа для телефона, почти как тяжёлая игра.\n\n", "No lyrics were found for this "
                + "song. A neural network can listen to the song on your phone and write down the words. It is heavy "
                + "work for a phone, almost like a heavy game.\n\n"));
        body.append(HeavyNotice.points(panel.getContext())).append("\n");
        body.append(text("• Текст приблизительный: на русских песнях в нашей проверке верно около шести слов из десяти, в "
                + "распевных припевах и при сильной обработке голоса бывает мусор.\n", "• The text is approximate: on "
                + "Russian songs in our test about six words in ten came out right; sung-out choruses and heavily "
                + "processed voices may come out as rubbish.\n"));
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
                .setPositiveButton(text("Включить и распознать", "Turn on and recognise"), (dialog, which) -> {
                    HeavyNotice.markSeen();
                    proceed(advice);
                })
                .setNegativeButton(text("Не сейчас", "Not now"), null)
                .show();
    }

    private void showWarnings(final Advice advice) {
        StringBuilder body = new StringBuilder();
        appendWarnings(body, advice.warnings);
        new ArsoundDialog(panel.getContext())
                .setTitle(text("Распознать сейчас?", "Recognise now?"))
                .setMessage(body.toString().trim())
                .setPositiveButton(text("Распознать", "Recognise"), (dialog, which) -> proceed(advice))
                .setNegativeButton(text("Не сейчас", "Not now"), null)
                .show();
    }

    private static void appendWarnings(StringBuilder body, List<String> warnings) {
        for (String warning : warnings) body.append("\n\n").append(text("Внимание: ", "Note: ")).append(warning);
    }

    /** No memory for the chosen model: the work does not start; the fast model is offered if it would fit. */
    private void showBlocked(Advice advice) {
        ArsoundDialog dialog = new ArsoundDialog(panel.getContext())
                .setTitle(text("Распознавание не начнётся", "Recognition cannot start"))
                .setMessage(advice.blocker)
                .setNegativeButton(text("Закрыть", "Close"), null);
        if (advice.lighterModelFits) {
            dialog.setPositiveButton(text("Взять быструю модель (хуже)", "Use the fast model (worse)"), (d, which) -> {
                TranscriptionManager.setChosenModel(AsrModel.BASE);
                ask();
            });
        }
        dialog.show();
    }

    /** The last question: a download on mobile data. Then the work starts. */
    private void proceed(Advice advice) {
        if (advice.metered && advice.downloadBytes > 0) {
            new ArsoundDialog(panel.getContext())
                    .setTitle(text("Скачать по мобильной сети?", "Download over mobile data?"))
                    .setMessage(text("Сейчас используется мобильная или ограниченная сеть. Будет скачано "
                            + Sizes.format(advice.downloadBytes) + ".", "The network in use is mobile or limited. "
                            + Sizes.format(advice.downloadBytes) + " will be downloaded."))
                    .setPositiveButton(text("Скачать", "Download"), (dialog, which) -> start())
                    .setNegativeButton(text("Подождать Wi-Fi", "Wait for Wi-Fi"), null)
                    .show();
        } else {
            start();
        }
    }

    private void start() {
        if (info == null) return;
        TranscriptionManager.forgetFailure(info.urn);
        TranscriptionManager.setEnabled(true);
        panel.showProgress(preparing(), cancel);
        TranscriptionManager.start(info.urn, info.durationMs, this);
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
    public void onFinished(Transcript transcript) {
        if (info == null) return;
        if (!transcript.isDone()) {
            showOffer();
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
        showOffer();
    }

    // endregion
}
