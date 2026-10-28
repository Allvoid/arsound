package app.revanced.extension.soundcloud.shared;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Finding the parts of SoundCloud's classes by what they are, never by name: the names are shrunk and change from
 * version to version, the types and shapes stay. A part that is found is already accessible.
 */
public final class Reflect {
    private Reflect() {
    }

    /** The class with this name from the app's class loader, without initialising it. */
    public static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    /** The first field the class itself declares that matches, made accessible; null if there is none. */
    public static Field field(Class<?> owner, Predicate<Field> matches) {
        for (Field field : owner.getDeclaredFields()) {
            if (matches.test(field)) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    /** The first method the class itself declares that matches, made accessible; null if there is none. */
    public static Method method(Class<?> owner, Predicate<Method> matches) {
        for (Method method : owner.getDeclaredMethods()) {
            if (matches.test(method)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    /**
     * The values of all instance fields the class itself declares, in declaration order (a field that holds null
     * gives null). For an object whose fields are told apart by what they hold, not by their shrunk names.
     */
    public static List<Object> instanceFieldValues(Object target) throws IllegalAccessException {
        List<Object> values = new ArrayList<>();
        for (Field field : target.getClass().getDeclaredFields()) {
            if (isStatic(field)) continue;
            field.setAccessible(true);
            values.add(field.get(target));
        }
        return values;
    }

    /** True for a static field or method. */
    public static boolean isStatic(Member member) {
        return Modifier.isStatic(member.getModifiers());
    }

    /** Matches a field of exactly this type. */
    public static Predicate<Field> typeIs(Class<?> type) {
        return field -> field.getType() == type;
    }

    /** Matches a field whose type has exactly this class name. */
    public static Predicate<Field> typeNamed(String className) {
        return field -> field.getType().getName().equals(className);
    }

    /** Matches a field whose type's class name ends with this text. */
    public static Predicate<Field> typeNameEndsWith(String suffix) {
        return field -> field.getType().getName().endsWith(suffix);
    }
}
