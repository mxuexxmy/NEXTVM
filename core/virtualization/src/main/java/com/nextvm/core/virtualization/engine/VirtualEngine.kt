package com.nextvm.core.virtualization.engine

import android.content.Context
import android.content.Intent
import com.nextvm.core.apk.VirtualClassLoader
import com.nextvm.core.binder.BinderProxyManager
import com.nextvm.core.binder.proxy.ContentResolverProxy
import com.nextvm.core.binder.proxy.SystemServiceProxyManager
import com.nextvm.core.common.AndroidCompat
import com.nextvm.core.framework.parsing.FrameworkApkParser
import com.nextvm.core.framework.parsing.FullPackageInfo
import com.nextvm.core.hook.AntiDetectionEngine
import com.nextvm.core.hook.IdentitySpoofingEngine
import com.nextvm.core.hook.NativeHookBridge
import com.nextvm.core.model.*
import com.nextvm.core.sandbox.NativeLibManager
import com.nextvm.core.sandbox.SandboxManager
import com.nextvm.core.sandbox.VirtualEnvironmentHook
import com.nextvm.core.services.VirtualServiceManager
import com.nextvm.core.virtualization.lifecycle.VirtualApplicationManager
import com.nextvm.core.virtualization.lifecycle.VirtualConfigurationManager
import com.nextvm.core.virtualization.lifecycle.VirtualResourceManager
import com.nextvm.core.virtualization.process.VirtualMultiProcessManager
import com.nextvm.core.virtualization.ui.VirtualWindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VirtualEngine — The heart of NEXTVM.
 *
 * Phase 2+ architecture: Uses REAL Android 16 framework code with comprehensive
 * subsystem orchestration for full guest app lifecycle.
 *
 * Sub-systems:
 * - FrameworkApkParser: APK parsing ported from Android 16's ApkLiteParseUtils
 * - VirtualServiceManager: Hosts VAMS, VPMS, service/broadcast/provider/intent/perm/network/GMS
 * - VirtualApplicationManager: Guest Application class lifecycle (instantiate, attach, onCreate)
 * - VirtualResourceManager: Guest app resource/asset loading
 * - VirtualConfigurationManager: Configuration change detection and dispatch
 * - SystemServiceProxyManager: 35 Android system service proxies
 * - ContentResolverProxy: ContentResolver interception
 * - IdentitySpoofingEngine: Build.*, ANDROID_ID, IMEI spoofing
 * - NativeHookBridge: PLT hooks for libc I/O redirect, /proc spoofing
 * - AntiDetectionEngine: Root/emulator/virtual environment detection bypass
 * - VirtualMultiProcessManager: Multi-process guest app support
 * - VirtualWindowManager: Window type management and UI interaction
 * - ActivityThread.mH hook: Runtime stub-to-real class swapping
 *
 * Initialization sequence (MUST follow this order):
 * 1. bypassHiddenApis()            → Unlock reflection access
 * 2. createVirtualDirectories()     → Set up sandbox file tree
 * 3. initializeStubRegistry()       → Map stubs to process slots
 * 4. initializeFrameworkServices()  → Start all virtual services
 * 5. installSystemServiceProxies()  → Hook 35 system services
 * 6. installNativeHooks()           → PLT hooks for file I/O & /proc
 * 7. installIdentitySpoofing()      → Device identity spoofing
 * 8. installAntiDetection()         → Root/emulator detection bypass
 * 9. installActivityThreadHook()    → Hook mH for class swapping
 * 10. loadInstalledApps()           → Restore from disk
 */
@Singleton
class VirtualEngine @Inject constructor(
    private val context: Context,
    private val frameworkApkParser: FrameworkApkParser,
    private val serviceManager: VirtualServiceManager,
    // Application lifecycle
    private val applicationManager: VirtualApplicationManager,
    private val resourceManager: VirtualResourceManager,
    private val configurationManager: VirtualConfigurationManager,
    // System service proxies
    private val systemServiceProxyManager: SystemServiceProxyManager,
    private val contentResolverProxy: ContentResolverProxy,
    // Hook / Identity / Security
    private val identitySpoofingEngine: IdentitySpoofingEngine,
    private val nativeHookBridge: NativeHookBridge,
    private val antiDetectionEngine: AntiDetectionEngine,
    // Multi-process & Window
    private val multiProcessManager: VirtualMultiProcessManager,
    private val windowManager: VirtualWindowManager,
    // External storage / Environment hooks
    private val virtualEnvironmentHook: VirtualEnvironmentHook
) {
    companion object {
        private const val TAG = "VirtualEngine"
    }

    // Engine state
    private val _status = MutableStateFlow(EngineStatus.NOT_INITIALIZED)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    // Installed virtual apps
    private val _installedApps = MutableStateFlow<List<VirtualApp>>(emptyList())
    val installedApps: StateFlow<List<VirtualApp>> = _installedApps.asStateFlow()

    // Sub-systems
    private lateinit var stubRegistry: StubRegistry
    private lateinit var sandboxManager: SandboxManager
    private lateinit var processManager: VirtualProcessManager
    private lateinit var activityThreadHook: ActivityThreadHook
    private lateinit var binderProxyManager: BinderProxyManager

    // Virtual directories
    private lateinit var virtualRoot: File
    private lateinit var apksDir: File
    private lateinit var dataDir: File

    // ClassLoader cache: instanceId -> DexClassLoader
    private val classLoaders = mutableMapOf<String, ClassLoader>()

    // Running apps: instanceId -> process slot
    private val runningApps = mutableMapOf<String, Int>()

    // Thread-safety lock for initialization
    private val initLock = Any()

    // Flag to track if early ATHook was installed
    private var earlyHookInstalled = false

    /**
     * Install the ActivityThread.mH hook EARLY, synchronously on the main thread.
     *
     * This MUST be called in Application.onCreate() BEFORE the background init starts.
     * In child processes (:p0, :p1), Android sends EXECUTE_TRANSACTION immediately
     * after Application.onCreate() returns, so ATHook must be ready by then.
     *
     * This method does the minimum work needed for ATHook:
     * 1. Hidden API bypass (needed for reflection)
     * 2. StubRegistry initialization (needed for stub→real class mapping)
     * 3. ATHook installation (hooks ActivityThread.mH.mCallback)
     */
    fun installActivityThreadHookEarly(context: Context) {
        try {
            // 1. Hidden API bypass (fast, ~10ms)
            AndroidCompat.bypassHiddenApis()

            // 2. Initialize virtual directories early so getDataDir()/getNativeLibDir()
            // work immediately when handleLaunchActivityItem runs on the main thread
            // BEFORE the background initialize() has completed.
            if (!::virtualRoot.isInitialized) {
                virtualRoot = File(context.filesDir, VirtualConstants.VIRTUAL_DIR)
                apksDir = File(virtualRoot, VirtualConstants.VIRTUAL_APKS_DIR)
                dataDir = File(virtualRoot, VirtualConstants.VIRTUAL_DATA_DIR)
                virtualRoot.mkdirs()
                apksDir.mkdirs()
                dataDir.mkdirs()
            }

            // 3. Initialize StubRegistry (fast, ~5ms)
            if (!::stubRegistry.isInitialized) {
                stubRegistry = StubRegistry()
            }

            // 4. Install ATHook synchronously (hooks mH.mCallback + Instrumentation)
            if (!::activityThreadHook.isInitialized) {
                activityThreadHook = ActivityThreadHook(context, stubRegistry, this)
                activityThreadHook.install()
                // CRITICAL: Install Instrumentation hook immediately so newActivity()
                // is intercepted from the very first EXECUTE_TRANSACTION message.
                // Without this, the system Instrumentation will try to load guest
                // classes with the wrong ClassLoader, causing ClassNotFoundException.
                activityThreadHook.installInstrumentationHookEarly()
            }

            earlyHookInstalled = true
            Timber.tag(TAG).i("ATHook installed early (main thread) ✓")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to install early ATHook — guest apps may show blank screen")
        }
    }

    /**
     * Initialize the virtual engine.
     * MUST be called before any other operation.
     * Order matters — follow the sequence exactly.
     * Thread-safe: concurrent calls are handled gracefully.
     */
    fun initialize(): VmResult<Unit> {
        synchronized(initLock) {
            if (_status.value == EngineStatus.READY) {
                Timber.tag(TAG).d("Engine already initialized")
                return VmResult.Success(Unit)
            }

            if (_status.value == EngineStatus.INITIALIZING) {
                Timber.tag(TAG).d("Engine initialization already in progress, waiting...")
                // Wait for existing init to complete (max 30 seconds)
                val startTime = System.currentTimeMillis()
                while (_status.value == EngineStatus.INITIALIZING) {
                    if (System.currentTimeMillis() - startTime > 30_000) {
                        return VmResult.Error("Engine initialization timed out")
                    }
                    Thread.sleep(100)
                }
                return if (_status.value == EngineStatus.READY) {
                    VmResult.Success(Unit)
                } else {
                    VmResult.Error("Engine initialization failed")
                }
            }

            _status.value = EngineStatus.INITIALIZING
        Timber.tag(TAG).i("Initializing NEXTVM Virtual Engine (Phase 2+)...")

        try {
            // Step 1: Bypass hidden API restrictions (MUST be first)
            if (!AndroidCompat.bypassHiddenApis()) {
                Timber.tag(TAG).w("Hidden API bypass failed — some features may not work")
            }

            // Step 2: Create virtual directory structure
            createVirtualDirectories()

            // Step 3: Initialize core sub-systems
            // (Skip StubRegistry if already initialized by installActivityThreadHookEarly)
            if (!::stubRegistry.isInitialized) {
                stubRegistry = StubRegistry()
            }
            sandboxManager = SandboxManager(context, virtualRoot)
            processManager = VirtualProcessManager(VirtualConstants.MAX_PROCESS_SLOTS)

            // Step 4: Initialize ALL framework services (VPMS + VAMS + 7 more services)
            serviceManager.initialize(context)
            Timber.tag(TAG).d("Framework services initialized (9 services)")

            // Step 5: Install system service proxies (35 Android system services)
            systemServiceProxyManager.installAllProxies(context)
            Timber.tag(TAG).d("System service proxies installed (35 services)")

            // Step 5.5: Install IActivityManager + IPackageManager Binder proxies
            // These intercept critical IPC calls (startActivity, getContentProvider, etc.)
            // and fix callingPackage to match the host UID, preventing SecurityException.
            binderProxyManager = BinderProxyManager(context)
            binderProxyManager.installAllProxies()
            Timber.tag(TAG).d("Binder proxies installed (IActivityManager + IPackageManager)")

            // Step 5.6: Connect GMS service router to ActivityManagerProxy + PackageManagerProxy
            // This enables guest app bindService() calls to GMS packages to be
            // routed through the Hybrid GMS bridge for identity spoofing.
            binderProxyManager.setGmsRouter(serviceManager.gmsManager)
            Timber.tag(TAG).d("GMS service router connected to Binder proxy")

            // Step 5.7: Connect GMS router to SystemServiceProxyManager for account isolation
            // Each virtual instance will only see its own Google account (not all host accounts)
            systemServiceProxyManager.setGmsRouter(serviceManager.gmsManager)
            Timber.tag(TAG).d("GMS service router connected to SystemServiceProxy (account isolation)")

            // Step 6: Install ContentResolver proxy
            contentResolverProxy.install(context)
            Timber.tag(TAG).d("ContentResolver proxy installed")

            // Step 7: Install native PLT hooks (file I/O redirect, /proc spoofing)
            // NOTE: Native GOT patching can SIGSEGV on some devices if libraries have
            // Full RELRO or unusual ELF layouts. We catch any exception from the Kotlin
            // side, but a native crash will still terminate the process. The native code
            // includes address validation to prevent this.
            try {
                nativeHookBridge.initNativeHooks(context)
                Timber.tag(TAG).d("Native hook bridge initialized")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Native hook bridge failed — Java-level hooks only")
            }

            // Step 8: Initialize identity spoofing engine
            identitySpoofingEngine.initialize()
            Timber.tag(TAG).d("Identity spoofing engine initialized")

            // Step 9: Initialize anti-detection engine
            antiDetectionEngine.initialize()
            Timber.tag(TAG).d("Anti-detection engine initialized")

            // Step 10: Initialize application lifecycle manager
            applicationManager.initialize(context)
            Timber.tag(TAG).d("Application lifecycle manager initialized")

            // Step 11: Initialize resource manager
            resourceManager.initialize(context)
            Timber.tag(TAG).d("Resource manager initialized")

            // Step 12: Initialize configuration change manager
            configurationManager.initialize(context)
            Timber.tag(TAG).d("Configuration change manager initialized")

            // Step 13: Initialize multi-process manager
            multiProcessManager.initialize(context)
            Timber.tag(TAG).d("Multi-process manager initialized")

            // Step 14: Initialize window manager
            windowManager.initialize(context)
            Timber.tag(TAG).d("Window manager initialized")

            // Step 14b: Initialize network manager
            serviceManager.networkManager.initialize(context)
            Timber.tag(TAG).d("Network manager initialized")

            // Note: Hybrid GMS Manager is initialized by serviceManager.initialize() above

            // Step 15: Hook ActivityThread.mH for class swapping
            // (Skip if already installed by installActivityThreadHookEarly)
            if (!earlyHookInstalled) {
                activityThreadHook = ActivityThreadHook(context, stubRegistry, this)
                activityThreadHook.install()
            }

            // Step 16: Load previously installed apps from disk
            loadInstalledApps()

            _status.value = EngineStatus.READY
            Timber.tag(TAG).i("NEXTVM Virtual Engine initialized successfully (all subsystems online)")
            return VmResult.Success(Unit)

        } catch (e: Exception) {
            _status.value = EngineStatus.ERROR
            Timber.tag(TAG).e(e, "Failed to initialize virtual engine")
            return VmResult.Error("Engine initialization failed: ${e.message}", e)
        }
        } // synchronized(initLock)
    }

    /**
     * Install an APK into the virtual environment.
     */
    fun installApp(apkPath: String): VmResult<VirtualApp> {
        ensureInitialized()

        Timber.tag(TAG).i("Installing APK: $apkPath")

        try {
            // 1. Parse APK using Android 16 framework parser
            val apkFile = File(apkPath)
            val result = frameworkApkParser.parseFullPackage(apkFile)
            val parseResult = when (result) {
                is com.nextvm.core.framework.parsing.ParseResult.Success -> result.result
                is com.nextvm.core.framework.parsing.ParseResult.Error -> {
                    return VmResult.Error("Failed to parse APK: ${result.errorMessage ?: "unknown error"}")
                }
            }

            val packageName = parseResult.packageName
            val appName = parseResult.appName

            Timber.tag(TAG).d("Parsed APK: $packageName ($appName)")

            // 2. Generate unique instance ID
            val instanceId = "${packageName}_${System.currentTimeMillis()}"

            // 3. Copy APK to virtual storage
            val virtualApkPath = copyApkToVirtualStorage(apkPath, instanceId, packageName)

            // 3.5. Discover and COPY split APKs (critical for Unity/native apps)
            // Split APKs contain native libs (libmain.so, libil2cpp.so, etc.)
            // that Play Store app bundles separate into split_config.{abi}.apk
            val discoveredSplits = discoverSplitApks(apkPath, packageName)
            val virtualSplitPaths = mutableListOf<String>()
            if (discoveredSplits.isNotEmpty()) {
                Timber.tag(TAG).d("Discovered ${discoveredSplits.size} split APKs for $packageName")
                for (splitPath in discoveredSplits) {
                    try {
                        val splitName = File(splitPath).name
                        val destFile = File(apksDir, "${instanceId}_${splitName}")
                        File(splitPath).copyTo(destFile, overwrite = true)
                        destFile.setWritable(false, false)
                        destFile.setReadable(true, false)
                        destFile.setExecutable(false, false)
                        virtualSplitPaths.add(destFile.absolutePath)
                        Timber.tag(TAG).d("Copied split APK: $splitName")
                    } catch (e: Exception) {
                        Timber.tag(TAG).w("Failed to copy split $splitPath: ${e.message}")
                        // Try using original path as fallback
                        virtualSplitPaths.add(splitPath)
                    }
                }
            }

            // 4. Extract native libraries using NativeLibManager (handles all APKs)
            val allApkPaths = buildList {
                add(virtualApkPath)
                addAll(virtualSplitPaths)
            }
            val dataRoot = File(virtualRoot, "data")
            val nativeLibResult = NativeLibManager.extractNativeLibs(allApkPaths, instanceId, dataRoot)

            // 5. Create isolated data directory tree
            sandboxManager.createAppDataDir(instanceId, packageName)

            // 6. Assign process slot
            val slot = processManager.allocateSlot(instanceId)
            if (slot < 0) {
                return VmResult.Error("No free process slots available (max: ${VirtualConstants.MAX_PROCESS_SLOTS})")
            }

            // 7. Main launcher activity (already found during parsing)
            val mainActivity = parseResult.mainActivity

            // 8. Determine native ABI support
            val nativeAbis = parseResult.nativeAbis.ifEmpty {
                parseResult.apkLite?.let { apkLite ->
                    val abis = mutableListOf<String>()
                    if (apkLite.isMultiArch) abis.add("multi-arch")
                    abis
                } ?: emptyList()
            }

            // 9. Create VirtualApp record with Android 16 framework fields
            // Build activity-alias mapping: alias name → real target activity
            val activityAliases = parseResult.activities
                .filter { it.targetActivity != null }
                .associate { it.className to it.targetActivity!! }

            val virtualApp = VirtualApp(
                packageName = packageName,
                appName = appName,
                versionName = parseResult.versionName,
                versionCode = parseResult.versionCode,
                icon = parseResult.icon,
                apkPath = virtualApkPath,
                instanceId = instanceId,
                processSlot = slot,
                is64Bit = parseResult.apkLite?.isMultiArch != false,
                mainActivity = mainActivity,
                activities = parseResult.activities.map { it.className },
                services = parseResult.services.map { it.className },
                providers = parseResult.providers.map { it.className },
                receivers = parseResult.receivers.map { it.className },
                // Android 16 framework fields
                minSdkVersion = parseResult.apkLite?.minSdkVersion ?: 1,
                targetSdkVersion = parseResult.apkLite?.targetSdkVersion ?: 35,
                isMultiArch = parseResult.apkLite?.isMultiArch ?: false,
                use32bitAbi = parseResult.apkLite?.use32bitAbi ?: false,
                extractNativeLibs = parseResult.apkLite?.extractNativeLibs ?: true,
                useEmbeddedDex = parseResult.apkLite?.useEmbeddedDex ?: false,
                isDebuggable = parseResult.apkLite?.isDebuggable ?: false,
                installLocation = parseResult.apkLite?.installLocation ?: 0,
                pageSizeCompat = parseResult.apkLite?.pageSizeCompat ?: 0,
                splitApkPaths = virtualSplitPaths.ifEmpty {
                    parseResult.packageLite?.splitApkPaths ?: emptyList()
                },
                splitNames = parseResult.packageLite?.splitNames ?: emptyList(),
                hasSplitApks = (parseResult.packageLite?.splitApkPaths?.isNotEmpty()) == true,
                isolatedSplits = parseResult.apkLite?.isolatedSplits ?: false,
                applicationClassName = parseResult.applicationClassName,
                requestedPermissions = parseResult.permissions,
                nativeAbis = nativeAbis,
                activityAliases = activityAliases,
                dataDir = sandboxManager.getAppDir(instanceId).absolutePath,
                libDir = nativeLibResult.libDir,
                librarySearchPath = nativeLibResult.librarySearchPath,
                primaryAbi = nativeLibResult.selectedAbi,
                cacheDir = sandboxManager.getCacheDir(instanceId).absolutePath
            )

            // 10. Register in virtual PMS (real service, not proxy)
            serviceManager.packageManager.registerPackage(virtualApp, parseResult)

            // 10.2. Initialize and AUTO-GRANT all permissions for the virtual app.
            // This ensures checkPermission/checkOp returns GRANTED for everything,
            // so guest apps never see permission denial dialogs or Settings redirects.
            serviceManager.permissionManager.initializePermissions(
                packageName = packageName,
                requestedPermissions = parseResult.permissions,
                overrides = virtualApp.permissionOverrides
            )
            serviceManager.permissionManager.grantAllPermissions(packageName)
            Timber.tag(TAG).d("All permissions auto-granted for $packageName")

            // 10.5. Register with Binder proxies (so AMProxy/PMProxy know about this app)
            if (::binderProxyManager.isInitialized) {
                binderProxyManager.registerVirtualApp(virtualApp)
            }

            // 10.6. Register external storage for file explorer support
            virtualEnvironmentHook.registerInstance(instanceId, packageName)
            systemServiceProxyManager.registerVirtualSdcard(
                instanceId,
                sandboxManager.getExternalStorageDir(instanceId).absolutePath
            )

            // 11. Save to persistent storage
            saveVirtualApp(virtualApp)

            // 12. Update installed apps list
            val current = _installedApps.value.toMutableList()
            current.add(virtualApp)
            _installedApps.value = current

            Timber.tag(TAG).i("App installed: ${virtualApp.appName} (${virtualApp.instanceId})")
            return VmResult.Success(virtualApp)

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to install APK: $apkPath")
            return VmResult.Error("Installation failed: ${e.message}", e)
        }
    }

    /**
     * Launch a virtual app by its instance ID.
     */
    fun launchApp(instanceId: String): VmResult<Unit> {
        ensureInitialized()

        val app = findApp(instanceId)
            ?: return VmResult.Error("App not found: $instanceId")

        Timber.tag(TAG).i("Launching: ${app.appName} (${app.instanceId})")

        try {
            // 1. Find main activity (from VPMS, the real service)
            val mainActivity = app.mainActivity
                ?: serviceManager.packageManager.getMainActivity(app.packageName)
                ?: return VmResult.Error("No main activity found for ${app.packageName}")

            // 2. Get launch mode from VPMS and convert to string for StubRegistry
            val launchModeInt = serviceManager.packageManager.getActivityLaunchMode(
                app.packageName, mainActivity
            )
            val launchMode = when (launchModeInt) {
                android.content.pm.ActivityInfo.LAUNCH_SINGLE_TOP -> "singleTop"
                android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK -> "singleTask"
                android.content.pm.ActivityInfo.LAUNCH_SINGLE_INSTANCE -> "singleInstance"
                else -> "standard"
            }

            // 3. Free previously reserved stubs for this slot so relaunch works.
            // Stubs were historically leaked on failed/repeated launches and on stop.
            stubRegistry.releaseAllStubsForSlot(app.processSlot)

            // 4. Resolve stub class based on launch mode
            val stubClass = stubRegistry.resolveStub(
                slot = app.processSlot,
                launchMode = launchMode
            ) ?: return VmResult.Error("No available stub for process slot ${app.processSlot}")

            try {
                // 5. Build the swapped intent
                val intent = Intent().apply {
                    setClassName(context.packageName, stubClass)
                    putExtra(VirtualIntentExtras.TARGET_PACKAGE, app.packageName)
                    putExtra(VirtualIntentExtras.TARGET_ACTIVITY, mainActivity)
                    putExtra(VirtualIntentExtras.INSTANCE_ID, app.instanceId)
                    putExtra(VirtualIntentExtras.APK_PATH, app.apkPath)
                    putExtra(VirtualIntentExtras.PROCESS_SLOT, app.processSlot)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                // 6. Create ClassLoader for guest app (if not cached)
                if (!classLoaders.containsKey(instanceId)) {
                    val classLoader = VirtualClassLoader.createClassLoader(
                        apkPath = app.apkPath,
                        instanceId = instanceId,
                        parentDir = virtualRoot,
                        prebuiltLibrarySearchPath = app.librarySearchPath.ifEmpty { null },
                        splitApkPaths = app.splitApkPaths
                    )
                    classLoaders[instanceId] = classLoader
                    GuestClassLoaderRegistry.register(app.packageName, instanceId, classLoader)
                }

                // 7. Register process start in VAMS (real service)
                serviceManager.activityManager.startProcess(
                    packageName = app.packageName,
                    instanceId = app.instanceId,
                    processSlot = app.processSlot
                )

                // 8. Start activity in VAMS
                serviceManager.activityManager.startActivity(
                    packageName = app.packageName,
                    activityName = mainActivity,
                    instanceId = app.instanceId,
                    stubClassName = stubClass,
                    launchMode = launchModeInt
                )

                // 9. Register in running apps
                runningApps[instanceId] = app.processSlot

                // 9.3: Activate external storage hooks for this instance.
                // This redirects Environment.getExternalStorageDirectory() and
                // file I/O paths (/sdcard/, /storage/emulated/0/) to the sandbox.
                val sdcardDir = sandboxManager.getExternalStorageDir(instanceId)
                virtualEnvironmentHook.activateForInstance(instanceId, app.packageName)
                nativeHookBridge.setupExternalStorageRedirections(instanceId, sdcardDir.absolutePath)

                // 9.5: Install any deferred service proxies (e.g., NotificationManager)
                systemServiceProxyManager.installDeferredProxies()

                // 10. Mark as launched in VPMS
                serviceManager.packageManager.markAsLaunched(app.packageName)

                // 11. Update app state
                updateAppState(instanceId) { it.copy(isRunning = true, lastLaunchedAt = System.currentTimeMillis()) }

                // 11.5: Drain any FCM messages that arrived while the app was offline.
                // These are queued by NextVmFcmService when the target instance wasn't running.
                try {
                    val fcmProxy = serviceManager.gmsManager.fcmProxy
                    val pending = fcmProxy.drainPendingMessages(instanceId)
                    if (pending.isNotEmpty()) {
                        Timber.tag(TAG).d("Draining ${pending.size} queued FCM messages for $instanceId")
                        pending.forEach { message ->
                            val deliveryIntent = fcmProxy.buildDeliveryIntent(instanceId, message)
                            deliverFcmToInstance(instanceId, deliveryIntent)
                        }
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).w("FCM drain failed for $instanceId: ${e.message}")
                }

                // 12. Launch via real system (it sees a valid stub component)
                context.startActivity(intent)

                Timber.tag(TAG).i("Launched: ${app.appName} via stub $stubClass (launchMode=$launchMode)")
                return VmResult.Success(Unit)
            } catch (e: Exception) {
                stubRegistry.releaseStub(stubClass)
                throw e
            }

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to launch: ${app.appName}")
            return VmResult.Error("Launch failed: ${e.message}", e)
        }
    }

    /**
     * Stop a running virtual app.
     */
    fun stopApp(instanceId: String): VmResult<Unit> {
        val slot = runningApps.remove(instanceId)
        if (slot != null) {
            // Kill process in VAMS (takes instanceId, not slot)
            serviceManager.activityManager.killProcess(instanceId)
            processManager.releaseSlot(slot)
            stubRegistry.releaseAllStubsForSlot(slot)
            classLoaders.remove(instanceId)
            val pkg = findApp(instanceId)?.packageName ?: instanceId.substringBefore('_')
            GuestClassLoaderRegistry.unregister(pkg, instanceId)

            // Deactivate storage hooks for this instance
            nativeHookBridge.removeExternalStorageRedirections()
            nativeHookBridge.removeAppRedirections(pkg)
            virtualEnvironmentHook.deactivate()

            // Clean up GMS state (FCM tokens, auth state, Binder connections, billing)
            try {
                serviceManager.gmsManager.cleanupInstance(instanceId)
            } catch (e: Exception) {
                Timber.tag(TAG).w("GMS cleanup failed for $instanceId: ${e.message}")
            }

            updateAppState(instanceId) { it.copy(isRunning = false) }
            Timber.tag(TAG).i("Stopped app: $instanceId")
        } else {
            // App may not be in runningApps (crashed / leaked) but still hold stubs
            findApp(instanceId)?.processSlot?.takeIf { it >= 0 }?.let { persistedSlot ->
                stubRegistry.releaseAllStubsForSlot(persistedSlot)
            }
        }
        return VmResult.Success(Unit)
    }

    /**
     * Uninstall a virtual app.
     */
    fun uninstallApp(instanceId: String): VmResult<Unit> {
        ensureInitialized()

        // Stop if running
        stopApp(instanceId)

        val app = findApp(instanceId)
            ?: return VmResult.Error("App not found: $instanceId")

        try {
            // Remove data directory
            sandboxManager.deleteAppData(instanceId)

            // Remove APK
            File(app.apkPath).delete()

            // Unregister from virtual PMS (real service)
            serviceManager.packageManager.unregisterPackage(app.packageName)

            // Unregister from Binder proxies
            if (::binderProxyManager.isInitialized) {
                binderProxyManager.unregisterVirtualApp(app.packageName)
            }

            // Unregister external storage hooks
            virtualEnvironmentHook.unregisterInstance(instanceId)

            // Release process slot
            if (app.processSlot >= 0) {
                processManager.releaseSlot(app.processSlot)
            }

            // Remove from installed list
            val current = _installedApps.value.toMutableList()
            current.removeAll { it.instanceId == instanceId }
            _installedApps.value = current

            // Remove saved state
            deleteSavedApp(instanceId)

            Timber.tag(TAG).i("Uninstalled: ${app.appName} ($instanceId)")
            return VmResult.Success(Unit)

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to uninstall: $instanceId")
            return VmResult.Error("Uninstall failed: ${e.message}", e)
        }
    }

    /**
     * Get the ClassLoader for a virtual app.
     */
    fun getClassLoader(instanceId: String): ClassLoader? = classLoaders[instanceId]

    /**
     * Set up a binder interceptor on a VirtualContext that wraps GMS binders
     * in GmsBinderProxy. This ensures bindService() calls from DynamiteModule-loaded
     * GMS code (Firebase measurement, analytics, etc.) get proxied binders with
     * proper package identity rewriting.
     */
    private fun setupBinderInterceptor(
        virtualContext: com.nextvm.core.sandbox.VirtualContext,
        instanceId: String,
        guestPackageName: String
    ) {
        val gmsManager = serviceManager.gmsManager
        val bridge = gmsManager.binderBridge
        val spoofer = gmsManager.identitySpoofer
        virtualContext.setBinderInterceptor { componentName, binder, intent ->
            // Check if this binder is from a GMS service
            val targetPkg = componentName?.packageName
                ?: intent?.component?.packageName
                ?: intent?.`package`
            val isGms = targetPkg != null && (
                    targetPkg == com.nextvm.core.services.gms.GmsBinderBridge.GMS_PACKAGE ||
                    targetPkg == com.nextvm.core.services.gms.GmsBinderBridge.GSF_PACKAGE ||
                    targetPkg == com.nextvm.core.services.gms.GmsBinderBridge.PLAY_STORE_PACKAGE ||
                    targetPkg.startsWith("com.google.android.gms") ||
                    targetPkg.startsWith("com.google.firebase")
            )
            if (isGms && bridge.isInitialized()) {
                // Already a GmsBinderProxy? Don't double-wrap
                if (binder is com.nextvm.core.services.gms.GmsBinderBridge.GmsBinderProxy) {
                    binder
                } else {
                    spoofer.registerMapping(guestPackageName, instanceId)
                    com.nextvm.core.services.gms.GmsBinderBridge.GmsBinderProxy(
                        binder, guestPackageName, instanceId, bridge
                    )
                }
            } else {
                binder
            }
        }
    }

    /**
     * Expose the GMS manager for FCM routing from the app module.
     * Used by NextVmFcmService to route incoming push notifications.
     */
    fun getGmsManager() = serviceManager.gmsManager

    /**
     * Expose the currently running virtual apps.
     * Used by ActivityThreadHook to identify which instance is active.
     */
    fun getRunningApps(): Map<String, Int> = runningApps.toMap()

    /**
     * Deliver an FCM message intent to a specific virtual app instance.
     * Called by NextVmFcmService after routing determines the target instance.
     *
     * If the instance is currently running, the intent is sent via startService()
     * targeting the virtual stub service in the instance's process slot.
     * If the instance is offline, the message is queued in GmsFcmProxy.
     */
    fun deliverFcmToInstance(instanceId: String, deliveryIntent: android.content.Intent) {
        val app = findApp(instanceId)

        if (app == null) {
            Timber.tag(TAG).w("FCM delivery: no app found for instance $instanceId")
            return
        }

        val isRunning = runningApps.containsKey(instanceId)

        if (isRunning) {
            // App is running — deliver directly via broadcast within the virtual process
            try {
                val intent = android.content.Intent(deliveryIntent).apply {
                    // Route to the StubService in the correct process slot so the
                    // VirtualServiceLifecycleManager can forward to the guest's
                    // FirebaseMessagingService-equivalent receiver
                    setClassName(context.packageName,
                        "com.nextvm.app.stub.StubService\$P${app.processSlot}\$S00")
                    addFlags(android.content.Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                }
                context.startService(intent)
                Timber.tag(TAG).d("FCM delivered to running instance $instanceId (slot=${app.processSlot})")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "FCM direct delivery failed for $instanceId")
                // Queue as fallback
                queueFcmForOfflineInstance(instanceId, deliveryIntent)
            }
        } else {
            // App is not running — queue the message
            queueFcmForOfflineInstance(instanceId, deliveryIntent)
        }
    }

    private fun queueFcmForOfflineInstance(instanceId: String, deliveryIntent: android.content.Intent) {
        try {
            val fcmProxy = getGmsManager().fcmProxy

            // Extract message data from the delivery intent
            val data = mutableMapOf<String, String>()
            deliveryIntent.extras?.keySet()?.forEach { key ->
                val value = deliveryIntent.getStringExtra(key)
                if (value != null) data[key] = value
            }

            val message = com.nextvm.core.services.gms.GmsFcmProxy.FcmMessage(
                from = deliveryIntent.getStringExtra("from") ?: "",
                to = deliveryIntent.getStringExtra("google.message_id") ?: "",
                messageId = deliveryIntent.getStringExtra("google.message_id") ?: "",
                data = data,
                notification = null
            )

            fcmProxy.queueMessage(instanceId, message)
            Timber.tag(TAG).d("FCM queued for offline instance $instanceId")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to queue FCM for $instanceId")
        }
    }

    /**
     * Get or create the ClassLoader for a virtual app (used lazily in child processes).
     */
    fun getOrCreateClassLoader(instanceId: String, apkPath: String): ClassLoader {
        return classLoaders.getOrPut(instanceId) {
            if (!::virtualRoot.isInitialized) {
                // Use the SAME path as createVirtualDirectories() to avoid path mismatch
                virtualRoot = File(context.filesDir, VirtualConstants.VIRTUAL_DIR)
                virtualRoot.mkdirs()
            }
            val app = findApp(instanceId)
            // Sanitize stale arm64 search paths when running on non-ARM (x86_64 emulator).
            val searchPath = app?.librarySearchPath?.ifEmpty { null }?.let { path ->
                val deviceAbis = android.os.Build.SUPPORTED_ABIS.toList()
                val pathLooksArm = path.contains("arm64-v8a") || path.contains("armeabi")
                val deviceIsArm = deviceAbis.any { it.startsWith("arm") }
                if (pathLooksArm && !deviceIsArm) {
                    NativeLibManager.rebuildLibrarySearchPath(
                        libDir = app.libDir.ifEmpty {
                            File(virtualRoot, "data/$instanceId/lib").absolutePath
                        },
                        apkPath = apkPath,
                        splitApkPaths = app.splitApkPaths,
                        preferredAbi = deviceAbis.firstOrNull() ?: ""
                    ).also {
                        Timber.tag(TAG).w(
                            "Sanitized librarySearchPath for $instanceId: $path → $it"
                        )
                    }
                } else {
                    path
                }
            }
            // Also purge/re-extract wrong-ABI .so before ClassLoader binds to lib dir
            NativeLibManager.reExtractIfMissing(
                apkPath = apkPath,
                splitApkPaths = app?.splitApkPaths ?: emptyList(),
                instanceId = instanceId,
                dataRoot = File(virtualRoot, "data")
            )
            VirtualClassLoader.createClassLoader(
                apkPath = apkPath,
                instanceId = instanceId,
                parentDir = virtualRoot,
                prebuiltLibrarySearchPath = searchPath,
                splitApkPaths = app?.splitApkPaths ?: emptyList()
            ).also { cl ->
                val pkg = findApp(instanceId)?.packageName ?: instanceId.substringBefore('_')
                GuestClassLoaderRegistry.register(pkg, instanceId, cl)
            }
        }
    }

    /**
     * Get a VirtualApp by instance ID.
     */
    fun findApp(instanceId: String): VirtualApp? =
        _installedApps.value.find { it.instanceId == instanceId }

    /**
     * Get a VirtualApp by package name.
     */
    fun findAppByPackage(packageName: String): VirtualApp? =
        _installedApps.value.find { it.packageName == packageName }

    /**
     * Find a VirtualApp by matching its ClassLoader.
     * Used to resolve the instanceId when an Activity is created by internal
     * navigation (e.g., Play Store launching AssetBrowserActivity internally)
     * without going through the stub system.
     */
    fun findAppByClassLoader(classLoader: ClassLoader): VirtualApp? {
        for ((instanceId, cl) in classLoaders) {
            if (cl === classLoader) {
                return findApp(instanceId)
            }
        }
        return null
    }

    /**
     * Check if a package name belongs to a virtual app.
     * Delegates to VirtualPackageManagerService (real service).
     */
    fun isVirtualPackage(packageName: String): Boolean =
        serviceManager.isVirtualPackage(packageName)

    /**
     * Resolve an activity name to its real class, handling activity-aliases.
     * If the named activity is an alias (has targetActivity set), returns the real target.
     * Otherwise returns the original name unchanged.
     */
    fun resolveActivityAlias(packageName: String, activityName: String): String =
        serviceManager.packageManager.resolveActivityClass(packageName, activityName)

    /**
     * Get the instance ID for a running app by process slot.
     */
    fun getInstanceIdForSlot(slot: Int): String? =
        runningApps.entries.find { it.value == slot }?.key

    /**
     * Convenience: get list of installed apps (snapshot).
     * Used by ViewModels that don't observe the StateFlow.
     */
    fun getInstalledApps(): List<VirtualApp> = _installedApps.value

    /**
     * Convenience: get current engine status.
     */
    fun getEngineStatus(): EngineStatus = _status.value

    /**
     * Launch a virtual app by package name (finds first matching instance).
     */
    fun launchAppByPackage(packageName: String): VmResult<Unit> {
        val app = findAppByPackage(packageName)
            ?: return VmResult.Error("No virtual app found for package: $packageName")
        return launchApp(app.instanceId)
    }

    /**
     * Uninstall a virtual app by package name (removes first matching instance).
     */
    fun uninstallAppByPackage(packageName: String): VmResult<Unit> {
        val app = findAppByPackage(packageName)
            ?: return VmResult.Error("No virtual app found for package: $packageName")
        return uninstallApp(app.instanceId)
    }

    // === Private helpers ===

    private fun ensureInitialized() {
        if (_status.value == EngineStatus.READY) return

        if (_status.value == EngineStatus.INITIALIZING) {
            // Wait for init to complete (max 30s)
            val startTime = System.currentTimeMillis()
            while (_status.value == EngineStatus.INITIALIZING) {
                if (System.currentTimeMillis() - startTime > 30_000) break
                Thread.sleep(100)
            }
        }

        check(_status.value == EngineStatus.READY) {
            "VirtualEngine not initialized (status=${_status.value}). " +
            "Call initialize() first or wait for async init to complete."
        }
    }

    private fun createVirtualDirectories() {
        virtualRoot = File(context.filesDir, VirtualConstants.VIRTUAL_DIR)
        apksDir = File(virtualRoot, VirtualConstants.VIRTUAL_APKS_DIR)
        dataDir = File(virtualRoot, VirtualConstants.VIRTUAL_DATA_DIR)

        virtualRoot.mkdirs()
        apksDir.mkdirs()
        dataDir.mkdirs()

        File(virtualRoot, VirtualConstants.VIRTUAL_SNAPSHOTS_DIR).mkdirs()

        Timber.tag(TAG).d("Virtual directories created at: ${virtualRoot.absolutePath}")
    }

    private fun copyApkToVirtualStorage(
        apkPath: String,
        instanceId: String,
        packageName: String
    ): String {
        val sourceFile = File(apkPath)
        val destFile = File(apksDir, "${instanceId}.apk")
        sourceFile.copyTo(destFile, overwrite = true)

        // Android 8.0+ (API 26+) rejects loading APKs/DEX files that are writable.
        // DexFile.openDexFileNative throws SecurityException: "Writable dex file is not allowed."
        // Strip all write permissions so the file is read-only (owner r--, group r--, other r--).
        destFile.setWritable(false, false)   // remove write for everyone
        destFile.setReadable(true, false)    // ensure readable by all
        destFile.setExecutable(false, false) // APKs don't need execute bit

        Timber.tag(TAG).d("APK copied to: ${destFile.absolutePath}")
        return destFile.absolutePath
    }

    /**
     * Discover split APKs for an installed host app.
     *
     * Split APKs (e.g., split_config.arm64_v8a.apk) contain native libraries
     * that are essential for apps like Unity games. Without these, the app
     * crashes with UnsatisfiedLinkError (e.g., "libmain.so not found").
     *
     * Discovery methods:
     * 1. Check the parent directory of the base APK for split_*.apk files
     * 2. Query the host PackageManager for ApplicationInfo.splitSourceDirs
     */
    private fun discoverSplitApks(baseApkPath: String, packageName: String): List<String> {
        val splits = mutableListOf<String>()

        // Method 1: Check parent directory for split_*.apk files
        // When installing from /data/app/.../base.apk, splits are siblings
        val parentDir = File(baseApkPath).parentFile
        if (parentDir != null && parentDir.isDirectory) {
            try {
                parentDir.listFiles()?.filter {
                    it.name.startsWith("split_") && it.name.endsWith(".apk") && it.isFile
                }?.forEach { splits.add(it.absolutePath) }
            } catch (e: Exception) {
                Timber.tag(TAG).d("Could not scan parent dir for splits: ${e.message}")
            }
        }

        // Method 2: Get from host PackageManager's ApplicationInfo.splitSourceDirs
        // On Android 11+, listFiles() often fails due to directory access restrictions,
        // but PM-reported paths are still directly readable via open().
        if (splits.isEmpty()) {
            try {
                @Suppress("DEPRECATION")
                val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                appInfo.splitSourceDirs?.forEach { splitPath ->
                    // Don't use File.exists() — on Android 11+ stat() may fail due to
                    // SELinux even though the file is readable. Trust PM-reported paths.
                    if (splitPath !in splits) {
                        splits.add(splitPath)
                    }
                }
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                // Package not installed on host — no splits to discover
            } catch (e: Exception) {
                Timber.tag(TAG).d("Could not get split paths from PM: ${e.message}")
            }
        }

        // Method 3: Construct known ABI split paths from base APK directory
        // Play Store app bundles use predictable naming: split_config.{abi}.apk
        if (splits.isEmpty() && parentDir != null) {
            val knownAbis = listOf("arm64_v8a", "armeabi_v7a", "x86_64", "x86")
            for (abi in knownAbis) {
                val splitFile = File(parentDir, "split_config.$abi.apk")
                try {
                    java.util.zip.ZipFile(splitFile).use { /* accessible */ }
                    splits.add(splitFile.absolutePath)
                    Timber.tag(TAG).d("Found ABI split via probe: ${splitFile.name}")
                } catch (_: Exception) { /* not accessible, skip */ }
            }
        }

        if (splits.isNotEmpty()) {
            Timber.tag(TAG).i("Found ${splits.size} split APKs: ${splits.map { File(it).name }}")
        } else {
            Timber.tag(TAG).w("No split APKs found for $packageName (base=$baseApkPath)")
        }

        return splits
    }

    private fun updateAppState(instanceId: String, transform: (VirtualApp) -> VirtualApp) {
        val current = _installedApps.value.toMutableList()
        val index = current.indexOfFirst { it.instanceId == instanceId }
        if (index >= 0) {
            current[index] = transform(current[index])
            _installedApps.value = current
        }
    }

    private fun saveVirtualApp(app: VirtualApp) {
        val file = File(virtualRoot, "apps/${app.instanceId}.json")
        file.parentFile?.mkdirs()
        val gson = com.google.gson.Gson()
        val data = VirtualAppData.fromVirtualApp(app)
        file.writeText(gson.toJson(data))
    }

    private fun deleteSavedApp(instanceId: String) {
        File(virtualRoot, "apps/${instanceId}.json").delete()
    }

    private fun loadInstalledApps() {
        val appsDir = File(virtualRoot, "apps")
        if (!appsDir.exists()) return

        val apps = mutableListOf<VirtualApp>()
        appsDir.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            try {
                val json = file.readText()
                val app = parseAppJson(json)
                if (app != null && File(app.apkPath).exists()) {
                    // Reload icon from APK since Drawable is not serialized
                    var appWithIcon = reloadIconFromApk(app)

                    // Repair launcher activities saved by older parser versions.
                    // Archive PackageInfo omits intent filters, so the old
                    // name-based heuristic could select MainActivity instead of
                    // the actual MAIN + LAUNCHER privacy/splash entry point.
                    val manifestLauncher = frameworkApkParser.findMainLauncherActivity(
                        File(appWithIcon.apkPath)
                    )
                    if (manifestLauncher != null &&
                        manifestLauncher != appWithIcon.mainActivity
                    ) {
                        Timber.tag(TAG).i(
                            "Updated launcher for ${appWithIcon.packageName}: " +
                                "${appWithIcon.mainActivity} → $manifestLauncher"
                        )
                        appWithIcon = appWithIcon.copy(mainActivity = manifestLauncher)
                        saveVirtualApp(appWithIcon)
                    }

                    // Re-extract native libs if missing OR wrong ABI (e.g. arm64 on x86_64 emulator)
                    val dataRoot = File(virtualRoot, "data")
                    val reResult = NativeLibManager.reExtractIfMissing(
                        apkPath = appWithIcon.apkPath,
                        splitApkPaths = appWithIcon.splitApkPaths,
                        instanceId = appWithIcon.instanceId,
                        dataRoot = dataRoot
                    )
                    if (reResult != null) {
                        appWithIcon = appWithIcon.copy(
                            libDir = reResult.libDir,
                            librarySearchPath = reResult.librarySearchPath,
                            primaryAbi = reResult.selectedAbi
                        )
                        saveVirtualApp(appWithIcon)
                    } else if (appWithIcon.librarySearchPath.contains("arm64") &&
                        !android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm") }
                    ) {
                        // Stale search path from a previous bad install — rebuild for this device
                        val rebuilt = NativeLibManager.rebuildLibrarySearchPath(
                            libDir = appWithIcon.libDir.ifEmpty {
                                File(dataRoot, "${appWithIcon.instanceId}/lib").absolutePath
                            },
                            apkPath = appWithIcon.apkPath,
                            splitApkPaths = appWithIcon.splitApkPaths,
                            preferredAbi = appWithIcon.primaryAbi
                        )
                        appWithIcon = appWithIcon.copy(
                            librarySearchPath = rebuilt,
                            primaryAbi = android.os.Build.SUPPORTED_ABIS.firstOrNull()
                                ?: appWithIcon.primaryAbi
                        )
                        saveVirtualApp(appWithIcon)
                        Timber.tag(TAG).w(
                            "Rebuilt librarySearchPath for ${appWithIcon.packageName}: $rebuilt"
                        )
                    }

                    apps.add(appWithIcon)
                    processManager.allocateSlot(appWithIcon.instanceId)

                    // Re-register in VPMS on load (lightweight, from disk)
                    serviceManager.packageManager.registerPackageFromDisk(appWithIcon)

                    // Re-initialize permissions and auto-grant all on reload
                    serviceManager.permissionManager.initializePermissions(
                        packageName = appWithIcon.packageName,
                        requestedPermissions = appWithIcon.requestedPermissions,
                        overrides = appWithIcon.permissionOverrides
                    )
                    serviceManager.permissionManager.grantAllPermissions(appWithIcon.packageName)

                    // Re-register with Binder proxies
                    if (::binderProxyManager.isInitialized) {
                        binderProxyManager.registerVirtualApp(appWithIcon)
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to load app from: ${file.name}")
            }
        }

        _installedApps.value = apps
        Timber.tag(TAG).d("Loaded ${apps.size} installed apps")
    }

    // NOTE: GMS auto-install has been removed. NEXTVM now uses a Hybrid GMS Bridge
    // that proxies guest app GMS calls to the host device's real Google Play Services
    // without copying GMS APKs into the virtual environment.
    // See: core/services/gms/GmsBinderBridge.kt

    private fun parseAppJson(json: String): VirtualApp? {
        return try {
            val gson = com.google.gson.Gson()
            gson.fromJson(json, VirtualAppData::class.java)?.toVirtualApp()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "JSON parse error")
            null
        }
    }

    /**
     * Reload the app icon from the APK file on disk.
     * Icons are Drawable (not serializable), so they are lost after JSON round-trip.
     * This uses PackageManager.getPackageArchiveInfo() which is fast and reliable.
     */
    private fun reloadIconFromApk(app: VirtualApp): VirtualApp {
        if (app.icon != null) return app
        return try {
            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(app.apkPath, 0)
            if (info?.applicationInfo != null) {
                info.applicationInfo!!.sourceDir = app.apkPath
                info.applicationInfo!!.publicSourceDir = app.apkPath
                val icon = pm.getApplicationIcon(info.applicationInfo!!)
                app.copy(icon = icon)
            } else {
                app
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d("Failed to reload icon for ${app.packageName}: ${e.message}")
            app
        }
    }

    // ==================== ActivityThreadHook Helper Methods ====================

    /**
     * Ensure the guest app's Application class has been created and initialized.
     * This MUST run before any Activity is launched for the guest app.
     *
     * The boot sequence mirrors real Android:
     * 1. Create ClassLoader (already done by getOrCreateClassLoader)
     * 2. Create VirtualContext for the Application
     * 3. Instantiate the Application subclass via ClassLoader
     * 4. Install ContentProviders (BEFORE onCreate!)
     * 5. Call Application.attachBaseContext()
     * 6. Call Application.onCreate()
     */
    fun ensureGuestApplicationBooted(instanceId: String, packageName: String, apkPath: String) {
        // Skip if already booted
        if (applicationManager.getApplication(instanceId) != null) {
            Timber.tag(TAG).d("Guest Application already booted for $instanceId")
            return
        }

        Timber.tag(TAG).i("Booting guest Application for $instanceId ($packageName)")

        try {
            // Get the app metadata
            val app = findApp(instanceId) ?: run {
                Timber.tag(TAG).w("App not found for $instanceId, skipping Application boot")
                return
            }

            // Get or create ClassLoader
            val classLoader = getOrCreateClassLoader(instanceId, apkPath)

            // Create a VirtualContext for the Application
            val virtualContext = com.nextvm.core.sandbox.VirtualContext(
                base = context,
                instanceId = instanceId,
                guestPackageName = packageName,
                sandboxManager = sandboxManager
            )
            virtualContext.setGuestClassLoader(classLoader)

            // Set binder interceptor for GMS binder proxying on bindService()
            setupBinderInterceptor(virtualContext, instanceId, packageName)

            // CRITICAL: Create and inject guest Resources BEFORE Application boot.
            // Without this, guest code calling context.getString(R.string.foo) or
            // ContentProvider.onCreate() -> context.getString() will look up resource IDs
            // in the HOST's resource table, causing Resources$NotFoundException for IDs
            // like 0x7f120025 that only exist in the guest APK.
            try {
                val guestResources = resourceManager.createResourcesForApp(
                    context = context,
                    apkPath = apkPath,
                    splitApkPaths = app.splitApkPaths.ifEmpty { null },
                    libDir = getNativeLibDir(instanceId),
                    classLoader = classLoader,
                    instanceId = instanceId
                )
                if (guestResources != null) {
                    virtualContext.setGuestResources(guestResources)
                    Timber.tag(TAG).d("Guest Resources injected for $instanceId")
                } else {
                    Timber.tag(TAG).w("Failed to create guest Resources for $instanceId — resource lookups may fail")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error creating guest Resources for $instanceId")
            }

            // Get Application class name from parsed APK metadata
            val applicationClassName = app.applicationClassName

            // Get ContentProvider info for auto-init
            val providerNames = app.providers
            val providerAuthorities = mutableMapOf<String, String>()
            // Build authority map from VPMS
            for (providerName in providerNames) {
                val authority = serviceManager.packageManager
                    .getProviderAuthority(packageName, providerName)
                if (authority != null) {
                    providerAuthorities[providerName] = authority
                }
            }

            // Spoof /proc/self/cmdline before Application boot so isMainProcess
            // checks that read cmdline see the guest package name.
            try {
                nativeHookBridge.spoofProcSelf(android.os.Process.myPid(), packageName)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "spoofProcSelf failed for $packageName (non-fatal)")
            }

            // Boot the Application via ApplicationManager
            applicationManager.bindApplication(
                instanceId = instanceId,
                packageName = packageName,
                applicationClassName = applicationClassName,
                apkPath = apkPath,
                classLoader = classLoader,
                virtualContext = virtualContext,
                providerNames = providerNames,
                providerAuthorities = providerAuthorities
            )

            Timber.tag(TAG).i("Guest Application booted successfully for $instanceId")
        } catch (e: UnsatisfiedLinkError) {
            // Native library failed to load — mark as booted anyway to prevent
            // infinite crash loops (the guest process keeps restarting otherwise).
            // The guest app will likely malfunction but at least the system won't loop.
            Timber.tag(TAG).e(e, "Native library load failed for $instanceId — guest app may not work correctly")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to boot guest Application for $instanceId")
        }
    }

    /**
     * Get the native library directory for a guest app instance.
     */
    fun getNativeLibDir(instanceId: String): String? {
        val app = findApp(instanceId) ?: return null
        val libDir = File(virtualRoot, "data/${app.instanceId}/lib")
        return if (libDir.exists()) libDir.absolutePath else null
    }

    /**
     * Get the data directory for a guest app instance.
     */
    fun getDataDir(instanceId: String): String? {
        val dataPath = File(virtualRoot, "data/$instanceId")
        return if (dataPath.exists()) dataPath.absolutePath else null
    }

    /**
     * Get the VirtualResourceManager.
     */
    fun getResourceManager(): VirtualResourceManager? {
        return resourceManager
    }

    /**
     * Get the theme resource ID for a guest app's activity.
     * Returns activity-specific theme if set, falls back to application theme.
     */
    fun getThemeResId(instanceId: String): Int {
        val app = findApp(instanceId) ?: return 0
        return app.metaData["_nextvm_theme_res_id"]?.toIntOrNull() ?: 0
    }

    /**
     * Get theme resource ID from parsed manifest via PackageManagerProxy.
     * Returns activity-specific theme, falls back to application theme.
     */
    fun getActivityThemeResId(packageName: String, activityName: String): Int {
        if (!::binderProxyManager.isInitialized) return 0
        return binderProxyManager.getActivityTheme(packageName, activityName)
    }

    /**
     * Rewrite guest startActivity Intents onto host stubs.
     * Called from NextVmInstrumentation.execStartActivity — see ActivityManagerProxy.
     */
    fun rewriteOutgoingStartActivityIntent(intent: android.content.Intent): Boolean {
        if (!::binderProxyManager.isInitialized) return false
        return binderProxyManager.rewriteOutgoingStartActivityIntent(intent)
    }

    /**
     * Get the guest Application instance for a virtual app.
     */
    fun getGuestApplication(instanceId: String): android.app.Application? {
        return applicationManager.getApplication(instanceId)
    }

    /**
     * Wrap an Activity's base context with VirtualContext for file isolation.
     *
     * CRITICAL: We must wrap the Activity's CURRENT base context (not the Activity itself)
     * to avoid circular ContextWrapper delegation that causes StackOverflowError
     * in getDisplay()/getDisplayNoVerify() calls.
     */
    fun wrapActivityContext(
        activity: android.app.Activity,
        instanceId: String,
        packageName: String
    ) {
        try {
            // Get the Activity's current base context (the real ContextImpl)
            val mBaseField = android.content.ContextWrapper::class.java.getDeclaredField("mBase")
            mBaseField.isAccessible = true
            val currentBase = mBaseField.get(activity) as? android.content.Context
                ?: run {
                    Timber.tag(TAG).w("Activity base context is null for $instanceId")
                    return
                }

            // Don't double-wrap
            if (currentBase is com.nextvm.core.sandbox.VirtualContext) {
                Timber.tag(TAG).d("Activity already wrapped for $instanceId")
                return
            }

            val virtualContext = com.nextvm.core.sandbox.VirtualContext(
                base = currentBase,  // wrap the base, NOT the activity
                instanceId = instanceId,
                guestPackageName = packageName,
                sandboxManager = sandboxManager
            )

            // Set binder interceptor for GMS binder proxying on bindService()
            setupBinderInterceptor(virtualContext, instanceId, packageName)

            // Set guest ClassLoader on the virtual context
            val guestCl = getClassLoader(instanceId)
            if (guestCl != null) {
                virtualContext.setGuestClassLoader(guestCl)
            }

            // CRITICAL: Set guest Application so getApplicationContext() returns the
            // guest Application, NOT the host NextVmApplication. Without this:
            // 1. Hilt/Dagger apps crash: they cast getApplicationContext() to their
            //    Application subclass which implements GeneratedComponentManager
            // 2. GMS "Unknown calling package" errors: GMS SDK calls
            //    getApplicationContext().bindService() which bypasses VirtualContext's
            //    bindService() override → binder not wrapped → package not rewritten
            val guestApp = getGuestApplication(instanceId)
            if (guestApp != null) {
                virtualContext.setGuestApplication(guestApp)
            }

            // Replace the Activity's base context
            mBaseField.set(activity, virtualContext)
            Timber.tag(TAG).d("VirtualContext wrapped for activity $instanceId")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to wrap activity context for $instanceId")
        }
    }

    /**
     * Internal data class for JSON serialization.
     * Includes all Android 16 framework fields.
     */
    private data class VirtualAppData(
        val packageName: String = "",
        val appName: String = "",
        val versionName: String = "",
        val versionCode: Long = 0,
        val apkPath: String = "",
        val instanceId: String = "",
        val processSlot: Int = -1,
        val installedAt: Long = 0,
        val is64Bit: Boolean = true,
        val mainActivity: String? = null,
        val activities: List<String> = emptyList(),
        val services: List<String> = emptyList(),
        val providers: List<String> = emptyList(),
        val receivers: List<String> = emptyList(),
        // Android 16 framework fields
        val minSdkVersion: Int = 1,
        val targetSdkVersion: Int = 35,
        val isMultiArch: Boolean = false,
        val use32bitAbi: Boolean = false,
        val extractNativeLibs: Boolean = true,
        val useEmbeddedDex: Boolean = false,
        val isDebuggable: Boolean = false,
        val installLocation: Int = 0,
        val pageSizeCompat: Int = 0,
        val splitApkPaths: List<String> = emptyList(),
        val splitNames: List<String> = emptyList(),
        val hasSplitApks: Boolean = false,
        val isolatedSplits: Boolean = false,
        val applicationClassName: String? = null,
        val requestedPermissions: List<String> = emptyList(),
        val nativeAbis: List<String> = emptyList(),
        val metaData: Map<String, String> = emptyMap(),
        val activityAliases: Map<String, String> = emptyMap(),
        val dataDir: String = "",
        val libDir: String = "",
        val librarySearchPath: String = "",
        val primaryAbi: String = "",
        val cacheDir: String = ""
    ) {
        fun toVirtualApp() = VirtualApp(
            packageName = packageName,
            appName = appName,
            versionName = versionName,
            versionCode = versionCode,
            apkPath = apkPath,
            instanceId = instanceId,
            processSlot = processSlot,
            installedAt = installedAt,
            is64Bit = is64Bit,
            mainActivity = mainActivity,
            activities = activities,
            services = services,
            providers = providers,
            receivers = receivers,
            minSdkVersion = minSdkVersion,
            targetSdkVersion = targetSdkVersion,
            isMultiArch = isMultiArch,
            use32bitAbi = use32bitAbi,
            extractNativeLibs = extractNativeLibs,
            useEmbeddedDex = useEmbeddedDex,
            isDebuggable = isDebuggable,
            installLocation = installLocation,
            pageSizeCompat = pageSizeCompat,
            splitApkPaths = splitApkPaths,
            splitNames = splitNames,
            hasSplitApks = hasSplitApks,
            isolatedSplits = isolatedSplits,
            applicationClassName = applicationClassName,
            requestedPermissions = requestedPermissions,
            nativeAbis = nativeAbis,
            metaData = metaData,
            activityAliases = activityAliases,
            dataDir = dataDir,
            libDir = libDir,
            librarySearchPath = librarySearchPath,
            primaryAbi = primaryAbi,
            cacheDir = cacheDir
        )

        companion object {
            fun fromVirtualApp(app: VirtualApp) = VirtualAppData(
                packageName = app.packageName,
                appName = app.appName,
                versionName = app.versionName,
                versionCode = app.versionCode,
                apkPath = app.apkPath,
                instanceId = app.instanceId,
                processSlot = app.processSlot,
                installedAt = app.installedAt,
                is64Bit = app.is64Bit,
                mainActivity = app.mainActivity,
                activities = app.activities,
                services = app.services,
                providers = app.providers,
                receivers = app.receivers,
                minSdkVersion = app.minSdkVersion,
                targetSdkVersion = app.targetSdkVersion,
                isMultiArch = app.isMultiArch,
                use32bitAbi = app.use32bitAbi,
                extractNativeLibs = app.extractNativeLibs,
                useEmbeddedDex = app.useEmbeddedDex,
                isDebuggable = app.isDebuggable,
                installLocation = app.installLocation,
                pageSizeCompat = app.pageSizeCompat,
                splitApkPaths = app.splitApkPaths,
                splitNames = app.splitNames,
                hasSplitApks = app.hasSplitApks,
                isolatedSplits = app.isolatedSplits,
                applicationClassName = app.applicationClassName,
                requestedPermissions = app.requestedPermissions,
                nativeAbis = app.nativeAbis,
                metaData = app.metaData,
                activityAliases = app.activityAliases,
                dataDir = app.dataDir,
                libDir = app.libDir,
                librarySearchPath = app.librarySearchPath,
                primaryAbi = app.primaryAbi,
                cacheDir = app.cacheDir
            )
        }
    }
}
