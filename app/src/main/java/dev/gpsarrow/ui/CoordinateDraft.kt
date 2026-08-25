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
    /**
     * Whether the share switch is on, carried in the draft like every other field so a
     * half-decided share survives the tab switch that took the user to check something.
     *
     * The switch position and nothing more: what it *means* — publish, withdraw, or leave a
     * never-shared point alone — is decided by [dev.gpsarrow.core.SharedPoints.intentAfterSwitch]
     * against the point's existing intent, not here.
     */
    val sharePublicly: Boolean = false,
) {
    val isEmpty: Boolean
        get() = name.isBlank() && latText.isBlank() && lonText.isBlank()

    companion object {
        val EMPTY = CoordinateDraft()
    }
}
