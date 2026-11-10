package app.revanced.extension.soundcloud.debug.sections;

import android.content.Context;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import app.revanced.extension.soundcloud.debug.LogSection;
import app.revanced.extension.soundcloud.debug.ReportLines;
import app.revanced.extension.soundcloud.widget.WidgetDiagnostics;

/**
 * What decides whether a tap on the player widget reaches the app, taken at the moment of saving: the phone and what it
 * allows the app in the background, the home screen app, the widgets on the home screen, whether the system still holds
 * the buttons' intents, and the latest starts of the app's process with the reason of each (a start by a broadcast with
 * the widget's action is a tap that woke the app). Every line is asked for separately.
 */
public final class WidgetStateSection implements LogSection {
    @Override
    public String name() {
        return "widget state";
    }

    @Override
    public boolean isRecord() {
        return false;
    }

    @Override
    public boolean hasContent(Context context) {
        return true;
    }

    @Override
    public void writeTo(Context context, OutputStream out) throws Exception {
        ReportLines lines = new ReportLines();
        lines.probe("phone", () -> WidgetDiagnostics.describePhone(context));
        lines.probe("home screen app", () -> WidgetDiagnostics.describeLauncher(context));
        lines.probe("widgets", () -> WidgetDiagnostics.describeWidgets(context));
        lines.probe("recent starts of the app, newest first", () -> WidgetDiagnostics.describeStarts(context, 10));
        out.write(lines.toString().getBytes(StandardCharsets.UTF_8));
    }
}
