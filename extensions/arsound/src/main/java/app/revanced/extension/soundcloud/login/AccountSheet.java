package app.revanced.extension.soundcloud.login;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.List;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The list of SoundCloud accounts, as in other apps: the accounts kept on this phone with the one signed in now marked,
 * a tap on another one switches to it, and "Add account" opens the sign-in for one more. A long press on a kept one
 * offers to take it off the list.
 */
public final class AccountSheet {
    private AccountSheet() {
    }

    /** Opens the list over the activity. The signed-in account is kept first, off the main thread. */
    public static void show(Activity activity) {
        Utils.runOnBackgroundThread(() -> {
            AccountSwitch.snapshotCurrent(activity);
            String current = AccountSwitch.currentName(activity);
            List<SavedAccount> accounts = AccountVault.list(activity);
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) build(activity, accounts, current);
            });
        });
    }

    private static void build(Activity activity, List<SavedAccount> accounts, String current) {
        int primary = ArsoundTheme.palette(activity, "primary");
        int secondary = ArsoundTheme.palette(activity, "secondary");
        int accent = ArsoundTheme.palette(activity, "special");
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        Dialog[] shown = new Dialog[1];

        for (SavedAccount account : accounts) {
            boolean isCurrent = account.name.equals(current);
            String subtitle = isCurrent
                    ? Lang.text("Вы вошли в этот аккаунт", "Signed in now")
                    : (account.email != null ? account.email : Lang.text("Нажмите, чтобы переключиться", "Tap to switch"));
            View row = row(activity, avatar(activity, account, accent), account.shownName(), subtitle,
                    isCurrent ? "✓" : null, primary, secondary, accent);
            row.setOnClickListener(v -> {
                if (shown[0] != null) shown[0].dismiss();
                if (!isCurrent) AccountSwitch.switchTo(activity, account);
            });
            if (!isCurrent) {
                row.setOnLongClickListener(v -> {
                    if (shown[0] != null) shown[0].dismiss();
                    confirmForget(activity, account);
                    return true;
                });
            }
            list.addView(row);
        }

        View add = row(activity, plus(activity, primary), Lang.text("Добавить аккаунт", "Add account"),
                current == null ? null : Lang.text("Этот аккаунт останется в списке", "This account stays on the list"),
                null, primary, secondary, accent);
        add.setOnClickListener(v -> {
            if (shown[0] != null) shown[0].dismiss();
            confirmAdd(activity);
        });
        list.addView(add);

        TextView note = new TextView(activity);
        note.setText(Lang.text("При смене приложение перезапустится. Скачанные треки и настройки Arsound общие для всех аккаунтов.",
                "Switching restarts the app. Downloaded tracks and Arsound settings are shared by all accounts."));
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setTextColor(secondary);
        note.setPadding(dp(activity, 24), dp(activity, 10), dp(activity, 24), 0);
        list.addView(note);

        shown[0] = new ArsoundDialog(activity)
                .setTitle(Lang.text("Аккаунты SoundCloud", "SoundCloud accounts"))
                .setView(list)
                .setNegativeButton(Lang.text("Закрыть", "Close"), null)
                .show();
    }

    private static void confirmAdd(Activity activity) {
        // Ten are kept: the new account could not be kept after its sign-in, so it is told now, before anything is wiped.
        if (!AccountVault.canAddAnother(AccountVault.list(activity).size())) {
            AccountSwitch.listFull(activity);
            return;
        }
        new ArsoundDialog(activity)
                .setTitle(Lang.text("Добавить аккаунт?", "Add an account?"))
                .setMessage(Lang.text("Приложение перезапустится и откроет вход в SoundCloud. Текущий аккаунт "
                                + "останется в списке: вернуться к нему можно в одно касание — долгим нажатием на "
                                + "аватарку или в настройках.",
                        "The app restarts and opens the SoundCloud sign-in. The current account stays on the list: "
                                + "you can come back to it with one tap, by a long press on the avatar or in the settings."))
                .setPositiveButton(Lang.text("Добавить", "Add"), (dialog, which) -> AccountSwitch.addAccount(activity))
                .setNegativeButton(Lang.text("Отмена", "Cancel"), null)
                .show();
    }

    private static void confirmForget(Activity activity, SavedAccount account) {
        new ArsoundDialog(activity)
                .setTitle(Lang.text("Убрать из списка?", "Remove from the list?"))
                .setMessage(Lang.text("«" + account.shownName() + "» пропадёт из этого списка. Сам аккаунт SoundCloud "
                                + "не удаляется: войти в него можно снова.",
                        "\"" + account.shownName() + "\" leaves this list. The SoundCloud account itself is not "
                                + "deleted: you can sign in to it again."))
                .setPositiveButton(Lang.text("Убрать", "Remove"), (dialog, which) -> Utils.runOnBackgroundThread(() -> {
                    AccountVault.remove(activity, account.name);
                    AccountAvatars.forget(activity, account);
                }))
                .setNegativeButton(Lang.text("Отмена", "Cancel"), null)
                .show();
    }

    /** One line: a round picture, the name with a line under it, and a mark at the end. */
    static View row(Activity activity, View picture, String title, String subtitle, String mark,
                    int primary, int secondary, int accent) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(activity, 64));
        row.setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 20), dp(activity, 8));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(Colors.withAlpha(primary, 0x22)), null,
                new ColorDrawable(0xFFFFFFFF)));
        int size = dp(activity, 44);
        row.addView(picture, new LinearLayout.LayoutParams(size, size));

        LinearLayout texts = new LinearLayout(activity);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(activity, 14), 0, dp(activity, 8), 0);
        TextView name = new TextView(activity);
        name.setText(title);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTextColor(primary);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(name);
        if (subtitle != null) {
            TextView under = new TextView(activity);
            under.setText(subtitle);
            under.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            under.setTextColor(secondary);
            under.setSingleLine(true);
            under.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(under);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (mark != null) {
            TextView end = new TextView(activity);
            end.setText(mark);
            end.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            end.setTextColor(accent);
            end.setTag(ArsoundDialog.KEEP_COLORS);
            row.addView(end);
        }
        return row;
    }

    /** The account's picture if one was seen, its first letter on the accent otherwise. */
    static View avatar(Activity activity, SavedAccount account, int accent) {
        View view = new View(activity) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Bitmap picture = load(AccountAvatars.file(activity, account));
            private Bitmap rounded;

            @Override
            protected void onDraw(Canvas canvas) {
                float r = Math.min(getWidth(), getHeight()) / 2f;
                if (picture != null) {
                    int size = Math.round(r * 2);
                    if (rounded == null || rounded.getWidth() != size) rounded = AccountAvatars.round(picture, size);
                    canvas.drawBitmap(rounded, 0, 0, null);
                    return;
                }
                paint.setColor(accent);
                canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r, paint);
                paint.setColor(Colors.contrast(accent));
                paint.setTextSize(r);
                paint.setTypeface(Typeface.DEFAULT_BOLD);
                paint.setTextAlign(Paint.Align.CENTER);
                String name = account.shownName();
                String letter = name.isEmpty() ? "?" : name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase();
                Paint.FontMetrics metrics = paint.getFontMetrics();
                canvas.drawText(letter, getWidth() / 2f, getHeight() / 2f - (metrics.ascent + metrics.descent) / 2f, paint);
            }
        };
        view.setTag(ArsoundDialog.KEEP_COLORS);
        return view;
    }

    private static Bitmap load(File file) {
        if (file == null || !file.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(file.getPath());
        } catch (Exception ex) {
            return null;
        }
    }

    /** A round outline with a plus, for "Add account". */
    static View plus(Activity activity, int primary) {
        View view = new View(activity) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas canvas) {
                float cx = getWidth() / 2f, cy = getHeight() / 2f;
                float r = Math.min(cx, cy);
                float stroke = Dimens.dpExact(activity, 1.5f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(stroke);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(Colors.withAlpha(primary, 0x99));
                canvas.drawCircle(cx, cy, r - stroke, paint);
                paint.setColor(primary);
                float arm = r * 0.38f;
                canvas.drawLine(cx - arm, cy, cx + arm, cy, paint);
                canvas.drawLine(cx, cy - arm, cx, cy + arm, paint);
            }
        };
        view.setTag(ArsoundDialog.KEEP_COLORS);
        return view;
    }

    private static int dp(Activity activity, float value) {
        return Dimens.dp(activity, value);
    }
}
