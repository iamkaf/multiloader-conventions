package com.iamkaf.multiloader.root

import java.util.Locale

object RootTaskNames {
    fun taskSuffix(name: String): String =
        name.replace(Regex("[^A-Za-z0-9]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString("") { token ->
                token.substring(0, 1).uppercase(Locale.ROOT) + token.substring(1)
            }
}
