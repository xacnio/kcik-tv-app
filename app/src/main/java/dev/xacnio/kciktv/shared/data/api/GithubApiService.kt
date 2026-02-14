/**
 * File: GithubApiService.kt
 *
 * Description: Background service handling Github Api tasks.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.api

import dev.xacnio.kciktv.shared.data.model.GithubRelease
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import dev.xacnio.kciktv.shared.data.api.GithubApiService

interface GithubApiService {
    @GET("repos/{owner}/{repo}/releases")
    suspend fun getReleases(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<List<GithubRelease>>

    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): Response<GithubRelease>
}
