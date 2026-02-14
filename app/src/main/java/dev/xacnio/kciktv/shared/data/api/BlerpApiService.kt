/**
 * File: BlerpApiService.kt
 *
 * Description: Background service handling Blerp Api tasks.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import dev.xacnio.kciktv.shared.data.api.BlerpApiService

/**
 * Blerp GraphQL API Service
 */
interface BlerpApiService {
    
    @Headers(
        "Content-Type: application/json",
        "Accept: application/json"
    )
    @POST("graphql")
    suspend fun getBlerpUsername(
        @Body body: BlerpGraphQLRequest
    ): Response<BlerpGraphQLResponse>
}

data class BlerpGraphQLRequest(
    val operationName: String = "viewerBrowserExtension",
    val variables: BlerpVariables,
    val query: String = """query viewerBrowserExtension(${'$'}kickUsername: String) {
  soundEmotes {
    currentStreamerPage(kickUsername: ${'$'}kickUsername) {
      streamerBlerpUser {
        username
      }
    }
  }
}"""
)

data class BlerpVariables(
    val kickUsername: String
)

data class BlerpGraphQLResponse(
    val data: BlerpData?
)

data class BlerpData(
    val soundEmotes: BlerpSoundEmotes?
)

data class BlerpSoundEmotes(
    val currentStreamerPage: BlerpStreamerPage?
)

data class BlerpStreamerPage(
    val streamerBlerpUser: BlerpUser?
)

data class BlerpUser(
    val username: String?
)
