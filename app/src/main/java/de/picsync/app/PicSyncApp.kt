package de.picsync.app

import android.app.Application

class PicSyncApp : Application() {
    val session by lazy { WhatsAppSession(this) }
    val contacts by lazy { ContactRepository(this) }
    val sync by lazy { SyncEngine(this, session, contacts) }
}
