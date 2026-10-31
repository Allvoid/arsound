package app.revanced.extension.soundcloud.lyrics.transcribe;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.PowerManager;

import app.revanced.extension.shared.Logger;

/**
 * Reads what the phone is doing from Android's services: charge, power saving, free memory, heat, whether the
 * network is metered. The decisions on these numbers are in {@link Guards}. Used by {@link TranscriptionManager}
 * before a job starts and by {@link TranscriptionJob} between windows.
 */
final class DeviceState {
    private DeviceState() {
    }

    static boolean isSupportedDevice() {
        // Android 8 and later: the recognition uses java.util.Base64 and String.join, which Android 7 does not have.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        // The libraries exist for 64-bit ARM phones, and for x86-64 (emulators, Chromebooks).
        return NativeArchive.abi() != null;
    }

    static Guards.Device read(Context context) {
        int percent = -1;
        boolean charging = false;
        try {
            Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (battery != null) {
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (level >= 0 && scale > 0) percent = Math.round(level * 100f / scale);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                        || battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            }
        } catch (RuntimeException ex) {
            Logger.printInfo(() -> "Transcribe: could not read the battery: " + ex);
        }
        boolean saver = false;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power != null) saver = power.isPowerSaveMode();
        return new Guards.Device(percent, charging, saver, availableMemory(context));
    }

    /** Free memory the system would give to the app now, in bytes. */
    static long availableMemory(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) return Long.MAX_VALUE;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(info);
        return info.availMem;
    }

    /** The heat level of Android's PowerManager (0 none to 6 shutdown); 0 where the phone does not tell. */
    static int thermalStatus(Context context) {
        if (Build.VERSION.SDK_INT < 29) return 0;
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return power == null ? 0 : power.getCurrentThermalStatus();
    }

    /** Whether the network in use costs money or is limited (mobile data, a hotspot, a roaming connection). */
    static boolean isMetered(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        return manager != null && manager.isActiveNetworkMetered();
    }

    /** How many threads the model gets: three or four, so the music and the screen keep their share of the cores. */
    static int threads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return cores >= 8 ? 4 : 3;
    }
}
