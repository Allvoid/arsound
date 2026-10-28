package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.soundcloud.shared.Lang;

/**
 * Decides whether the phone is in a state to recognise a song, from numbers {@link DeviceState} read from the system.
 * No Android classes here, so the rules are tested on the JVM.
 * <p>
 * Work that starts is allowed to heat the phone and drain the battery, but it must not start when the battery is
 * nearly empty or there is no memory for the model (the system would kill the app, and the music with it).
 */
final class Guards {
    /** A battery below this, not charging, makes the user confirm. */
    static final int LOW_BATTERY_PERCENT = 30;

    /** A thing the user should know before the work starts. */
    enum Concern {
        /** Not enough free memory for the chosen model. The work must not start. */
        NOT_ENOUGH_MEMORY(true, "Не хватает свободной памяти для этой модели. Закройте другие приложения и попробуйте снова.",
                "There is not enough free memory for this model. Close other apps and try again."),
        /** The battery is low and the phone is not charging. */
        LOW_BATTERY(false, "Заряд батареи низкий, а телефон не на зарядке. Распознавание заметно её расходует.",
                "The battery is low and the phone is not charging. Recognition drains it noticeably."),
        /** The phone is in power saving mode, which slows the processor. */
        POWER_SAVER(false, "Включён режим экономии заряда: распознавание будет медленным.",
                "Power saving is on: recognition will be slow.");

        /** Whether the work cannot go on whatever the user says. */
        final boolean blocking;
        private final String russian;
        private final String english;

        Concern(boolean blocking, String russian, String english) {
            this.blocking = blocking;
            this.russian = russian;
            this.english = english;
        }

        String message() {
            return Lang.text(russian, english);
        }
    }

    /** What the phone is doing now. */
    static final class Device {
        final int batteryPercent;
        final boolean charging;
        final boolean powerSaver;
        final long availableMemoryBytes;

        Device(int batteryPercent, boolean charging, boolean powerSaver, long availableMemoryBytes) {
            this.batteryPercent = batteryPercent;
            this.charging = charging;
            this.powerSaver = powerSaver;
            this.availableMemoryBytes = availableMemoryBytes;
        }
    }

    private Guards() {
    }

    /** The things to tell the user before starting; empty when all is well. The blocking one comes first. */
    static List<Concern> concerns(Device device, AsrModel model) {
        List<Concern> concerns = new ArrayList<>();
        if (device.availableMemoryBytes < model.minAvailableBytes) concerns.add(Concern.NOT_ENOUGH_MEMORY);
        if (!device.charging && device.batteryPercent >= 0 && device.batteryPercent < LOW_BATTERY_PERCENT) {
            concerns.add(Concern.LOW_BATTERY);
        }
        if (device.powerSaver) concerns.add(Concern.POWER_SAVER);
        return concerns;
    }

    /** Whether the fast model would fit where the chosen one does not. */
    static boolean lighterModelFits(Device device, AsrModel model) {
        return model != AsrModel.BASE && device.availableMemoryBytes >= AsrModel.BASE.minAvailableBytes;
    }

    /**
     * How long to wait before the next window, for a thermal status of Android's {@code PowerManager} (0 none,
     * 1 light, 2 moderate, 3 severe, 4 and up critical): nothing while cool, a short rest while warm, a long one
     * when hot, so that the phone is given the time to cool down.
     */
    static long thermalPauseMs(int status) {
        if (status >= 3) return 30_000;
        if (status == 2) return 4_000;
        return 0;
    }
}
