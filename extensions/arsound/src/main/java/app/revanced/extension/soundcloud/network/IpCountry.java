package app.revanced.extension.soundcloud.network;

/**
 * Countries whose IP addresses the app names in words ("российский IP", "Ukrainian IP"); any other country code is
 * named by the code. Pure, no Android.
 */
public enum IpCountry {
    RUSSIA("RU", "российский IP", "Russian IP"),
    UKRAINE("UA", "украинский IP", "Ukrainian IP"),
    BELARUS("BY", "белорусский IP", "Belarusian IP"),
    KAZAKHSTAN("KZ", "казахстанский IP", "Kazakh IP");

    private final String code;
    private final String russian;
    private final String english;

    IpCountry(String code, String russian, String english) {
        this.code = code;
        this.russian = russian;
        this.english = english;
    }

    /** "российский IP", "Ukrainian IP", "IP страны DE": the country of the address in words. */
    public static String describe(String code, boolean russian) {
        for (IpCountry country : values()) {
            if (country.code.equals(code)) return russian ? country.russian : country.english;
        }
        return russian ? "IP страны " + code : "IP from " + code;
    }

    /** {@link #describe} with the first letter capital, to start a sentence. */
    public static String describeCapitalized(String code, boolean russian) {
        String value = describe(code, russian);
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
