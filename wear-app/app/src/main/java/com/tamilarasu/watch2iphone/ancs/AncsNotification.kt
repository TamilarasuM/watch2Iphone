package com.tamilarasu.watch2iphone.ancs

/** A fully resolved iPhone notification, ready to be shown on the watch. */
data class AncsNotification(
    val sessionId: Int,
    val uid: Long,
    val flags: Int,
    val categoryId: Int,
    val appId: String,
    val appName: String,
    val title: String,
    val subtitle: String,
    val message: String,
    val positiveLabel: String,
    val negativeLabel: String,
) {
    val isIncomingCall get() = categoryId == CategoryId.INCOMING_CALL
    val isSilent get() = flags and EventFlags.SILENT != 0
    val hasPositiveAction get() = flags and EventFlags.POSITIVE_ACTION != 0
    val hasNegativeAction get() = flags and EventFlags.NEGATIVE_ACTION != 0
}
