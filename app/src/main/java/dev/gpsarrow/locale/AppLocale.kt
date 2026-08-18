package dev.gpsarrow.locale

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import android.util.Log
import dev.gpsarrow.core.Format
import java.util.Locale

/**
 * The three languages the app ships in.
 *
 * A language, not a region: the picker offers Arabic, not Moroccan Arabic and Mauritanian
 * Arabic. [nativeName] is written in the language itself, because someone who has picked up a
 * secondhand phone set to a language they do not read needs to recognise their own on sight —
 * "Français" is findable in an Arabic UI in a way that "French" translated into Arabic is not.
 */
enum class AppLanguage(val tag: String, val nativeName: String) {
    ENGLISH("en", "English"),
    FRENCH("fr", "Français"),
    ARABIC("ar", "العربية"),
    ;

    /** The locale for resource resolution and layout direction. */
    fun locale(): Locale = Locale.forLanguageTag(tag)

    /**
     * The locale to hand to number formatting: [locale] with the Latin numbering system pinned.
     *
     * Latin digits in every language is a deliberate product decision — see
     * `Format.latinDigitLocale`. `Format` transliterates as well, so this is the polite request
     * and not the guarantee, but asking correctly means the platform usually gets it right on
     * its own and the transliteration has nothing to do.
     */
    fun numberLocale(): Locale = Format.latinDigitLocale(locale())

    companion object {
        val DEFAULT = ENGLISH

        fun fromTag(tag: String?): AppLanguage? =
            entries.firstOrNull { it.tag.equals(tag, ignoreCase = true) }

        /**
         * The shipped language that best matches the device, or null if none does.
         *
         * Used only to preselect a row in the first-launch picker. A device set to Spanish gets
         * no preselection rather than a wrong one.
         */
        fun matchingDevice(context: Context): AppLanguage? {
            val locales = context.resources.configuration.locales
            for (i in 0 until locales.size()) {
                fromTag(locales[i].language)?.let { return it }
            }
            return null
        }
    }
}

/**
 * Per-app language, without AppCompat.
 *
 * This project has deliberately never depended on AppCompat — the activity is a plain
 * `ComponentActivity` and the theme is Material 3 through Compose — and
 * `AppCompatDelegate.setApplicationLocales` would mean pulling the whole library in plus a
 * manifest service entry for its backport to apply. Two mechanisms instead, one preference
 * driving both:
 *
 *  - **API 33+** (which is the A54 and the large majority of the install base) uses the
 *    platform [LocaleManager]. The choice then also appears in Android's own per-app language
 *    screen in Settings, and survives being changed from there.
 *  - **API 26-32** has no platform support, so [wrap] overrides the configuration in
 *    `attachBaseContext` and the activity is recreated on change.
 *
 * The preference is written on both paths, so it is always the single source of truth and the
 * two never disagree.
 */
object AppLocale {

    private const val PREFS = "gpsarrow.prefs"
    private const val KEY_LANGUAGE = "app.language"
    private const val KEY_CHOSEN = "app.language.chosen"
    private const val TAG = "AppLocale"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True once the user has been through the picker, whatever they picked. */
    fun hasChosen(context: Context): Boolean = prefs(context).getBoolean(KEY_CHOSEN, false)

    /** The stored choice, falling back to [AppLanguage.DEFAULT] before the picker has run. */
    fun current(context: Context): AppLanguage =
        AppLanguage.fromTag(prefs(context).getString(KEY_LANGUAGE, null)) ?: AppLanguage.DEFAULT

    /**
     * Record and apply a choice.
     *
     * Returns true when the caller must recreate the activity to see it. On API 33+ the
     * platform restarts the activity itself, so returning true there would recreate it twice.
     */
    fun set(context: Context, language: AppLanguage): Boolean {
        prefs(context).edit()
            .putString(KEY_LANGUAGE, language.tag)
            .putBoolean(KEY_CHOSEN, true)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val applied = runCatching {
                context.getSystemService(LocaleManager::class.java)
                    ?.applicationLocales = LocaleList.forLanguageTags(language.tag)
            }.isSuccess
            if (applied) return false
            // Falling through on failure is deliberate: a stored preference the UI honours is
            // better than a silently ignored language change.
            Log.w(TAG, "LocaleManager unavailable; using the configuration override instead")
        }
        return true
    }

    /**
     * Wrap a base context so its resources resolve in the chosen language.
     *
     * Called from `attachBaseContext`. On API 33+ the platform has already applied the locale
     * and this is a no-op in practice, but it is left unconditional so the two paths cannot
     * drift apart — the configuration is set from the same stored preference either way.
     */
    fun wrap(base: Context): Context {
        val language = current(base)
        val locale = language.locale()
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return ContextWrapper(base.createConfigurationContext(config))
    }
}
