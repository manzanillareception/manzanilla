package com.agentdeck.hotel

/** Configured display languages in Spain hotel order; provider support is verified separately. */
object DeskLanguages {
    val codes: List<String> = listOf(
        "es", "en", "ca", "fr", "de", "it", "pt", "nl", "ar", "zh",
        "ru", "uk", "pl", "ro", "sv", "el", "tr", "ja", "ko", "hi",
        "cs", "da", "fi", "hu", "bg", "hr", "sk", "sr", "no", "id",
        "th", "vi", "ms", "tl", "he", "fa", "ur", "bn", "ta", "te",
        "kn", "mr", "gu", "pa", "ne", "kk", "ka", "hy", "et", "lv",
        "lt", "af"
    )
    private val english = mapOf(
        "es" to "Spanish", "en" to "English", "ca" to "Catalan", "fr" to "French", "de" to "German",
        "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch", "ar" to "Arabic", "zh" to "Chinese",
        "ru" to "Russian", "uk" to "Ukrainian", "pl" to "Polish", "ro" to "Romanian", "sv" to "Swedish",
        "el" to "Greek", "tr" to "Turkish", "ja" to "Japanese", "ko" to "Korean", "hi" to "Hindi",
        "cs" to "Czech", "da" to "Danish", "fi" to "Finnish", "hu" to "Hungarian", "bg" to "Bulgarian",
        "hr" to "Croatian", "sk" to "Slovak", "sr" to "Serbian", "no" to "Norwegian", "id" to "Indonesian",
        "th" to "Thai", "vi" to "Vietnamese", "ms" to "Malay", "tl" to "Tagalog", "he" to "Hebrew",
        "fa" to "Persian", "ur" to "Urdu", "bn" to "Bengali", "ta" to "Tamil", "te" to "Telugu",
        "kn" to "Kannada", "mr" to "Marathi", "gu" to "Gujarati", "pa" to "Punjabi", "ne" to "Nepali",
        "kk" to "Kazakh", "ka" to "Georgian", "hy" to "Armenian", "et" to "Estonian", "lv" to "Latvian",
        "lt" to "Lithuanian", "af" to "Afrikaans"
    )
    val native = mapOf(
        "es" to "Español", "en" to "English", "ca" to "Català", "fr" to "Français", "de" to "Deutsch",
        "it" to "Italiano", "pt" to "Português", "nl" to "Nederlands", "ar" to "العربية", "zh" to "中文",
        "ru" to "Русский", "uk" to "Українська", "pl" to "Polski", "ro" to "Română", "sv" to "Svenska",
        "el" to "Ελληνικά", "tr" to "Türkçe", "ja" to "日本語", "ko" to "한국어", "hi" to "हिन्दी",
        "cs" to "Čeština", "da" to "Dansk", "fi" to "Suomi", "hu" to "Magyar", "bg" to "Български",
        "hr" to "Hrvatski", "sk" to "Slovenčina", "sr" to "Српски", "no" to "Norsk", "id" to "Bahasa Indonesia",
        "th" to "ไทย", "vi" to "Tiếng Việt", "ms" to "Bahasa Melayu", "tl" to "Tagalog", "he" to "עברית",
        "fa" to "فارسی", "ur" to "اردو", "bn" to "বাংলা", "ta" to "தமிழ்", "te" to "తెలుగు",
        "kn" to "ಕನ್ನಡ", "mr" to "मराठी", "gu" to "ગુજરાતી", "pa" to "ਪੰਜਾਬੀ", "ne" to "नेपाली",
        "kk" to "Қазақша", "ka" to "ქართული", "hy" to "Հայերեն", "et" to "Eesti", "lv" to "Latviešu",
        "lt" to "Lietuvių", "af" to "Afrikaans"
    )

    fun has(code: String) = code in codes
    fun english(code: String) = english[code] ?: code
}
