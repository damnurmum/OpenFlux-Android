package io.openflux.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

// DNS field, domain routing and the update check's version compare.
public class SmallLogicTest {

    @Test
    public void dnsAcceptsOnlyPlainIpv4() {
        assertEquals("8.8.8.8", DnsServer.normalizeIpv4(" 008.8.08.8 "));
        assertNull(DnsServer.normalizeIpv4("dns.google"));
        assertNull(DnsServer.normalizeIpv4("1.2.3"));
        assertNull(DnsServer.normalizeIpv4("1..2.3"));
        assertNull(DnsServer.normalizeIpv4("256.1.1.1"));
        assertNull(DnsServer.normalizeIpv4("1.2.3.4.5"));
        assertEquals(DnsServer.DEFAULT, DnsServer.effective(""));
        assertEquals("9.9.9.9", DnsServer.effective("9.9.9.9"));
    }

    @Test
    public void domainMatchesItselfAndSubdomainsOnly() {
        Set<String> domains = new HashSet<>(Arrays.asList("youtube.com", "ya.ru"));
        assertTrue(DomainFilter.matches("youtube.com", domains));
        assertTrue(DomainFilter.matches("M.YouTube.com", domains));
        assertFalse(DomainFilter.matches("notyoutube.com", domains));
        assertFalse(DomainFilter.matches("youtube.com.evil.org", domains));
        assertFalse(DomainFilter.matches("", domains));
        assertFalse(DomainFilter.matches(null, domains));
    }

    @Test
    public void everyPresetIsFoundById() {
        for (DomainFilter.Preset preset : DomainFilter.PRESETS) {
            assertNotNull(preset.id, DomainFilter.findPreset(preset.id));
        }
        assertNull(DomainFilter.findPreset("nope"));
    }

    @Test
    public void versionsCompareNumerically() {
        assertTrue(VersionCheck.compareVersions("1.1.10", "1.1.9") > 0);
        assertTrue(VersionCheck.compareVersions("1.1.6", "1.1.7") < 0);
        assertEquals(0, VersionCheck.compareVersions("1.1.7", "1.1.7"));
        assertEquals(0, VersionCheck.compareVersions("1.2", "1.2.0"));
        assertTrue(VersionCheck.compareVersions("1.2", "1.1.9") > 0);
    }
}
