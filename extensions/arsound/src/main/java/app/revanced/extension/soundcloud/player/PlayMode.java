package app.revanced.extension.soundcloud.player;

import app.revanced.extension.soundcloud.shared.Lang;

/**
 * The modes the play mode button cycles through: in order, repeat the queue, repeat the track, shuffled. A mode knows
 * how to read itself off a queue, how to put the queue in it and what the button says for it.
 */
enum PlayMode {
    ORDER("arsound_play_mode_order", "По порядку", "In order") {
        @Override
        void applyTo(Object queueManager) throws Exception {
            Object queue = PlayQueueApi.queue(queueManager);
            // Leaving the shuffle goes back to the queue from before it, with what was added since.
            if (PlayQueueApi.isShuffled(queue)) {
                PlayQueueApi.keepAddedItems(queue);
                PlayQueueApi.unshuffle(queueManager);
            }
            PlayQueueApi.setRepeat(queueManager, PlayQueueApi.REPEAT_NONE);
        }
    },
    REPEAT_ALL("arsound_play_mode_repeat_all", "Повтор очереди", "Repeat queue") {
        @Override
        void applyTo(Object queueManager) throws Exception {
            PlayQueueApi.setRepeat(queueManager, PlayQueueApi.REPEAT_ALL);
        }
    },
    REPEAT_ONE("arsound_play_mode_repeat_one", "Повтор трека", "Repeat track") {
        @Override
        void applyTo(Object queueManager) throws Exception {
            PlayQueueApi.setRepeat(queueManager, PlayQueueApi.REPEAT_ONE);
        }
    },
    SHUFFLE("arsound_play_mode_shuffle", "Вперемешку", "Shuffle") {
        @Override
        void applyTo(Object queueManager) throws Exception {
            PlayQueueApi.setRepeat(queueManager, PlayQueueApi.REPEAT_NONE);
            // As SoundCloud's own shuffle button: the tracks after the current one are shuffled.
            Object queue = PlayQueueApi.queue(queueManager);
            int position = PlayQueueApi.position(queueManager);
            int size = PlayQueueApi.size(queue);
            Object shuffled = PlayQueueApi.shuffledFrom(queue, position + 1 >= size ? 0 : position + 1);
            PlayQueueApi.setQueue(queueManager, shuffled);
        }
    };

    /** The name of the drawable of the button for this mode. */
    final String icon;
    private final String russian;
    private final String english;

    PlayMode(String icon, String russian, String english) {
        this.icon = icon;
        this.russian = russian;
        this.english = english;
    }

    /** What the button says for the mode, in the language of the app. */
    String title() {
        return Lang.text(russian, english);
    }

    PlayMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** Puts the play queue of the manager in this mode. Main thread only. */
    abstract void applyTo(Object queueManager) throws Exception;

    /** The mode of a queue that has tracks in it: shuffled wins, else what it repeats. */
    static PlayMode of(boolean shuffled, String repeatName) {
        if (shuffled) return SHUFFLE;
        if (PlayQueueApi.REPEAT_ALL.equals(repeatName)) return REPEAT_ALL;
        if (PlayQueueApi.REPEAT_ONE.equals(repeatName)) return REPEAT_ONE;
        return ORDER;
    }

    /** The mode of the queue the manager plays, or null if there is no queue or it is empty. */
    static PlayMode of(Object queueManager) throws Exception {
        Object queue = PlayQueueApi.queue(queueManager);
        if (queue == null || PlayQueueApi.size(queue) == 0) return null;
        boolean shuffled = PlayQueueApi.isShuffled(queue);
        return of(shuffled, shuffled ? null : PlayQueueApi.repeatName(queue));
    }
}
