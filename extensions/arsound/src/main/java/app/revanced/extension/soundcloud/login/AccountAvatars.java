package app.revanced.extension.soundcloud.login;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileOutputStream;

import app.revanced.extension.shared.Logger;

/**
 * The pictures of the kept accounts: SoundCloud loads the signed-in user's picture into the profile button of the top
 * bar, and a copy of it is taken from there when the list of accounts is opened. Small files in the app's private
 * folder, one per user id.
 */
final class AccountAvatars {
    private static final String FOLDER = "arsound_accounts";
    /** The size the copy is kept at, in pixels: the list draws it at 44 dp. */
    private static final int SIZE = 160;

    private AccountAvatars() {
    }

    static File file(Context context, SavedAccount account) {
        if (account == null || account.userId == null) return null;
        return new File(new File(context.getFilesDir(), FOLDER), account.userId.replaceAll("[^0-9A-Za-z]", "") + ".png");
    }

    /** Keeps the picture the profile button shows now as the signed-in account's picture. Off the main thread is fine. */
    static void capture(Context context, SavedAccount account, Drawable picture) {
        File file = file(context, account);
        if (file == null || picture == null) return;
        // Only a loaded photo: a user without one has SoundCloud's grey figure, a vector, and the letter looks better.
        // The button's drawable is a ripple with the picture inside it.
        Drawable photo = photo(picture, 0);
        if (photo == null) {
            Logger.printInfo(() -> "Accounts: the profile button shows no photo (" + picture.getClass().getSimpleName() + ")");
            // Only the grey figure is a sure sign of "no photo". A photo still loading looks the same to us, and the
            // kept picture must not go because of it.
            if (isPlaceholder(picture, 0)) forget(context, account);
            return;
        }
        Bitmap bitmap = null;
        File temporary = null;
        try {
            bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            Drawable copy = photo.getConstantState() != null ? photo.getConstantState().newDrawable().mutate() : photo;
            copy.setBounds(0, 0, SIZE, SIZE);
            copy.draw(canvas);
            File folder = file.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) return;
            // Written beside the picture and renamed over it: a break in the middle leaves the old one, not half a PNG.
            temporary = new File(file.getPath() + ".tmp");
            boolean written;
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                written = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            if (written && temporary.renameTo(file)) {
                temporary = null;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Accounts: could not keep the picture of the account", ex);
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (temporary != null && !temporary.delete() && temporary.exists()) {
                Logger.printInfo(() -> "Accounts: could not delete a half-written picture");
            }
        }
    }

    /** True when the drawable is SoundCloud's grey figure (a vector), possibly inside a ripple or an inset. */
    private static boolean isPlaceholder(Drawable drawable, int depth) {
        if (drawable == null || depth > 4) return false;
        if (drawable.getClass().getName().contains("Vector")) return true;
        if (drawable instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable layers = (android.graphics.drawable.LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                if (isPlaceholder(layers.getDrawable(i), depth + 1)) return true;
            }
            return false;
        }
        if (android.os.Build.VERSION.SDK_INT >= 23 && drawable instanceof android.graphics.drawable.DrawableWrapper) {
            return isPlaceholder(((android.graphics.drawable.DrawableWrapper) drawable).getDrawable(), depth + 1);
        }
        return false;
    }

    /** The photo inside the button's drawable (a ripple, an inset), or null if it shows none. */
    private static Drawable photo(Drawable drawable, int depth) {
        if (drawable == null || depth > 4) return null;
        if (drawable.getClass().getName().contains("Bitmap")) {
            return drawable.getIntrinsicWidth() > 0 && drawable.getIntrinsicHeight() > 0 ? drawable : null;
        }
        if (drawable instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable layers = (android.graphics.drawable.LayerDrawable) drawable;
            for (int i = 0; i < layers.getNumberOfLayers(); i++) {
                Drawable found = photo(layers.getDrawable(i), depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        if (android.os.Build.VERSION.SDK_INT >= 23 && drawable instanceof android.graphics.drawable.DrawableWrapper) {
            return photo(((android.graphics.drawable.DrawableWrapper) drawable).getDrawable(), depth + 1);
        }
        return null;
    }

    static void forget(Context context, SavedAccount account) {
        File file = file(context, account);
        if (file != null && file.isFile() && !file.delete()) {
            Logger.printInfo(() -> "Accounts: could not delete a picture");
        }
    }

    /** The picture cut to a circle of the given size. */
    static Bitmap round(Bitmap picture, int size) {
        Bitmap result = Bitmap.createBitmap(Math.max(1, size), Math.max(1, size), Bitmap.Config.ARGB_8888);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        BitmapShader shader = new BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        Matrix scale = new Matrix();
        float factor = (float) size / Math.min(picture.getWidth(), picture.getHeight());
        scale.setScale(factor, factor);
        shader.setLocalMatrix(scale);
        paint.setShader(shader);
        new Canvas(result).drawCircle(size / 2f, size / 2f, size / 2f, paint);
        return result;
    }
}
