package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.media.AudioManager;
import android.media.audiofx.Equalizer;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * An equalizer on the player's sound: Android's own effect, attached to every audio session
 * the player creates. The chosen preset or band levels are applied at once to the sound that plays.
 */
@SuppressWarnings("unused")
public final class AudioEqualizer {
    public static final String ENABLED = "equalizer_enabled";
    /** The chosen preset of the device, or {@link #CUSTOM} for the user's own band levels. */
    public static final String PRESET = "equalizer_preset";
    /** Band levels in millibels, comma separated, lowest band first. */
    public static final String LEVELS = "equalizer_levels";
    public static final long CUSTOM = -1;

    private static final List<Equalizer> active = new ArrayList<>();

    private AudioEqualizer() {
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(ENABLED, false);
    }

    /**
     * Injection point: the player got a new audio session.
     *
     * @param sessionId The session id, 0 if none was given.
     */
    public static void onAudioSession(int sessionId) {
        if (sessionId <= 0) return;
        try {
            Equalizer equalizer = new Equalizer(0, sessionId);
            synchronized (active) {
                active.add(equalizer);
            }
            apply(equalizer);
            Logger.printInfo(() -> "Equalizer attached to audio session " + sessionId + ", enabled " + isEnabled());
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not attach the equalizer", ex);
        }
    }

    /** Applies the settings to the sound that plays now. */
    public static void applyAll() {
        synchronized (active) {
            for (Equalizer equalizer : active) apply(equalizer);
        }
    }

    private static void apply(Equalizer equalizer) {
        try {
            boolean enabled = isEnabled();
            if (enabled) {
                long preset = Settings.getLong(PRESET, CUSTOM);
                if (preset >= 0 && preset < equalizer.getNumberOfPresets()) {
                    equalizer.usePreset((short) preset);
                } else {
                    short[] levels = levels(equalizer.getNumberOfBands());
                    for (short band = 0; band < levels.length; band++) equalizer.setBandLevel(band, levels[band]);
                }
            }
            equalizer.setEnabled(enabled);
        } catch (Throwable ex) {
            // The session ended: the effect is dropped.
            synchronized (active) {
                active.remove(equalizer);
            }
            equalizer.release();
        }
    }

    /** The user's band levels; missing bands are flat. */
    public static short[] levels(int bands) {
        short[] levels = new short[bands];
        String saved = Settings.getString(LEVELS, "");
        String[] parts = saved.isEmpty() ? new String[0] : saved.split(",");
        for (int i = 0; i < bands && i < parts.length; i++) {
            try {
                levels[i] = Short.parseShort(parts[i]);
            } catch (NumberFormatException ignored) {
            }
        }
        return levels;
    }

    public static void setLevel(int band, short level, int bands) {
        short[] levels = levels(bands);
        levels[band] = level;
        setLevels(levels, "");
    }

    /**
     * Saves and applies band levels.
     *
     * @param choice What they came from: "p:&lt;id&gt;" a built-in preset, "u:&lt;name&gt;" the user's preset,
     *               "" the user's own band levels.
     */
    public static void setLevels(short[] levels, String choice) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < levels.length; i++) value.append(i == 0 ? "" : ",").append(levels[i]);
        Settings.putString(LEVELS, value.toString());
        Settings.putLong(PRESET, CUSTOM);
        Settings.putString(CHOICE, choice);
        applyAll();
    }

    // region Presets

    /** The preset the band levels came from, see {@link #setLevels}. */
    public static final String CHOICE = "equalizer_choice";
    /** The user's own presets: a JSON array of {"name", "levels": [millibels per band of this phone]}. */
    public static final String USER_PRESETS = "equalizer_user_presets";

    /** The frequencies of a classic 10-band graphic equalizer, on which the built-in presets are written. */
    private static final int[] GRAPHIC_HZ = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

    /** A built-in preset: its sound in decibels on the 10 classic bands, put onto the phone's bands when chosen. */
    public static final class Preset {
        public final String id;
        private final String russian, english, russianNote, englishNote;
        private final float[] decibels;

        Preset(String id, String russian, String english, String russianNote, String englishNote, float... decibels) {
            this.id = id;
            this.russian = russian;
            this.english = english;
            this.russianNote = russianNote;
            this.englishNote = englishNote;
            this.decibels = decibels;
        }

        public String name() {
            return RUSSIAN ? russian : english;
        }

        public String note() {
            return RUSSIAN ? russianNote : englishNote;
        }

        /** The preset on the phone's bands, in millibels, kept within the phone's range. */
        public short[] levels(Info info) {
            return onBands(decibels, info);
        }
    }

    private static final boolean RUSSIAN = "ru".equals(java.util.Locale.getDefault().getLanguage());

    /** The usual curves of music players (Winamp, foobar2000, Poweramp and their kin), slightly evened out. */
    public static final Preset[] PRESETS = {
            new Preset("flat", "Ровно", "Flat", "Звук как записан, без правок", "The sound as recorded",
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            new Preset("bass", "Больше баса", "Bass boost", "Мягкий подъём низа", "A soft lift of the lows",
                    6, 5.5f, 4.5f, 3, 1, 0, 0, 0, 0, 0),
            new Preset("subbass", "Глубокий бас", "Deep bass", "Саб-бас для хип-хопа и электроники", "Sub-bass for hip-hop and electronic",
                    8, 7, 4, 1, -1, -1.5f, 0, 0, 0, 0),
            new Preset("bass_cut", "Меньше баса", "Bass reducer", "Для гулких колонок и машины", "For boomy speakers and the car",
                    -6, -5, -4, -2.5f, -1, 0, 0, 0, 0, 0),
            new Preset("treble", "Больше высоких", "Treble boost", "Воздух и детали", "Air and detail",
                    0, 0, 0, 0, 0, 1, 2.5f, 4, 5, 6),
            new Preset("treble_cut", "Меньше высоких", "Treble reducer", "Убирает резкость и шипение", "Tames harshness and hiss",
                    0, 0, 0, 0, 0, -1, -2.5f, -4, -5, -6),
            new Preset("smile", "Смайл", "V-shape", "Сочный низ и яркий верх, середина тише", "Rich lows and bright highs",
                    6, 4.5f, 2.5f, 0, -2, -2, 0, 2.5f, 4.5f, 6),
            new Preset("loudness", "Тихое прослушивание", "Loudness", "Добавляет края звука на малой громкости", "Fills in the edges at low volume",
                    5, 4, 2, 0.5f, 0, 0, 0.5f, 2, 3.5f, 4.5f),
            new Preset("vocal", "Голос", "Vocal", "Вокал и подкасты вперёд", "Vocals and podcasts up front",
                    -2, -3, -3, 1, 3.5f, 4, 3.5f, 2, 0, -1),
            new Preset("rock", "Рок", "Rock", "Плотный низ и звонкие гитары", "Punchy lows and ringing guitars",
                    5, 4, 3, 1, -1, -1, 1, 3, 4, 5),
            new Preset("metal", "Метал", "Metal", "Бочка, рык гитар и тарелки", "Kick drum, guitar growl and cymbals",
                    5, 4, 1, -2, -1, 1, 3.5f, 4.5f, 4, 3),
            new Preset("pop", "Поп", "Pop", "Голос и середина ярче", "Brighter voice and mids",
                    -1.5f, -1, 0, 2, 4, 4, 2, 0, -1, -1.5f),
            new Preset("hiphop", "Хип-хоп", "Hip-hop", "Тяжёлый низ и чёткий голос", "Heavy lows and a clear voice",
                    5, 4.5f, 2, 3, -1, -1, 1, -0.5f, 1, 3),
            new Preset("electronic", "Электроника", "Electronic", "Бас и искрящийся верх", "Bass and sparkling highs",
                    4.5f, 4, 1, 0, -2, 2, 1, 1, 4, 5),
            new Preset("dance", "Танцевальная", "Dance", "Для клуба: бочка и хай-хэт", "Club sound: kick and hi-hats",
                    4, 6, 4.5f, 0, 1, 2.5f, 4.5f, 4, 3, 0),
            new Preset("rnb", "R&B", "R&B", "Тёплый бас и гладкий верх", "Warm bass and smooth highs",
                    2.5f, 7, 5.5f, 1.5f, -2.5f, -1.5f, 2, 2.5f, 3, 3.5f),
            new Preset("jazz", "Джаз", "Jazz", "Тёплые духовые и контрабас", "Warm horns and double bass",
                    4, 3, 1.5f, 2, -1.5f, -1.5f, 0, 1.5f, 3, 4),
            new Preset("classical", "Классика", "Classical", "Широкий зал, оркестр", "A wide hall, an orchestra",
                    4.5f, 3.5f, 3, 2.5f, -1.5f, -1.5f, 0, 2, 3, 4),
            new Preset("acoustic", "Акустика", "Acoustic", "Живые инструменты и гитара", "Live instruments and guitar",
                    4.5f, 4.5f, 3.5f, 1, 2, 1.5f, 3, 3.5f, 3, 2),
            new Preset("lofi", "Lo-fi", "Lo-fi", "Мягко и тепло, верх приглушён", "Soft and warm, muted highs",
                    3, 2.5f, 2, 1.5f, 1, 0, -1, -2.5f, -4, -5),
            new Preset("lounge", "Лаунж", "Lounge", "Спокойный фон", "A calm background",
                    -3, -1.5f, -0.5f, 1.5f, 4, 2.5f, 0, -1.5f, 2, 1),
    };

    /** Puts a 10-band curve onto the phone's bands: interpolated on a logarithmic frequency scale. */
    private static short[] onBands(float[] decibels, Info info) {
        int bands = info.centerFrequenciesHz.length;
        short[] levels = new short[bands];
        for (int band = 0; band < bands; band++) {
            double hz = Math.max(GRAPHIC_HZ[0], Math.min(GRAPHIC_HZ[GRAPHIC_HZ.length - 1], info.centerFrequenciesHz[band]));
            int upper = 1;
            while (upper < GRAPHIC_HZ.length - 1 && GRAPHIC_HZ[upper] < hz) upper++;
            double position = (Math.log(hz) - Math.log(GRAPHIC_HZ[upper - 1]))
                    / (Math.log(GRAPHIC_HZ[upper]) - Math.log(GRAPHIC_HZ[upper - 1]));
            double db = decibels[upper - 1] + (decibels[upper] - decibels[upper - 1]) * Math.max(0, Math.min(1, position));
            // Half-decibel steps, as the curve on the settings screen moves.
            int millibels = (int) Math.round(db * 2) * 50;
            levels[band] = (short) Math.max(info.minLevel, Math.min(info.maxLevel, millibels));
        }
        return levels;
    }

    public static Preset preset(String id) {
        for (Preset preset : PRESETS) if (preset.id.equals(id)) return preset;
        return null;
    }

    /** One of the user's presets. */
    public static final class UserPreset {
        public final String name;
        public final short[] levels;

        UserPreset(String name, short[] levels) {
            this.name = name;
            this.levels = levels;
        }
    }

    public static List<UserPreset> userPresets(int bands) {
        List<UserPreset> result = new ArrayList<>();
        try {
            org.json.JSONArray list = new org.json.JSONArray(Settings.getString(USER_PRESETS, "[]"));
            for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject item = list.getJSONObject(i);
                org.json.JSONArray saved = item.getJSONArray("levels");
                short[] levels = new short[bands];
                for (int band = 0; band < bands && band < saved.length(); band++) levels[band] = (short) saved.getInt(band);
                result.add(new UserPreset(item.getString("name"), levels));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Equalizer: could not read the user's presets", ex);
        }
        return result;
    }

    private static void saveUserPresets(List<UserPreset> presets) {
        org.json.JSONArray list = new org.json.JSONArray();
        try {
            for (UserPreset preset : presets) {
                org.json.JSONArray levels = new org.json.JSONArray();
                for (short level : preset.levels) levels.put(level);
                list.put(new org.json.JSONObject().put("name", preset.name).put("levels", levels));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Equalizer: could not save the user's presets", ex);
        }
        Settings.putString(USER_PRESETS, list.toString());
    }

    /** Saves the current band levels as the user's preset; one with the same name is replaced. */
    public static void saveUserPreset(String name, short[] levels) {
        List<UserPreset> presets = userPresets(levels.length);
        int index = -1;
        for (int i = 0; i < presets.size(); i++) if (presets.get(i).name.equals(name)) index = i;
        UserPreset preset = new UserPreset(name, levels.clone());
        if (index >= 0) presets.set(index, preset);
        else presets.add(preset);
        saveUserPresets(presets);
        Settings.putString(CHOICE, "u:" + name);
    }

    public static void renameUserPreset(String from, String to, int bands) {
        List<UserPreset> presets = userPresets(bands);
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).name.equals(from)) presets.set(i, new UserPreset(to, presets.get(i).levels));
        }
        saveUserPresets(presets);
        if (("u:" + from).equals(Settings.getString(CHOICE, ""))) Settings.putString(CHOICE, "u:" + to);
    }

    public static void deleteUserPreset(String name, int bands) {
        List<UserPreset> presets = userPresets(bands);
        for (int i = presets.size() - 1; i >= 0; i--) if (presets.get(i).name.equals(name)) presets.remove(i);
        saveUserPresets(presets);
        if (("u:" + name).equals(Settings.getString(CHOICE, ""))) Settings.putString(CHOICE, "");
    }

    // endregion

    /** What the settings screen shows: bands, their ranges and the device presets. */
    public static final class Info {
        public int[] centerFrequenciesHz = new int[0];
        public short minLevel;
        public short maxLevel;
        public String[] presets = new String[0];
        /** Band levels of each preset, to show them on the sliders. */
        public short[][] presetLevels = new short[0][];
    }

    /** Reads the equalizer of this device on a session of its own. */
    public static Info readInfo() {
        Info info = new Info();
        Equalizer equalizer = null;
        try {
            Context context = Utils.getContext();
            int session = ((AudioManager) context.getSystemService(Context.AUDIO_SERVICE)).generateAudioSessionId();
            equalizer = new Equalizer(0, session);
            short bands = equalizer.getNumberOfBands();
            info.centerFrequenciesHz = new int[bands];
            for (short band = 0; band < bands; band++) info.centerFrequenciesHz[band] = equalizer.getCenterFreq(band) / 1000;
            short[] range = equalizer.getBandLevelRange();
            info.minLevel = range[0];
            info.maxLevel = range[1];
            short presets = equalizer.getNumberOfPresets();
            info.presets = new String[presets];
            info.presetLevels = new short[presets][];
            for (short preset = 0; preset < presets; preset++) {
                info.presets[preset] = equalizer.getPresetName(preset);
                equalizer.usePreset(preset);
                info.presetLevels[preset] = new short[bands];
                for (short band = 0; band < bands; band++) info.presetLevels[preset][band] = equalizer.getBandLevel(band);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not read the equalizer", ex);
        } finally {
            if (equalizer != null) equalizer.release();
        }
        return info;
    }
}
