package app.revanced.extension.soundcloud.lyrics.transcribe;

import app.revanced.extension.soundcloud.shared.Lang;

/**
 * Why a recognition did not finish, with the sentence the page shows for it. Thrown by {@link TranscriptionJob} inside
 * {@link JobFailed} and handed to the listener of {@link TranscriptionManager}.
 */
public enum Failure {
    /** The track is a preview, needs a subscription, is protected or cannot be found. */
    UNAVAILABLE("Для этого трека распознавание недоступно", "Recognition is not available for this track", false),
    NO_SPACE("Не хватает места на телефоне для модели. Освободите место и повторите.",
            "There is not enough space on the phone for the model. Free some and try again.", true),
    NETWORK("Не удалось скачать файлы. Проверьте подключение и повторите.",
            "Could not download the files. Check the connection and try again.", true),
    BAD_DOWNLOAD("Скачанный файл повреждён. Повторите загрузку.", "The downloaded file is damaged. Try again.", true),
    MODEL("Не удалось запустить нейросеть на этом телефоне.", "The neural network could not be started on this phone.", true),
    AUDIO("Не удалось разобрать звук этого трека.", "Could not decode the sound of this track.", false),
    MEMORY("Не хватило памяти. Закройте другие приложения или выберите быструю модель (она ошибается чаще).",
            "Out of memory. Close other apps or choose the fast model (it makes more mistakes).", true);

    private final String russian;
    private final String english;
    /** Whether trying again later may help. */
    final boolean retryable;

    Failure(String russian, String english, boolean retryable) {
        this.russian = russian;
        this.english = english;
        this.retryable = retryable;
    }

    public String message() {
        return Lang.text(russian, english);
    }

    public boolean isRetryable() {
        return retryable;
    }
}
