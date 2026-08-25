package dev.gpsarrow.core

/**
 * What an edit to a saved point is allowed to change, and what it must leave alone.
 *
 * In `:core` and pure so the preservation rules can be pinned by a test. They were previously
 * spelled out inside the store's `update`, where the only way to check them was to edit a point
 * on a phone and look — which is how the note-wiping bug below survived.
 *
 * ### The bug this exists to prevent
 *
 * The store's `update` took `note: String? = null` and assigned it unconditionally. The editor
 * screen has no note field at all, so it called `update` without one, and **every edit silently
 * erased the point's note** — "gate is on the north side", "water here in winter" — while
 * appearing to change only the name and the coordinates.
 *
 * The shape is the project's recurring one: a default of `null` was made to mean both "this
 * point has no note" and "this call is not about notes". Those are different statements and a
 * single nullable field cannot carry both. The fix is that an edit now names only what it
 * actually edits, and everything else is carried over from the original rather than defaulted.
 */
object DestinationEdit {

    /**
     * [original] with the editor's [name] and [position] applied, and every other field carried
     * over untouched.
     *
     * Carried over because the editor never presents them, and an edit must not discard what it
     * did not show: the note, the favourite star, the id, the creation and last-used stamps, and
     * how the point was created.
     *
     * Two rules are not plain carry-over:
     *
     * - **A blank name keeps the old one.** Clearing the field is not a request to be left with
     *   a nameless point in the list.
     * - **Accuracy survives only while the position is unchanged.** A hand-typed coordinate is
     *   no longer the fix it came from, so the fix's accuracy has stopped describing it. Dropping
     *   the badge is the honest outcome; keeping it would let a ±4 m claim vouch for a number the
     *   receiver never produced.
     */
    fun applied(original: Destination, name: String, position: LatLon): Destination =
        original.copy(
            name = name.ifBlank { original.name },
            position = position,
            accuracyMeters =
                if (position == original.position) original.accuracyMeters else null,
        )
}
