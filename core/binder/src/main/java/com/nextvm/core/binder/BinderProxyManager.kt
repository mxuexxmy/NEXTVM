package com.nextvm.core.binder

import android.content.Context
import android.content.Intent
import com.nextvm.core.binder.proxy.ActivityManagerProxy
import com.nextvm.core.binder.proxy.ActivityTaskManagerProxy
import com.nextvm.core.binder.proxy.PackageManagerProxy
import com.nextvm.core.common.AndroidCompat
import com.nextvm.core.common.findField
import com.nextvm.core.model.GmsServiceRouter
import com.nextvm.core.model.VirtualApp
import timber.log.Timber
import java.lang.reflect.Proxy

/**
 * BinderProxyManager — Installs and manages all Binder IPC proxies.
 *
 * Based on Android 16 frameworks/base analysis:
 * - ActivityManager.IActivityManagerSingleton (Singleton<IActivityManager>)
 * - ActivityTaskManager.IActivityTaskManagerSingleton (Singleton<IActivityTaskManager>)
 * - ActivityThread.sPackageManager (static IPackageManager field)
 *
 * These proxies intercept system service calls from guest apps and
 * redirect them through our virtual engine instead of the real system.
 */
class BinderProxyManager(private val context: Context) {

    companion object {
        private const val TAG = "BinderProxy"
    }

    private var amProxy: ActivityManagerProxy? = null
    private var atmProxy: ActivityTaskManagerProxy? = null
    private var pmProxy: PackageManagerProxy? = null

    // Virtual app registry (package name -> VirtualApp)
    private val virtualApps = mutableMapOf<String, VirtualApp>()

    /**
     * Install all Binder proxies.
     * MUST be called after hidden API bypass.
     */
    fun installAllProxies() {
        Timber.tag(TAG).i("Installing Binder proxies...")

        installActivityManagerProxy()
        // ATM after AM — startActivity Intent rewrite is shared via amProxy.
        installActivityTaskManagerProxy()
        installPackageManagerProxy()

        Timber.tag(TAG).i("All Binder proxies installed")
    }

    /**
     * Register a virtual app in the proxy system.
     * After registration, system service calls for this package
     * will be intercepted and handled virtually.
     */
    fun registerVirtualApp(app: VirtualApp) {
        virtualApps[app.packageName] = app
        pmProxy?.registerApp(app)
        amProxy?.registerApp(app)
        Timber.tag(TAG).d("Registered virtual app: ${app.packageName}")
    }

    /**
     * Unregister a virtual app from the proxy system.
     */
    fun unregisterVirtualApp(packageName: String) {
        virtualApps.remove(packageName)
        pmProxy?.unregisterApp(packageName)
        amProxy?.unregisterApp(packageName)
        Timber.tag(TAG).d("Unregistered virtual app: $packageName")
    }

    /**
     * Check if a package is a virtual app.
     */
    fun isVirtualPackage(packageName: String): Boolean =
        virtualApps.containsKey(packageName)

    /**
     * Connect the GMS service router to the ActivityManagerProxy.
     * This enables GMS bindService/startService calls from guest apps
     * to be routed through the Hybrid GMS bridge.
     */
    fun setGmsRouter(router: GmsServiceRouter) {
        amProxy?.setGmsRouter(router)
        pmProxy?.setGmsRouter(router)
    }

    /**
     * Get theme resource ID for a specific activity from parsed manifest.
     */
    fun getActivityTheme(packageName: String, activityName: String): Int =
        pmProxy?.getActivityTheme(packageName, activityName) ?: 0

    // === IActivityManager Proxy ===

    /**
     * Install the IActivityManager proxy.
     *
     * Android 16 source (ActivityManager.java line 5787):
     * private static final Singleton<IActivityManager> IActivityManagerSingleton = new Singleton<>() {
     *     protected IActivityManager create() {
     *         final IBinder b = ServiceManager.getService(Context.ACTIVITY_SERVICE);
     *         return IActivityManager.Stub.asInterface(b);
     *     }
     * };
     *
     * We replace the mInstance field of this Singleton with our proxy.
     */
    private fun installActivityManagerProxy() {
        try {
            val fieldName = AndroidCompat.getIActivityManagerSingletonFieldName()
            val amClass = Class.forName("android.app.ActivityManager")
            val singletonField = amClass.getDeclaredField(fieldName)
            singletonField.isAccessible = true
            val singleton = singletonField.get(null)
                ?: throw IllegalStateException("IActivityManagerSingleton is null")

            val singletonClass = Class.forName("android.util.Singleton")
            val instanceField = singletonClass.getDeclaredField("mInstance")
            instanceField.isAccessible = true

            // Force singleton initialization
            val getMethod = singletonClass.getDeclaredMethod("get")
            getMethod.isAccessible = true
            val originalAm = getMethod.invoke(singleton)
                ?: throw IllegalStateException("IActivityManager original instance is null")

            // Create our proxy handler
            amProxy = ActivityManagerProxy(originalAm, context)

            // Create dynamic proxy
            val iamClass = Class.forName("android.app.IActivityManager")
            val proxy = Proxy.newProxyInstance(
                iamClass.classLoader,
                arrayOf(iamClass),
                amProxy!!
            )

            // Replace the singleton's mInstance with our proxy
            instanceField.set(singleton, proxy)

            Timber.tag(TAG).i("IActivityManager proxy installed")

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to install IActivityManager proxy")
        }
    }

    // === IActivityTaskManager Proxy ===

    /**
     * Install the IActivityTaskManager proxy.
     *
     * On Android 10+, [android.app.Activity.startActivity] and
     * [android.app.Instrumentation.execStartActivity] call
     * IActivityTaskManager.startActivity — not IActivityManager.
     * Without this proxy, guest Intents keep the guest ComponentName and the
     * real host ActivityManager resolves them to the host-installed app (if any),
     * causing SecurityException for non-exported activities.
     *
     * Hook point (AOSP): ActivityTaskManager.IActivityTaskManagerSingleton
     */
    private fun installActivityTaskManagerProxy() {
        val am = amProxy
        if (am == null) {
            Timber.tag(TAG).w("Skipping IActivityTaskManager proxy — IActivityManager not ready")
            return
        }
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val singletonField = atmClass.getDeclaredField("IActivityTaskManagerSingleton")
            singletonField.isAccessible = true
            val singleton = singletonField.get(null)
                ?: throw IllegalStateException("IActivityTaskManagerSingleton is null")

            val singletonClass = Class.forName("android.util.Singleton")
            val instanceField = singletonClass.getDeclaredField("mInstance")
            instanceField.isAccessible = true

            val getMethod = singletonClass.getDeclaredMethod("get")
            getMethod.isAccessible = true
            val originalAtm = getMethod.invoke(singleton)
                ?: throw IllegalStateException("IActivityTaskManager original instance is null")

            atmProxy = ActivityTaskManagerProxy(originalAtm, am)

            val iatmClass = Class.forName("android.app.IActivityTaskManager")
            val proxy = Proxy.newProxyInstance(
                iatmClass.classLoader,
                arrayOf(iatmClass),
                atmProxy!!
            )

            instanceField.set(singleton, proxy)

            // Also refresh ServiceManager cache if activity_task was already fetched.
            try {
                val smClass = Class.forName("android.os.ServiceManager")
                val cacheField = smClass.getDeclaredField("sCache")
                cacheField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val cache = cacheField.get(null) as? MutableMap<String, Any?>
                if (cache != null && cache.containsKey("activity_task")) {
                    // Keep raw Binder in ServiceManager; our Singleton mInstance is what
                    // ActivityTaskManager.getService() returns after init. No-op here —
                    // documented for awareness if future Android caches the stub interface.
                    Timber.tag(TAG).d("ServiceManager sCache has activity_task (Singleton override is authoritative)")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d("ServiceManager activity_task cache check skipped: ${e.message}")
            }

            Timber.tag(TAG).i("IActivityTaskManager proxy installed")
        } catch (e: ClassNotFoundException) {
            // Pre-Q devices may lack ActivityTaskManager — AM path is enough.
            Timber.tag(TAG).d("IActivityTaskManager not present on this API: ${e.message}")
        } catch (e: NoSuchFieldException) {
            Timber.tag(TAG).w(e, "IActivityTaskManagerSingleton field missing — ATM startActivity not hooked")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to install IActivityTaskManager proxy")
        }
    }

    // === IPackageManager Proxy ===

    /**
     * Install the IPackageManager proxy.
     *
     * Android 16 source (ActivityThread.java):
     * static volatile IPackageManager sPackageManager;
     * Also in ApplicationPackageManager.mPM instance field.
     */
    private fun installPackageManagerProxy() {
        try {
            // Method 1: Replace ActivityThread.sPackageManager
            val atClass = Class.forName("android.app.ActivityThread")
            val spmField = atClass.getDeclaredField("sPackageManager")
            spmField.isAccessible = true
            val originalPm = spmField.get(null)
                ?: run {
                    // Force initialization
                    val getMethod = atClass.getDeclaredMethod("getPackageManager")
                    getMethod.isAccessible = true
                    getMethod.invoke(null)
                    spmField.get(null)
                }
                ?: throw IllegalStateException("sPackageManager is null")

            // Create our proxy handler
            pmProxy = PackageManagerProxy(originalPm, context)

            // Create dynamic proxy
            val ipmClass = Class.forName("android.content.pm.IPackageManager")
            val proxy = Proxy.newProxyInstance(
                ipmClass.classLoader,
                arrayOf(ipmClass),
                pmProxy!!
            )

            // Replace static field
            spmField.set(null, proxy)

            // Method 2: Also try to replace ApplicationPackageManager.mPM
            try {
                val appPm = context.packageManager
                val mPmField = findField(appPm::class.java, "mPM")
                mPmField?.isAccessible = true
                mPmField?.set(appPm, proxy)
                Timber.tag(TAG).d("Also replaced ApplicationPackageManager.mPM")
            } catch (e: Exception) {
                Timber.tag(TAG).w("Could not replace ApplicationPackageManager.mPM: ${e.message}")
            }

            Timber.tag(TAG).i("IPackageManager proxy installed")

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to install IPackageManager proxy")
        }
    }

    /**
     * Rewrite a guest startActivity Intent onto a host stub activity.
     * Used by [com.nextvm.core.virtualization.engine.NextVmInstrumentation]
     * because Activity.startActivity goes through IActivityTaskManager on modern Android,
     * bypassing the IActivityManager Binder proxy.
     */
    fun rewriteOutgoingStartActivityIntent(intent: Intent): Boolean {
        return amProxy?.rewriteOutgoingStartActivityIntent(intent) ?: false
    }
}
