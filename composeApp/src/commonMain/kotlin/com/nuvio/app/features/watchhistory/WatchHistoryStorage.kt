package com.nuvio.app.features.watchhistory

internal expect object WatchHistoryStorage {
    fun loadPayload(profileId: Int): String?
    fun savePayload(profileId: Int, payload: String)
}
