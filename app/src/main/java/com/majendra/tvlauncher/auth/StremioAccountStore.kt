package com.majendra.tvlauncher.auth
import android.content.Context
/** No account can be configured or read in the public edition. */
class StremioAccountStore(context: Context) {
    val generation: Long get() = 0L
    fun hasSession(): Boolean = false
    fun <T> withGeneration(expected: Long, action: () -> T): T? = if (expected == 0L) action() else null
}
