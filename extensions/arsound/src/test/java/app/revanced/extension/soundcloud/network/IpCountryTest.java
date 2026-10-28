package app.revanced.extension.soundcloud.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class IpCountryTest {
    @Test
    public void knownCountriesAreNamedInWords() {
        assertEquals("российский IP", IpCountry.describe("RU", true));
        assertEquals("Russian IP", IpCountry.describe("RU", false));
        assertEquals("украинский IP", IpCountry.describe("UA", true));
        assertEquals("Belarusian IP", IpCountry.describe("BY", false));
        assertEquals("казахстанский IP", IpCountry.describe("KZ", true));
    }

    @Test
    public void otherCountriesAreNamedByTheirCode() {
        assertEquals("IP страны DE", IpCountry.describe("DE", true));
        assertEquals("IP from DE", IpCountry.describe("DE", false));
    }

    @Test
    public void aSentenceCanStartWithTheName() {
        assertEquals("Российский IP", IpCountry.describeCapitalized("RU", true));
        assertEquals("IP from DE", IpCountry.describeCapitalized("DE", false));
    }

    @Test
    public void traceLineGivesTheCountry() {
        assertEquals("DE", CountryTrace.countryOf("loc=de"));
        assertEquals("RU", CountryTrace.countryOf("loc= RU "));
        assertNull(CountryTrace.countryOf("ip=1.2.3.4"));
    }

    @Test
    public void theBlockToastNamesTheCountryOrTheFailure() {
        assertEquals("Arsound: российский IP — SoundCloud отключён, играют скачанные треки", BlockNotice.message("RU", true));
        assertEquals("Arsound: Russian IP, SoundCloud is off, downloaded tracks still play", BlockNotice.message("RU", false));
        assertEquals("Arsound: could not check the IP country, SoundCloud is off, downloaded tracks still play",
                BlockNotice.message(null, false));
    }
}
