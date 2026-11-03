package app.revanced.extension.soundcloud.wallpaper;

import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The plain decisions of the Wikimedia Commons source that need no Android and no network: which pictures are kept and in
 * which order, which address is fetched, what the credit line says. The JVM tests pin them, because the filters decide
 * what the user sees and the host check decides what the app may fetch.
 */
public final class CommonsRules {
    /** The sets the user can choose from: pictures the Commons community marked as the best. */
    public static final String FEATURED = "featured", QUALITY = "quality";
    /** The sets in the order the screen offers them. */
    public static final String[] SETS = {FEATURED, QUALITY};
    /**
     * The set a search starts with. Quality images are far more (for "space" 390 against 37 featured ones, for "nature"
     * about 24 000 against some hundreds), so a word finds something; the featured ones are one tap away.
     */
    public static final String DEFAULT_SET = QUALITY;

    /** A picture is tall when it is at least this many times higher than wide: it then fills a phone held upright. */
    public static final float TALL_RATIO = 1.3f;
    /**
     * Tall pictures are asked for between these widths and from this height. The top of the width keeps out the giant
     * scans whose only use here would be a long download; the floor of the height is a phone's screen.
     */
    public static final int TALL_MIN_WIDTH = WallhavenRules.TALL_MIN_WIDTH, TALL_MAX_WIDTH = 2400, TALL_MIN_HEIGHT = 1920;
    /**
     * The size of the thumbnail of the grid, one of the widths the Wikimedia servers keep ready. A cell of a grid of three
     * columns is about 320 pixels wide on a phone of 1080 pixels, so 330 is enough to fill it; a wider thumbnail only
     * makes each picture of the thumbnail cache bigger and the cache holds fewer of them (500 pixels: about 32).
     */
    public static final int THUMB_WIDTH = 330;
    /** A page of results; the service allows 500, but a page is also a wait, and the grid asks for the next one. */
    public static final int PAGE_SIZE = 30;
    /** The search service lists at most 10 000 results; 100 pages of 30 is far beyond what anyone scrolls. */
    public static final int MAX_PAGES = 100;
    /** The longest search text sent, in characters. */
    static final int MAX_TEXT = 100;
    /** The longest author and description shown or kept, in characters. */
    static final int MAX_AUTHOR = 120, MAX_DESCRIPTION = 160;

    private static final String IMAGE_HOST = "upload.wikimedia.org", THUMB_HOST = "thumb.wikimedia.org";
    private static final String PAGE_HOST = "commons.wikimedia.org";

    private CommonsRules() {
    }

    // region Sets and filters

    /** The set to use: a known one, else the default. */
    public static String validSet(String set) {
        return FEATURED.equals(set) ? FEATURED : DEFAULT_SET;
    }

    /** The name of the Commons category a set is. */
    static String category(String set) {
        return FEATURED.equals(set) ? "Featured pictures on Wikimedia Commons" : "Quality images";
    }

    /** The filters that differ from the usual, for the number on the filter button: only the shape is left to choose. */
    public static int activeFilters(boolean portrait) {
        return portrait ? 0 : 1;
    }

    /** True for a picture high enough above its width to fill a phone held upright. */
    public static boolean isTall(int width, int height) {
        return width > 0 && height * 10L >= width * (long) Math.round(TALL_RATIO * 10);
    }

    /** True for the kinds of picture the phone can use as a wallpaper without surprises. */
    static boolean isUsableMime(String mime) {
        return "image/jpeg".equals(mime) || "image/png".equals(mime);
    }

    /**
     * Whether a picture of this size belongs to the stage of the search. The service filters by size, but it cannot filter
     * by shape, so the shape is decided here.
     */
    static boolean keeps(WallhavenRules.Shape shape, int width, int height) {
        switch (shape) {
            case TALL:
                return width >= TALL_MIN_WIDTH && height >= TALL_MIN_HEIGHT && isTall(width, height);
            case WIDE_FOR_PHONE:
                return width >= WallhavenRules.WIDE_FOR_PHONE_MIN_WIDTH && height >= WallhavenRules.WIDE_FOR_PHONE_MIN_HEIGHT;
            default:
                return width >= WallhavenRules.WIDE_MIN_WIDTH && height >= WallhavenRules.WIDE_MIN_HEIGHT && width > height;
        }
    }

    /**
     * The order of a page: tall pictures first, then the others, each group in the order the service gave. The big
     * pictures that follow the tall ones can be of any shape, and the ones that fill a phone belong in front.
     *
     * @return The indexes of the pictures in their new order.
     */
    static int[] tallFirstOrder(int[] widths, int[] heights) {
        int count = widths.length;
        int[] order = new int[count];
        int next = 0;
        for (int i = 0; i < count; i++) if (isTall(widths[i], heights[i])) order[next++] = i;
        for (int i = 0; i < count; i++) if (!isTall(widths[i], heights[i])) order[next++] = i;
        return order;
    }

    // endregion

    // region Search text

    /**
     * The text of a search made safe to put in front of the filters: letters, digits and the apostrophe and hyphen inside
     * a word are kept, everything else becomes a space, and the case is dropped. A search of Commons knows quotes, colons
     * and the words OR and NOT; typed by hand they could widen the search beyond the filters that keep it to the chosen
     * sets, and Commons has no safe search of its own.
     */
    static String cleanText(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder(Math.min(text.length(), MAX_TEXT));
        boolean inWord = false;
        for (int i = 0; i < text.length() && out.length() < MAX_TEXT; i++) {
            char c = text.charAt(i);
            // A hyphen or an apostrophe is kept only inside a word: at the start of one it would make a "not" of it.
            boolean joiner = inWord && (c == '\'' || c == '-' || c == '’');
            if (Character.isLetterOrDigit(c) || joiner) {
                out.append(c == '’' ? '\'' : Character.toLowerCase(c));
                inWord = true;
            } else if (inWord) {
                out.append(' ');
                inWord = false;
            }
        }
        return out.toString().trim();
    }

    // endregion

    // region Unwanted content

    /**
     * Words that keep a picture out. Commons has no safe search and even its best sets (quality images, featured
     * pictures) hold nudes, so the title and the categories of every file are read for these (whole words only, in the
     * lower case). The list is English because the categories of Commons are; the search itself also leaves out the
     * category "Nudity" ({@code CommonsRequest}), but that only reaches part of its subcategories.
     */
    private static final Set<String> UNWANTED_WORDS = new HashSet<>(Arrays.asList(
            "nude", "nudes", "nudity", "nudism", "nudist", "nudists", "naked", "topless", "erotic", "erotica", "porn",
            "pornography", "pornographic", "pornstar", "pornstars", "fetish", "sexual", "sexy", "lingerie", "bdsm", "genital",
            "genitals", "penis", "vagina", "vulva", "breast", "breasts", "nipple", "nipples", "buttocks"));

    /**
     * True when the text holds one of the unwanted words as a whole word, in any case. A word ends at anything that is not
     * a letter or a digit, so "Nude women" and "File:Nude_beach.jpg" are caught and "Sussex", "Essex" or "Sexton" are not.
     */
    static boolean hasUnwantedWord(String text) {
        if (text == null) return false;
        int start = -1;
        for (int i = 0; i <= text.length(); i++) {
            boolean inWord = i < text.length() && Character.isLetterOrDigit(text.charAt(i));
            if (inWord) {
                if (start < 0) start = i;
            } else if (start >= 0) {
                if (UNWANTED_WORDS.contains(text.substring(start, i).toLowerCase(Locale.ROOT))) return true;
                start = -1;
            }
        }
        return false;
    }

    // endregion

    // region Pictures

    /** True for an address of a picture on the Wikimedia servers (the originals and the thumbnails), over https. */
    static boolean isImageHost(String address) {
        return hostIs(address, IMAGE_HOST, THUMB_HOST);
    }

    /** True for an address of a file's description page on Commons, over https. */
    static boolean isPageUrl(String address) {
        return hostIs(address, PAGE_HOST);
    }

    private static boolean hostIs(String address, String... hosts) {
        if (address == null) return false;
        try {
            URL url = new URL(address);
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null) return false;
            if (url.getPort() != -1 && url.getPort() != 443) return false;
            String host = url.getHost().toLowerCase(Locale.ROOT);
            for (String allowed : hosts) if (allowed.equals(host)) return true;
            return false;
        } catch (Exception ex) {
            return false;
        }
    }

    /** The address without its query: the service adds tracking words to the addresses it hands out. */
    static String stripQuery(String address) {
        if (address == null) return "";
        int cut = address.indexOf('?');
        if (cut >= 0) address = address.substring(0, cut);
        int fragment = address.indexOf('#');
        return fragment >= 0 ? address.substring(0, fragment) : address;
    }

    /**
     * The width of the copy of a picture that is downloaded to look at and to set. A tall picture fills a phone held
     * upright, so 1920 pixels of width are plenty (0, the original itself, when it is not wider than that: a copy would
     * only be a blur of it). A wide or square picture shows only its middle on a phone, so it needs the pixels: 3840 when
     * it is wider than that, else the original. The widths are the ones the Wikimedia servers keep ready.
     */
    static int fullWidth(int width, int height) {
        if (isTall(width, height)) return width > 1920 ? 1920 : 0;
        return width > 3840 ? 3840 : 0;
    }

    /**
     * The address of the picture to download: the thumbnail address with its width put to {@link #fullWidth}, or the
     * original. The thumbnail address is of the form {@code .../<width>px-<file name>}; one that is not falls back to the
     * original.
     */
    static String fullUrl(String thumbUrl, String originalUrl, int width, int height) {
        String original = stripQuery(originalUrl);
        int target = fullWidth(width, height);
        if (target == 0) return original;
        String thumb = stripQuery(thumbUrl);
        int slash = thumb.lastIndexOf('/');
        if (slash < 0) return original;
        String last = thumb.substring(slash + 1);
        int marker = last.indexOf("px-");
        if (marker <= 0) return original;
        for (int i = 0; i < marker; i++) if (!Character.isDigit(last.charAt(i))) return original;
        return thumb.substring(0, slash + 1) + target + last.substring(marker);
    }

    /**
     * A guess of how many bytes the copy of {@link #fullWidth} has, from the original's size: the bytes of a photograph
     * follow its pixels. 0 when the original's size is not known.
     */
    static long estimateBytes(long originalBytes, int width, int fullWidth) {
        if (originalBytes <= 0) return 0;
        if (fullWidth <= 0 || width <= 0 || fullWidth >= width) return originalBytes;
        double scale = (double) fullWidth / width;
        return Math.max(1, Math.round(originalBytes * scale * scale));
    }

    // endregion

    // region Credit

    /** Elements that have no closing tag. */
    private static final Set<String> VOID_ELEMENTS = new HashSet<>(Arrays.asList(
            "br", "hr", "img", "input", "wbr", "meta", "link", "area", "col", "embed", "source", "track", "param"));

    /**
     * Plain text from the HTML Commons keeps its descriptions and author names in: the elements nobody sees are dropped
     * with what is inside them (scripts, styles, and whatever hides itself with {@code display:none} or the class
     * "hidden": Commons keeps such notes for machines inside the visible text), the other tags are dropped (a line break
     * or a paragraph becomes a space), the common entities are decoded, spaces are gathered, and a text that is too long
     * is cut at a word with an ellipsis.
     */
    static String stripHtml(String html, int max) {
        if (html == null || html.isEmpty()) return "";
        return shorten(plainText(visibleHtml(html)), max);
    }

    /**
     * The author as the credit line says it. The Artist field of Commons is HTML: usually the name as a link to the user's
     * page, but sometimes a whole sentence around the link ("Designed by ..., issued by ..."). The text of the first link
     * is the name, so it is preferred; with no link (or an empty one) the whole text is taken. Cut to
     * {@link #MAX_AUTHOR} at a word.
     */
    static String author(String artistHtml) {
        if (artistHtml == null || artistHtml.isEmpty()) return "";
        String visible = visibleHtml(artistHtml);
        String linked = plainText(firstLink(visible));
        return shorten(linked.isEmpty() ? plainText(visible) : linked, MAX_AUTHOR);
    }

    /** The HTML of the first link (opening tag, content and closing tag), or "" when there is none. */
    private static String firstLink(String html) {
        int i = 0, n = html.length();
        while (i < n) {
            int start = html.indexOf('<', i);
            if (start < 0) break;
            int end = html.indexOf('>', start);
            if (end < 0) break;
            if (html.charAt(start + 1) != '/' && tagName(html, start + 1, end).equals("a")) {
                return html.substring(start, afterElement(html, "a", end + 1));
            }
            i = end + 1;
        }
        return "";
    }

    /** The HTML without the elements that are not seen: scripts, styles and whatever {@link #hides} says. */
    private static String visibleHtml(String html) {
        StringBuilder out = new StringBuilder(html.length());
        int i = 0, n = html.length();
        while (i < n) {
            char c = html.charAt(i);
            if (c != '<') {
                out.append(c);
                i++;
                continue;
            }
            int end = html.indexOf('>', i);
            if (end < 0) break;
            boolean closing = i + 1 < n && html.charAt(i + 1) == '/';
            String tag = tagName(html, i + 1, end);
            if (!closing && !tag.isEmpty()) {
                String opening = html.substring(i, end + 1);
                boolean raw = tag.equals("script") || tag.equals("style");
                if (raw || hides(opening)) {
                    // A tag that has no end takes nothing with it.
                    boolean alone = VOID_ELEMENTS.contains(tag) || html.charAt(end - 1) == '/';
                    i = alone ? end + 1 : raw ? afterRawElement(html, tag, end + 1) : afterElement(html, tag, end + 1);
                    continue;
                }
            }
            out.append(html, i, end + 1);
            i = end + 1;
        }
        return out.toString();
    }

    /**
     * The index after the closing tag of the element whose opening tag ends just before {@code from}. Elements of the same
     * name inside it are counted, so a hidden {@code div} with a {@code div} in it goes whole. The end of the text when it
     * never closes: a browser hides the rest of the text in that case too.
     */
    private static int afterElement(String html, String tag, int from) {
        int depth = 1, n = html.length(), i = from;
        while (i < n) {
            int open = html.indexOf('<', i);
            if (open < 0) return n;
            int end = html.indexOf('>', open);
            if (end < 0) return n;
            if (tagName(html, open + 1, end).equals(tag)) {
                if (open + 1 < n && html.charAt(open + 1) == '/') {
                    if (--depth == 0) return end + 1;
                } else if (html.charAt(end - 1) != '/') {
                    depth++;
                }
            }
            i = end + 1;
        }
        return n;
    }

    /**
     * Like {@link #afterElement} for a script or a style, whose content is text and may hold a {@code <} that is no tag: only
     * the closing tag counts.
     */
    private static int afterRawElement(String html, String tag, int from) {
        for (int i = html.indexOf("</", from); i >= 0; i = html.indexOf("</", i + 2)) {
            if (!html.regionMatches(true, i + 2, tag, 0, tag.length())) continue;
            int end = html.indexOf('>', i);
            return end < 0 ? html.length() : end + 1;
        }
        return html.length();
    }

    /** True when the opening tag hides its element: {@code display:none} (spaces allowed) in its style, or the class "hidden". */
    private static boolean hides(String openingTag) {
        String style = attribute(openingTag, "style");
        if (style != null && style.replaceAll("\\s+", "").toLowerCase(Locale.ROOT).contains("display:none")) return true;
        String classes = attribute(openingTag, "class");
        if (classes == null) return false;
        for (String name : classes.trim().split("\\s+")) if (name.equalsIgnoreCase("hidden")) return true;
        return false;
    }

    /** The value of an attribute of an opening tag, written in double quotes, single quotes or none; null when it has none. */
    private static String attribute(String tag, String wanted) {
        int n = tag.length(), i = 1;
        while (i < n && !Character.isWhitespace(tag.charAt(i)) && tag.charAt(i) != '>' && tag.charAt(i) != '/') i++;
        while (i < n) {
            while (i < n && (Character.isWhitespace(tag.charAt(i)) || tag.charAt(i) == '/')) i++;
            int nameStart = i;
            while (i < n && !Character.isWhitespace(tag.charAt(i)) && tag.charAt(i) != '=' && tag.charAt(i) != '>'
                    && tag.charAt(i) != '/') {
                i++;
            }
            if (i == nameStart) {
                i++;
                continue;
            }
            String name = tag.substring(nameStart, i);
            while (i < n && Character.isWhitespace(tag.charAt(i))) i++;
            String value = "";
            if (i < n && tag.charAt(i) == '=') {
                i++;
                while (i < n && Character.isWhitespace(tag.charAt(i))) i++;
                if (i < n && (tag.charAt(i) == '"' || tag.charAt(i) == '\'')) {
                    char quote = tag.charAt(i++);
                    int close = tag.indexOf(quote, i);
                    if (close < 0) close = n;
                    value = tag.substring(i, close);
                    i = Math.min(n, close + 1);
                } else {
                    int valueStart = i;
                    while (i < n && !Character.isWhitespace(tag.charAt(i)) && tag.charAt(i) != '>') i++;
                    value = tag.substring(valueStart, i);
                }
            }
            if (name.equalsIgnoreCase(wanted)) return value;
        }
        return null;
    }

    /** The text of some HTML: tags dropped (a line break or a paragraph becomes a space), entities decoded, spaces gathered. */
    private static String plainText(String html) {
        StringBuilder text = new StringBuilder(html.length());
        int i = 0, n = html.length();
        while (i < n) {
            char c = html.charAt(i);
            if (c != '<') {
                text.append(c);
                i++;
                continue;
            }
            int end = html.indexOf('>', i);
            if (end < 0) break;
            String tag = tagName(html, i + 1, end);
            if (tag.equals("br") || tag.equals("p") || tag.equals("div") || tag.equals("li") || tag.equals("tr")
                    || tag.equals("td") || tag.equals("dd") || tag.equals("dt")) {
                text.append(' ');
            }
            i = end + 1;
        }
        return gatherSpaces(decodeEntities(text.toString()));
    }

    private static String tagName(String html, int from, int to) {
        int start = from;
        if (start < to && html.charAt(start) == '/') start++;
        int stop = start;
        while (stop < to && Character.isLetterOrDigit(html.charAt(stop))) stop++;
        return html.substring(start, stop).toLowerCase(Locale.ROOT);
    }

    private static String decodeEntities(String text) {
        if (text.indexOf('&') < 0) return text;
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '&') {
                out.append(c);
                continue;
            }
            int semicolon = text.indexOf(';', i + 1);
            String decoded = semicolon < 0 || semicolon - i > 10 ? null : entity(text.substring(i + 1, semicolon));
            if (decoded == null) {
                out.append(c);
                continue;
            }
            out.append(decoded);
            i = semicolon;
        }
        return out.toString();
    }

    private static String entity(String name) {
        switch (name) {
            case "amp":
                return "&";
            case "lt":
                return "<";
            case "gt":
                return ">";
            case "quot":
                return "\"";
            case "apos":
                return "'";
            case "nbsp":
                return " ";
            case "copy":
                return "©";
            case "ndash":
                return "–";
            case "mdash":
                return "—";
            case "hellip":
                return "…";
            case "laquo":
                return "«";
            case "raquo":
                return "»";
            case "middot":
                return "·";
            default:
                break;
        }
        if (name.length() < 2 || name.charAt(0) != '#') return null;
        try {
            boolean hex = name.charAt(1) == 'x' || name.charAt(1) == 'X';
            int code = hex ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
            return code > 0 && Character.isValidCodePoint(code) ? new String(Character.toChars(code)) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String gatherSpaces(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean space = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            // The no-break space is not "whitespace" to Java, but it is to the reader.
            if (Character.isWhitespace(c) || c == '\u00a0') {
                space = out.length() > 0;
            } else {
                if (space) out.append(' ');
                space = false;
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * The text cut to at most {@code max} characters with an ellipsis. The cut is at a space when there is one in the
     * second half of what is kept (a name cut in the middle of a word reads as a mistake), with the comma or dash left
     * before it dropped; a text of one long word is cut where the room ends, not in the middle of a pair of surrogates.
     */
    static String shorten(String text, int max) {
        if (text.length() <= max) return text;
        int cut = Math.max(1, max - 1);
        int space = text.lastIndexOf(' ', cut);
        if (space >= cut / 2 && space > 0) {
            cut = space;
        } else if (Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        String kept = text.substring(0, cut);
        int end = kept.length();
        while (end > 1 && " ,;:-–—/(".indexOf(kept.charAt(end - 1)) >= 0) end--;
        return kept.substring(0, end).trim() + "…";
    }

    /**
     * The licence as the credit line says it: the public domain and CC0 in words ("Общественное достояние"), any other
     * licence as Commons names it ("CC BY-SA 4.0"). Empty when Commons gave none.
     */
    static String licenceLabel(String shortName, boolean russian) {
        String name = stripHtml(shortName, 40);
        if (name.isEmpty()) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        boolean freeOfRights = lower.startsWith("public domain") || lower.startsWith("cc0") || lower.startsWith("cc-zero")
                || lower.equals("pd") || lower.startsWith("pd-") || lower.startsWith("pd ");
        if (freeOfRights) return russian ? "Общественное достояние" : "Public domain";
        return name;
    }

    /** "Author · Licence · Wikimedia Commons": what the preview shows under the picture. */
    static String creditLine(String author, String licence, boolean russian) {
        StringBuilder line = new StringBuilder();
        line.append(author == null || author.isEmpty() ? (russian ? "Автор не указан" : "Author not given") : author);
        String label = licenceLabel(licence, russian);
        if (!label.isEmpty()) line.append(" · ").append(label);
        return line.append(" · Wikimedia Commons").toString();
    }

    // endregion
}
