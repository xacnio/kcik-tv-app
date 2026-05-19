package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName

data class ChatSettingsFlag(
    @SerializedName("enabled") val enabled: Boolean = false
)

data class ChatSettingsDuration(
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("duration_seconds") val durationSeconds: Int = 0
)

data class ChatSettingsData(
    @SerializedName("channel_id") val channelId: Long,
    @SerializedName("allow_links") val allowLinks: ChatSettingsFlag?,
    @SerializedName("emotes_only_mode") val emotesOnlyMode: ChatSettingsFlag?,
    @SerializedName("followers_only_mode") val followersOnlyMode: ChatSettingsDuration?,
    @SerializedName("minimum_account_age") val minimumAccountAge: ChatSettingsDuration?,
    @SerializedName("rules") val rules: String?,
    @SerializedName("slow_mode") val slowMode: ChatSettingsDuration?,
    @SerializedName("subscribers_only_mode") val subscribersOnlyMode: ChatSettingsFlag?
)

data class ChatSettingsResponse(
    @SerializedName("data") val data: ChatSettingsData?,
    @SerializedName("message") val message: String?
)
