package app.revanced.extension.soundcloud.network;

import java.lang.reflect.Proxy;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Entry point injected into {@code OkHttpClient.Builder.build()}, so it reaches every OkHttp client
 * of SoundCloud: API, streaming, images and downloads.
 * <p>
 * Currently it adds the developer option that slows down every request, to reproduce a poor
 * connection on a fast network. Custom DNS and region guard will hook in here as well.
 */
@SuppressWarnings("unused")
public final class NetworkPatch {
    private static final String INTERCEPTOR_NAME = "ArsoundNetworkInterceptor";

    private NetworkPatch() {
    }

    private static final String DNS_NAME = "ArsoundDns";

    /**
     * Wraps the resolver of the client. The custom server is checked on every lookup, so the option
     * applies without a restart; while it is off, or fails, the original resolver answers.
     */
    private static void installDns(Object builder, ClassLoader loader) throws Exception {
        Class<?> dnsClass = Class.forName("okhttp3.Dns", false, loader);
        Object original = builder.getClass().getMethod("getDns$okhttp").invoke(builder);
        if (original != null && Proxy.isProxyClass(original.getClass()) && DNS_NAME.equals(original.toString())) return;
        Object fallback = original != null ? original : dnsClass.getField("SYSTEM").get(null);

        Object dns = Proxy.newProxyInstance(loader, new Class<?>[]{dnsClass}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                return DNS_NAME;
            }
            java.util.List<java.net.InetAddress> custom = CustomDns.lookup((String) args[0]);
            if (custom != null) return custom;
            try {
                return method.invoke(fallback, args);
            } catch (java.lang.reflect.InvocationTargetException ex) {
                throw ex.getCause();
            }
        });
        builder.getClass().getMethod("dns", dnsClass).invoke(builder, dns);
    }

    public static void onBuild(Object builder) {
        try {
            ClassLoader loader = builder.getClass().getClassLoader();
            Class<?> interceptorClass = Class.forName("okhttp3.Interceptor", false, loader);
            Class<?> chainClass = Class.forName("okhttp3.Interceptor$Chain", false, loader);
            Class<?> requestClass = Class.forName("okhttp3.Request", false, loader);

            // Builders copied with newBuilder() already carry the interceptor; do not stack delays.
            for (Object existing : (java.util.List<?>) builder.getClass().getMethod("interceptors").invoke(builder)) {
                if (Proxy.isProxyClass(existing.getClass()) && INTERCEPTOR_NAME.equals(existing.toString())) return;
            }

            Object interceptor = Proxy.newProxyInstance(loader, new Class<?>[]{interceptorClass}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    return INTERCEPTOR_NAME;
                }

                Object chain = args[0];
                int delay = Settings.getDeveloperNetworkDelaySeconds();
                if (delay > 0) {
                    Object url = chainClass.getMethod("request").invoke(chain);
                    Logger.printInfo(() -> "Delaying request by " + delay + " s: " + url);
                }
                if (delay > 0) {
                    try {
                        Thread.sleep(delay * 1000L);
                    } catch (InterruptedException ex) {
                        // OkHttp interrupts calls that were cancelled; let the chain report it.
                        Thread.currentThread().interrupt();
                    }
                }

                Object request = chainClass.getMethod("request").invoke(chain);
                Object url = request.getClass().getMethod("url").invoke(request);
                String host = String.valueOf(url.getClass().getMethod("host").invoke(url));
                RegionGuard.throwIfBlocked(host);
                if (app.revanced.extension.soundcloud.power.PowerSavingPatch.isBackgroundReportBlocked(host,
                        String.valueOf(url.getClass().getMethod("encodedPath").invoke(url)))) {
                    throw new java.io.IOException("Arsound: background report blocked to save power");
                }
                try {
                    return chainClass.getMethod("proceed", requestClass).invoke(chain, request);
                } catch (java.lang.reflect.InvocationTargetException ex) {
                    // Rethrow the original IOException, OkHttp handles it.
                    throw ex.getCause();
                }
            });

            builder.getClass().getMethod("addInterceptor", interceptorClass).invoke(builder, interceptor);
            installDns(builder, loader);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not configure OkHttp client", ex);
        }
    }
}
