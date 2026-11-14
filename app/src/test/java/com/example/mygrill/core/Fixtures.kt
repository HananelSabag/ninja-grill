package com.example.mygrill.core

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File

object Fixtures {
    fun json(name: String): JSONObject =
        JSONObject(checkNotNull(javaClass.classLoader!!.getResource(name)) { "missing fixture $name" }.readText())

    val captures: JSONObject by lazy { json("og901_captures.json") }

    fun hex(key: String): ByteArray = captures.getString(key).hexToBytes()

    /** The owner's key bundle (.secrets/, never committed); tests that need it are skipped without it. */
    fun keyBundleOrSkip(): KeyBundle {
        val file = System.getProperty("keyBundle")?.let(::File)
        assumeTrue("no key bundle at $file", file != null && file.isFile)
        return KeyBundle.parse(file!!.readText())
    }

    /** Encrypted captures of the owner's grill and its real DSN, kept beside the key bundle. */
    fun privateOrSkip(): JSONObject {
        val file = System.getProperty("keyBundle")?.let { File(File(it).parentFile, "captures_private.json") }
        assumeTrue("no private captures at $file", file != null && file.isFile)
        return JSONObject(file!!.readText())
    }
}
