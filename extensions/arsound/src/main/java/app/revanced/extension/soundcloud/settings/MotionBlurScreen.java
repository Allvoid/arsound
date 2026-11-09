package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import app.revanced.extension.soundcloud.motion.MotionBlur;
import app.revanced.extension.soundcloud.motion.MotionBlurSettings;

/**
 * Motion blur: a list or a pager that moves fast is blurred along its movement. One switch, two areas (scrolling and
 * swipes) and two sliders (how strong, how much longer along the movement than across it). Opens from the Appearance
 * section; it has no row of its own on the main screen. The screen is a long list itself, so it can be tried here:
 * scroll it fast. Behaviour: {@code motion.MotionBlur}.
 */
final class MotionBlurScreen extends SectionScreen {
    static final String ID = "motion_blur";

    MotionBlurScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Размытие в движении", "Motion blur");
    }

    @Override
    String iconKey() {
        return "animation";
    }

    @Override
    void fill(LinearLayout list) {
        SubRows options = ui.createSubRows();
        boolean supported = MotionBlur.isSupported();
        list.addView(ui.createSubHeading(text("Размытие", "Blur")));
        list.addView(ui.createToggleRow(
                text("Размытие в движении", "Motion blur"),
                text("Быстрая прокрутка или свайп размывает список по направлению движения: чем быстрее, тем сильнее. "
                                + "Медленное чтение остаётся чётким. Не работает в режиме экономии батареи и когда анимации "
                                + "выключены в Android. Нужен Android 12 или новее."
                                + (supported ? "" : " На этом телефоне его нет."),
                        "A fast scroll or swipe blurs the list along the movement: the faster, the stronger. Slow reading "
                                + "stays sharp. Off in battery saver and when Android's animations are off. Needs Android 12 "
                                + "or newer." + (supported ? "" : " This phone has an older one.")),
                BoolSettings.MOTION_BLUR,
                options,
                (button, checked) -> MotionBlur.refresh(activity)
        ).icon("animation").brief(text("Список размывается, пока летит под пальцем.",
                "The list blurs while it flies under the finger.")));

        options.addView(ui.createToggleRow(
                text("Скролл", "Scrolling"),
                text("Списки и ленты при прокрутке вверх и вниз.", "Lists and feeds when scrolled up and down."),
                BoolSettings.MOTION_BLUR_SCROLL));
        options.addView(ui.createToggleRow(
                text("Свайпы", "Swipes"),
                text("Страницы и вкладки при свайпе в стороны: просмотрщики, вкладки. На экранах, которые SoundCloud рисует "
                                + "сам (Compose), в стороны не размывается.",
                        "Pages and tabs when swiped sideways: viewers, tabs. Screens SoundCloud draws itself (Compose) "
                                + "do not blur sideways."),
                BoolSettings.MOTION_BLUR_SWIPES));
        options.addView(ui.createSliderRow(text("Размытие", "Blur"), "",
                100, MotionBlurSettings.amount(), value -> String.valueOf(value),
                value -> {
                }, MotionBlurSettings::setAmount, null));
        options.addView(ui.createSliderRow(text("Сила растягивания", "Stretch"),
                text("Во сколько раз размытие вдоль движения длиннее, чем поперёк: 1,00x — одинаково во все стороны.",
                        "How many times longer the blur is along the movement than across it: 1.00x is the same all round."),
                300, MotionBlurSettings.stretch() - 100, value -> stretchText(value + 100),
                value -> {
                }, value -> MotionBlurSettings.setStretch(value + 100), null));
        list.addView(options);

        list.addView(ui.createSubHeading(text("Проба", "Try it")));
        TextView note = ui.createText("Body.Secondary", text(
                "Этот экран сам длинный: проведите по нему быстро вверх и вниз.",
                "This screen is long itself: swipe it fast up and down."));
        note.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(10));
        list.addView(note);
        for (int i = 1; i <= 24; i++) {
            TextView line = ui.createText("H4.Primary", text("Трек ", "Track ") + i + " · "
                    + text("Артист и название песни", "Artist and song title"));
            line.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
            list.addView(line);
        }
    }

    private static String stretchText(int hundredths) {
        return String.format(Locale.US, "%d.%02dx", hundredths / 100, hundredths % 100);
    }
}
