package app.revanced.extension.soundcloud.network;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Network diagnostics, as on Arsound Desktop: each site is looked up through the DNS of the phone and through the
 * custom DNS (a real query, not the cache) and asked over HTTPS; the verdict of a site is ok, slow or fail. This class
 * holds what needs no network or Android: the list of sites, the verdicts, the report text and the check of a typed
 * domain, so it is tested on the JVM. {@link NetDiagRunner} does the asking.
 */
public final class NetDiag {
    /** An answer slower than this is "slow". */
    public static final long SLOW_MS = 1_500;

    /**
     * The sites of the screen, in order. The googlevideo one is redirector.googlevideo.com: a name that exists (the
     * shard hosts differ per network and video).
     */
    public static final List<String> HOSTS = Collections.unmodifiableList(Arrays.asList(
            "soundcloud.com",
            "api-v2.soundcloud.com",
            "a-v2.sndcdn.com",
            "playback.media-streaming.soundcloud.cloud",
            "cf-hls-media.sndcdn.com",
            "i1.sndcdn.com",
            "ws.audioscrobbler.com",
            "music.youtube.com",
            "redirector.googlevideo.com",
            "www.cloudflare.com"));

    /** Names in ASCII: a non-Latin name is turned into its xn-- form first, so its top level is xn--... too. */
    private static final Pattern DOMAIN = Pattern.compile(
            "([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+([a-z]{2,63}|xn--[a-z0-9-]{1,59})");

    private NetDiag() {
    }

    public enum Verdict {
        OK, SLOW, FAIL
    }

    /** What was found for one site. */
    public static final class Row {
        public final String host;
        /** Empty with {@link #systemError} set: the lookup failed. */
        public List<String> systemAddresses = new ArrayList<>();
        public String systemError = "";
        /** Whether the custom DNS was asked at all (off, or "system only": it was not). */
        public boolean customActive;
        /** Empty with {@link #customActive}: the servers gave no answer and the DNS of the phone is used. */
        public List<String> customAddresses = new ArrayList<>();
        /** "DoH" or "DNS" when the servers answered. */
        public String customMethod = "";
        public String customError = "";
        public long customMs;
        /** The check was cancelled before this site started. */
        public boolean skipped;
        /** 0: no answer. */
        public int httpStatus;
        public String httpError = "";
        public long httpMs;

        public Row(String host) {
            this.host = host;
        }
    }

    /** Fail if HTTPS did not answer, slow if it answered after {@link #SLOW_MS}, else ok. */
    public static Verdict verdict(Row row) {
        if (row.httpStatus <= 0) return Verdict.FAIL;
        return row.httpMs > SLOW_MS ? Verdict.SLOW : Verdict.OK;
    }

    /** The verdict of a whole check in numbers: how many sites were checked, failed and slow. Skipped ones do not count. */
    public static final class Tally {
        public final int checked;
        public final int failed;
        public final int slow;

        Tally(int checked, int failed, int slow) {
            this.checked = checked;
            this.failed = failed;
            this.slow = slow;
        }

        /** The worst verdict of the check; ok when nothing was checked. */
        public Verdict overall() {
            return failed > 0 ? Verdict.FAIL : slow > 0 ? Verdict.SLOW : Verdict.OK;
        }
    }

    public static Tally tally(List<Row> rows) {
        int checked = 0;
        int failed = 0;
        int slow = 0;
        for (Row row : rows) {
            if (row == null || row.skipped) continue;
            checked++;
            Verdict verdict = verdict(row);
            if (verdict == Verdict.FAIL) failed++;
            else if (verdict == Verdict.SLOW) slow++;
        }
        return new Tally(checked, failed, slow);
    }

    /**
     * The domain a person typed, cleaned up: a pasted address loses its scheme, path, port and spaces. Null if what is
     * left is not a domain name (an IP address is not one: this checks names).
     */
    public static String cleanDomain(String typed) {
        if (typed == null) return null;
        String domain = typed.trim().toLowerCase(Locale.ROOT);
        int scheme = domain.indexOf("://");
        if (scheme >= 0) domain = domain.substring(scheme + 3);
        int end = domain.length();
        for (int i = 0; i < domain.length(); i++) {
            char c = domain.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        domain = domain.substring(0, end);
        int at = domain.lastIndexOf('@');
        if (at >= 0) domain = domain.substring(at + 1);
        int colon = domain.lastIndexOf(':');
        if (colon >= 0 && domain.substring(colon + 1).matches("[0-9]{1,5}")) domain = domain.substring(0, colon);
        while (domain.endsWith(".")) domain = domain.substring(0, domain.length() - 1);
        try {
            domain = IDN.toASCII(domain);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (domain.length() > 253 || !DOMAIN.matcher(domain).matches()) return null;
        return domain;
    }

    /** The country code of the Cloudflare trace ({@code loc=XX} line), or "" if there is none. */
    public static String parseTraceCountry(String trace) {
        if (trace == null) return "";
        for (String line : trace.split("\\r?\\n")) {
            if (line.startsWith("loc=")) return line.substring(4).trim();
        }
        return "";
    }

    /** The status of an HTTP answer from its first line ({@code HTTP/1.1 200 OK}); 0 if the line is not one. */
    public static int parseStatusLine(String line) {
        if (line == null || !line.startsWith("HTTP/")) return 0;
        String[] parts = line.trim().split(" +", 3);
        if (parts.length < 2 || !parts[1].matches("[0-9]{3}")) return 0;
        return Integer.parseInt(parts[1]);
    }

    private static String joined(List<String> items) {
        StringBuilder text = new StringBuilder();
        for (String item : items) {
            if (text.length() > 0) text.append(", ");
            text.append(item);
        }
        return text.toString();
    }

    /** The text of one site for the report, in English: the report goes to the developer. */
    public static String rowText(Row row) {
        StringBuilder text = new StringBuilder(row.host).append('\n');
        if (row.skipped) return text.append("  skipped (cancelled)\n\n").toString();
        if (row.systemError.isEmpty()) {
            text.append("  system DNS: ").append(joined(row.systemAddresses)).append('\n');
        } else {
            text.append("  system DNS: FAILED (").append(row.systemError).append(")\n");
        }
        if (!row.customActive) {
            text.append("  custom DNS: off\n");
        } else if (row.customAddresses.isEmpty()) {
            text.append("  custom DNS: no answer (").append(row.customError).append("), the system resolver is used\n");
        } else {
            text.append("  custom DNS: ").append(joined(row.customAddresses)).append(" (").append(row.customMethod)
                    .append(", ").append(row.customMs).append(" ms)\n");
        }
        if (row.httpStatus > 0) {
            text.append("  HTTPS HEAD: status ").append(row.httpStatus).append(", ").append(row.httpMs).append(" ms, ")
                    .append(verdict(row).name().toLowerCase(Locale.ROOT)).append('\n');
        } else {
            text.append("  HTTPS HEAD: FAILED (").append(row.httpError).append("), ").append(row.httpMs).append(" ms\n");
        }
        return text.append('\n').toString();
    }

    /**
     * The whole report for "Copy". Keys, tokens and the like are not in it: only names, addresses, codes and times.
     *
     * @param when    The time of the check, already formatted.
     * @param dnsPath Which DNS is used, e.g. "auto: DoH https://..., then UDP 1.1.1.1".
     * @param country The country of the IP, or "" if the trace did not answer.
     */
    public static String report(String when, String dnsPath, String country, List<Row> rows, boolean cancelled) {
        StringBuilder text = new StringBuilder("Arsound network diagnostics, ").append(when).append('\n');
        text.append("DNS: ").append(dnsPath).append('\n');
        text.append("Country: ").append(country.isEmpty() ? "unknown (the trace did not answer)" : country).append('\n');
        Tally tally = tally(rows);
        text.append("Result: ").append(tally.checked).append(" checked, ").append(tally.failed).append(" failed, ")
                .append(tally.slow).append(" slow\n");
        if (cancelled) text.append("Cancelled: some sites were not checked\n");
        text.append('\n');
        for (Row row : rows) {
            if (row != null) text.append(rowText(row));
        }
        return text.toString();
    }

    /**
     * The DoH server as the report shows it: scheme and host only. The path of a DoH address may carry a key or a token,
     * so it is never copied; a string that is not a parsable address gives "custom DoH".
     */
    public static String dohOrigin(String dohUrl) {
        if (dohUrl == null) return "custom DoH";
        try {
            URI uri = new URI(dohUrl.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) return "custom DoH";
            return scheme + "://" + host;
        } catch (URISyntaxException e) {
            return "custom DoH";
        }
    }

    /** The DNS path line of the report: what the app does with a name. */
    public static String describeDnsPath(boolean customDnsOn, String mode, String dohUrl, String servers) {
        if (!customDnsOn) return "system resolver (custom DNS is off)";
        if (DnsModes.SYSTEM.equals(mode)) return "system resolver (mode: phone DNS only)";
        if (DnsModes.DOH.equals(mode)) return "DoH " + dohOrigin(dohUrl);
        if (DnsModes.PLAIN.equals(mode)) return "UDP " + servers;
        return "auto: " + (dohUrl.isEmpty() ? "no DoH" : "DoH " + dohOrigin(dohUrl)) + ", then UDP " + servers;
    }
}
