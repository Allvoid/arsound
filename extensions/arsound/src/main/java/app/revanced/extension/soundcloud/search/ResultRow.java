package app.revanced.extension.soundcloud.search;

import static app.revanced.extension.soundcloud.shared.Colors.withAlpha;
import static app.revanced.extension.soundcloud.shared.Dimens.dpTruncated;
import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

/** One result: tap to listen, the button on the right downloads. */
final class ResultRow {
    private final SearchScreen screen;
    private final OtherSource.Track track;
    private final LinearLayout view;
    private final TextView title;
    private final TextView subtitle;
    private final ImageView download;
    private final TextView percent;
    private final ProgressBar busy;

    ResultRow(SearchScreen screen, OtherSource.Track track) {
        this.screen = screen;
        this.track = track;
        Context context = screen.context;

        view = new LinearLayout(context);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinimumHeight(dpTruncated(context, 64));
        view.setPadding(dpTruncated(context, 16), dpTruncated(context, 8), dpTruncated(context, 4), dpTruncated(context, 8));
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        view.setBackgroundResource(ripple.resourceId);
        view.setOnClickListener(v -> PreviewPlayer.toggle(context.getApplicationContext(), track.url));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(context);
        title.setText(track.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setTextColor(withAlpha(screen.textColor, 0xA0));
        texts.addView(title);
        texts.addView(subtitle);
        view.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

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
        view.addView(action, new LinearLayout.LayoutParams(dpTruncated(context, 56), dpTruncated(context, 56)));

        refresh();
    }

    View view() {
        return view;
    }

    OtherSource.Track track() {
        return track;
    }

    /** Redraws the row from what is playing and downloading now. */
    void refresh() {
        boolean playing = PreviewPlayer.isPlaying(track.url);
        title.setTextColor(playing ? SearchScreen.accent(title.getContext()) : screen.textColor);
        String duration = track.durationSeconds > 0
                ? String.format(Locale.ROOT, "%d:%02d", track.durationSeconds / 60, track.durationSeconds % 60) : "";
        String state = playing ? (PreviewPlayer.isPreparing() ? text("загрузка…", "loading…") : text("играет", "playing")) : "";
        StringBuilder line = new StringBuilder(track.artist);
        for (String part : new String[]{duration, state}) {
            if (part.isEmpty()) continue;
            if (line.length() > 0) line.append(" · ");
            line.append(part);
        }
        subtitle.setText(line);

        Integer progress = DownloadQueue.progress(track.url);
        boolean done = DownloadQueue.isDownloaded(track.url);
        download.setVisibility(progress == null ? View.VISIBLE : View.INVISIBLE);
        download.setEnabled(!done);
        download.setImageResource(Utils.getResourceIdentifier(ResourceType.DRAWABLE,
                done ? "ic_actions_downloaded" : "ic_actions_download_initial"));
        download.setImageTintList(done ? null : ColorStateList.valueOf(screen.textColor));
        busy.setVisibility(progress == null ? View.GONE : View.VISIBLE);
        percent.setVisibility(progress == null || progress <= 0 ? View.GONE : View.VISIBLE);
        percent.setText(progress == null ? "" : progress + "%");
    }
}
