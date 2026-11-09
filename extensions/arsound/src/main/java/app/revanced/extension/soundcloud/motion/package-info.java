/**
 * Motion blur: a list or a pager that moves fast is blurred along its movement, more the faster it goes.
 *
 * <p>User view: Settings → Arsound → Appearance → "Motion blur" ({@code settings.MotionBlurScreen}). One switch (off by
 * default), two areas (scrolling up and down, swipes to the sides) and two sliders: how strong (0 to 100, default 48)
 * and how many times longer the blur is along the movement than across it (1.00x to 4.00x, default 2.04x). Slow reading
 * stays sharp; the blur is off in battery saver, when Android's animations are off and before Android 12.
 *
 * <p>Entry points: {@code MotionBlur.onActivityResumed/onActivityPaused} are screen hooks ({@code update.UpdateChecker}
 * lists them); {@code MotionBlur.refresh} is called by the switch. No patch is needed: the first resumed screen with
 * the switch on wraps its window callback ({@code MotionWindowCallback}), so every touch of the screen passes
 * {@code MotionBlurRun.onTouch} first and then goes on unchanged.
 *
 * <p>How it works: on the first move past the touch slop the gesture gets its axis; the view under the first touch is
 * found by a walk down the view tree, and the nearest parent of it that can scroll that way (so also a Compose list:
 * its host view may answer {@code canScroll*}; when it does not, a vertical drag on a Compose view counts as a scroll) takes the blur. While the finger is down and while the fling runs on, a
 * frame callback turns the speed into a pair of radii ({@code MotionBlurMath}) and sets a {@code RenderEffect}, made
 * again only when a radius moves by half a pixel; when the speed is below the quiet limit the effect is taken off and
 * the callback stops. A tap, a slow drag, a gesture with a second finger or a view that cannot scroll costs nothing.
 *
 * <p>Gotchas: popups and bottom sheets are windows of their own and are not blurred; the menu and the main-menu swipe
 * of the original idea are covered by "swipes" (a menu is not a view that moves). The state of a screen is in
 * {@code MotionBlurRun}, on the main thread only. {@code MotionBlurMath} is pure and covered by JUnit tests.
 */
package app.revanced.extension.soundcloud.motion;
