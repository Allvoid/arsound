package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.view.View;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

/**
 * The header of the playlist and album screen.
 * <p>
 * SoundCloud writes "Playlist · 95 Tracks · 5:12:40", counting only the tracks on its server. The
 * number of tracks this phone can play without the network follows it with the download icon:
 * tracks downloaded by Arsound and imported files of the playlist.
 * <p>
 * The numbers come from {@link PlaylistTracks}.
 */
public final class PlaylistHeader {
    /** The header shown last, drawn again when the track list of its playlist arrives after it. */
    private static WeakReference<View> shownLabel = new WeakReference<>(null);
    private static Object shownState;
    private static String shownPlaylist;

    private PlaylistHeader() {
    }

    /**
     * Called with the track list of a playlist screen, off the main thread.
     *
     * @param urns The {@code Urn}s the screen shows.
     */
    static void onScreenTracks(String playlistUrn, List<?> urns) {
        if (!PlaylistTracks.record(playlistUrn, urns)) return;
        Utils.runOnMainThread(() -> {
            if (!playlistUrn.equals(shownPlaylist)) return;
            View label = shownLabel.get();
            if (label != null && label.isAttachedToWindow()) draw(label, shownState, playlistUrn);
        });
    }

    /**
     * Injection point: right after SoundCloud has drawn the meta line of the playlist header.
     *
     * @param metaLabel The {@code MetaLabel} view.
     * @param metadata  {@code PlaylistDetailsMetadata}, which holds the playlist.
     * @param viewState The {@code MetaLabel.ViewState} SoundCloud drew.
     */
    public static void addDownloadedCount(Object metaLabel, Object metadata, Object viewState) {
        try {
            String playlistUrn = playlistUrnOf(metadata);
            if (playlistUrn == null) return;
            View label = (View) metaLabel;
            shownLabel = new WeakReference<>(label);
            shownState = viewState;
            shownPlaylist = playlistUrn;
            Integer count = PlaylistTracks.downloadedCount(playlistUrn);
            if (count != null && count > 0) draw(label, viewState, playlistUrn);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the downloaded count", ex);
        }
    }

    /**
     * The open header shows a new cover right away: SoundCloud draws it again only when the
     * playlist itself changes.
     *
     * @param coverUrl The address of the cover, or null to clear the picture.
     */
    public static void showCover(String playlistUrn, String coverUrl) {
        try {
            View label = shownLabel.get();
            if (label == null || !playlistUrn.equals(shownPlaylist) || !(label.getParent() instanceof View)) return;
            View container = ((View) label.getParent()).findViewById(
                    Utils.getResourceIdentifier(ResourceType.ID, "playlist_details_header_artwork_container"));
            android.widget.ImageView image = firstImage(container);
            if (image == null) return;
            if (coverUrl == null) {
                image.setImageDrawable(null);
            } else {
                image.setImageURI(android.net.Uri.parse(coverUrl));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show the new cover", ex);
        }
    }

    private static android.widget.ImageView firstImage(View view) {
        if (view instanceof android.widget.ImageView) return (android.widget.ImageView) view;
        if (!(view instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup group = (android.view.ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            android.widget.ImageView image = firstImage(group.getChildAt(i));
            if (image != null) return image;
        }
        return null;
    }

    private static String playlistUrnOf(Object metadata) throws Exception {
        for (java.lang.reflect.Field field : metadata.getClass().getDeclaredFields()) {
            if (!field.getType().getName().endsWith(".PlaylistItem")) continue;
            field.setAccessible(true);
            Object item = field.get(metadata);
            return item == null ? null : String.valueOf(item.getClass().getMethod("getUrn").invoke(item));
        }
        return null;
    }

    /** Draws SoundCloud's items of the meta line again, with the count after the number of tracks. */
    private static void draw(View label, Object viewState, String playlistUrn) {
        try {
            if (viewState == null) return;
            Integer count = PlaylistTracks.downloadedCount(playlistUrn);
            Context context = label.getContext();
            ClassLoader loader = label.getClass().getClassLoader();
            Class<?> utils = Class.forName("com.soundcloud.android.ui.utils.MetaLabelUtilsKt", false, loader);
            Object builder = utils.getMethod("b", Context.class).invoke(null, context);
            Method items = null;
            for (Method method : utils.getMethods()) {
                if (method.getName().equals("a") && method.getParameterTypes().length == 3) items = method;
            }
            if (items == null) return;
            List<Object> list = new ArrayList<>((List<?>) items.invoke(null, builder, viewState, context));
            // Without files the line is drawn as SoundCloud has it.
            if (count != null && count > 0) PlaylistTracks.addCount(list, count, loader);
            label.getClass().getMethod("n", List.class).invoke(label, list);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not draw the downloaded count", ex);
        }
    }
}
