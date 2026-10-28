package app.revanced.extension.soundcloud.debug.sections;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings.Global;
import android.text.TextUtils;
import android.webkit.WebView;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import app.revanced.extension.soundcloud.debug.LogSection;
import app.revanced.extension.soundcloud.debug.ReportLines;
import app.revanced.extension.soundcloud.network.RegionGuard;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * What decides whether the sign-in works on this phone, taken at the moment of saving: the network (a VPN, the DNS,
 * a proxy), the clock (a wrong time breaks secure connections and tokens), the browsers that can show SoundCloud's
 * sign-in page, and the settings of Arsound that change the network. It is never empty, so the file of a user who
 * has not signed in yet, and so has no logs of the app, still says something.
 */
public final class SignInEnvironmentSection implements LogSection {
    private static final String CUSTOM_TABS_SERVICE = "android.support.customtabs.action.CustomTabsService";

    @Override
    public String name() {
        return "sign-in environment";
    }

    @Override
    public boolean isRecord() {
        return false;
    }

    @Override
    public boolean hasContent(Context context) {
        return true;
    }

    @Override
    public void writeTo(Context context, OutputStream out) throws Exception {
        ReportLines lines = new ReportLines();
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        addNetwork(lines, manager);
        addTime(lines, context);
        lines.add("locale", Locale.getDefault());
        addBrowsers(lines, context);
        lines.probe("webview", () -> {
            PackageInfo webView = Build.VERSION.SDK_INT >= 26 ? WebView.getCurrentWebViewPackage() : null;
            return webView == null ? "unknown" : webView.packageName + " " + webView.versionName;
        });
        addArsoundSettings(lines);
        out.write(lines.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void addNetwork(ReportLines lines, ConnectivityManager manager) {
        lines.probe("active network", () -> {
            Network active = manager.getActiveNetwork();
            return active == null ? "none" : describe(manager.getNetworkCapabilities(active));
        });
        lines.probe("all networks", () -> {
            List<String> all = new ArrayList<>();
            for (Network network : manager.getAllNetworks()) all.add(describe(manager.getNetworkCapabilities(network)));
            return all;
        });
        lines.probe("metered", manager::isActiveNetworkMetered);
        lines.probe("dns", () -> {
            Network active = manager.getActiveNetwork();
            LinkProperties properties = active == null ? null : manager.getLinkProperties(active);
            if (properties == null) return "none";
            String privateDns = Build.VERSION.SDK_INT >= 28
                    ? ", private dns " + properties.isPrivateDnsActive() + " " + properties.getPrivateDnsServerName()
                    : "";
            return properties.getDnsServers() + privateDns + ", proxy " + properties.getHttpProxy();
        });
    }

    /** The kind of a network and what it can do: "WIFI+VPN, internet, validated". */
    private static String describe(NetworkCapabilities capabilities) {
        if (capabilities == null) return "no information";
        List<String> transports = new ArrayList<>();
        addIf(transports, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), "WIFI");
        addIf(transports, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), "CELLULAR");
        addIf(transports, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET), "ETHERNET");
        addIf(transports, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH), "BLUETOOTH");
        addIf(transports, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN), "VPN");

        List<String> abilities = new ArrayList<>();
        addIf(abilities, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), "internet");
        addIf(abilities, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), "validated");
        addIf(abilities, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL), "captive portal");
        return TextUtils.join("+", transports) + ", " + TextUtils.join(", ", abilities);
    }

    private static void addIf(List<String> into, boolean condition, String name) {
        if (condition) into.add(name);
    }

    private static void addTime(ReportLines lines, Context context) {
        lines.add("time", new Date() + ", zone " + TimeZone.getDefault().getID());
        lines.probe("automatic time", () -> Global.getInt(context.getContentResolver(), Global.AUTO_TIME, -1));
        lines.probe("automatic zone", () -> Global.getInt(context.getContentResolver(), Global.AUTO_TIME_ZONE, -1));
    }

    private static void addBrowsers(ReportLines lines, Context context) {
        PackageManager packages = context.getPackageManager();
        lines.probe("browsers with custom tabs", () -> {
            List<String> browsers = new ArrayList<>();
            for (ResolveInfo info : packages.queryIntentServices(new Intent(CUSTOM_TABS_SERVICE), 0)) {
                browsers.add(info.serviceInfo.packageName);
            }
            return browsers;
        });
        lines.probe("default browser", () -> {
            ResolveInfo info = packages.resolveActivity(
                    new Intent(Intent.ACTION_VIEW, Uri.parse("https://soundcloud.com")), PackageManager.MATCH_DEFAULT_ONLY);
            return info == null ? "none" : info.activityInfo.packageName;
        });
    }

    private static void addArsoundSettings(ReportLines lines) {
        lines.probe("arsound custom dns", () -> Settings.isCustomDnsEnabled()
                ? Settings.getDnsMode() + " " + Settings.getDnsPreset() + " " + Settings.getCustomDnsServers()
                : "off");
        lines.probe("arsound region guard", () -> Settings.isRegionGuardEnabled()
                ? Settings.getRegionGuardCountries() + ", last country " + RegionGuard.lastCountry()
                : "off");
        lines.probe("arsound bot check prompt", Settings::isDataDomePromptEnabled);
    }
}
