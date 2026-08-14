package com.nextvm.core.binder.proxy

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.nextvm.core.common.findField
import com.nextvm.core.model.GmsServiceRouter
import com.nextvm.core.model.VirtualApp
import com.nextvm.core.model.VirtualConstants
import com.nextvm.core.model.VirtualIntentExtras
import timber.log.Timber
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * ActivityManagerProxy — Intercepts all IActivityManager IPC calls.
 *
 * Based on Android 16 IActivityManager.aidl analysis:
 * - startActivity: Swap guest Intent with stub Intent
 * - startService: Swap guest service with stub service
 * - bindService: Swap guest + GMS service binds through Hybrid bridge
 * - broadcastIntent: Route to virtual receivers
 * - getContentProvider: Return virtual provider
 *
 * This proxy sits between the app process and the real ActivityManagerService.
 * When a virtual app calls startActivity(), we intercept the Intent,
 * replace the target with our stub, and add NEXTVM metadata extras.
 *
 * GMS ROUTING: When a guest app calls bindService() targeting GMS packages
 * (com.google.android.gms, com.android.vending), the call is routed through
 * the GmsServiceRouter to the Hybrid GMS bridge instead of going directly
 * to the real system. This ensures package identity is properly spoofed.
 */
class ActivityManagerProxy(
    private val original: Any,
    private val context: Context
) : InvocationHandler {

    companion object {
        private const val TAG = "AMProxy"

        // GMS / Play Store packages that need routing through the GMS bridge
        private val GMS_PACKAGES = setOf(
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.vending"
        )

        // Settings intents that guest apps use to request special permissions.
        // We intercept these to prevent opening the real device's Settings,
        // and instead silently return as if the permission was already granted.
        private val PERMISSION_SETTINGS_ACTIONS = setOf(
            // Runtime permission request dialog
            "android.content.pm.action.REQUEST_PERMISSIONS",
            // Special permission settings
            Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
            "android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION",
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            "android.settings.REQUEST_SCHEDULE_EXACT_ALARM",
            "android.settings.REQUEST_MANAGE_MEDIA",
            "android.settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
            Settings.ACTION_USAGE_ACCESS_SETTINGS,
            Settings.ACTION_ACCESSIBILITY_SETTINGS,
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            "android.settings.REQUEST_INSTALL_PACKAGES"
        )
    }

    private val virtualApps = mutableMapOf<String, VirtualApp>()
    private val hostPackage = VirtualConstants.HOST_PACKAGE

    // GMS service router — set by VirtualEngine after initialization
    @Volatile
    private var gmsRouter: GmsServiceRouter? = null

    // Re-entrancy guard: prevents infinite recursion when GmsBinderBridge.connectService()
    // calls context.bindService() which re-enters this proxy through the GMS routing path.
    private val gmsBindInProgress = ThreadLocal<Boolean>()

    /**
     * Set the GMS service router. Called by VirtualEngine to connect the
     * ActivityManagerProxy to the Hybrid GMS bridge.
     */
    fun setGmsRouter(router: GmsServiceRouter) {
        gmsRouter = router
        Timber.tag(TAG).i("GMS service router connected to ActivityManagerProxy")
    }

    fun registerApp(app: VirtualApp) {
        virtualApps[app.packageName] = app
    }

    fun unregisterApp(packageName: String) {
        virtualApps.remove(packageName)
    }

    override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
        val methodName = method.name

        return try {
            when (methodName) {
                "startActivity" -> handleStartActivity(method, args)
                "startActivityAsUser" -> handleStartActivity(method, args)
                "startService" -> handleStartService(method, args)
                "stopService" -> handleStopService(method, args)
                "bindService" -> handleBindServiceCommon(method, args, callingPackageIndex = 6)
                "bindServiceInstance" -> handleBindServiceCommon(method, args, callingPackageIndex = 7)
                "broadcastIntent",
                "broadcastIntentWithFeature" -> handleBroadcastIntent(method, args)
                "getContentProvider" -> handleGetContentProvider(method, args)
                "getRunningAppProcesses" -> handleGetRunningAppProcesses(method, args)
                "getIntentSender",
                "getIntentSenderWithFeature" -> handleGetIntentSender(method, args)
                // Intercept kill methods to prevent guest apps from killing host process
                "killApplicationProcess",
                "killApplication",
                "killBackgroundProcesses",
                "forceStopPackage",
                "killAllBackgroundProcesses" -> handleKillProcess(method, args, methodName)
                else -> {
                    // Pass through all other methods to the real AMS
                    invokeOriginal(method, args)
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error in proxy method: $methodName")
            // Unwrap InvocationTargetException to get the real cause
            val cause = if (e is java.lang.reflect.InvocationTargetException) e.cause ?: e else e
            if (cause is SecurityException) {
                // SecurityException from AMS = UID/package mismatch in virtual env.
                // Swallow it to prevent UndeclaredThrowableException FATAL crashes.
                Timber.tag(TAG).w("AMS SecurityException swallowed for $methodName: ${cause.message}")
                null
            } else {
                // For non-security errors, try original one more time
                try {
                    invokeOriginal(method, args)
                } catch (e2: Exception) {
                    Timber.tag(TAG).w("Fallback also failed for $methodName: ${e2.message}")
                    null
                }
            }
        }
    }

    /**
     * Handle startActivity — THE most critical interception.
     *
     * If the calling process is a virtual app and the target is also virtual,
     * we swap the Intent to use a stub Activity from the target's process slot.
     *
     * Also intercepts permission-related Settings intents so that guest apps
     * never open the real device's Settings for permissions. Instead, we silently
     * return as if the permission was already granted.
     */
    private fun handleStartActivity(method: Method, args: Array<out Any>?): Any? {
        return handleStartActivityVia(method, args, original)
    }

    /**
     * Shared startActivity* handling for IActivityManager and IActivityTaskManager.
     *
     * Modern Android routes [android.app.Activity.startActivity] through
     * IActivityTaskManager; IActivityManager alone never sees those calls.
     *
     * @param binder original AMS or ATM Binder stub to invoke after rewriting args
     */
    fun handleStartActivityVia(method: Method, args: Array<out Any>?, binder: Any): Any? {
        if (args == null) return method.invoke(binder)

        val intentIndex = args.indexOfFirst { it is Intent }
        if (intentIndex < 0) return method.invoke(binder, *args)

        val intent = args[intentIndex] as Intent

        // ---- Permission Settings Intent Interception ----
        // When a guest app tries to open Settings for special permission granting
        // (e.g., "All file access", overlay, notification, etc.), intercept it
        // and return a fake success result. The virtual permission system already
        // auto-grants everything, so there's nothing to actually configure.
        val action = intent.action
        if (action != null && action in PERMISSION_SETTINGS_ACTIONS) {
            Timber.tag(TAG).i("Intercepted permission Settings intent: $action — auto-granted in VM")
            // Return 0 (ActivityManager.START_SUCCESS) to the caller.
            // The guest app thinks the Settings activity started, but nothing opens.
            // Since our VirtualPermissionManager already grants everything,
            // when the app checks the permission status afterward, it will see GRANTED.
            return 0 // START_SUCCESS
        }

        // Also intercept intents targeting the system PermissionController or PackageInstaller
        // These handle runtime permission dialogs (requestPermissions)
        val targetComponent = intent.component
        val targetPkg = targetComponent?.packageName ?: intent.`package` ?: ""
        if (targetPkg.contains("permissioncontroller") ||
            targetPkg.contains("packageinstaller") ||
            (targetPkg.contains("permission") && action?.contains("REQUEST_PERMISSIONS") == true)) {
            Timber.tag(TAG).i("Intercepted PermissionController/PackageInstaller intent ($targetPkg) — auto-granted in VM")
            return 0 // START_SUCCESS
        }

        // Also catch generic Settings intents with package-specific URIs
        // (e.g., Settings.ACTION_APPLICATION_DETAILS_SETTINGS with "package:com.foo")
        if (action != null && intent.data?.scheme == "package") {
            val settingsPkg = intent.data?.schemeSpecificPart
            if (settingsPkg != null && virtualApps.containsKey(settingsPkg)) {
                if (action.contains("settings", ignoreCase = true) ||
                    action.contains("permission", ignoreCase = true) ||
                    action.contains("manage", ignoreCase = true)) {
                    Timber.tag(TAG).i("Intercepted package Settings intent for virtual app $settingsPkg: $action")
                    return 0 // START_SUCCESS
                }
            }
        }

        // FIX: Replace callingPackage (args[1]) with host package.
        // Android 16 AIDL: startActivity(IApplicationThread, String callingPackage, Intent, ...)
        // The system server checks callingPackage against the caller's UID.
        val newArgs = args.clone() as Array<Any?>
        if (args.size >= 3 && args[1] is String && args[1] != hostPackage) {
            Timber.tag(TAG).d("startActivity: replacing callingPackage '${args[1]}' → '$hostPackage'")
            newArgs[1] = hostPackage
        }

        // Rewrite guest-to-guest Intents onto host stubs (mutates Intent in place).
        // Critical for IActivityTaskManager: without this, non-exported guest activities
        // resolve to a same-package host install → SecurityException.
        rewriteOutgoingStartActivityIntent(intent)

        return method.invoke(binder, *newArgs)
    }

    /**
     * Rewrite an outgoing startActivity Intent when it targets a registered virtual app.
     *
     * Mutates [intent] in place so callers that hold the same Intent reference
     * (e.g. Instrumentation.execStartActivity) see the stub component.
     *
     * Without this, guest apps that start their own non-exported activities resolve
     * against a same-package host install and crash with:
     *   SecurityException: ... not exported from uid XXX
     *
     * @return true if the Intent was rewritten to a host stub
     */
    fun rewriteOutgoingStartActivityIntent(intent: Intent): Boolean {
        if (intent.hasExtra(VirtualIntentExtras.TARGET_PACKAGE)) {
            return false
        }

        val resolvedTargetPkg = intent.component?.packageName ?: intent.`package` ?: return false
        if (!virtualApps.containsKey(resolvedTargetPkg)) {
            return false
        }

        val app = virtualApps[resolvedTargetPkg] ?: return false
        val targetClass = intent.component?.className ?: app.mainActivity ?: return false

        Timber.tag(TAG).d(
            "Rewriting startActivity Intent: $resolvedTargetPkg/$targetClass → stub " +
                "(slot=${app.processSlot}, instance=${app.instanceId})"
        )

        intent.setClassName(hostPackage, getStubForApp(app))
        intent.putExtra(VirtualIntentExtras.TARGET_PACKAGE, resolvedTargetPkg)
        intent.putExtra(VirtualIntentExtras.TARGET_ACTIVITY, targetClass)
        intent.putExtra(VirtualIntentExtras.INSTANCE_ID, app.instanceId)
        intent.putExtra(VirtualIntentExtras.APK_PATH, app.apkPath)
        intent.putExtra(VirtualIntentExtras.PROCESS_SLOT, app.processSlot)
        return true
    }

    /**
     * Handle startService — swap virtual service with stub.
     */
    private fun handleStartService(method: Method, args: Array<out Any>?): Any? {
        if (args == null) return invokeOriginal(method, args)

        val intentIndex = args.indexOfFirst { it is Intent }
        if (intentIndex < 0) return invokeOriginal(method, args)

        val intent = args[intentIndex] as Intent
        val targetPkg = intent.component?.packageName

        // FIX: Replace callingPackage (args[4]) with host package.
        // Android 16 AIDL: startService(IApplicationThread, Intent, String resolvedType,
        //                               boolean requireForeground, String callingPackage, ...)
        val newArgs = args.clone() as Array<Any?>
        if (args.size >= 6 && args[4] is String && args[4] != hostPackage) {
            Timber.tag(TAG).d("startService: replacing callingPackage '${args[4]}' → '$hostPackage'")
            newArgs[4] = hostPackage
        }

        if (targetPkg != null && virtualApps.containsKey(targetPkg)) {
            val app = virtualApps[targetPkg]!!
            val targetService = intent.component?.className

            if (targetService != null) {
                Timber.tag(TAG).d("Intercepting startService: $targetPkg/$targetService")

                val newIntent = Intent(intent).apply {
                    setClassName(hostPackage, getServiceStubForApp(app))
                    putExtra(VirtualIntentExtras.TARGET_PACKAGE, targetPkg)
                    putExtra(VirtualIntentExtras.TARGET_SERVICE, targetService)
                    putExtra(VirtualIntentExtras.INSTANCE_ID, app.instanceId)
                }

                newArgs[intentIndex] = newIntent
                return method.invoke(original, *newArgs)
            }
        }

        return method.invoke(original, *newArgs)
    }

    private fun handleStopService(method: Method, args: Array<out Any>?): Any? {
        return invokeOriginal(method, args)
    }

    private fun handleBindServiceCommon(method: Method, args: Array<out Any>?, callingPackageIndex: Int): Any? {
        if (args == null) return invokeOriginal(method, args)

        // Capture the ORIGINAL callingPackage before replacing it — this tells us
        // which guest app is making the call (VirtualContext.getPackageName() returns guest pkg)
        val originalCallingPkg = if (args.size > callingPackageIndex && args[callingPackageIndex] is String) {
            args[callingPackageIndex] as String
        } else null

        // FIX: Replace callingPackage with host package.
        // bindService AIDL:         callingPackage at index 6
        // bindServiceInstance AIDL:  callingPackage at index 7 (extra instanceName param at 6)
        val newArgs = args.clone() as Array<Any?>
        if (originalCallingPkg != null && originalCallingPkg != hostPackage) {
            Timber.tag(TAG).d("bindService: replacing callingPackage '$originalCallingPkg' → '$hostPackage'")
            newArgs[callingPackageIndex] = hostPackage
        }

        // Find the Intent argument in the parameter list
        val intentIndex = args.indexOfFirst { it is Intent }
        if (intentIndex < 0) return method.invoke(original, *newArgs)

        val intent = args[intentIndex] as Intent
        val targetPkg = intent.component?.packageName

        // ---- GMS SERVICE ROUTING ----
        // When a guest app calls bindService() targeting a GMS package
        // (com.google.android.gms, com.android.vending, etc.), we need to:
        // 1. Route through the GmsBinderBridge to ensure identity spoofing
        // 2. Rewrite the callingPackage so GMS sees the host app (which is installed)
        // 3. Let the real bind go through to host GMS with modified identity
        val router = gmsRouter
        if (router != null && router.isGmsServiceIntent(intent) && gmsBindInProgress.get() != true) {
            // Determine which guest app is making this call:
            // 1. Use the original callingPackage if it's a known guest app
            // 2. Fall back to registered virtualApps lookup
            val callingGuestPkg = if (originalCallingPkg != null && originalCallingPkg != hostPackage
                && virtualApps.containsKey(originalCallingPkg)) {
                originalCallingPkg
            } else {
                findCallingGuestPkg()
            }
            val callingInstanceId = if (callingGuestPkg != null) {
                virtualApps[callingGuestPkg]?.instanceId ?: findCallingInstanceId()
            } else {
                findCallingInstanceId()
            }

            Timber.tag(TAG).i("GMS bindService intercepted: action=${intent.action ?: intent.component} " +
                    "from guest=$callingGuestPkg instance=$callingInstanceId")

            if (callingInstanceId != null && callingGuestPkg != null) {
                // Get a proxied IBinder from the GmsBinderBridge / GmsPlayStoreProxy
                // Set re-entrancy guard so nested bindService calls from GmsBinderBridge
                // bypass this GMS routing and go directly to the real AMS.
                gmsBindInProgress.set(true)
                val proxiedBinder = try {
                    router.routeGmsBindIntent(
                        intent, callingInstanceId, callingGuestPkg
                    )
                } finally {
                    gmsBindInProgress.set(false)
                }

                if (proxiedBinder != null) {
                    // ---- DIRECT IServiceConnection DELIVERY ----
                    // The IBinder is real (obtained by the host binding to GMS).
                    // We deliver it directly via IServiceConnection.connected() instead
                    // of passing through the real AMS, because:
                    //  1. AMS would reject the bind — guest UID ≠ host UID match
                    //  2. Our IBinder already has identity spoofing in GmsBinderProxy
                    //  3. Delivering directly ensures the guest's onServiceConnected() fires

                    // IServiceConnection is at args[4] in both bindService and bindServiceInstance
                    val serviceConnectionArg = args.getOrNull(4)

                    if (serviceConnectionArg != null) {
                        // Build the ComponentName from the intent (what the guest app sees)
                        val componentName = intent.component
                            ?: android.content.ComponentName(
                                intent.`package` ?: "com.google.android.gms",
                                intent.action ?: "com.google.android.gms.IService"
                            )

                        // Post IServiceConnection.connected() on the main Handler to avoid
                        // re-entrancy issues inside the Binder thread
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                val iScClass = Class.forName("android.app.IServiceConnection")
                                val connectedMethod = iScClass.getDeclaredMethod(
                                    "connected",
                                    android.content.ComponentName::class.java,
                                    android.os.IBinder::class.java,
                                    Boolean::class.javaPrimitiveType
                                )
                                connectedMethod.invoke(serviceConnectionArg, componentName, proxiedBinder, false)
                                Timber.tag(TAG).i("GMS IServiceConnection.connected() delivered: ${intent.action}")
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "Failed to deliver GMS IBinder via IServiceConnection")
                            }
                        }

                        // Return 1 = BIND_SUCCEEDED
                        return 1
                    }

                    Timber.tag(TAG).w("GMS bridge: proxied binder available but IServiceConnection not found at args[4]")
                }
            }

            // No proxied binder (GMS not available on host) — let the real bind attempt proceed
            // with host package identity so GMS can at least attempt to service it
            return try {
                method.invoke(original, *newArgs)
            } catch (e: Exception) {
                val cause = e.cause ?: e
                if (cause is SecurityException) {
                    Timber.tag(TAG).w("GMS bindService SecurityException (expected in VM): ${cause.message}")
                    0
                } else {
                    throw e
                }
            }
        }

        // If the target service belongs to a virtual app, redirect to a stub service
        if (targetPkg != null && virtualApps.containsKey(targetPkg)) {
            val app = virtualApps[targetPkg]!!
            val targetService = intent.component?.className

            if (targetService != null) {
                Timber.tag(TAG).d("Intercepting bindService: $targetPkg/$targetService")

                val newIntent = Intent(intent).apply {
                    setClassName(hostPackage, getServiceStubForApp(app))
                    putExtra(VirtualIntentExtras.TARGET_PACKAGE, targetPkg)
                    putExtra(VirtualIntentExtras.TARGET_SERVICE, targetService)
                    putExtra(VirtualIntentExtras.INSTANCE_ID, app.instanceId)
                    putExtra(VirtualIntentExtras.APK_PATH, app.apkPath)
                }

                newArgs[intentIndex] = newIntent
                return try {
                    method.invoke(original, *newArgs)
                } catch (e: Exception) {
                    val cause = e.cause ?: e
                    if (cause is SecurityException && cause.message?.contains("has extras", ignoreCase = true) == true) {
                        Timber.tag(TAG).w("bindService 'has extras' SecurityException — retrying without extras")
                        // Strip all NEXTVM extras, keep only component routing
                        val strippedIntent = Intent().apply {
                            setClassName(hostPackage, getServiceStubForApp(app))
                        }
                        newArgs[intentIndex] = strippedIntent
                        try {
                            method.invoke(original, *newArgs)
                        } catch (e2: Exception) {
                            Timber.tag(TAG).w("bindService retry also failed: ${e2.message}")
                            0 // Return 0 = bind failed, caller handles gracefully
                        }
                    } else {
                        throw e
                    }
                }
            }
        }

        // Non-virtual service — still pass through with fixed callingPackage
        return try {
            method.invoke(original, *newArgs)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            if (cause is SecurityException) {
                Timber.tag(TAG).w("bindService SecurityException for ${intent.component}: ${cause.message}")
                0 // Return 0 = bind failed
            } else {
                throw e
            }
        }
    }

    private fun handleBroadcastIntent(method: Method, args: Array<out Any>?): Any? {
        if (args == null) return invokeOriginal(method, args)

        // Find the Intent argument
        val intentIndex = args.indexOfFirst { it is Intent }
        if (intentIndex < 0) return invokeOriginal(method, args)

        val intent = args[intentIndex] as Intent

        // Check if the sender is a virtual app (tagged by VirtualContext)
        val callerInstance = intent.getStringExtra("_nextvm_caller_instance")
        val callerPkg = intent.getStringExtra("_nextvm_caller_pkg")

        if (callerInstance != null && callerPkg != null) {
            // Tag the broadcast with virtual identity so the BroadcastManager
            // can route it to the correct virtual receivers
            intent.putExtra("_nextvm_broadcast_sender_instance", callerInstance)
            intent.putExtra("_nextvm_broadcast_sender_pkg", callerPkg)

            // Check if this is a broadcast targeting a specific virtual package
            val targetPkg = intent.component?.packageName ?: intent.`package`
            if (targetPkg != null && virtualApps.containsKey(targetPkg)) {
                // For explicit broadcasts to virtual apps, we need to deliver them
                // within the virtual environment instead of going through the real AMS
                Timber.tag(TAG).d("Virtual broadcast from $callerPkg to $targetPkg: ${intent.action}")
            }
        }

        // Let the broadcast proceed — virtual BroadcastManager handles delivery
        return invokeOriginal(method, args)
    }

    private fun handleGetContentProvider(method: Method, args: Array<out Any>?): Any? {
        if (args == null) return invokeOriginal(method, args)

        // Android 16 AIDL signature:
        // getContentProvider(IApplicationThread caller, String callingPackage,
        //                    String name, int userId, boolean stable)
        // args[0] = IApplicationThread caller
        // args[1] = callingPackage (String)
        // args[2] = authority name (String)
        // args[3] = userId (int)
        // args[4] = stable (boolean)

        // FIX: Replace callingPackage (args[1]) with host package.
        // The system server checks callingPackage against the caller's UID via
        // AppOpsService.checkPackage(callingUid, callingPackage). Since the guest
        // process runs under the host app's UID, callingPackage MUST be the host
        // package name — otherwise SecurityException is thrown.
        if (args.size >= 3 && args[1] is String) {
            val callingPackage = args[1] as String
            if (callingPackage != hostPackage) {
                Timber.tag(TAG).d("getContentProvider: replacing callingPackage '$callingPackage' → '$hostPackage'")
                val mutableArgs = args.toMutableList().toTypedArray()
                mutableArgs[1] = hostPackage
                return try {
                    invokeOriginal(method, mutableArgs)
                } catch (e: Exception) {
                    val cause = e.cause ?: e
                    if (cause is SecurityException) {
                        Timber.tag(TAG).w("getContentProvider SecurityException for authority '${args[2]}': ${cause.message}")
                        null
                    } else {
                        throw e
                    }
                }
            }
        }

        return try {
            invokeOriginal(method, args)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            if (cause is SecurityException) {
                Timber.tag(TAG).w("getContentProvider SecurityException for authority '${args.getOrNull(2)}': ${cause.message}")
                null
            } else {
                throw e
            }
        }
    }

    private fun handleGetRunningAppProcesses(method: Method, args: Array<out Any>?): Any? {
        // Get real process list from the system
        val result = invokeOriginal(method, args) ?: return null

        // Guest isMainProcess() often does:
        //   for (info : am.getRunningAppProcesses())
        //     if (info.pid == myPid()) return info.processName.equals(packageName)
        // Host stub processes are named "com.nextvm.app:pN" — rewrite to guest pkg.
        try {
            @Suppress("UNCHECKED_CAST")
            val processList = result as? List<Any> ?: return result
            val guestPkg = findCallingGuestPkg() ?: return result
            val myPid = android.os.Process.myPid()

            for (info in processList) {
                val pidField = findField(info.javaClass, "pid") ?: continue
                pidField.isAccessible = true
                val pid = pidField.get(info) as? Int ?: continue
                if (pid != myPid) continue

                val processNameField = findField(info.javaClass, "processName") ?: continue
                processNameField.isAccessible = true
                val oldName = processNameField.get(info) as? String
                if (oldName != guestPkg) {
                    processNameField.set(info, guestPkg)
                    Timber.tag(TAG).d(
                        "getRunningAppProcesses: pid=$myPid processName '$oldName' → '$guestPkg'"
                    )
                }

                // pkgList often contains host package; rewrite so detectors match guest
                val pkgListField = findField(info.javaClass, "pkgList")
                if (pkgListField != null) {
                    pkgListField.isAccessible = true
                    pkgListField.set(info, arrayOf(guestPkg))
                }
            }
            return result
        } catch (e: Exception) {
            Timber.tag(TAG).w("Error filtering process list: ${e.message}")
            return result
        }
    }

    /**
     * Handle getIntentSender / getIntentSenderWithFeature.
     *
     * AIDL signatures:
     *   getIntentSender(int type, String packageName, IBinder token, String resultWho,
     *                   int requestCode, Intent[] intents, String[] resolvedTypes,
     *                   int flags, Bundle bOptions, int userId)
     *
     *   getIntentSenderWithFeature(int type, String packageName, String featureId,
     *                              IBinder token, String resultWho, int requestCode,
     *                              Intent[] intents, String[] resolvedTypes,
     *                              int flags, Bundle bOptions, int userId)
     *
     * Android checks callingUid owns packageName (args[1]).
     * Guest apps run under NEXTVM's UID, so we replace the package name with hostPackage.
     */
    private fun handleGetIntentSender(method: Method, args: Array<out Any>?): Any? {
        if (args == null || args.size < 3) return invokeOriginal(method, args)

        val newArgs = args.clone() as Array<Any?>
        // args[1] is always the packageName in both variants
        if (args[1] is String && args[1] != hostPackage) {
            Timber.tag(TAG).d("getIntentSender: replacing packageName '${args[1]}' → '$hostPackage'")
            newArgs[1] = hostPackage
        }

        return try {
            method.invoke(original, *newArgs)
        } catch (e: Exception) {
            val cause = e.cause ?: e
            if (cause is SecurityException) {
                Timber.tag(TAG).w("getIntentSender SecurityException (swallowed): ${cause.message}")
                null
            } else {
                throw e
            }
        }
    }

    private fun getStubForApp(app: VirtualApp): String {
        val slot = app.processSlot
        return "${VirtualConstants.STUB_PREFIX}.P${slot}\$Standard00"
    }

    private fun getServiceStubForApp(app: VirtualApp): String {
        val slot = app.processSlot
        return "${VirtualConstants.STUB_PREFIX}.P${slot}\$S00"
    }

    /**
     * Intercept kill methods to prevent guest apps from killing the host process.
     *
     * Unity apps call Process.killProcess(myPid()) on activity destroy, which
     * directly sends SIGKILL via the kernel (can't be intercepted at Java level).
     * However, some apps use IActivityManager.killApplicationProcess() or
     * forceStopPackage() which goes through the binder — we can block those.
     *
     * For the direct Process.killProcess() case, we also install a shutdown hook
     * in the guest's Activity.onDestroy() to prevent the kill (see ATHook).
     */
    private fun handleKillProcess(method: Method, args: Array<out Any>?, methodName: String): Any? {
        // Check if the target package is a virtual app
        val targetPackage = args?.filterIsInstance<String>()?.firstOrNull()
        if (targetPackage != null && virtualApps.containsKey(targetPackage)) {
            Timber.tag(TAG).d("Blocked $methodName for virtual app: $targetPackage")
            // Return without calling the real AMS — don't actually kill the process
            return when (method.returnType) {
                Boolean::class.javaPrimitiveType -> true
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }

        // Also block kill attempts on the host package itself
        if (targetPackage == hostPackage) {
            Timber.tag(TAG).d("Blocked $methodName targeting host package")
            return when (method.returnType) {
                Boolean::class.javaPrimitiveType -> true
                Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }

        // Non-virtual target — pass through to real AMS
        return invokeOriginal(method, args)
    }

    private fun invokeOriginal(method: Method, args: Array<out Any>?): Any? {
        return if (args != null) method.invoke(original, *args)
        else method.invoke(original)
    }

    /**
     * Find the instanceId of the currently calling guest app.
     * Uses the current thread's context ClassLoader to identify which
     * virtual app instance is making the call.
     */
    private fun findCallingInstanceId(): String? {
        // Check all registered virtual apps — find which one is currently active
        // based on the current thread's context class loader
        val currentCL = Thread.currentThread().contextClassLoader
        for ((_, app) in virtualApps) {
            // The app is assigned to this process if it has a valid instanceId
            if (app.processSlot >= 0 && app.instanceId.isNotEmpty()) {
                return app.instanceId
            }
        }
        return virtualApps.values.firstOrNull()?.instanceId
    }

    /**
     * Find the package name of the currently calling guest app.
     * Prefer the spoofed process name (guest package) over map insertion order —
     * stub processes register ALL virtual apps during engine init.
     */
    private fun findCallingGuestPkg(): String? {
        val processName = try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.app.Application.getProcessName()
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
        if (processName != null && virtualApps.containsKey(processName)) {
            return processName
        }
        return virtualApps.values.firstOrNull { it.processSlot >= 0 }?.packageName
            ?: virtualApps.keys.firstOrNull()
    }
}
