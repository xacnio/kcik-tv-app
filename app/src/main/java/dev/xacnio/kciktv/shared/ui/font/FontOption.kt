package dev.xacnio.kciktv.shared.ui.font

data class FontOption(
    val id: String,
    val displayName: String,
    val family: String,
    val isSystem: Boolean,
    val category: String = ""
)
