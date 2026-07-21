package gg.mineral.bot.base.client.concurrent

import gg.mineral.bot.api.instance.ClientInstance
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ListenableFutureImplTest {

    @Test
    fun registeringErrorListenerAfterSuccessDoesNotReportFailure() {
        val instance = clientInstance()
        val future = ListenableFutureImpl(instance)
        future.complete()

        var successes = 0
        var errors = 0
        future.onComplete { successes++ }
        future.onError { errors++ }

        assertEquals(1, successes)
        assertEquals(0, errors)
        assertTrue(future.isDone)
    }

    @Test
    fun registeringErrorListenerAfterFailureReportsOriginalFailure() {
        val future = ListenableFutureImpl(clientInstance())
        val expected = IllegalStateException("join failed")
        future.fail(expected)

        var actual: Throwable? = null
        var successes = 0
        future.onComplete { successes++ }
        future.onError { actual = it }

        assertEquals(0, successes)
        assertSame(expected, actual)
        assertTrue(future.isDone)
    }

    @Test
    fun listenersRegisteredBeforeCompletionReceiveOnlySuccess() {
        val future = ListenableFutureImpl(clientInstance())
        var successes = 0
        var errors = 0
        future.onComplete { successes++ }
        future.onError { errors++ }

        future.complete()

        assertEquals(1, successes)
        assertEquals(0, errors)
    }

    private fun clientInstance(): ClientInstance {
        return Proxy.newProxyInstance(
            ClientInstance::class.java.classLoader,
            arrayOf(ClientInstance::class.java)
        ) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0F
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }
        } as ClientInstance
    }
}
