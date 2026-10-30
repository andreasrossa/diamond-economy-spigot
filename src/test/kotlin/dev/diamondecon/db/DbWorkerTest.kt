package dev.diamondecon.db

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The test thread plays the main thread: callbacks only run when it calls drain() or stop(). */
class DbWorkerTest {

    private val log = LogCapture()
    private val worker = DbWorker(log.logger)

    @AfterTest
    fun tearDown() = worker.stop()

    /** Waits until every job submitted so far has run. */
    private fun awaitJobs() {
        val done = CountDownLatch(1)
        worker.submit({ done.countDown() }) {}
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `jobs run off the calling thread, callbacks only when drained`() {
        val main = Thread.currentThread()
        var jobThread: Thread? = null
        var callbackThread: Thread? = null
        worker.submit({ jobThread = Thread.currentThread(); 42 }) { result ->
            callbackThread = Thread.currentThread()
            assertEquals(42, result.getOrThrow())
        }
        awaitJobs()
        assertEquals(null, callbackThread, "Callbacks must wait for drain()")
        worker.drain()
        assertNotEquals(main, jobThread)
        assertEquals(main, callbackThread)
    }

    @Test
    fun `jobs and callbacks keep submission order`() {
        val jobs = mutableListOf<Int>()
        val callbacks = mutableListOf<Int>()
        repeat(100) { i -> worker.submit({ synchronized(jobs) { jobs += i } }) { callbacks += i } }
        awaitJobs()
        worker.drain()
        assertEquals((0 until 100).toList(), jobs)
        assertEquals((0 until 100).toList(), callbacks)
    }

    @Test
    fun `failures reach the callback`() {
        var result: Result<Int>? = null
        worker.submit<Int>({ error("boom") }) { result = it }
        awaitJobs()
        worker.drain()
        assertEquals("boom", result!!.exceptionOrNull()?.message)
    }

    @Test
    fun `a failing callback doesn't stop the others`() {
        var ran = false
        worker.submit({}) { error("callback bug") }
        worker.submit({}) { ran = true }
        awaitJobs()
        worker.drain()
        assertTrue(ran)
        assertTrue(log.messages(Level.SEVERE).any { "callback failed" in it })
    }

    @Test
    fun `failed writes are logged`() {
        worker.write("save the thing") { error("db down") }
        awaitJobs()
        worker.drain()
        assertEquals(listOf("Could not save the thing"), log.messages(Level.SEVERE))
    }

    @Test
    fun `stop finishes queued jobs and runs their callbacks`() {
        val gate = CountDownLatch(1)
        val callbacks = mutableListOf<String>()
        worker.submit({ gate.await() }) { callbacks += "slow" }
        worker.submit({ "queued" }) { callbacks += it.getOrThrow() }
        Thread { Thread.sleep(100); gate.countDown() }.start()
        worker.stop()
        assertEquals(listOf("slow", "queued"), callbacks)
    }

    @Test
    fun `after stop, work runs inline so callbacks can still hit the database`() {
        worker.stop()
        val main = Thread.currentThread()
        var jobThread: Thread? = null
        var result: Result<Int>? = null
        worker.submit({ jobThread = Thread.currentThread(); 7 }) { result = it }
        assertEquals(main, jobThread)
        assertEquals(7, result!!.getOrThrow())
    }
}
