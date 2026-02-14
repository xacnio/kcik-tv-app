/**
 * File: GithubRelease.kt
 *
 * Description: Implementation of Github Release functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.GithubRelease

data class GithubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("name") val name: String,
    @SerializedName("prerelease") val prerelease: Boolean,
    @SerializedName("published_at") val publishedAt: String,
    @SerializedName("assets") val assets: List<GithubAsset>,
    @SerializedName("body") val body: String
)

data class GithubAsset(
    @SerializedName("name") val name: String,
    @SerializedName("browser_download_url") val downloadUrl: String,
    @SerializedName("size") val size: Long
)
