/**
 * File: PollModels.kt
 *
 * Description: Implementation of Poll Models functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

data class PollUpdateEventData(
    @SerializedName("poll") val poll: PollData?
)

data class PollData(
    @SerializedName("title") val title: String?,
    @SerializedName("options") val options: List<PollOption>?,
    @SerializedName("duration") val duration: Int?,
    @SerializedName("remaining") val remaining: Int?,
    @SerializedName("result_display_duration") val resultDisplayDuration: Int?,
    @SerializedName("has_voted") val hasVoted: Boolean? = false,
    @SerializedName("voted_option_id") val votedOptionId: Int? = null,
    @SerializedName("is_active") val isActive: Boolean? = true
)

data class PollOption(
    @SerializedName("id") val id: Int,
    @SerializedName("label") val label: String?,
    @SerializedName("votes") val votes: Int?
)

data class CreatePollRequest(
    @SerializedName("title") val title: String,
    @SerializedName("options") val options: List<String>,
    @SerializedName("duration") val duration: Int = 30,
    @SerializedName("result_display_duration") val resultDisplayDuration: Int = 15
)
