package com.nothing.mobilecore.shared.infrastructure

import com.nothing.mobilecore.shared.domain.AppRelease
import com.nothing.mobilecore.shared.domain.AppUpdatePolicy
import com.nothing.mobilecore.shared.domain.SemanticVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Разбор ответа `GET /repos/.../releases/latest`. Сеть сюда не входит. */
object GitHubReleaseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): AppRelease? {
        val dto = json.decodeFromString(ReleaseDto.serializer(), body)
        val tag = dto.tagName?.trim().orEmpty()
        if (!SemanticVersion.canParse(tag)) return null
        val asset = dto.assets.firstOrNull { asset ->
            asset.name?.trim().equals(AppUpdatePolicy.ASSET_NAME, ignoreCase = true) &&
                !asset.browserDownloadUrl.isNullOrBlank()
        } ?: return null
        val normalized = SemanticVersion.normalize(tag)
        if (normalized.isEmpty()) return null
        return AppRelease(
            tag = tag,
            semver = normalized,
            notes = dto.body?.trim()?.take(MAX_NOTES).orEmpty(),
            downloadUrl = asset.browserDownloadUrl!!.trim(),
        )
    }

    private const val MAX_NOTES = 2_000

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tagName: String? = null,
        val body: String? = null,
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(
        val name: String? = null,
        @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
    )
}
