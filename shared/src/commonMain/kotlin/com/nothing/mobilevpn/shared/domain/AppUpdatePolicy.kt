package com.nothing.mobilecore.shared.domain

import kotlinx.serialization.Serializable

/**
 * Решение «есть ли более новый релиз и можно ли качать его файл».
 * Сеть и установка — снаружи. Сравнение версий совпадает с десктопным клиентом:
 * `v1.2` и `1.2.0` — одна версия, берётся только тройка чисел.
 */
object AppUpdatePolicy {
    const val OWNER = "EXTRBXY"
    const val REPOSITORY = "smartVPN"
    const val ASSET_NAME = "NothingMobileVpn.apk"
    const val CHECK_INTERVAL_MS = 84_600_000L
    const val POLL_INTERVAL_MS = 1_800_000L

    private val allowedHosts = setOf(
        "github.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
    )

    fun latestReleaseUrl(): String =
        "https://api.github.com/repos/$OWNER/$REPOSITORY/releases/latest"

    fun isCheckDue(lastCheckEpochMs: Long, nowEpochMs: Long): Boolean =
        lastCheckEpochMs <= 0L || nowEpochMs - lastCheckEpochMs >= CHECK_INTERVAL_MS

    fun isNewer(latest: String, current: String): Boolean {
        if (!SemanticVersion.canParse(latest) || !SemanticVersion.canParse(current)) return false
        return SemanticVersion.compare(latest, current) > 0
    }

    fun availableRelease(release: AppRelease?, currentVersion: String): AppRelease? =
        release?.takeIf {
            it.tag.isNotBlank() && isNewer(it.semver, currentVersion) &&
                downloadUrlProblem(it.downloadUrl, requireFileName = true) == null
        }

    fun shouldNotify(dismissedTag: String, tag: String): Boolean =
        tag.isNotBlank() && !tag.equals(dismissedTag, ignoreCase = true)

    /** null — URL годится. [requireFileName] снимается после редиректа: у GitHub финальный адрес без имени файла. */
    fun downloadUrlProblem(raw: String, requireFileName: Boolean): DownloadUrlProblem? {
        val url = raw.trim()
        if (url.isEmpty()) return DownloadUrlProblem.EMPTY
        val match = HTTPS.matchEntire(url.substringBefore('?').substringBefore('#'))
            ?: return DownloadUrlProblem.NOT_ABSOLUTE
        if (!match.groupValues[1].equals("https", ignoreCase = true)) return DownloadUrlProblem.NOT_HTTPS
        val host = match.groupValues[2]
        if (host.isEmpty() || host.any { it == '@' || it == ':' || it == '\\' || it.isWhitespace() }) {
            return DownloadUrlProblem.HOST
        }
        if (allowedHosts.none { it.equals(host, ignoreCase = true) }) return DownloadUrlProblem.HOST
        if (!requireFileName) return null
        val name = match.groupValues[3].substringAfterLast('/')
        return if (name.equals(ASSET_NAME, ignoreCase = true)) null else DownloadUrlProblem.FILE_NAME
    }

    private val HTTPS = Regex("""^(https?)://([^/?#]+)([^?#]*)""", RegexOption.IGNORE_CASE)
}

enum class DownloadUrlProblem {
    EMPTY,
    NOT_ABSOLUTE,
    NOT_HTTPS,
    HOST,
    FILE_NAME,
}

object SemanticVersion {
    private val triple = Regex("""^(?:v|V)?(\d+)\.(\d+)(?:\.(\d+))?""")

    fun canParse(input: String?): Boolean = parse(input) != null

    fun normalize(input: String?): String {
        val parts = parse(input) ?: return ""
        return "${parts.major}.${parts.minor}.${parts.patch}"
    }

    fun compare(left: String?, right: String?): Int {
        val a = parse(left)
        val b = parse(right)
        if (a == null && b == null) return 0
        if (a == null) return -1
        if (b == null) return 1
        return compareValuesBy(a, b, { it.major }, { it.minor }, { it.patch })
    }

    private fun parse(input: String?): Parts? {
        if (input.isNullOrBlank()) return null
        val match = triple.find(input.trim()) ?: return null
        if (match.range.first != 0) return null
        return Parts(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toIntOrNull() ?: 0,
        )
    }

    private data class Parts(val major: Int, val minor: Int, val patch: Int)
}

@Serializable
data class AppRelease(
    val tag: String,
    val semver: String,
    val notes: String,
    val downloadUrl: String,
)
