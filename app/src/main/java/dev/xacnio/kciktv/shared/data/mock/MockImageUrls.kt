package dev.xacnio.kciktv.shared.data.mock

object MockImageUrls {
    fun thumbnail(seed: Any, width: Int = 480, height: Int = 270): String =
        "https://picsum.photos/seed/th${seed}/$width/$height"

    fun avatar(seed: Any): String {
        val n = seed.toString().hashCode().let { if (it < 0) -it else it } % 70 + 1
        return "https://i.pravatar.cc/150?img=$n"
    }

    fun categoryBanner(seed: Any, width: Int = 460, height: Int = 215): String =
        "https://picsum.photos/seed/cat${seed}/$width/$height"

    fun offlineBanner(seed: Any): String =
        "https://picsum.photos/seed/ban${seed}/1200/480"
}
