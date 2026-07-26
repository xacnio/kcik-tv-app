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
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.api.BlerpApiService

/**
 * Blerp GraphQL API Service
 */
interface BlerpApiService {

    /** Channel's Blerp page. Needs the viewer's session for channelViewerEdge to be populated. */
    @Headers(
        "Content-Type: application/json",
        "Accept: application/json"
    )
    @POST("graphql")
    suspend fun getBlerpUsername(
        @Header("Authorization") authorization: String?,
        @Header("Cookie") cookie: String?,
        @Body body: BlerpGraphQLRequest
    ): Response<BlerpGraphQLResponse>

    /** Channel points ("snoots") earn tick, fired once per standardMS of watch time. */
    @Headers(
        "Content-Type: application/json",
        "Accept: application/json"
    )
    @POST("graphql")
    suspend fun earnSnoots(
        @Header("Authorization") authorization: String?,
        @Header("Cookie") cookie: String?,
        @Body body: BlerpEarnRequest
    ): Response<BlerpEarnResponse>
}

data class BlerpGraphQLRequest(
    val operationName: String = "viewerBrowserExtension",
    val variables: BlerpVariables,
    val query: String = """query viewerBrowserExtension(${'$'}kickUsername: String) {
  soundEmotes {
    currentStreamerPage(kickUsername: ${'$'}kickUsername) {
      streamerBlerpUser {
        _id
        soundEmotesObject {
          urlKey
          channelPointsDisabled
        }
      }
      channelViewerEdge {
        points
        showManualButton
        standardMS
        manualMS
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
    val streamerBlerpUser: BlerpUser?,
    val channelViewerEdge: BlerpChannelViewerEdge?
)

data class BlerpUser(
    @SerializedName("_id") val id: String?,
    val soundEmotesObject: BlerpSoundEmotesObject?
)

data class BlerpSoundEmotesObject(
    val urlKey: String?,
    val channelPointsDisabled: Boolean?
)

/** What the app needs from a channel's Blerp page. [channelOwnerId] is the id point ticks use. */
data class BlerpChannelInfo(
    val url: String,
    val channelOwnerId: String?,
    val channelPointsDisabled: Boolean,
    val points: Int?,
    val showManualButton: Boolean,
    val standardMs: Long?
)

data class BlerpEarnRequest(
    val operationName: String = "earnSomeSnoots",
    val variables: BlerpEarnVariables,
    val query: String = """mutation earnSomeSnoots(${'$'}channelOwnerId: MongoID!, ${'$'}manualEarn: Boolean) {
  soundEmotes {
    earningSnoots(channelOwnerId: ${'$'}channelOwnerId, manualEarn: ${'$'}manualEarn) {
      pointsIncremented
      channelViewerEdge {
        _id
        points
        lastIncrementedAt
        showManualButton
        standardMS
        manualMS
      }
    }
  }
}"""
)

data class BlerpEarnVariables(
    val channelOwnerId: String,
    val manualEarn: Boolean = false
)

data class BlerpEarnResponse(
    val data: BlerpEarnData?,
    val errors: List<BlerpGraphQLError>?
)

data class BlerpGraphQLError(
    val message: String?
)

data class BlerpEarnData(
    val soundEmotes: BlerpEarnSoundEmotes?
)

data class BlerpEarnSoundEmotes(
    val earningSnoots: BlerpEarningSnoots?
)

data class BlerpEarningSnoots(
    val pointsIncremented: Int?,
    val channelViewerEdge: BlerpChannelViewerEdge?
)

data class BlerpChannelViewerEdge(
    @SerializedName("_id") val id: String?,
    val points: Int?,
    val lastIncrementedAt: String?,
    // A manual (tap to claim) reward is waiting
    val showManualButton: Boolean?,
    // Automatic reward cadence, ~600000ms
    val standardMS: Long?,
    // Manual reward cadence, ~1500000ms
    val manualMS: Long?
)

/** Outcome of one point tick. Blerp reports the wait left when a tick beats the cooldown. */
sealed interface BlerpEarnResult {
    data class Earned(
        val pointsIncremented: Int?,
        val totalPoints: Int?,
        val intervalMs: Long?,
        val showManualButton: Boolean
    ) : BlerpEarnResult
    data class TooEarly(val retryAfterMs: Long) : BlerpEarnResult
    data object Failed : BlerpEarnResult
}
