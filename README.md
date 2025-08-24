# JVM Runner Plugin for RDF Connect

The **JvmRunner** executes processors implemented in the Java Virtual Machine (JVM).  
It allows you to integrate custom Java (or Kotlin, Scala, etc.) processors into an RDF Connect streaming pipeline by providing a JAR and a class name.

## Overview

- **Runner type**: `rdfc:JvmRunner` (imported automatically).  
- **Processor definition**: Each processor must declare a JAR and a fully qualified Java class via `rdfc:javaImplementationOf`.  
- **Implementation requirement**: All processors extend the abstract class `io.github.rdfc.Processor<T>` and provide lifecycle methods.  
- **Packaging requirement**: Processors must include a descriptor RDF file (e.g., `index.ttl`) inside their JAR.  
- **Distribution option**: You can publish your processor JAR with [JitPack](https://jitpack.io) so it can be included as a dependency in pipelines.  


## Using Processors in a Pipeline

To use your JVM processor in a pipeline:

1. Import the JvmRunner:

```turtle
<> owl:imports <https://javadoc.jitpack.io/com/github/rdf-connect/jvm-runner/runner/master-SNAPSHOT/runner-master-SNAPSHOT-index.jar>.
```

2. Link your processor to the runner:

```turtle
@prefix rdfc: <https://w3id.org/rdf-connect#>.
<> a rdfc:Pipeline;
   rdfc:consistsOf [
     rdfc:instantiates rdfc:JvmRunner;
     rdfc:processor <myProcessor>;
   ].
<myProcessor> a rdfc:TestProcessor.
```


## Implementing a new processor

Processors must:

1. **Extend the abstract class** `io.github.rdfc.Processor<T>` where `T` is an `Args` class containing configuration fields.
2. **Implement lifecycle methods**:
   - `init(Consumer<Void> callback)` — initialization.
   - `transform(Consumer<Void> callback)` — processing of inputs from readers, called for each processor before produce.
   - `produce(Consumer<Void> callback)` — producing data, useful for processor like a file reader.
   The callbacks should be called to indicate that the function is finished.
3. **Define an `Args` class** with fields matching RDF properties defined in the SHACL shape.

### Processor description file

The processor should be accompanied with a description file, often called `index.ttl`.

They require the following fields:
* `rdfc:javaImplementationOf` with value `rdfc:Processor`, indicating that this processor is a JavaProcessor,
* `rdfc:jar` pointing to the resulting jar, often `<>` pointing to the current jar  
* `rdfc:class` the fully qualified name of the processor
* A SHACL shape defining the required arguments.

For example, the following description file declares a processor with arguments `{ reader: Reader, writer: Writer, additionalText: string }`.
A matching implementation can be found on [github](https://github.com/rdf-connect/template-processor-jvm/blob/main/src/main/java/org/example/Library.java).
```turtle
@prefix rdfc: <https://w3id.org/rdf-connect#>.
@prefix sh: <http://www.w3.org/ns/shacl#>.
@prefix xsd: <http://www.w3.org/2001/XMLSchema#>.

rdfc:TestProcessor rdfc:javaImplementationOf rdfc:Processor;
  rdfc:class "org.example.Library";
  rdfc:jar <file:./build/libs/my-processor-all.jar>.

[] a sh:NodeShape;
  sh:targetClass rdfc:TestProcessor;
  sh:property [
    sh:path rdfc:reader;
    sh:name "reader";
    sh:minCount 1;
    sh:maxCount 1;
    sh:class rdfc:Reader;
  ], [
    sh:path rdfc:writer;
    sh:name "writer";
    sh:minCount 1;
    sh:maxCount 1;
    sh:class rdfc:Writer;
  ], [
    sh:path rdfc:additionalText;
    sh:name "additionalText";
    sh:minCount 1;
    sh:maxCount 1;
    sh:datatype xsd:string;
  ].
```

### Build Instructions

To build a JVM processor for use with the JvmRunner:

#### Dependencies

Your `build.gradle` should include:

```gradle
plugins {
    id 'java'
    id 'com.github.johnrengelman.shadow' version '8.1.1'
    id 'maven-publish'
}

repositories {
    mavenCentral()
    maven { url = 'https://jitpack.io' }
}

dependencies {
    implementation 'com.google.protobuf:protobuf-java:4.28.2'
    implementation 'com.github.rdf-connect.jvm-runner:types:master-SNAPSHOT'
}
```

#### Fat JAR Packaging

Use the Shadow plugin to produce a fat JAR that includes your processor and its descriptor:
``` gradle
tasks.named("shadowJar", Jar) {
    // add your processor descriptor (e.g., index.ttl) to the root of the jar
    from("index.ttl") {
        into("")
    }
}
```

The fat jar is built with `gradle shadowJar`


#### Publishing with JitPack

To make your processor available as a dependency from GitHub via [JitPack](https://jitpack.io), add the following to your `build.gradle`.

```gradle 
publishing {
    publications {
        maven(MavenPublication) {
            // publish the fat JAR
            artifact(tasks.shadowJar)
        }
    }
}
```

Then:

1. Push your code to GitHub
2. Users can then include your processor as a dependency like this:

```gradle
repositories {
    mavenCentral()
    maven { url = 'https://jitpack.io' }
}

dependencies {
    implementation 'com.github.<your-github-user>:<your-repo>:master-SNAPSHOT' // or a git hash or release
}
```



## Notes

* Args class fields must align with RDF properties defined in the SHACL shape.
* Descriptor file (e.g., index.ttl) must be packaged in the JAR.
* Fat JAR packaging ensures no dependency issues when running.
* Publishing with JitPack allows others to use your processor directly via GitHub.

