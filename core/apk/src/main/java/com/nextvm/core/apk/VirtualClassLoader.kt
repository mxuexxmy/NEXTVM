package com.nextvm.core.apk

import android.os.Build
import dalvik.system.PathClassLoader
import timber.log.Timber
import java.io.File
import java.util.zip.ZipFile

/**
 * VirtualClassLoader — Creates isolated ClassLoaders for guest apps.
 *
 * Each virtual app gets its own PathClassLoader that:
 * 1. Loads classes from ALL dex files in the guest APK (multi-dex safe)
 * 2. Uses a private optimized DEX cache directory
 * 3. Adds the extracted native library path for .so loading
 * 4. Has SystemClassLoader as parent (isolated from host app)
 *
 * IMPORTANT: We use PathClassLoader instead of DexClassLoader because:
 * - On API 26+, PathClassLoader can load from any path (not just /data/app)
 * - PathClassLoader correctly loads ALL .dex files in the APK (multi-dex)
 * - DexClassLoader may only load classes.dex, missing classes2.dex+ etc.
 */
object VirtualClassLoader {

    private const val TAG = "VClassLoader"

    /**
     * Create a ClassLoader for a guest app.
     *
     * @param apkPath Path to the guest APK
     * @param instanceId Unique instance ID for the virtual app
     * @param parentDir Root directory for virtual storage
     * @param prebuiltLibrarySearchPath Pre-built library search path from NativeLibManager (if available)
     * @return Configured ClassLoader that can load all classes from the APK
     */
    fun createClassLoader(
        apkPath: String,
        instanceId: String,
        parentDir: File,
        prebuiltLibrarySearchPath: String? = null,
        splitApkPaths: List<String> = emptyList()
    ): ClassLoader {
        // Optimized DEX cache directory
        val optimizedDir = File(parentDir, "data/$instanceId/code_cache")
        optimizedDir.mkdirs()

        // Native library directory (extracted .so files)
        val libDir = File(parentDir, "data/$instanceId/lib")
        libDir.mkdirs()

        Timber.tag(TAG).d(
            "Creating ClassLoader: apk=$apkPath, " +
            "dexCache=${optimizedDir.absolutePath}, " +
            "nativeLib=${libDir.absolutePath}"
        )

        // Android 8.0+ (API 26+) rejects loading APKs that are world-writable.
        // DexFile.openDexFileNative throws:
        //   SecurityException: Writable dex file '<path>' is not allowed.
        // Ensure the APK is read-only before creating the ClassLoader.
        val apkFile = File(apkPath)
        if (apkFile.exists() && apkFile.canWrite()) {
            apkFile.setWritable(false, false)  // remove write for all (owner, group, other)
            apkFile.setReadable(true, false)   // ensure readable
            Timber.tag(TAG).d("Enforced read-only on APK: $apkPath")
        }

        // Use PathClassLoader which properly handles multi-dex APKs.
        // It loads ALL .dex files (classes.dex, classes2.dex, classes3.dex, ...)
        // unlike DexClassLoader which may only load the primary classes.dex.
        //
        // The librarySearchPath format: dir1:dir2:...
        // Parent = system ClassLoader (NOT context.classLoader)
        // This ensures guest app classes don't conflict with host app
        //
        // CRITICAL: Include the APK path itself with !/lib/{abi}/ suffix.
        // This allows the linker to load .so files directly from the APK
        // (required when extractNativeLibs=false, and as fallback if extraction missed libs).
        val primaryAbi = Build.SUPPORTED_ABIS[0]

        // Use pre-built library search path from NativeLibManager if available,
        // otherwise construct a basic one (backward compatibility).
        val librarySearchPath = if (!prebuiltLibrarySearchPath.isNullOrEmpty()) {
            prebuiltLibrarySearchPath
        } else {
            val apkLibPath = "$apkPath!/lib/$primaryAbi/"
            "${libDir.absolutePath}:$apkLibPath"
        }

        Timber.tag(TAG).d("Library search path: $librarySearchPath")

        // Build dex path: base APK + all split APKs (colon-separated)
        // Split APKs may contain additional dex files and native libraries
        val dexPath = if (splitApkPaths.isNotEmpty()) {
            val allPaths = buildList {
                add(apkPath)
                addAll(splitApkPaths.filter { File(it).exists() })
            }
            Timber.tag(TAG).d("Including ${splitApkPaths.size} split APKs in dex path")
            allPaths.joinToString(":")
        } else {
            apkPath
        }

        // Also extend library search path with split APK native lib paths
        val fullLibrarySearchPath = if (splitApkPaths.isNotEmpty()) {
            val splitLibPaths = splitApkPaths
                .filter { File(it).exists() }
                .map { "$it!/lib/$primaryAbi/" }
            if (splitLibPaths.isNotEmpty()) {
                "$librarySearchPath:${splitLibPaths.joinToString(":")}"
            } else {
                librarySearchPath
            }
        } else {
            librarySearchPath
        }

        // GuestPathClassLoader hides java.lang.ClassValue from guest-initiated
        // Class.forName lookups so kotlin-reflect / kotlinx caches take the
        // ConcurrentHashMap path. R8-stripped ComputableClassValue.computeValue
        // otherwise crashes with AbstractMethodError on API 34+ (Android 14+).
        val classLoader = GuestPathClassLoader(
            dexPath,
            fullLibrarySearchPath,
            ClassLoader.getSystemClassLoader()
        )

        // CRITICAL FIX: The 3-param PathClassLoader constructor leaves the
        // `sharedLibraries` and `sharedLibrariesLoadedAfterApp` fields as null.
        // When Runtime.nativeLoad() is called, the native linker code (libnativeloader)
        // accesses these fields via JNI GetObjectArrayElement — if they're null,
        // CheckJNI aborts with: "jarray was NULL in call to GetObjectArrayElement".
        // Set them to empty arrays to prevent the SIGABRT crash.
        initSharedLibraryFields(classLoader)

        // Create a linker namespace for this ClassLoader.
        // Without this, the native linker has no namespace to resolve library paths.
        // The framework normally does this via ClassLoaderFactory.createClassloaderNamespace().
        createLinkerNamespace(classLoader, dexPath, fullLibrarySearchPath)

        return classLoader
    }

    /**
     * Initialize the sharedLibraries fields on BaseDexClassLoader to empty arrays.
     *
     * The 3-param PathClassLoader constructor leaves these fields null.
     * libnativeloader's native code accesses them via JNI without null checks,
     * causing SIGABRT when GetObjectArrayElement is called on a null jarray.
     */
    private fun initSharedLibraryFields(classLoader: ClassLoader) {
        try {
            val bdclClass = Class.forName("dalvik.system.BaseDexClassLoader")

            // Use Unsafe to bypass 'final' modifier on these fields
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val theUnsafeField = unsafeClass.getDeclaredField("theUnsafe")
            theUnsafeField.isAccessible = true
            val unsafe = theUnsafeField.get(null)
            val objectFieldOffsetMethod = unsafeClass.getDeclaredMethod(
                "objectFieldOffset", java.lang.reflect.Field::class.java
            )
            val putObjectMethod = unsafeClass.getDeclaredMethod(
                "putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java
            )
            val getObjectMethod = unsafeClass.getDeclaredMethod(
                "getObject", Any::class.java, Long::class.javaPrimitiveType
            )

            val emptyClassLoaderArray = emptyArray<ClassLoader>()

            // Fix sharedLibraries
            try {
                val sharedLibsField = bdclClass.getDeclaredField("sharedLibraries")
                val offset = objectFieldOffsetMethod.invoke(unsafe, sharedLibsField) as Long
                if (getObjectMethod.invoke(unsafe, classLoader, offset) == null) {
                    putObjectMethod.invoke(unsafe, classLoader, offset, emptyClassLoaderArray)
                    Timber.tag(TAG).d("Set sharedLibraries to empty array")
                }
            } catch (_: NoSuchFieldException) {
                // Field doesn't exist on this Android version — OK
            }

            // Fix sharedLibrariesLoadedAfterApp (Android 14+)
            try {
                val afterAppField = bdclClass.getDeclaredField("sharedLibrariesLoadedAfterApp")
                val offset = objectFieldOffsetMethod.invoke(unsafe, afterAppField) as Long
                if (getObjectMethod.invoke(unsafe, classLoader, offset) == null) {
                    putObjectMethod.invoke(unsafe, classLoader, offset, emptyClassLoaderArray)
                    Timber.tag(TAG).d("Set sharedLibrariesLoadedAfterApp to empty array")
                }
            } catch (_: NoSuchFieldException) {
                // Field doesn't exist on this Android version — OK
            }

        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to init sharedLibrary fields (non-fatal)")
        }
    }

    /**
     * Create a linker namespace for a manually-created ClassLoader.
     * This mirrors what ClassLoaderFactory.createClassloaderNamespace() does
     * when the framework creates ClassLoaders via ApplicationLoaders.
     */
    private fun createLinkerNamespace(
        classLoader: ClassLoader,
        dexPath: String,
        librarySearchPath: String
    ) {
        try {
            val factoryClass = Class.forName("com.android.internal.os.ClassLoaderFactory")
            val createMethod = factoryClass.getDeclaredMethod(
                "createClassloaderNamespace",
                ClassLoader::class.java,
                Int::class.javaPrimitiveType,
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                String::class.java,
                String::class.java
            )
            createMethod.isAccessible = true

            // CRITICAL FIX: libraryPermittedPath must include the directories
            // where the guest app's .so files live. Without this, the linker
            // namespace blocks loading from our virtual data directory.
            // Include: the lib dir, the APK path (for in-APK .so loading),
            // and /data/user/0/ as a broad permitted path.
            val permittedPath = "/data:/mnt/expand"

            val errorMessage = createMethod.invoke(
                null,
                classLoader,
                Build.VERSION.SDK_INT,
                librarySearchPath,
                permittedPath,  // was null — now includes data paths
                false,  // isNamespaceShared
                dexPath,
                ""      // sonameList (empty = default)
            ) as? String

            if (errorMessage != null) {
                Timber.tag(TAG).w("createClassloaderNamespace returned error: $errorMessage")
            } else {
                Timber.tag(TAG).d("Linker namespace created with permitted path: $permittedPath")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to create linker namespace (non-fatal, will use default)")
        }
    }

    /**
     * Extract native libraries (.so files) from an APK.
     *
     * Extracts libraries matching the device's primary ABI.
     * On arm64 devices, this extracts all .so files from lib/arm64-v8a/.
     *
     * @param apkPath Path to the APK
     * @param libDir Target directory for extracted libraries
     * @return List of extracted library file names
     */
    fun extractNativeLibs(apkPath: String, libDir: File): List<String> {
        libDir.mkdirs()

        val primaryAbi = Build.SUPPORTED_ABIS[0]  // e.g., "arm64-v8a"
        val prefix = "lib/$primaryAbi/"
        val extracted = mutableListOf<String>()

        try {
            ZipFile(apkPath).use { zip ->
                // Debug: log all lib/ entries to diagnose extraction failures
                val allLibEntries = zip.entries().asSequence()
                    .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                    .map { it.name }
                    .toList()
                if (allLibEntries.isNotEmpty()) {
                    val abiGroups = allLibEntries.groupBy { it.split("/").getOrElse(1) { "unknown" } }
                    Timber.tag(TAG).d("APK native libs found: ${abiGroups.map { "${it.key}: ${it.value.size}" }}")
                } else {
                    Timber.tag(TAG).d("No native libs found in APK")
                }

                // Re-open entries for extraction (ZipFile entries iterator is consumed)
                zip.entries().asSequence()
                    .filter { entry ->
                        entry.name.startsWith(prefix) &&
                        entry.name.endsWith(".so") &&
                        !entry.isDirectory
                    }
                    .forEach { entry ->
                        val soName = entry.name.substringAfterLast("/")
                        val outFile = File(libDir, soName)

                        // Skip if already extracted and same size
                        if (outFile.exists() && outFile.length() == entry.size) {
                            extracted.add(soName)
                            return@forEach
                        }

                        zip.getInputStream(entry).use { input ->
                            outFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }

                        // Make executable
                        outFile.setExecutable(true, false)
                        outFile.setReadable(true, false)
                        extracted.add(soName)

                        Timber.tag(TAG).d("Extracted native lib: $soName (${entry.size} bytes)")
                    }
            }

            // If primary ABI libs not found, try ALL fallback ABIs
            if (extracted.isEmpty() && Build.SUPPORTED_ABIS.size > 1) {
                for (i in 1 until Build.SUPPORTED_ABIS.size) {
                    val fallbackAbi = Build.SUPPORTED_ABIS[i]
                    val fallbackPrefix = "lib/$fallbackAbi/"

                    ZipFile(apkPath).use { zip ->
                        zip.entries().asSequence()
                            .filter { it.name.startsWith(fallbackPrefix) && it.name.endsWith(".so") && !it.isDirectory }
                            .forEach { entry ->
                                val soName = entry.name.substringAfterLast("/")
                                val outFile = File(libDir, soName)

                                zip.getInputStream(entry).use { input ->
                                    outFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                outFile.setExecutable(true, false)
                                outFile.setReadable(true, false)
                                extracted.add(soName)

                                Timber.tag(TAG).d("Extracted native lib (fallback $fallbackAbi): $soName")
                            }
                    }
                    if (extracted.isNotEmpty()) break // Found libs in this ABI, stop
                }
            }

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to extract native libs from $apkPath")
        }

        Timber.tag(TAG).i("Extracted ${extracted.size} native libraries for $primaryAbi")
        return extracted
    }

    /**
     * Check if an APK has native libraries for the device's ABI.
     */
    fun hasNativeLibs(apkPath: String): Boolean {
        try {
            ZipFile(apkPath).use { zip ->
                return zip.entries().asSequence().any { entry ->
                    entry.name.startsWith("lib/") && entry.name.endsWith(".so")
                }
            }
        } catch (e: Exception) {
            return false
        }
    }
}

/**
 * PathClassLoader for guest apps that forces kotlin-reflect off the broken
 * ClassValue cache path used by some R8-shrunk APKs on Android 14+.
 *
 * kotlin-reflect probes `Class.forName("java.lang.ClassValue")` and, if found,
 * instantiates ComputableClassValue. When R8 previously stripped computeValue
 * (compiled against an android.jar without ClassValue), that path throws
 * AbstractMethodError. Hiding ClassValue only for guest-initiated lookups
 * makes the probe fail and selects ConcurrentHashMapCache instead.
 *
 * Boot/framework code still sees the real ClassValue via the parent loader;
 * we only intercept loadClass on this guest loader (the initiating loader for
 * guest kotlin-reflect).
 */
internal class GuestPathClassLoader(
    dexPath: String,
    librarySearchPath: String,
    parent: ClassLoader
) : PathClassLoader(dexPath, librarySearchPath, parent) {

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name == CLASS_VALUE_NAME) {
            throw ClassNotFoundException(name)
        }
        return super.loadClass(name, resolve)
    }

    companion object {
        private const val CLASS_VALUE_NAME = "java.lang.ClassValue"
    }
}
