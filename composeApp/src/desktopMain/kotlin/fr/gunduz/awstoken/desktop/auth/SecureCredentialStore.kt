package fr.gunduz.awstoken.desktop.auth

import co.touchlab.kermit.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets

/**
 * Thin wrapper over the macOS Keychain. Other platforms return `null` /
 * no-ops so the rest of the code can call these methods unconditionally.
 *
 * ## Why not `/usr/bin/security`?
 *
 * The previous implementation shelled out to `/usr/bin/security`. That
 * works, but a keychain item carries an ACL listing the executables that
 * are allowed to read it without prompting — and when the writer is
 * `/usr/bin/security`, the trusted client is the system CLI, not the app.
 * That made the password readable by **anyone** with shell access:
 *
 *     security find-generic-password -s aws-token-kmp -a "<user>@<host>" -w
 *
 * …would silently print the password, no prompt.
 *
 * This implementation calls `SecItemAdd` / `SecItemCopyMatching` /
 * `SecItemUpdate` / `SecItemDelete` directly via JNA. The keychain item's
 * ACL is then bound to the AwsToken bundle's code identity, so a stray
 * `security find-generic-password` from a terminal triggers an
 * "AwsToken wants to use your confidential information…" prompt instead
 * of silently handing over the secret.
 *
 * ## Caveat: code signing
 *
 * macOS identifies the trusted client by code signature. An **unsigned**
 * release DMG has no stable signing identity, so the OS may re-prompt
 * after a fresh install / rebuild. Once the DMG is signed with a Developer
 * ID certificate, the user clicks "Always Allow" once and it stays silent
 * for that signed build family.
 *
 * ## Migration from the old `/usr/bin/security` data
 *
 * Existing users will have keychain entries written by the old code, with
 * `/usr/bin/security` as the trusted client. The first read after the
 * upgrade triggers a normal macOS keychain prompt — clicking "Always Allow"
 * adds AwsToken to the ACL. Subsequent reads are silent.
 *
 * Note that the legacy entry still has `/usr/bin/security` on its ACL until
 * the password is re-saved (e.g. on the next password change). To force a
 * clean ACL immediately, delete the "aws-token-kmp" item from Keychain
 * Access and re-enter the password.
 *
 * TODO (see README):
 *   - Windows: wrap `wincred.h`/CredRead+CredWrite via a tiny JNA call.
 *   - Linux: libsecret / Secret Service via D-Bus.
 */
object SecureCredentialStore {
    private const val SERVICE = "aws-token-kmp"
    private const val TAG = "SecureCredentialStore"

    val isSupported: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    suspend fun store(
        adfsHost: String,
        username: String,
        password: String,
    ): Boolean {
        if (!isSupported) {
            Logger.w(tag = TAG) { "store() called on non-macOS — no-op" }
            return false
        }
        val account = accountKey(adfsHost, username)
        Logger.i(tag = TAG) { "store: SecItemAdd service=$SERVICE account=$account" }
        return withContext(Dispatchers.IO) {
            runCatching {
                val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
                val addStatus = secItemAdd(account, passwordBytes)
                when (addStatus) {
                    Sec.errSecSuccess -> {
                        Logger.i(tag = TAG) { "keychain store OK (added)" }
                        true
                    }
                    Sec.errSecDuplicateItem -> {
                        // Item already exists (either from an earlier run of
                        // this code, or from the legacy `/usr/bin/security`
                        // implementation). Delete + re-add rather than
                        // SecItemUpdate, because Update preserves the
                        // entry's ACL — and a legacy entry's ACL still
                        // trusts /usr/bin/security. Replacing it ensures
                        // every saved password ends up with a fresh ACL
                        // bound only to the AwsToken bundle.
                        val deleteStatus = secItemDelete(account)
                        if (deleteStatus != Sec.errSecSuccess && deleteStatus != Sec.errSecItemNotFound) {
                            Logger.w(tag = TAG) { "delete-before-replace failed (OSStatus=$deleteStatus)" }
                            return@runCatching false
                        }
                        val readdStatus = secItemAdd(account, passwordBytes)
                        if (readdStatus == Sec.errSecSuccess) {
                            Logger.i(tag = TAG) { "keychain store OK (replaced)" }
                            true
                        } else {
                            Logger.w(tag = TAG) { "re-add after delete failed (OSStatus=$readdStatus)" }
                            false
                        }
                    }
                    else -> {
                        Logger.w(tag = TAG) { "SecItemAdd failed (OSStatus=$addStatus)" }
                        false
                    }
                }
            }.getOrElse { t ->
                Logger.w(t, tag = TAG) { "keychain store threw" }
                false
            }
        }
    }

    suspend fun retrieve(
        adfsHost: String,
        username: String,
    ): String? {
        if (!isSupported) {
            Logger.i(tag = TAG) { "retrieve() on non-macOS — returning null" }
            return null
        }
        val account = accountKey(adfsHost, username)
        Logger.i(tag = TAG) { "retrieve: SecItemCopyMatching service=$SERVICE account=$account" }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (status, bytes) = secItemCopyMatching(account)
                when (status) {
                    Sec.errSecSuccess -> {
                        if (bytes == null || bytes.isEmpty()) {
                            null
                        } else {
                            String(bytes, StandardCharsets.UTF_8)
                        }
                    }
                    Sec.errSecItemNotFound -> {
                        Logger.i(tag = TAG) { "keychain retrieve: not found" }
                        null
                    }
                    Sec.errSecUserCanceled, Sec.errSecAuthFailed -> {
                        Logger.i(tag = TAG) { "keychain retrieve: user denied (OSStatus=$status)" }
                        null
                    }
                    else -> {
                        Logger.w(tag = TAG) { "SecItemCopyMatching failed (OSStatus=$status)" }
                        null
                    }
                }
            }.getOrElse { t ->
                Logger.w(t, tag = TAG) { "keychain retrieve threw" }
                null
            }
        }
    }

    suspend fun delete(
        adfsHost: String,
        username: String,
    ): Boolean {
        if (!isSupported) return false
        val account = accountKey(adfsHost, username)
        return withContext(Dispatchers.IO) {
            runCatching {
                val status = secItemDelete(account)
                // Treat "not found" as success — the caller's intent is
                // "ensure no credential remains for this identity," and
                // not-found satisfies that.
                status == Sec.errSecSuccess || status == Sec.errSecItemNotFound
            }.getOrElse { t ->
                Logger.w(t, tag = TAG) { "keychain delete threw" }
                false
            }
        }
    }

    private fun accountKey(
        adfsHost: String,
        username: String,
    ) = "$username@$adfsHost"

    // ---------------------------------------------------------------------
    // Native plumbing
    // ---------------------------------------------------------------------
    //
    // Below the line is the JNA bridge to Security.framework + Core
    // Foundation. We build a CFDictionary describing the keychain item we
    // want, hand it to one of the four `SecItem*` functions, and (for
    // reads) decode the CFData reply back into a byte array.
    //
    // CF reference counting is manual: anything we Create/Copy must be
    // released. The `cfRetainScope` helper makes that easier — every
    // pointer we register inside it is released when the lambda exits,
    // even on failure.

    private fun secItemAdd(
        account: String,
        passwordBytes: ByteArray,
    ): Int = cfScope {
        val service = trackString(SERVICE)
        val acct = trackString(account)
        val data = trackData(passwordBytes)
        val query =
            trackDict(
                Sec.kSecClass to Sec.kSecClassGenericPassword,
                Sec.kSecAttrService to service,
                Sec.kSecAttrAccount to acct,
                Sec.kSecValueData to data,
            )
        Sec.INSTANCE.SecItemAdd(query, null)
    }

    private fun secItemCopyMatching(account: String): Pair<Int, ByteArray?> = cfScope {
        val service = trackString(SERVICE)
        val acct = trackString(account)
        val query =
            trackDict(
                Sec.kSecClass to Sec.kSecClassGenericPassword,
                Sec.kSecAttrService to service,
                Sec.kSecAttrAccount to acct,
                Sec.kSecMatchLimit to Sec.kSecMatchLimitOne,
                Sec.kSecReturnData to CF.kCFBooleanTrue,
            )
        val out = PointerByReference()
        val status = Sec.INSTANCE.SecItemCopyMatching(query, out)
        val resultPtr = out.value
        val bytes =
            if (status == Sec.errSecSuccess && resultPtr != null) {
                // SecItemCopyMatching returns a +1 retained CFDataRef
                // when kSecReturnData is requested. Track it so it gets
                // released after we copy the bytes out.
                track(resultPtr)
                val length = CF.INSTANCE.CFDataGetLength(resultPtr).toInt()
                if (length <= 0) {
                    ByteArray(0)
                } else {
                    val ptr = CF.INSTANCE.CFDataGetBytePtr(resultPtr)
                    ptr.getByteArray(0, length)
                }
            } else {
                null
            }
        status to bytes
    }

    private fun secItemDelete(account: String): Int = cfScope {
        val service = trackString(SERVICE)
        val acct = trackString(account)
        val query =
            trackDict(
                Sec.kSecClass to Sec.kSecClassGenericPassword,
                Sec.kSecAttrService to service,
                Sec.kSecAttrAccount to acct,
            )
        Sec.INSTANCE.SecItemDelete(query)
    }

    /**
     * Run `block` with a fresh CF reference-counting scope. Every pointer
     * registered via the `track*` helpers is `CFRelease`d when the scope
     * exits, even if `block` throws.
     */
    private inline fun <T> cfScope(block: CFScope.() -> T): T {
        val scope = CFScope()
        try {
            return scope.block()
        } finally {
            scope.releaseAll()
        }
    }

    private class CFScope {
        private val owned = ArrayList<Pointer>()

        fun track(p: Pointer): Pointer {
            owned.add(p)
            return p
        }

        fun trackString(s: String): Pointer {
            val bytes = s.toByteArray(StandardCharsets.UTF_8)
            val ref =
                CF.INSTANCE.CFStringCreateWithBytes(
                    null,
                    bytes,
                    bytes.size.toLong(),
                    CF.kCFStringEncodingUTF8,
                    0,
                ) ?: error("CFStringCreateWithBytes returned null")
            return track(ref)
        }

        fun trackData(bytes: ByteArray): Pointer {
            val ref =
                CF.INSTANCE.CFDataCreate(null, bytes, bytes.size.toLong())
                    ?: error("CFDataCreate returned null")
            return track(ref)
        }

        fun trackDict(vararg pairs: Pair<Pointer, Pointer>): Pointer {
            val dict =
                CF.INSTANCE.CFDictionaryCreateMutable(
                    null,
                    pairs.size.toLong(),
                    CF.kCFTypeDictionaryKeyCallBacks,
                    CF.kCFTypeDictionaryValueCallBacks,
                ) ?: error("CFDictionaryCreateMutable returned null")
            track(dict)
            for ((k, v) in pairs) {
                CF.INSTANCE.CFDictionaryAddValue(dict, k, v)
            }
            return dict
        }

        fun releaseAll() {
            // Release in reverse order so dictionaries (which retained
            // their keys/values) drop their refs before the keys/values
            // themselves are released.
            for (i in owned.indices.reversed()) {
                runCatching { CF.INSTANCE.CFRelease(owned[i]) }
            }
            owned.clear()
        }
    }

    // ---------------------------------------------------------------------
    // CoreFoundation bindings
    // ---------------------------------------------------------------------

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface CF : Library {
        fun CFRelease(cf: Pointer)

        fun CFStringCreateWithBytes(
            allocator: Pointer?,
            bytes: ByteArray,
            length: Long,
            encoding: Int,
            isExternalRepresentation: Byte,
        ): Pointer?

        fun CFDataCreate(
            allocator: Pointer?,
            bytes: ByteArray,
            length: Long,
        ): Pointer?

        fun CFDataGetLength(theData: Pointer): Long

        fun CFDataGetBytePtr(theData: Pointer): Pointer

        fun CFDictionaryCreateMutable(
            allocator: Pointer?,
            capacity: Long,
            keyCallBacks: Pointer?,
            valueCallBacks: Pointer?,
        ): Pointer?

        fun CFDictionaryAddValue(
            theDict: Pointer,
            key: Pointer,
            value: Pointer,
        )

        companion object {
            const val kCFStringEncodingUTF8: Int = 0x08000100

            val INSTANCE: CF = Native.load("CoreFoundation", CF::class.java)

            private val cfLib: NativeLibrary =
                NativeLibrary.getInstance("CoreFoundation")

            // Callback structs for typed CF dictionaries — these tell CF
            // to retain/release/equal/hash CFTypeRef keys & values.
            val kCFTypeDictionaryKeyCallBacks: Pointer =
                cfLib.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks")

            val kCFTypeDictionaryValueCallBacks: Pointer =
                cfLib.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks")

            // Boolean singletons — these are CFTypeRef constants, so we
            // dereference once to get the actual CFBoolean pointer.
            val kCFBooleanTrue: Pointer =
                cfLib.getGlobalVariableAddress("kCFBooleanTrue").getPointer(0)
        }
    }

    // ---------------------------------------------------------------------
    // Security.framework bindings
    // ---------------------------------------------------------------------

    @Suppress("FunctionName", "ktlint:standard:function-naming")
    private interface Sec : Library {
        fun SecItemAdd(
            attributes: Pointer,
            result: PointerByReference?,
        ): Int

        fun SecItemCopyMatching(
            query: Pointer,
            result: PointerByReference,
        ): Int

        fun SecItemDelete(query: Pointer): Int

        companion object {
            val INSTANCE: Sec = Native.load("Security", Sec::class.java)

            // OSStatus codes we care about. See SecBase.h.
            const val errSecSuccess: Int = 0
            const val errSecAuthFailed: Int = -25293
            const val errSecDuplicateItem: Int = -25299
            const val errSecItemNotFound: Int = -25300
            const val errSecUserCanceled: Int = -128

            private val secLib: NativeLibrary =
                NativeLibrary.getInstance("Security")

            // The keychain query language is built from CFStringRef
            // constants exported by Security.framework. Each symbol points
            // at a CFStringRef, so we deref once.
            val kSecClass: Pointer = global("kSecClass")
            val kSecClassGenericPassword: Pointer = global("kSecClassGenericPassword")
            val kSecAttrService: Pointer = global("kSecAttrService")
            val kSecAttrAccount: Pointer = global("kSecAttrAccount")
            val kSecValueData: Pointer = global("kSecValueData")
            val kSecMatchLimit: Pointer = global("kSecMatchLimit")
            val kSecMatchLimitOne: Pointer = global("kSecMatchLimitOne")
            val kSecReturnData: Pointer = global("kSecReturnData")

            private fun global(name: String): Pointer = secLib.getGlobalVariableAddress(name).getPointer(0)
        }
    }
}
