package app.revanced.extension.soundcloud.transfer;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.revanced.extension.shared.Logger;

/**
 * VK's music, read through VK's API with the access of its own website ({@link VkAccount}): "My music", the playlists
 * and albums of the account, and a playlist by its link. VK shows no music to anyone not signed in, so every read
 * needs the account.
 */
final class VkSource extends TransferSource {
    static final VkSource INSTANCE = new VkSource();

    /** The API version VK's website asks with. */
    private static final String API_VERSION = "5.246";
    private static final int PAGE = 200;
    private static final int MAX_PAGES = 100;
    private static final int PLAYLIST_PAGE = 100;
    private static final int RATE_LIMIT_TRIES = 4;

    private VkSource() {
    }

    @Override
    public String id() {
        return "vk";
    }

    @Override
    public String name() {
        return text("VK Музыка", "VK Music");
    }

    @Override
    public String fromName() {
        return text("из VK Музыки", "from VK Music");
    }

    @Override
    public TransferLink find(String text) {
        return VkLink.find(text);
    }

    @Override
    TransferLink fromKey(String key) {
        return VkLink.fromKey(key);
    }

    @Override
    public boolean needsSignInForLinks() {
        return true;
    }

    static String likesTitle() {
        return text("Моя музыка из VK", "My music on VK");
    }

    @Override
    SourceCollection load(TransferLink link) throws Exception {
        SourceLink vk = (SourceLink) link;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("owner_id", vk.owner);
        String title;
        if (VkLink.PLAYLIST.equals(vk.kind)) {
            params.put("playlist_id", vk.id);
            if (vk.accessKey != null) params.put("access_key", vk.accessKey);
            title = VkParser.playlistTitle(call("audio.getPlaylistById", new LinkedHashMap<>(params)));
            if (title.isEmpty()) title = text("Плейлист из VK", "VK playlist");
        } else {
            title = vk.owner.equals(VkAccount.userId()) ? likesTitle() : text("Музыка из VK", "Music from VK");
        }
        List<SourceTrack> tracks = new ArrayList<>();
        int total = -1;
        int offset = 0;
        for (int page = 0; page < MAX_PAGES; page++) {
            Map<String, String> pageParams = new LinkedHashMap<>(params);
            pageParams.put("count", String.valueOf(PAGE));
            pageParams.put("offset", String.valueOf(offset));
            VkParser.TrackPage read = VkParser.tracks(call("audio.get", pageParams));
            tracks.addAll(read.tracks);
            total = read.count;
            offset += read.items;
            if (read.items == 0 || offset >= read.count) break;
        }
        if (tracks.isEmpty() && total != 0) throw new NotAvailableException("VK gave no tracks of " + link);
        return new SourceCollection(title, tracks);
    }

    /**
     * One method of VK's API with the website's access. A refused access is asked for once again; VK's "too many
     * requests" waits and tries again.
     */
    static JSONObject call(String method, Map<String, String> params) throws Exception {
        String refused = null;
        for (int attempt = 0; ; attempt++) {
            String token = VkAccount.token(refused);
            StringBuilder form = new StringBuilder();
            for (Map.Entry<String, String> param : params.entrySet()) {
                form.append(param.getKey()).append('=').append(VkAccount.encode(param.getValue())).append('&');
            }
            form.append("access_token=").append(VkAccount.encode(token));
            String domain = VkAccount.domain();
            String url = "https://api.vk." + domain + "/method/" + method + "?v=" + API_VERSION
                    + "&client_id=" + VkAccount.WEB_APP_ID;
            JSONObject answer;
            try {
                answer = new JSONObject(SourceHttp.post(url, VkAccount.headers(domain),
                        "application/x-www-form-urlencoded", form.toString()));
            } catch (SourceHttp.StatusException ex) {
                throw new NotAvailableException("VK answered " + ex.getMessage());
            }
            int code = VkParser.errorCode(answer);
            if (code == 0) return answer;
            Logger.printInfo(() -> "VK " + method + ": error " + code);
            if (code == 5 && refused == null) {
                refused = token;
                continue;
            }
            if (code == 5) throw new SignInExpiredException("VK refused the access twice");
            if ((code == 6 || code == 9 || code == 29) && attempt < RATE_LIMIT_TRIES) {
                SourceHttp.pause(1_500L * (attempt + 1));
                continue;
            }
            // 15, 18, 30, 200, 201, 203: no access to it, a deleted page, a private list.
            if (code == 15 || code == 18 || code == 30 || code == 100 || code == 104 || code >= 200 && code < 300) {
                throw new NotAvailableException("VK error " + code + " for " + method);
            }
            throw new java.io.IOException("VK error " + code + " for " + method);
        }
    }

    @Override
    String notAvailable(TransferLink link) {
        return text("VK не отдал этот список: он закрыт, удалён или ссылка без ключа доступа. Откройте плейлист в VK, "
                        + "нажмите «Поделиться» → «Скопировать ссылку» и вставьте её целиком.",
                "VK did not give this list: it is private, removed, or the link has no access key. Open the playlist "
                        + "on VK, tap \"Share\" → \"Copy link\" and paste the whole link.");
    }

    @Override
    public boolean isSignedIn() {
        return VkAccount.isSignedIn();
    }

    @Override
    public String accountName() {
        return VkAccount.name();
    }

    @Override
    public void signIn(Context context) {
        VkLoginActivity.start(context);
    }

    @Override
    public void signOut() {
        VkAccount.signOut();
    }

    @Override
    public String signInTitle() {
        return text("Войти в VK", "Sign in to VK");
    }

    @Override
    public String signInNote() {
        return text("VK отдаёт музыку только после входа: и свою, и по ссылке. После входа «Моя музыка» и плейлисты "
                        + "появятся здесь списком. Пароль вводится на странице VK, Arsound его не видит; сессия "
                        + "хранится только на этом телефоне и отправляется только в VK.",
                "VK gives music only after signing in, your own and by link alike. After signing in, \"My music\" "
                        + "and the playlists are listed here. The password is typed into VK's page; Arsound does not "
                        + "see it. The session stays on this phone and goes only to VK.");
    }

    @Override
    public String signOutTitle() {
        return text("Выйти из VK", "Sign out of VK");
    }

    @Override
    public List<SourceList> library() throws Exception {
        if (!VkAccount.isSignedIn()) return null;
        String me = VkAccount.userId();
        try {
            readName(me);
            Map<String, String> count = new LinkedHashMap<>();
            count.put("owner_id", me);
            count.put("count", "1");
            VkParser.TrackPage mine = VkParser.tracks(call("audio.get", count));
            List<SourceList> lists = new ArrayList<>();
            lists.add(new SourceList(VkLink.audios(me), text("Моя музыка", "My music"), mine.count, true));
            for (int offset = 0, page = 0; page < MAX_PAGES; page++, offset += PLAYLIST_PAGE) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("owner_id", me);
                params.put("count", String.valueOf(PLAYLIST_PAGE));
                params.put("offset", String.valueOf(offset));
                JSONObject answer = call("audio.getPlaylists", params);
                List<VkParser.Playlist> playlists = VkParser.playlists(answer);
                for (VkParser.Playlist playlist : playlists) {
                    lists.add(new SourceList(VkLink.playlist(playlist.owner, playlist.id, playlist.accessKey),
                            playlist.title, playlist.count, false));
                }
                // By the items VK gave, not by those kept: one without a title must not end the list early.
                if (VkParser.itemCount(answer) < PLAYLIST_PAGE) break;
            }
            return lists;
        } catch (SignInExpiredException ex) {
            return null;
        }
    }

    /** The person's name, for the sign-out row; not having it is no failure. */
    private static void readName(String me) {
        if (!VkAccount.name().isEmpty()) return;
        try {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("user_ids", me);
            JSONArray people = call("users.get", params).optJSONArray("response");
            JSONObject person = people == null ? null : people.optJSONObject(0);
            if (person != null) {
                VkAccount.saveName((person.optString("first_name") + " " + person.optString("last_name")).trim());
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "VK: could not read the name: " + ex);
        }
    }
}
