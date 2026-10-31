package dev.chudpresence.mobile.reporting

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection

/** SDK 1.10 binds RPC through its engine Activity, but reporting outlives that Activity. */
internal object DiscordRpcBinding {
    const val SERVICE_ACTION = "com.discord.socialsdk.rpc.IDiscordRpcService"
    // SDK service binding and unbinding run on the main thread, including after activity recreation.
    private val connections = mutableSetOf<ServiceConnection>()

    fun bind(context: Context, intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
        val bound = context.applicationContext.bindService(intent, connection, flags)
        if (bound) connections.add(connection)
        return bound
    }

    fun unbindIfOwned(context: Context, connection: ServiceConnection): Boolean {
        if (!connections.remove(connection)) return false
        context.applicationContext.unbindService(connection)
        return true
    }
}
