package trawl.desktop

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class IndexAccess {
  private val lock = ReentrantLock()
  private val drained = lock.newCondition()
  private var accepting = true
  private var readers = 0

  fun acquire(): AutoCloseable? = lock.withLock {
    if (!accepting) return null
    readers++
    AutoCloseable {
      lock.withLock {
        readers--
        drained.signalAll()
      }
    }
  }

  fun <Result> read(unavailable: Result, block: () -> Result): Result {
    val lease = acquire() ?: return unavailable
    return lease.use { block() }
  }

  fun <Result> flow(unavailable: Result, block: suspend FlowCollector<Result>.() -> Unit): Flow<Result> = flow {
    val lease = acquire()
    if (lease == null) emit(unavailable) else lease.use { block() }
  }

  fun pause() = lock.withLock {
    accepting = false
    while (readers != 0) drained.await()
  }

  fun resume() = lock.withLock { accepting = true }
}
