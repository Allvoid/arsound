package com.soundcloud.android.playback.session;

import com.soundcloud.android.foundation.domain.Urn;

/**
 * A state of the media session as the app sees it. The "Arsound" patch checks these names, so a renamed method fails
 * the patch, not the app.
 */
public interface PlayState {
    /** @return True while playing. */
    boolean a();

    /** @return True while buffering or connecting. */
    boolean h();

    /** @return The track (or other item) the state is about. */
    Urn o();
}
