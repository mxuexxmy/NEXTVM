package com.nextvm.core.sandbox

import android.os.Build
import timber.log.Timber
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * NativeLibManager — Comprehensive native library extraction for ALL guest apps.
 *
 * Handles three cases:
 * 1. Standard extraction: .so files extracted from APK to lib dir
 * 2. extractNativeLibs=false: .so stays page-aligned in APK, loaded directly
 * 3. Split APK: native libs may be in config.arm64_v8a.apk or similar splits
 *
 * IMPORTANT: Only extracts ABIs present in [Build.SUPPORTED_ABIS]. Never fall back
 * to arm64-v8a on an x86_64 emulator — that produces UnsatisfiedLinkError
 * ("is for EM_AARCH64 instead of EM_X86_64").
 *
 * Call [extractNativeLibs] during installApp() to process all APK paths.
 */
object NativeLibManager {

    private const val TAG = "NativeLibMgr"

    // ELF e_machine values
    private const val EM_386 = 3
    private const val EM_X86_64 = 62
    private const val EM_ARM = 40
    private const val EM_AARCH64 = 183

    /** Device-native ABI priority only — never invent incompatible architectures. */
    val ABI_ORDER: List<String> by lazy {
        Build.SUPPORTED_ABIS.toList().distinct()
    }

    /**
     * Main entry point — call during installApp().
     * Handles all 3 cases: standard APK, extractNativeLibs=false, split APKs.
     *
     * @param apkPaths Main APK + all split APK paths
     * @param instanceId Unique virtual app instance ID
     * @param dataRoot Root data directory (e.g., virtualRoot/data)
     * @return [NativeLibResult] with paths and extraction stats
     */
    fun extractNativeLibs(
        apkPaths: List<String>,
        instanceId: String,
        dataRoot: File
    ): NativeLibResult {
        val libDir = File(dataRoot, "$instanceId/lib").also { it.mkdirs() }

        // Drop previously extracted libs that don't match this device's ABI
        // (e.g. arm64 .so left behind from a bad install on an x86_64 emulator).
        purgeIncompatibleLibs(libDir)

        var totalExtracted = 0
        var selectedAbi = ""
        var availableButIncompatible = emptySet<String>()

        for (apkPath in apkPaths) {
            val result = extractFromApk(apkPath, libDir)
            totalExtracted += result.count
            if (selectedAbi.isEmpty() && result.abi.isNotEmpty()) {
                selectedAbi = result.abi
            }
            if (result.incompatibleAbis.isNotEmpty()) {
                availableButIncompatible = availableButIncompatible + result.incompatibleAbis
            }
        }

        // If no ABI detected from libs, use device primary for search-path construction
        if (selectedAbi.isEmpty()) {
            selectedAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
            if (availableButIncompatible.isNotEmpty()) {
                Timber.tag(TAG).e(
                    "No device-compatible native ABI for $instanceId. " +
                        "APK has $availableButIncompatible but device supports ${ABI_ORDER}. " +
                        "Native code will fail to load on this emulator/device."
                )
            }
        }

        Timber.tag(TAG).i("Extracted $totalExtracted .so files for $instanceId (abi=$selectedAbi)")

        val libPaths = buildLibraryPath(libDir, apkPaths, selectedAbi)

        return NativeLibResult(
            libDir = libDir.absolutePath,
            librarySearchPath = libPaths,
            selectedAbi = selectedAbi,
            extractedCount = totalExtracted,
            hasCompatibleAbi = totalExtracted > 0 || availableButIncompatible.isEmpty()
        )
    }

    private fun extractFromApk(apkPath: String, libDir: File): SingleApkResult {
        if (!File(apkPath).exists()) return SingleApkResult(0, "", emptySet())

        return try {
            ZipFile(apkPath).use { zip ->
                val availableAbis = collectApkAbis(zip)
                if (availableAbis.isEmpty()) return SingleApkResult(0, "", emptySet())

                Timber.tag(TAG).d("APK ABIs available: $availableAbis (device=$ABI_ORDER)")

                val bestAbi = ABI_ORDER.firstOrNull { it in availableAbis }
                if (bestAbi == null) {
                    Timber.tag(TAG).w(
                        "Skipping native extract for $apkPath — no ABI match " +
                            "(apk=$availableAbis, device=$ABI_ORDER)"
                    )
                    return SingleApkResult(0, "", availableAbis)
                }

                val pageAligned = isPageAligned(zip, bestAbi)

                val entries = zip.entries().asSequence()
                    .filter { entry ->
                        entry.name.startsWith("lib/$bestAbi/") &&
                            entry.name.endsWith(".so") &&
                            !entry.isDirectory
                    }
                    .toList()

                var count = 0

                for (entry in entries) {
                    val soName = entry.name.substringAfterLast("/")
                    val destFile = File(libDir, soName)

                    // Skip if already extracted with same size AND correct ELF machine
                    if (destFile.exists() &&
                        destFile.length() == entry.size &&
                        isCompatibleElf(destFile)
                    ) {
                        Timber.tag(TAG).d("Skip existing: $soName")
                        count++
                        continue
                    }

                    try {
                        zip.getInputStream(entry).use { input ->
                            destFile.outputStream().buffered().use { output ->
                                input.copyTo(output)
                            }
                        }
                        destFile.setReadable(true, false)
                        destFile.setExecutable(true, false)
                        count++
                        val mode = if (pageAligned) "page-aligned fallback" else "standard"
                        Timber.tag(TAG).d("Extracted ($mode): $soName (${destFile.length()} bytes)")
                    } catch (e: Exception) {
                        Timber.tag(TAG).e(e, "Failed to extract $soName")
                    }
                }

                SingleApkResult(count, bestAbi, emptySet())
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to process APK $apkPath")
            SingleApkResult(0, "", emptySet())
        }
    }

    private fun collectApkAbis(zip: ZipFile): Set<String> {
        val availableAbis = mutableSetOf<String>()
        zip.entries().asSequence()
            .filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
            .forEach { entry ->
                val parts = entry.name.split("/")
                if (parts.size >= 3) availableAbis.add(parts[1])
            }
        return availableAbis
    }

    /**
     * Check if .so entries are page-aligned (STORED method), indicating
     * extractNativeLibs=false in the manifest.
     */
    private fun isPageAligned(zip: ZipFile, abi: String): Boolean {
        val soEntry = zip.entries().asSequence()
            .firstOrNull { it.name.startsWith("lib/$abi/") && it.name.endsWith(".so") }
            ?: return false

        if (soEntry.method == ZipEntry.STORED) {
            Timber.tag(TAG).d("Detected page-aligned .so (STORED) — APK embedded mode")
            return true
        }
        return false
    }

    /**
     * Build the full library search path string for ClassLoader and linker namespace.
     *
     * Format: "dir1:dir2:apk!/lib/abi:..."
     * - Extracted lib dir first (highest priority)
     * - APK zip paths for direct APK loading (fallback / extractNativeLibs=false)
     */
    private fun buildLibraryPath(
        libDir: File,
        apkPaths: List<String>,
        abi: String
    ): String {
        val paths = mutableListOf<String>()

        paths.add(libDir.absolutePath)

        // Only add zip ABI paths that the device can actually load
        val abiForZip = if (abi in ABI_ORDER) abi else (ABI_ORDER.firstOrNull() ?: abi)
        for (apkPath in apkPaths) {
            if (File(apkPath).exists()) {
                paths.add("$apkPath!/lib/$abiForZip/")
            }
        }

        return paths.joinToString(":")
    }

    /**
     * Re-extract native libs if missing OR if existing .so files are the wrong ELF arch.
     */
    fun reExtractIfMissing(
        apkPath: String,
        splitApkPaths: List<String>,
        instanceId: String,
        dataRoot: File
    ): NativeLibResult? {
        val libDir = File(dataRoot, "$instanceId/lib")
        val soFiles = libDir.listFiles()?.filter { it.name.endsWith(".so") }.orEmpty()
        val hasLibs = soFiles.isNotEmpty()
        val hasWrongAbi = soFiles.any { !isCompatibleElf(it) }

        if (hasLibs && !hasWrongAbi) return null

        val apkFile = File(apkPath)
        if (!apkFile.exists()) return null

        if (hasWrongAbi) {
            Timber.tag(TAG).w(
                "Re-extracting libs for $instanceId — existing .so files are wrong ABI for this device"
            )
        } else {
            Timber.tag(TAG).i("Re-extracting libs for $instanceId — lib dir empty")
        }

        val allPaths = buildList {
            add(apkPath)
            addAll(splitApkPaths.filter { File(it).exists() })
        }

        val result = extractNativeLibs(allPaths, instanceId, dataRoot)
        Timber.tag(TAG).i("Re-extraction complete: ${result.extractedCount} files (abi=${result.selectedAbi})")
        return result
    }

    /**
     * Rebuild library search path for an already-installed app using the current
     * device ABI (fixes stale arm64-v8a paths persisted from a bad install).
     */
    fun rebuildLibrarySearchPath(
        libDir: String,
        apkPath: String,
        splitApkPaths: List<String>,
        preferredAbi: String
    ): String {
        val abi = when {
            preferredAbi in ABI_ORDER -> preferredAbi
            else -> ABI_ORDER.firstOrNull() ?: preferredAbi
        }
        val allPaths = buildList {
            add(apkPath)
            addAll(splitApkPaths.filter { File(it).exists() })
        }
        return buildLibraryPath(File(libDir), allPaths, abi)
    }

    /** Delete extracted .so files whose ELF machine does not match this device. */
    private fun purgeIncompatibleLibs(libDir: File) {
        val files = libDir.listFiles() ?: return
        for (file in files) {
            if (!file.name.endsWith(".so")) continue
            if (!isCompatibleElf(file)) {
                Timber.tag(TAG).w("Removing incompatible native lib: ${file.name}")
                file.delete()
            }
        }
    }

    /**
     * Returns true if [file] is a readable ELF whose e_machine matches a device ABI.
     * Non-ELF / unreadable files are treated as incompatible so they get replaced.
     */
    fun isCompatibleElf(file: File): Boolean {
        if (!file.exists() || file.length() < 20) return false
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                if (magic[0] != 0x7f.toByte() || magic[1] != 'E'.code.toByte() ||
                    magic[2] != 'L'.code.toByte() || magic[3] != 'F'.code.toByte()
                ) {
                    return false
                }
                raf.seek(18) // e_machine is at offset 18 (both 32/64-bit ELF)
                val b0 = raf.readUnsignedByte()
                val b1 = raf.readUnsignedByte()
                val machine = b0 or (b1 shl 8)
                val expected = expectedElfMachines()
                val ok = machine in expected
                if (!ok) {
                    Timber.tag(TAG).d(
                        "${file.name}: ELF machine=$machine not in $expected (device ABIs=$ABI_ORDER)"
                    )
                }
                ok
            }
        } catch (e: Exception) {
            Timber.tag(TAG).d("ELF check failed for ${file.name}: ${e.message}")
            false
        }
    }

    private fun expectedElfMachines(): Set<Int> {
        val machines = mutableSetOf<Int>()
        for (abi in ABI_ORDER) {
            when (abi) {
                "arm64-v8a" -> machines.add(EM_AARCH64)
                "armeabi-v7a", "armeabi" -> machines.add(EM_ARM)
                "x86_64" -> machines.add(EM_X86_64)
                "x86" -> machines.add(EM_386)
            }
        }
        return machines
    }

    data class NativeLibResult(
        val libDir: String,
        val librarySearchPath: String,
        val selectedAbi: String,
        val extractedCount: Int,
        val hasCompatibleAbi: Boolean = true
    )

    private data class SingleApkResult(
        val count: Int,
        val abi: String,
        val incompatibleAbis: Set<String>
    )
}
