package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

/**
 * The words the user reads while exporting and importing settings: the question before an import, what went wrong, what
 * was done. Kept apart from the screen code (no Android here) so the texts, and the numbers in them, can be tested.
 */
final class TransferTexts {
    private TransferTexts() {
    }

    /** "1 значение", "3 значения", "5 значений"; "1 value", "5 values". */
    static String values(long count) {
        return text(count + " " + Numerals.russianForm(count, "значение", "значения", "значений"),
                count + (count == 1 ? " value" : " values"));
    }

    /** What the question before an import says: how much, from which version and day, and what it replaces. */
    static String confirmation(TransferBackup backup) {
        StringBuilder origin = new StringBuilder();
        if (!backup.appVersion.isEmpty()) origin.append("Arsound ").append(backup.appVersion);
        if (!backup.date.isEmpty()) origin.append(origin.length() > 0 ? ", " : "").append(backup.date);
        String from = origin.length() > 0 ? " (" + origin + ")" : "";

        String message = text(
                "Из файла" + from + " будет взято: " + values(backup.valueCount()) + ". Они заменят на этом телефоне текущие "
                        + "настройки, список «Не нравится», память «Моей волны» и свой порядок треков в плейлистах, после этого "
                        + "приложение перезапустится.\n\nВход в аккаунты и скачанные треки не меняются. "
                        + "Импортируйте только тот файл, который сохранили сами.",
                "From the file" + from + " this takes " + values(backup.valueCount()) + ". They replace on this phone the "
                        + "current settings, the \"Not for me\" list, what My Wave learned and your own track orders in "
                        + "playlists, then the app restarts.\n\nSign-ins and downloaded tracks do not change. "
                        + "Import only a file you saved yourself.");
        int left = backup.ignored + backup.wrongType;
        if (left > 0) {
            message += text("\n\nПропущено: " + values(left) + " — они не переносятся или не подходят этой версии.",
                    "\n\nLeft out: " + values(left) + " — not carried over, or not fitting this version.");
        }
        return message;
    }

    /** Why a file was refused. */
    static String failure(TransferException.Reason reason) {
        switch (reason) {
            case NOT_SETTINGS:
                return text("Это не файл настроек Arsound. Выберите файл, сохранённый через «Экспорт настроек».",
                        "This is not an Arsound settings file. Pick a file saved with \"Export settings\".");
            case NEWER_FORMAT:
                return text("Файл сделан более новой версией Arsound. Обновите приложение и попробуйте снова.",
                        "This file was made by a newer Arsound. Update the app and try again.");
            case NOTHING_TO_IMPORT:
                return text("В файле нет настроек, которые можно применить.",
                        "The file has no settings that can be applied.");
            case TOO_BIG:
                return text("Файл слишком большой для файла настроек.",
                        "The file is too big to be a settings file.");
            case NOT_JSON:
            default:
                return text("Не удалось прочитать файл: он повреждён или это не файл настроек.",
                        "Could not read the file: it is damaged or not a settings file.");
        }
    }

    /** After a successful export. */
    static String exported(TransferExport export) {
        return text("Настройки сохранены в файл: " + values(export.valueCount) + ".",
                "Settings saved to the file: " + values(export.valueCount) + ".");
    }

    /** After a successful import, just before the restart. */
    static String imported(TransferChanges changes) {
        String done = text("Настройки применены: " + values(changes.writeCount()) + ". Перезапускаю приложение…",
                "Settings applied: " + values(changes.writeCount()) + ". Restarting the app…");
        if (changes.typeConflicts > 0) {
            done += text(" Пропущено: " + values(changes.typeConflicts) + ", они не подходят этой версии.",
                    " Left out: " + values(changes.typeConflicts) + ", not fitting this version.");
        }
        return done;
    }
}
