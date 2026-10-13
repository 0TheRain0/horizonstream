package com.cmsoft.horizonstream.lib

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class NativeSessionHandleTest {
    @Test
    fun destructionWaitsForAnInFlightNativeCall() {
        val handle = NativeSessionHandle(42L)
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val detaching = CountDownLatch(1)
        try {
            val call = executor.submit {
                handle.withPointer {
                    assertEquals(42L, it)
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val dispose = executor.submit<Long> {
                detaching.countDown()
                handle.detach()
            }
            assertTrue(detaching.await(5, TimeUnit.SECONDS))
            try {
                dispose.get(100, TimeUnit.MILLISECONDS)
                fail("Native pointer detached while its JNI call was still active")
            } catch(expected: TimeoutException) {
                // Destruction must wait for the JNI call to return.
            }
            release.countDown()
            call.get(5, TimeUnit.SECONDS)
            assertEquals(42L, dispose.get(5, TimeUnit.SECONDS).toLong())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun lateControllerReleaseCannotCallADeletedSession() {
        val handle = NativeSessionHandle(42L)
        assertEquals(42L, handle.detach())
        var called = false
        assertNull(handle.withPointer { called = true })
        assertFalse(called)
        assertEquals(0L, handle.detach())
    }

    @Test
    fun finalWorkerCallbackCanFinishWhileDisposerWaitsForJoin() {
        val handle = NativeSessionHandle(42L)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertEquals(42L, handle.detach())
            // A native join waits for this worker's final callback. It must
            // return without touching the pointer or waiting on a held lock.
            executor.submit {
                handle.withPointer { fail("Disposed pointer reached final callback") }
            }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun concurrentDisposersFreeThePointerOnlyOnce() {
        val handle = NativeSessionHandle(42L)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val disposers = (0 until 8).map {
                executor.submit<Long> {
                    start.await()
                    handle.detach()
                }
            }
            start.countDown()
            val pointers = disposers.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, pointers.count { it == 42L })
            assertEquals(7, pointers.count { it == 0L })
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun controllerUpdatesRacingShutdownNeverUseFreedMemory() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            repeat(200) {
                val handle = NativeSessionHandle(42L)
                val freed = AtomicBoolean(false)
                val start = CountDownLatch(1)
                val writers = (0 until 3).map {
                    executor.submit {
                        start.await()
                        repeat(100) {
                            handle.withPointer {
                                assertFalse("JNI called after free", freed.get())
                            }
                        }
                    }
                }
                val dispose = executor.submit {
                    start.await()
                    assertEquals(42L, handle.detach())
                    freed.set(true)
                }
                start.countDown()
                (writers + dispose).forEach { it.get(5, TimeUnit.SECONDS) }
                assertNull(handle.withPointer { fail("Late release used freed pointer") })
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
