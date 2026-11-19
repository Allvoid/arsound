package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/**
 * "Set up playback…" in the menu of a track, and the small window it opens: the speed this one track always plays
 * at (normal, slowed, sped up), and its own reverb and bass boost with their strengths (off, weak, medium, strong),
 * wherever it plays, or none of its own: then it plays like the others. Kept by {@link TrackSpeeds}.
 * <p>
 * For the listener who wants one track of a playlist always slowed or sped up, without switching the version of every
 * track back and forth.
 */
public final class TrackSpeedPanel {
    private static final String ROW_TAG = "arsound_track_speed_row";

    private final Context context;
    private final PopupParts parts;
    private final Object urn;
    private final TextView[] chips = new TextView[VersionSpeed.values().length];
    private final TextView[] reverbChips = new TextView[VersionStrength.values().length];
    private final TextView[] bassChips = new TextView[VersionStrength.values().length];
    private TextView note;
    private TextView reverbNote;
    private TextView bassNote;

    private TrackSpeedPanel(Context context, Object urn) {
        this.context = context;
        this.urn = urn;
        parts = new PopupParts(context);
    }

    /** Adds the row under the items of the track menu. Main thread. */
    public static void addTrackMenuRow(Dialog dialog, Object trackUrn) {
        try {
            if (TrackSpeeds.keyOf(trackUrn) == null) return;
            View menuItems = dialog.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "menuItems"));
            if (menuItems == null || !(menuItems.getParent() instanceof LinearLayout)) return;
            LinearLayout parent = (LinearLayout) menuItems.getParent();
            View existing = parent.findViewWithTag(ROW_TAG);
            if (existing != null) parent.removeView(existing);

            Context context = dialog.getContext();
            VersionSpeed own = TrackSpeeds.of(trackUrn);
            // The row says what is set, so the menu shows it without opening the window.
            String title = own != null ? text("Проигрывание: всегда ", "Playback: always ") + own.label()
                    : !TrackSpeeds.fxOf(trackUrn).isEmpty() ? text("Проигрывание: свои настройки", "Playback: its own settings")
                    : text("Настроить проигрывание…", "Set up playback…");
            ViewGroup row = DownloadTrackPatch.createMenuRow(context, title, "ic_actions_filter", v -> {
                dialog.dismiss();
                show(context, trackUrn);
            });
            row.setTag(ROW_TAG);
            parent.addView(row, parent.indexOfChild(menuItems) + 1, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the playback row of the track", ex);
        }
    }

    /** Opens the window for this track. */
    static void show(Context context, Object urn) {
        try {
            new TrackSpeedPanel(context, urn).show();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the playback window of the track", ex);
        }
    }

    private void show() {
        LinearLayout root = new LinearLayout(context);
        root.setTag(ArsoundDialog.KEEP_COLORS);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(parts.dp(20), 0, parts.dp(20), 0);

        root.addView(parts.heading(text("Этот трек всегда играет", "This track always plays")));
        LinearLayout row = parts.chipRow();
        for (VersionSpeed speed : VersionSpeed.values()) {
            TextView chip = parts.chip(speed.label(), false);
            chips[speed.ordinal()] = chip;
            // A tap on the chosen one takes the choice back.
            chip.setOnClickListener(v -> choose(TrackSpeeds.of(urn) == speed ? null : speed));
            row.addView(chip, new LinearLayout.LayoutParams(0, parts.dp(40), 1));
        }
        root.addView(row);

        root.addView(parts.heading(text("Реверб", "Reverb")));
        root.addView(strengthRow(reverbChips, true));
        reverbNote = hint();
        root.addView(reverbNote);
        root.addView(parts.heading(text("Усиление баса", "Bass boost")));
        root.addView(strengthRow(bassChips, false));
        bassNote = hint();
        root.addView(bassNote);

        note = parts.label("", 14);
        note.setTextColor(parts.secondary);
        note.setPadding(0, parts.dp(12), 0, parts.dp(4));
        root.addView(note);
        paint();

        new ArsoundDialog(context)
                .setTitle(text("Проигрывание трека", "Playback of the track"))
                .setView(root)
                .setNeutralButton(text("Сбросить", "Reset"), (dialog, which) -> {
                    if (TrackSpeeds.of(urn) == null && TrackSpeeds.fxOf(urn).isEmpty()) return;
                    TrackSpeeds.set(urn, null);
                    TrackSpeeds.setFx(urn, null, null);
                    Toast.makeText(context, text("Трек снова играет как остальные", "The track plays like the others again"),
                            Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton(text("Готово", "Done"), null)
                .show();
    }

    /** Off, weak, medium, strong: a tap on the chosen one takes the choice back, and the track follows the others. */
    private LinearLayout strengthRow(TextView[] chipsOf, boolean reverb) {
        LinearLayout row = parts.chipRow();
        for (VersionStrength strength : VersionStrength.values()) {
            TextView chip = parts.chip(strength.label(), false);
            chipsOf[strength.ordinal()] = chip;
            chip.setOnClickListener(v -> {
                TrackFxList.Own own = TrackSpeeds.fxOf(urn);
                VersionStrength now = reverb ? own.reverb : own.bass;
                VersionStrength choice = now == strength ? null : strength;
                if (reverb) TrackSpeeds.setFx(urn, choice, own.bass);
                else TrackSpeeds.setFx(urn, own.reverb, choice);
                paint();
            });
            row.addView(chip, new LinearLayout.LayoutParams(0, parts.dp(40), 1));
        }
        return row;
    }

    private TextView hint() {
        TextView hint = parts.label("", 14);
        hint.setTextColor(parts.secondary);
        hint.setPadding(0, parts.dp(4), 0, parts.dp(8));
        return hint;
    }

    private void choose(VersionSpeed speed) {
        TrackSpeeds.set(urn, speed);
        paint();
    }

    /** The chosen chip and the line under the chips, which says what the choice means. */
    private void paint() {
        VersionSpeed own = TrackSpeeds.of(urn);
        for (VersionSpeed speed : VersionSpeed.values()) parts.paint(chips[speed.ordinal()], speed == own);
        TrackFxList.Own fx = TrackSpeeds.fxOf(urn);
        for (VersionStrength strength : VersionStrength.values()) {
            parts.paint(reverbChips[strength.ordinal()], strength == fx.reverb);
            parts.paint(bassChips[strength.ordinal()], strength == fx.bass);
        }
        reverbNote.setText(fxNote(fx.reverb, TrackVersions.reverb()));
        bassNote.setText(fxNote(fx.bass, TrackVersions.bass()));
        if (own == null) {
            note.setText(text("Сейчас трек играет как остальные (" + TrackVersions.speed().label() + "). Выберите скорость, "
                            + "и он всегда будет играть так: в любом плейлисте, в очереди и в «Моей волне».",
                    "The track plays like the others now (" + TrackVersions.speed().label() + "). Pick a speed, and it "
                            + "will always play so: in any playlist, in the queue and in My Wave."));
        } else if (own == VersionSpeed.NORMAL) {
            note.setText(text("Трек всегда играет на своей скорости, даже когда для остальных выбрано Slowed или Speed up.",
                    "The track always plays at its own speed, also while Slowed or Speed up is chosen for the others."));
        } else {
            int percent = Math.round(TrackVersions.factorOf(own) * 100);
            note.setText(text("Трек всегда играет " + own.label() + " (" + percent + "% скорости, тон меняется вместе с ней). "
                            + "Насколько замедлять и ускорять, выбирается для всех треков сразу: Настройки → Arsound → Воспроизведение.",
                    "The track always plays " + own.label() + " (" + percent + "% of its speed, the pitch follows). "
                            + "How much to slow down and speed up is one choice for all tracks: Settings → Arsound → Playback."));
        }
    }

    /** What a reverb or bass choice means: the common one (as for all) or the track's own. */
    private static String fxNote(VersionStrength own, VersionStrength common) {
        return own == null
                ? text("Как у всех (" + common.label() + "). Выберите силу, и трек всегда будет играть с ней.",
                "As for all (" + common.label() + "). Pick a strength, and the track will always play with it.")
                : text("Только у этого трека: " + own.label() + ".", "Only for this track: " + own.label() + ".");
    }
}
