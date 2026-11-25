package cc.szakacs.aurion

import android.app.Application
import cc.szakacs.aurion.reminders.Reminders

class AurionApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Reminders.createChannels(this)
        // Falls die App aktualisiert/neu gestartet wurde: offene Erinnerungen sicherstellen
        Reminders.restoreAll(this)
    }
}
