package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.net.ConnectivityManager;

/**
 * Whether the network in use costs money or is limited, which decides if pictures are downloaded just to be looked at
 * (see {@link SharpPolicy}). Used by {@link SharpLoader} and by the wallpaper preview.
 */
public final class NetworkCost {
    private NetworkCost() {
    }

    /** True on mobile data and other metered networks. When it cannot be told, the network counts as metered. */
    public static boolean isMetered(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            return manager == null || manager.isActiveNetworkMetered();
        } catch (RuntimeException ex) {
            return true;
        }
    }
}
