package dev.gpsarrow.ui

import androidx.annotation.StringRes
import dev.gpsarrow.R
import dev.gpsarrow.core.ShareStatus

/**
 * The one place a [ShareStatus] becomes words, so the editor and the list cannot drift apart on
 * what they claim about the same point.
 *
 * Null means **say nothing** — not "say something neutral". A row with no sharing state must
 * look identical to a row from before this feature existed; a greyed-out badge meaning
 * "not shared" would be a mark that carries no information, and the user has to scan past it on
 * every row forever.
 *
 * None of these strings forecasts. There is no "gone within about a day", because whether the
 * cleanup job ran is not something this device knows, and a user whose camp is still on the map
 * after being told it was withdrawn is worse off than one who was told nothing at all.
 */
@StringRes
fun shareStatusLabelRes(status: ShareStatus): Int? = when (status) {
    ShareStatus.NOT_SHARED -> null
    ShareStatus.PUBLISHED -> R.string.shared_badge
    ShareStatus.EDIT_UNPUBLISHED -> R.string.shared_edit_unpublished
    ShareStatus.PUBLISH_UNCONFIRMED -> R.string.shared_publish_unconfirmed
    ShareStatus.STILL_PUBLIC -> R.string.shared_still_public
    ShareStatus.WITHDRAWAL_UNCONFIRMED -> R.string.shared_withdrawal_unconfirmed
}
