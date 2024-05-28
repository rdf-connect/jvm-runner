package processors;

import java.util.Map;
import java.util.Optional;
import technology.idlab.runner.Processor;
import technology.idlab.runner.ProcessorDefinition;

@ProcessorDefinition(resource = "/processors/optionals.ttl")
public class Optionals extends Processor {
  // Parameters
  public final String required;
  public final Optional<String> present;
  public final Optional<String> missing;

  public Optionals(Map<String, Object> args) {
    // Call super constructor.
    super(args);

    // Parameters
    this.required = this.getArgument("required");
    this.present = this.getOptionalArgument("present");
    this.missing = this.getOptionalArgument("missing");
  }

  public void exec() {}
}