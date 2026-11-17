package org.nodescope.android.core.design

import java.text.DecimalFormatSymbols
import java.util.Locale

private val decimalInput = Regex("""[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?""")

/** Accepts the keyboard decimal separator, a dot or a comma. Grouping is not supported. */
internal fun String.parseDecimalInput(): Double? {
    val separator = DecimalFormatSymbols.getInstance(Locale.getDefault(Locale.Category.FORMAT)).decimalSeparator
    val normalized = trim().map { char ->
        when {
            char == ',' || char == separator -> '.'
            char.isDigit() -> char.digitToInt().digitToChar()
            else -> char
        }
    }.joinToString("")
    return normalized.takeIf(decimalInput::matches)?.toDoubleOrNull()?.takeIf(Double::isFinite)
}
