package app.revanced.extension.soundcloud.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The equalizer presets the user saved, kept in the settings as a JSON array of
 * {@code {"name", "levels": [millibels per band]}}. Every change reads the list, changes it and writes it back, in one
 * place. Presets saved by an older version, on the few bands of the phone's own equalizer, are put onto the bands of
 * now when read ({@link LegacyLevels}).
 */
final class UserPresetStore {
    private UserPresetStore() {
    }

    static List<AudioEqualizer.UserPreset> load(int bands) {
        List<AudioEqualizer.UserPreset> result = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(Settings.getString(AudioEqualizer.USER_PRESETS, "[]"));
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.getJSONObject(i);
                JSONArray saved = item.getJSONArray("levels");
                short[] read = new short[saved.length()];
                for (int band = 0; band < read.length; band++) read[band] = (short) saved.getInt(band);
                result.add(new AudioEqualizer.UserPreset(item.getString("name"), LegacyLevels.onTenBands(read, bands)));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Equalizer: could not read the user's presets", ex);
        }
        return result;
    }

    private static void write(List<AudioEqualizer.UserPreset> presets) {
        JSONArray list = new JSONArray();
        try {
            for (AudioEqualizer.UserPreset preset : presets) {
                JSONArray levels = new JSONArray();
                for (short level : preset.levels) levels.put(level);
                list.put(new JSONObject().put("name", preset.name).put("levels", levels));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Equalizer: could not save the user's presets", ex);
        }
        Settings.putString(AudioEqualizer.USER_PRESETS, list.toString());
    }

    /** Saves the levels as a preset; one with the same name is replaced. It becomes the chosen one. */
    static void put(String name, short[] levels) {
        List<AudioEqualizer.UserPreset> presets = load(levels.length);
        int index = -1;
        for (int i = 0; i < presets.size(); i++) if (presets.get(i).name.equals(name)) index = i;
        AudioEqualizer.UserPreset preset = new AudioEqualizer.UserPreset(name, levels.clone());
        if (index >= 0) presets.set(index, preset);
        else presets.add(preset);
        write(presets);
        Settings.putString(AudioEqualizer.CHOICE, EqualizerChoice.userPreset(name));
    }

    /** Gives a preset another name; the chosen one follows it. */
    static void rename(String from, String to, int bands) {
        List<AudioEqualizer.UserPreset> presets = load(bands);
        for (int i = 0; i < presets.size(); i++) {
            if (presets.get(i).name.equals(from)) presets.set(i, new AudioEqualizer.UserPreset(to, presets.get(i).levels));
        }
        write(presets);
        if (EqualizerChoice.isUserPreset(Settings.getString(AudioEqualizer.CHOICE, ""), from)) {
            Settings.putString(AudioEqualizer.CHOICE, EqualizerChoice.userPreset(to));
        }
    }

    /** Removes a preset; if it was the chosen one, the choice goes back to the user's own levels. */
    static void delete(String name, int bands) {
        List<AudioEqualizer.UserPreset> presets = load(bands);
        for (int i = presets.size() - 1; i >= 0; i--) if (presets.get(i).name.equals(name)) presets.remove(i);
        write(presets);
        if (EqualizerChoice.isUserPreset(Settings.getString(AudioEqualizer.CHOICE, ""), name)) {
            Settings.putString(AudioEqualizer.CHOICE, "");
        }
    }
}
