package android.database

/**
 * The Android framework's `android.database.SQLException`, exactly as AOSP declares it (three
 * constructors, nothing else), for the app's JVM tests only.
 *
 * On the phone the store's SQLite driver throws this class with SQLite's message, and Room's
 * upsert reads that message (a unique-constraint failure means "the row exists, update it").
 * On the JVM the class otherwise comes from the stubbed `android.jar`, whose constructor drops the
 * message — so every upsert of an existing key fails there, though never on the phone. With this
 * copy first on the test classpath the JVM store behaves as the device's. Never in the APK.
 */
open class SQLException : RuntimeException {
    constructor() : super()
    constructor(error: String?) : super(error)
    constructor(error: String?, cause: Throwable?) : super(error, cause)
}
