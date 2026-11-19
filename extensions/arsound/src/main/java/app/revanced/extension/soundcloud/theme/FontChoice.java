package app.revanced.extension.soundcloud.theme;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The font of the app's text (bot ticket #66): the theme's own, SoundCloud's, one of the fonts the themes ship with,
 * or a font file of the user's. It goes into the theme's resource table in place of SoundCloud's fonts
 * ({@link ThemeTableBuilder}); with SoundCloud's own look a table of the fonts alone is made ({@link #fontsOnlyTheme}).
 */
public final class FontChoice {
    public static final String KEY = "theme_font";
    public static final long THEME = 0, SOUNDCLOUD = 1, GOLOS = 2, MANROPE = 3, GEOLOGICA = 4, NUNITO = 5, ONEST = 6, OWN = 7;
    /** The ids of the choices in the order of the settings list. */
    public static final long[] VALUES = {THEME, SOUNDCLOUD, GOLOS, MANROPE, GEOLOGICA, NUNITO, ONEST, OWN};
    /** The id of the table that carries only the fonts, for SoundCloud's own look. */
    static final String FONTS_ONLY_ID = "fonts";
    /** The user's font file, under the theme files (Android reads a font resource only from a path under res/). */
    static final String OWN_FILE = ThemeFiles.ROOT + "fonts/own.ttf";

    private FontChoice() {
    }

    public static long current() {
        long value = Settings.getLong(KEY, THEME);
        for (long known : VALUES) if (known == value) return value;
        return THEME;
    }

    /** What the table depends on: a change of the font makes a new table. */
    static String key(Context context) {
        long choice = current();
        String font = String.valueOf(choice);
        if (choice == OWN) {
            File own = new File(context.getFilesDir(), OWN_FILE);
            font = choice + "-" + own.length() + "-" + own.lastModified();
        }
        // The text colour rides in the same key: both are put into the one table.
        String colour = TextColour.key();
        String key = colour.isEmpty() ? font : font + "~" + colour.substring(1);
        // And the play button glow: the flat buttons are put into the same table too.
        String glow = PlayGlow.key();
        return glow.isEmpty() ? key : key + "^" + glow;
    }

    /**
     * The font files for SoundCloud's slots (regular, semibold, bold, extrabold, numbers), as the theme's json names
     * them; null to leave the theme's own fonts (or SoundCloud's, with no theme).
     *
     * @param themeFonts The theme's own, or null.
     */
    static JSONObject fonts(Context context, JSONObject themeFonts) {
        long choice = current();
        try {
            if (choice == THEME) return themeFonts;
            if (choice == SOUNDCLOUD) return null;
            if (choice == OWN) {
                if (!new File(context.getFilesDir(), OWN_FILE).isFile()) return themeFonts;
                return all("@own");
            }
            if (choice == GOLOS) return slots("golostext_400", "golostext_700", "golostext_t04_800", "golostext_t04_900", "golostext_500");
            if (choice == MANROPE) return slots("manrope_500", "manrope_600", "manrope_800", "manrope_800", "manrope_500");
            if (choice == GEOLOGICA) return slots("geologica_400", "geologica_600", "geologica_700", "geologica_700", "geologica_500");
            if (choice == NUNITO) return slots("nunito_600", "nunito_700", "nunito_900", "nunito_900", "nunito_700");
            if (choice == ONEST) return slots("onest_400", "onest_500", "onest_600", "onest_600", "onest_500");
        } catch (Exception ex) {
            Logger.printException(() -> "Font: could not name the files", ex);
        }
        return themeFonts;
    }

    private static JSONObject slots(String regular, String semibold, String bold, String extrabold, String numbers) throws Exception {
        return new JSONObject().put("regular", regular).put("semibold", semibold).put("bold", bold)
                .put("extrabold", extrabold).put("numbers", numbers);
    }

    private static JSONObject all(String file) throws Exception {
        return slots(file, file, file, file, file);
    }

    /** Whether SoundCloud's own look needs a table: a font other than SoundCloud's or a text colour is chosen. */
    static boolean needsTableWithoutTheme() {
        long choice = current();
        return (choice != THEME && choice != SOUNDCLOUD) || TextColour.isSet();
    }

    /** The table of the fonts and the text colour alone, for SoundCloud's own look. */
    static ArsoundTheme.Theme fontsOnlyTheme(Context context) {
        try {
            return new ArsoundTheme.Theme(new JSONObject().put("id", FONTS_ONLY_ID), FONTS_ONLY_ID + "-" + key(context));
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Keeps a font file the user picked. Refused (false) when Android cannot read it as a font.
     */
    public static boolean saveOwn(Context context, Uri picked) {
        File target = new File(context.getFilesDir(), OWN_FILE);
        File folder = target.getParentFile();
        File temporary = new File(folder, "own.ttf.part");
        try {
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) return false;
            try (InputStream in = context.getContentResolver().openInputStream(picked);
                 OutputStream out = new FileOutputStream(temporary)) {
                if (in == null) return false;
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) > 0) {
                    total += read;
                    // A font of more than 30 MB is not a font of a few weights: probably a wrong file.
                    if (total > 30L * 1024 * 1024) return false;
                    out.write(buffer, 0, read);
                }
            }
            Typeface typeface = Typeface.createFromFile(temporary);
            if (typeface == null || typeface.equals(Typeface.DEFAULT)) {
                Logger.printInfo(() -> "Font: the picked file is not a font Android can read");
                return false;
            }
            // Android renames over the old font in one step: the kept font stays in place if this fails.
            if (!temporary.renameTo(target)) return false;
            Logger.printInfo(() -> "Font: own font kept, " + target.length() + " bytes");
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Font: could not keep the picked file", ex);
            return false;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
        }
    }
}
