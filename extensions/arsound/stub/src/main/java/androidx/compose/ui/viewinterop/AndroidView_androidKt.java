package androidx.compose.ui.viewinterop;

import android.content.Context;
import android.view.View;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;

import kotlin.Unit;
import kotlin.jvm.functions.Function1;

public final class AndroidView_androidKt {
    /** Compose's AndroidView: a View inside a Compose layout. {@code changed} and {@code defaults} are 0 when all are given. */
    public static <T extends View> void AndroidView(Function1<? super Context, ? extends T> factory, Modifier modifier,
                                                    Function1<? super T, Unit> update, Composer composer,
                                                    int changed, int defaults) {
    }
}
