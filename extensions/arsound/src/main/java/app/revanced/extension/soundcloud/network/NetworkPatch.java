package app.revanced.extension.soundcloud.network;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.power.PowerSavingPatch;
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
    private static final String DNS_NAME = "ArsoundDns";

    private NetworkPatch() {
    }

    /**
     * Wraps the resolver of the client. The custom server is checked on every lookup, so the option
     * applies without a restart; while it is off, or fails, the original resolver answers.
     */
    private static void installDns(Object builder, ClassLoader loader) throws Exception {
        Class<?> dnsClass = Class.forName("okhttp3.Dns", false, loader);
        Object original = builder.getClass().getMethod("getDns$okhttp").invoke(builder);
        if (ReflectiveProxy.isNamed(original, DNS_NAME)) return;
        Object fallback = original != null ? original : dnsClass.getField("SYSTEM").get(null);

        Object dns = ReflectiveProxy.create(loader, dnsClass, DNS_NAME, (method, args) -> {
            List<InetAddress> custom = CustomDns.lookup((String) args[0]);
            if (custom != null) return custom;
            try {
                return method.invoke(fallback, args);
            } catch (InvocationTargetException ex) {
                throw ex.getCause();
            }
        });
        builder.getClass().getMethod("dns", dnsClass).invoke(builder, dns);
    }

    /** The exception an OkHttp interceptor may throw: IOException and unchecked ones as they are, others wrapped. */
    private static Throwable unwrap(Throwable ex) {
        while (ex instanceof InvocationTargetException && ex.getCause() != null) ex = ex.getCause();
        if (ex instanceof IOException || ex instanceof RuntimeException || ex instanceof Error) return ex;
        return new IOException(ex);
    }

    public static void onBuild(Object builder) {
        try {
            ClassLoader loader = builder.getClass().getClassLoader();
            Class<?> interceptorClass = Class.forName("okhttp3.Interceptor", false, loader);

            // Builders copied with newBuilder() already carry the interceptor; do not stack delays.
            for (Object existing : (List<?>) builder.getClass().getMethod("interceptors").invoke(builder)) {
                if (ReflectiveProxy.isNamed(existing, INTERCEPTOR_NAME)) return;
            }

            RequestGate gate = new RequestGate(loader);
            Object interceptor = ReflectiveProxy.create(loader, interceptorClass, INTERCEPTOR_NAME,
                    (method, args) -> gate.intercept(args[0]));
            builder.getClass().getMethod("addInterceptor", interceptorClass).invoke(builder, interceptor);
            installDns(builder, loader);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not configure OkHttp client", ex);
        }
    }

    /**
     * What happens to each request on its way out, in this order: the developer delay, the region guard, the power
     * saving block of background reports, and the shared download of covers.
     */
    private static final class RequestGate {
        // Looked up once per client: the interceptor runs for every request, reflection lookups there cost battery.
        private final Method requestMethod;
        private final Method proceedMethod;
        private final Method urlMethod;
        private final Method hostMethod;
        private final Method pathMethod;

        RequestGate(ClassLoader loader) throws Exception {
            Class<?> chainClass = Class.forName("okhttp3.Interceptor$Chain", false, loader);
            Class<?> requestClass = Class.forName("okhttp3.Request", false, loader);
            requestMethod = chainClass.getMethod("request");
            proceedMethod = chainClass.getMethod("proceed", requestClass);
            urlMethod = requestClass.getMethod("url");
            Class<?> urlClass = urlMethod.getReturnType();
            hostMethod = urlClass.getMethod("host");
            pathMethod = urlClass.getMethod("encodedPath");
        }

        /** @param chain The {@code okhttp3.Interceptor.Chain}. @return The {@code okhttp3.Response}. */
        Object intercept(Object chain) throws Throwable {
            // OkHttp's Interceptor may only throw IOException. Any other checked exception, such as the wrapper
            // reflection puts around a cancelled download, became UndeclaredThrowableException on OkHttp's thread
            // and closed the app (a cover cancelled while a track was downloaded or a list was scrolled).
            try {
                Object request = requestMethod.invoke(chain);
                delay(request);

                Object url = urlMethod.invoke(request);
                String host = (String) hostMethod.invoke(url);
                RegionGuard.throwIfBlocked(host);
                String path = (String) pathMethod.invoke(url);
                if (PowerSavingPatch.isBackgroundReportBlocked(host, path)) {
                    throw new IOException("Arsound: background report blocked to save power");
                }

                SharedArtwork.Call call = () -> {
                    try {
                        return proceedMethod.invoke(chain, request);
                    } catch (InvocationTargetException ex) {
                        // Rethrow the original IOException, OkHttp handles it.
                        throw ex.getCause();
                    }
                };
                if (SharedArtwork.applies(host, path)) return SharedArtwork.load(url.toString(), request, call);
                return call.proceed();
            } catch (InvocationTargetException ex) {
                throw unwrap(ex.getCause());
            } catch (IOException | RuntimeException | Error ex) {
                throw ex;
            } catch (Throwable ex) {
                throw unwrap(ex);
            }
        }

        /** The developer option that slows every request down. */
        private static void delay(Object request) {
            int seconds = Settings.getDeveloperNetworkDelaySeconds();
            if (seconds <= 0) return;
            Logger.printInfo(() -> "Delaying request by " + seconds + " s: " + request);
            try {
                Thread.sleep(seconds * 1000L);
            } catch (InterruptedException ex) {
                // OkHttp interrupts calls that were cancelled; let the chain report it.
                Thread.currentThread().interrupt();
            }
        }
    }
}
