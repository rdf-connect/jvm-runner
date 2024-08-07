package technology.idlab.parser

import technology.idlab.intermediate.IRPackage
import technology.idlab.intermediate.IRPipeline
import technology.idlab.intermediate.IRProcessor
import technology.idlab.intermediate.IRRunner

/**
 * Parse an RDF file into an intermediate representation, and validate it against the ontology and
 * SHACL shapes.
 */
interface Parser {
  /** The pipelines in the current configuration. */
  val pipelines: List<IRPipeline>

  /** The packages in the current configuration. */
  val packages: List<IRPackage>

  /** List of all known processors. */
  val processors: List<IRProcessor>

  /** List of all known runners. */
  val runners: List<IRRunner>
}
