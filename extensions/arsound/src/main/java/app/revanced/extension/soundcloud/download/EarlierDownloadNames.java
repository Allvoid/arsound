package app.revanced.extension.soundcloud.download;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The names earlier versions gave to downloaded files: "soundcloud-ID.m4a", "soundcloud-ID-ID.m4a" for a taken name,
 * and the media store's " (1)" before the extension. The track id is in the name, so such a file belongs to its
 * track without asking the network.
 */
final class EarlierDownloadNames {
    private static final Pattern ID_NAME = Pattern.compile("^soundcloud-(\\d+)(?:-\\d+)*(?: \\(\\d+\\))?\\.(?:m4a|mp3|aac|opus)$");
    private static final Pattern COPY_SUFFIX = Pattern.compile(" \\((\\d+)\\)(?=\\.[^.]*$)");

    private EarlierDownloadNames() {
    }

    /** The id of the track the file is named after; null for any other name. */
    static String trackIdOf(String fileName) {
        Matcher matcher = ID_NAME.matcher(fileName);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /** The n of " (n)" in a name the media store made for a taken one, 0 without it. */
    static int copyNumber(String fileName) {
        Matcher matcher = COPY_SUFFIX.matcher(fileName);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
