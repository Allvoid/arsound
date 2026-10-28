package app.arsound.patches.soundcloud.misc.hooks

import app.revanced.com.android.tools.smali.dexlib2.mutable.MutableMethod
import app.revanced.patcher.extensions.ExternalLabel
import app.revanced.patcher.extensions.addInstructionsWithLabels
import app.revanced.patcher.extensions.getInstruction

/**
 * What a guard hook does on the path where the extension has taken over the call: how it reads the extension's answer
 * into v0 and which smali ends the method. The rest of the guard is the same for every kind, see [insertGuard].
 */
internal sealed interface GuardExit {
    /** The instruction that moves the extension's answer into v0, for the return type of the extension method. */
    val readAnswer: String

    /** Smali that ends the method early. It may use v0, which holds the answer. */
    val smali: String

    /** Returns nothing: the method is `void`. */
    object ReturnVoid : GuardExit {
        override val readAnswer = "move-result"
        override val smali = "return-void"
    }

    /** Returns the primitive `false` (or 0). */
    object ReturnFalse : GuardExit {
        override val readAnswer = "move-result"
        override val smali = "const/4 v0, 0x0\nreturn v0"
    }

    /** Returns the primitive `true` (or 1). */
    object ReturnTrue : GuardExit {
        override val readAnswer = "move-result"
        override val smali = "const/4 v0, 0x1\nreturn v0"
    }

    /** Returns Kotlin's `Unit`, for a `suspend` or `Function` method that returns an object. */
    object ReturnUnit : GuardExit {
        override val readAnswer = "move-result"
        override val smali = "sget-object v0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;\nreturn-object v0"
    }

    /** Returns the boxed `Boolean.FALSE`. */
    object ReturnBoxedFalse : GuardExit {
        override val readAnswer = "move-result"
        override val smali = "sget-object v0, Ljava/lang/Boolean;->FALSE:Ljava/lang/Boolean;\nreturn-object v0"
    }

    /**
     * Returns the object the extension answered with. The guard fires when the answer is not null; [castTo] is the
     * type of the method's return, when the extension can only answer with `Object`.
     */
    class ReturnAnswer(private val castTo: String? = null) : GuardExit {
        override val readAnswer = "move-result-object"
        override val smali = (castTo?.let { "check-cast v0, $it\n" } ?: "") + "return-object v0"
    }

    /** Any other ending, with the answer read by [readAnswer]. */
    class Custom(override val readAnswer: String, override val smali: String) : GuardExit
}

/** Which answer of the extension makes a guard fire. */
internal enum class FiresOn(private val skipBranch: String) {
    /** The guard fires on `true` (or a non-null object): on `false` the original code runs. */
    TRUE("if-eqz"),

    /** The guard fires on `false`: on `true` the original code runs. */
    FALSE("if-nez"),
    ;

    /** The instruction that jumps over the exit to the original code. */
    fun skip(register: String, label: String) = "$skipBranch $register, :$label"
}

/**
 * Puts a guard at the start of the method (or at [index]): the extension is asked first, and when it fires the method
 * ends in [exit] without running its own code.
 *
 * ```
 * [prelude]
 * [call]                      invoke-static of the extension method
 * [GuardExit.readAnswer] v0
 * if-eqz v0, :original        (if-nez when [fires] is [FiresOn.FALSE])
 * [GuardExit.smali]
 * ```
 *
 * v0 must be free at [index]: the guard writes it before anything is read. [prelude] is smali that prepares the
 * arguments of [call].
 */
internal fun MutableMethod.insertGuard(
    call: String,
    exit: GuardExit,
    fires: FiresOn = FiresOn.TRUE,
    prelude: String = "",
    index: Int = 0,
) {
    val original = "original"
    addInstructionsWithLabels(
        index,
        listOf(prelude, call, "${exit.readAnswer} v0", fires.skip("v0", original), exit.smali)
            .filter { it.isNotEmpty() }
            .joinToString("\n"),
        ExternalLabel(original, getInstruction(index)),
    )
}
