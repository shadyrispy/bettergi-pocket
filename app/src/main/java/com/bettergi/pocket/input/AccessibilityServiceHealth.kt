package com.bettergi.pocket.input

object AccessibilityServiceHealth {
    enum class State {
        CONNECTED,
        DISABLED,
        DISCONNECTED,
    }

    fun state(connected: Boolean, listedInSettings: Boolean): State = when {
        connected -> State.CONNECTED
        listedInSettings -> State.DISCONNECTED
        else -> State.DISABLED
    }

    fun isListed(enabledServices: String?, vararg componentNames: String): Boolean {
        if (enabledServices.isNullOrBlank()) return false
        return enabledServices.split(':').any { token ->
            componentNames.any { it.equals(token, ignoreCase = true) }
        }
    }
}
