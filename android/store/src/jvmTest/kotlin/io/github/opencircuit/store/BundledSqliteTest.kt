package io.github.opencircuit.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The store's JVM tests stand in for the phone only because both run the SQLite build bundled
 * with `androidx.sqlite:sqlite-bundled`, not whatever SQLite the OS ships. This pins that build:
 * a dependency bump that changes it fails here, and the on-device smoke test is re-run.
 */
class BundledSqliteTest {

    @Test
    fun theJvmTestsRunTheBundledSqliteBuild() {
        val connection = BundledSQLiteDriver().open(":memory:")
        try {
            val statement = connection.prepare("SELECT sqlite_version()")
            try {
                statement.step()
                assertEquals("3.50.1", statement.getText(0))
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }
    }

    /**
     * The list the on-device smoke test compares the phone's bundled SQLite against
     * (`src/androidDeviceTest/resources/sqlite-compile-options.txt`) is this JVM's list, so a pass
     * there means the device runs the same build with the same options.
     */
    @Test
    fun theJvmCompileOptionsAreTheListTheDeviceTestExpects() {
        val storeDir = assertNotNull(System.getProperty("opencircuit.storeDir"), "opencircuit.storeDir is not set — see store/build.gradle.kts")
        val expected = File(storeDir, "src/androidDeviceTest/resources/sqlite-compile-options.txt").readLines().filter { it.isNotBlank() }

        val connection = BundledSQLiteDriver().open(":memory:")
        val actual = mutableListOf<String>()
        try {
            val statement = connection.prepare("PRAGMA compile_options")
            try {
                while (statement.step()) actual += statement.getText(0)
            } finally {
                statement.close()
            }
        } finally {
            connection.close()
        }

        assertEquals(expected, actual)
    }
}
