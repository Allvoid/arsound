package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.wallpaper.NetworkCost;
import app.revanced.extension.soundcloud.wallpaper.SharpPolicy;
import app.revanced.extension.soundcloud.wallpaper.WallpaperItem;
import app.revanced.extension.soundcloud.wallpaper.WallpaperDownload;
import app.revanced.extension.soundcloud.wallpaper.WallpaperSource;
import app.revanced.extension.soundcloud.wallpaper.WallpaperStore;

/**
 * The full-screen look at a catalogue picture (of Wallhaven or Commons) before it becomes the wallpaper. The picture
 * fills the screen the way it will lie behind the app, <em>with</em> the dim and the blur the sliders at the bottom are
 * set to, so what is seen is what is got. A tap on the picture hides and shows the controls.
 * <p>
 * The thumbnail is small (300 or 500 pixels), so the full picture downloads while the preview is open ("Set as
 * background" then takes that same file). A file that is too big to fetch on its own (see {@link SharpPolicy}) is not
 * fetched, and the preview says so, with a button that loads it anyway.
 * <p>
 * Under the size the preview shows the lines the picture's source gives ({@link WallpaperSource#lines}): for Wallhaven
 * the views and the link to the picture's page, for Commons the author and the licence, which open the file's
 * description page.
 * <p>
 * Made by {@link WallhavenScreen}, which keeps it to give up the download when the screen is closed.
 */
final class WallpaperPreviewScreen {
    private static final int RENDER_WIDTH = 540;

    private final SettingsUi ui;
    private final WallpaperItem item;
    private final Runnable onInstalled;
    private final int surface;
    private final int renderWidth, renderHeight;

    private Dialog dialog;
    private ImageView picture;
    private View veil;
    private View topBar;
    private View panel;
    private ProgressBar bar;
    private View spinner;
    private TextView setButton;
    private View fullNote;
    private WallpaperDownload download;
    /** The picture the shown copy is made from: the thumbnail first, the full picture when it is there. */
    private Bitmap base;
    private int dim = WallpaperStore.dim();
    private int blur = WallpaperStore.blur();
    private int renderToken;
    private boolean open = true;
    private boolean controlsShown = true;
    /** The picture is being made the wallpaper: closing now would take the file from under it. */
    private volatile boolean installing;
    /** "Set as background" was tapped: the dim and the blur of the sliders are in the settings until it fails or is given up. */
    private boolean used;
    /** The picture is the wallpaper now. */
    private boolean done;
    /** The dim and the blur the settings had before "Set as background", to give back when it does not come to the end. */
    private int savedDim;
    private long savedBlur;

    private WallpaperPreviewScreen(SettingsUi ui, WallpaperItem item, Bitmap thumbnail, Runnable onInstalled) {
        this.ui = ui;
        this.item = item;
        this.base = thumbnail;
        this.onInstalled = onInstalled;
        surface = ArsoundTheme.palette(ui.activity, "surface") | 0xFF000000;
        DisplayMetrics metrics = ui.activity.getResources().getDisplayMetrics();
        int shortSide = Math.min(metrics.widthPixels, metrics.heightPixels);
        int longSide = Math.max(metrics.widthPixels, metrics.heightPixels);
        renderWidth = Math.min(shortSide, RENDER_WIDTH);
        renderHeight = Math.round(renderWidth * (float) longSide / shortSide);
        download = new WallpaperDownload(ui.activity, item.source, item.fullUrl);
    }

    /**
     * Opens the preview.
     *
     * @param thumbnail   The thumbnail if the grid has it in memory, else null.
     * @param onInstalled Called once the picture is the wallpaper; the browser then closes.
     */
    static WallpaperPreviewScreen show(SettingsUi ui, WallpaperItem item, Bitmap thumbnail, Runnable onInstalled) {
        WallpaperPreviewScreen preview = new WallpaperPreviewScreen(ui, item, thumbnail, onInstalled);
        preview.open();
        return preview;
    }

    /** Whether the picture is being kept as the wallpaper just now. */
    boolean isInstalling() {
        return installing;
    }

    /** The browser is closed: gives up what is still downloading and closes the preview. */
    void release() {
        open = false;
        if (!installing) download.cancel();
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
    }

    // region Views

    private void open() {
        dialog = new Dialog(ui.activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(ui.activity);
        root.setBackgroundColor(Color.BLACK);

        picture = new ImageView(ui.activity);
        picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
        picture.setOnClickListener(v -> toggleControls());
        root.addView(picture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The dim, as WallpaperDrawable lays it: the theme's surface colour over the picture.
        veil = new View(ui.activity);
        veil.setBackgroundColor(surface);
        veil.setClickable(false);
        root.addView(veil, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView sample = ui.createText("H3.Primary", text("Так выглядит текст на обоях", "This is how text looks on the wallpaper"));
        sample.setGravity(Gravity.CENTER);
        sample.setPadding(dp(32), 0, dp(32), 0);
        FrameLayout.LayoutParams sampleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        root.addView(sample, sampleParams);

        // Over the picture: the letter line white, as the logo on a dark tile.
        spinner = new app.revanced.extension.soundcloud.shared.A9Loader(ui.activity, 0xFFFFFFFF);
        root.addView(spinner, new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER));

        topBar = createTopBar();
        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        panel = createPanel();
        root.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // The controls keep clear of the status bar, a camera cut-out and the navigation bar.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            if (android.os.Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                top = Math.max(top, insets.getDisplayCutout().getSafeInsetTop());
            }
            topBar.setPadding(topBar.getPaddingLeft(), top + dp(4), topBar.getPaddingRight(), topBar.getPaddingBottom());
            panel.setPadding(panel.getPaddingLeft(), panel.getPaddingTop(), panel.getPaddingRight(),
                    Math.max(dp(12), insets.getSystemWindowInsetBottom() + dp(8)));
            return insets;
        });

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                window.getAttributes().layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        }
        dialog.setOnDismissListener(d -> {
            open = false;
            if (installing || done) return;
            // Closed before the picture was kept: the downloaded part is of no use, and the settings stay as they were.
            download.cancel();
            if (used) restoreSettings();
        });
        try {
            dialog.show();
        } catch (WindowManager.BadTokenException ex) {
            return;
        }
        applyDim();
        if (base != null) {
            spinner.setVisibility(View.GONE);
            render();
        }
        startDownload();
    }

    /** A strip on top over a dark fade: the close button and what is known of the picture. */
    private View createTopBar() {
        LinearLayout bar = new LinearLayout(ui.activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(4), dp(16), dp(24));
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xB3000000, 0x00000000});
        bar.setBackground(fade);

        ImageButton close = new ImageButton(ui.activity);
        close.setImageDrawable(new SearchGlyph(SearchGlyph.CROSS, Color.WHITE));
        close.setScaleType(ImageView.ScaleType.FIT_CENTER);
        close.setPadding(dp(14), dp(14), dp(14), dp(14));
        close.setBackgroundResource(ui.themeAttribute(android.R.attr.selectableItemBackgroundBorderless));
        close.setContentDescription(text("Закрыть", "Close"));
        close.setOnClickListener(v -> {
            if (!installing) dialog.dismiss();
        });
        bar.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout facts = new LinearLayout(ui.activity);
        facts.setOrientation(LinearLayout.VERTICAL);
        // A source that does not know the size of the file it will send gives a guess, marked with a sign.
        String size = item.previewBytes <= 0 ? ""
                : "  ·  " + (item.fileSize > 0 ? "" : "≈ ")
                + String.format(Locale.US, "%.1f", item.previewBytes / 1048576f) + " MB";
        facts.addView(whiteText(item.resolution + size, 15));
        for (WallpaperSource.Line line : item.source.lines(item)) facts.addView(factLine(line));
        LinearLayout.LayoutParams factsParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        factsParams.leftMargin = dp(4);
        bar.addView(facts, factsParams);
        return bar;
    }

    /** A line under the size: plain text, or underlined and opening its address when tapped. */
    private TextView factLine(WallpaperSource.Line line) {
        TextView view = whiteText(line.text, 12);
        String link = line.link;
        if (link == null || !link.startsWith("https://")) return view;
        view.setPaintFlags(view.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        view.setPadding(0, dp(2), 0, dp(2));
        view.setOnClickListener(v -> {
            try {
                ui.activity.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)));
            } catch (Exception ex) {
                Logger.printInfo(() -> "Wallpaper: no app to open the link");
            }
        });
        return view;
    }

    private TextView whiteText(String value, float sp) {
        TextView view = new TextView(ui.activity);
        view.setText(value);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setShadowLayer(dp(3), 0, dp(1), 0x99000000);
        return view;
    }

    /** The panel at the bottom: the note about a big file, the two sliders and the button that makes it the wallpaper. */
    private View createPanel() {
        LinearLayout panel = new LinearLayout(ui.activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        float radius = dp(24);
        GradientDrawable shape = new GradientDrawable();
        shape.setColor((ArsoundTheme.palette(ui.activity, "dialog") & 0x00FFFFFF) | 0xF2000000);
        shape.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        panel.setBackground(shape);
        panel.setPadding(0, dp(4), 0, dp(12));

        bar = new ProgressBar(ui.activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(true);
        bar.setProgressTintList(ColorStateList.valueOf(ArsoundTheme.palette(ui.activity, "special")));
        bar.setIndeterminateTintList(ColorStateList.valueOf(ArsoundTheme.palette(ui.activity, "special")));
        bar.setVisibility(View.INVISIBLE);
        panel.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)));

        fullNote = createFullNote();
        fullNote.setVisibility(View.GONE);
        panel.addView(fullNote);

        panel.addView(ui.createSliderRow(text("Затемнение", "Dim"), "", 95, dim, value -> value + " %", value -> {
            dim = value;
            applyDim();
        }, null, null));
        panel.addView(ui.createSliderRow(text("Размытие", "Blur"), "", 100, blur, value -> value + " %", value -> {
        }, value -> {
            blur = value;
            render();
        }, null));

        setButton = (TextView) ui.createPillButton(text("Сделать фоном", "Set as background"), true, v -> setAsBackground());
        setButton.setMinHeight(dp(52));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.leftMargin = buttonParams.rightMargin = ui.dimen("spacing_m");
        buttonParams.topMargin = dp(8);
        panel.addView(setButton, buttonParams);
        return panel;
    }

    /** The line that says the full picture was not fetched, with the button that fetches it. */
    private View createFullNote() {
        LinearLayout note = new LinearLayout(ui.activity);
        note.setOrientation(LinearLayout.VERTICAL);
        note.setPadding(ui.dimen("spacing_m"), dp(10), ui.dimen("spacing_m"), dp(4));
        String size = String.format(Locale.US, "%.0f", item.previewBytes / 1048576f);
        TextView text = ui.createText("Body.Secondary", text(
                "Показана уменьшенная миниатюра, она мягче настоящей картинки. Полный файл " + size
                        + " МБ сам не качается, чтобы не тратить трафик.",
                "This is a small thumbnail, softer than the real picture. The full file of " + size
                        + " MB is not fetched on its own, to save data."));
        note.addView(text);
        View load = ui.createPillButton(text("Загрузить полное превью", "Load the full preview"), false, v -> {
            note.setVisibility(View.GONE);
            download.start();
            showBusy();
        });
        LinearLayout.LayoutParams loadParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        loadParams.topMargin = dp(6);
        loadParams.leftMargin = -dp(8);
        note.addView(load, loadParams);
        return note;
    }

    private void toggleControls() {
        controlsShown = !controlsShown;
        float alpha = controlsShown ? 1f : 0f;
        for (View controls : new View[]{topBar, panel}) {
            controls.setVisibility(View.VISIBLE);
            controls.animate().alpha(alpha).setDuration(180)
                    .withEndAction(() -> controls.setVisibility(controlsShown ? View.VISIBLE : View.INVISIBLE)).start();
        }
    }

    // endregion

    // region Picture

    private void applyDim() {
        veil.setAlpha(dim / 100f);
    }

    /** Makes the shown picture from {@link #base} with the blur of the slider, off the main thread; the newest request wins. */
    private void render() {
        Bitmap source = base;
        if (source == null) return;
        int token = ++renderToken;
        int strength = blur;
        Utils.runOnBackgroundThread(() -> {
            Bitmap result = null;
            try {
                result = WallpaperStore.preview(source, renderWidth, renderHeight, strength);
            } catch (Exception | OutOfMemoryError ex) {
                Logger.printInfo(() -> "Wallpaper: the preview could not be drawn: " + ex.getClass().getSimpleName());
            }
            Bitmap ready = result;
            Utils.runOnMainThread(() -> {
                if (ready == null || !open || token != renderToken) return;
                boolean first = picture.getDrawable() == null;
                picture.setImageBitmap(ready);
                spinner.setVisibility(View.GONE);
                if (first) {
                    picture.setAlpha(0f);
                    picture.animate().alpha(1f).setDuration(200).start();
                }
            });
        });
    }

    // endregion

    // region Download

    /** Starts the download of the full picture, if it is small enough to take without being asked; else shows the note. */
    private void startDownload() {
        boolean metered = NetworkCost.isMetered(ui.activity);
        if (SharpPolicy.previewMayLoad(item.previewBytes, metered)) {
            download.start();
        } else {
            fullNote.setVisibility(View.VISIBLE);
        }
        listen();
        showBusy();
    }

    /** Hears the download the preview shows: the bar moves, and the full picture replaces the thumbnail when it is there. */
    private void listen() {
        download.setListener(new WallpaperDownload.Listener() {
            @Override
            public void onProgress(long done, long total) {
                showProgress(done, total);
            }

            @Override
            public void onFinished(boolean saved) {
                showBusy();
                if (saved) showFullPicture();
            }
        });
    }

    private void showProgress(long done, long total) {
        if (total <= 0) return;
        bar.setIndeterminate(false);
        bar.setProgress((int) (done * 1000 / total));
    }

    private void showBusy() {
        bar.setVisibility(download.isRunning() || installing ? View.VISIBLE : View.INVISIBLE);
    }

    /** Decodes the downloaded picture to the size of the screen, so it is sharp and light, and draws it. */
    private void showFullPicture() {
        Utils.runOnBackgroundThread(() -> {
            File file = download.file();
            Bitmap full = null;
            if (file != null) {
                try {
                    full = WallpaperStore.decodeCovering(file, renderWidth, renderHeight);
                } catch (Exception | OutOfMemoryError ex) {
                    Logger.printInfo(() -> "Wallpaper: the preview could not be read: " + ex.getClass().getSimpleName());
                }
            }
            Bitmap ready = full;
            Utils.runOnMainThread(() -> {
                if (ready == null || !open) return;
                base = ready;
                render();
            });
        });
    }

    // endregion

    // region Setting

    /**
     * Keeps the picture as the wallpaper with the dim and the blur of the sliders. The download may be under way since
     * the preview opened; it is waited for here and the bar shows how far it is.
     */
    private void setAsBackground() {
        if (installing || used) return;
        savedDim = WallpaperStore.dim();
        savedBlur = WallpaperStore.blur();
        WallpaperStore.setDim(dim);
        Settings.putLong(Settings.WALLPAPER_BLUR, blur);
        // This screen's own change must not count as an outside one, or coming back would rebuild the catalogue.
        ui.activity.markWallpaperSeen();
        used = true;
        // A download that failed stays failed (it does not start again), so a new one is made.
        if (download.isFailed()) download = new WallpaperDownload(ui.activity, item.source, item.fullUrl);
        fullNote.setVisibility(View.GONE);
        setBusy(text("Скачиваю…", "Downloading…"));
        bar.setIndeterminate(true);
        bar.setVisibility(View.VISIBLE);

        WallpaperDownload mine = download;
        mine.setListener(new WallpaperDownload.Listener() {
            @Override
            public void onProgress(long done, long total) {
                showProgress(done, total);
            }

            @Override
            public void onFinished(boolean saved) {
                if (!saved) {
                    failed();
                    return;
                }
                // From here the picture is being kept; giving up would take the file from under it.
                installing = true;
                dialog.setCancelable(false);
                setBusy(text("Готовлю обои…", "Preparing the wallpaper…"));
                bar.setIndeterminate(true);
                Utils.runOnBackgroundThread(() -> {
                    boolean ok = mine.install(ui.activity.getApplicationContext(), item.id);
                    Utils.runOnMainThread(() -> {
                        if (ok) installed();
                        else failed();
                    });
                });
            }
        });
        // Starts it unless the preview did already.
        mine.start();
    }

    private void restoreSettings() {
        WallpaperStore.setDim(savedDim);
        Settings.putLong(Settings.WALLPAPER_BLUR, savedBlur);
        ui.activity.markWallpaperSeen();
    }

    private void setBusy(String label) {
        setButton.setText(label);
        setButton.setEnabled(false);
        setButton.setAlpha(.6f);
    }

    private void installed() {
        installing = false;
        done = true;
        // The kept picture changed the stamp too; this screen made it, so the catalogue is not rebuilt for it.
        ui.activity.markWallpaperSeen();
        if (dialog.isShowing()) dialog.dismiss();
        if (ui.activity.isFinishing() || ui.activity.isDestroyed()) return;
        Toast.makeText(ui.activity, text("Обои установлены", "Wallpaper set"), Toast.LENGTH_SHORT).show();
        onInstalled.run();
    }

    /** The download or the keeping failed: the old dim and blur come back, and the button can be tapped again. */
    private void failed() {
        installing = false;
        restoreSettings();
        used = false;
        if (!open) return;
        dialog.setCancelable(true);
        setButton.setText(text("Сделать фоном", "Set as background"));
        setButton.setEnabled(true);
        setButton.setAlpha(1f);
        bar.setVisibility(View.INVISIBLE);
        if (!ui.activity.isFinishing() && !ui.activity.isDestroyed()) {
            Toast.makeText(ui.activity, text("Не удалось скачать обои", "Could not download the wallpaper"), Toast.LENGTH_SHORT).show();
        }
        listen();
    }

    // endregion

    private int dp(float value) {
        return Dimens.dp(ui.activity, value);
    }
}
