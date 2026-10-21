package com.nuvio.app.features.player

/** Speed to apply when a new episode's engine is ready. Party rate wins over the carried speed. */
internal fun speedForNewEpisode(sessionSpeed: Float?, partyRate: Float?): Float =
    partyRate ?: sessionSpeed ?: 1f

/** Session speed after the user picks [picked]; a hold-to-speed boost must not overwrite it. */
internal fun recordUserSpeed(current: Float?, picked: Float, holdActive: Boolean): Float? =
    if (holdActive) current else picked
