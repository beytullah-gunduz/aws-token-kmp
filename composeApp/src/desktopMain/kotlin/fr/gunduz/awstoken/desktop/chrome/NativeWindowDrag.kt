package fr.gunduz.awstoken.desktop.chrome

import co.touchlab.kermit.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * Initiates a native macOS window drag by forwarding the current NSEvent to
 * `-[NSWindow performWindowDragWithEvent:]`.
 *
 * Why this exists: with `undecorated = true` the window has no OS title
 * bar, so drags go through Compose Desktop's `WindowDraggableArea`, which
 * handles them entirely in Kotlin — it reads AWT mouse deltas and calls
 * `frame.setLocation(x, y)`. On macOS multi-monitor setups, AWT's screen
 * coordinate mapping disagrees with AppKit's unified coordinate space
 * whenever the cursor crosses into a secondary display (different scale
 * factors, negative origins for monitors to the left of the primary,
 * etc.), so the window gets clamped at the primary display's edge instead
 * of following the cursor across. Delegating to `performWindowDragWithEvent:`
 * hands the drag to AppKit's window server — the same path every normal
 * macOS app takes — and multi-monitor just works.
 *
 * The trick is `-[NSApplication currentEvent]`, which returns the NSEvent
 * currently being processed by AppKit. When Compose/Skiko hands a
 * mouse-press to our pointer-input handler on the EDT, we're still inside
 * AppKit's event dispatch for the originating NSEvent, so `currentEvent`
 * still points at it. We grab it and pass it to the drag API.
 *
 * No-op on non-macOS platforms (returns `false` so the caller can fall
 * back to `WindowDraggableArea`).
 */
object NativeWindowDrag {
    private val isMacOs: Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    /**
     * JNA binding to `libobjc`'s low-level Objective-C runtime. Two
     * `objc_msgSend` overloads are declared — one for zero-arg selectors
     * (`sharedApplication`, `currentEvent`, `keyWindow`) and one for
     * the single-pointer-argument `performWindowDragWithEvent:` call.
     */
    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, arg1: Pointer): Pointer?
    }

    private val objc: ObjC? by lazy {
        if (!isMacOs) return@lazy null
        runCatching { Native.load("objc", ObjC::class.java) }
            .onFailure { Logger.w(it, tag = TAG) { "failed to load libobjc" } }
            .getOrNull()
    }

    /**
     * Cached `NSApplication.sharedApplication` and selector pointers.
     * Selectors are interned by the Objective-C runtime and never unload,
     * and `sharedApplication` is a singleton, so caching is safe.
     */
    private data class Bindings(
        val sharedApp: Pointer,
        val currentEventSel: Pointer,
        val keyWindowSel: Pointer,
        val performDragSel: Pointer,
    )

    private val bindings: Bindings? by lazy {
        val obj = objc ?: return@lazy null
        try {
            val nsAppClass = obj.objc_getClass("NSApplication") ?: return@lazy null
            val sharedAppSel = obj.sel_registerName("sharedApplication") ?: return@lazy null
            val sharedApp = obj.objc_msgSend(nsAppClass, sharedAppSel) ?: return@lazy null
            val currentEventSel = obj.sel_registerName("currentEvent") ?: return@lazy null
            val keyWindowSel = obj.sel_registerName("keyWindow") ?: return@lazy null
            val performDragSel = obj.sel_registerName("performWindowDragWithEvent:") ?: return@lazy null
            Bindings(
                sharedApp = sharedApp,
                currentEventSel = currentEventSel,
                keyWindowSel = keyWindowSel,
                performDragSel = performDragSel,
            )
        } catch (t: Throwable) {
            Logger.w(t, tag = TAG) { "failed to resolve AppKit bindings" }
            null
        }
    }

    /**
     * Try to start a native window drag for the currently focused NSWindow.
     * Must be called synchronously from Compose pointer-input handling so
     * `[NSApp currentEvent]` still points at the press event the user just
     * generated.
     *
     * @return `true` if the drag was delegated to AppKit (the caller
     *   should consume the Compose pointer change so Compose doesn't
     *   double-handle the event), `false` if JNA/ObjC resolution failed
     *   or any selector returned null — the caller is expected to fall
     *   back to `WindowDraggableArea`.
     */
    fun startDrag(): Boolean {
        val obj = objc ?: return false
        val b = bindings ?: return false
        return try {
            val currentEvent = obj.objc_msgSend(b.sharedApp, b.currentEventSel) ?: return false
            val keyWindow = obj.objc_msgSend(b.sharedApp, b.keyWindowSel) ?: return false
            obj.objc_msgSend(keyWindow, b.performDragSel, currentEvent)
            true
        } catch (t: Throwable) {
            Logger.w(t, tag = TAG) { "performWindowDragWithEvent: threw" }
            false
        }
    }

    private const val TAG = "NativeWindowDrag"
}
