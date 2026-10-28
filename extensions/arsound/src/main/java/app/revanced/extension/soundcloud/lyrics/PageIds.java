package app.revanced.extension.soundcloud.lyrics;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

/** Ids of the views of a player page and of the lyrics button, found once. */
final class PageIds {
    static boolean loaded;
    static int page, artwork, overlay, header, holder, corner, waveform, title, toggle, iconOff, iconOn;

    private PageIds() {
    }

    static void load() {
        if (loaded) return;
        page = id("player_track_page");
        artwork = id("track_page_artwork");
        overlay = id("artwork_overlay_dark");
        header = id("track_page_header");
        holder = id("timestamp_holder");
        corner = id("track_page_top_left_corner");
        waveform = id("waveform_container");
        title = id("track_page_title");
        toggle = id("arsound_lyrics_toggle");
        iconOff = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_lyrics");
        iconOn = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "arsound_lyrics_on");
        loaded = true;
    }

    private static int id(String name) {
        return Utils.getResourceIdentifier(ResourceType.ID, name);
    }
}
