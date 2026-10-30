package app.revanced.extension.soundcloud.transfer;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import app.revanced.extension.shared.Logger;

/**
 * Yandex's own sign-in page, asking for access to Yandex Music the way its apps do. Yandex then opens
 * {@code music.yandex.ru/#access_token=…}: that address is not loaded, the token in it is kept for reading the
 * account's likes and playlists. Arsound never sees the password: it is typed into Yandex's page.
 */
@SuppressWarnings("unused")
public final class YandexLoginActivity extends Activity {
    /** The client of the Yandex Music apps: only it gets a token the music API takes. */
    private static final String START_URL = "https://oauth.yandex.ru/authorize?response_type=token"
            + "&client_id=23cabbbdc6cd418abb4b39c32c41195d";

    private WebView web;
    private boolean done;

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, YandexLoginActivity.class);
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the Yandex sign-in", ex);
        }
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
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            web.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return takeToken(request.getUrl());
                }

                @Override
                public void onPageStarted(WebView view, String url, Bitmap favicon) {
                    if (url != null && takeToken(Uri.parse(url))) view.stopLoading();
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    // Not consumed, the framework would take the whole app down with the renderer.
                    Logger.printInfo(() -> "Yandex login WebView render process gone");
                    try {
                        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                        view.destroy();
                    } catch (RuntimeException ex) {
                        Logger.printException(() -> "Could not close the crashed sign-in page", ex);
                    }
                    if (web == view) web = null;
                    Toast.makeText(YandexLoginActivity.this,
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

    /** @return True if the address carried the token (or a refusal) and the page must not be opened. */
    private boolean takeToken(Uri url) {
        String fragment = url.getEncodedFragment();
        if (done || fragment == null || !fragment.contains("access_token=") && !fragment.contains("error=")) return false;
        done = true;
        Uri values = Uri.parse("arsound://token?" + fragment);
        String token = values.getQueryParameter("access_token");
        if (token == null || token.isEmpty()) {
            Logger.printInfo(() -> "Yandex sign-in refused: " + values.getQueryParameter("error"));
            Toast.makeText(this, text("Яндекс не дал доступ", "Yandex did not grant access"), Toast.LENGTH_SHORT).show();
            finish();
            return true;
        }
        long expiresIn = 0;
        try {
            expiresIn = Long.parseLong(values.getQueryParameter("expires_in"));
        } catch (NumberFormatException ignored) {
            // Then it is kept until Yandex refuses it.
        }
        YandexAccount.save(token, expiresIn);
        CookieManager.getInstance().flush();
        Toast.makeText(this, text("Вход в Яндекс выполнен", "Signed in to Yandex"), Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
        return true;
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
