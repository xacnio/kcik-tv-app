/**
 * File: PredictionModels.kt
 *
 * Description: Implementation of Prediction Models functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

data class PredictionEventData(
    @SerializedName("prediction") val prediction: PredictionData?,
    @SerializedName("user_vote") val userVote: PredictionUserVote?
)

data class PredictionData(
    @SerializedName("id") val id: String?,
    @SerializedName("channel_id") val channelId: Long?,
    @SerializedName("title") val title: String?,
    @SerializedName("state") val state: String?, // ACTIVE, LOCKED, RESOLVED, CANCELLED
    @SerializedName("outcomes") val outcomes: List<PredictionOutcome>?,
    @SerializedName("duration") val duration: Int?,
    @SerializedName("remaining") val remaining: Int?, // We'll compute this or get from API if available
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("updated_at") val updated_at: String?,
    @SerializedName("winning_outcome_id") val winningOutcomeId: String? = null,
    @SerializedName("user_vote") val userVote: PredictionUserVote? = null
)

data class PredictionOutcome(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String?,
    @SerializedName("total_vote_amount") val totalVoteAmount: Long?,
    @SerializedName("vote_count") val voteCount: Int?,
    @SerializedName("return_rate") val returnRate: Double?,
    @SerializedName("top_users") val topUsers: List<PredictionTopUser>? = null
)

data class PredictionTopUser(
    @SerializedName("user_id") val userId: Long?,
    @SerializedName("username") val username: String?,
    @SerializedName("amount") val amount: Long?
)

data class PredictionUserVote(
    @SerializedName("outcome_id") val outcomeId: String?,
    @SerializedName("total_vote_amount") val totalVoteAmount: Long?
)

data class PredictionVoteRequest(
    @SerializedName("amount") val amount: Int,
    @SerializedName("outcome_id") val outcomeId: String
)

data class PredictionVoteResponse(
    @SerializedName("data") val data: PredictionVoteData?,
    @SerializedName("message") val message: String?
)

data class PredictionVoteData(
    @SerializedName("prediction") val prediction: PredictionData?,
    @SerializedName("user_vote") val userVote: PredictionUserVote?
)

data class LatestPredictionResponse(
    @SerializedName("data") val data: PredictionEventData?,
    @SerializedName("message") val message: String?
)

data class CreatePredictionRequest(
    @SerializedName("title") val title: String,
    @SerializedName("outcomes") val outcomes: List<String>,
    @SerializedName("duration") val duration: Int
)

data class UpdatePredictionRequest(
    @SerializedName("state") val state: String,
    @SerializedName("winning_outcome_id") val winningOutcomeId: String? = null
)


data class RecentPredictionsResponse(
    @SerializedName("data") val data: RecentPredictionsData?,
    @SerializedName("message") val message: String?
)

data class RecentPredictionsData(
    @SerializedName("predictions") val predictions: List<PredictionData>?
)
