package app.revanced.extension.soundcloud.download;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import app.revanced.extension.shared.Logger;

/**
 * Has no screen: opens Android's own confirmation for removing files the app cannot remove by itself (the files of an
 * earlier install belong to that install for Android, Android 11 and later) and closes with the answer. The track and
 * the playlist menus are in SoundCloud's activity, which cannot take the answer.
 */
public final class DeleteConsentActivity extends Activity {
    private static final String FILES = "files";
    private static final int REQUEST = 0x4144;
    private static volatile Consumer<Boolean> pending;

    /**
     * Asks the user to allow removing the files.
     *
     * @param done Called with whether the files were removed.
     * @return False if the question could not be opened, then {@code done} is not called.
     */
    static boolean ask(Context context, List<Uri> files, Consumer<Boolean> done) {
        if (files.isEmpty() || android.os.Build.VERSION.SDK_INT < 30) return false;
        try {
            Intent intent = new Intent(context, DeleteConsentActivity.class)
                    .putParcelableArrayListExtra(FILES, new ArrayList<>(files));
            if (!(context instanceof Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            pending = done;
            context.startActivity(intent);
            return true;
        } catch (Exception ex) {
            pending = null;
            Logger.printException(() -> "Could not ask to delete the files", ex);
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Made again after the app was closed: nobody waits for the answer.
        if (pending == null) {
            finish();
            return;
        }
        // Made again while the confirmation is open (the screen turned): its answer comes to this one.
        if (state != null) return;
        try {
            List<Uri> files = getIntent().getParcelableArrayListExtra(FILES);
            startIntentSenderForResult(MediaStore.createDeleteRequest(getContentResolver(), files).getIntentSender(),
                    REQUEST, null, 0, 0, 0);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open the delete confirmation", ex);
            answer(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST) answer(resultCode == RESULT_OK);
    }

    private void answer(boolean granted) {
        Consumer<Boolean> done = pending;
        pending = null;
        finish();
        if (done != null) done.accept(granted);
    }
}
