package com.nextvm.core.binder.proxy

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ApplicationInfo
import android.content.pm.ActivityInfo
import android.content.pm.ProviderInfo
import com.nextvm.core.model.GmsServiceRouter
import com.nextvm.core.model.VirtualApp
import com.nextvm.core.model.VirtualConstants
import timber.log.Timber
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * PackageManagerProxy — Intercepts all IPackageManager IPC calls.
 *
 * Based on Android 16 IPackageManager.aidl analysis:
 * - getPackageInfo: Return virtual package info for installed virtual apps
 * - getApplicationInfo: Return virtual app info
 * - getActivityInfo: Return virtual activity info
 * - resolveIntent: Resolve against virtual component registry
 * - queryIntentActivities: Include virtual app activities
 * - checkPermission: Check against virtual permission policy
 */
class PackageManagerProxy(
    private val original: Any,
    private val context: Context
) : InvocationHandler {

    companion object {
        private const val TAG = "PMProxy"
        private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }

    // Virtual app registry
    private val virtualApps = mutableMapOf<String, VirtualApp>()

    // Cached PackageInfo for virtual apps
    private val packageInfoCache = mutableMapOf<String, PackageInfo>()

    // GMS service router — provides synthesized GMS PackageInfo
    @Volatile
    private var gmsRouter: GmsServiceRouter? = null

    // Re-entrancy guard: prevents StackOverflow when synthesizeGmsPackageInfo calls
    // context.packageManager.getPackageInfo() which loops back into this proxy.
    private val gmsLookupInProgress = ThreadLocal<Boolean>()

    /**
     * Set the GMS service router. Enables returning synthesized
     * PackageInfo for GMS packages (com.google.android.gms, etc.)
     * when guest apps query them via getPackageInfo().
     */
    fun setGmsRouter(router: GmsServiceRouter) {
        gmsRouter = router
        Timber.tag(TAG).i("GMS router connected to PackageManagerProxy")
    }

    fun registerApp(app: VirtualApp) {
        virtualApps[app.packageName] = app
        // Pre-build PackageInfo for this app using direct APK parsing
        parseApkDirect(app)
    }

    fun unregisterApp(packageName: String) {
        virtualApps.remove(packageName)
        packageInfoCache.remove(packageName)
    }

    /**
     * Get theme resource ID for a specific activity.
     * Returns activity-specific theme if set, falls back to application theme.
     */
    fun getActivityTheme(packageName: String, activityName: String): Int {
        val pkgInfo = packageInfoCache[packageName] ?: return 0
        val ai = pkgInfo.activities?.firstOrNull { it.name == activityName }
        if (ai != null && ai.theme != 0) return ai.theme
        return pkgInfo.applicationInfo?.theme ?: 0
    }

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        val methodName = method.name

        return try {
            when (methodName) {
                "getPackageInfo" -> handleGetPackageInfo(method, args)
                "getApplicationInfo" -> handleGetApplicationInfo(method, args)
                "getActivityInfo" -> handleGetActivityInfo(method, args)
                "getServiceInfo" -> handleGetServiceInfo(method, args)
                "getReceiverInfo" -> handleGetReceiverInfo(method, args)
                "getProviderInfo" -> handleGetProviderInfo(method, args)
                "resolveContentProvider" -> handleResolveContentProvider(method, args)
                "resolveIntent" -> handleResolveIntent(method, args)
                "queryIntentActivities" -> handleQueryIntentActivities(method, args)
                "getInstalledPackages" -> handleGetInstalledPackages(method, args)
                "getPackageUid" -> handleGetPackageUid(method, args)
                "checkPermission" -> handleCheckPermission(method, args)
                "checkUidPermission" -> handleCheckUidPermission(method, args)
                "getInstallerPackageName" -> handleGetInstallerPackageName(method, args)
                "getInstallSourceInfo" -> handleGetInstallSourceInfo(method, args)

                // Splash screen APIs — guest app can't own its package at system level.
                // These throw SecurityException ("Calling uid XXXX does not own package")
                // because the virtual app runs under NEXTVM's UID, not its own.
                // Safe to no-op: splash screen theming is not meaningful in virtual env.
                "setSplashScreenTheme",
                "setSplashScreenStyle",
                "reportSplashScreenAttached" -> {
                    Timber.tag(TAG).d("SplashScreen API $methodName no-op for virtual guest (pkg=${args?.getOrNull(0)})")
                    null
                }

                // setComponentEnabledSetting — guest app tries to enable/disable its own
                // components (e.g. WorkManager's SystemJobService) but those components
                // belong to the guest package which isn't actually installed on the system.
                // NEXTVM's UID can't modify another package's components → SecurityException.
                // Safe to silently no-op.
                "setComponentEnabledSetting" -> {
                    Timber.tag(TAG).d("setComponentEnabledSetting no-op for virtual guest (component=${args?.getOrNull(0)})")
                    null
                }

                else -> invokeOriginal(method, args)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in proxy method: $methodName")
            // For SecurityExceptions from virtual app operations, swallow rather than propagate
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
            when {
                cause is SecurityException -> {
                    Timber.tag(TAG).w("PM SecurityException swallowed for $methodName: ${cause.message}")
                    null
                }
                // WebView BuildInfo / Chromium: unknown virtual package must not crash factory init
                cause is IllegalArgumentException &&
                    (methodName == "getInstallerPackageName" || methodName == "getInstallSourceInfo") -> {
                    Timber.tag(TAG).w("PM IllegalArgumentException swallowed for $methodName: ${cause.message}")
                    if (methodName == "getInstallSourceInfo") synthesizeInstallSourceInfo() else null
                }
                else -> invokeOriginal(method, args)
            }
        }
    }

    private fun handleGetPackageInfo(method: Method, args: Array<out Any>?): Any? {
        if (args == null || args.isEmpty()) return invokeOriginal(method, args)

        val packageName = args[0] as? String

        // Check virtual apps first
        if (packageName != null && virtualApps.containsKey(packageName)) {
            val cached = packageInfoCache[packageName]
            if (cached != null) {
                Timber.tag(TAG).d("Returning virtual PackageInfo for $packageName")
                return cached
            }
        }

        // Check GMS packages — pass through to real PM first (preserves caller's flags
        // like GET_SIGNING_CERTIFICATES needed by GoogleSignatureVerifier), fall back to
        // synthesis only when GMS isn't installed on the host device.
        val router = gmsRouter
        if (packageName != null && router != null && router.isGmsPackage(packageName)
                && gmsLookupInProgress.get() != true) {
            // Try real PM first — preserves caller's flags
            try {
                val realInfo = invokeOriginal(method, args)
                if (realInfo != null) return realInfo
            } catch (_: Exception) {}

            // GMS not installed on host — synthesize
            gmsLookupInProgress.set(true)
            try {
                val gmsInfo = router.synthesizeGmsPackageInfo(packageName)
                if (gmsInfo != null) {
                    Timber.tag(TAG).d("Returning synthesized GMS PackageInfo for $packageName (v=${gmsInfo.versionName})")
                    return gmsInfo
                }
            } finally {
                gmsLookupInProgress.set(false)
            }
        }

        return invokeOriginal(method, args)
    }

    private fun handleGetApplicationInfo(method: Method, args: Array<out Any>?): Any? {
        if (args == null || args.isEmpty()) return invokeOriginal(method, args)

        val packageName = args[0] as? String
        if (packageName != null && virtualApps.containsKey(packageName)) {
            val app = virtualApps[packageName]!!
            val appInfo = buildApplicationInfo(app)
            Timber.tag(TAG).d("Returning virtual ApplicationInfo for $packageName")
            return appInfo
        }

        // GMS packages — return applicationInfo from synthesized PackageInfo
        val router = gmsRouter
        if (packageName != null && router != null && router.isGmsPackage(packageName)) {
            val gmsInfo = router.synthesizeGmsPackageInfo(packageName)
            if (gmsInfo?.applicationInfo != null) {
                Timber.tag(TAG).d("Returning synthesized GMS ApplicationInfo for $packageName")
                return gmsInfo.applicationInfo
            }
        }

        // Host package — inject GMS version metadata so Google Play Services SDKs
        // inside virtual apps don't throw GooglePlayServicesMissingManifestValueException.
        // Many SDKs call context.getPackageName() which returns the host package name,
        // then check getApplicationInfo(hostPkg).metaData for com.google.android.gms.version.
        if (packageName != null && virtualApps.isNotEmpty() && packageName == context.packageName) {
            val result = invokeOriginal(method, args) as? ApplicationInfo
            if (result != null) {
                val meta = result.metaData ?: android.os.Bundle()
                if (!meta.containsKey("com.google.android.gms.version")) {
                    meta.putInt("com.google.android.gms.version", 243431006)
                    result.metaData = meta
                    Timber.tag(TAG).d("Injected GMS version metadata into host ApplicationInfo")
                }
                return result
            }
        }

        return invokeOriginal(method, args)
    }

    private fun handleGetActivityInfo(method: Method, args: Array<out Any>?): Any? {
        if (args == null || args.isEmpty()) return invokeOriginal(method, args)
        val component = args[0] as? ComponentName ?: return invokeOriginal(method, args)
        if (!virtualApps.containsKey(component.packageName)) {
            return invokeOriginal(method, args)
        }
        val activity = packageInfoCache[component.packageName]
            ?.activities
            ?.firstOrNull { it.name == component.className }
        if (activity != null) {
            Timber.tag(TAG).d(
                "Returning virtual ActivityInfo for ${component.flattenToShortString()} " +
                    "(meta=${activity.metaData != null})"
            )
        }
        return activity
    }

    /**
     * Firebase ComponentDiscovery reads registrar declarations from the
     * ComponentDiscoveryService's meta-data via getServiceInfo(GET_META_DATA).
     */
    private fun handleGetServiceInfo(method: Method, args: Array<out Any>?): Any? {
        if (args == null || args.isEmpty()) return invokeOriginal(method, args)
        val component = args[0] as? ComponentName ?: return invokeOriginal(method, args)
        if (!virtualApps.containsKey(component.packageName)) {
            return invokeOriginal(method, args)
        }
        val service = packageInfoCache[component.packageName]
            ?.services
            ?.firstOrNull { it.name == component.className }
        if (service != null) {
            Timber.tag(TAG).d(
                "Returning virtual ServiceInfo for ${component.flattenToShortString()} " +
                    "(meta=${service.metaData != null})"
            )
        }
        return service
    }

    private fun handleGetReceiverInfo(method: Method, args: Array<out Any>?): Any? {
        if (args == null || args.isEmpty()) return invokeOriginal(method, args)
        val component = args[0] as? ComponentName ?: return invokeOriginal(method, args)
        if (!virtualApps.containsKey(component.packageName)) {
            return invokeOriginal(method, args)
        }
        return packageInfoCache[component.packageName]
            ?.receivers
            ?.firstOrNull { it.name == component.className }
    }

    /**
     * Return guest ProviderInfo (with meta-data) for FileProvider / Startup / etc.
     * AIDL: ProviderInfo getProviderInfo(ComponentName className, long flags, int userId)
     */
    private fun handleGetProviderInfo(method: Method, args: Array<out Any>?): Any? {
        if (args == null || args.isEmpty()) return invokeOriginal(method, args)
        val component = args[0] as? ComponentName ?: return invokeOriginal(method, args)
        if (!virtualApps.containsKey(component.packageName)) {
            return invokeOriginal(method, args)
        }
        val provider = findProviderInfo(component.packageName, component.className)
        if (provider != null) {
            Timber.tag(TAG).d(
                "Returning virtual ProviderInfo for ${component.flattenToShortString()} " +
                    "(meta=${provider.metaData != null})"
            )
            return provider
        }
        Timber.tag(TAG).w("No virtual ProviderInfo for ${component.flattenToShortString()}")
        return null
    }

    /**
     * Resolve guest content authority → ProviderInfo (needs meta-data for FileProvider paths).
     * AIDL: ProviderInfo resolveContentProvider(String name, long flags, int userId)
     */
    private fun handleResolveContentProvider(method: Method, args: Array<out Any>?): Any? {
        if (args == null || args.isEmpty()) return invokeOriginal(method, args)
        val authority = args[0] as? String ?: return invokeOriginal(method, args)

        for (pkg in virtualApps.keys) {
            val provider = findProviderByAuthority(pkg, authority)
            if (provider != null) {
                Timber.tag(TAG).d(
                    "resolveContentProvider($authority) → ${provider.name} " +
                        "(meta=${provider.metaData != null})"
                )
                return provider
            }
        }
        return invokeOriginal(method, args)
    }

    private fun findProviderInfo(packageName: String, className: String): ProviderInfo? {
        return packageInfoCache[packageName]?.providers?.firstOrNull { it.name == className }
    }

    private fun findProviderByAuthority(packageName: String, authority: String): ProviderInfo? {
        val providers = packageInfoCache[packageName]?.providers ?: return null
        return providers.firstOrNull { info ->
            info.authority?.split(';')?.any { it.trim() == authority } == true
        }
    }

    private fun handleResolveIntent(method: Method, args: Array<out Any>?): Any? {
        // TODO: Resolve against virtual component registry
        return invokeOriginal(method, args)
    }

    private fun handleQueryIntentActivities(method: Method, args: Array<out Any>?): Any? {
        // TODO: Include virtual app activities in results
        return invokeOriginal(method, args)
    }

    private fun handleGetInstalledPackages(method: Method, args: Array<out Any>?): Any? {
        // Return real packages + virtual packages
        return invokeOriginal(method, args)
    }

    private fun handleGetPackageUid(method: Method, args: Array<out Any>?): Any? {
        if (args == null || args.isEmpty()) return invokeOriginal(method, args)

        val packageName = args[0] as? String
        if (packageName != null && virtualApps.containsKey(packageName)) {
            // Return a virtual UID based on process slot
            val app = virtualApps[packageName]!!
            val virtualUid = 10000 + app.processSlot + 1000 // Offset to avoid conflicts
            return virtualUid
        }

        return invokeOriginal(method, args)
    }

    /**
     * WebView / Chromium BuildInfo calls getInstallerPackageName(guestPkg).
     * Real PMS throws IllegalArgumentException for packages not system-installed.
     * Return null (valid "unknown installer") for virtual guests.
     */
    private fun handleGetInstallerPackageName(method: Method, args: Array<out Any>?): Any? {
        val packageName = args?.getOrNull(0) as? String
        if (packageName != null && virtualApps.containsKey(packageName)) {
            Timber.tag(TAG).d("getInstallerPackageName($packageName) → null (virtual)")
            return null
        }
        return try {
            invokeOriginal(method, args)
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
            if (cause is IllegalArgumentException) {
                Timber.tag(TAG).d("getInstallerPackageName($packageName) → null (unknown pkg)")
                null
            } else {
                throw e
            }
        }
    }

    /**
     * API 30+ replacement for getInstallerPackageName. Chromium may call this path.
     * Synthesize an empty InstallSourceInfo for virtual packages.
     */
    private fun handleGetInstallSourceInfo(method: Method, args: Array<out Any>?): Any? {
        val packageName = args?.getOrNull(0) as? String
        if (packageName != null && virtualApps.containsKey(packageName)) {
            Timber.tag(TAG).d("getInstallSourceInfo($packageName) → synthetic (virtual)")
            return synthesizeInstallSourceInfo()
                ?: invokeOriginalSafeNull(method, args)
        }
        return try {
            invokeOriginal(method, args)
        } catch (e: Exception) {
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause else e
            if (cause is IllegalArgumentException || cause is PackageManager.NameNotFoundException) {
                synthesizeInstallSourceInfo()
            } else {
                throw e
            }
        }
    }

    private fun invokeOriginalSafeNull(method: Method, args: Array<out Any>?): Any? {
        return try {
            invokeOriginal(method, args)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build android.content.pm.InstallSourceInfo with all-null installer fields.
     * Chromium accepts this; avoids IllegalArgumentException from real PMS.
     */
    private fun synthesizeInstallSourceInfo(): Any? {
        return try {
            val clazz = Class.forName("android.content.pm.InstallSourceInfo")
            // Prefer 4-arg ctor: (initiating, SigningInfo, originating, installing)
            val ctors = clazz.declaredConstructors
            for (ctor in ctors) {
                ctor.isAccessible = true
                val params = ctor.parameterTypes
                when (params.size) {
                    4 -> {
                        // (String, SigningInfo, String, String) — common AOSP shape
                        if (params[0] == String::class.java && params[2] == String::class.java) {
                            return ctor.newInstance(null, null, null, null)
                        }
                    }
                    5 -> {
                        // Later APIs add updateOwnerPackageName
                        if (params[0] == String::class.java) {
                            return ctor.newInstance(null, null, null, null, null)
                        }
                    }
                    6 -> {
                        if (params[0] == String::class.java) {
                            return ctor.newInstance(null, null, null, null, null, null)
                        }
                    }
                }
            }
            // Last resort: any ctor filled with nulls
            val ctor = ctors.minByOrNull { it.parameterCount } ?: return null
            ctor.isAccessible = true
            val nulls = arrayOfNulls<Any>(ctor.parameterCount)
            ctor.newInstance(*nulls)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to synthesize InstallSourceInfo")
            null
        }
    }

    private fun handleCheckPermission(method: Method, args: Array<out Any>?): Any? {
        if (args == null || args.size < 2) return invokeOriginal(method, args)

        val permission = args[0] as? String
        val packageName = args[1] as? String

        if (packageName != null && virtualApps.containsKey(packageName)) {
            val app = virtualApps[packageName]!!
            // Check against virtual permission overrides (allow explicit deny)
            val override = app.permissionOverrides[permission]
            if (override != null) {
                return if (override) 0 else -1 // PERMISSION_GRANTED or PERMISSION_DENIED
            }
            // Auto-grant ALL permissions to virtual apps
            return 0 // PackageManager.PERMISSION_GRANTED
        }

        return invokeOriginal(method, args)
    }

    /**
     * Handle checkUidPermission — some framework code checks by UID instead of package name.
     * Since virtual apps run under the host UID, we intercept and return GRANTED
     * for all permission checks that could be coming from a virtual app context.
     *
     * AIDL: int checkUidPermission(String permName, int uid)
     */
    private fun handleCheckUidPermission(method: Method, args: Array<out Any>?): Any? {
        if (args == null || args.size < 2) return invokeOriginal(method, args)

        // If any virtual app is registered, grant permissions for the host UID
        // because all virtual apps run under the host app's UID
        if (virtualApps.isNotEmpty()) {
            val hostUid = android.os.Process.myUid()
            val checkedUid = args[1] as? Int
            if (checkedUid == hostUid) {
                return 0 // PackageManager.PERMISSION_GRANTED
            }
        }

        return invokeOriginal(method, args)
    }

    /**
     * Parse APK manifest directly using AssetManager to build PackageInfo cache.
     * This bypasses PackageParser2 which triggers AconfigFlags static init
     * that crashes on devices missing /vendor/etc/aconfig_flags.pb.
     */
    private fun parseApkDirect(app: VirtualApp) {
        try {
            val am = android.content.res.AssetManager::class.java.getDeclaredConstructor().newInstance()
            val addAssetPath = android.content.res.AssetManager::class.java
                .getDeclaredMethod("addAssetPath", String::class.java)
            addAssetPath.isAccessible = true
            addAssetPath.invoke(am, app.apkPath)

            val parser = am.openXmlResourceParser("AndroidManifest.xml")

            var packageName = app.packageName
            var versionCode = 0L
            var versionName = ""
            val activities = mutableListOf<ActivityInfo>()
            val services = mutableListOf<android.content.pm.ServiceInfo>()
            val providers = mutableListOf<android.content.pm.ProviderInfo>()
            val permissions = mutableListOf<String>()

            var currentActivityName: String? = null
            var currentActivityTheme = 0
            var appThemeResId = 0
            var inActivity = false
            var inIntentFilter = false

            var eventType = parser.eventType
            while (eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    org.xmlpull.v1.XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "manifest" -> {
                                packageName = parser.getAttributeValue(null, "package")
                                    ?: app.packageName
                                versionCode = parser.getAttributeValue(ANDROID_NS, "versionCode")
                                    ?.toLongOrNull() ?: 0L
                                versionName = parser.getAttributeValue(ANDROID_NS, "versionName")
                                    ?: ""
                            }
                            "application" -> {
                                appThemeResId = parser.getAttributeResourceValue(ANDROID_NS, "theme", 0)
                            }
                            "activity", "activity-alias" -> {
                                inActivity = true
                                val name = parser.getAttributeValue(ANDROID_NS, "name")
                                currentActivityName = resolveClassName(name, packageName)
                                currentActivityTheme = parser.getAttributeResourceValue(ANDROID_NS, "theme", 0)
                            }
                            "service" -> {
                                val name = parser.getAttributeValue(ANDROID_NS, "name")
                                if (name != null) {
                                    val si = android.content.pm.ServiceInfo()
                                    si.name = resolveClassName(name, packageName)
                                    si.packageName = packageName
                                    services.add(si)
                                }
                            }
                            "provider" -> {
                                val name = parser.getAttributeValue(ANDROID_NS, "name")
                                val authority = parser.getAttributeValue(ANDROID_NS, "authorities")
                                if (name != null) {
                                    val pi = android.content.pm.ProviderInfo()
                                    pi.name = resolveClassName(name, packageName)
                                    pi.packageName = packageName
                                    pi.authority = authority
                                    providers.add(pi)
                                }
                            }
                            "uses-permission" -> {
                                val perm = parser.getAttributeValue(ANDROID_NS, "name")
                                if (perm != null) permissions.add(perm)
                            }
                            "intent-filter" -> {
                                if (inActivity) inIntentFilter = true
                            }
                            "action" -> {
                                if (inIntentFilter) {
                                    // Parsing intent-filter actions (for future MAIN/LAUNCHER detection)
                                }
                            }
                            "category" -> {
                                if (inIntentFilter) {
                                    // Parsing intent-filter categories
                                }
                            }
                        }
                    }
                    org.xmlpull.v1.XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "activity", "activity-alias" -> {
                                if (currentActivityName != null) {
                                    val ai = ActivityInfo()
                                    ai.name = currentActivityName
                                    ai.packageName = packageName
                                    ai.theme = currentActivityTheme
                                    activities.add(ai)
                                }
                                inActivity = false
                                inIntentFilter = false
                                currentActivityName = null
                                currentActivityTheme = 0
                            }
                            "intent-filter" -> inIntentFilter = false
                        }
                    }
                }
                eventType = parser.next()
            }
            parser.close()
            am.close()

            // Build PackageInfo from parsed data
            val info = PackageInfo()
            info.packageName = packageName
            info.versionName = versionName
            @Suppress("DEPRECATION")
            info.versionCode = versionCode.toInt()

            val appInfo = ApplicationInfo()
            appInfo.packageName = packageName
            appInfo.sourceDir = app.apkPath
            appInfo.publicSourceDir = app.apkPath
            appInfo.dataDir = "${context.filesDir}/${VirtualConstants.VIRTUAL_DIR}/" +
                    "${VirtualConstants.VIRTUAL_DATA_DIR}/${app.instanceId}"
            appInfo.nativeLibraryDir = "${appInfo.dataDir}/lib"
            appInfo.enabled = true
            appInfo.flags = ApplicationInfo.FLAG_INSTALLED
            appInfo.theme = appThemeResId
            info.applicationInfo = appInfo

            // Link each ActivityInfo to applicationInfo so getThemeResource() fallback works
            activities.forEach { it.applicationInfo = appInfo }

            info.activities = activities.toTypedArray()
            info.services = services.toTypedArray()
            info.providers = providers.toTypedArray()
            info.requestedPermissions = permissions.toTypedArray()

            // Prefer archive component records, which retain manifest meta-data.
            // This is required by Firebase ComponentDiscoveryService, FlutterActivity,
            // FileProvider, AndroidX Startup, and other manifest-driven SDKs.
            enrichComponentsFromArchive(app, info, appInfo)

            // Extract signing certificates from APK so GMS/Firebase can verify
            // the app's identity (fingerprint hash, GoogleSignatureVerifier, etc.)
            try {
                val archiveInfo = context.packageManager.getPackageArchiveInfo(
                    app.apkPath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                if (archiveInfo?.signingInfo != null) {
                    info.signingInfo = archiveInfo.signingInfo
                    Timber.tag(TAG).d("parseApkDirect: extracted signingInfo for $packageName")
                } else {
                    // Fallback to deprecated GET_SIGNATURES for older signing schemes
                    @Suppress("DEPRECATION")
                    val archiveLegacy = context.packageManager.getPackageArchiveInfo(
                        app.apkPath,
                        @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
                    )
                    @Suppress("DEPRECATION")
                    if (archiveLegacy?.signatures != null) {
                        @Suppress("DEPRECATION")
                        info.signatures = archiveLegacy.signatures
                        Timber.tag(TAG).d("parseApkDirect: extracted legacy signatures for $packageName")
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).w("Could not extract signing info for $packageName: ${e.message}")
            }

            packageInfoCache[packageName] = info
            Timber.tag(TAG).d("parseApkDirect: cached PackageInfo for $packageName " +
                    "(${info.activities?.size ?: 0} activities, ${info.services?.size ?: 0} services, " +
                    "${info.providers?.size ?: 0} providers)")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "parseApkDirect failed for ${app.packageName}")
        }
    }

    /**
     * Replace XML-only component stubs with PackageManager archive records so all
     * ComponentInfo meta-data is retained.
     */
    private fun enrichComponentsFromArchive(
        app: VirtualApp,
        info: PackageInfo,
        appInfo: ApplicationInfo
    ) {
        try {
            val flags = PackageManager.GET_ACTIVITIES or
                PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_META_DATA
            val archive = context.packageManager.getPackageArchiveInfo(app.apkPath, flags)
                ?: return
            val componentAppInfo = (archive.applicationInfo ?: appInfo).apply {
                packageName = app.packageName
                sourceDir = app.apkPath
                publicSourceDir = app.apkPath
                dataDir = appInfo.dataDir
                nativeLibraryDir = appInfo.nativeLibraryDir
                enabled = true
            }

            archive.activities?.takeIf { it.isNotEmpty() }?.let { activities ->
                info.activities = activities.onEach { activity ->
                    activity.packageName = app.packageName
                    activity.applicationInfo = componentAppInfo
                }
            }
            archive.services?.takeIf { it.isNotEmpty() }?.let { services ->
                info.services = services.onEach { service ->
                    service.packageName = app.packageName
                    service.applicationInfo = componentAppInfo
                }
            }
            archive.receivers?.takeIf { it.isNotEmpty() }?.let { receivers ->
                info.receivers = receivers.onEach { receiver ->
                    receiver.packageName = app.packageName
                    receiver.applicationInfo = componentAppInfo
                }
            }
            archive.providers?.takeIf { it.isNotEmpty() }?.let { providers ->
                val xmlByName = info.providers?.associateBy { it.name }.orEmpty()
                info.providers = providers.onEach { provider ->
                    provider.packageName = app.packageName
                    provider.applicationInfo = componentAppInfo
                    if (provider.authority.isNullOrEmpty()) {
                        provider.authority = xmlByName[provider.name]?.authority
                    }
                }
            }

            Timber.tag(TAG).d(
                "enrichComponentsFromArchive: ${app.packageName} " +
                    "(activities=${info.activities?.size ?: 0}, " +
                    "services=${info.services?.size ?: 0}, " +
                    "serviceMeta=${info.services?.count { it.metaData != null } ?: 0}, " +
                    "providers=${info.providers?.size ?: 0}, " +
                    "providerMeta=${info.providers?.count { it.metaData != null } ?: 0})"
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "enrichComponentsFromArchive failed for ${app.packageName}")
        }
    }

    /**
     * Resolve a class name from the manifest. Handles:
     * - ".MyActivity" → "com.pkg.MyActivity" (relative with dot)
     * - "MyActivity" → "com.pkg.MyActivity" (short name, no dot)
     * - "com.pkg.MyActivity" → "com.pkg.MyActivity" (already fully qualified)
     */
    private fun resolveClassName(name: String?, packageName: String): String? {
        if (name == null) return null
        return when {
            name.startsWith(".") -> "$packageName$name"
            !name.contains(".") -> "$packageName.$name"
            else -> name
        }
    }

    private fun buildApplicationInfo(app: VirtualApp): ApplicationInfo {
        return ApplicationInfo().apply {
            packageName = app.packageName
            sourceDir = app.apkPath
            publicSourceDir = app.apkPath
            dataDir = "${context.filesDir}/${VirtualConstants.VIRTUAL_DIR}/${VirtualConstants.VIRTUAL_DATA_DIR}/${app.instanceId}"
            nativeLibraryDir = "$dataDir/lib"
            enabled = true
            flags = ApplicationInfo.FLAG_INSTALLED or ApplicationInfo.FLAG_HAS_CODE

            // Include meta-data from the guest APK so GMS/SDKs can read their
            // <meta-data> tags (com.google.android.gms.version, etc.)
            try {
                val pm = context.packageManager
                val pkgInfo = pm.getPackageArchiveInfo(
                    app.apkPath,
                    android.content.pm.PackageManager.GET_META_DATA
                )
                pkgInfo?.applicationInfo?.let { it2 ->
                    it2.sourceDir = app.apkPath
                    it2.publicSourceDir = app.apkPath
                }
                val bundle = pkgInfo?.applicationInfo?.metaData ?: android.os.Bundle()

                // Inject GMS version if not present — many SDKs crash without this
                if (!bundle.containsKey("com.google.android.gms.version")) {
                    bundle.putInt("com.google.android.gms.version", 243431006)
                }

                this.metaData = bundle
            } catch (e: Exception) {
                Timber.tag(TAG).w("Failed to parse metaData from APK: ${app.apkPath}")
                this.metaData = android.os.Bundle().apply {
                    putInt("com.google.android.gms.version", 243431006)
                }
            }
        }
    }

    private fun invokeOriginal(method: Method, args: Array<out Any>?): Any? {
        return if (args != null) method.invoke(original, *args)
        else method.invoke(original)
    }
}
