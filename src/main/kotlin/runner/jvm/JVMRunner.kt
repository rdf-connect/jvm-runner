package runner.jvm

import kotlin.concurrent.thread
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import runner.Runner
import technology.idlab.parser.intermediate.IRParameter
import technology.idlab.parser.intermediate.IRProcessor
import technology.idlab.parser.intermediate.IRStage
import technology.idlab.util.Log

class JVMRunner(outgoing: Channel<Payload> = Channel()) : Runner(outgoing) {
  private val processors = mutableMapOf<String, Pair<IRProcessor, Class<Processor>>>()
  private val stages = mutableMapOf<String, Processor>()

  /** Incoming messages are delegated to sub channels. These are mapped by their URI. */
  private val readers = mutableMapOf<String, Channel<ByteArray>>()

  /** Keep track of all spawned threads. */
  private var threads = mutableListOf<Thread>()

  // Handle incoming messages.
  private val handler = thread {
    try {
      runBlocking {
        while (true) {
          // Get the next message, check if it is valid.
          val message = this@JVMRunner.incoming.receiveCatching()
          if (message.isClosed || message.isFailure) {
            break
          }
          val payload = message.getOrNull()!!

          // Get the reader.
          val reader = readers[payload.destinationURI]!!
          reader.send(payload.data)
        }
      }
    } catch (e: InterruptedException) {
      Log.shared.severe("Message handler thread interrupted.")
    }
  }

  override suspend fun prepare(processor: IRProcessor) {
    val className = processor.metadata["class"] ?: Log.shared.fatal("Processor has no class key.")
    val clazz = Class.forName(className) as Class<*>

    if (!Processor::class.java.isAssignableFrom(clazz)) {
      Log.shared.fatal("Processor class does not extend Processor.")
    }

    this.processors[processor.uri] = Pair(processor, clazz as Class<Processor>)
  }

  override suspend fun prepare(stage: IRStage) {
    val processor =
        processors[stage.processor.uri]
            ?: Log.shared.fatal("Unknown processor: ${stage.processor.uri}")
    val irArguments = stage.arguments.associateBy { it.name }

    val arguments = mutableMapOf<String, Any>()
    for (parameter in processor.first.parameters) {
      val irArgument = irArguments[parameter.name]

      if (irArgument == null) {
        if (parameter.presence == IRParameter.Presence.REQUIRED) {
          Log.shared.fatal("Missing required argument: ${parameter.name}")
        }

        continue
      }

      if (parameter.count == IRParameter.Count.SINGLE) {
        if (irArgument.value.size != 1) {
          Log.shared.fatal("Expected single value for argument: ${parameter.name}")
        }

        val serialized = irArgument.value[0]
        arguments[parameter.name] = instantiate(parameter.type, serialized)
        continue
      }

      arguments[parameter.name] = irArgument.value.map { instantiate(parameter.type, it) }
    }

    val constructor = processor.second.getConstructor(Map::class.java)
    this.stages[stage.uri] = constructor.newInstance(arguments) as Processor
  }

  override suspend fun exec() {
    Log.shared.info("Bringing JVM stages online.")
    this.stages.values.forEach { this.threads.add(thread { it.exec() }) }
    Log.shared.info("All stages are online.")
  }

  override suspend fun status(): Status {
    TODO("Not yet implemented")
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
      IRParameter.Type.WRITER -> return Writer(this.outgoing, value)
      IRParameter.Type.READER -> {
        val channel = Channel<ByteArray>()
        this.readers[value] = channel
        return Reader(channel)
      }
    }
  }

  override fun halt() {
    handler.interrupt()
  }
}
