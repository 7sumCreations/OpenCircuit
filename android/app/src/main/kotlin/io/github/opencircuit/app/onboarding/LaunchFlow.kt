package io.github.opencircuit.app.onboarding

import io.github.opencircuit.app.data.AppPrefs

/** The app's screens. No navigation library: onboarding, then three tabs, one `when`. */
enum class Destination { Onboarding, Today, Ring, Settings }

/**
 * The tabs after onboarding and what back does between them. Back from Ring or Settings returns to
 * Today; back on Today (or in onboarding) is Android's own, which leaves the app.
 */
object AppNavigation {

    /** The bottom bar's tabs, in order. */
    val tabs: List<Destination> = listOf(Destination.Today, Destination.Ring, Destination.Settings)

    /** A tab's label, always shown under its icon. */
    fun label(tab: Destination): String = when (tab) {
        Destination.Today -> "Today"
        Destination.Ring -> "Ring"
        Destination.Settings -> "Settings"
        Destination.Onboarding -> "Welcome"
    }

    /** Where back goes from [from], or null when Android's own back applies. */
    fun back(from: Destination): Destination? = when (from) {
        Destination.Ring, Destination.Settings -> Destination.Today
        Destination.Today, Destination.Onboarding -> null
    }

    /** The ring session (reconnect, sync on link-up) runs on every screen after onboarding, whatever tab is open. */
    fun runsSession(on: Destination): Boolean = on != Destination.Onboarding
}

/**
 * Which screen the app opens on, and what finishing onboarding does
 * (`ios/OpenCircuit/OnboardingView.swift:12-40` @ b1c2fdd: shown once, by a saved flag).
 *
 * [start] reads the flag synchronously when the flow is built, so the first frame already shows
 * the right screen. Finishing or skipping saves the flag and opens Today; if the write fails the
 * user still gets in, the failure is logged, and onboarding shows again next launch. Onboarding
 * asks for nothing: no permission is requested from here.
 */
class LaunchFlow(private val prefs: AppPrefs, private val log: (String) -> Unit) {

    /** The first screen. */
    val start: Destination = if (prefs.onboardingCompleted) Destination.Today else Destination.Onboarding

    /** Get started or Skip: remembers it and returns the next screen. */
    fun finishOnboarding(): Destination {
        if (!prefs.setOnboardingCompleted()) log("Could not save that onboarding was done; it will show again next launch")
        return Destination.Today
    }
}

/** One onboarding page: its title and its paragraphs. */
data class OnboardingPage(val title: String, val paragraphs: List<String>)

/**
 * The four pages: upstream's (OB:58-102) with the Android words (Health Connect, Nearby devices,
 * force-stop, every supported model) and the disclaimer kept in substance.
 */
object OnboardingPages {
    val all: List<OnboardingPage> = listOf(
        OnboardingPage(
            "Welcome to OpenCircuit",
            listOf(
                "OpenCircuit reads your RingConn ring (Gen 2, Gen 2 Air, Gen 3) over Bluetooth — heart rate, HRV, SpO₂, sleep, skin temperature and more.",
                "It's local-first: every reading stays on your phone. Nothing is sent to any server, and nothing goes to Health Connect unless you turn that on in Settings.",
                "No account, no subscription, no cloud.",
            ),
        ),
        OnboardingPage(
            "Getting started",
            listOf(
                "No RingConn account and no official app needed — OpenCircuit connects to your ring on its own, even a brand-new ring.",
                "If the official RingConn app is installed, force-stop it before using OpenCircuit — only one app can talk to the ring at a time.",
                "Keep your phone nearby, especially overnight.",
                "Charge the ring as usual; OpenCircuit picks up where it left off.",
            ),
        ),
        OnboardingPage(
            "Permissions",
            listOf(
                "Nearby devices (Bluetooth) — to find and connect to your ring. OpenCircuit never uses it for location.",
                "Health Connect — only if you want a copy of your metrics there: turn on Send to Health Connect in Settings, then tap Connect Health Connect. You choose exactly what to share. It stays off until you do.",
                "You'll be asked the first time you tap Scan & connect. Android may also ask you to confirm pairing with the ring.",
            ),
        ),
        OnboardingPage(
            "Good to know",
            listOf(
                "OpenCircuit is an independent, local-first app compatible with RingConn smart rings. It is not affiliated with, " +
                    "authorized, or endorsed by RingConn or JZ_Tech; “RingConn” is a trademark of its respective owner.",
                "OpenCircuit is not a medical device. Its readings are estimates for personal insight, not diagnosis. " +
                    "Talk to a clinician about any health concern.",
            ),
        ),
    )

    /** Skip is on every page but the last, where Get started does the same (OB:46-50). */
    fun showsSkip(index: Int): Boolean = index < all.lastIndex

    /** "Continue", or "Get started" on the last page. */
    fun primaryLabel(index: Int): String = if (index < all.lastIndex) "Continue" else "Get started"
}
