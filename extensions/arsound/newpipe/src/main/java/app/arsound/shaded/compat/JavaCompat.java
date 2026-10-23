package app.arsound.shaded.compat;

import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * Stand-ins for Java methods that NewPipeExtractor and its libraries call, but that Android has only since a later
 * version than Arsound runs on: such a call fails there with NoSuchMethodError. The build rewrites every call to them
 * in the packed library into a call to the method of the same name here (see build.gradle.kts).
 */
@SuppressWarnings("unused")
public final class JavaCompat {
    private JavaCompat() {
    }

    /** {@code Collectors.toUnmodifiableList()}, Android 13. */
    public static <T> Collector<T, ?, List<T>> toUnmodifiableList() {
        return Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList);
    }

    /** {@code URLEncoder.encode(String, Charset)}, Android 13. */
    public static String encode(String value, Charset charset) {
        try {
            return URLEncoder.encode(value, charset.name());
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    /** {@code URLDecoder.decode(String, Charset)}, Android 13. */
    public static String decode(String value, Charset charset) {
        try {
            return URLDecoder.decode(value, charset.name());
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    /** {@code BigInteger.intValueExact()}, Android 12. */
    public static int intValueExact(BigInteger value) {
        if (value.bitLength() > 31) throw new ArithmeticException("BigInteger out of int range");
        return value.intValue();
    }
}
