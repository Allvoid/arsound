/**
 * "Not available in your country" hint: a "?" badge that explains the label.
 *
 * <p>User view: next to the label "Not available in your country" under a track (and on the track page banner) a small
 * "?" wiggles every few seconds; a tap opens a dialog that says what the label means and what helps, with a
 * "Don't show again" tick.
 *
 * <p>Entry points in {@code RegionHint} (called by {@code region/RegionHintPatch.kt}; SoundCloud draws the label in
 * two ways and both are patched):
 * <ul>
 * <li>{@code noteLabels(List)} - sees the label types of a line before it is built and remembers (per thread) whether
 * the geo-lock label is among them ({@code GeoLabel} recognises it by its lock icon).</li>
 * <li>{@code appendBadge(List)} - Compose: adds a piece ({@code ComposeBadge}) hosting the badge to the label
 * pieces.</li>
 * <li>{@code appendBadgeView(List)} - the View version of the same.</li>
 * <li>{@code bannerBadge(Composer)} - the track page banner.</li>
 * </ul>
 *
 * <p>Other classes: {@code RegionHintBadge} (the view, with the wiggle from {@code ShakeCurve}),
 * {@code RegionHintDialog} and {@code RegionHintText} (the explanation), {@code RegionHintSettings} (title and
 * description of the row on the settings screen).
 *
 * <p>Settings: {@code region_hint} ({@code Settings.isRegionHintEnabled()}, default on); the dialog's tick writes it
 * off. The country check itself is not here, it is {@code network.RegionGuard}.
 *
 * <p>Gotchas: the texts name the Arsound settings screens and buttons; when one is renamed in {@code settings}, rename
 * it in {@code RegionHintText}. {@code noteLabels} and the call after it must run on the same thread (a thread-local
 * carries the answer). The lock icon is looked up by the resource name {@code ic_labels_geo_locked}.
 */
package app.revanced.extension.soundcloud.region;
