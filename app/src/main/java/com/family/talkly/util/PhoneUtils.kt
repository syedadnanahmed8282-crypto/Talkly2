package com.family.talkly.util

object PhoneUtils {
    /**
     * Cleans a phone number by removing spaces, dashes, brackets, plus signs, and any non-digit character.
     */
    fun cleanPhoneNumber(phone: String): String {
        return phone.replace(Regex("[^0-9]"), "")
    }

    /**
     * Extracts the LAST 10 DIGITS (phoneSuffix) from any given phone number.
     * E.g., '+8801712345678' -> '1712345678', '01712345678' -> '1712345678'.
     */
    fun extractPhoneSuffix(phone: String): String {
        val clean = cleanPhoneNumber(phone)
        return if (clean.length > 10) {
            clean.takeLast(10)
        } else {
            clean
        }
    }

    /**
     * Formats last seen timestamp into human-readable string like '10:15 AM', 'Today at 10:15 AM', 'Yesterday at 8:30 PM', etc.
     */
    fun formatLastSeenTime(timestamp: Long): String {
        if (timestamp <= 0L) return "Recently"
        val now = System.currentTimeMillis()
        val diffMs = now - timestamp
        if (diffMs < 60 * 1000L) {
            return "Just now"
        }
        val calNow = java.util.Calendar.getInstance()
        val calThen = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }

        val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
        val timeStr = timeFormat.format(java.util.Date(timestamp))

        val isSameDay = calNow.get(java.util.Calendar.YEAR) == calThen.get(java.util.Calendar.YEAR) &&
                calNow.get(java.util.Calendar.DAY_OF_YEAR) == calThen.get(java.util.Calendar.DAY_OF_YEAR)

        if (isSameDay) {
            return "Today at $timeStr"
        }

        calNow.add(java.util.Calendar.DAY_OF_YEAR, -1)
        val isYesterday = calNow.get(java.util.Calendar.YEAR) == calThen.get(java.util.Calendar.YEAR) &&
                calNow.get(java.util.Calendar.DAY_OF_YEAR) == calThen.get(java.util.Calendar.DAY_OF_YEAR)

        if (isYesterday) {
            return "Yesterday at $timeStr"
        }

        val dateFormat = java.text.SimpleDateFormat("MMM d 'at' h:mm a", java.util.Locale.US)
        return dateFormat.format(java.util.Date(timestamp))
    }

    /**
     * Decodes Base64 data strings or returns URL/Uri for Coil AsyncImage model.
     * Prevents blank images when media is sent as Base64 fallback or data URI across devices.
     */
    fun getCoilMediaModel(mediaUrl: String?): Any? {
        if (mediaUrl.isNullOrBlank()) return null
        if (mediaUrl.startsWith("data:") || mediaUrl.startsWith("base64:")) {
            val commaIndex = mediaUrl.indexOf(",")
            val base64Str = if (commaIndex != -1) mediaUrl.substring(commaIndex + 1) else mediaUrl.removePrefix("base64:")
            return try {
                android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
            } catch (e: Exception) {
                mediaUrl
            }
        }
        return mediaUrl
    }
}
