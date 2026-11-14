package com.heavysighs.sealedwatcher

/**
 * Pure string logic for the enabled-accessibility-services setting
 * (Settings.Secure "enabled_accessibility_services": a colon-separated list
 * of flattened component names, "package/class").
 *
 * Kept free of Android classes so it can be unit-tested on the JVM.
 * The system writes components in full form ("pkg/pkg.Class"); other
 * writers (and AccessibilityServiceInfo.getId()) may use the short form
 * ("pkg/.Class"). All comparisons here normalize both sides, so the two
 * forms are treated as the same service.
 */

internal fun normalizeComponent(id: String): String {
    val pkg = id.substringBefore('/')
    val cls = id.substringAfter('/', "")
    return when {
        cls.isEmpty() -> id
        cls.startsWith(".") -> "$pkg/$pkg$cls"
        cls.contains('.') -> id
        else -> "$pkg/$pkg.$cls"
    }
}

internal fun parseTokens(setting: String?): List<String> =
    setting?.split(':')?.filter { it.isNotBlank() } ?: emptyList()

internal fun containsService(tokens: List<String>, id: String): Boolean {
    val target = normalizeComponent(id)
    return tokens.any { normalizeComponent(it) == target }
}

internal fun withoutService(tokens: List<String>, id: String): List<String> {
    val target = normalizeComponent(id)
    return tokens.filter { normalizeComponent(it) != target }
}

internal fun withService(tokens: List<String>, id: String): List<String> =
    if (containsService(tokens, id)) tokens else tokens + id

internal fun joinTokens(tokens: List<String>): String = tokens.joinToString(":")
