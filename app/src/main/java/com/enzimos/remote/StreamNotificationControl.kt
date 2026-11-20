package com.enzimos.remote

import java.lang.ref.WeakReference

internal object StreamNotificationControl {
    interface Owner {
        val token: String
        fun requestStop()
    }

    private var current: WeakReference<Owner>? = null

    @Synchronized fun claim(owner: Owner) {
        require(owner.token.isNotBlank())
        current = WeakReference(owner)
    }

    @Synchronized fun owns(token: String?): Boolean =
        token != null && current?.get()?.token == token

    @Synchronized fun release(owner: Owner): Boolean {
        if (current?.get() !== owner) return false
        current = null
        return true
    }

    fun requestStop(token: String?): Boolean {
        val target = synchronized(this) {
            current?.get()?.takeIf { token != null && it.token == token }
        } ?: return false
        target.requestStop()
        return true
    }
}
