package dev.gpsarrow

import androidx.annotation.StringRes

/**
 * Values the ViewModel hands to the UI in place of sentences.
 *
 * The app ships in three languages, so nothing below the UI layer may hold user-facing prose.
 * There is a second, less obvious reason these are resource ids rather than strings resolved
 * in the ViewModel: `AndroidViewModel` holds the *Application* context, and below API 33 the
 * per-app locale is applied by wrapping the *Activity's* base context, so the Application's
 * resources are still in the system language. A string resolved in the ViewModel would come
 * out in the wrong language on exactly the older devices this app is meant to support.
 */

/** A subsystem that failed without taking the app down, ready for the warning banner. */
data class Degradation(
    @param:StringRes val messageRes: Int,
    /** Substituted into [messageRes] when it takes an argument. */
    val arg: String? = null,
)

/** Why "save my location" is refused right now. */
sealed interface SaveBlock {
    /** Nothing to save: no position at all yet. */
    data object NoFix : SaveBlock

    /** A fix exists but describes where the user was, not where they are. */
    data class StaleFix(val ageSeconds: Long) : SaveBlock
}

/** One row of the diagnostics panel: a translated label and an already-formatted value. */
data class Diagnostic(
    @param:StringRes val labelRes: Int,
    /** Identifiers, provider names and numbers — not translated, and not meant to be. */
    val value: String,
    /**
     * Optional unit wrapper, e.g. `"%1$s s"`. Applied in the UI rather than baked into
     * [value] because the unit word is translated and the ViewModel cannot resolve resources
     * in the app's language on API levels below 33.
     */
    @param:StringRes val unitRes: Int? = null,
)
