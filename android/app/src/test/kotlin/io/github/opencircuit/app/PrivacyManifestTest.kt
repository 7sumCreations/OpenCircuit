package io.github.opencircuit.app

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.w3c.dom.Element

/**
 * Nothing the app stores may leave the phone through Android's own backup: not to the cloud, and
 * not to a new phone by device-to-device transfer.
 *
 * `allowBackup="false"` alone is not enough from Android 12 (targetSdk 31+): it no longer stops
 * device-to-device transfer. That needs a `dataExtractionRules` file that excludes every storage
 * domain under both `<cloud-backup>` and `<device-transfer>`. A section with no `<include>` backs
 * up everything by default, so each domain is excluded with `path="."` (the whole domain).
 *
 * Read from the source tree: host tests run with the module directory as working directory.
 */
class PrivacyManifestTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    /** Every domain the data-extraction-rules schema knows, credential- and device-protected. */
    private val allDomains = setOf(
        "root", "file", "database", "sharedpref", "external",
        "device_root", "device_file", "device_database", "device_sharedpref",
    )

    private fun parse(file: File): Element {
        assertTrue(file.isFile, "missing ${file.path}")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun application(): Element {
        val manifest = parse(File("src/main/AndroidManifest.xml"))
        val apps = manifest.getElementsByTagName("application")
        assertEquals(1, apps.length)
        return apps.item(0) as Element
    }

    @Test
    fun `cloud backup is off`() {
        assertEquals("false", application().getAttributeNS(androidNs, "allowBackup"))
    }

    @Test
    fun `cloud backup and device transfer both exclude every domain`() {
        val ref = application().getAttributeNS(androidNs, "dataExtractionRules")
        assertTrue(ref.startsWith("@xml/"), "dataExtractionRules must point at an @xml resource, was '$ref'")
        val rules = parse(File("src/main/res/xml/${ref.removePrefix("@xml/")}.xml"))
        assertEquals("data-extraction-rules", rules.tagName)

        for (section in listOf("cloud-backup", "device-transfer")) {
            val nodes = rules.getElementsByTagName(section)
            assertEquals(1, nodes.length, "exactly one <$section>")
            val el = nodes.item(0) as Element
            assertEquals(0, el.getElementsByTagName("include").length, "<$section> must include nothing")
            val excludes = el.getElementsByTagName("exclude")
            val wholeDomains = (0 until excludes.length)
                .map { excludes.item(it) as Element }
                .filter { it.getAttribute("path") == "." }
                .map { it.getAttribute("domain") }
                .toSet()
            assertEquals(allDomains, wholeDomains, "<$section> must exclude every domain whole")
        }
    }
}
