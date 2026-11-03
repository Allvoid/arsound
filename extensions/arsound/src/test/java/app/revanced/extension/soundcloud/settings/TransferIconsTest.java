package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

/** The icons of the two rows of the settings backup ({@link SettingsTransferRows}). */
public class TransferIconsTest {
    @Test
    public void exportUsesTheUploadArrowThenTheShareIcon() {
        assertArrayEquals(new String[]{"arsound_set_export", "ic_actions_upload", "ic_actions_share"}, SettingsIcons.candidates("export"));
    }

    @Test
    public void importUsesSoundCloudsImportIcon() {
        assertArrayEquals(new String[]{"arsound_set_import", "ic_actions_import"}, SettingsIcons.candidates("import"));
    }
}
