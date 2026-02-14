/**
 * File: CreatePredictionBottomSheet.kt
 *
 * Description: Bottom Sheet implementation for displaying Create Prediction Bottom content.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.dialog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences
import dev.xacnio.kciktv.shared.data.repository.ChannelRepository
import kotlinx.coroutines.launch

class CreatePredictionBottomSheet : BottomSheetDialogFragment() {

    private val repository = ChannelRepository()
    private var channelSlug: String? = null
    private lateinit var prefs: AppPreferences

    private val durationOptions by lazy {
        listOf(
            DurationOption(getString(R.string.x_seconds, 30), 30),
            DurationOption(getString(R.string.x_minutes, 1), 60),
            DurationOption(getString(R.string.x_minutes, 2), 120),
            DurationOption(getString(R.string.x_minutes, 3), 180),
            DurationOption(getString(R.string.x_minutes, 4), 240),
            DurationOption(getString(R.string.x_minutes, 5), 300),
            DurationOption(getString(R.string.x_minutes, 10), 600)
        )
    }

    data class DurationOption(val label: String, val seconds: Int) {
        override fun toString(): String = label
    }

    companion object {
        private const val ARG_SLUG = "slug"

        fun newInstance(slug: String): CreatePredictionBottomSheet {
            val fragment = CreatePredictionBottomSheet()
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
    ): View? {
        return inflater.inflate(R.layout.dialog_create_prediction, container, false)
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
        dialog?.let { d ->
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                behavior.isDraggable = false
                
                // Make the sheet fit properly with keyboard
                sheet.fitsSystemWindows = true
            }
            // Use ADJUST_PAN to push the entire dialog above the keyboard
            d.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN or 
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnClose = view.findViewById<ImageButton>(R.id.btnClose)
        val titleInput = view.findViewById<EditText>(R.id.predictionTitleInput)
        val outcome1Input = view.findViewById<EditText>(R.id.outcome1Input)
        val outcome2Input = view.findViewById<EditText>(R.id.outcome2Input)
        val durationSpinner = view.findViewById<Spinner>(R.id.durationSpinner)
        val btnCreate = view.findViewById<Button>(R.id.btnCreatePrediction)
        val errorText = view.findViewById<TextView>(R.id.errorText)

        val durationAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, durationOptions)
        durationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        durationSpinner.adapter = durationAdapter
        durationSpinner.setSelection(2) // Default: 2 dakika

        btnClose.setOnClickListener { dismiss() }

        btnCreate.setOnClickListener {
            val title = titleInput.text.toString().trim()
            val o1 = outcome1Input.text.toString().trim()
            val o2 = outcome2Input.text.toString().trim()
            val duration = (durationSpinner.selectedItem as DurationOption).seconds

            if (title.isEmpty() || o1.isEmpty() || o2.isEmpty()) {
                errorText.text = getString(R.string.fill_all_fields)
                errorText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            btnCreate.isEnabled = false
            btnCreate.text = getString(R.string.creating)
            
            lifecycleScope.launch {
                val result = repository.createPrediction(channelSlug!!, prefs.authToken!!, title, listOf(o1, o2), duration)
                if (result.isSuccess) {
                    Toast.makeText(requireContext(), getString(R.string.prediction_created), Toast.LENGTH_SHORT).show()
                    dismiss()
                } else {
                    errorText.text = result.exceptionOrNull()?.message ?: getString(R.string.error_occurred)
                    errorText.visibility = View.VISIBLE
                    btnCreate.isEnabled = true
                    btnCreate.text = getString(R.string.start_prediction)
                }
            }
        }
    }
}
