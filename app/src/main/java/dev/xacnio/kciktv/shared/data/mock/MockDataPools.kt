package dev.xacnio.kciktv.shared.data.mock

import kotlin.random.Random

object MockDataPools {

    val streamTitles = listOf(
        "Ranked grind — diamond or bust",
        "Morning chill stream | First coffee ☕",
        "Speedrun attempt - WR incoming??",
        "Late night gaming session !socials",
        "New patch reacts + ranked",
        "Just vibing, come hangout",
        "Road to Platinum - Day 4",
        "Game testing + viewer games",
        "VOD review + ranked after",
        "Solo queue suffering - !donate",
        "Story mode playthrough (no spoilers!)",
        "Grinding ranked until morning",
        "Trying a new game for the first time",
        "Chill background stream while coding",
        "Championship watch party!",
        "Duo queue tonight",
        "Sub only viewer games tonight",
        "High ELO gameplay - educating chat",
        "English stream | ranked matches",
        "!commands !discord !youtube",
        "Pro scrims analysis live",
        "24 hour charity stream - day 1",
        "Community game night every Friday",
        "Low rank to high rank challenge",
        "Relaxed playthrough - easy mode no judge",
        "Pushing top 10 leaderboard today",
        "Let's go! First time playing this",
        "Banger session incoming 🔥",
        "Late night chill & ranked",
        "PvP focus, let's get these Ws"
    )

    val usernames = listOf(
        "ProGamer99", "SilverWolf_TV", "NightOwlGG", "CodeAndPlay", "CrimsonKing",
        "FlameThrower", "IcedOut_23", "BreezyVibes", "ZaraGames", "HypeManGG",
        "SoloQueueHero", "Nightfall_X", "GhostRider99", "CloudNine_TV", "DarkMatter",
        "RexGaming", "JakeStream", "MarcusPlays", "NolanGG", "CalebStream",
        "EthanPro", "LoganGamer", "LiamTV", "MasonStream", "RadiantPlayer",
        "NebulaDrifter", "PixelPusher99", "ByteKnight", "TurboChargedGG", "VortexGaming",
        "NeonNinja", "StormChaseer", "ArcticWolf_TV", "PhoenixRising", "VenomStrike",
        "GoldenEagle", "IronFist_GG", "ThunderBolt_X", "MidnightRacer", "SilentSniper",
        "BlazingFist", "CosmicRay_TV", "DiamondGrinder", "CrimsonClaw", "ObsidianKing",
        "AquaVortex", "GalacticRift", "FrostByte_GG", "LunarEclipse", "SolarFlare99"
    )

    val chatMessages = listOf(
        "LUL", "KEKW", "PogChamp", "nice!", "OMEGALUL",
        "let's goo!", "xD", "bro what was that", "GG", "EZ",
        "clip it", "insane", "W", "chat is cooked",
        "bro thinks he's pro lmao", "KEKW KEKW KEKW", "?", "no way",
        "this is actually insane", "Pog", "!discord", "first",
        "I've been watching for 3 hours straight lol",
        "stream is too good tonight", "chat sleeping?",
        "that play was clean", "actually peak gameplay",
        "when are you going live next?", "sub hype!",
        "I love this game so much", "LUL he thought he could",
        "my guy really said that 💀", "fr fr no cap",
        "literally carried", "back to back clips fr",
        "stream quality is S tier today", "PogU", "BASED", "vibing rn",
        "lmaooo", "oh he's mad now", "ICANT", "copium", "sadge",
        "W move", "that was clean asf", "bro is going off tonight",
        "this game actually slaps", "we cooking 🔥", "sheeeesh",
        "ngl kinda based", "banger stream as always",
        "yo who's in chat rn", "prayge",
        "this is going on the highlight reel", "LETSGOOO",
        "GGs only", "chat tell him he's wrong",
        "I'm literally dying rn 💀", "caught in 4k",
        "omega lul", "he's built different",
        "respectfully, what was that?", "HOLY MOLY",
        "not like this", "someone clip", "monkaS", "PauseChamp",
        "AYAYA", "FeelsGoodMan", "WAYTOODANK",
        "bro just speedran the L", "certified W moment",
        "he cooked for real", "imagine losing to this 💀",
        "NAH BRO 💀💀", "this map is cooked",
        "wait how did that hit", "literally aimbot smh",
        "chat how is this allowed", "ratio + fell off",
        "stream enjoyer ✅", "daily viewer reporting in",
        "haven't missed a stream in 2 weeks",
        "this the most fun stream I've watched all week",
        "we are so back", "the saga continues"
    )

    data class Category(val id: Long, val categoryId: Long, val name: String, val slug: String)

    val categories = listOf(
        Category(25L, 25L, "Valorant", "valorant"),
        Category(32L, 32L, "Fortnite", "fortnite"),
        Category(7L, 7L, "League of Legends", "league-of-legends"),
        Category(513L, 513L, "Just Chatting", "just-chatting"),
        Category(1469L, 1469L, "Grand Theft Auto V", "grand-theft-auto-v"),
        Category(29307L, 29307L, "Minecraft", "minecraft"),
        Category(27471L, 27471L, "Apex Legends", "apex-legends"),
        Category(512710L, 512710L, "Counter-Strike 2", "counter-strike-2"),
        Category(33214L, 33214L, "Overwatch 2", "overwatch-2"),
        Category(21779L, 21779L, "Rocket League", "rocket-league"),
        Category(493597L, 493597L, "Escape from Tarkov", "escape-from-tarkov"),
        Category(500670L, 500670L, "Elden Ring", "elden-ring"),
        Category(29595L, 29595L, "Dota 2", "dota-2"),
        Category(18122L, 18122L, "World of Warcraft", "world-of-warcraft"),
        Category(515025L, 515025L, "Lethal Company", "lethal-company"),
        Category(770L, 770L, "Slots", "slots"),
        Category(509658L, 509658L, "PUBG: Battlegrounds", "pubg-battlegrounds"),
        Category(11073L, 11073L, "Rainbow Six Siege", "rainbow-six-siege"),
        Category(493244L, 493244L, "EA Sports FC 24", "ea-sports-fc-24"),
        Category(27546L, 27546L, "Diablo IV", "diablo-iv")
    )

    val languages = listOf("en", "tr", "en", "en", "de", "fr", "es", "pt", "en", "ko")

    val chatColors = listOf(
        "#FF4500", "#1E90FF", "#FF69B4", "#00FF7F", "#FFD700",
        "#DC143C", "#00BFFF", "#FF8C00", "#7B68EE", "#32CD32",
        "#FF6347", "#40E0D0", "#DA70D6", "#87CEEB", "#FA8072"
    )

    val tags = listOf(
        "English", "Turkish", "Ranked", "Chill", "FPS", "MMORPG",
        "Speedrun", "Variety", "Strategy", "Horror", "Solo", "Educational",
        "Viewer Games", "Charity", "18+"
    )

    val chatBadgeTypes = listOf("subscriber", "vip", "og", "moderator")

    // Populated by EmotePanelManager after loading real emotes from the API
    @Volatile var availableEmotes: List<Pair<Long, String>> = emptyList()

    // Deterministic seeded random so a given slug always produces the same channel data
    fun rng(slug: String): Random {
        val seed = slug.fold(0L) { acc, c -> acc * 31 + c.code }
        return Random(seed)
    }

    fun randomUsername(rng: Random): String = usernames[rng.nextInt(usernames.size)]
    fun randomTitle(rng: Random): String = streamTitles[rng.nextInt(streamTitles.size)]
    fun randomCategory(rng: Random): Category = categories[rng.nextInt(categories.size)]
    fun randomViewerCount(rng: Random): Int = rng.nextInt(50, 95_000)
    fun randomLanguage(rng: Random): String = languages[rng.nextInt(languages.size)]
    fun randomColor(rng: Random): String = chatColors[rng.nextInt(chatColors.size)]
    fun randomChatMessage(rng: Random): String = chatMessages[rng.nextInt(chatMessages.size)]

    // Returns 1-3 unique badge type strings, or null (25 % chance of having badges at all)
    fun randomBadgeTypes(rng: Random): List<String>? {
        if (rng.nextInt(4) != 0) return null
        val count = rng.nextInt(1, 4)
        return chatBadgeTypes.shuffled(rng).take(count)
    }

    // Chat message string, optionally containing [emote:id:name] tags if emotes are loaded
    fun randomContentWithEmotes(rng: Random): String {
        val text = chatMessages[rng.nextInt(chatMessages.size)]
        val emotes = availableEmotes
        if (emotes.isEmpty() || rng.nextInt(3) != 0) return text
        val e1 = emotes[rng.nextInt(emotes.size)]
        val tag1 = "[emote:${e1.first}:${e1.second}]"
        return when (rng.nextInt(4)) {
            0 -> tag1
            1 -> "$text $tag1"
            2 -> "$tag1 $text"
            else -> {
                val e2 = emotes[rng.nextInt(emotes.size)]
                "$tag1 [emote:${e2.first}:${e2.second}]"
            }
        }
    }
}
