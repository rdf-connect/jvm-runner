# RDF Connect: JVM runner


## Build
```sh
gradle build
```


## Use in a pipeline

The file `index.ttl` suggests how to specify the JvmRunner.
We currently face a problem of how to point correctly to the jar, here it is hard coded.

For the test pipeline there is a test processor in `test-processor`.
In their `build.gradle`, it points to the types jar from the runner, all Processors require this jar to implement against the expected `Processor<?>` abstract class.

```gradle
implementation files('../../types/lib/build/libs/types.jar')
```


