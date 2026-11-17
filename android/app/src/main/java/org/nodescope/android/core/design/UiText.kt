package org.nodescope.android.core.design

import androidx.compose.ui.res.stringResource
import org.nodescope.android.R
import org.nodescope.android.core.design.withDescription
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

/** Text produced outside composition and resolved using the current UI resources. */
sealed interface UiText {
    class Resource(@param:StringRes val id: Int, vararg val args: Any) : UiText
    class Plural(@param:PluralsRes val id: Int, val count: Int, vararg val args: Any) : UiText
    data class Raw(val value: String) : UiText

    fun resolve(resources: Resources): String {
        fun resolveArg(value: Any): Any = if (value is UiText) value.resolve(resources) else value
        return when (this) {
            is Resource -> if (args.isEmpty()) resources.getString(id)
                else resources.getString(id, *args.map(::resolveArg).toTypedArray())
            is Plural -> if (args.isEmpty()) resources.getQuantityString(id, count)
                else resources.getQuantityString(id, count, *args.map(::resolveArg).toTypedArray())
            is Raw -> value
        }
    }

    @Composable fun asString(): String = resolve(LocalResources.current)
}

/** Captures a resolved accessibility description before the semantics lambda runs. */
inline fun Modifier.withDescription(
    description: String,
    mergeDescendants: Boolean = false,
    crossinline properties: SemanticsPropertyReceiver.(String) -> Unit,
): Modifier = semantics(mergeDescendants = mergeDescendants) { properties(description) }
