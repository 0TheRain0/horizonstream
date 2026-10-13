package com.cmsoft.horizonstream.lib

/** Serializes JNI access with detachment, without holding a lock during join. */
internal class NativeSessionHandle(private var pointer: Long) {
    private val lock = Any()

    fun <T> withPointer(action: (Long) -> T): T? = synchronized(lock) {
        if(pointer == 0L) null else action(pointer)
    }

    fun detach(): Long = synchronized(lock) {
        val owned = pointer
        pointer = 0L
        owned
    }
}
