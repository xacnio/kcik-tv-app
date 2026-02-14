/**
 * File: CreatePollBottomSheet.kt
 *
 * Description: Bottom Sheet implementation for displaying Create Poll Bottom content.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.shared.ui.dialog

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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

class CreatePollBottomSheet : BottomSheetDialogFragment() {

    private val repository = ChannelRepository()
    private var channelSlug: String? = null
    private lateinit var prefs: AppPreferences

    // Duration options in seconds
    private val durationOptions by lazy {
        listOf(
            DurationOption(getString(R.string.x_seconds, 10), 10),
            DurationOption(getString(R.string.x_seconds, 30), 30),
            DurationOption(getString(R.string.x_minutes, 1), 60),
            DurationOption(getString(R.string.x_minutes, 2), 120),
            DurationOption(getString(R.string.x_minutes, 3), 180),
            DurationOption(getString(R.string.x_minutes, 4), 240),
            DurationOption(getString(R.string.x_minutes, 5), 300)
        )
    }

    // Result display duration options in seconds
    private val resultDurationOptions by lazy {
        listOf(
            DurationOption(getString(R.string.x_seconds, 15), 15),
            DurationOption(getString(R.string.x_seconds, 30), 30),
            DurationOption(getString(R.string.x_minutes, 1), 60),
            DurationOption(getString(R.string.x_minutes, 2), 120),
            DurationOption(getString(R.string.x_minutes, 3), 180),
            DurationOption(getString(R.string.x_minutes, 4), 240),
            DurationOption(getString(R.string.x_minutes, 5), 300)
        )
    }

    data class DurationOption(val label: String, val seconds: Int) {
        override fun toString(): String = label
    }

    companion object {
        private const val ARG_SLUG = "slug"

        fun newInstance(slug: String): CreatePollBottomSheet {
            val fragment = CreatePollBottomSheet()
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
        return inflater.inflate(R.layout.dialog_create_poll, container, false)
    }

    override fun onStart() {
        super.onStart()
        
        val dialog = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog
        dialog?.let { d ->
            val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            bottomSheet?.let { sheet ->
                val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet)
                
                // Keep wrap_content but expand immediately
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
            
            // Allow keyboard to push up the content
            @Suppress("DEPRECATION")
            d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnClose = view.findViewById<ImageButton>(R.id.btnClose)
        val pollTitleInput = view.findViewById<EditText>(R.id.pollTitleInput)
        val option1Input = view.findViewById<EditText>(R.id.option1Input)
        val option2Input = view.findViewById<EditText>(R.id.option2Input)
        val option3Input = view.findViewById<EditText>(R.id.option3Input)
        val option4Input = view.findViewById<EditText>(R.id.option4Input)
        val option5Input = view.findViewById<EditText>(R.id.option5Input)
        val option6Input = view.findViewById<EditText>(R.id.option6Input)
        val durationSpinner = view.findViewById<Spinner>(R.id.durationSpinner)
        val resultDurationSpinner = view.findViewById<Spinner>(R.id.resultDurationSpinner)
        val btnCreatePoll = view.findViewById<Button>(R.id.btnCreatePoll)
        val errorText = view.findViewById<TextView>(R.id.errorText)

        // Setup Duration Spinner
        val durationAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            durationOptions
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        durationSpinner.adapter = durationAdapter
        durationSpinner.setSelection(1) // Default: 30 saniye

        // Setup Result Duration Spinner
        val resultDurationAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            resultDurationOptions
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        resultDurationSpinner.adapter = resultDurationAdapter
        resultDurationSpinner.setSelection(0) // Default: 15 saniye

        // Dynamic option visibility
        val optionInputs = listOf(option1Input, option2Input, option3Input, option4Input, option5Input, option6Input)
        
        fun updateOptionVisibility() {
            // Option 3 appears when option 2 is filled
            option3Input.visibility = if (option2Input.text.isNotEmpty()) View.VISIBLE else View.GONE
            
            // Option 4 appears when option 3 is filled
            option4Input.visibility = if (option3Input.text.isNotEmpty() && option3Input.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            
            // Option 5 appears when option 4 is filled
            option5Input.visibility = if (option4Input.text.isNotEmpty() && option4Input.visibility == View.VISIBLE) View.VISIBLE else View.GONE
            
            // Option 6 appears when option 5 is filled
            option6Input.visibility = if (option5Input.text.isNotEmpty() && option5Input.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        }

        // Add text watchers to show next option when current is filled
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateOptionVisibility()
            }
        }

        option2Input.addTextChangedListener(textWatcher)
        option3Input.addTextChangedListener(textWatcher)
        option4Input.addTextChangedListener(textWatcher)
        option5Input.addTextChangedListener(textWatcher)

        btnClose.setOnClickListener {
            dismiss()
        }

        btnCreatePoll.setOnClickListener {
            val title = pollTitleInput.text.toString().trim()
            val options = optionInputs
                .filter { it.visibility == View.VISIBLE }
                .map { it.text.toString().trim() }
                .filter { it.isNotEmpty() }

            val selectedDuration = durationSpinner.selectedItem as DurationOption
            val selectedResultDuration = resultDurationSpinner.selectedItem as DurationOption

            // Validation
            errorText.visibility = View.GONE

            if (title.isEmpty()) {
                errorText.text = getString(R.string.poll_question_required)
                errorText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (options.size < 2) {
                errorText.text = getString(R.string.poll_min_options_required)
                errorText.visibility = View.VISIBLE
                return@setOnClickListener
            }

            createPoll(
                title = title,
                options = options,
                duration = selectedDuration.seconds,
                resultDisplayDuration = selectedResultDuration.seconds,
                button = btnCreatePoll,
                errorText = errorText
            )
        }
    }

    private fun createPoll(
        title: String,
        options: List<String>,
        duration: Int,
        resultDisplayDuration: Int,
        button: Button,
        errorText: TextView
    ) {
        val slug = channelSlug ?: return
        val token = prefs.authToken

        if (token.isNullOrEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.login_required), Toast.LENGTH_SHORT).show()
            return
        }

        button.isEnabled = false
        button.text = getString(R.string.creating)

        lifecycleScope.launch {
            try {
                val result = repository.createPoll(
                    channelSlug = slug,
                    token = token,
                    title = title,
                    options = options,
                    duration = duration,
                    resultDisplayDuration = resultDisplayDuration
                )

                if (result.isSuccess) {
                    Toast.makeText(requireContext(), getString(R.string.poll_created), Toast.LENGTH_SHORT).show()
                    dismiss()
                } else {
                    val errorMessage = result.exceptionOrNull()?.message ?: getString(R.string.poll_create_failed, "")
                    errorText.text = errorMessage
                    errorText.visibility = View.VISIBLE
                    button.isEnabled = true
                    button.text = getString(R.string.create_poll)
                }
            } catch (e: Exception) {
                errorText.text = getString(R.string.error_format, e.message)
                errorText.visibility = View.VISIBLE
                button.isEnabled = true
                button.text = getString(R.string.create_poll)
            }
        }
    }
}
