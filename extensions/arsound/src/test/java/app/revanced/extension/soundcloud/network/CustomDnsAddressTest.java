package app.revanced.extension.soundcloud.network;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CustomDnsAddressTest {
    @Test
    public void onlyWrittenOutAddressesAreServers() {
        assertTrue(CustomDns.isIpLiteral("1.1.1.1"));
        assertTrue(CustomDns.isIpLiteral("255.0.0.255"));
        assertTrue(CustomDns.isIpLiteral("2606:4700:4700::1111"));
        assertTrue(CustomDns.isIpLiteral("::1"));
    }

    @Test
    public void namesAndBrokenAddressesAreRefusedWithoutALookup() {
        // "abc" is hex letters only: it must not pass as an address, nor reach the system resolver.
        assertFalse(CustomDns.isIpLiteral("abc"));
        assertFalse(CustomDns.isIpLiteral("dns.example"));
        assertFalse(CustomDns.isIpLiteral("999.1.1.1"));
        assertFalse(CustomDns.isIpLiteral("1.1.1"));
        assertFalse(CustomDns.isIpLiteral("abc:def"));
        assertFalse(CustomDns.isIpLiteral(""));
        assertFalse(CustomDns.isIpLiteral(null));
    }
}
