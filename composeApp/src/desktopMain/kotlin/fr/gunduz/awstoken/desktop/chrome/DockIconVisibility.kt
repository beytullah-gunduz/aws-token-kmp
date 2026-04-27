package fr.gunduz.awstoken.desktop.chrome

import co.touchlab.kermit.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Toggles the macOS Dock icon for the running JVM at runtime.
 *
 * There is no public JVM API for `NSApplication.setActivationPolicy(_:)`, so
 * we call into the Objective-C runtime (`libobjc`) via JNA and build the
 * message send by hand:
 *
 *     [[NSApplication sharedApplication] setActivationPolicy:policy]
 *
 * where `policy` is `NSApplicationActivationPolicyRegular` (0, Dock icon
 * visible, conventional app) or `NSApplicationActivationPolicyAccessory` (1,
 * menu-bar-only, no Dock icon).
 *
 * Toggling at runtime is officially supported by AppKit — apps like VSCode
 * use the same pattern — but there are a couple of known edge cases:
 *
 *   - Switching Regular → Accessory hides the app's main menu bar (the
 *     system-wide menu at the top of the screen). This app doesn't use the
 *     main menu bar anyway so it's a no-op visually.
 *   - Switching Accessory → Regular does NOT automatically bring the window
 *     to front. The caller is responsible for unhiding / raising the window.
 *
 * No-op on non-macOS platforms.
 */
object DockIconVisibility {
    private val isMacOs: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    /**
     * JNA binding to `libobjc`'s low-level Objective-C runtime functions.
     * Only the three calls we need are declared; variadic `objc_msgSend` is
     * surfaced as two overloads because JNA doesn't handle C varargs well.
     */
    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, arg1: Long): Pointer?
    }

    private val objc: ObjC? by lazy {
        if (!isMacOs) return@lazy null
        runCatching { Native.load("objc", ObjC::class.java) }
            .onFailure { Logger.w(it, tag = TAG) { "failed to load libobjc" } }
            .getOrNull()
    }

    /**
     * `true` → hide from Dock (accessory app), `false` → show in Dock
     * (regular app). Safe to call repeatedly with the same value.
     */
    fun setHidden(hidden: Boolean) {
        val obj = objc ?: return
        try {
            val nsAppClass = obj.objc_getClass("NSApplication") ?: return
            val sharedAppSel = obj.sel_registerName("sharedApplication") ?: return
            val sharedApp = obj.objc_msgSend(nsAppClass, sharedAppSel) ?: return
            val setPolicySel = obj.sel_registerName("setActivationPolicy:") ?: return
            val policy = if (hidden) NS_APPLICATION_ACTIVATION_POLICY_ACCESSORY else NS_APPLICATION_ACTIVATION_POLICY_REGULAR
            obj.objc_msgSend(sharedApp, setPolicySel, policy)
            Logger.i(tag = TAG) { "setActivationPolicy: ${if (hidden) "Accessory" else "Regular"}" }
        } catch (t: Throwable) {
            Logger.w(t, tag = TAG) { "failed to toggle Dock visibility" }
        }
    }

    private const val TAG = "DockIconVisibility"
    private const val NS_APPLICATION_ACTIVATION_POLICY_REGULAR: Long = 0
    private const val NS_APPLICATION_ACTIVATION_POLICY_ACCESSORY: Long = 1
}
