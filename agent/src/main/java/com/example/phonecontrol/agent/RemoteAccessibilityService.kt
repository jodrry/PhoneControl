package com.example.phonecontrol.agent

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * The only privileged component in the app. It is bound by the system after the
 * user enables it in Settings > Accessibility. GestureInjector uses this singleton.
 */
class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: RemoteAccessibilityService? = null

        fun peek(): RemoteAccessibilityService? = instance
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AgentState.setAccessibilityConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No window content is inspected. The service is used only for gestures.
    }

    override fun onInterrupt() {
        // Nothing to interrupt.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearInstance()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearInstance()
        super.onDestroy()
    }

    private fun clearInstance() {
        if (instance === this) {
            instance = null
            AgentState.setAccessibilityConnected(false)
        }
    }
}
