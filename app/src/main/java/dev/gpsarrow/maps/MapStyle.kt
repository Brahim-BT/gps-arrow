package dev.gpsarrow.maps

import android.content.Context
import android.util.Log

/**
 * Loads the bundled style and points it at an installed archive.
 *
 * The style ships in `assets/style/basemap-dark.json` with a placeholder where the tile source
 * URL goes, because that URL is device-specific — it contains the absolute path of the downloaded
 * file, which is only known at runtime.
 *
 * ## Why the fonts are `asset://`
 *
 * A style's `glyphs` and `sprite` URLs are fetched when the map renders. Left pointing at
 * `https://protomaps.github.io/...`, an offline device would draw roads with no labels — the exact
 * failure this app exists to avoid. They are therefore bundled and referenced as
 * `asset://fonts/{fontstack}/{range}.pbf`.
 *
 * That works, and it was verified by reading MapLibre Native's source rather than assumed:
 * `AssetFileSource::canRequest` tests the URL scheme and nothing else, with no `Resource::Kind`
 * filter, so glyph and sprite requests route through it exactly like any other. The one thing
 * `asset://` cannot do is serve a *byte range*, which is why `pmtiles://asset://` is unsupported
 * and the archive itself must live on the filesystem — see [InstalledArea.pmtilesUri].
 */
object MapStyle {

    private const val ASSET_PATH = "style/basemap-dark.json"
    private const val PLACEHOLDER = "__PMTILES_URI__"

    /**
     * @return the style JSON with the source pointed at [installed], or null if the style asset
     *   cannot be read. Null means the map is unavailable, which the caller renders as the
     *   ordinary empty state — never as a crash.
     */
    fun forInstalled(context: Context, installed: InstalledArea): String? {
        val template = read(context) ?: return null
        if (!template.contains(PLACEHOLDER)) {
            // The asset is present but not the one we expect. Refusing is better than handing
            // MapLibre a style whose source points nowhere and letting it fail obscurely.
            Log.w(TAG, "style asset has no $PLACEHOLDER placeholder")
            return null
        }
        return template.replace(PLACEHOLDER, installed.pmtilesUri)
    }

    private fun read(context: Context): String? = try {
        context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        Log.w(TAG, "could not read $ASSET_PATH", e)
        null
    }

    private const val TAG = "MapStyle"
}
