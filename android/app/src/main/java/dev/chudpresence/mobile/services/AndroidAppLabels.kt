package dev.chudpresence.mobile.services

import android.content.Context

internal fun installedAppLabel(context: Context, packageName: String): String? = runCatching {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
}.getOrNull()?.trim()?.takeIf { it.isNotEmpty() && it != packageName }
