package com.tamilarasu.watch2iphone

import kotlinx.coroutines.flow.MutableStateFlow

enum class LinkPhase {
    IDLE,
    SCANNING,
    ADVERTISING,
    CONNECTING,
    BONDING,
    DISCOVERING,
    SUBSCRIBING,
    READY,
    RECONNECTING,
    FAILED,
}

data class UiState(
    val phase: LinkPhase = LinkPhase.IDLE,
    val detail: String = "",
    val deviceName: String? = null,
    val savedDevice: String? = null,
)

data class ScanItem(
    val address: String,
    val name: String?,
    val rssi: Int,
    val isApple: Boolean,
    val detail: String,
)

/** State shared between the foreground service (writer) and the UI (reader). */
object AncsStatus {
    val ui = MutableStateFlow(UiState())
    val scanResults = MutableStateFlow<List<ScanItem>>(emptyList())
}
