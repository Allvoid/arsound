package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * The player's list of apps it shows, the secure setting "music_app_white_list": a JSON array with an object for each
 * app. The fields of the objects are the player's own and must not change. Without the setting the player uses a list
 * of its own, which {@link #builtIn} repeats.
 */
final class VivoPlayerList {
    private static final String LIST_KEY = "music_app_white_list";

    /**
     * The list of Origin Player 6.2, written together with Arsound when the setting is absent, so the player keeps
     * the apps it had. "Cooperate" apps have their own integration in the player, all of them use the lock screen.
     */
    private static final String BUILT_IN_PACKAGES = "com.android.bbkmusic com.tencent.qqmusic com.ximalaya.ting.android "
            + "com.kugou.android com.netease.cloudmusic bubei.tingshu cn.kuwo.player cmccwm.mobilemusic "
            + "com.kugou.android.lite com.kugou.android.elder app.podcast.cosmos fm.qingting.qtradio "
            + "com.yibasan.lizhifm cn.missevan com.luna.music com.hiby.music com.tencent.blackkey "
            + "com.vivo.newsreader com.tencent.qqmusiclite cn.wenyu.bodian com.tencent.weread com.ting.mp3.android "
            + "com.xs.fm com.xs.fm.lite com.ximalaya.ting.lite com.shinyv.cnr com.ormatch.android.asmr "
            + "com.luojilab.player com.kugou.android.tingshu com.tencent.radio com.audio.tingting com.spotify.music "
            + "com.kugou.viper com.baidu.netdisk com.vivo.agent com.vivo.base.agent com.apple.android.music "
            + "com.dragon.read com.kmxs.reader com.qidian.QDReader com.qq.reader com.jjwxc.reader "
            + "com.jd.app.reader bubei.tingshu.pro io.dushu.fandengreader com.larus.nova com.vivo.screenreader "
            + "com.vivo.ai.gptagent com.psyone.brainmusic com.ifeng.fhdt com.ximalaya.ting.kid com.vivo.ai.copilot "
            + "com.qiyi.video com.ss.android.ugc.aweme com.tencent.qqmusicpad tv.danmaku.bili com.shuqi.controller "
            + "com.salt.music com.tencent.mm";
    private static final String BUILT_IN_COOPERATE = " com.android.bbkmusic com.tencent.qqmusic "
            + "com.ximalaya.ting.android com.kugou.android com.netease.cloudmusic bubei.tingshu cn.kuwo.player "
            + "cmccwm.mobilemusic com.kugou.android.lite com.kugou.android.elder app.podcast.cosmos "
            + "fm.qingting.qtradio com.yibasan.lizhifm com.luna.music com.hiby.music com.tencent.blackkey com.xs.fm "
            + "com.xs.fm.lite com.apple.android.music com.spotify.music cn.missevan cn.wenyu.bodian ";
    private static final String BUILT_IN_NOT_ON_LOCK_SCREEN = " com.tencent.qqmusiclite com.ting.mp3.android "
            + "com.ximalaya.ting.lite com.ormatch.android.asmr com.tencent.radio com.audio.tingting com.baidu.netdisk "
            + "com.vivo.ai.copilot com.larus.nova com.psyone.brainmusic com.ifeng.fhdt com.ximalaya.ting.kid ";

    private static final Set<String> COOPERATE = words(BUILT_IN_COOPERATE);
    private static final Set<String> NOT_ON_LOCK_SCREEN = words(BUILT_IN_NOT_ON_LOCK_SCREEN);

    private VivoPlayerList() {
    }

    private static Set<String> words(String text) {
        return new HashSet<>(Arrays.asList(text.trim().split(" ")));
    }

    /** Whether the player gives an app of its built-in list its own integration ("cooperate"). */
    static boolean isCooperate(String packageName) {
        return COOPERATE.contains(packageName);
    }

    /** Whether an app of the built-in list shows on the lock screen. */
    static boolean showsOnLockScreen(String packageName) {
        return !NOT_ON_LOCK_SCREEN.contains(packageName);
    }

    /** The setting as the system holds it; null when it is absent. Throws a RuntimeException if Android refuses the read. */
    static String stored(Context context) {
        return Settings.Secure.getString(context.getContentResolver(), LIST_KEY);
    }

    /** Writes the setting. Needs WRITE_SECURE_SETTINGS. */
    static void store(Context context, JSONArray list) {
        Settings.Secure.putString(context.getContentResolver(), LIST_KEY, list.toString());
    }

    /** The list in the setting; the built-in one while the setting is absent, as the player itself reads it. */
    static JSONArray parse(String stored) throws JSONException {
        return stored != null ? new JSONArray(stored) : builtIn();
    }

    static boolean contains(JSONArray list, String packageName) {
        for (int i = 0; i < list.length(); i++) {
            JSONObject entry = list.optJSONObject(i);
            if (entry != null && packageName.equals(entry.optString("packageName"))) return true;
        }
        return false;
    }

    static JSONObject entry(String packageName, String appName, boolean cooperate, boolean lockScreen)
            throws JSONException {
        return new JSONObject()
                .put("packageName", packageName)
                .put("appName", appName)
                .put("isCooperate", cooperate ? 1 : 0)
                .put("showLockScreen", lockScreen ? 1 : 0)
                .put("dataStatus", 0);
    }

    /** The player's own list, with its package of the Chinese build. */
    static JSONArray builtIn() throws JSONException {
        JSONArray list = new JSONArray();
        for (String packageName : BUILT_IN_PACKAGES.split(" ")) {
            list.put(entry(packageName, "", isCooperate(packageName), showsOnLockScreen(packageName)));
        }
        return list;
    }
}
