package app.revanced.extension.soundcloud.upsell;

import android.app.Activity;
import android.os.Bundle;

/**
 * An invisible screen that closes as soon as it opens. Replaces the subscription offer screen.
 */
public final class EmptyActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
        overridePendingTransition(0, 0);
    }
}
