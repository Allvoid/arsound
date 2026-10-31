package app.revanced.extension.soundcloud.search;

import static app.revanced.extension.soundcloud.shared.Dimens.dpTruncated;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

/** One result, as a track cell of SoundCloud's search: the cover, tap to listen, the button on the right downloads. */
final class ResultRow {
    private final SearchScreen screen;
    private final OtherSource.Track track;
    private final LinearLayout view;
    private final TextView title;
    private final TextView subtitle;
    /** The length and what the preview is doing. */
    private final TextView meta;
    private final ImageView download;
    private final TextView percent;
    private final ProgressBar busy;

    ResultRow(SearchScreen screen, OtherSource.Track track) {
        this.screen = screen;
        this.track = track;
        Context context = screen.context;

        SearchLook look = screen.look;
        view = look.row();
        view.setOnClickListener(v -> PreviewPlayer.toggle(context.getApplicationContext(), track.url));
        ImageView art = look.artwork(false, "ic_actions_musical_note");
        look.load(art, track.thumbUrl, SearchLook.ART_DP);
        title = look.title(track.title);
        subtitle = look.subtitle(track.artist);
        subtitle.setVisibility(track.artist.isEmpty() ? View.GONE : View.VISIBLE);
        meta = look.meta("");
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);

        FrameLayout action = new FrameLayout(context);
        download = new ImageView(context);
        download.setScaleType(ImageView.ScaleType.CENTER);
        download.setImageTintList(ColorStateList.valueOf(screen.textColor));
        download.setContentDescription(text("Скачать", "Download"));
        download.setBackgroundResource(ripple.resourceId);
        download.setOnClickListener(v -> DownloadQueue.start(context.getApplicationContext(), track));
        action.addView(download, new FrameLayout.LayoutParams(dpTruncated(context, 48), dpTruncated(context, 48), Gravity.CENTER));
        busy = new ProgressBar(context);
        busy.setIndeterminateTintList(ColorStateList.valueOf(SearchScreen.accent(context)));
        action.addView(busy, new FrameLayout.LayoutParams(dpTruncated(context, 28), dpTruncated(context, 28), Gravity.CENTER));
        percent = new TextView(context);
        percent.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        percent.setTextColor(screen.textColor);
        percent.setGravity(Gravity.CENTER);
        action.addView(percent, new FrameLayout.LayoutParams(dpTruncated(context, 48), dpTruncated(context, 48), Gravity.CENTER));
        action.setLayoutParams(new LinearLayout.LayoutParams(dpTruncated(context, 56), dpTruncated(context, 56)));
        look.fill(view, art, look.texts(title, subtitle, meta), action);

        refresh();
    }

    View view() {
        return view;
    }

    OtherSource.Track track() {
        return track;
    }

    private static int downloadedIcon, downloadIcon;
    /** The icon the download button shows, so it is not set again unchanged. */
    private int shownIcon;

    /** Redraws the row from what is playing and downloading now. */
    void refresh() {
        boolean playing = PreviewPlayer.isPlaying(track.url);
        title.setTextColor(playing ? screen.look.accent : screen.look.primary);
        String duration = track.durationSeconds > 0
                ? String.format(Locale.ROOT, "%d:%02d", track.durationSeconds / 60, track.durationSeconds % 60) : "";
        String state = playing ? (PreviewPlayer.isPreparing() ? text("загрузка…", "loading…") : text("играет", "playing")) : "";
        StringBuilder line = new StringBuilder(duration);
        if (!state.isEmpty()) {
            if (line.length() > 0) line.append(" · ");
            line.append(state);
        }
        meta.setText(line);
        meta.setTextColor(playing ? screen.look.accent : screen.look.secondary);
        meta.setVisibility(line.length() == 0 ? View.GONE : View.VISIBLE);

        Integer progress = DownloadQueue.progress(track.url);
        boolean done = DownloadQueue.isDownloaded(track.url);
        download.setVisibility(progress == null ? View.VISIBLE : View.INVISIBLE);
        download.setEnabled(!done);
        // Looked up by name once, not on every redraw of every row.
        if (downloadedIcon == 0) {
            downloadedIcon = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "ic_actions_downloaded");
            downloadIcon = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "ic_actions_download_initial");
        }
        int icon = done ? downloadedIcon : downloadIcon;
        if (icon != shownIcon) {
            shownIcon = icon;
            download.setImageResource(icon);
        }
        download.setImageTintList(done ? null : ColorStateList.valueOf(screen.textColor));
        busy.setVisibility(progress == null ? View.GONE : View.VISIBLE);
        percent.setVisibility(progress == null || progress <= 0 ? View.GONE : View.VISIBLE);
        percent.setText(progress == null ? "" : progress + "%");
    }
}
