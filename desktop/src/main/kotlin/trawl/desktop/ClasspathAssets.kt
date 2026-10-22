package trawl.desktop

import com.craneai.findr.core.AssetSource
import java.io.FileNotFoundException
import java.io.InputStream

/** The bundled word lists and language centroids, read from the classpath (the app's assets folder is a resource root of this build). */
object ClasspathAssets : AssetSource {
  override fun open(name: String): InputStream =
    ClasspathAssets::class.java.classLoader.getResourceAsStream(name) ?: throw FileNotFoundException(name)
}
