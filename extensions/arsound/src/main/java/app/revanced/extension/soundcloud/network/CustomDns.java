package app.revanced.extension.soundcloud.network;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Resolves host names through a chosen DNS server instead of the system one, for this app only.
 * <p>
 * Modes: DNS-over-HTTPS ({@link DohResolver}: the query goes inside HTTPS and cannot be seen or replaced by the
 * provider) and plain DNS over UDP ({@link PlainDnsResolver}). "Auto" tries DoH first, then plain DNS. If the
 * chosen server fails, the system resolver is used, so the option never breaks the network.
 */
public final class CustomDns {
    public static final String PRESET_XBOX = "xbox";
    public static final String PRESET_CUSTOM = "custom";

    public static final String MODE_AUTO = "auto";
    public static final String MODE_DOH = "doh";
    public static final String MODE_PLAIN = "plain";
    /** Only the system resolver: the servers are not asked. */
    public static final String MODE_SYSTEM = DnsModes.SYSTEM;

    /** The host name of the xbox-dns.ru preset, shown in the settings. */
    public static final String XBOX_DOH_HOST = "xbox-dns.ru";
    public static final String XBOX_DOH = "https://xbox-dns.ru/dns-query";
    public static final String[] XBOX_SERVERS = {"111.88.96.50", "111.88.96.51"};

    private static final class Entry {
        final List<InetAddress> addresses;
        final long expiresAt;

        Entry(List<InetAddress> addresses, long ttlSeconds) {
            this.addresses = addresses;
            this.expiresAt = System.currentTimeMillis() + Math.max(30, Math.min(ttlSeconds, 3600)) * 1000;
        }
    }

    private static final Map<String, Entry> cache = new ConcurrentHashMap<>();
    /** Hosts the custom servers failed for, until when: the system resolver answers them without the long wait. */
    private static final Map<String, Long> failedUntil = new ConcurrentHashMap<>();
    private static final long FAILURE_KEPT_MS = 30_000;
    private static final int MAX_CACHED_HOSTS = 500;

    /** In the order they are tried. */
    private static final List<DnsResolver> RESOLVERS =
            Collections.unmodifiableList(Arrays.<DnsResolver>asList(new DohResolver(), new PlainDnsResolver()));

    private CustomDns() {
    }

    public static String dohUrl() {
        return PRESET_CUSTOM.equals(Settings.getDnsPreset()) ? Settings.getCustomDohUrl() : XBOX_DOH;
    }

    public static String[] plainServers() {
        if (!PRESET_CUSTOM.equals(Settings.getDnsPreset())) return XBOX_SERVERS;
        List<String> servers = new ArrayList<>();
        for (String server : Settings.getCustomDnsServers().split("[,\\s]+")) {
            // A name here would be looked up through the system resolver first: only addresses are kept.
            if (isIpLiteral(server.trim())) servers.add(server.trim());
        }
        return servers.toArray(new String[0]);
    }

    private static final Pattern IPV4 = Pattern.compile(
            "((25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])");

    /**
     * Whether the text is an IPv4 or IPv6 address written out, so that it needs no lookup. Checked by shape first: Java
     * would send a dotted text that is not a valid address to the system resolver, and that is what this must not do.
     */
    public static boolean isIpLiteral(String text) {
        if (text == null || text.isEmpty()) return false;
        if (IPV4.matcher(text).matches()) return true;
        if (text.indexOf(':') < 0 || !text.matches("[0-9a-fA-F:.]+")) return false;
        try {
            // With a colon in it, Java parses the text as an address and never asks a resolver.
            InetAddress.getByName(text);
            return true;
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    /**
     * @return The addresses of the host, or null to use the system resolver.
     */
    /** The lookups under way, by host: connections to a new host at once wait for one query, not one each. */
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.FutureTask<List<InetAddress>>> inFlight =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static List<InetAddress> lookup(String host) {
        if (host == null || !DnsModes.asksCustomServers(Settings.isCustomDnsEnabled(), Settings.getDnsMode())) return null;
        if (host.matches("[0-9.]+") || host.contains(":")) return null;
        Entry known = cache.get(host);
        if (known != null && known.expiresAt > System.currentTimeMillis()) return known.addresses;
        java.util.concurrent.FutureTask<List<InetAddress>> mine = new java.util.concurrent.FutureTask<>(() -> resolveNow(host));
        java.util.concurrent.FutureTask<List<InetAddress>> running = inFlight.putIfAbsent(host, mine);
        if (running == null) {
            try {
                mine.run();
            } finally {
                inFlight.remove(host, mine);
            }
            running = mine;
        }
        try {
            return running.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static List<InetAddress> resolveNow(String host) {
        // Literal addresses need no resolving.
        if (host.matches("[0-9.]+") || host.contains(":")) return null;

        Entry cached = cache.get(host);
        if (cached != null && cached.expiresAt > System.currentTimeMillis()) return cached.addresses;
        // Unreachable servers cost several seconds per attempt; every new connection must not pay them again.
        Long failed = failedUntil.get(host);
        if (failed != null && failed > System.currentTimeMillis()) return null;

        String mode = Settings.getDnsMode();
        DnsLookup found = null;
        for (DnsResolver resolver : RESOLVERS) {
            if (found != null) break;
            if (!resolver.takesPartIn(mode) || !resolver.isConfigured() || resolver.isSkipped()) continue;
            DnsLookup lookup = resolver.resolve(host);
            if (lookup.unreachable) resolver.markDown();
            if (lookup.addresses != null) found = lookup;
        }
        if (found == null || found.addresses.isEmpty()) {
            Logger.printInfo(() -> "Custom DNS could not resolve " + host + ", using system DNS");
            if (failedUntil.size() >= MAX_CACHED_HOSTS) failedUntil.clear();
            failedUntil.put(host, System.currentTimeMillis() + FAILURE_KEPT_MS);
            return null;
        }

        // Hosts of CDN shards come in thousands over a long session: the cache does not grow for ever.
        if (cache.size() >= MAX_CACHED_HOSTS) cache.clear();
        failedUntil.remove(host);
        cache.put(host, new Entry(found.addresses, found.ttlSeconds));
        return found.addresses;
    }

    public static void clearCache() {
        failedUntil.clear();
        cache.clear();
        for (DnsResolver resolver : RESOLVERS) resolver.markUp();
    }

    /** What the chosen servers answered for one host, for the diagnostics. */
    static final class Probe {
        /** False when the servers are not used at all: the option is off, or the mode is "system only". */
        final boolean active;
        final List<String> addresses = new ArrayList<>();
        /** "DoH" or "DNS" when a server answered. */
        String method = "";
        String error = "";
        long elapsedMs;

        Probe(boolean active) {
            this.active = active;
        }
    }

    /**
     * Asks the chosen servers for the host with a real query, ignoring the cache and the marks of failed servers, so the
     * diagnostics show what the servers say now. Blocks; not for the main thread.
     */
    static Probe probe(String host) {
        String mode = Settings.getDnsMode();
        Probe probe = new Probe(DnsModes.asksCustomServers(Settings.isCustomDnsEnabled(), mode));
        if (!probe.active) return probe;
        long start = System.currentTimeMillis();
        StringBuilder tried = new StringBuilder();
        for (DnsResolver resolver : RESOLVERS) {
            if (!resolver.takesPartIn(mode) || !resolver.isConfigured()) continue;
            DnsLookup lookup = resolver.resolve(host);
            if (tried.length() > 0) tried.append(", ");
            tried.append(resolver.label());
            if (lookup.addresses != null && !lookup.addresses.isEmpty()) {
                probe.method = resolver.label();
                for (java.net.InetAddress address : lookup.addresses) probe.addresses.add(address.getHostAddress());
                break;
            }
        }
        probe.elapsedMs = System.currentTimeMillis() - start;
        if (probe.addresses.isEmpty()) probe.error = tried.length() == 0 ? "no server is set" : "no answer from " + tried;
        return probe;
    }

    /** The "Check" button in the mode "system only": what the phone's own DNS says. Null if it has no answer. */
    private static String testSystem(String host, long start) {
        try {
            java.net.InetAddress[] found = java.net.InetAddress.getAllByName(host);
            StringBuilder text = new StringBuilder(
                    app.revanced.extension.soundcloud.shared.Lang.text("DNS телефона", "Phone DNS") + ", "
                            + (System.currentTimeMillis() - start) + " ms: ");
            for (int i = 0; i < Math.min(found.length, 3); i++) {
                if (i > 0) text.append(", ");
                text.append(found[i].getHostAddress());
            }
            return text.toString();
        } catch (java.net.UnknownHostException ex) {
            return null;
        }
    }

    /** Resolves a host through the chosen server only, for the "Check" button. */
    public static String test(String host) {
        long start = System.currentTimeMillis();
        String mode = Settings.getDnsMode();
        if (DnsModes.SYSTEM.equals(mode)) return testSystem(host, start);
        DnsLookup found = null;
        String used = "";
        for (DnsResolver resolver : RESOLVERS) {
            if (found != null) break;
            if (!resolver.takesPartIn(mode) || !resolver.isConfigured()) continue;
            DnsLookup lookup = resolver.resolve(host);
            used = resolver.label();
            if (lookup.addresses != null) {
                found = lookup;
                resolver.markUp();
            }
        }
        long elapsed = System.currentTimeMillis() - start;
        if (found == null) return null;
        StringBuilder text = new StringBuilder(used + ", " + elapsed + " ms: ");
        for (int i = 0; i < Math.min(found.addresses.size(), 3); i++) {
            if (i > 0) text.append(", ");
            text.append(found.addresses.get(i).getHostAddress());
        }
        return text.toString();
    }
}
