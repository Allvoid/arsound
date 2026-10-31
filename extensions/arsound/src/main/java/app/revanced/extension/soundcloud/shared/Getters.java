package app.revanced.extension.soundcloud.shared;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Calls the getters of SoundCloud's objects by name ({@code getUrn}, {@code getTitle}), the method looked up once per
 * class and name. The cells of the lists ask for them on every bind, and {@code getMethod} goes through the methods of
 * the class each time.
 */
public final class Getters {
    private static final ConcurrentHashMap<Class<?>, Map<String, Method>> METHODS = new ConcurrentHashMap<>();

    private Getters() {
    }

    /** The value of the public getter with no arguments. */
    public static Object get(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        Map<String, Method> methods = METHODS.get(type);
        if (methods == null) {
            methods = new ConcurrentHashMap<>();
            Map<String, Method> earlier = METHODS.putIfAbsent(type, methods);
            if (earlier != null) methods = earlier;
        }
        Method method = methods.get(name);
        if (method == null) {
            method = type.getMethod(name);
            methods.put(name, method);
        }
        return method.invoke(target);
    }
}
