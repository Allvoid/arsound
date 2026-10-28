package app.revanced.extension.soundcloud.region;

import android.content.Context;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.viewinterop.AndroidView_androidKt;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function3;

/**
 * The hint badge as a piece of a Compose screen. Compose draws labels from lists of such pieces (a function of a
 * modifier, the composer and the change flags); this one hosts the badge view.
 */
final class ComposeBadge implements Function3<Object, Object, Object, Object> {
    static final ComposeBadge INSTANCE = new ComposeBadge();

    private static final Function1<Context, RegionHintBadge> FACTORY = RegionHintBadge::new;
    private static final Function1<RegionHintBadge, Unit> NO_UPDATE = badge -> Unit.INSTANCE;

    private ComposeBadge() {
    }

    @Override
    public Object invoke(Object modifier, Object composer, Object changed) {
        AndroidView_androidKt.AndroidView(FACTORY, (Modifier) modifier, NO_UPDATE, (Composer) composer, 0, 0);
        return Unit.INSTANCE;
    }
}
