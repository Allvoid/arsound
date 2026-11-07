/**
 * Animated theme backgrounds: sparks, meteors, fireflies, petals, scanner squares, snow under frost, a storm, a turning record.
 *
 * <p>User view: a theme can have a moving background under the settings screens, over the main screen and in the
 * preview tiles. It runs only while visible, stands still in battery saver or when Android animations are off, and the
 * amount of particles is a setting.
 *
 * <p>Entry points: none from Kotlin patches. {@code theme.ThemeEffect} (a {@code Drawable}) asks
 * {@code EffectKind.fromId(themeId)} for the effect of a theme and its scene; {@code theme.ThemeBackdrop} puts the
 * drawable on screens.
 *
 * <p>Classes:
 * <ul>
 * <li>{@code EffectKind} - an enum; each constant carries the id of its theme and makes its scene
 * ({@code scene(EffectBrush, density)}). Add a new effect here and as an {@code EffectScene}.</li>
 * <li>{@code EffectScene} - the interface of one animation ({@code layout(Rect)}, {@code draw(Canvas, time, w, h)});
 * scenes: {@code SparksScene} (Scarlet), {@code StarsScene} (Cobalt), {@code FirefliesScene} (Mint),
 * {@code PetalsScene} (Sakura), {@code SquaresScene} (Lime), {@code SnowScene} with {@code FrostLayer} (Winter),
 * {@code StormScene} (Storm), {@code VinylScene} (Vinyl).</li>
 * <li>{@code EffectBrush} - the paints, colours and scale a scene draws with; {@code Particles}, {@code ParticleRandom},
 * {@code Keyframes} - particles computed from time alone, from the design's seeded random sequence.</li>
 * </ul>
 *
 * <p>Settings: none here; {@code theme_effects} and {@code theme_effects_density} are read by {@code theme.ThemeBackdrop}.
 *
 * <p>Gotchas: a scene is drawn from the time alone, like a CSS keyframe animation, so nothing piles up between
 * frames; only {@code VinylScene} keeps an angle, to stop where it was on pause. The theme ids ({@code scarlet}, {@code cobalt}, {@code mint}, {@code sakura}, {@code lime}, {@code winter}, {@code storm}, {@code vinyl})
 * must match {@code themes.json}. Keep {@code ParticleRandom} unchanged so particles start where the design has them.
 */
package app.revanced.extension.soundcloud.theme.effect;
