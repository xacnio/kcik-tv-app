/**
 * File: ChatRulesResponse.kt
 *
 * Description: Implementation of Chat Rules Response functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.model

import com.google.gson.annotations.SerializedName
import dev.xacnio.kciktv.shared.data.model.ChatRulesResponse

data class ChatRulesResponse(
    @SerializedName("status") val status: Status?,
    @SerializedName("data") val data: ChatRulesData?
) {
    data class Status(
        @SerializedName("code") val code: Int,
        @SerializedName("message") val message: String
    )
}

data class ChatRulesData(
    @SerializedName("rules") val rules: String?
)
