package com.rm.interchange.ui

import androidx.compose.runtime.Composable
import com.rm.interchange.res.Res
import com.rm.interchange.res.language_code
import com.rm.interchange.res.number_group
import org.jetbrains.compose.resources.stringResource

/** Everything in [content] in [language] ("en", "fr"), or the phone's own language when it's empty; numbers too. */
@Composable
fun InLanguage(language: String, content: @Composable () -> Unit) {
    InPlatformLanguage(language) {
        Numbers.group = stringResource(Res.string.number_group)
        content()
    }
}

/** The code of the language being shown, "en" or "fr", for text that comes from packs. */
@Composable
fun languageCode(): String = stringResource(Res.string.language_code)

/** [content] with the platform set to [language], or its own when empty, so strings are looked up in it. */
@Composable
expect fun InPlatformLanguage(language: String, content: @Composable () -> Unit)

/** How numbers are written in the language being shown. Set by [InLanguage]. */
object Numbers {
    var group = ","

    /** 20000 as 20,000, or as the language groups thousands. */
    fun grouped(n: Long): String {
        val digits = kotlin.math.abs(n).toString()
        val out = StringBuilder()
        for ((i, c) in digits.withIndex()) {
            if (i > 0 && (digits.length - i) % 3 == 0) out.append(group)
            out.append(c)
        }
        return (if (n < 0) "-" else "") + out
    }
}
