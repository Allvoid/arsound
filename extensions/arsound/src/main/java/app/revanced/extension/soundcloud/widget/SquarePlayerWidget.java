package app.revanced.extension.soundcloud.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.widget.RemoteViews;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import app.revanced.extension.shared.Logger;

/**
 * The player widget 2 x 2: the cover over the whole square, the title and the artist over its darkened bottom, the
 * previous, play and next buttons under them and the heart in the corner.
 * <p>
 * It is filled by SoundCloud's own widget builder: each time SoundCloud updates its player widget
 * ({@code PlayerWidgetPresenter}), the same builder is run once more with a resource provider that gives this
 * widget's layout ({@code arsound_widget_square}, which has the ids of SoundCloud's widget) and its round play, pause
 * and heart icons. So the texts, the cover, the play state and the actions of every button are SoundCloud's, and the
 * square follows the 4 x 1 widget whether that one is on the home screen or not.
 */
public final class SquarePlayerWidget extends AppWidgetProvider {
    private static final String LAYOUT = "arsound_widget_square";
    private static final String EMPTY_LAYOUT = "arsound_widget_square_empty";
    /** SoundCloud's provider of its player widget: asked to build again when a square is added. */
    private static final String SOUNDCLOUD_PROVIDER = "com.soundcloud.android.playback.widget.service.PlayerAppWidgetProvider";

    /** SoundCloud's resources the builder asks for, and the square's own in their place. */
    private static final String[][] SWAPS = {
            {"drawable", "ic_playback_play_widget", "arsound_widget_play"},
            {"drawable", "ic_playback_pause_widget", "arsound_widget_pause"},
            {"drawable", "ic_actions_heart_widget", "arsound_widget_heart"},
            {"drawable", "ic_actions_heart_active_widget", "arsound_widget_heart_active"},
    };
    private static volatile Map<Integer, Integer> swaps;

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        // Something to show at once; SoundCloud fills it with the track when it has built its widget again.
        manager.updateAppWidget(ids, emptyViews(context));
        try {
            context.sendBroadcast(new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(new ComponentName(context, SOUNDCLOUD_PROVIDER))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids));
        } catch (Throwable ex) {
            Logger.printException(() -> "Square widget: could not ask SoundCloud for its widget", ex);
        }
    }

    /**
     * Injection point, at the start of {@code PlayerWidgetPresenter.b(RemoteViews)}, which hands SoundCloud's widget
     * to the home screen. With no square on the home screen nothing is done.
     *
     * @param presenter SoundCloud's presenter: its context, builder, intent factory and resource provider.
     * @param views     SoundCloud's widget as it is about to be shown.
     */
    public static void onPlayerWidget(Object presenter, RemoteViews views) {
        try {
            Context context = (Context) fieldOfType(presenter, Context.class);
            if (context == null) return;
            ComponentName square = new ComponentName(context, SquarePlayerWidget.class);
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(square);
            if (ids == null || ids.length == 0) return;
            manager.updateAppWidget(square, squareViews(context, presenter, views));
        } catch (Throwable ex) {
            Logger.printException(() -> "Square widget: could not update", ex);
        }
    }

    /** The square for the same state as SoundCloud's widget, or the empty square when that one shows no track. */
    private static RemoteViews squareViews(Context context, Object presenter, RemoteViews views) throws Exception {
        Object builder = fieldEndingWith(presenter, "PlayerWidgetRemoteViewsBuilder");
        Object factory = fieldEndingWith(presenter, "WidgetIntentFactory");
        Object resources = fieldEndingWith(presenter, "WidgetResourceProvider");
        if (builder == null || factory == null || resources == null) return emptyViews(context);
        Class<?> resourcesType = interfaceEndingWith(resources.getClass(), "WidgetResourceProvider");
        // SoundCloud's empty widget ("touch to open") has a layout of its own, not the player's.
        int playerLayout = (int) resourcesType.getMethod("getLayoutId").invoke(resources);
        if (views.getLayoutId() != playerLayout) return emptyViews(context);

        int layout = resource(context, "layout", LAYOUT);
        Map<Integer, Integer> swapped = swaps(context);
        boolean[] liked = {false};
        Object squareResources = Proxy.newProxyInstance(resourcesType.getClassLoader(), new Class<?>[]{resourcesType},
                (proxy, method, args) -> {
                    if (method.getName().equals("getLayoutId")) return layout;
                    Object answer = method.invoke(resources, args);
                    if (answer instanceof Integer) {
                        Integer own = swapped.get(answer);
                        if (own != null) {
                            if (own == resource(context, "drawable", "arsound_widget_heart_active")) liked[0] = true;
                            return own;
                        }
                    }
                    return answer;
                });
        Method build = null;
        for (Method method : builder.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 3 && parameters[0] == Context.class && RemoteViews.class.isAssignableFrom(method.getReturnType())) {
                build = method;
                break;
            }
        }
        if (build == null) return emptyViews(context);
        RemoteViews square = (RemoteViews) build.invoke(builder, context, factory, squareResources);
        // White on the dark bottom of the cover: no colour of the card, which the 4 x 1 widget's heart is fitted to.
        int heart = resource(context, "id", "btn_like");
        if (heart != 0) square.setInt(heart, "setColorFilter", liked[0] ? 0xFFFF5A7A : Color.TRANSPARENT);
        return square;
    }

    /** The square before anything played: the logo, "touch to open", and a tap opens the app. */
    static RemoteViews emptyViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), resource(context, "layout", EMPTY_LAYOUT));
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        int body = resource(context, "id", "widget_body");
        if (launch != null && body != 0) {
            views.setOnClickPendingIntent(body, PendingIntent.getActivity(context, 0x5A2, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
        return views;
    }

    private static Map<Integer, Integer> swaps(Context context) {
        Map<Integer, Integer> map = swaps;
        if (map != null) return map;
        map = new HashMap<>();
        for (String[] swap : SWAPS) {
            int theirs = resource(context, swap[0], swap[1]);
            int ours = resource(context, swap[0], swap[2]);
            if (theirs != 0 && ours != 0) map.put(theirs, ours);
        }
        swaps = map;
        return map;
    }

    private static int resource(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }

    private static Object fieldOfType(Object owner, Class<?> type) throws IllegalAccessException {
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (type.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                return field.get(owner);
            }
        }
        return null;
    }

    private static Object fieldEndingWith(Object owner, String typeName) throws IllegalAccessException {
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (field.getType().getName().endsWith(typeName)) {
                field.setAccessible(true);
                return field.get(owner);
            }
        }
        return null;
    }

    private static Class<?> interfaceEndingWith(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> candidate : current.getInterfaces()) if (candidate.getName().endsWith(name)) return candidate;
        }
        throw new IllegalStateException("No " + name + " in " + type);
    }
}
