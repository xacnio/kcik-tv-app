/**
 * File: ChannelRepository.kt
 *
 * Description: Implementation of Channel Repository functionality.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.data.repository

import android.util.Log
import dev.xacnio.kciktv.shared.data.api.RetrofitClient
import dev.xacnio.kciktv.shared.data.model.*
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse
import dev.xacnio.kciktv.shared.data.model.ChannelItem
import dev.xacnio.kciktv.shared.data.model.ChannelLink
import dev.xacnio.kciktv.shared.data.model.ChannelUserMeResponse
import dev.xacnio.kciktv.shared.data.model.ChannelUserResponse
import dev.xacnio.kciktv.shared.data.model.ChannelVideo
import dev.xacnio.kciktv.shared.data.model.ChatIdentityResponse
import dev.xacnio.kciktv.shared.data.model.ChatMessage
import dev.xacnio.kciktv.shared.data.model.ClipPlayResponse
import dev.xacnio.kciktv.shared.data.model.ClipResponse
import dev.xacnio.kciktv.shared.data.model.ListChannelVideo
import dev.xacnio.kciktv.shared.data.model.SubcategoriesResponse
import dev.xacnio.kciktv.shared.data.model.TopCategory
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.shared.util.DateParseUtils

data class ChannelListData(
    val channels: List<ChannelItem>,
    val nextCursor: String?
)

/**
 * Repository that manages channel data
 */
class ChannelRepository {
    
    private val liveStreamsService = RetrofitClient.liveStreamsService
    private val channelService = RetrofitClient.channelService
    private val searchService = RetrofitClient.searchService
    
    companion object {
        private const val TAG = "ChannelRepository"
    }
    
    /**
     * Fetches live streams with language filter
     */
    suspend fun getFilteredLiveStreams(languages: List<String>? = null, after: String? = null, sort: String = "viewer_count_desc", categoryId: Long? = null): Result<ChannelListData> = withContext(Dispatchers.IO) {
        try {
            val response = liveStreamsService.getLiveStreams(languages = languages, sort = sort, limit = 100, after = after, categoryId = categoryId)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val streams = body.data?.livestreams ?: emptyList()
                val channels = streams.map { ChannelItem.fromLiveStreamItem(it) }
                val cursor = body.data?.pagination?.nextCursor ?: body.pagination?.nextCursor
                Result.success(ChannelListData(channels, cursor))
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getChannelVideos(slug: String): Result<List<dev.xacnio.kciktv.shared.data.model.ListChannelVideo>> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getChannelVideos(slug)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getVideo(id: String): Result<dev.xacnio.kciktv.shared.data.model.ChannelVideo> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getVideo(id)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Fetches stream URL for a specific channel
     */
    suspend fun getStreamUrl(slug: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getChannel(slug)
            if (response.isSuccessful && response.body() != null) {
                val playbackUrl = response.body()!!.playbackUrl
                if (playbackUrl != null) {
                    Result.success(playbackUrl)
                } else {
                    Result.failure(Exception("Channel is offline or no playback URL found"))
                }
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getStreamUrl Exception: ${e.message}")
            Result.failure(e)
        }
    }
    
    /**
     * Fetches live stream details for a specific channel
     */
    suspend fun getLiveStreamDetails(slug: String): Result<LivestreamResponse> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getChannelLivestream(slug)
            if (response.isSuccessful && response.body() != null) {
                val data = response.body()!!.data
                if (data != null) {
                    Result.success(data)
                } else {
                    Result.failure(Exception("Live stream data is null"))
                }
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches details for a specific subcategory (viewers, followers, tags)
     */
    suspend fun getSubcategoryDetails(slug: String): Result<dev.xacnio.kciktv.shared.data.model.TopCategory> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getSubcategoryDetails(slug)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches all details for a specific channel (v2/channels/{slug})
     */
    suspend fun getChannelDetails(slug: String): Result<dev.xacnio.kciktv.shared.data.model.ChannelDetailResponse> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getChannel(slug)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    data class ChatInfo(
        val channelId: Long,
        val chatroomId: Long,
        val subscriberBadges: Map<Int, String>, // months -> badge URL
        val verified: Boolean,
        val followersCount: Int,
        val isFollowing: Boolean,
        val chatroomInfo: dev.xacnio.kciktv.shared.data.model.ChatroomInfo? = null,
        val startTimeMillis: Long? = null
    )

    data class ChatHistoryResult(
        val messages: List<dev.xacnio.kciktv.shared.data.model.ChatMessage>,
        val pinnedMessage: dev.xacnio.kciktv.shared.data.model.ChatMessage?,
        val cursor: String? = null
    )
    /**
     * Fetches chat information for a specific channel
     */
    suspend fun getChatInfo(slug: String, token: String? = null): Result<ChatInfo> = withContext(Dispatchers.IO) {
        try {
            val authHeader = if (token != null) "Bearer $token" else null
            val response = channelService.getChannel(slug, authHeader)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val channelId = body.id
                val chatroomId = body.chatroom?.id
                val verified = body.verified ?: false
                val followersCount = body.followersCount ?: 0
                val isFollowing = body.following ?: false
                
                // Convert subscriber badges to month -> URL map
                val badgesMap = body.subscriberBadges?.mapNotNull { badge ->
                    val months = badge.months
                    val url = badge.badgeImage?.src
                    if (months != null && url != null) {
                        months to url
                    } else null
                }?.toMap() ?: emptyMap()
                
                val startTime = body.livestream?.createdAt?.let { created ->
                    try {
                        val cleaned = if (created.contains(".")) created.substringBefore(".") + "Z" else created
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        sdf.parse(cleaned)?.time
                    } catch (e: Exception) {
                        try {
                            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                            sdf.parse(created)?.time
                        } catch (e2: Exception) { null }
                    }
                }
                
                if (channelId != null && chatroomId != null) {
                    Log.d(TAG, "Got channelId: $channelId, chatroomId: $chatroomId, badges: ${badgesMap.size}, startTime: $startTime for slug: $slug")
                    Result.success(ChatInfo(channelId, chatroomId, badgesMap, verified, followersCount, isFollowing, body.chatroom, startTime))
                } else {
                    Result.failure(Exception("Channel ID or Chatroom ID not found"))
                }
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getChatInfo Exception: ${e.message}")
            Result.failure(e)
        }
    }
    
    suspend fun getChannelUserInfo(channelSlug: String, userSlug: String, token: String?): Result<dev.xacnio.kciktv.shared.data.model.ChannelUserResponse> = withContext(Dispatchers.IO) {
        try {
            val authHeader = if (token != null) "Bearer $token" else null
            val cSlug = channelSlug.lowercase()
            val uSlug = userSlug.lowercase()
            val response = channelService.getChannelUserInfo(cSlug, uSlug, authHeader)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getChannelUserMe(channelSlug: String, token: String): Result<dev.xacnio.kciktv.shared.data.model.ChannelUserMeResponse> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getChannelUserMe(channelSlug.lowercase(), "Bearer $token")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun performCelebrationAction(chatroomId: Long, celebrationId: String, action: String, message: String? = null, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = org.json.JSONObject().apply {
                put("action", action)
                if (message != null) put("message", message)
            }.toString()
            
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
            val response = channelService.postChannelCelebrationAction(chatroomId, celebrationId, "Bearer $token", requestBody)
            
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun followChannel(slug: String, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.followChannel(slug.lowercase(), "Bearer $token")
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun unfollowChannel(slug: String, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.unfollowChannel(slug.lowercase(), "Bearer $token")
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches chat identity for a specific user in a specific channel
     */
    suspend fun getChatIdentity(channelId: Long, userId: Long, token: String?): Result<dev.xacnio.kciktv.shared.data.model.ChatIdentityResponse> = withContext(Dispatchers.IO) {
        try {
            val authHeader = if (token != null) "Bearer $token" else null
            val response = channelService.getChatIdentity(channelId, userId, authHeader)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("API error identity: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Updates chat identity for a specific user in a specific channel
     */
    suspend fun updateChatIdentity(channelId: Long, userId: Long, token: String, badges: List<String>, color: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = org.json.JSONObject().apply {
                put("badges", org.json.JSONArray(badges))
                put("color", color)
            }.toString()
            
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())
            val response = channelService.updateChatIdentity(channelId, userId, "Bearer $token", requestBody)
            
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("API error update identity: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Fetches chat history for a specific chatroom with pagination
     * Loads messages until targetMessageCount is reached or no more pages exist
     */
    /**
     * Fetches chat history for a specific chatroom (single page)
     */
    suspend fun getChatHistory(chatroomId: Long, cursor: String? = null): Result<ChatHistoryResult> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "getChatHistory using liveStreamsService. Cursor: $cursor")
            
            // Convert cursor (unix timestamp string) to ISO-8601 for start_time
            val startTime = cursor?.toLongOrNull()?.let { nanos ->
                // Kick API returns duplicates if we use exact time. Subtract 1ms to fetch previous window? 
                // Or maybe start_time fetches forward?
                // The cursor is likely the "oldest" message timestamp.
                // If we want OLDER messages, and start_time fetches forward, we are stuck.
                // Assuming start_time fetches "around" or "starting window ending at timestamp", 
                // Let's try to feed the exact timestamp.
                
                val millis = nanos / 1000  // Cursor is microseconds in Kick
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                sdf.format(java.util.Date(millis)) 
            }

            // Using pure start_time per user request
            val response = liveStreamsService.getChatHistory(chatroomId, startTime)
            
            if (!response.isSuccessful || response.body() == null) {
                return@withContext Result.failure(Exception("API error: ${response.code()}"))
            }
            
            val body = response.body()!!
            val historyData = body.data
            val messageList = historyData?.messages ?: body.messages
            
            val messages = messageList?.mapNotNull { parseMessage(it) } ?: emptyList()
            
            val pinnedMsgRaw = historyData?.pinnedMessageWrapper?.message ?: body.pinnedMessageWrapper?.message
            val pinnedMessage = pinnedMsgRaw?.let { parseMessage(it) }
            
            val nextCursor = historyData?.cursor ?: body.cursor
            
            // Sort by createdAt to ensure chronological order for this page
            val sortedMessages = messages.sortedBy { it.createdAt }
            
            Result.success(ChatHistoryResult(sortedMessages, pinnedMessage, nextCursor))
        } catch (e: Exception) {
            Log.e(TAG, "getChatHistory Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun parseIsoDate(isoString: String?): Long {
        return dev.xacnio.kciktv.shared.util.DateParseUtils.parseIsoDate(isoString)
    }

    private fun parseMessage(historyMsg: ChatHistoryMessage): dev.xacnio.kciktv.shared.data.model.ChatMessage? {
        if (historyMsg.sender == null || historyMsg.content == null) return null
        return dev.xacnio.kciktv.shared.data.model.ChatMessage(
            id = historyMsg.id ?: "h_${historyMsg.createdAt}_${historyMsg.sender.id}",
            content = historyMsg.content.replace(Regex("[\\r\\n]+"), " "),
            sender = dev.xacnio.kciktv.shared.data.model.ChatSender(
                id = historyMsg.sender.id ?: 0L,
                username = historyMsg.sender.username ?: "Anonymous",
                color = historyMsg.sender.identity?.color,
                badges = historyMsg.sender.identity?.badges?.map { badge ->
                    dev.xacnio.kciktv.shared.data.model.ChatBadge(
                        type = badge.type ?: "",
                        text = badge.text,
                        count = badge.count
                    )
                },
                profilePicture = historyMsg.sender.profilePicture
            ),
            createdAt = parseIsoDate(historyMsg.createdAt),
            messageRef = try {
                historyMsg.metadata?.let { metaStr ->
                    val obj = org.json.JSONObject(metaStr)
                    if (obj.has("message_ref")) obj.getString("message_ref") else null
                }
            } catch (e: Exception) { null },
            metadata = try {
                historyMsg.metadata?.let { metaStr ->
                    val pusherMeta = Gson().fromJson(metaStr, dev.xacnio.kciktv.shared.data.model.PusherMetadata::class.java)
                    if (pusherMeta?.originalSender != null) {
                        dev.xacnio.kciktv.shared.data.model.ChatMetadata(
                            originalSender = dev.xacnio.kciktv.shared.data.model.ChatSender(
                                id = pusherMeta.originalSender.id ?: 0L,
                                username = pusherMeta.originalSender.username ?: "",
                                color = pusherMeta.originalSender.identity?.color,
                                badges = pusherMeta.originalSender.identity?.badges?.map { b ->
                                    dev.xacnio.kciktv.shared.data.model.ChatBadge(b.type ?: "", b.text, b.count)
                                },
                                profilePicture = pusherMeta.originalSender.profilePicture
                            ),
                            originalMessageContent = pusherMeta.originalMessage?.content?.replace(Regex("[\\r\\n]+"), " "),
                            originalMessageId = pusherMeta.originalMessage?.id
                        )
                    } else null
                }
            } catch (e: Exception) { null },
            type = when (historyMsg.type) {
                "celebration" -> dev.xacnio.kciktv.shared.data.model.MessageType.CELEBRATION
                "gift" -> dev.xacnio.kciktv.shared.data.model.MessageType.GIFT
                else -> dev.xacnio.kciktv.shared.data.model.MessageType.CHAT
            },
            celebrationData = try {
                historyMsg.metadata?.let { metaStr ->
                    Gson().fromJson(metaStr, dev.xacnio.kciktv.shared.data.model.CelebrationMetadata::class.java).celebration
                }
            } catch (e: Exception) { null },
            giftData = try {
                historyMsg.metadata?.let { metaStr ->
                    if (historyMsg.type == "gift") {
                        val obj = org.json.JSONObject(metaStr)
                        val giftObj = obj.optJSONObject("gift")
                        val senderObj = historyMsg.sender
                        
                        val giftData = dev.xacnio.kciktv.shared.data.model.GiftData(
                            giftId = giftObj?.optString("gift_id"),
                            name = giftObj?.optString("name"),
                            amount = giftObj?.optInt("amount"),
                            type = giftObj?.optString("type"),
                            tier = giftObj?.optString("tier")
                        )
                        
                        val sender = dev.xacnio.kciktv.shared.data.model.PusherSender(
                            id = senderObj.id,
                            username = senderObj.username,
                            slug = senderObj.slug,
                            identity = dev.xacnio.kciktv.shared.data.model.PusherIdentity(
                                color = senderObj.identity?.color,
                                badges = senderObj.identity?.badges?.map { b ->
                                    dev.xacnio.kciktv.shared.data.model.PusherBadge(b.type, b.text, b.count)
                                }
                            )
                        )
                        
                        dev.xacnio.kciktv.shared.data.model.KicksGiftedEventData(
                            giftTransactionId = obj.optString("gift_transaction_id", historyMsg.id ?: ""),
                            message = historyMsg.content,
                            sender = sender,
                            gift = giftData,
                            createdAt = historyMsg.createdAt,
                            expiresAt = null
                        )
                    } else null
                }
            } catch (e: Exception) { null }
        )
    }

    /**
     * Fetches pinned gifts for a specific channel
     */
    suspend fun getPinnedGifts(channelId: Long): Result<List<PinnedGift>> = withContext(Dispatchers.IO) {
        try {
            val response = liveStreamsService.getPinnedGifts(channelId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()?.data?.pinnedGifts ?: emptyList())
            } else {
                Result.success(emptyList()) // Fail gracefully
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPinnedGifts Exception: ${e.message}")
            Result.success(emptyList())
        }
    }

    /**
     * Fetches following live streams (v1 and v2 merged)
     * Thumbnails from v1, offline channels from v2.
     */
    suspend fun getFollowingLiveStreams(token: String, after: String? = null): Result<ChannelListData> = withContext(Dispatchers.IO) {
        try {
            val cleanToken = token.filter { it.code in 33..126 }
            val bearer = "Bearer $cleanToken"
            
            // Call both APIs in parallel
            val (v1Res, v2Res) = coroutineScope {
                // v1 usually doesn't need pagination for following (it's a live-only list)
                val v1Def = async { if (after == null) try { channelService.getFollowingLiveStreamsV1(bearer) } catch (e: Exception) { null } else null }
                val v2Def = async { try { channelService.getFollowingLiveStreams(bearer, after?.toIntOrNull()) } catch (e: Exception) { null } }
                v1Def.await() to v2Def.await()
            }
            
            val finalChannels = mutableListOf<ChannelItem>()
            
            // 1. Get live channels from v1 (With Thumbnails)
            val v1Channels = v1Res?.body()?.map { ChannelItem.fromLiveStreamItem(it) } ?: emptyList()
            finalChannels.addAll(v1Channels)
            
            // 2. Add offline channels from v2 and complete missing live ones from v1
            val v2ChannelsData = v2Res?.body()?.channels ?: emptyList()
            val v2Processed = coroutineScope {
                v2ChannelsData.map { v2Item ->
                    async {
                        val alreadyExists = finalChannels.any { it.slug == v2Item.channelSlug }
                        if (!alreadyExists) {
                            var ch = ChannelItem.fromFollowedChannelItem(v2Item)
                            
                            // If live but title is missing, fetch full details for better data (title, viewers, thumb)
                            if (v2Item.isLive && v2Item.sessionTitle.isNullOrBlank()) {
                                try {
                                    val detailResponse = channelService.getChannel(v2Item.channelSlug, bearer)
                                    if (detailResponse.isSuccessful) {
                                        detailResponse.body()?.let { details ->
                                            ch = ChannelItem.fromChannelDetailResponse(details)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to fetch details for ${v2Item.channelSlug}: ${e.message}")
                                }
                            }
                            
                            // If offline, use banner as thumbnail (if not already set)
                            if (!ch.isLive && v2Item.bannerPicture != null && ch.thumbnailUrl == null) {
                                ch = ch.copy(thumbnailUrl = v2Item.bannerPicture)
                            }
                            ch
                        } else null
                    }
                }.mapNotNull { it.await() }
            }
            finalChannels.addAll(v2Processed)
            
            // 3. Sorting: First Live (Top), then By Viewer Count (Z-A)
            // Note: If pagination exists, sorting happens within each page
            val sortedChannels = if (after == null) {
                finalChannels.sortedWith(
                    compareByDescending<ChannelItem> { it.isLive }
                    .thenByDescending { it.viewerCount }
                )
            } else {
                finalChannels // Don't change order from v2 (for pagination)
            }
            
            val nextCursor = v2Res?.body()?.nextCursor?.toString()
            
            Log.d(TAG, "Following Hybrid success: ${sortedChannels.size} channels total, next: $nextCursor")
            Result.success(ChannelListData(sortedChannels, nextCursor))
            
        } catch (e: Exception) {
            Log.e(TAG, "Following Hybrid Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Old v2 following list (Can be used for fallback and seeing offline channels)
     */
    private suspend fun getFollowingLiveStreamsV2(token: String): Result<ChannelListData> = withContext(Dispatchers.IO) {
        try {
            val cleanToken = token.filter { it.code in 33..126 }
            val response = channelService.getFollowingLiveStreams("Bearer $cleanToken")
            if (response.isSuccessful && response.body() != null) {
                val data = response.body()!!
                val mappedChannels = data.channels.map { item ->
                    val ch = ChannelItem.fromFollowedChannelItem(item)
                    if (!item.isLive && item.bannerPicture != null) {
                        ch.copy(thumbnailUrl = item.bannerPicture)
                    } else ch
                }
                val sortedChannels = mappedChannels.sortedByDescending { it.isLive }
                Result.success(ChannelListData(sortedChannels, data.nextCursor?.toString()))
            } else {
                Result.failure(Exception("Following v2 API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Search for channels and categories using Kick's multi_search API
     */
    suspend fun searchChannels(query: String): Result<List<dev.xacnio.kciktv.shared.data.model.SearchResultItem>> = withContext(Dispatchers.IO) {
        try {
            // Build the request body for multi_search
            val requestJson = """
                {
                    "searches": [
                        {
                            "collection": "channel",
                            "q": "$query",
                            "query_by": "username",
                            "per_page": 10,
                            "filter_by": "is_banned:false"
                        },
                        {
                            "collection": "subcategory_index",
                            "q": "$query",
                            "query_by": "name",
                            "per_page": 5
                        },
                        {
                            "collection": "tags",
                            "q": "$query",
                            "query_by": "label",
                            "per_page": 5
                        }
                    ]
                }
            """.trimIndent()
            
            val requestBody = requestJson.toRequestBody("application/json".toMediaType())
            
            val response = searchService.multiSearch(requestBody)
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val results = mutableListOf<dev.xacnio.kciktv.shared.data.model.SearchResultItem>()
                
                for (searchResult in body.results) {
                    val collectionName = searchResult.requestParams.collectionName
                    
                    for (hit in searchResult.hits) {
                        val doc = hit.document
                        
                        when (collectionName) {
                            "channel" -> {
                                if (doc.username != null && doc.slug != null) {
                                    results.add(
                                        dev.xacnio.kciktv.shared.data.model.SearchResultItem.ChannelResult(
                                            id = doc.id,
                                            slug = doc.slug,
                                            username = doc.username,
                                            isLive = doc.isLive,
                                            verified = doc.verified,
                                            followersCount = doc.followersCount,
                                            profilePic = doc.profilePic
                                        )
                                    )
                                }
                            }
                            "subcategory_index" -> {
                                if (doc.name != null && doc.slug != null) {
                                    results.add(
                                        dev.xacnio.kciktv.shared.data.model.SearchResultItem.CategoryResult(
                                            id = doc.id,
                                            slug = doc.slug,
                                            name = doc.name,
                                            imageUrl = doc.src,
                                            parent = doc.parent
                                        )
                                    )
                                }
                            }
                            "tags" -> {
                                if (doc.label != null) {
                                    results.add(
                                        dev.xacnio.kciktv.shared.data.model.SearchResultItem.TagResult(
                                            id = doc.id,
                                            label = doc.label
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                
                Log.d(TAG, "Search returned ${results.size} results for query: $query")
                Result.success(results)
            } else {
                Log.e(TAG, "Search API error: ${response.code()}")
                Result.failure(Exception("Search API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Search Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Sends a chat message to a chatroom
     */
    /**
     * Sends a chat message to a chatroom
     */
    suspend fun sendChatMessage(
        token: String, 
        chatroomId: Long, 
        message: String, 
        messageRef: String? = null,
        replyToMessage: dev.xacnio.kciktv.shared.data.model.ChatMessage? = null
    ): Result<dev.xacnio.kciktv.shared.data.model.ChatMessage?> = withContext(Dispatchers.IO) {
        try {
            val authService = RetrofitClient.authService
            val ref = messageRef ?: System.currentTimeMillis().toString()
            
            val request = if (replyToMessage != null) {
                // Construct reply metadata
                val metadata = dev.xacnio.kciktv.shared.data.api.SendMessageMetadata(
                    originalMessage = dev.xacnio.kciktv.shared.data.api.OriginalMessageData(
                        id = replyToMessage.id,
                        content = replyToMessage.content
                    ),
                    originalSender = dev.xacnio.kciktv.shared.data.api.OriginalSenderData(
                        id = replyToMessage.sender.id.toInt(),
                        username = replyToMessage.sender.username
                    )
                )
                
                dev.xacnio.kciktv.shared.data.api.SendMessageRequest(
                    content = message, 
                    type = "reply",
                    messageRef = ref,
                    metadata = metadata
                )
            } else {
                dev.xacnio.kciktv.shared.data.api.SendMessageRequest(
                    content = message,
                    messageRef = ref
                )
            }
            
            val response = authService.sendChatMessage("Bearer $token", chatroomId, request)
            
            if (response.isSuccessful) {
                val bodyStr = response.body()?.string()
                var parsedMessage: dev.xacnio.kciktv.shared.data.model.ChatMessage? = null
                
                if (bodyStr != null) {
                    try {
                        val json = org.json.JSONObject(bodyStr)
                        val dataFn = json.optJSONObject("data")
                        if (dataFn != null) {
                            val historyMsg = com.google.gson.Gson().fromJson(dataFn.toString(), dev.xacnio.kciktv.shared.data.model.ChatHistoryMessage::class.java)
                            parsedMessage = parseMessage(historyMsg)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse sent message response: ${e.message}")
                    }
                }

                Log.d(TAG, "Message sent successfully to chatroom $chatroomId")
                Result.success(parsedMessage)
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                Log.e(TAG, "Send message failed: ${response.code()} - $errorBody")
                
                val errorMessage = try {
                    val errorResponse = com.google.gson.Gson().fromJson(errorBody, dev.xacnio.kciktv.shared.data.model.ChatErrorResponse::class.java)
                    errorResponse.status?.message ?: "Unknown error"
                } catch (e: Exception) {
                    "API error: ${response.code()}"
                }
                
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send message Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches emotes for a channel (includes channel emotes, global, and emoji)
     */
    suspend fun getEmotes(slug: String, token: String?): Result<List<dev.xacnio.kciktv.shared.data.model.EmoteCategory>> = withContext(Dispatchers.IO) {
        try {
            val authHeader = if (token != null) "Bearer $token" else null
            val response = channelService.getEmotes(slug, authHeader)
            
            if (response.isSuccessful && response.body() != null) {
                Log.d(TAG, "Fetched emotes for channel $slug: ${response.body()!!.size} categories")
                Result.success(response.body()!!)
            } else {
                Log.e(TAG, "Get emotes failed: ${response.code()}")
                Result.failure(Exception("Failed to get emotes: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get emotes Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun checkStreamStatus(url: String): Pair<Int, String?> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.checkUrl(url)
            val code = response.code()
            val body = if (response.isSuccessful) {
                response.body()?.use { it.string() }
            } else {
                response.errorBody()?.use { it.string() }
                null
            }
            code to body
        } catch (e: Exception) {
            // Network error
            -1 to null
        }
    }
    
    /**
     * Updates stream category for a channel
     */
    suspend fun updateStreamCategory(slug: String, token: String, categoryId: Long): Result<Boolean> = withContext(Dispatchers.IO) {
        updateChannelInfo(slug, token, categoryId = categoryId)
    }

    suspend fun getChatRules(slug: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getChatRules(slug)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()?.data?.rules)
            } else {
                Result.failure(Exception("Failed to get rules: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Updates stream mature status.
     */
    suspend fun updateStreamMature(slug: String, token: String, isMature: Boolean): Result<Boolean> = withContext(Dispatchers.IO) {
        updateChannelInfo(slug, token, isMature = isMature)
    }

    /**
     * Fetches current viewer count for a livestream
     */
    suspend fun getCurrentViewers(livestreamId: Long): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getCurrentViewers(livestreamId)
            if (response.isSuccessful && response.body() != null) {
                val viewers = response.body()!!.firstOrNull()?.viewers ?: 0
                Result.success(viewers)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    /**
     * Removes (unpins) the pinned message from a channel
     * Requires moderator or channel owner permission
     */
    suspend fun unpinMessage(slug: String, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.unpinMessage(slug, "Bearer $token")
            if (response.isSuccessful) {
                Log.d(TAG, "Pinned message removed from channel: $slug")
                Result.success(true)
            } else {
                Log.e(TAG, "Unpin message failed: ${response.code()}")
                Result.failure(Exception("Unpin failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unpin message Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Pins a message to a channel
     * Requires moderator or channel owner permission
     * @param messageJson The full message JSON object
     * @param duration Duration in seconds (default 1200 = 20 minutes)
     */
    suspend fun pinMessage(slug: String, token: String, messageJson: String, duration: Int = 1200): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val requestBody = """{"message": $messageJson, "duration": $duration}"""
                .toRequestBody("application/json".toMediaType())
            
            val response = channelService.pinMessage(slug, "Bearer $token", requestBody)
            if (response.isSuccessful) {
                Log.d(TAG, "Message pinned to channel: $slug")
                Result.success(true)
            } else {
                Log.e(TAG, "Pin message failed: ${response.code()}")
                Result.failure(Exception("Pin failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Pin message Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Deletes a chat message
     * Requires moderator or channel owner permission
     */
    suspend fun deleteMessage(chatroomId: Long, messageId: String, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.deleteMessage(chatroomId, messageId, "Bearer $token")
            if (response.isSuccessful) {
                Log.d(TAG, "Message deleted: $messageId")
                Result.success(true)
            } else {
                Log.e(TAG, "Delete message failed: ${response.code()}")
                Result.failure(Exception("Silme başarısız: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete message Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches the latest prediction for the channel
     */
    suspend fun getLatestPrediction(slug: String): Result<dev.xacnio.kciktv.shared.data.model.PredictionData> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getLatestPrediction(slug)
            if (response.isSuccessful && response.body() != null) {
                val prediction = response.body()!!.data?.prediction
                if (prediction != null) {
                    Result.success(prediction)
                } else {
                    Result.failure(Exception("No active prediction data found"))
                }
            } else {
                Log.e(TAG, "Get latest prediction failed: ${response.code()}")
                Result.failure(Exception("Failed to get latest prediction: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get latest prediction Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches loyalty rewards for a channel
     */
    suspend fun getChannelRewards(slug: String): Result<List<dev.xacnio.kciktv.shared.data.model.LoyaltyRewardItem>> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getChannelRewards(slug)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.data)
            } else {
                Log.e(TAG, "Get rewards failed: ${response.code()}")
                Result.failure(Exception("Failed to get rewards: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get rewards Exception: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    private fun generateKickStyleTransactionId(): String {
        val chars = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val sb = StringBuilder()
        val random = java.security.SecureRandom()
        
        // Timestamp (10 chars)
        var time = System.currentTimeMillis()
        val timeChars = CharArray(10)
        for (i in 9 downTo 0) {
            timeChars[i] = chars[(time % 32).toInt()]
            time /= 32
        }
        sb.append(timeChars)
        
        // Random (16 chars)
        for (i in 0 until 16) {
            sb.append(chars[random.nextInt(32)])
        }
        
        return sb.toString()
    }

    suspend fun redeemReward(slug: String, rewardId: String, token: String, message: String? = null): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            // Kick requires a transaction_id in ULID-like format (Crockford Base32)
            val txId = generateKickStyleTransactionId()
            
            val body = mutableMapOf("transaction_id" to txId)
            if (!message.isNullOrBlank()) {
                body["message"] = message
            }
            
            val response = channelService.redeemReward(slug, rewardId, "Bearer $token", body)
            if (response.isSuccessful && response.body() != null) {
                Result.success(true)
            } else {
                Log.e(TAG, "Redeem failed: ${response.code()}")
                Result.failure(Exception("Redeem failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Redeem Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getChannelPoints(slug: String, token: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
             // Clean token just in case
            val cleanToken = token.filter { it.code in 33..126 }
            val response = channelService.getChannelPoints(slug, "Bearer $cleanToken")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!.data.points)
            } else {
                 Log.e(TAG, "Get points failed: ${response.code()}")
                 Result.failure(Exception("Failed to get points"))
            }
        } catch(e: Exception) {
             Log.e(TAG, "Get points Exception: ${e.message}", e)
             Result.failure(e)
        }
    }

    suspend fun getViewerToken(): Result<String> = withContext(Dispatchers.IO) {
         try {
             val response = channelService.getViewerToken()
             if (response.isSuccessful && response.body() != null) {
                 val json = response.body()!!.string()
                 // Assuming format {"token": "..."}
                 try {
                     val obj = org.json.JSONObject(json)
                     if (obj.has("token")) {
                        Result.success(obj.getString("token"))
                     } else {
                        Result.success(json) 
                     }
                 } catch (e: Exception) {
                     Result.success(json)
                 }
             } else {
                 Result.failure(Exception("Failed to get token"))
             }
         } catch (e: Exception) {
             Result.failure(e)
         }
    }

    /**
     * Timeout a user (temporary ban)
     * @param durationSeconds Duration in seconds
     */
    suspend fun timeoutUser(slug: String, username: String, durationSeconds: Int, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val requestBody = """{"banned_username": "$username", "duration": $durationSeconds, "permanent": false}"""
                .toRequestBody("application/json".toMediaType())
            
            val response = channelService.timeoutUser(slug, "Bearer $token", requestBody)
            if (response.isSuccessful) {
                Log.d(TAG, "User timed out: $username for $durationSeconds seconds")
                Result.success(true)
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                Log.e(TAG, "Timeout user failed: ${response.code()} - $errorBody")
                
                val errorMessage = try {
                    val json = org.json.JSONObject(errorBody)
                    json.optString("message", "API error: ${response.code()}")
                } catch (e: Exception) {
                    "API error: ${response.code()}"
                }
                
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Timeout user Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches and parses channel links from kick.com/{slug}/about
     */
    suspend fun getChannelLinks(slug: String): Result<List<dev.xacnio.kciktv.shared.data.model.ChannelLink>> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getChannelLinks(slug)
            
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Log.e(TAG, "getChannelLinks failed: ${response.code()}")
                Result.failure(Exception("Failed to get channel links: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getChannelLinks Exception: ${e.message}", e)
            Result.failure(e)
        }
    }




    /**
     * Fetches chat history for VOD/Clip replay from a specific time
     * @param channelId Channel ID for chat
     * @param startTime ISO8601 formatted start time
     */
    /**
     * Fetches chat history for VOD/Clip replay from a specific time
     * @param channelId Channel ID for chat
     * @param startTime ISO8601 formatted start time
     */
    suspend fun getChatHistoryForVod(channelId: Long, startTime: String): Result<ChatHistoryResult> = withContext(Dispatchers.IO) {
        try {
            val response = liveStreamsService.getChatHistory(channelId, startTime)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val historyData = body.data
                val messageList = historyData?.messages ?: body.messages ?: emptyList()
                val nextCursor = historyData?.cursor ?: body.cursor
                
                val messages = messageList.mapNotNull { parseMessage(it) }
                                          .sortedBy { it.createdAt }
                
                Result.success(ChatHistoryResult(messages, null, nextCursor))
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    /**
     * Helper to extract a balanced JSON array or object from a string starting at index
     */
    private fun extractJson(text: String, startIdx: Int): String {
        var balance = 0
        var i = startIdx
        var inString = false
        var escaped = false
        val startChar = text[startIdx]
        val endChar = if (startChar == '[') ']' else '}'

        while (i < text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == '\"') {
                inString = !inString
            } else if (!inString) {
                if (c == startChar) balance++
                else if (c == endChar) {
                    balance--
                    if (balance == 0) return text.substring(startIdx, i + 1)
                }
            }
            i++
        }
        throw Exception("Unbalanced JSON structure")
    }

    private fun unescapeJsString(escaped: String): String {
        val builder = StringBuilder()
        var i = 0
        while (i < escaped.length) {
            val c = escaped[i]
            if (c == '\\' && i + 1 < escaped.length) {
                val next = escaped[i + 1]
                when (next) {
                    '\"' -> builder.append('\"')
                    '\\' -> builder.append('\\')
                    'n' -> builder.append('\n')
                    'r' -> builder.append('\r')
                    't' -> builder.append('\t')
                    'u' -> {
                        if (i + 5 < escaped.length) {
                            try {
                                val hex = escaped.substring(i + 2, i + 6)
                                builder.append(hex.toInt(16).toChar())
                                i += 5
                            } catch (e: Exception) {
                                builder.append("\\u")
                            }
                        } else {
                            builder.append("\\u")
                        }
                    }
                    else -> builder.append(next)
                }
                i += 1
            } else {
                builder.append(c)
            }
            i++
        }
        return builder.toString()
    }

    /**
     * Permanent ban a user
     */
    suspend fun banUser(slug: String, username: String, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val requestBody = """{"banned_username": "$username", "permanent": true}"""
                .toRequestBody("application/json".toMediaType())
            
            val response = channelService.banUser(slug, "Bearer $token", requestBody)
            if (response.isSuccessful) {
                Log.d(TAG, "User banned: $username")
                Result.success(true)
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                Log.e(TAG, "Ban user failed: ${response.code()} - $errorBody")
                
                val errorMessage = try {
                    val json = org.json.JSONObject(errorBody)
                    json.optString("message", "API error: ${response.code()}")
                } catch (e: Exception) {
                    "API error: ${response.code()}"
                }
                
                Result.failure(Exception(errorMessage))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ban user Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches the list of banned users for a specific channel
     */
    suspend fun getBans(slug: String, token: String): Result<List<dev.xacnio.kciktv.shared.data.model.BanItem>> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getBans(slug, "Bearer $token")
            if (response.isSuccessful && response.body() != null) {
                val bans = response.body() ?: emptyList()
                Log.d(TAG, "Fetched ${bans.size} bans for channel: $slug")
                Result.success(bans)
            } else {
                Log.e(TAG, "Get bans failed: ${response.code()}")
                Result.failure(Exception("Get bans failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Get bans Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Unban a user
     */
    suspend fun unbanUser(slug: String, username: String, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.unbanUser(slug, username, "Bearer $token")
            if (response.isSuccessful) {
                Log.d(TAG, "User unbanned: $username")
                Result.success(true)
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                Log.e(TAG, "Unban user failed: ${response.code()} - $errorBody")
                Result.failure(Exception("Unban failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unban user Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Updates chatroom settings (slow mode, followers only, subscribers only, emotes only)
     * PUT https://kick.com/api/v2/channels/{slug}/chatroom
     * Requires moderator or channel owner permission
     */
    suspend fun updateChatroomSettings(
        slug: String, 
        token: String,
        slowMode: Boolean? = null,
        messageInterval: Int? = null,        // For slow mode (seconds)
        followersMode: Boolean? = null,
        followersModeMinDuration: Int? = null, // For followers mode (minutes)
        subscribersMode: Boolean? = null,
        emotesMode: Boolean? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val jsonParts = mutableListOf<String>()
            
            // Slow mode: {"slow_mode": true, "message_interval": 15}
            slowMode?.let { jsonParts.add("\"slow_mode\": $it") }
            messageInterval?.let { jsonParts.add("\"message_interval\": $it") }
            
            // Followers mode: {"followers_mode": true, "following_min_duration": 10}
            followersMode?.let { jsonParts.add("\"followers_mode\": $it") }
            followersModeMinDuration?.let { jsonParts.add("\"following_min_duration\": $it") }
            
            // Subscribers mode: {"subscribers_mode": true}
            subscribersMode?.let { jsonParts.add("\"subscribers_mode\": $it") }
            
            // Emotes mode: {"emotes_mode": true}
            emotesMode?.let { jsonParts.add("\"emotes_mode\": $it") }
            
            val requestBody = "{${jsonParts.joinToString(", ")}}"
                .toRequestBody("application/json".toMediaType())
            
            Log.d(TAG, "Updating chatroom for $slug: $requestBody")
            
            val response = channelService.updateChatroomSettings(slug, "Bearer $token", requestBody)
            if (response.isSuccessful) {
                Log.d(TAG, "Chatroom settings updated successfully")
                Result.success(true)
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                Log.e(TAG, "Update chatroom settings failed: ${response.code()} - $errorBody")
                Result.failure(Exception("Update failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update chatroom settings Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun updateChannelInfo(
        slug: String,
        token: String,
        title: String? = null,
        categoryId: Long? = null,
        isMature: Boolean? = null,
        language: String? = null
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val jsonParts = mutableListOf<String>()
            title?.let { jsonParts.add("\"stream_title\": \"${it.replace("\"", "\\\"")}\"") }
            categoryId?.let { jsonParts.add("\"category_id\": $it") }
            isMature?.let { jsonParts.add("\"is_mature\": $it") }
            language?.let { jsonParts.add("\"language\": \"$it\"") }
            jsonParts.add("\"tags\": []") // Required field
            
            val requestBody = "{${jsonParts.joinToString(", ")}}"
                .toRequestBody("application/json".toMediaType())
            
            val response = channelService.updateChannelInfo(slug, "Bearer $token", requestBody)
            if (response.isSuccessful) Result.success(true)
            else Result.failure(Exception("Update failed: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun searchCategories(query: String): Result<List<dev.xacnio.kciktv.shared.data.model.CategoryInfo>> = withContext(Dispatchers.IO) {
        try {
            val response = searchService.searchCategories(query)
            if (response.isSuccessful) {
                val hits = response.body()?.hits ?: emptyList<dev.xacnio.kciktv.shared.data.model.CategorySearchHit>()
                val mapped = hits.mapNotNull { it.document }.map { doc ->
                    dev.xacnio.kciktv.shared.data.model.CategoryInfo(
                        id = doc.id?.toLongOrNull() ?: 0L,
                        name = doc.name ?: "",
                        slug = doc.slug ?: "",
                        banner = if (doc.src != null) dev.xacnio.kciktv.shared.data.model.BannerInfo(doc.src) else null
                    )
                }
                Result.success(mapped)
            } else Result.failure(Exception("Search failed: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getStreamInfo(slug: String, token: String? = null): Result<dev.xacnio.kciktv.shared.data.model.StreamInfoResponse> = withContext(Dispatchers.IO) {
        try {
            val authHeader = token?.let { "Bearer $it" }
            val response = channelService.getStreamInfo(slug, authHeader)
            if (response.code() == 204) {
                 Result.failure(Exception("Yayın bilgisi alınamadı (Server 204)"))
            } else if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to get stream info: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    suspend fun getUserChatHistory(channelId: Long, userId: Long, channelSlug: String?, token: String?, cursor: String? = null): Result<ChatHistoryResult> = withContext(Dispatchers.IO) {
        try {
            val referer = if (!channelSlug.isNullOrEmpty()) "https://dashboard.kick.com/" else "https://dashboard.kick.com/"
            val authToken = if (token.isNullOrEmpty()) "" else token
            val response = channelService.getUserChatHistory(channelId, userId, authToken, referer, cursor)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                
                fun parseIsoDate(isoString: String?): Long {
                    if (isoString == null) return System.currentTimeMillis()
                    return try {
                        val cleaned = if (isoString.contains(".")) {
                            isoString.substringBefore(".") + "Z"
                        } else isoString
                        val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        format.parse(cleaned)?.time ?: System.currentTimeMillis()
                    } catch (e: Exception) {
                        try {
                            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                            format.parse(isoString)?.time ?: System.currentTimeMillis()
                        } catch (e2: Exception) {
                            System.currentTimeMillis()
                        }
                    }
                }

                val historyData = body.data
                val messageList = historyData?.messages ?: body.messages
                val nextCursor = historyData?.cursor
                
                val messages = messageList?.mapNotNull { historyMsg ->
                    if (historyMsg.sender != null && historyMsg.content != null) {
                        dev.xacnio.kciktv.shared.data.model.ChatMessage(
                            id = historyMsg.id ?: "h_${historyMsg.createdAt}_${historyMsg.sender.id}",
                            content = historyMsg.content,
                            sender = dev.xacnio.kciktv.shared.data.model.ChatSender(
                                id = historyMsg.sender.id ?: 0L,
                                username = historyMsg.sender.username ?: "Anonymous",
                                color = historyMsg.sender.identity?.color,
                                badges = historyMsg.sender.identity?.badges?.map { badge ->
                                    dev.xacnio.kciktv.shared.data.model.ChatBadge(
                                        type = badge.type ?: "",
                                        text = badge.text,
                                        count = badge.count
                                    )
                                },
                                profilePicture = historyMsg.sender.profilePicture
                            ),
                            createdAt = parseIsoDate(historyMsg.createdAt),
                            messageRef = try {
                                historyMsg.metadata?.let { metaStr ->
                                    val obj = org.json.JSONObject(metaStr)
                                    if (obj.has("message_ref")) obj.getString("message_ref") else null
                                }
                            } catch (e: Exception) { null }
                        )
                    } else null
                } ?: emptyList()
                
                Result.success(ChatHistoryResult(messages, null, nextCursor))
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    suspend fun voteInPoll(channelSlug: String, token: String, optionId: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.voteInPoll(channelSlug, "Bearer $token", mapOf("id" to optionId))
            if (response.isSuccessful) {
                Result.success(true)
            } else {
                Result.failure(Exception("Vote failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun voteInPrediction(channelSlug: String, token: String, outcomeId: String, amount: Int): Result<dev.xacnio.kciktv.shared.data.model.PredictionVoteResponse> = withContext(Dispatchers.IO) {
        try {
            val request = dev.xacnio.kciktv.shared.data.model.PredictionVoteRequest(amount = amount, outcomeId = outcomeId)
            val response = channelService.voteInPrediction(channelSlug, "Bearer $token", request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                val errorMsg = response.errorBody()?.string() ?: "Unknown error"
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches available gifts metadata
     */
    suspend fun getGifts(): Result<List<dev.xacnio.kciktv.shared.data.model.GiftMetadata>> = withContext(Dispatchers.IO) {
        try {
            val response = liveStreamsService.getGifts()
            if (response.isSuccessful && response.body() != null) {
                val data = response.body()!!.data ?: emptyList()
                Result.success(data)
            } else {
                Result.failure(Exception("Get gifts failed: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Creates a poll in the channel
     */
    suspend fun createPoll(
        channelSlug: String, 
        token: String, 
        title: String, 
        options: List<String>,
        duration: Int = 30,
        resultDisplayDuration: Int = 15
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = dev.xacnio.kciktv.shared.data.model.CreatePollRequest(
                title = title,
                options = options,
                duration = duration,
                resultDisplayDuration = resultDisplayDuration
            )
            val response = channelService.createPoll(channelSlug, "Bearer $token", request)
            if (response.isSuccessful) {
                Log.d(TAG, "Poll created successfully in channel: $channelSlug")
                Result.success(true)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Create poll failed: ${response.code()} - $errorBody")
                Result.failure(Exception("Anket oluşturulamadı: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Create poll Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Deletes/ends the current poll in the channel
     */
    suspend fun deletePoll(channelSlug: String, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.deletePoll(channelSlug, "Bearer $token")
            if (response.isSuccessful) {
                Log.d(TAG, "Poll deleted successfully in channel: $channelSlug")
                Result.success(true)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Delete poll failed: ${response.code()} - $errorBody")
                Result.failure(Exception("Anket silinemedi: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Delete poll Exception: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun lockPrediction(channelSlug: String, predictionId: String, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = UpdatePredictionRequest(state = "LOCKED")
            val response = channelService.updatePrediction(channelSlug, predictionId, "Bearer $token", request)
            if (response.isSuccessful) Result.success(true)
            else Result.failure(Exception("Kilitleme başarısız: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resolvePrediction(channelSlug: String, predictionId: String, token: String, outcomeId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = UpdatePredictionRequest(state = "RESOLVED", winningOutcomeId = outcomeId)
            val response = channelService.updatePrediction(channelSlug, predictionId, "Bearer $token", request)
            if (response.isSuccessful) Result.success(true)
            else Result.failure(Exception("Sonuçlandırma başarısız: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun cancelPrediction(channelSlug: String, predictionId: String, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = UpdatePredictionRequest(state = "CANCELLED")
            val response = channelService.updatePrediction(channelSlug, predictionId, "Bearer $token", request)
            if (response.isSuccessful) Result.success(true)
            else Result.failure(Exception("İptal başarısız: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createPrediction(
        channelSlug: String,
        token: String,
        title: String,
        outcomes: List<String>,
        duration: Int
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = CreatePredictionRequest(
                title = title,
                outcomes = outcomes,
                duration = duration
            )
            val response = channelService.createPrediction(channelSlug, "Bearer $token", request)
            if (response.isSuccessful) Result.success(true)
            else Result.failure(Exception("Sohbet tahmini oluşturulamadı: ${response.code()}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    suspend fun getTopCategories(): Result<List<dev.xacnio.kciktv.shared.data.model.TopCategory>> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getTopCategories()
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch top categories: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSubcategories(limit: Int = 32, page: Int = 1): Result<dev.xacnio.kciktv.shared.data.model.SubcategoriesResponse> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getSubcategories(limit, page)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to fetch subcategories: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }



    /**
     * Creates a clip draft logic
     */
    suspend fun createClipDraft(slug: String, token: String): Result<dev.xacnio.kciktv.shared.data.model.ClipResponse> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.createClipDraft(slug, "Bearer $token")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("Failed to create clip draft: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Finalizes and publishes a clip
     * @param slug The livestream slug (from livestream.slug)
     * @param clipId The clip ID (from clipResponse.id)
     * @param title The clip title (if empty, use stream title)
     * @param startTime The start time in seconds
     * @param duration The duration in seconds
     * @param token The auth token
     */
    suspend fun finalizeClip(slug: String, clipId: String, title: String, startTime: Int, duration: Int, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val request = dev.xacnio.kciktv.shared.data.model.FinalizeClipRequest(
                duration = duration,
                startTime = startTime,
                title = title
            )
            Log.d(TAG, "Finalizing clip: slug=$slug, clipId=$clipId, title=$title, startTime=$startTime, duration=$duration")
            val response = channelService.finalizeClip(slug, clipId, "Bearer $token", request)
            if (response.isSuccessful) {
                Log.d(TAG, "Clip finalized successfully")
                Result.success(true)
            } else {
                val errorBody = response.errorBody()?.string() ?: ""
                Log.e(TAG, "Finalize clip failed: ${response.code()} - $errorBody")
                Result.failure(Exception("Klip paylaşılamadı: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Finalize clip Exception: ${e.message}", e)
            Result.failure(e)
        }
    }
    suspend fun getChannelClips(
        slug: String, 
        cursor: String? = null, 
        sort: String = "date", 
        time: String = "all"
    ): Result<dev.xacnio.kciktv.shared.data.model.ChannelClipsResponse> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getChannelClips(slug, cursor, sort, time)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    
    /**
     * Gets the active prediction for a channel
     */
    suspend fun getActivePrediction(slug: String, token: String? = null): Result<dev.xacnio.kciktv.shared.data.model.PredictionData?> = withContext(Dispatchers.IO) {
        try {
            val authHeader = if (token != null) "Bearer $token" else null
            val response = channelService.getLatestPrediction(slug, authHeader)
            if (response.isSuccessful && response.body() != null) {
                // The API returns the event data wrapper, extract the prediction AND user_vote
                val data = response.body()?.data
                var prediction = data?.prediction
                
                // Merge userVote (which is outside prediction object in this endpoint)
                if (prediction != null && data?.userVote != null) {
                    prediction = prediction.copy(userVote = data.userVote)
                }
                
                Result.success(prediction)
            } else {
                Result.success(null) // Return null if not found/error, don't fail entire flow
            }
        } catch (e: Exception) {
            Result.success(null)
        }
    }

    suspend fun getRecentPredictions(slug: String, token: String): Result<List<dev.xacnio.kciktv.shared.data.model.PredictionData>?> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getRecentPredictions(slug, "Bearer $token")
            if (response.isSuccessful) {
                Result.success(response.body()?.data?.predictions)
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.success(emptyList())
        }
    }


    /**
     * Gets the current poll for a channel
     * Note: Kick API doesn't expose a direct "get current poll" endpoint easily like predictions.
     * This is largely handled via WebSocket. This method acts as a placeholder or check if available.
     */
    suspend fun getPoll(@Suppress("UNUSED_PARAMETER") slug: String): Result<dev.xacnio.kciktv.shared.data.model.PollData?> = withContext(Dispatchers.IO) {
        // Currently no public endpoint to fetch active poll state directly without WS.
        // Returning null so checking doesn't crash.
        Result.success(null)
    }

    /**
     * Fetches browse clips (global clips feed)
     * @param sort Sort order: "date" or "view"
     * @param time Time filter: "day", "week", "month", or "all"
     * @param next Pagination cursor
     */
    suspend fun getBrowseClips(
        sort: String = "date",
        time: String = "all",
        cursor: String? = null
    ): BrowseClipsResponse? = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getBrowseClips(sort, time, cursor)
            if (response.isSuccessful && response.body() != null) {
                response.body()
            } else {
                Log.e(TAG, "getBrowseClips API error: ${response.code()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getBrowseClips Exception: ${e.message}", e)
            null
        }
    }

    suspend fun getFollowedCategories(token: String): Result<List<dev.xacnio.kciktv.shared.data.model.TopCategory>> = withContext(Dispatchers.IO) {
        try {
            val cleanToken = token.filter { it.code in 33..126 }
            val response = channelService.getFollowedCategories("Bearer $cleanToken")
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleCategoryFollow(slug: String, token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val cleanToken = token.filter { it.code in 33..126 }
            val response = channelService.toggleCategoryFollow(slug, "Bearer $cleanToken")
            if (response.isSuccessful && response.body() != null) {
                val isFollowing = response.body()!!.deletedAt == null
                Result.success(isFollowing)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getCategoryClips(slug: String, sort: String = "view", time: String = "day", cursor: String? = null): Result<dev.xacnio.kciktv.shared.data.model.BrowseClipsResponse?> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getCategoryClips(slug, sort, time, cursor)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body())
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getClipPlayDetails(id: String): Result<dev.xacnio.kciktv.shared.data.model.ClipPlayResponse> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getClipPlayDetails(id)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches Blerp URL for a specific channel using GraphQL API
     * Returns the full Blerp URL (https://blerp.com/x/{username}) if available
     */
    suspend fun getBlerpUrl(kickUsername: String): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val blerpService = RetrofitClient.blerpService
            val request = dev.xacnio.kciktv.shared.data.api.BlerpGraphQLRequest(
                variables = dev.xacnio.kciktv.shared.data.api.BlerpVariables(kickUsername = kickUsername)
            )
            
            val response = blerpService.getBlerpUsername(request)
            
            if (response.isSuccessful && response.body() != null) {
                val blerpUsername = response.body()?.data?.soundEmotes?.currentStreamerPage?.streamerBlerpUser?.username
                
                if (!blerpUsername.isNullOrEmpty()) {
                    val blerpUrl = "https://blerp.com/x/$blerpUsername"
                    Log.d(TAG, "Blerp URL found for $kickUsername: $blerpUrl")
                    Result.success(blerpUrl)
                } else {
                    Log.d(TAG, "No Blerp user found for $kickUsername")
                    Result.success(null)
                }
            } else {
                Log.w(TAG, "Blerp API error: ${response.code()}")
                Result.success(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Blerp API Exception: ${e.message}")
            Result.success(null) // Fail gracefully, don't crash for Blerp
        }
    }
}




