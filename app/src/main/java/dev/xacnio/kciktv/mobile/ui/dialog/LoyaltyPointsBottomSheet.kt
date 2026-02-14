/**
 * File: LoyaltyPointsBottomSheet.kt
 *
 * Description: Implements the Channel Points (Loyalty) redemption interface.
 * It displays available rewards, handles point balance updates, and manages the
 * redemption flow, including custom user input for rewards that require it.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.dialog

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.databinding.BottomSheetLoyaltyBinding
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale
import dev.xacnio.kciktv.mobile.MobilePlayerActivity

// UI Model adapted for the adapter
data class LoyaltyReward(
    val id: String,
    val title: String,
    val cost: String, // Formatted cost
    val color: String,
    val description: String?,
    val isInputRequired: Boolean,
    val rawCost: Int
)

class LoyaltyPointsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLoyaltyBinding? = null
    private val binding get() = _binding!!
    private val repository = ChannelRepository()
    private var channelSlug: String? = null
    private var adapter: LoyaltyAdapter? = null
    private lateinit var prefs: AppPreferences
    private var selectedReward: LoyaltyReward? = null
    private var currentPoints: Int = 0
    private var currentPrediction: dev.xacnio.kciktv.shared.data.model.PredictionData? = null

    companion object {
        private const val ARG_SLUG = "slug"
        
        fun newInstance(slug: String): LoyaltyPointsBottomSheet {
            val fragment = LoyaltyPointsBottomSheet()
            val args = Bundle()
            args.putString(ARG_SLUG, slug)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channelSlug = arguments?.getString(ARG_SLUG)
        prefs = AppPreferences(requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetLoyaltyBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onStart() {
        super.onStart()
        // Prevent full screen expansion
        dialog?.let { dialog ->
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let {
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
                behavior.skipCollapsed = true
                behavior.isFitToContents = true
                behavior.isDraggable = true
                // Set max height to prevent full screen
                val displayMetrics = resources.displayMetrics
                val maxHeight = (displayMetrics.heightPixels * 0.65).toInt()
                it.layoutParams.height = maxHeight
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnClose.setOnClickListener {
            dismiss()
        }
        
        // Back button logic
        binding.btnBackToList.setOnClickListener {
            showListView()
        }
        
        // Redeem button logic
        binding.btnRedeemReward.setOnClickListener {
            selectedReward?.let { reward ->
                redeemReward(reward)
            }
        }
        
        // Prediction Entry Button
        binding.predictionEntryButton.setOnClickListener {
            dismiss()
            val mobileActivity = activity as? dev.xacnio.kciktv.mobile.MobilePlayerActivity
            val prediction = currentPrediction
            if (prediction != null) {
                // Show prediction bottom sheet for any state (active, locked, resolved, cancelled)
                mobileActivity?.overlayManager?.showPredictionBottomSheet(prediction)
            } else {
                // No prediction exists, open create new prediction sheet
                mobileActivity?.overlayManager?.openPredictionSheet()
            }
        }

        setupRecyclerView()
        showListView() // Ensure container is visible
        fetchRewards()
        fetchPoints()
        fetchLatestPrediction()
    }
    
    private fun showListView() {
        binding.listViewContainer.visibility = View.VISIBLE
        binding.detailViewContainer.visibility = View.GONE
        selectedReward = null
    }
    
    private fun showDetailView(item: LoyaltyReward) {
        selectedReward = item
        binding.listViewContainer.visibility = View.GONE
        binding.detailViewContainer.visibility = View.VISIBLE
        
        binding.detailTitle.text = item.title
        binding.detailCost.text = item.cost
        
        // Use description if available
        if (!item.description.isNullOrEmpty()) {
             binding.rewardInputMessage.hint = item.description
        } else {
             binding.rewardInputMessage.hint = getString(R.string.input_message_hint)
        }
        
        // Show/Hide input based on requirement
        if (item.isInputRequired) {
            binding.rewardInputMessage.visibility = View.VISIBLE
        } else {
            binding.rewardInputMessage.visibility = View.GONE
        }
        
        // Clear previous input
        binding.rewardInputMessage.text.clear()
        
        // Check if user has enough points
        val hasEnoughPoints = currentPoints >= item.rawCost
        binding.btnRedeemReward.isEnabled = hasEnoughPoints
        
        if (hasEnoughPoints) {
            binding.btnRedeemReward.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#53FC18"))
            binding.btnRedeemReward.setTextColor(android.graphics.Color.parseColor("#000000"))
            binding.insufficientPointsText.visibility = View.GONE
        } else {
            binding.btnRedeemReward.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#404040"))
            binding.btnRedeemReward.setTextColor(android.graphics.Color.parseColor("#808080"))
            val neededPoints = item.rawCost - currentPoints
            val formattedNeeded = NumberFormat.getNumberInstance(Locale("tr")).format(neededPoints)
            binding.insufficientPointsText.text = getString(R.string.points_needed_format, formattedNeeded)
            binding.insufficientPointsText.visibility = View.VISIBLE
        }
    }

    private fun setupRecyclerView() {
        adapter = LoyaltyAdapter(emptyList()) { item ->
            showDetailView(item)
        }
        binding.loyaltyItemsRecyclerView.layoutManager = GridLayoutManager(context, 3)
        binding.loyaltyItemsRecyclerView.adapter = adapter
    }
    
    private fun fetchRewards() {
        val slug = channelSlug ?: return
        
        if (_binding == null) return
        binding.rewardsLoading.visibility = View.VISIBLE
        binding.emptyRewardsText.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val result = repository.getChannelRewards(slug)
                android.util.Log.d("LoyaltySheet", "Rewards result: isSuccess=${result.isSuccess}")
                if (_binding != null) {
                    binding.rewardsLoading.visibility = View.GONE
                    binding.loyaltyItemsRecyclerView.visibility = View.VISIBLE
                    if (result.isSuccess) {
                        val rewards = result.getOrNull() ?: emptyList()
                        android.util.Log.d("LoyaltySheet", "Rewards count: ${rewards.size}")
                        
                        if (rewards.isEmpty()) {
                            binding.emptyRewardsText.visibility = View.VISIBLE
                            binding.loyaltyItemsRecyclerView.visibility = View.GONE
                        } else {
                            binding.emptyRewardsText.visibility = View.GONE
                            binding.loyaltyItemsRecyclerView.visibility = View.VISIBLE
                            
                            val uiItems = rewards.map { 
                                LoyaltyReward(
                                    id = it.id,
                                    title = it.title,
                                    cost = NumberFormat.getNumberInstance(Locale("tr")).format(it.cost),
                                    color = it.backgroundColor ?: "#202020", // Default dark gray
                                    description = it.description,
                                    isInputRequired = it.isUserInputRequired,
                                    rawCost = it.cost
                                )
                            }
                            
                            // Sort by cost ascending
                            val sortedItems = uiItems.sortedBy { it.rawCost }
                            android.util.Log.d("LoyaltySheet", "Updating adapter with ${sortedItems.size} items")
                            
                            adapter?.updateItems(sortedItems)
                        }
                    } else {
                        // API error - show empty message
                        android.util.Log.e("LoyaltySheet", "Rewards API failed")
                        binding.emptyRewardsText.visibility = View.VISIBLE
                        binding.loyaltyItemsRecyclerView.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("LoyaltySheet", "Rewards exception: ${e.message}", e)
                if (_binding != null) {
                    binding.rewardsLoading.visibility = View.GONE
                    binding.emptyRewardsText.visibility = View.VISIBLE
                    binding.loyaltyItemsRecyclerView.visibility = View.GONE
                }
                e.printStackTrace()
            }
        }
    }
    
    private fun fetchPoints() {
        val slug = channelSlug ?: return
        val token = prefs.authToken ?: return
        
        if (_binding == null) return
        binding.pointsLoading.visibility = View.VISIBLE
        binding.pointsContainer.visibility = View.INVISIBLE

        lifecycleScope.launch {
            try {
                val result = repository.getChannelPoints(slug, token)
                if (_binding != null) {
                    binding.pointsLoading.visibility = View.GONE
                    binding.pointsContainer.visibility = View.VISIBLE
                    if (result.isSuccess) {
                        val points = result.getOrNull() ?: 0
                        currentPoints = points
                        if (points >= 1_000_000) {
                            binding.currentPointsText.visibility = View.GONE
                            binding.loyaltyInfinityIcon.visibility = View.VISIBLE
                        } else {
                            binding.currentPointsText.visibility = View.VISIBLE
                            binding.loyaltyInfinityIcon.visibility = View.GONE
                            val formatted = NumberFormat.getNumberInstance(Locale("tr")).format(points)
                            binding.currentPointsText.text = formatted
                        }
                    }
                }
            } catch (e: Exception) {
                if (_binding != null) {
                    binding.pointsLoading.visibility = View.GONE
                    binding.pointsContainer.visibility = View.VISIBLE
                }
                e.printStackTrace()
            }
        }
    }
    
    private fun fetchLatestPrediction() {
        val slug = channelSlug ?: return
        lifecycleScope.launch {
            try {
                repository.getLatestPrediction(slug).onSuccess { prediction ->
                     currentPrediction = prediction
                     if (_binding != null) {
                         updatePredictionButton(prediction)
                     }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updatePredictionButton(prediction: dev.xacnio.kciktv.shared.data.model.PredictionData) {
        val state = prediction.state?.lowercase()
        
        // Hide all layouts first
        binding.defaultPredictionLayout.visibility = View.GONE
        binding.resolvedPredictionLayout.visibility = View.GONE
        binding.activePredictionLayout.visibility = View.GONE
        
        when (state) {
            "resolved" -> {
                binding.resolvedPredictionLayout.visibility = View.VISIBLE
                
                val winningId = prediction.winningOutcomeId
                val winner = prediction.outcomes?.find { it.id == winningId }
                
                binding.resolvedOutcomeTitle.text = winner?.title ?: getString(R.string.prediction_resolved_title)
                binding.resolvedStatusText.text = getString(R.string.prediction_ended_desc)
                binding.resolvedStatusIcon.setImageResource(R.drawable.ic_check)
                
                val distributed = winner?.totalVoteAmount ?: 0
                val formatted = NumberFormat.getNumberInstance(Locale("tr")).format(distributed)
                binding.resolvedPointsText.text = formatted
            }
            "cancelled" -> {
                binding.resolvedPredictionLayout.visibility = View.VISIBLE
                
                binding.resolvedOutcomeTitle.text = prediction.title ?: getString(R.string.prediction_default_title)
                binding.resolvedStatusText.text = getString(R.string.prediction_cancelled_desc)
                binding.resolvedStatusIcon.setImageResource(R.drawable.ic_refresh)
                
                val totalPoints = prediction.outcomes?.sumOf { it.totalVoteAmount ?: 0L } ?: 0L
                val formatted = NumberFormat.getNumberInstance(Locale("tr")).format(totalPoints)
                binding.resolvedPointsText.text = formatted
            }
            "active", "locked" -> {
                binding.activePredictionLayout.visibility = View.VISIBLE
                
                binding.activePredictionTitle.text = prediction.title ?: getString(R.string.prediction_default_title)
                
                if (state == "active") {
                    binding.activePredictionStatusIcon.visibility = View.GONE
                    binding.activePredictionStatus.text = getString(R.string.prediction_active)
                    binding.activePredictionStatus.setTextColor(android.graphics.Color.parseColor("#53FC18"))
                } else {
                    // Locked state - show lock icon on right
                    binding.activePredictionStatusIcon.visibility = View.VISIBLE
                    binding.activePredictionStatusIcon.setImageResource(R.drawable.ic_lock)
                    binding.activePredictionStatusIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFC107"))
                    binding.activePredictionStatus.text = getString(R.string.prediction_status_locked)
                    binding.activePredictionStatus.setTextColor(android.graphics.Color.parseColor("#FFC107"))
                }
            }
            else -> {
                // Default view
                binding.defaultPredictionLayout.visibility = View.VISIBLE
                binding.predictionButtonTitle.text = getString(R.string.prediction_menu_title)
            }
        }
    }

    private fun redeemReward(reward: LoyaltyReward) {
        val slug = channelSlug ?: return
        val token = prefs.authToken
        
        if (token.isNullOrEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.login_required), Toast.LENGTH_SHORT).show()
            return
        }
        
        if (_binding == null) return
        // Disable button to prevent double click
        binding.btnRedeemReward.isEnabled = false
        binding.btnRedeemReward.text = getString(R.string.processing)
        
        lifecycleScope.launch {
            try {
                if (_binding == null) return@launch
                
                // Get user input if visible/available
                val message = if (binding.rewardInputMessage.visibility == View.VISIBLE) {
                     binding.rewardInputMessage.text.toString().takeIf { it.isNotBlank() }
                } else {
                     null
                }
                
                // If input is required but empty, warn user
                if (reward.isInputRequired && message == null) {
                    Toast.makeText(requireContext(), getString(R.string.enter_message_required), Toast.LENGTH_SHORT).show()
                    if (_binding != null) {
                        binding.btnRedeemReward.isEnabled = true
                        binding.btnRedeemReward.text = getString(R.string.action_redeem)
                    }
                    return@launch
                }

                val result = repository.redeemReward(slug, reward.id, token, message)
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), getString(R.string.reward_redeemed, reward.title), Toast.LENGTH_SHORT).show()
                    fetchPoints() // Update points
                    dismiss() // Close sheet
                } else {
                    Toast.makeText(requireContext(), getString(R.string.reward_redeem_failed), Toast.LENGTH_SHORT).show()
                    if (_binding != null) {
                        binding.btnRedeemReward.isEnabled = true
                        binding.btnRedeemReward.text = getString(R.string.action_redeem)
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), getString(R.string.error_format, e.message), Toast.LENGTH_SHORT).show()
                if (_binding != null) {
                    binding.btnRedeemReward.isEnabled = true
                    binding.btnRedeemReward.text = getString(R.string.action_redeem)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // Inner Adapter Class
    class LoyaltyAdapter(
        private var items: List<LoyaltyReward>,
        private val onItemClick: (LoyaltyReward) -> Unit
    ) : RecyclerView.Adapter<LoyaltyAdapter.ViewHolder>() {
    
        fun updateItems(newItems: List<LoyaltyReward>) {
            items = newItems
            notifyDataSetChanged()
        }

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardView: CardView = view.findViewById(R.id.cardView)
            val costText: TextView = view.findViewById(R.id.rewardCost)
            val titleText: TextView = view.findViewById(R.id.rewardTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_loyalty_reward, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.titleText.text = item.title
            holder.costText.text = item.cost
            
            try {
                // Handle hex colors
                if (item.color.startsWith("#")) {
                    holder.cardView.setCardBackgroundColor(Color.parseColor(item.color))
                } else {
                    holder.cardView.setCardBackgroundColor(Color.parseColor("#" + item.color))
                }
            } catch (e: Exception) {
                holder.cardView.setCardBackgroundColor(Color.parseColor("#333333"))
            }
            
            holder.itemView.setOnClickListener {
                onItemClick(item)
            }
        }

        override fun getItemCount() = items.size
    }
}
