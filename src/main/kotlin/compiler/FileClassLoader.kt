package technology.idlab.compiler

import technology.idlab.logging.createLogger
import technology.idlab.logging.fatal
import java.io.File

class FileClassLoader : ClassLoader() {
    private val logger = createLogger()

    fun fromFile(
        file: File,
        name: String,
    ): Class<*> {
        logger.info("Loading ${file.absoluteFile}")

        // Define and return the class.
        return try {
            val bytes = file.readBytes()
            defineClass(name, bytes, 0, bytes.size)
        } catch (e: ClassFormatError) {
            logger.fatal("Failed to load class $name")
        }
    }
}
