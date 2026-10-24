package app.revanced.extension.soundcloud.search;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.Locale;

import app.revanced.extension.shared.Logger;

/**
 * Google's own sign-in page for YouTube Music. When it lands on YouTube, the session cookies are kept
 * for the Arsound search. Arsound never sees the password: it is typed into Google's page.
 */
@SuppressWarnings("unused")
public final class YouTubeLoginActivity extends Activity {
    private static final String START_URL = "https://accounts.google.com/ServiceLogin"
            + "?service=youtube&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F";

    private WebView web;

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, YouTubeLoginActivity.class);
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the YouTube sign-in", ex);
        }
    }

    private static String text(String russian, String english) {
        return "ru".equals(Locale.getDefault().getLanguage()) ? russian : english;
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            web = new WebView(this);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            // Google refuses sign-in in pages that call themselves an embedded browser ("; wv").
            settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    String host = url == null ? null : Uri.parse(url).getHost();
                    if (host == null || !host.endsWith("youtube.com")) return;
                    CookieManager.getInstance().flush();
                    if (YouTubeAccount.saveSession(null)) {
                        Toast.makeText(YouTubeLoginActivity.this,
                                text("Вход в YouTube Music выполнен", "Signed in to YouTube Music"), Toast.LENGTH_SHORT).show();
                        setResult(RESULT_OK);
                        finish();
                    }
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    // Not consumed, the framework would take the whole app down with the renderer.
                    Logger.printInfo(() -> "YouTube login WebView render process gone");
                    // A WebView whose renderer died cannot be used again: the screen closes, the next try starts fresh.
                    try {
                        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                        view.destroy();
                    } catch (RuntimeException ex) {
                        Logger.printException(() -> "Could not close the crashed sign-in page", ex);
                    }
                    if (web == view) web = null;
                    Toast.makeText(YouTubeLoginActivity.this,
                            text("Страница входа закрылась, попробуйте ещё раз", "The sign-in page closed, try again"),
                            Toast.LENGTH_SHORT).show();
                    finish();
                    return true;
                }
            });
            setContentView(web);
            web.loadUrl(START_URL);
        } catch (RuntimeException | LinkageError ex) {
            // No usable system WebView (disabled, removed, being updated): the screen closes, the app stays.
            Logger.printException(() -> "WebView is not available", ex);
            Toast.makeText(this, text("Нужен Android System WebView: он отключён или не установлен",
                    "Android System WebView is disabled or missing"), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
