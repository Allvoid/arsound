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
import app.revanced.extension.soundcloud.lyrics.transcribe.HeavyNotice;
import app.revanced.extension.soundcloud.lyrics.transcribe.Sizes;
import app.revanced.extension.soundcloud.lyrics.transcribe.TranscriptionManager;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/**
 * The rows of "Recognition of lyrics by a neural network": the switch, the models (download ahead, delete, size),
 * the voice separation, and clearing the recognised texts. There is no model or language to choose for all songs: the
 * lyrics page asks the language of each song, and that decides the model. Nothing is added on a phone that cannot run the
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
                                + "намного меньше (в нашей проверке с GigaAM — четыре слова из десяти вместо шести). На "
                                + "Snapdragon 8 Gen 2, 8 Gen 3 и 8 Elite это делает нейропроцессор за секунды, на других "
                                + "телефонах — ядра, и трек в три минуты обрабатывается несколько минут. Ещё около 75 МБ "
                                + "загрузки (на таком Snapdragon — около 120 МБ).",
                        "Before the recognition a separate network removes the music and keeps the voice: there are far "
                                + "fewer mistakes (in our test with GigaAM four wrong words in ten instead of six). On a "
                                + "Snapdragon 8 Gen 2, 8 Gen 3 or 8 Elite the neural processor does it in seconds, on other "
                                + "phones the cores do, and a three-minute track takes several minutes. About 75 MB more to "
                                + "download (about 120 MB on such a Snapdragon)."),
                BoolSettings.LYRICS_AI_SEPARATE,
                (button, checked) -> refreshModelStatus()).icon("ai"));
        addClearRow(list);
    }

    // region The models

    private void addModelRow(LinearLayout list) {
        ActionRow row = ui.createActionRow(text("Модели нейросети", "Neural network models"), "", v -> onModelRowTapped());
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
                if (TranscriptionManager.attachInstall(installListener)) showDownloading(null);
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                TranscriptionManager.detachInstall(installListener);
            }
        });
    }

    /** Which models are on the phone and how much space they take, then what the row is for. */
    private void refreshModelStatus() {
        final AsrModel[] models = AsrModel.values();
        final boolean[] installed = new boolean[models.length];
        for (int i = 0; i < models.length; i++) installed[i] = TranscriptionManager.isInstalled(models[i]);
        modelStatus.setText(modelSummary(models, installed, null));
        ui.fillInBackground(() -> {
            final long[] bytes = new long[models.length];
            for (int i = 0; i < models.length; i++) {
                bytes[i] = installed[i] ? TranscriptionManager.modelBytes(models[i]) : TranscriptionManager.missingBytes(models[i]);
            }
            Utils.runOnMainThread(() -> {
                if (!TranscriptionManager.isInstalling()) modelStatus.setText(modelSummary(models, installed, bytes));
            });
        });
    }

    /** A line per model ("ready, 290 MB" or "not downloaded"), then how the model of a song is chosen. */
    private static String modelSummary(AsrModel[] models, boolean[] installed, long[] bytes) {
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < models.length; i++) {
            summary.append(models[i].label()).append(" · ");
            if (installed[i]) {
                summary.append(bytes == null ? text("скачана", "downloaded")
                        : text("скачана, " + Sizes.format(bytes[i]), "downloaded, " + Sizes.format(bytes[i])));
            } else {
                summary.append(text("не скачана", "not downloaded"));
            }
            summary.append('\n');
        }
        return summary.append(text("Перед распознаванием спрашивается язык песни: русский слушает GigaAM, другие — "
                        + "Whisper. Недостающая модель скачивается тогда же. Нажмите, чтобы скачать заранее или удалить.",
                "The language of the song is asked before the recognition: Russian is heard by GigaAM, other languages "
                        + "by Whisper. A missing model is downloaded then. Tap to download one ahead or delete it."))
                .toString();
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
        new ArsoundDialog(ui.activity)
                .setTitle(text("Модели нейросети", "Neural network models"))
                .setItems(labels, (d, which) -> showModel(models[which]))
                .setNegativeButton(text("Закрыть", "Close"), null)
                .show();
    }

    /** One model: what it is good at, and its download or its deletion. */
    private void showModel(AsrModel model) {
        boolean installed = TranscriptionManager.isInstalled(model);
        ArsoundDialog dialog = new ArsoundDialog(ui.activity)
                .setTitle(model.label())
                .setMessage(modelHint(model))
                .setNegativeButton(text("Закрыть", "Close"), null);
        if (installed) {
            dialog.setNeutralButton(text("Удалить", "Delete"), (d, w) -> confirmDelete(model));
        } else {
            dialog.setPositiveButton(text("Скачать", "Download"),
                    (d, w) -> HeavyNotice.showThen(ui.activity, TranscriptionManager.missingBytes(model),
                            () -> offerDownload(model)));
        }
        dialog.show();
    }

    private void confirmDelete(AsrModel model) {
        new ArsoundDialog(ui.activity)
                .setTitle(text("Удалить модель?", "Delete the model?"))
                .setMessage(text("Файлы модели «" + model.label() + "» будут удалены с телефона. Уже распознанные тексты "
                                + "останутся. Понадобится снова — скачается перед распознаванием.",
                        "The files of the model \"" + model.label() + "\" will be removed from the phone. Texts already "
                                + "recognised stay. When it is needed again, it is downloaded before the recognition."))
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
        final Advice advice = TranscriptionManager.advise(model);
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
        TranscriptionManager.startInstall(model, installListener);
        showDownloading(null);
    }

    /** The row while a model downloads: its name, how far it is, how to stop it. */
    private void showDownloading(Integer percent) {
        AsrModel model = TranscriptionManager.installingModel();
        String name = model == null ? "" : model.label() + " · ";
        modelStatus.setText(name + (percent == null
                ? text("загрузка… Нажмите, чтобы остановить.", "downloading… Tap to stop.")
                : text("загрузка " + percent + "%. Нажмите, чтобы остановить.", "downloading " + percent + "%. Tap to stop.")));
    }

    private final TranscriptionManager.InstallListener installListener = new TranscriptionManager.InstallListener() {
        @Override
        public void onProgress(int percent) {
            if (!gone()) showDownloading(percent);
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

    /** What the model is good and bad at. */
    private static String modelHint(AsrModel model) {
        if (model == AsrModel.GIGAAM) {
            return text("Слушает песни, для которых вы выбрали русский язык: точнее и быстрее Whisper, около 0,7 ГБ "
                            + "памяти. Другие языки не слышит.",
                    "Listens to the songs you said are in Russian: more accurate and faster than Whisper, about 0.7 GB of "
                            + "memory. Hears no other language.");
        }
        return model == AsrModel.SMALL
                ? text("Слушает песни на английском и других языках. Нужно около 1,5 ГБ свободной памяти и больше времени, "
                        + "чем русской модели.",
                "Listens to the songs in English and other languages. Needs about 1.5 GB of free memory and more time "
                        + "than the Russian model.")
                : text("Вдвое быстрее и легче Whisper small, но ошибается чаще. Предлагается, когда для обычной модели "
                        + "не хватает памяти.",
                "Twice as fast and light as Whisper small, but makes more mistakes. Offered when there is not enough "
                        + "memory for the usual model.");
    }

    // endregion

    // region Recognised texts

    private void addClearRow(LinearLayout list) {
        ActionRow row = ui.createActionRow(text("Очистить распознанные тексты", "Clear recognised lyrics"), "", v -> {
            clearStatus.setText(text("Очищаю…", "Clearing…"));
            ui.fillInBackground(() -> {
                boolean cleared = TranscriptionManager.clearTranscripts();
                Utils.runOnMainThread(() -> {
                    if (!cleared) {
                        clearStatus.setText(text("Сначала остановите распознавание песни", "Stop the recognition of the song first"));
                        return;
                    }
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
