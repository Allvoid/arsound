package app.revanced.extension.soundcloud.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ReleaseVersionTest {
    @Test
    public void aLaterNumberIsNewer() {
        assertTrue(ReleaseVersion.compare("0.10.0", "0.9.3") > 0);
        assertTrue(ReleaseVersion.compare("0.9.3", "0.9.4") < 0);
        assertTrue(ReleaseVersion.compare("1.0", "0.99.9") > 0);
    }

    @Test
    public void aMissingPartCountsAsZero() {
        assertEquals(0, ReleaseVersion.compare("1.0", "1.0.0"));
    }

    @Test
    public void theDigitsOfAPreReleaseAreNotAPartOfTheVersion() {
        // Was read as 0.9.3.1, newer than 0.9.3.
        assertTrue(ReleaseVersion.compare("0.9.3-rc.1", "0.9.3") < 0);
        assertTrue(ReleaseVersion.compare("0.9.3", "0.9.3-beta.2") > 0);
    }

    @Test
    public void aPreReleaseOfALaterVersionIsStillNewer() {
        assertTrue(ReleaseVersion.compare("0.10.0-beta.1", "0.9.3") > 0);
    }

    @Test
    public void aSuffixWithoutDigitsComesBeforeTheRelease() {
        assertTrue(ReleaseVersion.compare("0.9.3-beta", "0.9.3") < 0);
        assertEquals(0, ReleaseVersion.compare("0.9.3-beta", "0.9.3-rc"));
    }

    @Test
    public void buildMetadataIsIgnoredForTheNumbers() {
        assertTrue(ReleaseVersion.compare("0.9.4+build.7", "0.9.3") > 0);
    }
}
