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
    private static final ConcurrentHashMap<Class<?>, Map<String, java.lang.reflect.Field>> FIELDS = new ConcurrentHashMap<>();

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

    /** The value of a field declared by the object's own class, private ones too, looked up once per class and name. */
    public static Object field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        Map<String, java.lang.reflect.Field> fields = FIELDS.get(type);
        if (fields == null) {
            fields = new ConcurrentHashMap<>();
            Map<String, java.lang.reflect.Field> earlier = FIELDS.putIfAbsent(type, fields);
            if (earlier != null) fields = earlier;
        }
        java.lang.reflect.Field field = fields.get(name);
        if (field == null) {
            field = type.getDeclaredField(name);
            field.setAccessible(true);
            fields.put(name, field);
        }
        return field.get(target);
    }
}
