package io.github.opencircuit.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
