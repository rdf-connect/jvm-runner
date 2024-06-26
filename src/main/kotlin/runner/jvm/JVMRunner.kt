package runner.jvm

import arrow.core.zip
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.kotlin.backend.common.push
import runner.Runner
import technology.idlab.parser.intermediate.IRArgument
import technology.idlab.parser.intermediate.IRParameter
import technology.idlab.parser.intermediate.IRStage
import technology.idlab.util.Log
import technology.idlab.util.Log.Cause.*

class JVMRunner(
    fromProcessors: Channel<Payload>,
) : Runner(fromProcessors) {
  /** Map of all stages in the runner. */
  private val stages = mutableMapOf<String, Processor>()

  /** All stages are ran in their own job, for cancellation purposes we keep track of them. */
  private val jobs: MutableList<Job> = mutableListOf()

  /** Incoming messages are delegated to sub channels. These are mapped by their URI. */
  private val readers = mutableMapOf<String, Channel<ByteArray>>()

  override suspend fun load(stage: IRStage) {
    /** Load the class into the JVM> */
    val className = stage.processor.metadata["class"] ?: Log.shared.fatal(JVM_RUNNER_STAGE_NO_CLASS)
    val clazz = Class.forName(className) as Class<*>

    /** Check if instantiatable. */
    if (!Processor::class.java.isAssignableFrom(clazz)) {
      Log.shared.fatal("Processor class does not extend Processor.")
    }

    /** Build the argument map. */
    val arguments = this.instantiate(stage.processor.parameters.zip(stage.arguments))

    /** Initialize the stage with the new map. */
    val constructor = clazz.getConstructor(Map::class.java)
    this.stages[stage.uri] = constructor.newInstance(arguments) as Processor
  }

  override suspend fun exec() = coroutineScope {
    Log.shared.info("Executing all stages.")

    // Initialize a job for all processors.
    this@JVMRunner.stages.values.forEach {
      val job = launch { it.exec() }
      jobs.push(job)
    }

    // Route all incoming messages.
    Log.shared.debug("Begin routing messages in JVMRunner.")
    while (isActive) {
      withTimeout(1000) {
        val message = toProcessors.receive()
        val target = readers[message.channel]!!
        Log.shared.info("'${message.data.decodeToString()}' -> ${message.channel}")
        target.send(message.data)
      }
    }
    Log.shared.debug("Ending routing messages in JVMRunner.")

    // Await all processors.
    jobs.forEach { it.cancelAndJoin() }
  }

  override suspend fun exit() {
    Log.shared.info("Exiting the JVM Runner.")
    super.exit()

    // Close all readers.
    for (reader in this.readers.values) {
      reader.close()
    }

    // Suspend all jobs.
    jobs.map { it.apply { it.cancel() } }.forEach { it.join() }
  }

  private fun instantiate(
      serialized: Map<String, Pair<IRParameter, IRArgument>>
  ): Map<String, Any> {
    return serialized.mapValues { (_, map) ->
      val (parameter, arguments) = map

      when (parameter.kind) {
        IRParameter.Kind.SIMPLE -> {
          arguments.getSimple().map { instantiate(parameter.getSimple(), it) }
        }
        IRParameter.Kind.COMPLEX -> {
          arguments.getComplex().map { instantiate(parameter.getComplex().zip(it)) }
        }
      }
    }
  }

  private fun instantiate(type: IRParameter.Type, value: String): Any {
    return when (type) {
      IRParameter.Type.BOOLEAN -> value.toBoolean()
      IRParameter.Type.BYTE -> value.toByte()
      IRParameter.Type.DATE -> TODO()
      IRParameter.Type.DOUBLE -> value.toDouble()
      IRParameter.Type.FLOAT -> value.toFloat()
      IRParameter.Type.INT -> value.toInt()
      IRParameter.Type.LONG -> value.toLong()
      IRParameter.Type.STRING -> value
      IRParameter.Type.WRITER -> return Writer(this.fromProcessors, value)
      IRParameter.Type.READER -> {
        val channel = this.readers[value] ?: Channel()
        this.readers[value] = channel
        return Reader(channel, value)
      }
    }
  }
}
