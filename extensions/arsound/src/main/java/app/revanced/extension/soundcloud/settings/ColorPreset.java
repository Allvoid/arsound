package app.revanced.extension.soundcloud.settings;

/** A ready colour of the colour picker: a name to read out and show, and the colour as #RRGGBB. */
final class ColorPreset {
    final String name;
    final String hex;

    ColorPreset(String name, String hex) {
        this.name = name;
        this.hex = hex;
    }
}
