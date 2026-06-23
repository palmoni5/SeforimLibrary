package io.github.kdroidfilter.seforimlibrary.packaging

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import com.github.luben.zstd.ZstdInputStream
import io.github.kdroidfilter.seforimlibrary.common.OptimizedHttpClient
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val LATEST_API = "https://api.github.com/repos/Otzaria/otzar-HB_catalog/releases/latest"
private const val USER_AGENT = "SeforimLibrary-DownloadOtzarCatalog/1.0"
private const val CATALOG_DB_NAME = "otzar-HB_catalog.db"

/**
 * Download the latest `otzar-HB_catalog.db.zst` from the Otzaria/otzar-HB_catalog GitHub
 * release, zstd-decompress it, and place the resulting `otzar-HB_catalog.db` next to
 * `seforim.db` so it gets bundled alongside the main database.
 *
 * Usage:
 *   ./gradlew :packaging:downloadOtzarCatalog
 *   ./gradlew :packaging:downloadOtzarCatalog -PseforimDb=/path/to/seforim.db
 *
 * Output:
 *   Writes `otzar-HB_catalog.db` next to the DB file (same directory as `seforim.db`).
 */
fun main(args: Array<String>) {
    Logger.setMinSeverity(Severity.Info)
    val logger = Logger.withTag("DownloadOtzarCatalog")

    val dbPath = resolveDbPath(args)
    val outDb = dbPath.resolveSibling(CATALOG_DB_NAME)
    if (Files.exists(outDb) && Files.isRegularFile(outDb) && Files.size(outDb) > 0) {
        logger.i { "Using existing $CATALOG_DB_NAME at ${outDb.toAbsolutePath()}" }
        println(outDb.toAbsolutePath().toString())
        return
    }

    Files.createDirectories(outDb.parent)
    downloadLatestCatalog(outDb, logger)
    println(outDb.toAbsolutePath().toString())
}

private fun resolveDbPath(args: Array<String>): Path {
    val dbPathStr = args.getOrNull(0)
        ?: System.getProperty("seforimDb")
        ?: System.getenv("SEFORIM_DB")
        ?: Paths.get("build", "seforim.db").toString()
    return Paths.get(dbPathStr)
}

private fun downloadLatestCatalog(outDb: Path, logger: Logger) {
    // Fetch release info from GitHub API
    val body = OptimizedHttpClient.fetchJson(LATEST_API, USER_AGENT, logger)

    val regex = Regex(""""browser_download_url"\s*:\s*"([^"]+/otzar-HB_catalog\.db\.zst)"""")
    val zstUrl = regex.findAll(body).map { it.groupValues[1] }.firstOrNull()
        ?: throw IllegalStateException("No otzar-HB_catalog.db.zst asset found in latest otzar-HB_catalog release")

    logger.i { "Downloading $CATALOG_DB_NAME (zst) from $zstUrl" }

    // Download the compressed db to a temporary file next to the destination.
    val tmpZst = outDb.resolveSibling("$CATALOG_DB_NAME.zst.part")
    Files.deleteIfExists(tmpZst)
    OptimizedHttpClient.downloadFile(
        url = zstUrl,
        destination = tmpZst,
        userAgent = USER_AGENT,
        logger = logger,
        progressPrefix = "Downloading $CATALOG_DB_NAME"
    )

    // Decompress .zst -> .db into a temporary file, then atomic-ish move into place.
    val tmpDb = outDb.resolveSibling("$CATALOG_DB_NAME.part")
    Files.deleteIfExists(tmpDb)
    logger.i { "Decompressing $CATALOG_DB_NAME ..." }
    BufferedInputStream(Files.newInputStream(tmpZst)).use { fileStream ->
        ZstdInputStream(fileStream).use { zstd ->
            BufferedOutputStream(Files.newOutputStream(tmpDb)).use { out ->
                zstd.copyTo(out, 1 shl 20) // 1 MiB buffer
            }
        }
    }
    Files.deleteIfExists(tmpZst)

    if (Files.exists(outDb)) {
        val backup = outDb.resolveSibling("$CATALOG_DB_NAME.bak")
        Files.deleteIfExists(backup)
        Files.move(outDb, backup)
        logger.i { "Existing $CATALOG_DB_NAME moved to ${backup.toAbsolutePath()}" }
    }
    Files.move(tmpDb, outDb)
    logger.i { "Wrote $CATALOG_DB_NAME (${"%.2f".format(Files.size(outDb) / 1_048_576.0)} MB) to ${outDb.toAbsolutePath()}" }
}
