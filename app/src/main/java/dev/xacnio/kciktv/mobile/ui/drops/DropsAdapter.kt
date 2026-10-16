/**
 * File: DropsAdapter.kt
 *
 * Description: RecyclerView adapter for the Drops page — renders the intro blurb and one card
 * per campaign with its category, rewards and channels.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.drops

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.model.DropCampaign
import dev.xacnio.kciktv.shared.util.CategoryUtils
import dev.xacnio.kciktv.shared.util.TimeUtils
import java.text.DateFormat
import java.util.Date

class DropsAdapter(
    private val onJoinClick: (DropCampaign) -> Unit,
    private val onConnectClick: (DropCampaign) -> Unit,
    private val onDetailsClick: (DropCampaign) -> Unit,
    private val onClaimClick: (campaign: DropCampaign, rewardId: String) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val TYPE_INTRO = 0
        const val TYPE_CAMPAIGN = 1

        // Reward image_url is relative to this CDN
        private const val REWARD_IMAGE_BASE = "https://ext.cdn.kick.com/"
        private const val MAX_CHANNEL_AVATARS = 5
        private const val RULE_WATCH_TO_REDEEM = 1

        fun rewardImageUrl(path: String?): String? = when {
            path.isNullOrBlank() -> null
            path.startsWith("http") -> path
            else -> "$REWARD_IMAGE_BASE${path.trimStart('/')}?width=176,format=webp,quality=75"
        }
    }

    sealed class Item {
        object Intro : Item()
        /** [progress] is the signed-in user's progress, null when signed out or not started. */
        data class Campaign(
            val campaign: DropCampaign,
            val progress: dev.xacnio.kciktv.shared.data.model.DropCampaignProgress? = null
        ) : Item()
    }

    private val items = mutableListOf<Item>()

    fun submit(newItems: List<Item>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.Intro -> TYPE_INTRO
        is Item.Campaign -> TYPE_CAMPAIGN
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_INTRO -> object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_drop_intro, parent, false)) {}
            else -> CampaignViewHolder(inflater.inflate(R.layout.item_drop_campaign, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Intro -> Unit
            is Item.Campaign -> (holder as CampaignViewHolder).bind(item.campaign, item.progress)
        }
    }

    inner class CampaignViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val categoryImage: ImageView = itemView.findViewById(R.id.dropCategoryImage)
        private val name: TextView = itemView.findViewById(R.id.dropCampaignName)
        private val categoryName: TextView = itemView.findViewById(R.id.dropCategoryName)
        private val orgRow: View = itemView.findViewById(R.id.dropOrganizationRow)
        private val orgLogo: ImageView = itemView.findViewById(R.id.dropOrganizationLogo)
        private val actions: View = itemView.findViewById(R.id.dropActions)
        private val watchButton: View = itemView.findViewById(R.id.dropWatchButton)
        private val connectButton: View = itemView.findViewById(R.id.dropConnectButton)
        private val detailsButton: View = itemView.findViewById(R.id.dropDetailsButton)
        private val rule: TextView = itemView.findViewById(R.id.dropRule)
        private val orgName: TextView = itemView.findViewById(R.id.dropOrganizationName)
        private val time: TextView = itemView.findViewById(R.id.dropCampaignTime)
        private val rewardsContainer: LinearLayout = itemView.findViewById(R.id.dropRewardsContainer)
        private val channelAvatars: LinearLayout = itemView.findViewById(R.id.dropChannelsAvatars)
        private val channelsText: TextView = itemView.findViewById(R.id.dropChannelsText)

        fun bind(campaign: DropCampaign, progress: dev.xacnio.kciktv.shared.data.model.DropCampaignProgress?) {
            val context = itemView.context
            val density = context.resources.displayMetrics.density

            name.text = campaign.name.orEmpty()

            val category = campaign.category
            val categoryLabel = CategoryUtils.getLocalizedCategoryName(context, category?.name, category?.slug)
            categoryName.text = categoryLabel
            categoryName.visibility = if (categoryLabel.isBlank()) View.GONE else View.VISIBLE

            val categoryUrl = campaign.category?.imageUrl
            categoryImage.visibility = if (categoryUrl.isNullOrBlank()) View.GONE else View.VISIBLE
            if (!categoryUrl.isNullOrBlank()) {
                Glide.with(context)
                    .load(categoryUrl)
                    .transform(CenterCrop(), RoundedCorners((6 * density).toInt()))
                    .into(categoryImage)
            }

            // Hide the organizer row for Kick's own campaigns
            val organization = campaign.organization?.name
            val showOrg = !organization.isNullOrBlank() && !organization.equals("KICK", ignoreCase = true)
            orgRow.visibility = if (showOrg) View.VISIBLE else View.GONE
            if (showOrg) {
                orgName.text = organization
                val logoUrl = campaign.organization?.logoUrl
                orgLogo.visibility = if (logoUrl.isNullOrBlank()) View.GONE else View.VISIBLE
                if (!logoUrl.isNullOrBlank()) {
                    Glide.with(context).load(logoUrl).circleCrop().into(orgLogo)
                }
            }

            // Ended campaigns have nothing left to watch or link
            val isActive = campaign.status != "expired"
            val canWatch = isActive && !campaign.category?.slug.isNullOrBlank()
            val needsConnect = isActive && !campaign.connectUrl.isNullOrBlank()
            val hasDetails = !campaign.url.isNullOrBlank()
            watchButton.visibility = if (canWatch) View.VISIBLE else View.GONE
            connectButton.visibility = if (needsConnect) View.VISIBLE else View.GONE
            detailsButton.visibility = if (hasDetails) View.VISIBLE else View.GONE
            actions.visibility = if (canWatch || needsConnect || hasDetails) View.VISIBLE else View.GONE
            watchButton.setOnClickListener { onJoinClick(campaign) }
            connectButton.setOnClickListener { onConnectClick(campaign) }
            detailsButton.setOnClickListener { onDetailsClick(campaign) }

            bindRule(campaign)
            bindTime(campaign)
            bindRewards(campaign, progress)
            bindChannels(campaign)
        }

        /** Translated label for known rules, API name otherwise. */
        private fun bindRule(campaign: DropCampaign) {
            val context = itemView.context
            val label = when (campaign.rule?.id) {
                RULE_WATCH_TO_REDEEM -> context.getString(R.string.drops_rule_watch)
                else -> campaign.rule?.name
            }
            if (label.isNullOrBlank()) {
                rule.visibility = View.GONE
                return
            }
            rule.text = label
            rule.visibility = View.VISIBLE
            if (rule.compoundDrawablesRelative[0] == null) {
                val size = (12 * context.resources.displayMetrics.density).toInt()
                ContextCompat.getDrawable(context, R.drawable.ic_eye)?.mutate()?.let { icon ->
                    icon.setBounds(0, 0, size, size)
                    icon.setTint(0xFFDDDDDD.toInt())
                    rule.setCompoundDrawablesRelative(icon, null, null, null)
                }
            }
        }

        private fun bindTime(campaign: DropCampaign) {
            val context = itemView.context
            val now = System.currentTimeMillis()
            val startsAt = TimeUtils.parseIso8601ToMillis(campaign.startsAt)
            val endsAt = TimeUtils.parseIso8601ToMillis(campaign.endsAt)

            val (text, active) = when {
                campaign.status == "expired" || (endsAt != null && endsAt <= now) -> {
                    val date = endsAt?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }
                    (date?.let { context.getString(R.string.drops_ended_on, it) } ?: "") to false
                }
                startsAt != null && startsAt > now ->
                    context.getString(R.string.drops_starts_in, remaining(startsAt - now)) to true
                endsAt != null ->
                    context.getString(R.string.drops_ends_in, remaining(endsAt - now)) to true
                else -> "" to true
            }
            time.text = text
            time.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
            time.setTextColor(
                if (active) ContextCompat.getColor(context, R.color.brand_green) else 0xFF888888.toInt()
            )
        }

        /** Days + hours when more than a day is left, otherwise hours + minutes. */
        private fun remaining(ms: Long): String {
            val totalMinutes = (ms / 60_000L).toInt().coerceAtLeast(1)
            val rounded = if (totalMinutes >= 1440) totalMinutes - totalMinutes % 60 else totalMinutes
            return TimeUtils.formatDuration(itemView.context, rounded)
        }

        private fun bindRewards(campaign: DropCampaign, progress: dev.xacnio.kciktv.shared.data.model.DropCampaignProgress?) {
            val context = itemView.context
            val inflater = LayoutInflater.from(context)
            val rewardProgress = progress?.rewards.orEmpty().associateBy { it.id }
            rewardsContainer.removeAllViews()

            campaign.rewards.orEmpty()
                .sortedBy { it.requiredUnits ?: 0 }
                .forEach { reward ->
                    val view = inflater.inflate(R.layout.item_drop_reward, rewardsContainer, false)
                    view.findViewById<TextView>(R.id.dropRewardName).text = reward.name.orEmpty()

                    val required = reward.requiredUnits ?: 0
                    val status = view.findViewById<TextView>(R.id.dropRewardRequirement)
                    val bar = view.findViewById<android.widget.ProgressBar>(R.id.dropRewardProgress)
                    val claimButton = view.findViewById<TextView>(R.id.dropRewardClaimButton)
                    val state = rewardProgress[reward.id]
                    if (state == null) {
                        // Signed out or not started: required watch time
                        status.text = TimeUtils.formatDuration(context, required)
                        bar.visibility = View.GONE
                    } else {
                        val fraction = (state.progress ?: 0f).coerceIn(0f, 1f)
                        bar.visibility = View.VISIBLE
                        bar.progress = (fraction * 100).toInt()
                        when {
                            state.claimed == true -> {
                                status.text = context.getString(R.string.drops_reward_claimed)
                            }
                            fraction >= 1f -> {
                                // Claimable even after the campaign ended
                                status.visibility = View.GONE
                                claimButton.visibility = View.VISIBLE
                                claimButton.setOnClickListener {
                                    claimButton.isEnabled = false
                                    claimButton.alpha = 0.5f
                                    onClaimClick(campaign, reward.id)
                                }
                            }
                            else -> {
                                val left = kotlin.math.ceil(required * (1f - fraction)).toInt().coerceAtLeast(1)
                                status.text = context.getString(
                                    R.string.drops_reward_remaining, TimeUtils.formatDuration(context, left)
                                )
                            }
                        }
                    }
                    Glide.with(context)
                        .load(rewardImageUrl(reward.imageUrl))
                        .into(view.findViewById(R.id.dropRewardImage))
                    rewardsContainer.addView(view)
                }
        }

        private fun bindChannels(campaign: DropCampaign) {
            val context = itemView.context
            val density = context.resources.displayMetrics.density
            val channels = campaign.channels.orEmpty()
            channelAvatars.removeAllViews()
            (channelsText.layoutParams as LinearLayout.LayoutParams).marginStart =
                if (channels.isEmpty()) 0 else (8 * density).toInt()

            if (channels.isEmpty()) {
                // No channel list means every stream in the category counts.
                channelAvatars.visibility = View.GONE
                val category = campaign.category?.name
                channelsText.text = if (category.isNullOrBlank()) {
                    context.getString(R.string.drops_any_channel_generic)
                } else {
                    context.getString(R.string.drops_any_channel, category)
                }
                return
            }

            channelAvatars.visibility = View.VISIBLE
            val size = (22 * density).toInt()
            val overlap = (6 * density).toInt()
            channels.take(MAX_CHANNEL_AVATARS).forEachIndexed { index, channel ->
                val avatar = ImageView(context)
                avatar.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    if (index > 0) marginStart = -overlap
                }
                Glide.with(context)
                    .load(channel.user?.profilePicture)
                    .placeholder(R.drawable.default_avatar)
                    .circleCrop()
                    .into(avatar)
                channelAvatars.addView(avatar)
            }

            val names = channels.take(2).mapNotNull { it.user?.username ?: it.slug }.joinToString(", ")
            val extra = channels.size - 2
            channelsText.text = if (extra > 0) "$names +$extra" else names
        }
    }
}
