package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Where "My Wave" gets its offers from; the user ticks the ones they like. SoundCloud's own related tracks need nothing;
 * Last.fm and Deezer are asked by name (no account, no key of the user's). With every source off the wave has nothing
 * to play, so the settings keep at least one on.
 */
public enum WaveSource {
    SOUNDCLOUD("soundcloud", "SoundCloud: похожие треки", "SoundCloud: related tracks",
            "Похожие треки, которые SoundCloud сам подбирает к каждому треку.",
            "The related tracks SoundCloud picks for every track."),
    LASTFM("lastfm", "Last.fm", "Last.fm",
            "Что слушают вместе с вашими треками (нужен VPN из России). Названия ищутся в SoundCloud.",
            "What people listen to along with your tracks (needs a VPN in Russia). Names are looked up on SoundCloud."),
    DEEZER("deezer", "Deezer", "Deezer",
            "Радио исполнителя из бесплатного API Deezer, без ключа и аккаунта. Названия ищутся в SoundCloud.",
            "The artist radio of Deezer's free API, no key or account. Names are looked up on SoundCloud.");

    private static final String PREFIX = "wave_src_";

    public final String key;
    private final String russian, english, russianNote, englishNote;

    WaveSource(String key, String russian, String english, String russianNote, String englishNote) {
        this.key = key;
        this.russian = russian;
        this.english = english;
        this.russianNote = russianNote;
        this.englishNote = englishNote;
    }

    public String label() {
        return text(russian, english);
    }

    public String note() {
        return text(russianNote, englishNote);
    }

    public boolean isOn() {
        return Settings.getBoolean(PREFIX + key, true);
    }

    public void set(boolean on) {
        Settings.putBoolean(PREFIX + key, on);
    }

    /** The sources that are ticked; SoundCloud's own if none is, so a wave always has somewhere to look. */
    public static List<WaveSource> chosen() {
        List<WaveSource> on = new ArrayList<>();
        for (WaveSource source : values()) if (source.isOn()) on.add(source);
        if (on.isEmpty()) on.add(SOUNDCLOUD);
        return on;
    }

    /** How many sources are ticked. */
    public static int onCount() {
        int count = 0;
        for (WaveSource source : values()) if (source.isOn()) count++;
        return count;
    }
}
