package dev.gpsarrow.ui

/**
 * The half-typed contents of the Add-point form.
 *
 * Hoisted out of the screen so switching tabs doesn't discard it. As a tab, "Add point" can be
 * left and returned to at any moment — losing a partly entered coordinate because the user
 * glanced at the arrow would be the worst kind of small betrayal.
 *
 * Deliberately in the UI layer, not `:core`: this is editor state, not domain state.
 */
data class CoordinateDraft(
    val name: String = "",
    val latText: String = "",
    val lonText: String = "",
    /** Which format a paste was recognised as, for the confirmation line. */
    val readAs: String? = null,
) {
    val isEmpty: Boolean
        get() = name.isBlank() && latText.isBlank() && lonText.isBlank()

    companion object {
        val EMPTY = CoordinateDraft()
    }
}
