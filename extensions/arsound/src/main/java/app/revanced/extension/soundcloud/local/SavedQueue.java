package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * The play queue saved as a playlist of this phone ({@link LocalPlaylists#SAVED_QUEUE}, "Saved queue"): a long swipe of
 * a queue row puts the whole queue in it, in its order, SoundCloud tracks and imported files alike. One such playlist,
 * a new save replaces what it held, so saved queues do not pile up in the library. Nothing reaches SoundCloud.
 */
public final class SavedQueue {
    private SavedQueue() {
    }

    static String title() {
        return text("Сохранённая очередь", "Saved queue");
    }

    /**
     * Saves the tracks in the background and says how it went.
     *
     * @param urns The {@code Urn}s of the queue, in its order; repeats are kept once.
     */
    public static void save(Context context, List<Object> urns) {
        Context app = context.getApplicationContext();
        Set<String> entries = new LinkedHashSet<>();
        for (Object urn : urns) {
            String entry = LocalAdditions.entryOf(urn);
            if (entry != null && (entry.startsWith(TrackEntry.PREFIX) || entry.startsWith("file:"))) entries.add(entry);
        }
        if (entries.isEmpty()) {
            Toast.makeText(app, text("В очереди нет треков", "The queue has no tracks"), Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> list = new ArrayList<>(entries);
        Utils.runOnBackgroundThread(() -> {
            boolean made;
            try {
                made = LocalPlaylists.ensure(LocalPlaylists.SAVED_QUEUE, title());
            } catch (Exception ex) {
                Logger.printException(() -> "Saved queue: the playlist could not be made", ex);
                made = false;
            }
            if (made) {
                AdditionsStore.setEntries(LocalPlaylists.SAVED_QUEUE, list);
                TrackTitles.rememberAll(list);
                Logger.printInfo(() -> "Saved queue: " + list.size() + " tracks");
            }
            boolean ok = made;
            Utils.runOnMainThread(() -> {
                if (ok) PlaylistChanges.playlistChanged(LocalPlaylists.SAVED_QUEUE);
                Toast.makeText(app, ok
                        ? text("Очередь сохранена в «" + title() + "»: треков — " + list.size(),
                        "The queue is saved to \"" + title() + "\": " + list.size() + " tracks")
                        : text("Не удалось сохранить очередь: нужен интернет при первом сохранении",
                        "Could not save the queue: the first save needs the internet"), Toast.LENGTH_LONG).show();
            });
        });
    }
}
