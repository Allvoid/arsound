package app.revanced.extension.soundcloud.debug;

import java.util.concurrent.Callable;

/**
 * Lines of {@code name: value} for the sign-in environment. Every value is asked for separately: a phone that
 * refuses one question (a missing API, a security exception) still answers the others.
 */
public final class ReportLines {
    private final StringBuilder text = new StringBuilder();

    public ReportLines add(String name, Object value) {
        text.append(name).append(": ").append(value).append('\n');
        return this;
    }

    /** Adds the line with the value {@code probe} returns, or with the reason it could not be found out. */
    public ReportLines probe(String name, Callable<?> probe) {
        try {
            add(name, probe.call());
        } catch (Exception | LinkageError ex) {
            // LinkageError: an API the phone's Android does not have yet.
            add(name, "unavailable (" + ex + ")");
        }
        return this;
    }

    @Override
    public String toString() {
        return text.toString();
    }
}
