package runner.impl

import kotlinx.coroutines.channels.Channel
import runner.Runner
import runner.RunnerTest
import runner.jvm.JVMRunner

class JVMRunnerTest : RunnerTest() {
  override val target = "https://rdf-connect.com/#/JVMRunner"

  override val metadata: Map<String, String> = mapOf("class" to "technology.idlab.std.Transparent")

  override fun createRunner(): Runner = JVMRunner(Channel())
}
