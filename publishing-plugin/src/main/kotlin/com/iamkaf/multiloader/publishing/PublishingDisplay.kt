package com.iamkaf.multiloader.publishing

import java.io.File

internal object PublishingDisplay {
    fun publicationDisplayName(file: File, publication: PublicationSpec): String =
        publication.displayName ?: file.name.replace(Regex("\\.jar$"), "")
}
