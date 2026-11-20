package app.revanced.extension.soundcloud.local;

/**
 * A point in time as the like window needs it: the wall clock (which a user, a network or a dead battery can set to
 * anything), the time elapsed since the phone started (which only goes forward), and which start of the phone that was.
 * Within one start the elapsed time is the true time; across starts only the wall clock is left.
 * <p>
 * The start is told by {@code Settings.Global.BOOT_COUNT} (API 24, which is the least this app runs on, readable without
 * any permission); /proc/sys/kernel/random/boot_id is not used, as an app is not sure to be allowed to read it. Empty when
 * the count cannot be read: nothing is then taken for the same start.
 */
final class Moment {
    final long wall;
    final long elapsed;
    /** Which start of the phone; empty when not known. */
    final String boot;
    /** Whether {@link #elapsed} is a real reading (false for a moment known by the wall clock only). */
    final boolean elapsedKnown;

    Moment(long wall, long elapsed, String boot) {
        this.wall = wall;
        this.elapsed = elapsed;
        this.boot = boot == null ? "" : boot;
        this.elapsedKnown = true;
    }

    private Moment(long wall) {
        this.wall = wall;
        this.elapsed = 0;
        this.boot = "";
        this.elapsedKnown = false;
    }

    /** A moment known by the wall clock only (as the stamps of older files are). */
    static Moment wall(long wall) {
        return new Moment(wall);
    }
}
