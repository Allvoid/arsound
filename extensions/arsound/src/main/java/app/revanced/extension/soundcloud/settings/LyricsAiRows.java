package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.lyrics.LyricsRepository;
import app.revanced.extension.soundcloud.lyrics.transcribe.Advice;
import app.revanced.extension.soundcloud.lyrics.transcribe.AsrModel;
import app.revanced.extension.soundcloud.lyrics.transcribe.Failure;
import app.revanced.extension.soundcloud.lyrics.transcribe.Sizes;
import app.revanced.extension.soundcloud.lyrics.transcribe.TranscriptionManager;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/**
 * The rows of "Recognition of lyrics by a neural network": the switch, the model (which one, download, delete, size),
 * the language, and clearing the recognised texts. Nothing is added on a phone that cannot run the
 * model (anything but 64-bit ARM).
 * <p>
 * Used by {@code PlaybackScreen} in the lyrics section: {@code new LyricsAiRows(ui).addTo(list)}. The work behind the rows is
 * {@link TranscriptionManager}'s.
 */
final class LyricsAiRows {
    private final SettingsUi ui;
    /** The line of the model row that tells its state; null until the row is made. */
    private TextView modelStatus;
    private TextView clearStatus;

    LyricsAiRows(SettingsUi ui) {
        this.ui = ui;
    }

    /** Adds the section to the list. Nothing on a phone that cannot run the recognition. */
    void addTo(LinearLayout list) {
        if (!TranscriptionManager.isSupportedDevice()) return;
        list.addView(ui.createSubHeading(text("Распознавание текста нейросетью", "Lyrics recognition by a neural network")));
        list.addView(ui.createToggleRow(
                text("Распознавать текст нейросетью", "Recognise lyrics with a neural network"),
                text("Для песен, текста которых нет, на странице текста появляется кнопка: нейросеть послушает "
                                + "песню на телефоне и запишет слова. Текст приблизительный: на русских песнях в нашей "
                                + "проверке верно около шести слов из десяти, в распевных припевах бывает мусор. Работа "
                                + "заметно нагружает телефон. Результат сохраняется, второй раз песню не обрабатывают. "
                                + "Модели скачиваются один раз, после этого работают без интернета.",
                        "For songs without lyrics a button appears on the lyrics page: a neural network listens to the "
                                + "song on the phone and writes down the words. The text is approximate: on Russian songs in "
                                + "our test about six words in ten came out right, sung-out choruses may come out as "
                                + "rubbish. The work loads the phone noticeably. The result is kept, the song is not "
                                + "processed twice. The models are downloaded once and then work offline."),
                BoolSettings.LYRICS_AI_ENABLED,
                (button, checked) -> {
                    if (!checked) TranscriptionManager.cancel();
                    LyricsRepository.forgetRecognised();
                }).icon("ai"));
        addModelRow(list);
        list.addView(ui.createToggleRow(
                text("Отделять голос от музыки", "Take the voice out of the music"),
                text("Перед распознаванием отдельная нейросеть убирает музыку и оставляет голос: ошибок становится "
                                + "намного меньше (в нашей проверке с GigaAM — четыре слова из десяти вместо шести). Но это "
                                + "в несколько раз дольше: трек в три минуты обрабатывается несколько минут. Ещё около "
                                + "95 МБ загрузки.",
                        "Before the recognition a separate network removes the music and keeps the voice: there are far "
                                + "fewer mistakes (in our test with GigaAM four wrong words in ten instead of six). But it "
                                + "takes several times longer: a three-minute track takes several minutes. About 95 MB more "
                                + "to download."),
                BoolSettings.LYRICS_AI_SEPARATE,
                (button, checked) -> refreshModelStatus()).icon("ai"));
        addLanguageRow(list);
        addClearRow(list);
    }

    // region The model

    private void addModelRow(LinearLayout list) {
        ActionRow row = ui.createActionRow(text("Модель нейросети", "Neural network model"), "", v -> onModelRowTapped());
        row.icon("storage");
        modelStatus = row.description();
        modelStatus.setText(text("Считаю…", "Counting…"));
        list.addView(row);
        refreshModelStatus();
        // Only the row on screen hears about the download. The settings search builds the screens off screen too, and
        // such a copy must not take the progress away from the visible row (the download has one listener).
        row.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
                if (TranscriptionManager.attachInstall(installListener)) {
                    modelStatus.setText(TranscriptionManager.chosenModel().label() + " · "
                            + text("загрузка…", "downloading…"));
                }
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                TranscriptionManager.detachInstall(installListener);
            }
        });
    }

    /** Shows whether the chosen model is on the phone, how much space it takes, and what a tap does. */
    private void refreshModelStatus() {
        final AsrModel model = TranscriptionManager.chosenModel();
        final boolean installed = TranscriptionManager.isInstalled(model);
        final String name = model.label();
        final String hint = "\n" + modelHint(model);
        modelStatus.setText(name + " · " + (installed ? text("готова", "ready") : text("не загружена", "not downloaded"))
                + hint);
        ui.fillInBackground(() -> {
            final long bytes = installed ? TranscriptionManager.modelBytes(model) : TranscriptionManager.missingBytes(model);
            Utils.runOnMainThread(() -> {
                if (TranscriptionManager.isInstalling() || TranscriptionManager.chosenModel() != model) return;
                modelStatus.setText(name + " · " + (installed
                        ? text("готова, занимает " + Sizes.format(bytes) + ".", "ready, takes " + Sizes.format(bytes) + ".")
                        : text("не загружена, нужно скачать " + Sizes.format(bytes) + ".",
                        "not downloaded, " + Sizes.format(bytes) + " to download.")) + hint);
            });
        });
    }

    private void onModelRowTapped() {
        if (TranscriptionManager.isInstalling()) {
            new ArsoundDialog(ui.activity)
                    .setTitle(text("Остановить загрузку?", "Stop the download?"))
                    .setMessage(text("Скачанное сохранится, дальше можно продолжить.",
                            "What is downloaded is kept; it can go on later."))
                    .setPositiveButton(text("Остановить", "Stop"), (d, w) -> TranscriptionManager.cancelInstall())
                    .setNegativeButton(text("Продолжить", "Keep going"), null)
                    .show();
            return;
        }
        final AsrModel[] models = AsrModel.values();
        final CharSequence[] labels = new CharSequence[models.length];
        for (int i = 0; i < models.length; i++) {
            labels[i] = models[i].label() + " · " + Sizes.format(models[i].totalBytes())
                    + (TranscriptionManager.isInstalled(models[i]) ? text(" · скачана", " · downloaded") : "");
        }
        final AsrModel chosen = TranscriptionManager.chosenModel();
        ArsoundDialog dialog = new ArsoundDialog(ui.activity)
                .setTitle(text("Модель нейросети", "Neural network model"))
                .setSingleChoiceItems(labels, chosen.ordinal(), (d, which) -> {
                    d.dismiss();
                    AsrModel model = models[which];
                    TranscriptionManager.setChosenModel(model);
                    refreshModelStatus();
                    // A model that is not on the phone is of no use until it is: offer the download at once.
                    if (!TranscriptionManager.isInstalled(model)) offerDownload(model);
                })
                .setNegativeButton(text("Закрыть", "Close"), null);
        if (TranscriptionManager.isInstalled(chosen)) {
            dialog.setNeutralButton(text("Удалить", "Delete"), (d, w) -> confirmDelete(chosen));
        } else {
            dialog.setPositiveButton(text("Скачать", "Download"), (d, w) -> offerDownload(chosen));
        }
        dialog.show();
    }

    private void confirmDelete(AsrModel model) {
        new ArsoundDialog(ui.activity)
                .setTitle(text("Удалить модель?", "Delete the model?"))
                .setMessage(text("Файлы модели «" + model.label() + "» будут удалены с телефона. Уже распознанные тексты "
                                + "останутся. Чтобы распознавать дальше, модель придётся скачать снова.",
                        "The files of the model \"" + model.label() + "\" will be removed from the phone. Texts already "
                                + "recognised stay. To recognise more, the model has to be downloaded again."))
                .setPositiveButton(text("Удалить", "Delete"), (d, w) -> {
                    if (!TranscriptionManager.deleteModel(model)) {
                        Utils.showToastLong(text("Сейчас идёт распознавание: подождите или остановите его.",
                                "A recognition is running: wait for it or stop it."));
                    }
                    refreshModelStatus();
                })
                .setNegativeButton(text("Отмена", "Cancel"), null)
                .show();
    }

    /** Downloads the model, asking first on a mobile or limited network. */
    private void offerDownload(AsrModel model) {
        final Advice advice = TranscriptionManager.advise();
        if (advice.metered) {
            new ArsoundDialog(ui.activity)
                    .setTitle(text("Скачать по мобильной сети?", "Download over mobile data?"))
                    .setMessage(text("Сейчас используется мобильная или ограниченная сеть. Будет скачано "
                            + Sizes.format(advice.downloadBytes) + ".", "The network in use is mobile or limited. "
                            + Sizes.format(advice.downloadBytes) + " will be downloaded."))
                    .setPositiveButton(text("Скачать", "Download"), (d, w) -> startDownload(model))
                    .setNegativeButton(text("Подождать Wi-Fi", "Wait for Wi-Fi"), null)
                    .show();
        } else {
            startDownload(model);
        }
    }

    private void startDownload(AsrModel model) {
        modelStatus.setText(model.label() + " · " + text("загрузка… нажмите, чтобы остановить", "downloading… tap to stop"));
        TranscriptionManager.startInstall(model, installListener);
    }

    private final TranscriptionManager.InstallListener installListener = new TranscriptionManager.InstallListener() {
        @Override
        public void onProgress(int percent) {
            if (gone()) return;
            modelStatus.setText(TranscriptionManager.chosenModel().label() + " · "
                    + text("загрузка " + percent + "%. Нажмите, чтобы остановить.",
                    "downloading " + percent + "%. Tap to stop."));
        }

        @Override
        public void onDone() {
            if (!gone()) refreshModelStatus();
        }

        @Override
        public void onFailed(Failure failure) {
            if (gone()) return;
            Utils.showToastLong(failure.message());
            refreshModelStatus();
        }

        @Override
        public void onCancelled() {
            if (!gone()) refreshModelStatus();
        }
    };

    /**
     * The screen is closed or its rows are built again: a download that is going on must not keep this listener, and
     * with it the whole screen, alive. The download goes on without telling anyone.
     */
    void detach() {
        TranscriptionManager.detachInstall(installListener);
    }

    /** The screen was left; its views are of no use. */
    private boolean gone() {
        return ui.activity.isFinishing() || ui.activity.isDestroyed();
    }

    // endregion

    // region Choices

    /** What the model is good and bad at, under its name. */
    private static String modelHint(AsrModel model) {
        if (model == AsrModel.GIGAAM) {
            return text("Рекомендуется для русских песен: точнее и быстрее Whisper, около 0,7 ГБ памяти. Слышит только "
                            + "русский: английская песня выйдет русскими буквами. Нажмите, чтобы сменить, скачать или удалить.",
                    "Recommended for Russian songs: more accurate and faster than Whisper, about 0.7 GB of memory. Hears "
                            + "only Russian: an English song comes out in Russian letters. Tap to change, download or delete it.");
        }
        return model == AsrModel.SMALL
                ? text("Для песен на других языках. На русских песнях в нашей проверке верна примерно треть слов "
                        + "(половина, если отделять голос). Нужно около 1,5 ГБ свободной памяти и больше времени. "
                        + "Нажмите, чтобы сменить, скачать или удалить.",
                "For songs in other languages. On Russian songs in our test about a third of the words came out right "
                        + "(half with the voice taken out). Needs about 1.5 GB of free memory and more time. Tap to change, "
                        + "download or delete it.")
                : text("Вдвое быстрее и легче, но на русских песнях ошибок больше, чем слов: годится для английской речи "
                        + "и слабых телефонов. Нажмите, чтобы сменить, скачать или удалить.",
                "Twice as fast and light, but on Russian songs it makes more mistakes than there are words: fit for "
                        + "English speech and weak phones. Tap to change, download or delete it.");
    }

    private void addLanguageRow(LinearLayout list) {
        final String[] codes = {"auto", "ru", "en"};
        final CharSequence[] labels = {text("Определять самой (русский или английский)", "Detect automatically (Russian or English)"),
                text("Русский", "Russian"), "English"};
        final TextView[] description = new TextView[1];
        ActionRow row = ui.createActionRow(text("Язык песен", "Language of the songs"), "", v ->
                new ArsoundDialog(ui.activity)
                        .setTitle(text("Язык песен", "Language of the songs"))
                        .setSingleChoiceItems(labels, indexOf(codes, TranscriptionManager.language()), (dialog, which) -> {
                            TranscriptionManager.setLanguage(codes[which]);
                            description[0].setText(languageText(labels, codes));
                            dialog.dismiss();
                        })
                        .show());
        row.icon("language");
        description[0] = row.description();
        description[0].setText(languageText(labels, codes));
        list.addView(row);
    }

    private static String languageText(CharSequence[] labels, String[] codes) {
        return labels[indexOf(codes, TranscriptionManager.language())] + "\n"
                + text("Если все песни на одном языке, укажите его: так слова слышатся точнее. «Определять самой» верит "
                        + "только русскому, английскому и языку телефона; по умолчанию в русском приложении стоит русский.",
                "If all your songs are in one language, name it: the words are heard more accurately. \"Detect "
                        + "automatically\" believes only Russian, English and the language of the phone; in a Russian app "
                        + "Russian is the default.");
    }

    private static int indexOf(String[] codes, String code) {
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(code)) return i;
        }
        return 0;
    }

    // endregion

    // region Recognised texts

    private void addClearRow(LinearLayout list) {
        ActionRow row = ui.createActionRow(text("Очистить распознанные тексты", "Clear recognised lyrics"), "", v -> {
            clearStatus.setText(text("Очищаю…", "Clearing…"));
            ui.fillInBackground(() -> {
                TranscriptionManager.clearTranscripts();
                Utils.runOnMainThread(() -> {
                    LyricsRepository.forgetRecognised();
                    refreshClearStatus();
                });
            });
        });
        row.icon("trash");
        clearStatus = row.description();
        clearStatus.setText(text("Считаю…", "Counting…"));
        list.addView(row);
        refreshClearStatus();
    }

    private void refreshClearStatus() {
        ui.fillInBackground(() -> {
            final int count = TranscriptionManager.transcriptCount();
            final long bytes = TranscriptionManager.transcriptBytes();
            Utils.runOnMainThread(() -> clearStatus.setText(count == 0
                    ? text("Распознанных текстов нет.", "No recognised lyrics.")
                    : text("Распознано песен: " + count + ", занимают " + Sizes.format(bytes)
                            + ". Тексты хранятся в памяти приложения, не в кэше.",
                    "Songs recognised: " + count + ", taking " + Sizes.format(bytes)
                            + ". They are kept in the app's own storage, not in the cache.")));
        });
    }

    // endregion
}
