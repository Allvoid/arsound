package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import java.lang.reflect.Field;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.shared.Reflect;

/**
 * The sleep timer: music pauses after a set time ("in 30 minutes") or at a set clock time ("at 23:30").
 * Set from the "⋯" menu of the full screen player. The timer lives in the app process, like the music itself.
 * <p>
 * This class is the entry of the patch (the menu row); {@link SleepTimerScheduler} keeps the time,
 * {@link SleepTimerDialog} is the window and {@link TimerMode} the two ways to set it.
 */
@SuppressWarnings("unused")
public final class SleepTimer {
    private static final String ROW_TAG = "arsound_sleep_timer_row";
    /** SoundCloud's TrackMenuTypes.MENU_FOR_FULLSCREEN_PLAYER, as Params.toString() prints it. */
    private static final String PLAYER_MENU = "trackMenuType=1,";

    private SleepTimer() {
    }

    public static boolean isActive() {
        return SleepTimerScheduler.isActive();
    }

    /** The timer window: "In time" or "At a time", the wheels to set it, and when the music stops. */
    /** Called when the app starts: a timer set before the process was ended goes on. */
    public static void onAppStart(Context context) {
        try {
            SleepTimerScheduler.restore(context);
        } catch (Exception ex) {
            app.revanced.extension.shared.Logger.printException(() -> "Sleep timer: could not restore", ex);
        }
    }

    public static void show(Context context) {
        SleepTimerDialog.show(context);
    }

    // region Menu row

    /** The data consumer of the track menu being filled; it holds the menu fragment. Main thread only. */
    private static Object menuConsumer;

    /** Injection point. Called first when the track menu receives its data. */
    public static void setMenuConsumer(Object consumer) {
        menuConsumer = consumer;
    }

    /** Injection point. Called when the track menu receives its data, after {@link #setMenuConsumer}. */
    public static void onTrackMenu(Dialog dialog) {
        Object consumer = menuConsumer;
        // Used once: kept in the field, it would hold the closed menu, its dialog and the whole activity.
        menuConsumer = null;
        try {
            if (!isPlayerMenu(consumer)) return;
            View menuItems = dialog.findViewById(Utils.getResourceIdentifier(ResourceType.ID, "menuItems"));
            if (menuItems == null || !(menuItems.getParent() instanceof LinearLayout)) return;
            LinearLayout parent = (LinearLayout) menuItems.getParent();
            View existing = parent.findViewWithTag(ROW_TAG);
            if (existing != null) parent.removeView(existing);

            Context context = dialog.getContext();
            String title = isActive()
                    ? text("Таймер сна: остановка в ", "Sleep timer: stops at ") + TimerFormat.clock(SleepTimerScheduler.stopAt())
                    : text("Таймер сна", "Sleep timer");
            ViewGroup row = DownloadTrackPatch.createMenuRow(context, title, "ic_actions_timer", v -> {
                dialog.dismiss();
                show(context);
            });
            row.setTag(ROW_TAG);
            parent.addView(row, parent.indexOfChild(menuItems) + 1, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the sleep timer row", ex);
        }
    }

    /** True for the menu of the full screen player: its fragment arguments carry that menu type. */
    private static boolean isPlayerMenu(Object menuConsumer) throws Exception {
        if (menuConsumer == null) return false;
        Field fragmentField = Reflect.field(menuConsumer.getClass(), Reflect.typeNameEndsWith("TrackBottomSheetFragment"));
        if (fragmentField == null) return false;
        Object fragment = fragmentField.get(menuConsumer);
        if (fragment == null) return false;
        Bundle arguments = (Bundle) fragment.getClass().getMethod("getArguments").invoke(fragment);
        if (arguments == null) return false;
        arguments.setClassLoader(fragment.getClass().getClassLoader());
        Parcelable params = arguments.getParcelable("PARAMS_KEY");
        return params != null && params.toString().contains(PLAYER_MENU);
    }

    // endregion
}
