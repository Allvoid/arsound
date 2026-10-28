package app.revanced.extension.soundcloud.theme;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CustomThemeHexTest {
    @Test
    public void aSixDigitColourIsWrittenInCapitals() {
        assertEquals("#FF5500", CustomTheme.hex("#ff5500", "#000000"));
        assertEquals("#FF5500", CustomTheme.hex("ff5500", "#000000"));
    }

    @Test
    public void aThreeDigitColourIsDoubled() {
        assertEquals("#AABBCC", CustomTheme.hex("#abc", "#000000"));
    }

    @Test
    public void textThatIsNoColourGivesTheFallback() {
        assertEquals("#123456", CustomTheme.hex("#12345", "#123456"));
        assertEquals("#123456", CustomTheme.hex("#gggggg", "#123456"));
        assertEquals("#123456", CustomTheme.hex(null, "#123456"));
    }

    @Test
    public void spacesAroundTheColourAreIgnored() {
        assertEquals("#0A0B0C", CustomTheme.hex("  #0a0b0c ", "#000000"));
    }
}
