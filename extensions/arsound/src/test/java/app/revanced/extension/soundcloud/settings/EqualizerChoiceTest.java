package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EqualizerChoiceTest {
    @Test
    public void savedFormatIsKept() {
        assertEquals("p:rock", EqualizerChoice.builtIn("rock").toStored());
        assertEquals("u:My preset 1", EqualizerChoice.user("My preset 1").toStored());
        assertEquals("", EqualizerChoice.CUSTOM.toStored());
    }

    @Test
    public void parseReadsWhatWasStored() {
        EqualizerChoice builtIn = EqualizerChoice.parse("p:rock");
        assertTrue(builtIn.isBuiltIn());
        assertFalse(builtIn.isUser());
        assertEquals("rock", builtIn.value());

        EqualizerChoice own = EqualizerChoice.parse("u:Bass: more");
        assertTrue(own.isUser());
        assertEquals("Bass: more", own.value());
    }

    @Test
    public void emptyAndUnknownTextAreCustom() {
        assertEquals(EqualizerChoice.CUSTOM, EqualizerChoice.parse(""));
        assertEquals(EqualizerChoice.CUSTOM, EqualizerChoice.parse("x:1"));
        assertEquals("", EqualizerChoice.CUSTOM.value());
        assertFalse(EqualizerChoice.CUSTOM.isBuiltIn());
        assertFalse(EqualizerChoice.CUSTOM.isUser());
    }

    @Test
    public void aPresetAndAUserPresetOfTheSameNameDiffer() {
        assertNotEquals(EqualizerChoice.builtIn("a"), EqualizerChoice.user("a"));
        assertEquals(EqualizerChoice.builtIn("a"), EqualizerChoice.parse("p:a"));
    }
}
