package technology.idlab.util

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

suspend fun <T> retries(times: Int, milliseconds: Long = 1000, block: suspend () -> T): T =
    coroutineScope {
      for (i in 0 until times) {
        try {
          return@coroutineScope block()
        } catch (e: Exception) {
          Log.shared.severe("[$i/$times] ${e.message.toString()}")
          delay(milliseconds)
        }
      }

      Log.shared.fatal("Maximum retries exceeded.")
    }
