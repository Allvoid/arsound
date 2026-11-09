package app.revanced.extension.soundcloud.transfer;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * VK's own sign-in page. After each page of VK, the session is looked for off the main thread ({@link
 * VkAccount#takeSession}); once there is one, the screen closes. Arsound never sees the password: it is typed into
 * VK's page, and only the page's cookies stay, in the app's own WebView storage.
 */
@SuppressWarnings("unused")
public final class VkLoginActivity extends Activity {
    private static final String START_URL = "https://m.vk.ru/login";

    private WebView web;
    private final AtomicBoolean checking = new AtomicBoolean();
    private volatile boolean done;

    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, VkLoginActivity.class);
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the VK sign-in", ex);
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
                public void onPageFinished(WebView view, String url) {
                    view.requestFocus(View.FOCUS_DOWN);
                    String host = url == null ? null : Uri.parse(url).getHost();
                    if (host != null && (host.endsWith("vk.ru") || host.endsWith("vk.com"))) checkSession();
                }

                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    // Not consumed, the framework would take the whole app down with the renderer.
                    Logger.printInfo(() -> "VK login WebView render process gone");
                    try {
                        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
                        view.destroy();
                    } catch (RuntimeException ex) {
                        Logger.printException(() -> "Could not close the crashed sign-in page", ex);
                    }
                    if (web == view) web = null;
                    Toast.makeText(VkLoginActivity.this,
                            text("Страница входа закрылась, попробуйте ещё раз", "The sign-in page closed, try again"),
                            Toast.LENGTH_SHORT).show();
                    finish();
                    return true;
                }
            });
            // The page focuses its own fields; the keyboard needs the view focusable and the window resized by it.
            web.setFocusable(true);
            web.setFocusableInTouchMode(true);
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            setContentView(web);
            web.requestFocus(View.FOCUS_DOWN);
            web.loadUrl(START_URL);
        } catch (RuntimeException | LinkageError ex) {
            // No usable system WebView (disabled, removed, being updated): the screen closes, the app stays.
            Logger.printException(() -> "WebView is not available", ex);
            Toast.makeText(this, text("Нужен Android System WebView: он отключён или не установлен",
                    "Android System WebView is disabled or missing"), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /** Asks VK for the website's access with the page's cookies; one check at a time, off the main thread. */
    private void checkSession() {
        if (done || !checking.compareAndSet(false, true)) return;
        CookieManager.getInstance().flush();
        Utils.runOnBackgroundThread(() -> {
            boolean signedIn = false;
            try {
                signedIn = VkAccount.takeSession();
            } finally {
                checking.set(false);
            }
            if (!signedIn) return;
            Utils.runOnMainThread(() -> {
                if (done || isFinishing()) return;
                done = true;
                Toast.makeText(this, text("Вход в VK выполнен", "Signed in to VK"), Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        done = true;
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
