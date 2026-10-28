package app.revanced.extension.soundcloud.network;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * An implementation of an interface of SoundCloud's OkHttp, which is not known at compile time, made with
 * {@link Proxy}. It answers the methods of {@link Object} itself and names itself, so the same patch does not install
 * itself twice on a client that was copied with {@code newBuilder()}.
 */
final class ReflectiveProxy {
    /** The code of the interface method that is not one of {@link Object}'s. */
    interface Handler {
        Object invoke(Method method, Object[] args) throws Throwable;
    }

    private ReflectiveProxy() {
    }

    static Object create(ClassLoader loader, Class<?> type, String name, Handler handler) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                return name;
            }
            return handler.invoke(method, args);
        });
    }

    /** True for an object made by {@link #create} with this name. */
    static boolean isNamed(Object object, String name) {
        return object != null && Proxy.isProxyClass(object.getClass()) && name.equals(object.toString());
    }
}
