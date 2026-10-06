package io.github.opencircuit.ble

import java.time.Duration

/**
 * How long the link waits for each GATT operation's callback before it fails the operation and
 * closes the connection (PORTING.md D-185). Upstream has no operation timeouts: CoreBluetooth
 * queues its writes and a connect there never expires.
 *
 * An ATT request left unanswered for 30 s makes the connection unusable for any later request,
 * so every value for a single request sits well below that; the connect and bond values sit just
 * above the stack's own 30 s timeouts, as backstops.
 */
internal object LinkTimeouts {
    /** A direct connection: a backstop over Android's own 30 s direct-connect timeout. */
    val DIRECT_CONNECT: Duration = Duration.ofSeconds(35)

    /** A standing (`autoConnect`) connection waits for the ring to come in range: no timeout. */
    val STANDING_CONNECT: Duration? = null

    /** Service discovery. */
    val DISCOVER: Duration = Duration.ofSeconds(10)

    /** Making the bond: the 30 s pairing timeout plus time for the user to answer the prompt. */
    val BOND: Duration = Duration.ofSeconds(40)

    /** The ATT MTU exchange. */
    val MTU: Duration = Duration.ofSeconds(5)

    /** A descriptor write (the notification switch). */
    val DESCRIPTOR_WRITE: Duration = Duration.ofSeconds(5)

    /** A characteristic read (Device Information). */
    val READ: Duration = Duration.ofSeconds(5)

    /** A characteristic write with response. */
    val WRITE: Duration = Duration.ofSeconds(5)
}
