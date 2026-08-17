package dev.gpsarrow.core

import java.text.Normalizer

/**
 * Sort orders offered in the destinations list.
 *
 * [RECENTLY_USED] exists because it is the order people actually want once they have thirty
 * saved points: the thing you navigated to yesterday is overwhelmingly likely to be the thing
 * you want today. It costs one nullable timestamp on [Destination].
 */
enum class DestinationSort(val label: String) {
    NAME_ASC("Name A–Z"),
    NAME_DESC("Name Z–A"),
    DISTANCE_NEAREST("Nearest first"),
    DISTANCE_FARTHEST("Farthest first"),
    ADDED_NEWEST("Newest first"),
    ADDED_OLDEST("Oldest first"),
    RECENTLY_USED("Recently used"),
    ;

    /** True for orders that are meaningless without knowing where the user is. */
    val needsPosition: Boolean
        get() = this == DISTANCE_NEAREST || this == DISTANCE_FARTHEST

    companion object {
        val DEFAULT = NAME_ASC

        /** Tolerant lookup for restoring a persisted choice. */
        fun fromName(name: String?): DestinationSort =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Search, filter and sort for the destinations list.
 *
 * Deliberately in `:core` and pure: sorting by distance when there is no position fix is exactly
 * the sort of edge case that should be pinned by a unit test rather than discovered on a hillside.
 */
object DestinationQuery {

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /**
     * Case- and accent-insensitive comparison key. "Café" matches "cafe", "CAFE" and "café".
     *
     * NFD splits an accented character into base letter + combining mark, then the marks are
     * dropped. Handles the Latin-script cases users actually hit; it is not a full ICU collation
     * and does not try to be.
     */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .trim()

    /** Incremental substring match over the name, plus the note if there is one. */
    fun matches(destination: Destination, query: String): Boolean {
        val needle = fold(query)
        if (needle.isEmpty()) return true
        if (fold(destination.name).contains(needle)) return true
        return destination.note?.let { fold(it).contains(needle) } == true
    }

    /**
     * The one entry point the UI calls.
     *
     * @param origin current (or last known) position. When null, distance orders cannot be
     *   computed, so they fall back to [DestinationSort.NAME_ASC] rather than producing an
     *   arbitrary order that looks meaningful and isn't. The UI is expected to say so.
     */
    fun apply(
        destinations: List<Destination>,
        query: String = "",
        favouritesOnly: Boolean = false,
        sort: DestinationSort = DestinationSort.DEFAULT,
        origin: LatLon? = null,
    ): List<Destination> {
        val filtered = destinations
            .filter { !favouritesOnly || it.isFavourite }
            .filter { matches(it, query) }

        val effective = if (sort.needsPosition && origin == null) DestinationSort.NAME_ASC else sort

        // Name is the tie-breaker everywhere, so the order is total and stable — two points
        // saved in the same second never shuffle between recompositions.
        val byName = compareBy<Destination> { fold(it.name) }.thenBy { it.id }

        return when (effective) {
            DestinationSort.NAME_ASC -> filtered.sortedWith(byName)

            DestinationSort.NAME_DESC ->
                filtered.sortedWith(compareByDescending<Destination> { fold(it.name) }.thenBy { it.id })

            DestinationSort.DISTANCE_NEAREST -> filtered.sortedWith(
                compareBy<Destination> { Geo.distanceMeters(origin!!, it.position) }.then(byName),
            )

            DestinationSort.DISTANCE_FARTHEST -> filtered.sortedWith(
                compareByDescending<Destination> { Geo.distanceMeters(origin!!, it.position) }
                    .then(byName),
            )

            DestinationSort.ADDED_NEWEST -> filtered.sortedWith(
                compareByDescending<Destination> { it.createdAtMillis }.then(byName),
            )

            DestinationSort.ADDED_OLDEST -> filtered.sortedWith(
                compareBy<Destination> { it.createdAtMillis }.then(byName),
            )

            // Never-used points sort last rather than first, which is what "recently used" means.
            DestinationSort.RECENTLY_USED -> filtered.sortedWith(
                compareByDescending<Destination> { it.lastUsedAtMillis ?: Long.MIN_VALUE }
                    .then(byName),
            )
        }
    }

    /** What the UI ended up doing, so it can explain itself when it had to substitute. */
    fun effectiveSort(requested: DestinationSort, origin: LatLon?): DestinationSort =
        if (requested.needsPosition && origin == null) DestinationSort.NAME_ASC else requested
}
