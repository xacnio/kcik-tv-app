package dev.xacnio.kciktv.shared.data.mock

import dev.xacnio.kciktv.shared.data.api.UserResponse
import dev.xacnio.kciktv.shared.data.model.*
import kotlin.random.Random

object MockDataFactory {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun nowIso(): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date())
    }

    private fun pastIso(minutesAgo: Int): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(System.currentTimeMillis() - minutesAgo * 60_000L))
    }

    // Deterministic ID derived from slug: stable across calls
    private fun idFromSlug(slug: String): Long =
        (slug.fold(0L) { a, c -> a * 31 + c.code } and 0x7FFF_FFFFL) + 1_000L

    private fun chatroomIdFromSlug(slug: String): Long = idFromSlug(slug) + 500_000L

    // ------------------------------------------------------------------
    // Single channel
    // ------------------------------------------------------------------

    private fun buildLiveStreamItem(slug: String, rng: Random): LiveStreamItem {
        val username = MockDataPools.randomUsername(rng)
        val cat = MockDataPools.randomCategory(rng)
        val chanId = idFromSlug(slug)
        return LiveStreamItem(
            id = chanId.toString(),
            title = MockDataPools.randomTitle(rng),
            sessionTitle = MockDataPools.randomTitle(rng),
            viewerCount = MockDataPools.randomViewerCount(rng),
            viewers = null,
            thumbnail = ThumbnailInfo(MockImageUrls.thumbnail(slug), null),
            startTime = pastIso(rng.nextInt(10, 480)),
            channel = ChannelInfo(
                id = chanId,
                slug = slug,
                username = username,
                profilePic = MockImageUrls.avatar(slug),
                verified = rng.nextBoolean() && rng.nextBoolean(),
                user = null
            ),
            category = CategoryInfo(id = cat.id, name = cat.name, slug = cat.slug),
            categories = null,
            language = MockDataPools.randomLanguage(rng),
            isMature = false,
            tags = listOf(MockDataPools.tags[rng.nextInt(MockDataPools.tags.size)])
        )
    }

    // ------------------------------------------------------------------
    // Livestreams list  (web.kick.com/api/v1/livestreams)
    // ------------------------------------------------------------------

    fun buildLiveStreamsResponse(count: Int = MockConfig.LIVE_STREAM_COUNT): LiveStreamsResponse {
        val items = (0 until count).map { i ->
            val slug = "mock-channel-$i"
            val rng = MockDataPools.rng(slug)
            buildLiveStreamItem(slug, rng)
        }
        return LiveStreamsResponse(
            data = LiveStreamsData(livestreams = items, pagination = Pagination(nextCursor = null)),
            pagination = null,
            message = "Success"
        )
    }

    // ------------------------------------------------------------------
    // Following channels
    // ------------------------------------------------------------------

    fun buildFollowedChannelsResponse(count: Int = MockConfig.FOLLOWING_COUNT): FollowedChannelsResponse {
        val channels = (0 until count).map { i ->
            val slug = "followed-channel-$i"
            val rng = MockDataPools.rng(slug)
            val isLive = rng.nextFloat() > 0.35f
            FollowedChannelItem(
                isLive = isLive,
                profilePicture = MockImageUrls.avatar(slug),
                bannerPicture = MockImageUrls.thumbnail(slug),
                channelSlug = slug,
                viewerCount = if (isLive) MockDataPools.randomViewerCount(rng) else 0,
                categoryName = MockDataPools.randomCategory(rng).name,
                userUsername = MockDataPools.randomUsername(rng),
                sessionTitle = if (isLive) MockDataPools.randomTitle(rng) else null
            )
        }
        return FollowedChannelsResponse(nextCursor = null, channels = channels)
    }

    // Following v1 (List<LiveStreamItem>)
    fun buildFollowingV1(count: Int = MockConfig.FOLLOWING_COUNT): List<LiveStreamItem> {
        return (0 until count)
            .filter { it % 3 != 0 } // ~2/3 live
            .map { i ->
                val slug = "followed-channel-$i"
                buildLiveStreamItem(slug, MockDataPools.rng(slug))
            }
    }

    // ------------------------------------------------------------------
    // Channel detail  (kick.com/api/v2/channels/{slug})
    // ------------------------------------------------------------------

    fun buildChannelDetailResponse(slug: String): ChannelDetailResponse {
        val rng = MockDataPools.rng(slug)
        val username = MockDataPools.randomUsername(rng)
        val cat = MockDataPools.randomCategory(rng)
        val chanId = idFromSlug(slug)
        val chatroomId = chatroomIdFromSlug(slug)
        val viewers = MockDataPools.randomViewerCount(rng)
        val startTime = pastIso(rng.nextInt(10, 300))
        return ChannelDetailResponse(
            id = chanId,
            userId = chanId + 1,
            slug = slug,
            playbackUrl = null,
            vodEnabled = true,
            subscriptionEnabled = true,
            followersCount = rng.nextInt(500, 500_000),
            following = false,
            bannerImage = ImageInfo(
                url = MockImageUrls.thumbnail(slug, 1280, 720),
                src = null,
                srcset = null
            ),
            verified = rng.nextBoolean() && rng.nextBoolean(),
            user = UserDetail(
                id = chanId + 1,
                username = username,
                bio = "Mock channel for testing. ${MockDataPools.streamTitles[rng.nextInt(MockDataPools.streamTitles.size)]}",
                profilePic = MockImageUrls.avatar(slug),
                instagram = null, twitter = null, youtube = null, discord = null, tiktok = null, facebook = null
            ),
            livestream = LivestreamDetail(
                id = chanId + 2,
                slug = "$slug-live",
                sessionTitle = MockDataPools.randomTitle(rng),
                viewerCount = viewers,
                isLive = true,
                thumbnail = ImageInfo(MockImageUrls.thumbnail(slug, 1280, 720), null, null),
                categories = listOf(CategoryInfo(id = cat.id, name = cat.name, slug = cat.slug)),
                createdAt = startTime,
                startTime = startTime.replace("T", " ").replace("Z", ""),
                tags = listOf(MockDataPools.tags[rng.nextInt(MockDataPools.tags.size)]),
                langIso = MockDataPools.randomLanguage(rng),
                language = null,
                isMature = false
            ),
            chatroom = ChatroomInfo(
                id = chatroomId,
                chatableType = "App\\Models\\Channel",
                slowMode = false,
                followersMode = false,
                subscribersMode = false,
                emotesMode = false,
                slowModeInterval = null,
                followersMinDuration = null,
                pinnedMessage = null
            ),
            subscriberBadges = emptyList(),
            offlineBannerImage = ImageInfo(
                url = null,
                src = MockImageUrls.offlineBanner(slug),
                srcset = null
            ),
            recentCategories = listOf(CategoryInfo(id = cat.id, name = cat.name, slug = cat.slug)),
            previousLivestreams = null
        )
    }

    // ------------------------------------------------------------------
    // Livestream wrapper  (kick.com/api/v2/channels/{slug}/livestream)
    // ------------------------------------------------------------------

    fun buildLivestreamWrapper(slug: String): LivestreamResponseWrapper {
        val rng = MockDataPools.rng(slug)
        val cat = MockDataPools.randomCategory(rng)
        val chanId = idFromSlug(slug)
        val startTime = pastIso(rng.nextInt(10, 300))
        return LivestreamResponseWrapper(
            data = LivestreamResponse(
                id = chanId + 2,
                slug = "$slug-live",
                sessionTitle = MockDataPools.randomTitle(rng),
                playbackUrl = null,
                viewers = MockDataPools.randomViewerCount(rng),
                isLive = true,
                createdAt = startTime,
                thumbnail = ImageInfo(MockImageUrls.thumbnail(slug, 1280, 720), null, null),
                language = MockDataPools.randomLanguage(rng),
                isMature = false,
                tags = listOf(MockDataPools.tags[rng.nextInt(MockDataPools.tags.size)]),
                categories = listOf(CategoryInfo(id = cat.id, name = cat.name, slug = cat.slug))
            )
        )
    }

    // ------------------------------------------------------------------
    // Chat history  (web.kick.com/api/v1/chat/{id}/history)
    // ------------------------------------------------------------------

    fun buildChatHistory(chatroomId: Long, count: Int = MockConfig.HISTORY_SIZE): ChatHistoryResponse {
        val rng = Random(chatroomId)
        val messages = (0 until count).map { i ->
            val userIdx = rng.nextInt(MockDataPools.usernames.size)
            val username = MockDataPools.usernames[userIdx]
            val userId = 10_000L + userIdx
            val badgeTypes = MockDataPools.randomBadgeTypes(rng)
            val badges = badgeTypes?.map { ChatHistoryBadge(it, null, null) } ?: emptyList()
            val msAgo = (count - i) * rng.nextLong(3_000L, 15_000L)
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val createdAt = sdf.format(java.util.Date(System.currentTimeMillis() - msAgo))
            ChatHistoryMessage(
                id = "mock-hist-${chatroomId}-$i",
                chatId = chatroomId,
                userId = userId,
                content = MockDataPools.randomContentWithEmotes(rng),
                type = "message",
                metadata = null,
                createdAt = createdAt,
                sender = ChatHistorySender(
                    id = userId,
                    slug = username.lowercase(),
                    username = username,
                    identity = ChatHistoryIdentity(
                        color = MockDataPools.chatColors[rng.nextInt(MockDataPools.chatColors.size)],
                        badges = badges,
                        badgesV2 = null
                    ),
                    profilePicture = MockImageUrls.avatar(userIdx)
                )
            )
        }
        val historyData = ChatHistoryData(
            cursor = null,
            messages = messages,
            pinnedMessageWrapper = null
        )
        return ChatHistoryResponse(data = historyData, messages = null, pinnedMessageWrapper = null, cursor = null)
    }

    // ------------------------------------------------------------------
    // Top categories
    // ------------------------------------------------------------------

    fun buildTopCategories(): List<TopCategory> =
        MockDataPools.categories.mapIndexed { i, cat ->
            val rng = Random(cat.id)
            TopCategory(
                id = cat.id,
                categoryId = cat.categoryId,
                name = cat.name,
                slug = cat.slug,
                tags = listOf(MockDataPools.tags[rng.nextInt(MockDataPools.tags.size)]),
                description = null,
                viewers = rng.nextInt(500, 150_000),
                followersCount = rng.nextInt(1_000, 800_000),
                banner = CategoryBanner(src = "https://files.kick.com/images/subcategories/${cat.id}/banner_image/conversion/medium-webp", srcSet = null),
                parentCategory = ParentCategory(
                    id = cat.categoryId + 100,
                    name = if (cat.name.contains("Chat")) "IRL" else "Games",
                    slug = if (cat.name.contains("Chat")) "irl" else "games",
                    icon = null
                )
            )
        }

    // ------------------------------------------------------------------
    // Subcategories
    // ------------------------------------------------------------------

    fun buildSubcategoriesResponse(): SubcategoriesResponse =
        SubcategoriesResponse(
            currentPage = 1,
            data = buildTopCategories(),
            nextPageUrl = null,
            prevPageUrl = null
        )

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    fun buildSearchResponse(query: String): MultiSearchResponse {
        val rng = Random(query.hashCode().toLong())
        val channelHits = (0 until 5).map { i ->
            val slug = "search-result-$i-${query.take(4)}"
            val username = MockDataPools.randomUsername(rng)
            SearchHit(
                SearchDocument(
                    id = (1000L + i).toString(),
                    slug = slug,
                    username = username,
                    isLive = rng.nextBoolean(),
                    isBanned = false,
                    verified = rng.nextBoolean() && rng.nextBoolean(),
                    followersCount = rng.nextInt(100, 50_000),
                    profilePic = MockImageUrls.avatar(slug)
                )
            )
        }
        val categoryHits = (0 until 3).map { i ->
            val cat = MockDataPools.categories[rng.nextInt(MockDataPools.categories.size)]
            SearchHit(
                SearchDocument(
                    id = cat.id.toString(),
                    slug = cat.slug,
                    name = cat.name,
                    src = "https://files.kick.com/images/subcategories/${cat.id}/banner_image/conversion/medium-webp"
                )
            )
        }
        return MultiSearchResponse(
            results = listOf(
                SearchResult(
                    hits = channelHits,
                    found = channelHits.size,
                    requestParams = RequestParams("channel", 10, query)
                ),
                SearchResult(
                    hits = categoryHits,
                    found = categoryHits.size,
                    requestParams = RequestParams("subcategory_index", 5, query)
                ),
                SearchResult(
                    hits = emptyList(),
                    found = 0,
                    requestParams = RequestParams("tags", 5, query)
                )
            )
        )
    }

    // ------------------------------------------------------------------
    // Chat settings
    // ------------------------------------------------------------------

    fun buildChatSettingsResponse(channelId: Long): ChatSettingsResponse =
        ChatSettingsResponse(
            data = ChatSettingsData(
                channelId = channelId,
                allowLinks = ChatSettingsFlag(true),
                emotesOnlyMode = ChatSettingsFlag(false),
                followersOnlyMode = ChatSettingsDuration(false, 0),
                minimumAccountAge = ChatSettingsDuration(false, 0),
                rules = null,
                slowMode = ChatSettingsDuration(false, 0),
                subscribersOnlyMode = ChatSettingsFlag(false)
            ),
            message = "Success"
        )

    // ------------------------------------------------------------------
    // Viewer count
    // ------------------------------------------------------------------

    fun buildViewerCount(livestreamId: Long): List<ViewerCountItem> {
        val rng = Random(livestreamId)
        return listOf(ViewerCountItem(id = livestreamId, viewers = rng.nextInt(200, 50_000)))
    }

    // ------------------------------------------------------------------
    // User level (gamification)
    // ------------------------------------------------------------------

    fun buildUserLevelResponse(userId: Long): UserLevelResponse =
        UserLevelResponse(
            data = UserLevelData(
                badge = null,
                level = 12,
                progressXp = 340,
                totalXp = 8_200,
                userId = userId,
                xpToNextLevel = 160
            ),
            message = "Success"
        )

    // ------------------------------------------------------------------
    // Auth user (kick.com/api/v1/user)
    // ------------------------------------------------------------------

    fun buildUserResponse(): UserResponse =
        UserResponse(
            id = 99999L,
            username = "MockUser",
            email = null,
            bio = null,
            profilePic = MockImageUrls.avatar(42),
            verified = false,
            filteredCategories = null,
            is2faSetup = false,
            isOver18 = true,
            streamerChannel = null
        )

    // ------------------------------------------------------------------
    // Send message  (returns the sent message echoed back)
    // ------------------------------------------------------------------

    fun buildSentMessageJson(content: String, userId: Long, username: String): String {
        val msgId = "mock-sent-${System.currentTimeMillis()}"
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val now = sdf.format(java.util.Date())
        return """{"data":{"id":"$msgId","content":"$content","type":"message","created_at":"$now","sender":{"id":$userId,"username":"$username","identity":{"color":"#1E90FF","badges":[],"badges_v2":[]}}}}"""
    }

    // ------------------------------------------------------------------
    // Current viewers (List<ViewerCountItem>)
    // ------------------------------------------------------------------

    fun buildCurrentViewers(livestreamId: Long): String {
        val rng = Random(livestreamId)
        return """[{"id":$livestreamId,"viewers":${rng.nextInt(200, 50_000)}}]"""
    }

    // ------------------------------------------------------------------
    // GitHub releases — always "up to date"
    // ------------------------------------------------------------------

    fun buildGithubReleases(): String = "[]"

    // ------------------------------------------------------------------
    // Channel points
    // ------------------------------------------------------------------

    fun buildChannelPointsJson(): String = """{"data":{"points":1250}}"""

    // ------------------------------------------------------------------
    // Loyalty rewards (empty list — no rewards to show)
    // ------------------------------------------------------------------

    fun buildLoyaltyRewardsJson(): String = """{"data":[]}"""

    // ------------------------------------------------------------------
    // Channel links (empty)
    // ------------------------------------------------------------------

    fun buildChannelLinksJson(): String = "[]"

    // ------------------------------------------------------------------
    // Chat rules (none)
    // ------------------------------------------------------------------

    fun buildChatRulesJson(): String = """{"data":{"rules":null}}"""

    // ------------------------------------------------------------------
    // Videos / Clips (empty lists)
    // ------------------------------------------------------------------

    fun buildVideosJson(): String = "[]"

    fun buildClipsJson(): String = """{"clips":[],"nextCursor":null}"""

    // ------------------------------------------------------------------
    // Category detail (subcategory)
    // ------------------------------------------------------------------

    fun buildSubcategoryDetail(slug: String): TopCategory {
        val cat = MockDataPools.categories.find { it.slug == slug }
            ?: MockDataPools.categories.first()
        val rng = Random(cat.id)
        return TopCategory(
            id = cat.id,
            categoryId = cat.categoryId,
            name = cat.name,
            slug = cat.slug,
            tags = listOf(MockDataPools.tags[rng.nextInt(MockDataPools.tags.size)]),
            description = null,
            viewers = rng.nextInt(500, 150_000),
            followersCount = rng.nextInt(1_000, 800_000),
            banner = CategoryBanner("https://files.kick.com/images/subcategories/${cat.id}/banner_image/conversion/medium-webp", null),
            parentCategory = null
        )
    }

    // ------------------------------------------------------------------
    // Followed categories (top 5)
    // ------------------------------------------------------------------

    fun buildFollowedCategoriesJson(): String {
        val cats = buildTopCategories().take(5)
        return com.google.gson.Gson().toJson(cats)
    }

    // ------------------------------------------------------------------
    // Stream info
    // ------------------------------------------------------------------

    fun buildStreamInfoJson(slug: String): String {
        val rng = MockDataPools.rng(slug)
        val cat = MockDataPools.randomCategory(rng)
        val title = MockDataPools.randomTitle(rng).replace("\"", "'")
        return """{"stream_title":"$title","is_mature":false,"language":"${MockDataPools.randomLanguage(rng)}","category":{"id":${cat.id},"name":"${cat.name}","slug":"${cat.slug}"},"tags":[]}"""
    }
}
