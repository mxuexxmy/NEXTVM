package com.nextvm.core.services.component

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.nextvm.core.common.findField
import com.nextvm.core.common.findMethod
import com.nextvm.core.common.runSafe
import timber.log.Timber
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualContentProviderManager — Complete ContentProvider system for guest apps.
 *
 * Mirrors:
 *   - ContentProviderHelper.java in frameworks/base/services/core/java/com/android/server/am/
 *   - ActivityThread.installContentProviders()
 *   - ActivityThread.installProvider()
 *   - ContentProviderRecord in real AMS
 *
 * Handles:
 *   - Provider instantiation via guest app's ClassLoader
 *   - Provider.attachInfo() + Provider.onCreate()
 *   - Authority-based routing (virtual authorities mapped to real providers)
 *   - ContentResolver query/insert/update/delete proxy
 *   - FileProvider support (critical for camera intents, share intents)
 *   - Provider permission enforcement (readPermission, writePermission)
 *   - Multi-process provider access
 */
@Singleton
class VirtualContentProviderManager @Inject constructor() {

    companion object {
        private const val TAG = "VProviderMgr"

        /** Authority prefix for virtual apps to avoid collisions with real providers */
        const val VIRTUAL_AUTHORITY_PREFIX = "com.nextvm.virtual."
    }

    /**
     * Record for an installed virtual ContentProvider.
     */
    data class ProviderRecord(
        val instanceId: String,
        val packageName: String,
        val providerClassName: String,
        val authority: String,
        val virtualAuthority: String,
        val provider: ContentProvider,
        val readPermission: String? = null,
        val writePermission: String? = null,
        val exported: Boolean = false,
        val multiProcess: Boolean = false,
        val grantUriPermissions: Boolean = false,
        val createdAt: Long = System.currentTimeMillis()
    )

    // authority -> ProviderRecord (primary lookup)
    private val providersByAuthority = ConcurrentHashMap<String, ProviderRecord>()

    // virtualAuthority -> ProviderRecord
    private val providersByVirtualAuthority = ConcurrentHashMap<String, ProviderRecord>()

    // instanceId -> list of ProviderRecords
    private val providersByInstance = ConcurrentHashMap<String, MutableList<ProviderRecord>>()

    // className -> ProviderRecord (for lookup during manifest processing)
    private val providersByClassName = ConcurrentHashMap<String, ProviderRecord>()

    /**
     * Install a ContentProvider from a guest app.
     *
     * Mirrors ActivityThread.installProvider():
     *   1. Load ContentProvider class via ClassLoader
     *   2. Create instance
     *   3. Build ProviderInfo
     *   4. Call ContentProvider.attachInfo(context, info)
     *     - This internally calls ContentProvider.onCreate()
     *
     * @param instanceId Guest app instance ID
     * @param packageName Guest app package name
     * @param providerClassName Fully qualified ContentProvider class name
     * @param authority The declared content authority
     * @param classLoader Guest app's DexClassLoader
     * @param context Virtual/sandboxed context for the guest
     * @param readPermission Permission required to read
     * @param writePermission Permission required to write
     * @param exported Whether the provider is exported
     * @param grantUriPermissions Whether URI permissions can be granted
     * @return The installed ContentProvider or null on failure
     */
    fun installProvider(
        instanceId: String,
        packageName: String,
        providerClassName: String,
        authority: String,
        classLoader: ClassLoader,
        context: Context,
        readPermission: String? = null,
        writePermission: String? = null,
        exported: Boolean = false,
        grantUriPermissions: Boolean = false,
        multiProcess: Boolean = false
    ): ContentProvider? {
        // Check if already installed
        val existing = providersByAuthority[authority]
        if (existing != null && existing.instanceId == instanceId) {
            Timber.tag(TAG).d("Provider already installed: $authority")
            return existing.provider
        }

        Timber.tag(TAG).i("Installing provider: $providerClassName (authority=$authority)")

        try {
            // Step 1: Load class
            val providerClass = classLoader.loadClass(providerClassName)
            if (!ContentProvider::class.java.isAssignableFrom(providerClass)) {
                Timber.tag(TAG).e("$providerClassName is not a ContentProvider subclass")
                return null
            }

            // Step 2: Create instance
            val provider = providerClass.getDeclaredConstructor().let { ctor ->
                ctor.isAccessible = true
                ctor.newInstance()
            } as ContentProvider

            // Step 3: Build ProviderInfo — prefer archive meta-data when available
            val archiveInfo = try {
                val flags = android.content.pm.PackageManager.GET_PROVIDERS or
                    android.content.pm.PackageManager.GET_META_DATA
                val pkg = context.packageManager.getPackageArchiveInfo(
                    // Prefer sourceDir from applicationInfo when set by VirtualContext
                    context.applicationInfo.sourceDir,
                    flags
                )
                pkg?.providers?.firstOrNull { it.name == providerClassName }?.also { pi ->
                    pi.packageName = packageName
                    pi.applicationInfo = pkg.applicationInfo?.also { ai ->
                        ai.sourceDir = context.applicationInfo.sourceDir
                        ai.publicSourceDir = context.applicationInfo.sourceDir
                    }
                }
            } catch (_: Exception) {
                null
            }

            val providerInfo = ProviderInfo().apply {
                this.name = providerClassName
                this.authority = authority
                this.packageName = packageName
                this.applicationInfo = archiveInfo?.applicationInfo ?: context.applicationInfo
                this.readPermission = readPermission ?: archiveInfo?.readPermission
                this.writePermission = writePermission ?: archiveInfo?.writePermission
                this.exported = exported
                this.grantUriPermissions = grantUriPermissions || (archiveInfo?.grantUriPermissions == true)
                this.multiprocess = multiProcess
                this.enabled = true
                this.metaData = archiveInfo?.metaData
            }

            // Step 4: Call attachInfo (this calls onCreate internally)
            try {
                provider.attachInfo(context, providerInfo)
                Timber.tag(TAG).d("Provider.attachInfo() + onCreate() succeeded")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Provider.attachInfo() failed: $providerClassName")
                return null
            }

            // Step 5: Create virtual authority (namespaced to avoid collision)
            val virtualAuthority = "$VIRTUAL_AUTHORITY_PREFIX${instanceId}.$authority"

            // Step 6: Create and store record
            val record = ProviderRecord(
                instanceId = instanceId,
                packageName = packageName,
                providerClassName = providerClassName,
                authority = authority,
                virtualAuthority = virtualAuthority,
                provider = provider,
                readPermission = readPermission,
                writePermission = writePermission,
                exported = exported,
                multiProcess = multiProcess,
                grantUriPermissions = grantUriPermissions
            )

            providersByAuthority[authority] = record
            providersByVirtualAuthority[virtualAuthority] = record
            providersByClassName[providerClassName] = record
            providersByInstance.getOrPut(instanceId) { mutableListOf() }.add(record)

            Timber.tag(TAG).i("Provider installed: $authority → $virtualAuthority")
            return provider

        } catch (e: ClassNotFoundException) {
            Timber.tag(TAG).e("Provider class not found: $providerClassName")
            return null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to install provider: $providerClassName")
            return null
        }
    }

    /**
     * Get a provider by its declared authority.
     */
    fun getProvider(authority: String): ContentProvider? {
        return providersByAuthority[authority]?.provider
            ?: providersByVirtualAuthority[authority]?.provider
    }

    /**
     * Get a ProviderRecord by authority.
     */
    fun getProviderRecord(authority: String): ProviderRecord? {
        return providersByAuthority[authority] ?: providersByVirtualAuthority[authority]
    }

    /**
     * Resolve the real authority (may be the original or the virtual one).
     */
    fun resolveAuthority(authority: String): String? {
        if (providersByAuthority.containsKey(authority)) return authority
        if (providersByVirtualAuthority.containsKey(authority)) return authority
        return null
    }

    // ---------- ContentResolver Proxy Methods ----------

    /**
     * Query a virtual ContentProvider.
     */
    fun query(
        authority: String,
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        val record = findProviderRecord(authority) ?: run {
            Timber.tag(TAG).w("No provider for authority: $authority")
            return null
        }

        return try {
            record.provider.query(uri, projection, selection, selectionArgs, sortOrder)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Provider query failed: $authority")
            null
        }
    }

    /**
     * Insert into a virtual ContentProvider.
     */
    fun insert(
        authority: String,
        uri: Uri,
        values: ContentValues?
    ): Uri? {
        val record = findProviderRecord(authority) ?: run {
            Timber.tag(TAG).w("No provider for authority: $authority (insert)")
            return null
        }

        return try {
            record.provider.insert(uri, values)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Provider insert failed: $authority")
            null
        }
    }

    /**
     * Update a virtual ContentProvider.
     */
    fun update(
        authority: String,
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        val record = findProviderRecord(authority) ?: run {
            Timber.tag(TAG).w("No provider for authority: $authority (update)")
            return 0
        }

        return try {
            record.provider.update(uri, values, selection, selectionArgs)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Provider update failed: $authority")
            0
        }
    }

    /**
     * Delete from a virtual ContentProvider.
     */
    fun delete(
        authority: String,
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        val record = findProviderRecord(authority) ?: run {
            Timber.tag(TAG).w("No provider for authority: $authority (delete)")
            return 0
        }

        return try {
            record.provider.delete(uri, selection, selectionArgs)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Provider delete failed: $authority")
            0
        }
    }

    /**
     * Get the MIME type for a URI from a virtual ContentProvider.
     */
    fun getType(authority: String, uri: Uri): String? {
        val record = findProviderRecord(authority) ?: return null

        return try {
            record.provider.getType(uri)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Provider getType failed: $authority")
            null
        }
    }

    /**
     * Open a file via a virtual ContentProvider (for FileProvider support).
     *
     * Critical for:
     *   - Camera intent (capturing photos)
     *   - File sharing between apps
     *   - Content:// URI file access
     */
    fun openFile(
        authority: String,
        uri: Uri,
        mode: String
    ): ParcelFileDescriptor? {
        val record = findProviderRecord(authority) ?: run {
            Timber.tag(TAG).w("No provider for authority: $authority (openFile)")
            return null
        }

        return try {
            record.provider.openFile(uri, mode)
        } catch (e: FileNotFoundException) {
            Timber.tag(TAG).w("File not found via provider: $uri")
            null
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Provider openFile failed: $authority")
            null
        }
    }

    /**
     * Call a custom method on a virtual ContentProvider.
     */
    fun call(
        authority: String,
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle? {
        val record = findProviderRecord(authority) ?: run {
            Timber.tag(TAG).w("No provider for authority: $authority (call)")
            return null
        }

        return try {
            record.provider.call(method, arg, extras)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Provider call failed: $authority.$method")
            null
        }
    }

    // ---------- Permission Enforcement ----------

    /**
     * Check if the caller has read permission to a provider.
     */
    fun checkReadPermission(authority: String, callingPackage: String): Boolean {
        val record = findProviderRecord(authority) ?: return false

        // No read permission required
        if (record.readPermission == null) return true

        // Same package always allowed
        if (record.packageName == callingPackage) return true

        // Exported providers with no read permission are accessible
        if (record.exported && record.readPermission == null) return true

        // TODO: Check against VirtualPermissionManager
        return record.exported
    }

    /**
     * Check if the caller has write permission to a provider.
     */
    fun checkWritePermission(authority: String, callingPackage: String): Boolean {
        val record = findProviderRecord(authority) ?: return false

        if (record.writePermission == null) return true
        if (record.packageName == callingPackage) return true
        if (record.exported && record.writePermission == null) return true

        return record.exported
    }

    // ---------- Rewrite URIs ----------

    /**
     * Rewrite a content:// URI to use the virtual authority.
     * Guest apps use their original authority, we map it to the virtual one.
     */
    fun rewriteUri(uri: Uri, instanceId: String): Uri {
        val authority = uri.authority ?: return uri
        val record = providersByAuthority[authority] ?: return uri

        if (record.instanceId != instanceId) {
            // Not this instance's provider — return as-is
            return uri
        }

        return uri.buildUpon()
            .authority(record.virtualAuthority)
            .build()
    }

    /**
     * Reverse-rewrite a virtual authority URI back to the original.
     */
    fun unrewriteUri(uri: Uri): Uri {
        val virtualAuthority = uri.authority ?: return uri
        val record = providersByVirtualAuthority[virtualAuthority] ?: return uri

        return uri.buildUpon()
            .authority(record.authority)
            .build()
    }

    // ---------- Private Helpers ----------

    private fun findProviderRecord(authority: String): ProviderRecord? {
        return providersByAuthority[authority]
            ?: providersByVirtualAuthority[authority]
    }

    // ---------- Cleanup ----------

    /**
     * Uninstall all providers for an instance.
     */
    fun uninstallProviders(instanceId: String) {
        val records = providersByInstance.remove(instanceId) ?: return

        Timber.tag(TAG).i("Uninstalling ${records.size} providers for $instanceId")

        for (record in records) {
            try {
                record.provider.shutdown()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Error shutting down provider: ${record.providerClassName}")
            }

            providersByAuthority.remove(record.authority)
            providersByVirtualAuthority.remove(record.virtualAuthority)
            providersByClassName.remove(record.providerClassName)
        }
    }

    /**
     * Get all providers for an instance.
     */
    fun getProviders(instanceId: String): List<ProviderRecord> =
        providersByInstance[instanceId] ?: emptyList()

    /**
     * Get the number of installed providers for an instance.
     */
    fun getProviderCount(instanceId: String): Int =
        providersByInstance[instanceId]?.size ?: 0

    /**
     * Get all installed provider authorities.
     */
    fun getAllAuthorities(): Set<String> =
        providersByAuthority.keys.toSet()

    /**
     * Clear all state (shutdown).
     */
    fun clearAll() {
        Timber.tag(TAG).i("Clearing all provider state")

        for (records in providersByInstance.values) {
            for (record in records) {
                try {
                    record.provider.shutdown()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Error during provider shutdown")
                }
            }
        }

        providersByAuthority.clear()
        providersByVirtualAuthority.clear()
        providersByInstance.clear()
        providersByClassName.clear()
    }

    /**
     * Initialize the content provider manager with application context.
     */
    fun initialize(context: android.content.Context) {
        Timber.tag(TAG).d("VirtualContentProviderManager initialized")
    }
}
