package app.revanced.extension.soundcloud.network;

import java.lang.reflect.Proxy;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Entry point injected into {@code OkHttpClient.Builder.build()}, so it reaches every OkHttp client
 * of SoundCloud: API, streaming, images and downloads.
 * <p>
 * It adds the developer option that slows down every request, to reproduce a poor connection on a
 * fast network, the region guard, the custom DNS, and one shared download per cover ({@link SharedArtwork}).
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

    /** The exception an OkHttp interceptor may throw: IOException and unchecked ones as they are, others wrapped. */
    private static Throwable unwrap(Throwable ex) {
        while (ex instanceof java.lang.reflect.InvocationTargetException && ex.getCause() != null) ex = ex.getCause();
        if (ex instanceof java.io.IOException || ex instanceof RuntimeException || ex instanceof Error) return ex;
        return new java.io.IOException(ex);
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

            // Looked up once per client: the interceptor runs for every request, reflection lookups there cost battery.
            java.lang.reflect.Method requestMethod = chainClass.getMethod("request");
            java.lang.reflect.Method proceedMethod = chainClass.getMethod("proceed", requestClass);
            java.lang.reflect.Method urlMethod = requestClass.getMethod("url");
            Class<?> urlClass = urlMethod.getReturnType();
            java.lang.reflect.Method hostMethod = urlClass.getMethod("host");
            java.lang.reflect.Method pathMethod = urlClass.getMethod("encodedPath");

            Object interceptor = Proxy.newProxyInstance(loader, new Class<?>[]{interceptorClass}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    return INTERCEPTOR_NAME;
                }

                // OkHttp's Interceptor may only throw IOException. Any other checked exception, such as the wrapper
                // reflection puts around a cancelled download, became UndeclaredThrowableException on OkHttp's thread
                // and closed the app (a cover cancelled while a track was downloaded or a list was scrolled).
                try {

                    Object chain = args[0];
                    Object request = requestMethod.invoke(chain);
                    int delay = Settings.getDeveloperNetworkDelaySeconds();
                    if (delay > 0) {
                        Logger.printInfo(() -> "Delaying request by " + delay + " s: " + request);
                        try {
                            Thread.sleep(delay * 1000L);
                        } catch (InterruptedException ex) {
                            // OkHttp interrupts calls that were cancelled; let the chain report it.
                            Thread.currentThread().interrupt();
                        }
                    }

                    Object url = urlMethod.invoke(request);
                    String host = (String) hostMethod.invoke(url);
                    RegionGuard.throwIfBlocked(host);
                    if (app.revanced.extension.soundcloud.power.PowerSavingPatch.isBackgroundReportBlocked(host,
                            (String) pathMethod.invoke(url))) {
                        throw new java.io.IOException("Arsound: background report blocked to save power");
                    }
                    SharedArtwork.Call call = () -> {
                        try {
                            return proceedMethod.invoke(chain, request);
                        } catch (java.lang.reflect.InvocationTargetException ex) {
                            // Rethrow the original IOException, OkHttp handles it.
                            throw ex.getCause();
                        }
                    };
                    String path = (String) pathMethod.invoke(url);
                    if (SharedArtwork.applies(host, path)) return SharedArtwork.load(url.toString(), request, call);
                    return call.proceed();
                } catch (java.lang.reflect.InvocationTargetException ex) {
                    throw unwrap(ex.getCause());
                } catch (java.io.IOException | RuntimeException | Error ex) {
                    throw ex;
                } catch (Throwable ex) {
                    throw unwrap(ex);
                }
            });

            builder.getClass().getMethod("addInterceptor", interceptorClass).invoke(builder, interceptor);
            installDns(builder, loader);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not configure OkHttp client", ex);
        }
    }
}
