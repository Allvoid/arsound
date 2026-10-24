package app.revanced.extension.soundcloud.branding;

/**
 * SoundCloud's own app icons, in the same columns as {@link AppIconList}. The branding patch copies their pictures
 * from the patched APK (SOUNDCLOUD_APP_ICONS in the patches), so the ids must match it.
 */
final class SoundCloudIconList {
    static final String[][] ICONS = {
            {"sc_orange", "Оригинал", "Original", "SoundCloud", "SoundCloud"},
            {"sc_og", "OG", "OG", "SoundCloud", "SoundCloud"},
            {"sc_black", "Чёрная", "Black", "SoundCloud", "SoundCloud"},
            {"sc_blue", "Синяя", "Blue", "SoundCloud", "SoundCloud"},
            {"sc_lavender", "Лаванда", "Lavender", "SoundCloud", "SoundCloud"},
            {"sc_lime", "Лайм", "Lime", "SoundCloud", "SoundCloud"},
            {"sc_sunset", "Закат", "Sunset", "SoundCloud", "SoundCloud"},
            {"sc_soft_purple", "Сиреневая", "Soft purple", "SoundCloud", "SoundCloud"},
            {"sc_hot_pink", "Малиновая", "Hot pink", "SoundCloud", "SoundCloud"},
            {"sc_silver", "Серебро", "Silver", "SoundCloud", "SoundCloud"},
            {"sc_rose_gold", "Розовое золото", "Rose gold", "SoundCloud", "SoundCloud"},
            {"sc_chrome", "Хром", "Chrome", "SoundCloud", "SoundCloud"},
            {"sc_tie_dye", "Тай-дай", "Tie-dye", "SoundCloud", "SoundCloud"},
            {"sc_leopard", "Леопард", "Leopard", "SoundCloud", "SoundCloud"},
    };

    private SoundCloudIconList() {
    }
}
