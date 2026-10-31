package dev.chudpresence.mobile

import android.app.Application
import dev.chudpresence.mobile.data.SettingsStore
import dev.chudpresence.mobile.media.MediaSessionRepository
import dev.chudpresence.mobile.reporting.CrunchyrollApi
import dev.chudpresence.mobile.reporting.TwitchApi
import dev.chudpresence.mobile.services.ServiceRegistry
import dev.chudpresence.mobile.link.LinkSyncManager

class ChudPresenceApplication : Application() {
    val settingsStore by lazy { SettingsStore(this) }
    internal val crunchyrollApi by lazy { CrunchyrollApi() }
    internal val twitchApi by lazy { TwitchApi(BuildConfig.TWITCH_CLIENT_ID) }
    val serviceRegistry by lazy { ServiceRegistry(this) }
    val sessions by lazy { MediaSessionRepository(this) }
    internal val linkSync by lazy { LinkSyncManager(this, settingsStore, serviceRegistry) }
}
