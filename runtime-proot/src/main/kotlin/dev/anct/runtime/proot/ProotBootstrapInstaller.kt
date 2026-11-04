package dev.anct.runtime.proot

import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.zip.ZipFile

class ProotBootstrapInstaller(
    private val context: Context,
) {
    val prefix: File = File(context.filesDir, "usr")
    val distroRuntime: File = File(context.filesDir, "proot-distro")
    private val staging = File(context.filesDir, "usr.staging")
    private val versionFile = File(context.filesDir, ".proot-bootstrap-version")

    fun isInstalled(): Boolean =
        File(prefix, "bin/python3").isFile &&
            File(prefix, "bin/proot").isFile &&
            File(distroRuntime, "proot_distro/cli.py").isFile

    fun install(onProgress: (String) -> Unit = {}) {
        require("arm64-v8a" in Build.SUPPORTED_ABIS) {
            "AnCT currently supports arm64 devices only"
        }
        if (isInstalled() && versionFile.readTextOrNull() == BOOTSTRAP_VERSION) return

        onProgress("Preparing bundled PRoot runtime")
        val archive = File(context.cacheDir, "proot-bootstrap.zip")
        context.assets.open(ASSET_PATH).use { input ->
            FileOutputStream(archive).use(input::copyTo)
        }

        expectedSha256()?.let { expected ->
            val actual = sha256(archive)
            check(actual.equals(expected.trim(), ignoreCase = true)) {
                "PRoot bootstrap checksum mismatch"
            }
        }

        staging.deleteRecursively()
        check(staging.mkdirs()) { "Could not create PRoot staging directory" }

        extractBootstrap(archive, staging)

        prefix.deleteRecursively()
        check(staging.renameTo(prefix)) { "Could not commit PRoot runtime" }

        val distroArchive = File(context.cacheDir, "proot-distro.zip")
        context.assets.open(DISTRO_ASSET_PATH).use { input ->
            FileOutputStream(distroArchive).use(input::copyTo)
        }
        runCatching {
            context.assets.open(DISTRO_SHA_ASSET_PATH).bufferedReader().readText()
        }.getOrNull()?.let { expected ->
            check(sha256(distroArchive).equals(expected.trim(), ignoreCase = true)) {
                "PRoot-Distro package checksum mismatch"
            }
        }
        distroRuntime.deleteRecursively()
        check(distroRuntime.mkdirs()) { "Could not create PRoot-Distro runtime directory" }
        extractSimpleZip(distroArchive, distroRuntime.toPath())
        distroArchive.delete()

        versionFile.writeText(BOOTSTRAP_VERSION)
        archive.delete()
        onProgress("PRoot runtime installed")
    }

    private fun extractBootstrap(archive: File, staging: File) {
        val symlinks = mutableListOf<Pair<String, String>>()
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name
                if (name == "SYMLINKS.txt") {
                    zip.getInputStream(entry).bufferedReader().forEachLine { line ->
                        val parts = line.split('\u2190')
                        if (parts.size == 2) symlinks += parts[0] to parts[1]
                    }
                    continue
                }
                val target = safeChild(staging.toPath(), name)
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                    continue
                }
                Files.createDirectories(target.parent)
                zip.getInputStream(entry).use { input ->
                    Files.newOutputStream(target).use { output -> input.copyTo(output) }
                }
                target.toFile().setExecutable(
                    name.startsWith("bin/") ||
                        name.startsWith("libexec/") ||
                        name.startsWith("lib/apt/apt-helper") ||
                        name.startsWith("lib/apt/methods"),
                    false,
                )
            }
        }

        symlinks.forEach { (targetText, linkText) ->
            val link = safeChild(staging.toPath(), linkText)
            Files.createDirectories(link.parent)
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, Paths.get(targetText))
        }
    }

    private fun extractSimpleZip(archive: File, root: Path) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val target = safeChild(root, entry.name)
                if (entry.isDirectory) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    zip.getInputStream(entry).use { input ->
                        Files.newOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    private fun expectedSha256(): String? = runCatching {
        context.assets.open(SHA_ASSET_PATH).bufferedReader().readText()
    }.getOrNull()

    private fun safeChild(root: Path, entryName: String): Path {
        require(entryName.isNotBlank()) { "Empty archive entry" }
        require(!entryName.startsWith('/')) { "Absolute archive entry: $entryName" }
        val resolved = root.resolve(entryName).normalize()
        require(resolved.startsWith(root)) { "Archive entry escapes root: $entryName" }
        var cursor = root
        for (component in root.relativize(resolved)) {
            cursor = cursor.resolve(component)
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(cursor)) { "Archive writes through symlink: $entryName" }
            }
        }
        return resolved
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        private const val ASSET_PATH = "proot/bootstrap-aarch64.zip"
        private const val SHA_ASSET_PATH = "proot/bootstrap-aarch64.sha256"
        private const val DISTRO_ASSET_PATH = "proot/proot-distro.zip"
        private const val DISTRO_SHA_ASSET_PATH = "proot/proot-distro.sha256"
        const val BOOTSTRAP_VERSION = "pr-distro-e1f5351-cpython-3.14.8-v1"
    }
}

