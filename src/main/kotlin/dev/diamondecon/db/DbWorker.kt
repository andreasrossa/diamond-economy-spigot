package dev.diamondecon.db

import dev.diamondecon.util.PREFIX
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Runs database work off the main thread and hands the results back to it.
 *
 * Jobs run one at a time on a single thread, in the order they were submitted, so writes never overtake each
 * other. Callbacks run on the main thread (drained every tick), where they may touch the world and inventories.
 * [submit] must only be called from the main thread.
 *
 * On [stop], queued jobs finish and their callbacks still run. That's what keeps money and items in sync on
 * shutdown: a committed payment always gets its item step.
 */
class DbWorker(private val logger: Logger) {

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "DiamondEcon-DB").apply { isDaemon = true } }
    private val callbacks = ConcurrentLinkedQueue<() -> Unit>()
    private var drainTask: BukkitTask? = null

    /** Starts handing results back to the main thread, once per tick. */
    fun start(plugin: JavaPlugin) {
        drainTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { drain() }, 1, 1)
    }

    /** Runs [work] on the database thread, then [onDone] on the main thread with its result. */
    fun <T> submit(work: () -> T, onDone: (Result<T>) -> Unit) {
        if (executor.isShutdown) {
            // Stopping: no thread to hand off to, so finish on the main thread right away.
            onDone(runCatching(work))
            return
        }
        executor.execute {
            val result = runCatching(work)
            callbacks.add { onDone(result) }
        }
    }

    /** Fire-and-forget write. Failures are logged as "Could not [what]". */
    fun write(what: String, work: () -> Unit) = submit(work) { result ->
        result.onFailure { logger.log(Level.SEVERE, "Could not $what", it) }
    }

    /**
     * Like [submit] for a command: on success runs [then] with the result; on failure logs it and tells
     * [sender] that nothing changed. Only use it where a failed [work] really leaves everything unchanged.
     */
    fun <T> forSender(sender: CommandSender, what: String, work: () -> T, then: (T) -> Unit) = submit(work) { result ->
        result.fold(then) { e ->
            logger.log(Level.SEVERE, "$what failed", e)
            sender.sendMessage(SOMETHING_WENT_WRONG)
        }
    }

    /** Runs the callbacks of finished jobs. Main thread only. */
    internal fun drain() {
        while (true) {
            val callback = callbacks.poll() ?: return
            try {
                callback()
            } catch (e: Exception) {
                logger.log(Level.SEVERE, "A database callback failed", e)
            }
        }
    }

    /** Finishes all queued jobs and runs their callbacks. Call from onDisable, before closing the database. */
    fun stop() {
        drainTask?.cancel()
        executor.shutdown()
        if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
            logger.severe("Database jobs were still running after 30s; their results are lost")
        }
        drain()
    }

    companion object {
        val SOMETHING_WENT_WRONG = "$PREFIX${ChatColor.RED}Something went wrong, nothing was changed."
    }
}
