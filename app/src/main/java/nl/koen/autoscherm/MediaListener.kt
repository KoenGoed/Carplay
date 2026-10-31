package nl.koen.autoscherm

import android.service.notification.NotificationListenerService

/**
 * Geeft de app toegang tot de muziekbediening van Spotify.
 * Kan ook de navigatie stoppen via de knop "Afsluiten" in de melding van Google Maps of Waze.
 */
class MediaListener : NotificationListenerService() {

    companion object {
        @Volatile
        private var instance: MediaListener? = null

        private val NAV_APPS = setOf("com.google.android.apps.maps", "com.waze")
        private val EXIT_WORDS = Regex("afsluiten|sluiten|stop|beëindig|beeindig|exit|end|close|annuleren|cancel", RegexOption.IGNORE_CASE)

        /** Drukt op "Afsluiten" in de navigatiemelding. Geeft false als dat niet lukte. */
        fun stopNavigation(): Boolean {
            val listener = instance ?: return false
            val active = try { listener.activeNotifications } catch (e: Exception) { null } ?: return false
            for (sbn in active) {
                if (sbn.packageName !in NAV_APPS) continue
                val action = sbn.notification.actions?.firstOrNull { EXIT_WORDS.containsMatchIn(it.title?.toString().orEmpty()) }
                    ?: continue
                return try {
                    action.actionIntent.send()
                    true
                } catch (e: Exception) {
                    false
                }
            }
            return false
        }
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }
}
