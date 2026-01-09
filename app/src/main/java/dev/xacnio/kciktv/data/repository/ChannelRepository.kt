package dev.xacnio.kciktv.data.repository

import android.util.Log
import dev.xacnio.kciktv.data.api.RetrofitClient
import dev.xacnio.kciktv.data.model.ChannelItem
import dev.xacnio.kciktv.data.model.LivestreamResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
    
    companion object {
        private const val TAG = "ChannelRepository"
    }
    
    /**
     * Fetches live streams with language filter
     */
    suspend fun getFilteredLiveStreams(languages: List<String>? = null, after: String? = null, sort: String = "viewer_count_desc"): Result<ChannelListData> = withContext(Dispatchers.IO) {
        try {
            val response = liveStreamsService.getLiveStreams(languages = languages, sort = sort, limit = 50, after = after)
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
    
    /**
     * Fetches featured streams
     */
    suspend fun getFeaturedStreams(languages: List<String>? = null): Result<ChannelListData> = withContext(Dispatchers.IO) {
        try {
            val response = liveStreamsService.getFeaturedStreams(languages = languages)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val streams = body.data?.livestreams ?: emptyList()
                val channels = streams.map { ChannelItem.fromLiveStreamItem(it) }
                Result.success(ChannelListData(channels, null)) // Featured usually has no pagination
            } else {
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Fetches all live streams
     */
    suspend fun getAllLiveStreams(after: String? = null, sort: String = "viewer_count_desc"): Result<ChannelListData> = withContext(Dispatchers.IO) {
        try {
            val response = liveStreamsService.getAllLiveStreams(sort = sort, limit = 50, after = after)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val streams = body.data?.livestreams ?: emptyList()
                val channels = streams.map { ChannelItem.fromLiveStreamItem(it) }
                val cursor = body.data?.pagination?.nextCursor ?: body.pagination?.nextCursor
                Result.success(ChannelListData(channels, cursor))
            } else {
                Log.e(TAG, "API error: ${response.code()}")
                Result.failure(Exception("API error: ${response.code()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}", e)
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
                    Result.success(getDefaultStreamUrl(slug))
                }
            } else {
                Result.success(getDefaultStreamUrl(slug))
            }
        } catch (e: Exception) {
            Log.e(TAG, "getStreamUrl Exception: ${e.message}")
            Result.success(getDefaultStreamUrl(slug))
        }
    }
    
    /**
     * Fetches live stream details for a specific channel
     */
    suspend fun getLiveStreamDetails(slug: String): Result<LivestreamResponse> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getChannelLivestream(slug)
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
    suspend fun getChannelDetails(slug: String): Result<dev.xacnio.kciktv.data.model.ChannelDetailResponse> = withContext(Dispatchers.IO) {
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
    
    /**
     * Data class for chat information
     */
    data class ChatInfo(
        val channelId: Long,
        val chatroomId: Long,
        val subscriberBadges: Map<Int, String> // months -> badge URL
    )
    
    /**
     * Fetches chat information for a specific channel
     */
    suspend fun getChatInfo(slug: String): Result<ChatInfo> = withContext(Dispatchers.IO) {
        try {
            val response = channelService.getChannel(slug)
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                val channelId = body.id
                val chatroomId = body.chatroom?.id
                
                // Convert subscriber badges to month -> URL map
                val badgesMap = body.subscriberBadges?.mapNotNull { badge ->
                    val months = badge.months
                    val url = badge.badgeImage?.src
                    if (months != null && url != null) {
                        months to url
                    } else null
                }?.toMap() ?: emptyMap()
                
                if (channelId != null && chatroomId != null) {
                    Log.d(TAG, "Got channelId: $channelId, chatroomId: $chatroomId, badges: ${badgesMap.size} for slug: $slug")
                    Result.success(ChatInfo(channelId, chatroomId, badgesMap))
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
    
    /**
     * Fetches chat history for a specific chatroom
     */
    suspend fun getChatHistory(chatroomId: Long): Result<List<dev.xacnio.kciktv.data.model.ChatMessage>> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching chat history for chatroomId: $chatroomId")
            val response = liveStreamsService.getChatHistory(chatroomId)
            Log.d(TAG, "Chat history response code: ${response.code()}")
            
            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                Log.d(TAG, "Chat history body received, data=${body.data}, messages=${body.data?.messages?.size}")
                
                // Debug: log raw structure
                val gson = com.google.gson.Gson()
                Log.d(TAG, "Raw body: ${gson.toJson(body).take(500)}")
                
                val historyData = body.data
                val messageList = historyData?.messages
                
                val messages = messageList?.mapNotNull { historyMsg ->
                    Log.d(TAG, "Processing message: type=${historyMsg.type}, content=${historyMsg.content?.take(50)}")
                    // Only get normal messages (type: message)
                    if (historyMsg.type == "message" && historyMsg.sender != null && historyMsg.content != null) {
                        dev.xacnio.kciktv.data.model.ChatMessage(
                            id = historyMsg.id ?: "",
                            content = historyMsg.content,
                            sender = dev.xacnio.kciktv.data.model.ChatSender(
                                id = historyMsg.sender.id ?: 0L,
                                username = historyMsg.sender.username ?: "Anonymous",
                                color = historyMsg.sender.identity?.color,
                                badges = historyMsg.sender.identity?.badges?.map { badge ->
                                    dev.xacnio.kciktv.data.model.ChatBadge(
                                        type = badge.type ?: "",
                                        text = badge.text,
                                        count = badge.count
                                    )
                                }
                            )
                        )
                    } else null
                } ?: emptyList()
                
                Log.d(TAG, "Parsed ${messages.size} valid messages")
                
                // Sort messages chronologically (oldest at top)
                Result.success(messages.reversed())
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "getChatHistory API error: ${response.code()}, body: $errorBody")
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "getChatHistory Exception: ${e.message}", e)
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
            v2Res?.body()?.channels?.forEach { v2Item ->
                val alreadyExists = finalChannels.any { it.slug == v2Item.channelSlug }
                if (!alreadyExists) {
                    val ch = ChannelItem.fromFollowedChannelItem(v2Item)
                    // If offline, use banner as thumbnail
                    val updatedCh = if (!v2Item.isLive && v2Item.bannerPicture != null) {
                        ch.copy(thumbnailUrl = v2Item.bannerPicture)
                    } else ch
                    finalChannels.add(updatedCh)
                }
            }
            
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

    private fun getDefaultStreamUrl(slug: String): String {
        return "https://fa723fc1b171.us-west-2.playback.live-video.net/api/video/v1/us-west-2.196233775518.channel.$slug.m3u8"
    }
}
