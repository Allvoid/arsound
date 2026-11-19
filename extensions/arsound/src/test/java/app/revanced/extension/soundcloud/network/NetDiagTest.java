package app.revanced.extension.soundcloud.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class NetDiagTest {
    private static NetDiag.Row answered(String host, int status, long ms) {
        NetDiag.Row row = new NetDiag.Row(host);
        row.systemAddresses = Arrays.asList("192.0.2.1");
        row.httpStatus = status;
        row.httpMs = ms;
        return row;
    }

    private static NetDiag.Row silent(String host) {
        NetDiag.Row row = new NetDiag.Row(host);
        row.systemError = "no address";
        row.httpError = "timeout";
        row.httpMs = 10_000;
        return row;
    }

    @Test
    public void anAnswerIsOkSlowOrFailByStatusAndTime() {
        assertEquals(NetDiag.Verdict.OK, NetDiag.verdict(answered("a.example", 200, 300)));
        assertEquals(NetDiag.Verdict.OK, NetDiag.verdict(answered("a.example", 200, NetDiag.SLOW_MS)));
        assertEquals(NetDiag.Verdict.SLOW, NetDiag.verdict(answered("a.example", 200, NetDiag.SLOW_MS + 1)));
        // Any status is an answer: the server was reached.
        assertEquals(NetDiag.Verdict.OK, NetDiag.verdict(answered("a.example", 403, 100)));
        assertEquals(NetDiag.Verdict.FAIL, NetDiag.verdict(silent("a.example")));
    }

    @Test
    public void theTallyLeavesOutSkippedSitesAndTakesTheWorstVerdict() {
        NetDiag.Row skipped = new NetDiag.Row("skipped.example");
        skipped.skipped = true;
        List<NetDiag.Row> rows = new ArrayList<>(Arrays.asList(
                answered("a.example", 200, 100), answered("b.example", 200, 5_000), silent("c.example"), skipped));
        NetDiag.Tally tally = NetDiag.tally(rows);
        assertEquals(3, tally.checked);
        assertEquals(1, tally.failed);
        assertEquals(1, tally.slow);
        assertEquals(NetDiag.Verdict.FAIL, tally.overall());

        rows.remove(2);
        assertEquals(NetDiag.Verdict.SLOW, NetDiag.tally(rows).overall());
        rows.remove(1);
        assertEquals(NetDiag.Verdict.OK, NetDiag.tally(rows).overall());
        assertEquals(NetDiag.Verdict.OK, NetDiag.tally(new ArrayList<NetDiag.Row>()).overall());
    }

    @Test
    public void aTypedDomainIsCleanedOrRefused() {
        assertEquals("example.org", NetDiag.cleanDomain("  Example.ORG "));
        assertEquals("sub.example.org", NetDiag.cleanDomain("https://sub.example.org:8443/path?q=1"));
        assertEquals("example.org", NetDiag.cleanDomain("user@example.org."));
        assertNull(NetDiag.cleanDomain(""));
        assertNull(NetDiag.cleanDomain(null));
        assertNull(NetDiag.cleanDomain("localhost"));
        assertNull(NetDiag.cleanDomain("192.0.2.1"));
        assertNull(NetDiag.cleanDomain("not a domain.org"));
        assertNull(NetDiag.cleanDomain("-bad-.example.org"));
    }

    @Test
    public void aNonLatinDomainIsCheckedInItsAsciiForm() {
        // Cyrillic: the name is turned into xn-- form before the check.
        String cyrillic = NetDiag.cleanDomain("яндекс.рф");
        assertTrue(cyrillic != null && cyrillic.endsWith(".xn--p1ai"));
        assertEquals("site.xn--p1ai", NetDiag.cleanDomain("site.xn--p1ai"));
        assertNull(NetDiag.cleanDomain("site.xn--"));
    }

    @Test
    public void aDohAddressInTheReportKeepsOnlySchemeAndHost() {
        assertEquals("https://dns.example", NetDiag.dohOrigin("https://dns.example/dns-query/abcd1234"));
        assertEquals("https://dns.example", NetDiag.dohOrigin("https://dns.example/dns-query?id=SECRET"));
        assertEquals("custom DoH", NetDiag.dohOrigin("dns.example/dns-query/abcd1234"));
        assertEquals("custom DoH", NetDiag.dohOrigin(""));
        assertEquals("custom DoH", NetDiag.dohOrigin(null));
    }

    @Test
    public void theCountryComesFromTheLocLineOfTheTrace() {
        assertEquals("FI", NetDiag.parseTraceCountry("fl=1\r\nip=192.0.2.7\r\nloc=FI\r\ntls=TLSv1.3\r\n"));
        assertEquals("", NetDiag.parseTraceCountry("ip=192.0.2.7\n"));
        assertEquals("", NetDiag.parseTraceCountry(null));
    }

    @Test
    public void theStatusComesFromTheFirstLineOfTheAnswer() {
        assertEquals(200, NetDiag.parseStatusLine("HTTP/1.1 200 OK"));
        assertEquals(301, NetDiag.parseStatusLine("HTTP/2 301"));
        assertEquals(0, NetDiag.parseStatusLine("<html>"));
        assertEquals(0, NetDiag.parseStatusLine("HTTP/1.1 abc"));
        assertEquals(0, NetDiag.parseStatusLine(null));
    }

    @Test
    public void theReportSaysWhatEachStepFound() {
        NetDiag.Row good = answered("a.example", 200, 120);
        good.customActive = true;
        good.customAddresses = Arrays.asList("198.51.100.4");
        good.customMethod = "DoH";
        good.customMs = 45;
        NetDiag.Row bad = silent("b.example");
        bad.customActive = true;
        bad.customError = "no answer";
        String text = NetDiag.report("2026-11-19 10:00:00 UTC", "auto: no DoH, then UDP 192.0.2.53", "FI",
                Arrays.asList(good, bad), false);

        assertTrue(text.contains("Country: FI"));
        assertTrue(text.contains("Result: 2 checked, 1 failed, 0 slow"));
        assertTrue(text.contains("custom DNS: 198.51.100.4 (DoH, 45 ms)"));
        assertTrue(text.contains("HTTPS HEAD: status 200, 120 ms, ok"));
        assertTrue(text.contains("system DNS: FAILED (no address)"));
        assertTrue(text.contains("custom DNS: no answer (no answer), the system resolver is used"));
        assertTrue(text.contains("HTTPS HEAD: FAILED (timeout), 10000 ms"));
    }

    @Test
    public void theDnsPathFollowsTheModeAndTheSwitch() {
        assertTrue(NetDiag.describeDnsPath(false, "auto", "", "").startsWith("system resolver"));
        assertTrue(NetDiag.describeDnsPath(true, DnsModes.SYSTEM, "https://dns.example/q", "").startsWith("system resolver"));
        assertEquals("DoH https://dns.example", NetDiag.describeDnsPath(true, DnsModes.DOH, "https://dns.example/q", "192.0.2.1"));
        assertEquals("UDP 192.0.2.1", NetDiag.describeDnsPath(true, DnsModes.PLAIN, "", "192.0.2.1"));
        assertEquals("auto: DoH https://dns.example, then UDP 192.0.2.1",
                NetDiag.describeDnsPath(true, DnsModes.AUTO, "https://dns.example/q", "192.0.2.1"));
    }

    @Test
    public void theScreenListsTheTenSitesOfTheDesktop() {
        assertEquals(10, NetDiag.HOSTS.size());
        assertEquals("soundcloud.com", NetDiag.HOSTS.get(0));
        assertTrue(NetDiag.HOSTS.contains("redirector.googlevideo.com"));
    }
}
