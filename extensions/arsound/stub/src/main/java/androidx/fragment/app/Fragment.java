package androidx.fragment.app;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

/**
 * The parts of AndroidX's fragment that Arsound's own tab page ({@code navbar.WavePage}) uses. SoundCloud keeps these
 * names unshrunk: the fragment manager calls them on every fragment.
 */
public class Fragment {
    public Fragment() {
    }

    public Context getContext() {
        throw new UnsupportedOperationException("Stub");
    }

    public final Context requireContext() {
        throw new UnsupportedOperationException("Stub");
    }

    public View getView() {
        throw new UnsupportedOperationException("Stub");
    }

    public final boolean isHidden() {
        throw new UnsupportedOperationException("Stub");
    }

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        throw new UnsupportedOperationException("Stub");
    }

    public void onDestroyView() {
        throw new UnsupportedOperationException("Stub");
    }

    public void onHiddenChanged(boolean hidden) {
        throw new UnsupportedOperationException("Stub");
    }

    public void onResume() {
        throw new UnsupportedOperationException("Stub");
    }

    public void onPause() {
        throw new UnsupportedOperationException("Stub");
    }
}
