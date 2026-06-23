package io.github.kdroidfilter.seforimlibrary.packaging

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.github.luben.zstd.ZstdInputStream
import io.github.kdroidfilter.seforimlibrary.common.OptimizedHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val LATEST_API = "https://api.github.com/repos/Otzaria/otzaria-library/releases/latest"
private const val USER_AGENT = "SeforimLibrary-DownloadTalmudBavli/1.0"

/** Top-level directory the archive carries (and the layout the app expects next to the DB). */
const val TALMUD_BAVLI_DIR_NAME = "תלמוד בבלי"

/**
 * Download the latest `talmud_bavli_latest.tar.zst` from the Otzaria/otzaria-library GitHub
 * release and extract it next to `seforim.db`. The archive contains a single top-level
 * `תלמוד בבלי/` directory holding the masechet PDFs (flattened, one PDF per file), so after
 * extraction the PDFs live under the `תלמוד בבלי` folder next to the DB.
 *
 * Usage:
 *   ./gradlew :packaging:downloadTalmudBavli
 *   ./gradlew :packaging:downloadTalmudBavli -PseforimDb=/path/to/seforim.db
 *
 * Output:
 *   Writes the `תלמוד בבלי/` folder of PDFs next to the DB file (same directory as `seforim.db`).
 */
fun main(args: Array<String>) {
    Logger.setMinSeverity(Severity.Info)
    val logger = Logger.withTag("DownloadTalmudBavli")

    val dbPath = resolveDbPath(args)
    val destDir = dbPath.parent ?: Paths.get(".")
    val talmudDir = destDir.resolve(TALMUD_BAVLI_DIR_NAME)

    if (Files.isDirectory(talmudDir) && hasPdf(talmudDir)) {
        logger.i { "Using existing $TALMUD_BAVLI_DIR_NAME at ${talmudDir.toAbsolutePath()}" }
        println(talmudDir.toAbsolutePath().toString())
        return
    }

    Files.createDirectories(destDir)
    val archive = destDir.resolve("talmud_bavli_latest.tar.zst.part")
    Files.deleteIfExists(archive)
    downloadLatestArchive(archive, logger)
    extractTarZst(archive, destDir, logger)
    Files.deleteIfExists(archive)

    val pdfCount = countPdfs(talmudDir)
    logger.i { "Extracted $pdfCount PDF(s) into ${talmudDir.toAbsolutePath()}" }
    println(talmudDir.toAbsolutePath().toString())
}

private fun resolveDbPath(args: Array<String>): Path {
    val dbPathStr = args.getOrNull(0)
        ?: System.getProperty("seforimDb")
        ?: System.getenv("SEFORIM_DB")
        ?: Paths.get("build", "seforim.db").toString()
    return Paths.get(dbPathStr)
}

private fun downloadLatestArchive(out: Path, logger: Logger) {
    // Fetch release info from GitHub API
    val body = OptimizedHttpClient.fetchJson(LATEST_API, USER_AGENT, logger)

    val regex = Regex(""""browser_download_url"\s*:\s*"([^"]+/talmud_bavli_latest\.tar\.zst)"""")
    val archiveUrl = regex.findAll(body).map { it.groupValues[1] }.firstOrNull()
        ?: throw IllegalStateException("No talmud_bavli_latest.tar.zst asset found in latest otzaria-library release")
    logger.i { "Downloading Talmud Bavli PDFs from $archiveUrl" }

    OptimizedHttpClient.downloadFile(
        url = archiveUrl,
        destination = out,
        userAgent = USER_AGENT,
        logger = logger,
        progressPrefix = "Downloading Talmud Bavli"
    )
}

private fun extractTarZst(archive: Path, destinationDir: Path, logger: Logger) {
    logger.i { "Extracting ${TALMUD_BAVLI_DIR_NAME} to ${destinationDir.toAbsolutePath()}" }
    Files.createDirectories(destinationDir)
    BufferedInputStream(Files.newInputStream(archive)).use { fileStream ->
        ZstdInputStream(fileStream).use { zstd ->
            TarArchiveInputStream(zstd).use { tar ->
                var entry = tar.nextTarEntry
                val buffer = ByteArray(1 shl 20) // 1 MiB
                while (entry != null) {
                    val newPath = destinationDir.resolve(entry.name).normalize()
                    if (!newPath.startsWith(destinationDir)) {
                        throw IllegalStateException("Blocked suspicious path while extracting: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        Files.createDirectories(newPath)
                    } else {
                        Files.createDirectories(newPath.parent)
                        Files.newOutputStream(newPath).use { out ->
                            var n = tar.read(buffer)
                            while (n > 0) {
                                out.write(buffer, 0, n)
                                n = tar.read(buffer)
                            }
                        }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }
    logger.i { "Extraction complete." }
}

private fun hasPdf(dir: Path): Boolean =
    Files.walk(dir).use { stream ->
        stream.anyMatch { Files.isRegularFile(it) && it.fileName.toString().endsWith(".pdf", ignoreCase = true) }
    }

private fun countPdfs(dir: Path): Long {
    if (!Files.isDirectory(dir)) return 0
    return Files.walk(dir).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".pdf", ignoreCase = true) }.count()
    }
}
