/**
 * File: UpdateRepository.kt
 *
 * Description: Implementation of Update Repository functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.repository

import dev.xacnio.kciktv.shared.data.api.RetrofitClient
import dev.xacnio.kciktv.shared.data.model.GithubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.xacnio.kciktv.shared.data.repository.UpdateRepository

class UpdateRepository {
    private val githubService = RetrofitClient.githubService
    private val owner = "Xacnio"
    private val repo = "kcik-tv-app"

    suspend fun getLatestRelease(channel: String): Result<GithubRelease?> = withContext(Dispatchers.IO) {
        try {
            // Fetch list of releases (default page size is 30, sufficient for finding latest)
            val response = githubService.getReleases(owner, repo)
            
            if (response.isSuccessful) {
                var releases = response.body() ?: emptyList()
                
                // Explicitly sort by publishedAt descending to ensure we get the chrono-latest
                releases = releases.sortedByDescending { it.publishedAt }

                if (channel == "stable") {
                    // For stable, find the first one that is NOT a prerelease
                    val stableRelease = releases.firstOrNull { !it.prerelease }
                    Result.success(stableRelease)
                } else {
                    // For beta, just take the absolute newest (including prereleases)
                    val newestRelease = releases.firstOrNull()
                    Result.success(newestRelease)
                }
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getAllReleases(): Result<List<GithubRelease>?> = withContext(Dispatchers.IO) {
        try {
            val response = githubService.getReleases(owner, repo)
            if (response.isSuccessful) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
