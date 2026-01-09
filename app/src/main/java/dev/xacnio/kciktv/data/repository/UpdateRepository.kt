package dev.xacnio.kciktv.data.repository

import dev.xacnio.kciktv.data.api.RetrofitClient
import dev.xacnio.kciktv.data.model.GithubRelease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UpdateRepository {
    private val githubService = RetrofitClient.githubService
    private val owner = "Xacnio"
    private val repo = "kcik-tv-app"

    suspend fun getLatestRelease(channel: String): Result<GithubRelease?> = withContext(Dispatchers.IO) {
        try {
            val response = if (channel == "stable") {
                githubService.getLatestRelease(owner, repo)
            } else {
                // For beta, we take the absolute latest release (whether it is stable or beta)
                val listResponse = githubService.getReleases(owner, repo)
                if (listResponse.isSuccessful) {
                    val releases = listResponse.body() ?: emptyList()
                    val newestRelease = releases.firstOrNull()
                    return@withContext Result.success(newestRelease)
                } else {
                    return@withContext Result.failure(Exception("API error: ${listResponse.code()}"))
                }
            }

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
