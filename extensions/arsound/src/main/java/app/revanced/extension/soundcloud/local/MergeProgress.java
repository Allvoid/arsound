package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicInteger;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * A dialog with a spinner while the tracks are read and saved. It opens only if that takes a moment. Its Cancel
 * button asks the merge to stop before the tracks are saved, and wakes it from a read that waits for SoundCloud;
 * the dialog stays until the merge has stopped.
 */
final class MergeProgress {
    private static final long SHOW_AFTER_MS = 400;
    private static final int RUNNING_STATE = 0, CANCELLED_STATE = 1, SAVING_STATE = 2;

    private final Context context;
    /** One state for the button and for the merge, so Cancel and the start of the save cannot both win. */
    private final AtomicInteger state = new AtomicInteger(RUNNING_STATE);
    private final TextView label;
    private final View content;
    private volatile Dialog dialog;
    private volatile boolean finished;
    /** The thread that runs the merge, while it does. Guarded by this. */
    private Thread worker;

    MergeProgress(Context context) {
        this.context = context;
        int accent = ArsoundTheme.palette(context, "special");
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Dimens.dp(context, 24), Dimens.dp(context, 8), Dimens.dp(context, 24), Dimens.dp(context, 8));
        // The colours are set here, so the dialog leaves the texts alone.
        box.setTag(ArsoundDialog.KEEP_COLORS);
        ProgressBar bar = new ProgressBar(context);
        bar.setIndeterminate(true);
        bar.setIndeterminateTintList(ColorStateList.valueOf(accent));
        box.addView(bar, new LinearLayout.LayoutParams(Dimens.dp(context, 32), Dimens.dp(context, 32)));
        label = new TextView(context);
        int style = Utils.getResourceIdentifier(ResourceType.STYLE, "Body.Secondary");
        if (style != 0) label.setTextAppearance(style);
        label.setTextColor(ArsoundTheme.palette(context, "secondary"));
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.leftMargin = Dimens.dp(context, 16);
        box.addView(label, labelParams);
        content = box;
        Utils.runOnMainThreadDelayed(this::show, SHOW_AFTER_MS);
    }

    private void show() {
        if (finished) return;
        dialog = new ArsoundDialog(context).setTitle(text("Совмещаю плейлисты", "Merging playlists"))
                .setView(content).setCancelable(false)
                .setNegativeButtonKeepingOpen(context.getString(android.R.string.cancel), (d, which) -> {
                    if (!state.compareAndSet(RUNNING_STATE, CANCELLED_STATE)) return;
                    label.setText(text("Отменяю…", "Cancelling…"));
                    showCancelState();
                    wake();
                }).show();
        showCancelState();
    }

    /** The merge runs on this thread from now on. */
    synchronized void attach(Thread thread) {
        worker = thread;
    }

    /**
     * The merge is over. Cancel can no longer interrupt the thread, which goes back to the pool, and an interrupt
     * that came too late is cleared so it does not reach the next task.
     */
    synchronized void detach() {
        worker = null;
        Thread.interrupted();
    }

    /** Cancel has won: a read that waits for SoundCloud (up to a minute) is stopped instead of waited for. */
    private synchronized void wake() {
        if (worker != null) worker.interrupt();
    }

    /** The button works while the merge can still be stopped. */
    private void showCancelState() {
        Dialog shown = dialog;
        TextView button = shown == null ? null : ArsoundDialog.getButton(shown, DialogInterface.BUTTON_NEGATIVE);
        if (button == null) return;
        boolean open = state.get() == RUNNING_STATE;
        button.setEnabled(open);
        button.setAlpha(open ? 1f : 0.4f);
    }

    void update(String text) {
        // After Cancel the label keeps saying so.
        Utils.runOnMainThread(() -> {
            if (state.get() != CANCELLED_STATE) label.setText(text);
        });
    }

    /** The user pressed Cancel. */
    boolean cancelled() {
        return state.get() == CANCELLED_STATE;
    }

    /**
     * The tracks are about to be saved: from here Cancel does nothing.
     *
     * @return False if the user cancelled before.
     */
    boolean commit() {
        if (!state.compareAndSet(RUNNING_STATE, SAVING_STATE)) return false;
        Utils.runOnMainThread(this::showCancelState);
        return true;
    }

    void dismiss() {
        finished = true;
        try {
            if (dialog != null) dialog.dismiss();
        } catch (IllegalArgumentException ex) {
            // The window is gone with its screen.
            Logger.printInfo(() -> "The progress dialog was already closed");
        }
    }
}
