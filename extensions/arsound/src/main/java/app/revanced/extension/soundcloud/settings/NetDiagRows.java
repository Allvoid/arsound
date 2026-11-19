package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicReference;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.network.CustomDns;
import app.revanced.extension.soundcloud.network.NetDiag;
import app.revanced.extension.soundcloud.network.NetDiagRunner;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/**
 * The network diagnostics of the network screen, as on Arsound Desktop: a table with a row per site (the DNS of the
 * phone against the custom DNS, whether HTTPS answers, ok / slow / fail), a check of a domain typed by hand, and a copy
 * of the whole result. The asking is {@link NetDiagRunner}; the texts and verdicts are {@link NetDiag}.
 * <p>
 * The check runs off the main thread and reports through a weak reference, so a screen that was closed meanwhile is
 * not kept alive by it.
 */
final class NetDiagRows {
    private static final int GREEN = 0xFF3DDC84;
    private static final int AMBER = 0xFFFFB300;
    private static final int RED = 0xFFFF5252;

    private final ReVancedSettingsActivity activity;
    private final SettingsUi ui;

    private LinearLayout table;
    private ActionRow startRow;
    private ActionRow verdictRow;
    /** The rows of the table by site, in the order shown. */
    private final Map<String, ActionRow> siteRows = new LinkedHashMap<>();
    /** The result of the last check: the sites, then the domains typed by hand. */
    private final List<NetDiag.Row> results = new ArrayList<>();
    private String country = "";
    private boolean cancelled;
    private String checkedAt = "";
    private String dnsPath = "";
    /** Counts the checks; an answer of an older one is dropped. Main thread only. */
    private int generation;
    /**
     * The check under way, whichever screen started it: the screen is rebuilt on every visit, so a check started on an
     * earlier visit must still be found and cancelled here. Cleared by the checking thread when the check ends.
     */
    private static final AtomicReference<NetDiagRunner> RUNNING = new AtomicReference<>();

    NetDiagRows(SettingsUi ui) {
        this.ui = ui;
        this.activity = ui.activity;
    }

    void addTo(LinearLayout list) {
        startRow = ui.createActionRow(
                text("Проверить сеть", "Check the network"),
                text("Для каждого сайта SoundCloud и Arsound: что отвечает DNS телефона, что отвечает свой DNS и "
                                + "отвечает ли сайт по HTTPS. Обычно занимает до полминуты, на мёртвой сети — до минуты.",
                        "For each SoundCloud and Arsound site: what the phone DNS says, what the custom DNS says and "
                                + "whether the site answers over HTTPS. Usually up to half a minute, on a dead network up to a minute."),
                v -> startAll());
        startRow.icon("network").keywords(text("диагностика сети dns ping проверка сайтов", "network diagnostics dns ping site check"));
        list.addView(startRow);

        list.addView(ui.createActionRow(
                text("Проверить свой домен", "Check your own domain"),
                text("Те же проверки для любого сайта, который вы введёте: например, того, что не открывается.",
                        "The same checks for any site you type, for example one that does not open."),
                v -> askDomain()).chevron());

        list.addView(ui.createActionRow(
                text("Скопировать результат", "Copy the result"),
                text("Кладёт отчёт последней проверки в буфер обмена, чтобы отправить разработчику.",
                        "Puts the report of the last check on the clipboard to send to the developer."),
                v -> copyReport()).chevron());

        table = ui.createColumn();
        list.addView(table);
    }

    // region Checking

    private void startAll() {
        List<String> hosts = new ArrayList<>(NetDiag.HOSTS);
        beginCheck(hosts, true);
    }

    private void checkDomain(String domain) {
        if (RUNNING.get() != null) {
            // Starting over would drop the check under way: the sites would stay on "waiting".
            Toast.makeText(activity, text("Проверка ещё идёт, подождите", "A check is still running, wait"), Toast.LENGTH_SHORT).show();
            return;
        }
        beginCheck(Collections.singletonList(domain), false);
    }

    /**
     * Starts a check. The whole check replaces the table; one typed domain is added to it.
     *
     * @param whole True for the sites of the list, false for one domain typed by hand.
     */
    private void beginCheck(List<String> hosts, boolean whole) {
        final int mine = ++generation;
        if (whole) {
            table.removeAllViews();
            siteRows.clear();
            verdictRow = null;
            results.clear();
            country = "";
            cancelled = false;
        } else {
            // A domain typed twice is one row.
            ActionRow old = siteRows.remove(hosts.get(0));
            if (old != null) table.removeView(old);
            for (int i = results.size() - 1; i >= 0; i--) if (results.get(i).host.equals(hosts.get(0))) results.remove(i);
        }
        dnsPath = NetDiag.describeDnsPath(Settings.isCustomDnsEnabled(), Settings.getDnsMode(),
                CustomDns.dohUrl(), String.join(", ", CustomDns.plainServers()));
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        checkedAt = format.format(new Date());

        if (whole) {
            verdictRow = ui.createActionRow(text("Идёт проверка…", "Checking…"), "", null);
            table.addView(verdictRow);
        }
        for (String host : hosts) {
            ActionRow row = ui.createActionRow(host, text("Ждёт очереди…", "Waiting…"), null);
            siteRows.put(host, row);
            table.addView(row);
        }
        startRow.description().setText(text("Проверено 0 из ", "Checked 0 of ") + hosts.size());

        NetDiagRunner runner = new NetDiagRunner();
        // A check still under way, from this visit or an earlier one, is cancelled: two would run side by side.
        NetDiagRunner previous = RUNNING.getAndSet(runner);
        if (previous != null) previous.cancel();
        WeakReference<NetDiagRows> self = new WeakReference<>(this);
        Utils.runOnBackgroundThread(() -> {
            NetDiagRunner.Result result = runner.run(hosts, whole, (row, done, total) -> Utils.runOnMainThread(() -> {
                NetDiagRows me = self.get();
                if (me != null) me.showHost(mine, row, done, total, whole);
            }));
            // Cleared here, not on the main thread: the screen may be gone by then, and a stuck flag would block domain checks.
            RUNNING.compareAndSet(runner, null);
            Utils.runOnMainThread(() -> {
                NetDiagRows me = self.get();
                if (me != null) me.finish(mine, result, whole);
            });
        });
    }

    private boolean gone(int check) {
        return check != generation || activity.isFinishing() || activity.isDestroyed();
    }

    private void showHost(int check, NetDiag.Row row, int done, int total, boolean whole) {
        if (gone(check)) return;
        ActionRow view = siteRows.get(row.host);
        if (view != null) paint(view, row);
        startRow.description().setText(text("Проверено ", "Checked ") + done + text(" из ", " of ") + total);
    }

    private void finish(int check, NetDiagRunner.Result result, boolean whole) {
        if (gone(check)) return;
        for (NetDiag.Row row : result.rows) results.add(row);
        if (whole) {
            country = result.country;
            cancelled = result.cancelled;
        }
        startRow.description().setText(text("Проверить снова. ", "Check again. ")
                + (country.isEmpty() ? text("Страна IP не определена.", "IP country unknown.")
                : text("Страна IP: ", "IP country: ") + country));
        showVerdict();
    }

    // endregion

    // region Rows

    private void paint(ActionRow view, NetDiag.Row row) {
        TextView mark = view.plainValue("");
        if (row.skipped) {
            mark.setText(text("не проверен", "not checked"));
            view.description().setText("");
            return;
        }
        NetDiag.Verdict verdict = NetDiag.verdict(row);
        mark.setText(verdict == NetDiag.Verdict.OK ? text("ок", "ok")
                : verdict == NetDiag.Verdict.SLOW ? text("медленно", "slow") : text("нет ответа", "fail"));
        mark.setTextColor(verdict == NetDiag.Verdict.OK ? GREEN : verdict == NetDiag.Verdict.SLOW ? AMBER : RED);

        StringBuilder lines = new StringBuilder();
        lines.append(text("DNS телефона: ", "Phone DNS: "))
                .append(row.systemError.isEmpty() ? join(row.systemAddresses) : text("сбой (", "failed (") + row.systemError + ")")
                .append('\n');
        if (!row.customActive) {
            lines.append(text("Свой DNS: не используется", "Custom DNS: not used"));
        } else if (row.customAddresses.isEmpty()) {
            lines.append(text("Свой DNS: нет ответа (", "Custom DNS: no answer (")).append(row.customError)
                    .append(text("), работает DNS телефона", "), the phone DNS is used"));
        } else {
            lines.append(text("Свой DNS: ", "Custom DNS: ")).append(join(row.customAddresses)).append(" (")
                    .append(row.customMethod).append(", ").append(row.customMs).append(text(" мс)", " ms)"));
        }
        lines.append('\n');
        if (row.httpStatus > 0) {
            lines.append("HTTPS: ").append(row.httpStatus).append(" · ").append(row.httpMs).append(text(" мс", " ms"));
        } else {
            lines.append("HTTPS: ").append(text("нет ответа", "no answer")).append(" (").append(row.httpError).append(")");
        }
        view.description().setText(lines.toString());
    }

    private void showVerdict() {
        if (verdictRow == null) return;
        NetDiag.Tally tally = NetDiag.tally(results);
        TextView title = verdictRow.title();
        if (tally.checked == 0) {
            title.setText(text("Ничего не проверено", "Nothing was checked"));
            return;
        }
        NetDiag.Verdict overall = tally.overall();
        title.setText(overall == NetDiag.Verdict.FAIL
                ? text("Не отвечают: " + tally.failed + " из " + tally.checked, tally.failed + " of " + tally.checked + " do not answer")
                : overall == NetDiag.Verdict.SLOW
                ? text("Все отвечают, медленно: " + tally.slow, "All answer, " + tally.slow + " slowly")
                : text("Все адреса отвечают", "Every address answers"));
        title.setTextColor(overall == NetDiag.Verdict.OK ? GREEN : overall == NetDiag.Verdict.SLOW ? AMBER : RED);
        verdictRow.description().setText(text("DNS: ", "DNS: ") + dnsPath);
    }

    private static String join(List<String> items) {
        if (items.isEmpty()) return "—";
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < items.size() && i < 4; i++) {
            if (i > 0) text.append(", ");
            text.append(items.get(i));
        }
        if (items.size() > 4) text.append(" …");
        return text.toString();
    }

    // endregion

    // region Typed domain and copy

    private void askDomain() {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("example.com");
        LinearLayout form = ui.createColumn();
        form.setPadding(ui.dimen("spacing_m"), 0, ui.dimen("spacing_m"), 0);
        form.addView(input);
        new ArsoundDialog(activity)
                .setTitle(text("Свой домен", "Your own domain"))
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String domain = NetDiag.cleanDomain(input.getText().toString());
                    if (domain == null) {
                        Toast.makeText(activity, text("Введите имя сайта, например example.com", "Type a site name such as example.com"),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    checkDomain(domain);
                })
                .show();
    }

    private void copyReport() {
        if (results.isEmpty()) {
            Toast.makeText(activity, text("Сначала проверьте сеть", "Check the network first"), Toast.LENGTH_SHORT).show();
            return;
        }
        String report = NetDiag.report(checkedAt, dnsPath, country, results, cancelled);
        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) throw new IllegalStateException("no clipboard");
            clipboard.setPrimaryClip(ClipData.newPlainText("Arsound network diagnostics", report));
            Toast.makeText(activity, text("Отчёт скопирован", "Report copied"), Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ex) {
            Toast.makeText(activity, text("Не удалось скопировать", "Could not copy"), Toast.LENGTH_SHORT).show();
        }
    }

    // endregion
}
