package org.nodescope.android.core.model

import java.text.Collator
import java.util.Locale

internal class AnalyzerSourceChoices(
    private val sources: List<AnalyzerSource>,
    userCountry: String? = Locale.getDefault().country,
    private val displayLocale: Locale = Locale.getDefault(),
) {
    data class CountrySection(val country: String?, val name: String?, val sources: List<AnalyzerSource>)

    private val userCountry = countryCode(userCountry)
    private val localSources = this.userCountry?.let { code -> sources.filter { countryCode(it.country) == code } }.orEmpty()

    val userCountryName: String? get() = userCountry?.let(::countryName)
    val customFirst: Boolean get() = localSources.isEmpty()

    fun countrySections(): List<CountrySection> {
        val groups = sources.groupBy { countryCode(it.country) }
        val collator = Collator.getInstance(displayLocale).apply { strength = Collator.SECONDARY }
        val countries = groups.mapNotNull { (code, entries) ->
            code?.let { CountrySection(it, countryName(it), entries) }
        }.sortedWith(compareBy<CountrySection> { it.country != userCountry }.thenComparator { a, b ->
            collator.compare(a.name, b.name).takeIf { it != 0 } ?: compareValues(a.country, b.country)
        })
        return countries + listOfNotNull(groups[null]?.let { CountrySection(null, null, it) })
    }

    fun onboardingHost(savedHost: String?, isOnboarded: Boolean): String? =
        savedHost?.takeIf { it.isNotBlank() } ?: if (isOnboarded) null
        else (localSources.firstOrNull { it.isDefault } ?: localSources.firstOrNull())?.host

    fun nearest(to: Coordinate): List<AnalyzerSource> {
        val origin = FieldCoordinate.create(to.latitude, to.longitude) ?: return sources
        // sortedBy is stable, including ties and entries with no valid coordinate.
        return sources.map { source ->
            val coordinate = source.locationCoordinate
            val destination = coordinate?.let { FieldCoordinate.create(it.latitude, it.longitude) }
            val distance = destination?.let { FieldGeometry.geometry(origin, it).distanceMeters }
            source to (distance ?: Double.POSITIVE_INFINITY)
        }.sortedBy { it.second }.map { it.first }
    }

    private fun countryName(code: String): String = Locale("", code).getDisplayCountry(displayLocale)

    companion object {
        private val countryCodes = Locale.getISOCountries().toSet()

        fun countryCode(value: String?): String? = value?.uppercase(Locale.ROOT)?.takeIf { it in countryCodes }
    }
}
