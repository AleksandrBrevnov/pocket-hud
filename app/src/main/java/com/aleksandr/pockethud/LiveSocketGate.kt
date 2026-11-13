package com.aleksandr.pockethud

/** Owns readiness for one socket generation. Never sends to a replacement socket. */
class LiveSocketGate<T : Any> {
    private var generation = 0L
    private var socket: T? = null
    private var ready = false

    @Synchronized fun begin(): Long {
        generation++
        socket = null
        ready = false
        return generation
    }

    @Synchronized fun bind(token: Long, candidate: T): Boolean {
        if (token != generation) return false
        socket = candidate
        ready = false
        return true
    }

    @Synchronized fun isCurrent(token: Long, candidate: T): Boolean =
        token == generation && socket === candidate

    @Synchronized fun isGeneration(token: Long): Boolean = token == generation

    @Synchronized fun markReady(token: Long, candidate: T): Boolean {
        if (!isCurrent(token, candidate)) return false
        ready = true
        return true
    }

    @Synchronized fun pause() { ready = false }

    @Synchronized fun send(action: (T) -> Boolean): Boolean {
        val target = socket ?: return false
        return ready && action(target)
    }
}
