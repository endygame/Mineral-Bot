package gg.mineral.bot.base.client.concurrent

import gg.mineral.bot.api.concurrent.ListenableFuture
import gg.mineral.bot.api.instance.ClientInstance
import java.util.concurrent.ConcurrentLinkedQueue

class ListenableFutureImpl<T : ClientInstance>(instance: T) : ListenableFuture<T>(instance) {
    private val lock = Any()
    private val onCompleteListeners = ConcurrentLinkedQueue<(T) -> Unit>()
    private val onErrorListeners = ConcurrentLinkedQueue<(Throwable) -> Unit>()
    private var failure: Throwable? = null

    override fun onComplete(consumer: (T) -> Unit): ListenableFuture<T> {
        var callNow = false
        synchronized(lock) {
            if (cancelled) return this
            if (done) {
                callNow = failure == null
            } else {
                onCompleteListeners.add(consumer)
            }
        }
        if (callNow) {
            consumer(get())
        }
        return this
    }

    override fun onError(consumer: (Throwable) -> Unit): ListenableFuture<T> {
        var completedFailure: Throwable? = null
        synchronized(lock) {
            if (cancelled) return this
            if (done) {
                completedFailure = failure
            } else {
                onErrorListeners.add(consumer)
            }
        }
        completedFailure?.let(consumer)
        return this
    }

    fun complete() {
        val listeners: List<(T) -> Unit>
        synchronized(lock) {
            if (done || cancelled) return
            done = true
            listeners = onCompleteListeners.toList()
            onCompleteListeners.clear()
            onErrorListeners.clear()
        }
        listeners.forEach { it(get()) }
    }

    fun fail(throwable: Throwable) {
        val listeners: List<(Throwable) -> Unit>
        synchronized(lock) {
            if (done || cancelled) return
            failure = throwable
            done = true
            listeners = onErrorListeners.toList()
            onErrorListeners.clear()
            onCompleteListeners.clear()
        }
        listeners.forEach { it(throwable) }
    }
}
