package com.nextvm.core.virtualization.lifecycle

import android.app.Application
import android.app.Instrumentation
import android.content.ContentProvider
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.res.Configuration
import android.os.Build
import com.nextvm.core.common.AndroidCompat
import com.nextvm.core.common.findField
import com.nextvm.core.common.findMethod
import com.nextvm.core.common.runSafe
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualApplicationManager — Manages guest app Application lifecycle.
 *
 * This is the MOST critical missing piece. It handles:
 * - Instantiating guest app's custom Application subclass via DexClassLoader
 * - Calling Application.attachBaseContext(virtualContext)
 * - Calling Application.onCreate()
 * - Managing Application lifecycle per guest app instance
 * - ContentProvider auto-init (AndroidX Startup, WorkManager, Firebase via ContentProvider.onCreate())
 * - AppComponentFactory support (Android 9+)
 * - handleBindApplication() mirror — simulating what real Android does when binding an app
 *
 * Mirrors the flow from:
 *   ActivityThread.handleBindApplication() in frameworks/base/core/java/android/app/ActivityThread.java
 *
 * Flow:
 *   1. Create LoadedApk equivalent (set up ClassLoader, Resources)
 *   2. Create AppComponentFactory (Android 9+)
 *   3. Instantiate Application class
 *   4. Install ContentProviders (BEFORE Application.onCreate)
 *   5. Call Application.attachBaseContext()
 *   6. Call Application.onCreate()
 */
@Singleton
class VirtualApplicationManager @Inject constructor() {

    companion object {
        private const val TAG = "VAppLifecycle"
    }

    private var initialized = false
    private lateinit var appContext: android.content.Context

    fun initialize(context: android.content.Context) {
        if (initialized) return
        appContext = context.applicationContext
        initialized = true
        Timber.tag(TAG).i("VirtualApplicationManager initialized — Application lifecycle ready")
    }

    /**
     * Holds state for a living guest Application.
     */
    data class ApplicationRecord(
        val instanceId: String,
        val packageName: String,
        val application: Application,
        val classLoader: ClassLoader,
        val applicationClassName: String?,
        val contentProviders: MutableList<ContentProvider> = mutableListOf(),
        val startTime: Long = System.currentTimeMillis(),
        var state: ApplicationState = ApplicationState.CREATED
    )

    enum class ApplicationState {
        CREATED,
        ATTACHED,
        PROVIDERS_INSTALLED,
        RUNNING,
        TERMINATED
    }

    // instanceId -> ApplicationRecord
    private val applications = ConcurrentHashMap<String, ApplicationRecord>()

    /**
     * Full bind-application flow — mirrors ActivityThread.handleBindApplication().
     *
     * Order (from real Android source):
     *   1. Set up ApplicationInfo
     *   2. Create ClassLoader (already done by VirtualEngine)
     *   3. Set up Resources (handled by VirtualResourceManager)
     *   4. Create Instrumentation
     *   5. Create Application instance via AppComponentFactory or reflection
     *   6. Install ContentProviders (BEFORE onCreate!)
     *   7. Call Instrumentation.callApplicationOnCreate()
     *
     * @param instanceId Unique instance identifier
     * @param packageName Guest app package name
     * @param applicationClassName Fully qualified Application class (null = android.app.Application)
     * @param apkPath Path to the guest APK
     * @param classLoader DexClassLoader for the guest app
     * @param virtualContext Sandboxed context wrapping the guest identity
     * @param providerNames List of ContentProvider class names to initialize
     * @param providerAuthorities Map of provider class name -> authority string
     * @return The created Application or null on failure
     */
    fun bindApplication(
        instanceId: String,
        packageName: String,
        applicationClassName: String?,
        apkPath: String,
        classLoader: ClassLoader,
        virtualContext: Context,
        providerNames: List<String> = emptyList(),
        providerAuthorities: Map<String, String> = emptyMap()
    ): Application? {
        // Check if already bound
        val existing = applications[instanceId]
        if (existing != null && existing.state != ApplicationState.TERMINATED) {
            Timber.tag(TAG).d("Application already bound for $instanceId, returning existing")
            return existing.application
        }

        Timber.tag(TAG).i("bindApplication: $packageName ($instanceId)")
        Timber.tag(TAG).d("  applicationClass: ${applicationClassName ?: "android.app.Application"}")
        Timber.tag(TAG).d("  providers: ${providerNames.size}")

        try {
            // Step 1: Prepare ApplicationInfo on the virtual context
            val appInfo = buildApplicationInfo(packageName, apkPath, virtualContext)

            // Step 2: Create Application instance
            val application = createApplication(
                instanceId = instanceId,
                packageName = packageName,
                applicationClassName = applicationClassName,
                classLoader = classLoader,
                virtualContext = virtualContext,
                appInfo = appInfo
            ) ?: return null

            // Step 3: Create ApplicationRecord
            val record = ApplicationRecord(
                instanceId = instanceId,
                packageName = packageName,
                application = application,
                classLoader = classLoader,
                applicationClassName = applicationClassName
            )
            applications[instanceId] = record

            // Step 3.5: Set the guest Application on VirtualContext so that
            // getApplicationContext() returns the real Application instance.
            // This is CRITICAL for AndroidX libraries (ProcessLifecycleInitializer,
            // WorkManager, Firebase) which cast getApplicationContext() to Application.
            if (virtualContext is com.nextvm.core.sandbox.VirtualContext) {
                virtualContext.setGuestApplication(application)
            }

            // Step 3.6: Spoof process identity BEFORE attachBaseContext/onCreate.
            // Guest apps commonly gate EasyHttp / SDK init with isMainProcess():
            //   packageName.equals(Application.getProcessName())
            // Without this, host process "com.nextvm.app:p0" fails the check and
            // network stacks throw "请先调用 EasyHttp.init()".
            spoofGuestProcessIdentity(packageName, application)

            // Step 4: Attach base context
            attachBaseContext(application, virtualContext)
            record.state = ApplicationState.ATTACHED
            Timber.tag(TAG).d("Application.attachBaseContext() complete")

            // Step 4.5: Patch LoadedApk.mApplication to the guest Application.
            // ContextImpl.getApplicationContext() returns LoadedApk.getApplication()
            // which normally returns the HOST Application. By patching mApplication,
            // even code that bypasses our ContextWrapper chain (e.g., framework-internal
            // calls to ContextImpl directly) will get the guest Application.
            patchLoadedApkApplication(virtualContext, application)

            // Step 5: Install ContentProviders BEFORE Application.onCreate()
            // This is critical — AndroidX Startup, WorkManager, Firebase all use
            // ContentProviders that must be initialized before the Application.
            if (providerNames.isNotEmpty()) {
                initContentProviders(
                    instanceId = instanceId,
                    providers = providerNames,
                    authorities = providerAuthorities,
                    classLoader = classLoader,
                    virtualContext = virtualContext,
                    application = application,
                    apkPath = apkPath
                )
                record.state = ApplicationState.PROVIDERS_INSTALLED
                Timber.tag(TAG).d("ContentProviders installed: ${providerNames.size}")
            }

            // Step 6: Call Application.onCreate()
            callApplicationOnCreate(application)
            record.state = ApplicationState.RUNNING
            Timber.tag(TAG).i("Application.onCreate() complete for $packageName")

            return application

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to bind application for $packageName ($instanceId)")
            applications.remove(instanceId)
            return null
        }
    }

    /**
     * Create an Application instance using the guest app's ClassLoader.
     *
     * Mirrors the logic in:
     *   ActivityThread.java → LoadedApk.makeApplicationInner()
     *   AppComponentFactory.instantiateApplication() (Android 9+)
     */
    fun createApplication(
        instanceId: String,
        packageName: String,
        applicationClassName: String?,
        classLoader: ClassLoader,
        virtualContext: Context,
        appInfo: ApplicationInfo? = null
    ): Application? {
        val className = applicationClassName ?: "android.app.Application"

        Timber.tag(TAG).d("Creating Application: $className for $packageName")

        return try {
            // Try AppComponentFactory on Android 9+
            if (AndroidCompat.isAtLeastP) {
                val factoryApp = createViaAppComponentFactory(className, classLoader)
                if (factoryApp != null) {
                    Timber.tag(TAG).d("Application created via AppComponentFactory")
                    return factoryApp
                }
            }

            // Standard reflection path
            val appClass = if (className == "android.app.Application") {
                Application::class.java
            } else {
                classLoader.loadClass(className)
            }

            val app = appClass.getDeclaredConstructor().let { ctor ->
                ctor.isAccessible = true
                ctor.newInstance()
            } as Application

            Timber.tag(TAG).d("Application created via reflection: $className")
            app

        } catch (e: ClassNotFoundException) {
            Timber.tag(TAG).w("Application class not found: $className, falling back to base Application")
            try {
                Application::class.java.getDeclaredConstructor().newInstance()
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "Failed to create fallback Application")
                null
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to create Application: $className")
            null
        }
    }

    /**
     * Try to create Application via AppComponentFactory (Android 9+).
     *
     * Real Android uses AppComponentFactory to allow apps to customize
     * instantiation of Application, Activity, Service, etc.
     */
    private fun createViaAppComponentFactory(
        className: String,
        classLoader: ClassLoader
    ): Application? {
        return runSafe(TAG) {
            val factoryClass = Class.forName("android.app.AppComponentFactory")
            val factory = factoryClass.getDeclaredConstructor().newInstance()

            val instantiateMethod = findMethod(
                factoryClass,
                "instantiateApplication",
                arrayOf(ClassLoader::class.java, String::class.java)
            )
            instantiateMethod?.isAccessible = true
            instantiateMethod?.invoke(factory, classLoader, className) as? Application
        }
    }

    /**
     * Attach base context to Application.
     *
     * This calls Application.attach(context), which internally calls:
     *   - ContextWrapper.attachBaseContext(context)
     *   - Application.attachBaseContext(context) [overridable by app]
     *
     * We use the hidden Application.attach() method rather than just
     * attachBaseContext() because attach() also sets up internal fields
     * like mLoadedApk.
     */
    private fun attachBaseContext(application: Application, virtualContext: Context) {
        // CRITICAL: Before Application.attach(), inject guest app paths into the
        // LoadedApk that the ContextImpl holds. Application.attach() does:
        //   mLoadedApk = ContextImpl.getImpl(context).mPackageInfo
        // If we don't patch this, the LoadedApk has HOST paths (nativeLibraryDir etc.),
        // and when the guest app's attachBaseContext() calls System.load(), the runtime
        // looks up nativeLibraryDir from the LoadedApk's ApplicationInfo and fails.
        injectGuestPathsIntoLoadedApk(virtualContext)

        // FIX 1: Disable CheckJNI before guest Application.attach().
        // Jiagu/360-protected apps use reflection to call Runtime.load0(Class, String)
        // directly. In the virtual environment, the 'Class caller' argument becomes null
        // because the call originates from code loaded via custom ClassLoader/reflection.
        // CheckJNI catches the null caller's protection domains array (jarray == NULL)
        // in GetObjectArrayElement and triggers SIGABRT. Disabling CheckJNI prevents
        // this hard-abort — the null caller is harmless for actual library loading.
        disableCheckJniForGuest()

        // FIX 2: Patch Jiagu/packer stub Application fields before attach.
        // Packers like Jiagu store native lib extraction paths in static/instance String
        // fields. In the virtual environment these may be null, causing load failures.
        patchJiaguStubBeforeAttach(application, virtualContext)

        // FIX 3: Install Runtime.load0 caller fix to handle null caller class.
        // Pass the guest ClassLoader explicitly — android.app.Application is loaded by
        // BootClassLoader, so application.javaClass.classLoader is wrong for Flutter/JNI.
        val guestCl = when (virtualContext) {
            is com.nextvm.core.sandbox.VirtualContext -> virtualContext.classLoader
            else -> null
        } ?: application.classLoader
        installRuntimeLoadCallerFix(application, virtualContext, guestCl)

        try {
            // Try the full attach() method first (mirrors real Android flow)
            val attachMethod = findMethod(
                Application::class.java,
                "attach",
                arrayOf(Context::class.java)
            )
            if (attachMethod != null) {
                attachMethod.isAccessible = true
                attachMethod.invoke(application, virtualContext)
                Timber.tag(TAG).d("Used Application.attach(context)")
                return
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Application.attach() failed, using attachBaseContext fallback")
        }

        // Fallback: directly call attachBaseContext via reflection
        try {
            val attachBaseMethod = findMethod(
                android.content.ContextWrapper::class.java,
                "attachBaseContext",
                arrayOf(Context::class.java)
            )
            attachBaseMethod?.isAccessible = true
            attachBaseMethod?.invoke(application, virtualContext)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "attachBaseContext fallback also failed")
            // Last resort: set mBase field directly
            try {
                val mBaseField = findField(android.content.ContextWrapper::class.java, "mBase")
                mBaseField?.isAccessible = true
                mBaseField?.set(application, virtualContext)
                Timber.tag(TAG).d("Set mBase field directly as last resort")
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "All attach methods failed")
                throw RuntimeException("Cannot attach context to Application", e2)
            }
        }
    }

    /**
     * FIX 1: Disable CheckJNI in the current (guest) process.
     *
     * NOTE: VMRuntime.setCheckJni(boolean) does NOT exist on Android API 35+.
     * This method is kept as a best-effort attempt for older API levels.
     * The real fix for null-caller SIGABRT is the native Runtime.nativeLoad hook
     * installed by NativeHookBridge.hookRuntimeNativeLoad().
     */
    private fun disableCheckJniForGuest() {
        if (Build.VERSION.SDK_INT >= 35) {
            Timber.tag(TAG).d("Skipping disableCheckJniForGuest — VMRuntime.setCheckJni not available on API 35+")
            return
        }
        try {
            val vmRuntimeClass = Class.forName("dalvik.system.VMRuntime")
            val getRuntimeMethod = vmRuntimeClass.getDeclaredMethod("getRuntime")
            getRuntimeMethod.isAccessible = true
            val vmRuntime = getRuntimeMethod.invoke(null)

            val setCheckJniMethod = vmRuntimeClass.getDeclaredMethod(
                "setCheckJni", Boolean::class.javaPrimitiveType
            )
            setCheckJniMethod.isAccessible = true
            setCheckJniMethod.invoke(vmRuntime, false)
            Timber.tag(TAG).i("CheckJNI disabled for guest process")
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to disable CheckJNI: ${e.message} (non-fatal — native hook handles this)")
        }
    }

    /**
     * FIX 2: Patch Jiagu/packer stub Application fields before attach.
     *
     * Packers like Jiagu (com.stub.StubApp) store native library extraction paths
     * and data directory paths in static/instance String fields. In the virtual
     * environment, these fields may be null because the packer's initialization
     * code hasn't run yet, or the paths it computed are wrong. This causes the
     * packer's attachBaseContext() to pass null/invalid paths to System.load().
     *
     * We scan the stub Application class for null String fields and set them to
     * the guest app's nativeLibraryDir, which is the most commonly needed path.
     */
    private fun patchJiaguStubBeforeAttach(application: Application, virtualContext: Context) {
        try {
            val appClass = application.javaClass
            val className = appClass.name

            // Only patch if it looks like a packer stub class
            if (!className.contains("stub", ignoreCase = true) &&
                !className.contains("jiagu", ignoreCase = true) &&
                !className.contains("wrapper", ignoreCase = true) &&
                !className.contains("Shell", ignoreCase = true) &&
                !className.contains("Qihoo", ignoreCase = true)
            ) {
                return
            }

            Timber.tag(TAG).d("Detected packer stub class: $className — patching fields")

            val nativeLibDir = virtualContext.applicationInfo?.nativeLibraryDir ?: return
            val dataDir = virtualContext.applicationInfo?.dataDir ?: return
            val sourceDir = virtualContext.applicationInfo?.sourceDir ?: return

            // Patch instance fields
            for (field in appClass.declaredFields) {
                try {
                    field.isAccessible = true
                    if (field.type == String::class.java) {
                        val value = field.get(application) as? String
                        if (value == null) {
                            // Null String field — likely a path that needs to be set
                            field.set(application, nativeLibDir)
                            Timber.tag(TAG).d("  Patched null field: ${field.name} → $nativeLibDir")
                        } else if (value.contains("/data/data/") &&
                            !value.contains("com.nextvm.app")
                        ) {
                            // Contains a real package data path — redirect to virtual sandbox
                            val redirected = value.replace(
                                Regex("/data/(data|user/0)/[^/]+"),
                                dataDir
                            )
                            if (redirected != value) {
                                field.set(application, redirected)
                                Timber.tag(TAG).d("  Redirected field: ${field.name} → $redirected")
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Skip inaccessible fields
                }
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "patchJiaguStubBeforeAttach failed (non-fatal)")
        }
    }

    /**
     * FIX 3: Install a caller-class fix for Runtime.load0 / Runtime.nativeLoad.
     *
     * When Jiagu calls Runtime.load0(Class<?> callerClass, String filename) via
     * reflection, callerClass can be null in the virtual environment. The ART runtime's
     * nativeLoad implementation uses the caller's ClassLoader to determine the linker
     * namespace, and a null caller causes CheckJNI to abort when accessing the caller's
     * protection domains array.
     *
     * This method:
     * 1. Sets Thread contextClassLoader to the guest's ClassLoader
     * 2. Ensures the guest Application class has a valid ProtectionDomain with
     *    CodeSource pointing to the guest APK path
     * 3. The actual JNI-level fix is in NativeHookBridge.hookRuntimeNativeLoad()
     */
    private fun installRuntimeLoadCallerFix(
        application: Application,
        virtualContext: Context,
        guestClassLoader: ClassLoader?
    ) {
        try {
            // Prefer the guest Dex/Path ClassLoader, never BootClassLoader from
            // framework android.app.Application.
            val cl = guestClassLoader?.takeUnless {
                it.javaClass.name.contains("BootClassLoader")
            } ?: application.javaClass.classLoader?.takeUnless {
                it.javaClass.name.contains("BootClassLoader")
            }

            if (cl != null) {
                Thread.currentThread().contextClassLoader = cl
                Timber.tag(TAG).d("Set thread contextClassLoader to guest ClassLoader: ${cl.javaClass.name}")
            } else {
                Timber.tag(TAG).w("No guest ClassLoader available for contextClassLoader")
            }

            // Ensure the guest's Application class has a valid ProtectionDomain
            // with a CodeSource pointing to the guest APK. This prevents NPE/SIGABRT
            // when ART/CheckJNI tries to access the caller's ProtectionDomain array.
            try {
                val pd = application.javaClass.protectionDomain
                if (pd == null || pd.codeSource == null) {
                    // Inject a valid ProtectionDomain with CodeSource pointing to APK
                    val apkPath = virtualContext.applicationInfo?.sourceDir
                    if (apkPath != null) {
                        val codeSource = java.security.CodeSource(
                            java.io.File(apkPath).toURI().toURL(),
                            arrayOfNulls<java.security.cert.Certificate>(0)
                        )
                        val newPd = java.security.ProtectionDomain(codeSource, null)
                        // Set via reflection on the Class object
                        val pdField = findField(Class::class.java, "protectionDomain")
                            ?: findField(Class::class.java, "pd")
                        if (pdField != null) {
                            pdField.isAccessible = true
                            pdField.set(application.javaClass, newPd)
                            Timber.tag(TAG).d("Injected ProtectionDomain with CodeSource: $apkPath")
                        }
                    }
                } else {
                    Timber.tag(TAG).d("Guest Application ProtectionDomain already valid: $pd")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).d("ProtectionDomain fix: ${e.message} (non-fatal)")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "installRuntimeLoadCallerFix failed (non-fatal)")
        }
    }

    /**
     * Inject guest app paths into the LoadedApk's ApplicationInfo.
     *
     * When Application.attach(context) is called, it sets:
     *   mLoadedApk = ContextImpl.getImpl(context).mPackageInfo
     *
     * Since VirtualContext wraps the HOST ContextImpl, mPackageInfo is the HOST's
     * LoadedApk with HOST paths. We patch its mApplicationInfo to have guest paths
     * so that Runtime.nativeLoad() and other framework code finds the right
     * nativeLibraryDir, sourceDir, and dataDir.
     */
    private fun injectGuestPathsIntoLoadedApk(virtualContext: Context) {
        try {
            // Walk the ContextWrapper chain to find the underlying ContextImpl
            var ctx: Context = virtualContext
            while (ctx is android.content.ContextWrapper) {
                val base = ctx.baseContext ?: break
                ctx = base
            }

            // Get mPackageInfo (LoadedApk) from ContextImpl
            val mPackageInfoField = findField(ctx::class.java, "mPackageInfo")
            mPackageInfoField?.isAccessible = true
            val loadedApk = mPackageInfoField?.get(ctx) ?: run {
                Timber.tag(TAG).w("Could not find mPackageInfo on ContextImpl")
                return
            }

            // Get mApplicationInfo from LoadedApk
            val appInfoField = findField(loadedApk::class.java, "mApplicationInfo")
            appInfoField?.isAccessible = true
            val loadedApkAppInfo = appInfoField?.get(loadedApk) as? ApplicationInfo ?: run {
                Timber.tag(TAG).w("Could not find mApplicationInfo on LoadedApk")
                return
            }

            // Read guest paths from VirtualContext.getApplicationInfo()
            val guestAppInfo = virtualContext.applicationInfo

            // Patch the LoadedApk's ApplicationInfo with guest paths
            loadedApkAppInfo.nativeLibraryDir = guestAppInfo.nativeLibraryDir
            loadedApkAppInfo.sourceDir = guestAppInfo.sourceDir
            loadedApkAppInfo.publicSourceDir = guestAppInfo.sourceDir
            loadedApkAppInfo.dataDir = guestAppInfo.dataDir

            // CRITICAL: Also patch LoadedApk.mLibDir which is used by
            // Runtime.nativeLoad() to construct the library search path.
            // Without this, the linker namespace has wrong paths and .so loading fails.
            val mLibDirField = findField(loadedApk::class.java, "mLibDir")
            if (mLibDirField != null) {
                mLibDirField.isAccessible = true
                mLibDirField.set(loadedApk, guestAppInfo.nativeLibraryDir)
                Timber.tag(TAG).d("Injected mLibDir into LoadedApk: ${guestAppInfo.nativeLibraryDir}")
            }

            Timber.tag(TAG).d(
                "Injected guest paths into LoadedApk: nativeLibDir=${loadedApkAppInfo.nativeLibraryDir}"
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to inject guest paths into LoadedApk (non-fatal)")
        }
    }

    /**
     * Patch LoadedApk.mApplication to point to the guest Application.
     *
     * Without this, ContextImpl.getApplicationContext() returns the HOST Application
     * (set when the process started). Framework code that calls getApplicationContext()
     * directly on ContextImpl (bypassing our ContextWrapper) would get NextVmApplication
     * instead of the guest Application — causing Hilt/Dagger cast failures and GMS
     * identity mismatches.
     */
    private fun patchLoadedApkApplication(virtualContext: Context, guestApplication: Application) {
        try {
            // Walk the ContextWrapper chain to find the underlying ContextImpl
            var ctx: Context = virtualContext
            while (ctx is android.content.ContextWrapper) {
                val base = ctx.baseContext ?: break
                ctx = base
            }

            // Get mPackageInfo (LoadedApk) from ContextImpl
            val mPackageInfoField = findField(ctx::class.java, "mPackageInfo")
            mPackageInfoField?.isAccessible = true
            val loadedApk = mPackageInfoField?.get(ctx) ?: run {
                Timber.tag(TAG).w("Could not find mPackageInfo for mApplication patch")
                return
            }

            // Patch LoadedApk.mApplication
            val mApplicationField = findField(loadedApk::class.java, "mApplication")
            if (mApplicationField != null) {
                mApplicationField.isAccessible = true
                mApplicationField.set(loadedApk, guestApplication)
                Timber.tag(TAG).d("Patched LoadedApk.mApplication to guest Application")
            } else {
                Timber.tag(TAG).w("Could not find mApplication field on LoadedApk")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to patch LoadedApk.mApplication (non-fatal)")
        }
    }

    /**
     * Initialize ContentProviders before Application.onCreate().
     *
     * This mirrors ActivityThread.installContentProviders() which is called
     * BEFORE Instrumentation.callApplicationOnCreate().
     *
     * Critical providers that depend on this ordering:
     * - androidx.startup.InitializationProvider (AndroidX Startup)
     * - androidx.work.impl.WorkManagerInitializer (WorkManager)
     * - com.google.firebase.provider.FirebaseInitProvider (Firebase)
     * - androidx.lifecycle.ProcessLifecycleOwnerInitializer (Lifecycle)
     */
    fun initContentProviders(
        instanceId: String,
        providers: List<String>,
        authorities: Map<String, String>,
        classLoader: ClassLoader,
        virtualContext: Context,
        application: Application,
        apkPath: String? = null
    ) {
        Timber.tag(TAG).d("Installing ${providers.size} ContentProviders for $instanceId")

        val record = applications[instanceId]
        val archiveProviders = loadProviderInfosFromApk(
            apkPath = apkPath,
            packageName = application.packageName,
            context = virtualContext
        )

        for (providerClassName in providers) {
            // Skip ShizukuProvider — requires INTERACT_ACROSS_USERS_FULL which
            // is not available in the virtual environment. Attempting to install
            // it causes SecurityException in ContentResolver.call().
            val providerAuthority = authorities[providerClassName] ?: ""
            if (providerClassName.contains("shizuku", ignoreCase = true) ||
                providerClassName.contains("rikka.shizuku", ignoreCase = true) ||
                providerAuthority.contains("shizuku", ignoreCase = true)
            ) {
                Timber.tag(TAG).d("Skipping ShizukuProvider: $providerClassName — cross-user permission unavailable")
                continue
            }

            try {
                val providerClass = classLoader.loadClass(providerClassName)
                val provider = providerClass.getDeclaredConstructor().let { ctor ->
                    ctor.isAccessible = true
                    ctor.newInstance()
                } as ContentProvider

                val archiveInfo = archiveProviders[providerClassName]
                val resolvedAuthority = archiveInfo?.authority?.split(';')?.firstOrNull()?.trim()
                    ?: providerAuthority.ifEmpty {
                        "${virtualContext.packageName}.${providerClassName.substringAfterLast(".")}"
                    }

                // Build ProviderInfo for attachInfo() — copy meta-data from the
                // guest APK so FileProvider / AndroidX Startup can read paths & initializers.
                val providerInfo = ProviderInfo().apply {
                    name = providerClassName
                    packageName = application.packageName
                    authority = resolvedAuthority
                    applicationInfo = archiveInfo?.applicationInfo ?: virtualContext.applicationInfo
                    exported = archiveInfo?.exported ?: false
                    enabled = true
                    grantUriPermissions = archiveInfo?.grantUriPermissions ?: true
                    readPermission = archiveInfo?.readPermission
                    writePermission = archiveInfo?.writePermission
                    multiprocess = archiveInfo?.multiprocess ?: false
                    metaData = archiveInfo?.metaData
                }

                // Call ContentProvider.attachInfo(context, info)
                // This calls both attachInfo internally and then onCreate()
                provider.attachInfo(virtualContext, providerInfo)

                record?.contentProviders?.add(provider)
                Timber.tag(TAG).d(
                    "  Provider installed: $providerClassName " +
                        "(authority=$resolvedAuthority, meta=${providerInfo.metaData != null})"
                )

            } catch (e: ClassNotFoundException) {
                Timber.tag(TAG).w("ContentProvider class not found: $providerClassName (skipping)")
            } catch (e: SecurityException) {
                Timber.tag(TAG).w("SecurityException skipping provider $providerClassName: ${e.message}")
            } catch (e: IllegalStateException) {
                Timber.tag(TAG).w("IllegalStateException skipping provider $providerClassName: ${e.message}")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to install ContentProvider: $providerClassName")
                // Don't fail the whole app — some providers are optional
            }
        }
    }

    /**
     * Load ProviderInfo (including meta-data) from the guest APK archive.
     */
    private fun loadProviderInfosFromApk(
        apkPath: String?,
        packageName: String,
        context: Context
    ): Map<String, ProviderInfo> {
        if (apkPath.isNullOrEmpty()) return emptyMap()
        return try {
            val flags = PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA
            val pkgInfo = context.packageManager.getPackageArchiveInfo(apkPath, flags)
                ?: return emptyMap()
            pkgInfo.applicationInfo?.let { ai ->
                ai.sourceDir = apkPath
                ai.publicSourceDir = apkPath
                ai.packageName = packageName
            }
            pkgInfo.providers?.associate { pi ->
                pi.packageName = packageName
                pi.applicationInfo = pkgInfo.applicationInfo
                pi.name to pi
            } ?: emptyMap()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "loadProviderInfosFromApk failed for $apkPath")
            emptyMap()
        }
    }

    /**
     * Make the current host stub process look like the guest's main process.
     *
     * Spoofs:
     * - ActivityThread.sCurrentProcessName (Application.getProcessName / Process.myProcessName)
     * - ActivityThread.mBoundApplication.processName
     * - ActivityThread.mInitialApplication (+ mAllApplications)
     */
    private fun spoofGuestProcessIdentity(packageName: String, application: Application) {
        try {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAT = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            if (currentAT == null) {
                Timber.tag(TAG).w("spoofGuestProcessIdentity: ActivityThread is null")
                return
            }

            // Application.getProcessName() / Process.myProcessName() (API 28+)
            findField(atClass, "sCurrentProcessName")?.let { field ->
                field.isAccessible = true
                field.set(null, packageName)
                Timber.tag(TAG).d("sCurrentProcessName → $packageName")
            }

            // Legacy path: mBoundApplication.processName
            findField(atClass, "mBoundApplication")?.let { field ->
                field.isAccessible = true
                val boundApp = field.get(currentAT)
                if (boundApp != null) {
                    findField(boundApp.javaClass, "processName")?.let { pnField ->
                        pnField.isAccessible = true
                        pnField.set(boundApp, packageName)
                        Timber.tag(TAG).d("mBoundApplication.processName → $packageName")
                    }
                }
            }

            // Libraries that use ActivityThread.currentApplication()
            findField(atClass, "mInitialApplication")?.let { field ->
                field.isAccessible = true
                field.set(currentAT, application)
                Timber.tag(TAG).d("mInitialApplication → ${application.javaClass.name}")
            }

            findField(atClass, "mAllApplications")?.let { field ->
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val apps = field.get(currentAT) as? MutableList<Application>
                if (apps != null && !apps.contains(application)) {
                    apps.add(application)
                }
            }

            Timber.tag(TAG).i("Guest process identity spoofed as $packageName")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "spoofGuestProcessIdentity failed (non-fatal)")
        }
    }

    /**
     * Call Application.onCreate() using Instrumentation pattern.
     *
     * Mirrors:
     *   Instrumentation.callApplicationOnCreate(app) in ActivityThread.handleBindApplication()
     */
    private fun callApplicationOnCreate(application: Application) {
        try {
            // Use Instrumentation if available (more accurate mirroring)
            val instrumentation = Instrumentation()
            instrumentation.callApplicationOnCreate(application)
            Timber.tag(TAG).d("Application.onCreate() called via Instrumentation")
        } catch (e: RuntimeException) {
            // Check if this is a ShizukuProvider SecurityException — swallow it.
            // ZArchiver calls ShizukuProvider.a() in onCreate() which triggers
            // ContentResolver.call() to rikka.shizuku.ShizukuProvider. The system
            // throws SecurityException because INTERACT_ACROSS_USERS_FULL is missing.
            // ZArchiver can run without Shizuku root access.
            val cause = e.cause?.cause ?: e.cause ?: e
            if (cause is SecurityException &&
                (cause.message?.contains("ShizukuProvider", ignoreCase = true) == true ||
                 cause.message?.contains("INTERACT_ACROSS_USERS", ignoreCase = true) == true ||
                 cause.message?.contains("shizuku", ignoreCase = true) == true)
            ) {
                Timber.tag(TAG).w("Shizuku SecurityException in onCreate swallowed — continuing without Shizuku")
                return
            }
            // Not Shizuku-related — try direct call fallback
            Timber.tag(TAG).w(e, "Instrumentation.callApplicationOnCreate() failed, trying direct")
            try {
                application.onCreate()
            } catch (e2: RuntimeException) {
                val cause2 = e2.cause?.cause ?: e2.cause ?: e2
                if (cause2 is SecurityException &&
                    (cause2.message?.contains("ShizukuProvider", ignoreCase = true) == true ||
                     cause2.message?.contains("INTERACT_ACROSS_USERS", ignoreCase = true) == true ||
                     cause2.message?.contains("shizuku", ignoreCase = true) == true)
                ) {
                    Timber.tag(TAG).w("Shizuku SecurityException in direct onCreate swallowed")
                    return
                }
                Timber.tag(TAG).e(e2, "Application.onCreate() direct call also failed")
                throw e2
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "Application.onCreate() direct call also failed")
                throw e2
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Instrumentation.callApplicationOnCreate() failed, calling directly")
            try {
                application.onCreate()
            } catch (e2: Exception) {
                Timber.tag(TAG).e(e2, "Application.onCreate() direct call also failed")
                throw e2
            }
        }
    }

    /**
     * Build an ApplicationInfo suitable for the guest app.
     */
    private fun buildApplicationInfo(
        packageName: String,
        apkPath: String,
        virtualContext: Context
    ): ApplicationInfo {
        return ApplicationInfo().apply {
            this.packageName = packageName
            this.sourceDir = apkPath
            this.publicSourceDir = apkPath
            this.dataDir = virtualContext.dataDir.absolutePath
            this.nativeLibraryDir = virtualContext.applicationInfo.nativeLibraryDir
            this.processName = packageName
            this.enabled = true
            this.flags = ApplicationInfo.FLAG_HAS_CODE or ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA
            // longVersionCode defaults to 0 on new ApplicationInfo

            // Populate metaData from the guest APK so SDKs (AppLovin, Firebase,
            // Facebook, CronetProvider, etc.) can read their <meta-data> tags.
            // Also inject essential GMS meta-data if not already present.
            try {
                val pm = virtualContext.packageManager
                val pkgInfo = pm.getPackageArchiveInfo(
                    apkPath,
                    android.content.pm.PackageManager.GET_META_DATA
                )
                pkgInfo?.applicationInfo?.let { it2 ->
                    it2.sourceDir = apkPath
                    it2.publicSourceDir = apkPath
                }
                val bundle = pkgInfo?.applicationInfo?.metaData ?: android.os.Bundle()

                // Inject GMS version meta-data if the guest APK doesn't already have it.
                // Many SDKs (AppLovin, Firebase Analytics, CronetProvider) crash without this.
                if (!bundle.containsKey("com.google.android.gms.version")) {
                    bundle.putInt("com.google.android.gms.version", 243431006)
                }

                this.metaData = bundle
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to parse metaData from APK: $apkPath")
                // At minimum provide GMS version to avoid common SDK crashes
                this.metaData = android.os.Bundle().apply {
                    putInt("com.google.android.gms.version", 243431006)
                }
            }
        }
    }

    /**
     * Terminate a guest app's Application instance.
     * Calls Application.onTerminate() and cleans up all ContentProviders.
     */
    fun terminateApplication(instanceId: String) {
        val record = applications[instanceId] ?: run {
            Timber.tag(TAG).w("No Application record found for $instanceId")
            return
        }

        Timber.tag(TAG).i("Terminating application for ${record.packageName} ($instanceId)")

        try {
            // Shut down ContentProviders first
            for (provider in record.contentProviders) {
                try {
                    provider.shutdown()
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Error shutting down provider: ${provider.javaClass.name}")
                }
            }
            record.contentProviders.clear()

            // Call Application.onTerminate()
            try {
                record.application.onTerminate()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Application.onTerminate() threw exception")
            }

            record.state = ApplicationState.TERMINATED
            Timber.tag(TAG).i("Application terminated for ${record.packageName}")

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error terminating application for $instanceId")
        } finally {
            applications.remove(instanceId)
        }
    }

    /**
     * Propagate configuration change to a running Application.
     */
    fun onConfigurationChanged(instanceId: String, newConfig: Configuration) {
        val record = applications[instanceId] ?: return
        if (record.state != ApplicationState.RUNNING) return

        try {
            record.application.onConfigurationChanged(newConfig)
            Timber.tag(TAG).d("Configuration changed for ${record.packageName}")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error dispatching config change to ${record.packageName}")
        }
    }

    /**
     * Propagate low memory signal to a running Application.
     */
    fun onLowMemory(instanceId: String) {
        val record = applications[instanceId] ?: return
        if (record.state != ApplicationState.RUNNING) return

        try {
            record.application.onLowMemory()
            Timber.tag(TAG).d("onLowMemory dispatched to ${record.packageName}")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error dispatching onLowMemory")
        }
    }

    /**
     * Propagate trim memory signal to a running Application.
     */
    fun onTrimMemory(instanceId: String, level: Int) {
        val record = applications[instanceId] ?: return
        if (record.state != ApplicationState.RUNNING) return

        try {
            record.application.onTrimMemory(level)
            Timber.tag(TAG).d("onTrimMemory($level) dispatched to ${record.packageName}")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Error dispatching onTrimMemory")
        }
    }

    // ---------- Query Methods ----------

    /** Get the Application instance for a guest app */
    fun getApplication(instanceId: String): Application? =
        applications[instanceId]?.application

    /** Get the ApplicationRecord for a guest app */
    fun getApplicationRecord(instanceId: String): ApplicationRecord? =
        applications[instanceId]

    /** Check if an application is bound and running */
    fun isApplicationRunning(instanceId: String): Boolean {
        val record = applications[instanceId] ?: return false
        return record.state == ApplicationState.RUNNING
    }

    /** Get all running application instance IDs */
    fun getRunningInstanceIds(): Set<String> {
        return applications.entries
            .filter { it.value.state == ApplicationState.RUNNING }
            .map { it.key }
            .toSet()
    }

    /** Get the number of running applications */
    fun getRunningCount(): Int =
        applications.values.count { it.state == ApplicationState.RUNNING }

    /** Terminate all running applications */
    fun terminateAll() {
        Timber.tag(TAG).i("Terminating all ${applications.size} applications")
        val instanceIds = applications.keys.toList()
        for (id in instanceIds) {
            terminateApplication(id)
        }
    }
}
