package app.revanced.extension.soundcloud.network;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/** Tells the region guard when the default network of the phone is replaced, changes its addresses or is lost. */
final class NetworkWatcher {
    interface Listener {
        /** The app moved to another network (Wi-Fi to mobile, a VPN switched on). */
        void onNetworkReplaced();

        /** The addresses or DNS of the same network changed: the public IP may be new. */
        void onLinkChanged();

        void onNetworkLost();
    }

    private final Listener listener;
    private volatile boolean listening;
    /** The default network last reported, to tell the first report of the current one from a real change. */
    private volatile Network lastNetwork;

    NetworkWatcher(Listener listener) {
        this.listener = listener;
    }

    /** Starts listening, once. Does nothing until the app has a context. */
    synchronized void start() {
        // Synchronized: the first requests come on several threads at once, and each registered a callback of its own.
        if (listening) return;
        Context context = Utils.getContext();
        if (context == null) return;
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    // Right after registering, Android reports the network already in use: not a change, and
                    // treating it as one would throw away the check that is running for it.
                    Network previous = lastNetwork;
                    lastNetwork = network;
                    if (previous == null || previous.equals(network)) return;
                    listener.onNetworkReplaced();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                    listener.onLinkChanged();
                }

                @Override
                public void onLost(Network network) {
                    listener.onNetworkLost();
                }
            });
            listening = true;
        } catch (Exception ex) {
            Logger.printException(() -> "Region guard: could not watch network changes", ex);
        }
    }
}
