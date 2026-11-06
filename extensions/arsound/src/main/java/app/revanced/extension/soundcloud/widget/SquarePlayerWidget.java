package app.revanced.extension.soundcloud.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.RemoteViews;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import app.revanced.extension.soundcloud.player.PlayModeButton;
import app.revanced.extension.soundcloud.player.PlayModeReceiver;
import app.revanced.extension.soundcloud.recommendations.DislikeAction;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.SerialWork;

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

    /**
     * The square is built and sent on a thread of its own, in order, and only for the newest state: it was built a
     * second time on the main thread after SoundCloud's own widget, at every change of track, cover or play state.
     */
    private static final SerialWork WORK = new SerialWork("arsound-square-widget");
    private static final AtomicInteger latest = new AtomicInteger();
    /**
     * The longest side the cover is cut to for the square. SoundCloud hands the widget the cover in full size, and the
     * square sent it once more, serialized for the home screen; the square is never bigger than this on a screen.
     */
    private static final int COVER_SIZE = 300;
    /** The cover last cut, and what it was cut from: a pause or a heart does not cut it again. Work thread only. */
    /** Weak: SoundCloud's full-size bitmap must not stay in memory for a comparison. */
    private static java.lang.ref.WeakReference<Bitmap> coverSource = new java.lang.ref.WeakReference<>(null);
    private static Bitmap coverCut;
    /** The fields of SoundCloud's classes, found once per class and kind. */
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final Field NO_FIELD;
    private static volatile Method build;

    static {
        Field none = null;
        try {
            none = SquarePlayerWidget.class.getDeclaredField("COVER_SIZE");
        } catch (NoSuchFieldException ignored) {
            // Cannot happen: the field is right here.
        }
        NO_FIELD = none;
    }

    /** SoundCloud's widget state at the moment it was shown, taken on the main thread where SoundCloud changes it. */
    private static final class Snapshot {
        final Class<?> builderClass;
        final Object item, playing, factory, resources;
        final Bitmap cover;
        final int layoutId;

        Snapshot(Object builder, Object factory, Object resources, int layoutId) throws IllegalAccessException {
            builderClass = builder.getClass();
            item = valueEndingWith(builder, "WidgetItem");
            playing = valueOfType(builder, Boolean.class);
            cover = (Bitmap) valueOfType(builder, Bitmap.class);
            this.factory = factory;
            this.resources = resources;
            this.layoutId = layoutId;
        }
    }

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
            Context context = (Context) valueOfType(presenter, Context.class);
            if (context == null) return;
            Object builder = valueEndingWith(presenter, "PlayerWidgetRemoteViewsBuilder");
            Object factory = valueEndingWith(presenter, "WidgetIntentFactory");
            Object resources = valueEndingWith(presenter, "WidgetResourceProvider");
            Snapshot snapshot = builder == null || factory == null || resources == null
                    ? null : new Snapshot(builder, factory, resources, views.getLayoutId());
            int mine = latest.incrementAndGet();
            WORK.execute(() -> {
                // A newer state came while this one waited: only that one is built.
                if (mine != latest.get()) return;
                send(context, snapshot);
            });
        } catch (Throwable ex) {
            Logger.printException(() -> "Square widget: could not update", ex);
        }
    }

    /** Builds the square and hands it to the home screen. Work thread. */
    private static void send(Context context, Snapshot snapshot) {
        try {
            ComponentName square = new ComponentName(context, SquarePlayerWidget.class);
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            int[] ids = manager.getAppWidgetIds(square);
            if (ids == null || ids.length == 0) return;
            manager.updateAppWidget(square, snapshot == null ? emptyViews(context) : squareViews(context, snapshot));
        } catch (Throwable ex) {
            Logger.printException(() -> "Square widget: could not update", ex);
        }
    }

    /**
     * The square for the same state as SoundCloud's widget, or the empty square when that one shows no track. A
     * builder of its own is filled with the state taken on the main thread and a cover cut to the square's size.
     */
    private static RemoteViews squareViews(Context context, Snapshot snapshot) throws Exception {
        Object resources = snapshot.resources;
        Class<?> resourcesType = interfaceEndingWith(resources.getClass(), "WidgetResourceProvider");
        // SoundCloud's empty widget ("touch to open") has a layout of its own, not the player's.
        int playerLayout = (int) resourcesType.getMethod("getLayoutId").invoke(resources);
        if (snapshot.layoutId != playerLayout) return emptyViews(context);
        Object builder = snapshot.builderClass.getConstructor().newInstance();
        setEndingWith(builder, "WidgetItem", snapshot.item);
        setOfType(builder, Boolean.class, snapshot.playing);
        setOfType(builder, Bitmap.class, cut(snapshot.cover));
        Object factory = snapshot.factory;

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
        Method build = buildMethod(builder.getClass());
        if (build == null) return emptyViews(context);
        RemoteViews square = (RemoteViews) build.invoke(builder, context, factory, squareResources);
        // White on the dark bottom of the cover: no colour of the card, which the 4 x 1 widget's heart is fitted to.
        int heart = resource(context, "id", "btn_like");
        if (heart != 0) square.setInt(heart, "setColorFilter", liked[0] ? 0xFFFF5A7A : Color.TRANSPARENT);
        addDislike(context, square);
        return square;
    }

    /** The broken heart in the corner: "not for me" for the playing track and on to the next one; a broadcast to the receiver of the notification button. */
    private static void addDislike(Context context, RemoteViews square) {
        int button = resource(context, "id", "arsound_widget_dislike");
        if (button == 0) return;
        if (!DislikeAction.isAvailable()) {
            square.setViewVisibility(button, android.view.View.GONE);
            return;
        }
        Intent intent = new Intent(PlayModeButton.DISLIKE_ACTION).setClass(context, PlayModeReceiver.class)
                .setPackage(context.getPackageName());
        square.setOnClickPendingIntent(button, PendingIntent.getBroadcast(context, 0x507B, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
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

    /** The cover cut to {@link #COVER_SIZE} on its longest side, or the cover itself when it is small. */
    private static Bitmap cut(Bitmap cover) {
        if (cover == null || cover.isRecycled()) return null;
        int side = Math.max(cover.getWidth(), cover.getHeight());
        if (side <= COVER_SIZE) return cover;
        if (cover != coverSource.get()) {
            float scale = COVER_SIZE / (float) side;
            coverCut = Bitmap.createScaledBitmap(cover, Math.max(1, Math.round(cover.getWidth() * scale)),
                    Math.max(1, Math.round(cover.getHeight() * scale)), true);
            coverSource = new java.lang.ref.WeakReference<>(cover);
        }
        return coverCut;
    }

    /** The builder's method that makes the widget (context, intents, resources), found once. */
    private static Method buildMethod(Class<?> builderClass) {
        Method known = build;
        if (known != null && known.getDeclaringClass().isAssignableFrom(builderClass)) return known;
        for (Method method : builderClass.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 3 && parameters[0] == Context.class && RemoteViews.class.isAssignableFrom(method.getReturnType())) {
                build = method;
                return method;
            }
        }
        return null;
    }

    /** The first field of the owner's class whose type is the given one or ends with the name; looked up once. */
    private static Field field(Class<?> owner, Class<?> type, String typeName) {
        String key = owner.getName() + "/" + (type != null ? type.getName() : "*" + typeName);
        Field known = FIELDS.get(key);
        if (known != null) return known == NO_FIELD ? null : known;
        Field found = NO_FIELD;
        for (Field field : owner.getDeclaredFields()) {
            boolean matches = type != null ? type.isAssignableFrom(field.getType()) : field.getType().getName().endsWith(typeName);
            if (matches) {
                field.setAccessible(true);
                found = field;
                break;
            }
        }
        FIELDS.put(key, found);
        return found == NO_FIELD ? null : found;
    }

    private static Object valueOfType(Object owner, Class<?> type) throws IllegalAccessException {
        Field field = field(owner.getClass(), type, null);
        return field == null ? null : field.get(owner);
    }

    private static Object valueEndingWith(Object owner, String typeName) throws IllegalAccessException {
        Field field = field(owner.getClass(), null, typeName);
        return field == null ? null : field.get(owner);
    }

    private static void setOfType(Object owner, Class<?> type, Object value) throws IllegalAccessException {
        Field field = field(owner.getClass(), type, null);
        if (field != null) field.set(owner, value);
    }

    private static void setEndingWith(Object owner, String typeName, Object value) throws IllegalAccessException {
        Field field = field(owner.getClass(), null, typeName);
        if (field != null) field.set(owner, value);
    }

    private static Class<?> interfaceEndingWith(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> candidate : current.getInterfaces()) if (candidate.getName().endsWith(name)) return candidate;
        }
        throw new IllegalStateException("No " + name + " in " + type);
    }
}
