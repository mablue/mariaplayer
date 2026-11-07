package ir.mariaplayer.util

/** Shared sanitizer for org.json "null" strings (Android org.json bug). */
fun sanitizeJsonString(s: String?): String? =
    s?.takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
