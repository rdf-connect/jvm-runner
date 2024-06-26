package technology.idlab.parser.impl

import java.io.File
import org.apache.jena.rdf.model.Model
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.Property
import org.apache.jena.rdf.model.RDFNode
import org.apache.jena.rdf.model.Resource
import org.apache.jena.rdf.model.ResourceFactory.createProperty
import org.apache.jena.rdf.model.ResourceFactory.createResource
import org.apache.jena.shacl.vocabulary.SHACLM
import org.apache.jena.vocabulary.RDF
import runner.Runner
import technology.idlab.extensions.validate
import technology.idlab.parser.Parser
import technology.idlab.parser.intermediate.IRArgument
import technology.idlab.parser.intermediate.IRParameter
import technology.idlab.parser.intermediate.IRProcessor
import technology.idlab.parser.intermediate.IRStage
import technology.idlab.util.Log

private class CONN {
  companion object {
    const val NS = "https://www.rdf-connect.com/#"
    val NAMESPACE = createResource(NS)!!
    val processor = createProperty("${NS}Processor")!!
    val stage = createProperty("${NS}stage")!!
    val channel = createProperty("${NS}Channel")!!
    val target = createProperty("${NS}target")!!
    val metadata = createProperty("${NS}metadata")!!
    val arguments = createProperty("${NS}arguments")!!
    val kotlinRunner = createResource("${NS}Kotlin")!!
  }
}

private fun Resource.toRunnerTarget(): Runner.Target {
  return when (this) {
    CONN.kotlinRunner -> Runner.Target.JVM
    else -> Log.shared.fatal("Unknown runner type: $this")
  }
}

/**
 * Maps a resource to an IRParameter.Type based on the URI. Note that this implementation is
 * actually quite slow, and we should probably use Apache Jena native APIs here.
 */
private fun Resource.toIRParameterType(): IRParameter.Type {
  return when (this.uri) {
    "http://www.w3.org/2001/XMLSchema#boolean" -> IRParameter.Type.BOOLEAN
    "http://www.w3.org/2001/XMLSchema#byte" -> IRParameter.Type.BYTE
    "http://www.w3.org/2001/XMLSchema#datetime" -> IRParameter.Type.DATE
    "http://www.w3.org/2001/XMLSchema#double" -> IRParameter.Type.DOUBLE
    "http://www.w3.org/2001/XMLSchema#float" -> IRParameter.Type.FLOAT
    "http://www.w3.org/2001/XMLSchema#int" -> IRParameter.Type.INT
    "http://www.w3.org/2001/XMLSchema#long" -> IRParameter.Type.LONG
    "http://www.w3.org/2001/XMLSchema#string" -> IRParameter.Type.STRING
    "http://www.rdf-connect.com/#/writer" -> IRParameter.Type.WRITER
    "http://www.rdf-connect.com/#/reader" -> IRParameter.Type.READER
    else -> Log.shared.fatal("Unknown datatype: ${this.uri}")
  }
}

/**
 * Return the first object which corresponds to a subject and predicate. Returns null if not found.
 */
private fun Model.objectOfProperty(resource: Resource, property: Property): RDFNode? {
  return try {
    this.listObjectsOfProperty(resource, property).next()
  } catch (e: NoSuchElementException) {
    null
  }
}

/**
 * Return the first subject which corresponds to a predicate and object. Returns null if not found.
 */
private fun Model.subjectWithProperty(property: Property, obj: RDFNode): Resource? {
  return try {
    this.listSubjectsWithProperty(property, obj).next()
  } catch (e: NoSuchElementException) {
    null
  }
}

/**
 * Create a mapping of String to IRParameter from a SHACL property. This is a recursive
 * implementation that will automatically parse nested classes.
 */
private fun Model.parseSHACLProperty(property: Resource): Pair<String, IRParameter> {
  // Retrieve required fields.
  val minCount = objectOfProperty(property, SHACLM.minCount)?.asLiteral()?.int
  val maxCount = objectOfProperty(property, SHACLM.maxCount)?.asLiteral()?.int
  val node = objectOfProperty(property, SHACLM.node)?.asResource()
  val datatype = objectOfProperty(property, SHACLM.datatype)?.asResource()

  // Retrieve the path of the property.
  val path =
      try {
        objectOfProperty(property, SHACLM.name)!!.asLiteral().string
      } catch (e: Exception) {
        Log.shared.fatal("SHACL property must have a name.")
      }

  // Determine the presence.
  val presence =
      if (minCount != null && minCount > 0) {
        IRParameter.Presence.REQUIRED
      } else {
        IRParameter.Presence.OPTIONAL
      }

  // Determine the count.
  val count =
      if (maxCount != null && maxCount == 1) {
        IRParameter.Count.SINGLE
      } else {
        IRParameter.Count.LIST
      }

  // Create a new parameter object.
  val parameter =
      if (datatype != null) {
        IRParameter(simple = datatype.toIRParameterType(), presence = presence, count = count)
      } else if (node != null) {
        IRParameter(complex = parseSHACLShape(node), presence = presence, count = count)
      } else {
        Log.shared.fatal("SHACL property must have either a datatype or a class.")
      }

  // Return the parameter mapped to its path.
  return Pair(path, parameter)
}

/**
 * Parse a SHACL shape into a mapping of String to IRParameter. This is a recursive implementation
 * that will automatically parse nested classes.
 */
private fun Model.parseSHACLShape(shape: Resource): Map<String, IRParameter> {
  val result = mutableMapOf<String, IRParameter>()

  for (property in listObjectsOfProperty(shape, SHACLM.property)) {
    val (key, parameter) = parseSHACLProperty(property.asResource())
    result[key] = parameter
  }

  return result
}

private fun Model.nameOfSHACLPath(path: Resource): String {
  val property =
      subjectWithProperty(SHACLM.path, path)
          ?: Log.shared.fatal("No property found for path: $path")
  return objectOfProperty(property, SHACLM.name)?.asLiteral()?.string
      ?: Log.shared.fatal("No name found for path: $path")
}

/**
 * Parse the arguments of a stage. This is a recursive implementation that will automatically parse
 * nested classes. Recursion will continue until all objects found are literals.
 */
private fun Model.parseArguments(node: Resource): Map<String, IRArgument> {
  val simple = mutableMapOf<String, MutableList<String>>()
  val complex = mutableMapOf<String, MutableList<Map<String, IRArgument>>>()

  // Go over each triple of the resource. If it is a literal, add it to the simple list. Otherwise,
  // call recursively and add it to the complex list.
  for (triple in listStatements(node, null, null as RDFNode?)) {
    val key = nameOfSHACLPath(triple.predicate)
    val value = triple.`object`

    if (value.isLiteral) {
      val list = simple.getOrPut(key) { mutableListOf() }
      list.add(value.asLiteral().string)
    } else if (value.isResource) {
      val list = complex.getOrPut(key) { mutableListOf() }
      val nested = parseArguments(value.asResource())
      list.add(nested)
    } else {
      Log.shared.fatal("Unknown RDFNode type: $value")
    }
  }

  // Combine both simple and complex mappings as a single map to IRArguments.
  return simple.mapValues { (_, value) -> IRArgument(simple = value) } +
      complex.mapValues { (_, value) -> IRArgument(complex = value) }
}

private fun Model.parseProcessor(processor: Resource): IRProcessor {
  val uri = processor.toString()

  // Determine the target runner.
  val target = objectOfProperty(processor, CONN.target)!!.asResource().toRunnerTarget()

  // Parse the parameters by SHACL shape.
  val shape =
      subjectWithProperty(SHACLM.targetClass, processor)
          ?: Log.shared.fatal("No shape found for processor: ${processor}")
  val parameters =
      listObjectsOfProperty(shape, SHACLM.property)
          .toList()
          .find {
            val path = objectOfProperty(it.asResource(), SHACLM.path)?.asResource()
            return@find path == CONN.arguments
          }
          ?.let { objectOfProperty(it.asResource(), SHACLM.node)?.asResource() }
          ?.let { parseSHACLShape(it) }
          ?: Log.shared.fatal("No argument shape found for processor: $processor")

  // Parse metadata.
  val metadata = mutableMapOf<String, String>()
  for (entry in this.listObjectsOfProperty(processor, CONN.metadata)) {
    val literal =
        try {
          entry.asLiteral().string
        } catch (e: Exception) {
          Log.shared.fatal("Metadata must be a literal.")
        }
    val (key, value) = literal.split(':')
    metadata[key.trim()] = value.trim()
  }

  return IRProcessor(uri, target, parameters, metadata)
}

private fun Model.parseProcessors(): List<IRProcessor> {
  return listSubjectsWithProperty(RDF.type, CONN.processor).toList().map { parseProcessor(it) }
}

private fun Model.parseStages(): List<IRStage> {
  return listSubjectsWithProperty(RDF.type, CONN.processor)
      .toList()
      .map { processor ->
        listSubjectsWithProperty(RDF.type, processor).toList().map { stage ->
          val arguments =
              objectOfProperty(stage, CONN.arguments)?.asResource()
                  ?: Log.shared.fatal("No arguments found for stage: $stage")

          IRStage(stage.uri, parseProcessor(processor), parseArguments(arguments))
        }
      }
      .flatten()
}

class RDFParser(file: File) : Parser() {
  /* The pipeline config contains additional SHACL shapes. */
  private val config = File(this.javaClass.getResource("/pipeline.ttl")!!.toURI())

  /* Parse the RDF file into an Apache Jena model. */
  private val model: Model =
      ModelFactory.createDefaultModel()
          .read(config.inputStream(), null, "TURTLE")
          .read(file.inputStream(), null, "TURTLE")
          .validate()

  /* Cache the processors. */
  private val processors: List<IRProcessor> = model.parseProcessors()

  /* Cache stages as well. */
  private val stages: List<IRStage> = model.parseStages()

  override fun processors(): List<IRProcessor> {
    return processors
  }

  override fun stages(): List<IRStage> {
    return stages
  }
}
