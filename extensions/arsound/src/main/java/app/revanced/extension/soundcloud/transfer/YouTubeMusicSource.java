package app.revanced.extension.soundcloud.transfer;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.search.YouTubeAccount;
import app.revanced.extension.soundcloud.search.YouTubeLoginActivity;

/**
 * YouTube Music, read as its web client reads it (InnerTube "browse", WEB_REMIX). A public playlist or album needs no
 * account; the liked music and the library's playlists need the YouTube Music sign-in of the Arsound search
 * ({@link YouTubeAccount}): the same account, its cookies sent only to music.youtube.com.
 */
final class YouTubeMusicSource extends TransferSource {
    static final YouTubeMusicSource INSTANCE = new YouTubeMusicSource();

    private static final String ORIGIN = "https://music.youtube.com";
    private static final String BROWSE = ORIGIN + "/youtubei/v1/browse?prettyPrint=false";
    /** The version of the web client; YouTube takes older ones for a long time, as the search's player does. */
    private static final String CLIENT_VERSION = "1.20260121.03.00";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private static final int MAX_PAGES = 200;

    private YouTubeMusicSource() {
    }

    @Override
    public String id() {
        return "ytmusic";
    }

    @Override
    public String name() {
        return "YouTube Music";
    }

    @Override
    public String fromName() {
        return text("из YouTube Music", "from YouTube Music");
    }

    @Override
    public TransferLink find(String text) {
        return YouTubeMusicLink.find(text);
    }

    @Override
    TransferLink fromKey(String key) {
        return YouTubeMusicLink.fromKey(key);
    }

    static String likesTitle() {
        return text("Понравившиеся из YouTube Music", "Liked on YouTube Music");
    }

    @Override
    SourceCollection load(TransferLink link) throws Exception {
        SourceLink source = (SourceLink) link;
        String cookies = YouTubeAccount.sessionCookies();
        if (YouTubeMusicLink.LIKES.equals(source.kind)) {
            if (cookies == null) throw new SignInRequiredException("YouTube Music likes need the sign-in");
            SourceCollection likes = read("FEmusic_liked_videos", cookies);
            return new SourceCollection(likesTitle(), likes.tracks);
        }
        try {
            return read("VL" + source.id, cookies);
        } catch (SignInExpiredException | NotAvailableException ex) {
            // A public list is read without the account when the account is no longer taken.
            if (cookies == null) throw ex;
            return read("VL" + source.id, null);
        }
    }

    /** All the pages of a list. */
    private static SourceCollection read(String browseId, String cookies) throws Exception {
        JSONObject first = browse(new JSONObject().put("browseId", browseId), cookies);
        YouTubeMusicParser.TrackPage page = YouTubeMusicParser.firstPage(first);
        if (!page.hasList) throw new NotAvailableException("No list in the answer for " + browseId);
        List<SourceTrack> tracks = new ArrayList<>(page.tracks);
        String next = page.continuation;
        for (int pages = 1; next != null && pages < MAX_PAGES; pages++) {
            YouTubeMusicParser.TrackPage more = YouTubeMusicParser.nextPage(
                    browse(new JSONObject().put("continuation", next), cookies));
            tracks.addAll(more.tracks);
            // The same token again would read the same page forever.
            next = next.equals(more.continuation) ? null : more.continuation;
        }
        String title = page.title.isEmpty() ? text("Плейлист из YouTube Music", "YouTube Music playlist") : page.title;
        return new SourceCollection(title, tracks);
    }

    /**
     * One "browse" request. With cookies it is the account's: an answer given to nobody signed in means the session
     * is no longer taken.
     */
    private static JSONObject browse(JSONObject request, String cookies) throws Exception {
        request.put("context", new JSONObject().put("client", new JSONObject()
                .put("clientName", "WEB_REMIX").put("clientVersion", CLIENT_VERSION).put("hl", "en")));
        Map<String, String> headers = new HashMap<>();
        headers.put("Origin", ORIGIN);
        headers.put("X-Origin", ORIGIN);
        headers.put("Referer", ORIGIN + "/");
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", "application/json");
        if (cookies != null) {
            headers.put("Cookie", cookies);
            headers.put("X-Goog-AuthUser", "0");
            headers.put("Authorization", YouTubeAccount.authorization(cookies, ORIGIN));
        }
        String body;
        try {
            body = SourceHttp.post(BROWSE, headers, "application/json", request.toString());
        } catch (SourceHttp.StatusException ex) {
            if (cookies != null && ex.code == 401) throw new SignInExpiredException(ex.getMessage());
            // 403 is also YouTube's check for bots, with a session that is fine: not "sign in again", not "private".
            if (ex.code == 403 || ex.code >= 300 && ex.code < 400) throw new java.io.IOException(ex.getMessage());
            throw new NotAvailableException(ex.getMessage());
        }
        JSONObject response = new JSONObject(body);
        if (cookies != null && Boolean.FALSE.equals(YouTubeMusicParser.loggedIn(response))) {
            throw new SignInExpiredException("YouTube Music answered as to nobody signed in");
        }
        return response;
    }

    @Override
    String notAvailable(TransferLink link) {
        return text("YouTube Music не отдал этот плейлист: он закрыт или удалён. Свои закрытые плейлисты видны после "
                        + "входа в YouTube Music на этом экране.",
                "YouTube Music did not give this playlist: it is private or removed. Your own private playlists show "
                        + "after signing in to YouTube Music on this screen.");
    }

    @Override
    public boolean isSignedIn() {
        return YouTubeAccount.isSignedIn();
    }

    @Override
    public String accountName() {
        return "";
    }

    @Override
    public void signIn(Context context) {
        YouTubeLoginActivity.start(context);
    }

    @Override
    public void signOut() {
        YouTubeAccount.signOut();
    }

    @Override
    public String signInTitle() {
        return text("Войти в YouTube Music", "Sign in to YouTube Music");
    }

    @Override
    public String signInNote() {
        return text("Для понравившихся и своих плейлистов: после входа они появятся здесь списком. Это тот же вход, "
                        + "что в поиске Arsound. Пароль вводится на странице Google, Arsound его не видит; сессия "
                        + "хранится только на этом телефоне и отправляется только в YouTube.",
                "For your liked music and own playlists: after signing in they are listed here. It is the same "
                        + "sign-in as in the Arsound search. The password is typed into Google's page; Arsound does "
                        + "not see it. The session stays on this phone and goes only to YouTube.");
    }

    @Override
    public String signOutTitle() {
        return text("Выйти из YouTube Music", "Sign out of YouTube Music");
    }

    @Override
    public List<SourceList> library() throws Exception {
        String cookies = YouTubeAccount.sessionCookies();
        if (cookies == null) return null;
        List<SourceList> lists = new ArrayList<>();
        lists.add(new SourceList(YouTubeMusicLink.likes(), text("Понравившиеся", "Liked music"),
                SourceList.UNKNOWN_COUNT, true));
        try {
            JSONObject answer = browse(new JSONObject().put("browseId", "FEmusic_liked_playlists"), cookies);
            for (int pages = 0; answer != null && pages < MAX_PAGES; pages++) {
                YouTubeMusicParser.PlaylistPage page = YouTubeMusicParser.playlists(answer);
                for (String[] playlist : page.playlists) {
                    // Digits only, as the parser keeps them; more than nine would not fit and no playlist is that long.
                    int count = playlist[2].isEmpty() || playlist[2].length() > 9 ? SourceList.UNKNOWN_COUNT
                            : Integer.parseInt(playlist[2]);
                    lists.add(new SourceList(YouTubeMusicLink.playlist(playlist[0]), playlist[1], count, false));
                }
                answer = page.continuation == null ? null
                        : browse(new JSONObject().put("continuation", page.continuation), cookies);
            }
        } catch (SignInExpiredException ex) {
            // The search's sign-in is the same: it is left to the search to forget, the user is told to sign in again.
            Logger.printInfo(() -> "Transfer: the YouTube Music session is not taken: " + ex.getMessage());
            return null;
        }
        return lists;
    }
}
