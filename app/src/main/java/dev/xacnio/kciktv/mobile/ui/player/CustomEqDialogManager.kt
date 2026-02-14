/**
 * File: CustomEqDialogManager.kt
 *
 * Description: Manages the Custom Equalizer (EQ) dialog interface.
 * It provides a 6-band equalizer with adjustable sliders and various audio presets
 * (e.g., Bass Boost, Vocal, Rock) to customize the audio output of the video player.
 *
 * Author: Xacnio
 *
 */
package dev.xacnio.kciktv.mobile.ui.player

import android.view.View
import com.google.android.material.bottomsheet.BottomSheetDialog
import dev.xacnio.kciktv.mobile.MobilePlayerActivity
import dev.xacnio.kciktv.R
import dev.xacnio.kciktv.shared.data.prefs.AppPreferences

/**
 * Manages the custom equalizer dialog UI.
 */
class CustomEqDialogManager(
    private val activity: MobilePlayerActivity,
    private val prefs: AppPreferences
) {

    /*
        Audio EQ Settings is default?
    */
    fun allEqSettingsFlat(): Boolean {
        return prefs.eqPreAmpGain == 0f &&
                prefs.eqBassGain == 0f &&
                prefs.eqLowMidGain == 0f &&
                prefs.eqMidGain == 0f &&
                prefs.eqHighMidGain == 0f &&
                prefs.eqTrebleGain == 0f
    }

    /**
     * Shows the custom EQ bottom sheet dialog with 6-band equalizer.
     */
    fun showCustomEqDialog() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) {
            return
        }
        val dialog = BottomSheetDialog(activity, R.style.BottomSheetDialogTheme)
        val view = activity.layoutInflater.inflate(R.layout.dialog_custom_eq, null)
        dialog.setContentView(view)

        // Helper to map SeekBar (0-60) to Gain (-30 to 30)
        fun toGain(progress: Int): Float = (progress - 30).toFloat()
        fun toProgress(gain: Float): Int = (gain + 30).toInt().coerceIn(0, 60)
        fun formatDb(gain: Float): String = activity.getString(R.string.eq_db_format, if(gain > 0) "+" else "", gain.toInt())

        // Initialize SeekBars and Texts
        val seekPreAmp = view.findViewById<android.widget.SeekBar>(R.id.seekPreAmp)
        val valPreAmp = view.findViewById<android.widget.TextView>(R.id.valPreAmp)
        
        val seekBass = view.findViewById<android.widget.SeekBar>(R.id.seekBass)
        val valBass = view.findViewById<android.widget.TextView>(R.id.valBass)
        
        val seekLowMid = view.findViewById<android.widget.SeekBar>(R.id.seekLowMid)
        val valLowMid = view.findViewById<android.widget.TextView>(R.id.valLowMid)
        
        val seekMid = view.findViewById<android.widget.SeekBar>(R.id.seekMid)
        val valMid = view.findViewById<android.widget.TextView>(R.id.valMid)
        
        val seekHighMid = view.findViewById<android.widget.SeekBar>(R.id.seekHighMid)
        val valHighMid = view.findViewById<android.widget.TextView>(R.id.valHighMid)
        
        val seekTreble = view.findViewById<android.widget.SeekBar>(R.id.seekTreble)
        val valTreble = view.findViewById<android.widget.TextView>(R.id.valTreble)

        // Set initial values
        seekPreAmp.progress = toProgress(prefs.eqPreAmpGain)
        valPreAmp.text = formatDb(prefs.eqPreAmpGain)

        seekBass.progress = toProgress(prefs.eqBassGain)
        valBass.text = formatDb(prefs.eqBassGain)

        seekLowMid.progress = toProgress(prefs.eqLowMidGain)
        valLowMid.text = formatDb(prefs.eqLowMidGain)

        seekMid.progress = toProgress(prefs.eqMidGain)
        valMid.text = formatDb(prefs.eqMidGain)

        seekHighMid.progress = toProgress(prefs.eqHighMidGain)
        valHighMid.text = formatDb(prefs.eqHighMidGain)

        seekTreble.progress = toProgress(prefs.eqTrebleGain)
        valTreble.text = formatDb(prefs.eqTrebleGain)

        // Listeners
        val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val gain = toGain(progress)
                val text = formatDb(gain)

                when (seekBar.id) {
                    R.id.seekPreAmp -> {
                        valPreAmp.text = text
                        prefs.eqPreAmpGain = gain
                    }
                    R.id.seekBass -> {
                        valBass.text = text
                        prefs.eqBassGain = gain
                    }
                    R.id.seekLowMid -> {
                        valLowMid.text = text
                        prefs.eqLowMidGain = gain
                    }
                    R.id.seekMid -> {
                        valMid.text = text
                        prefs.eqMidGain = gain
                    }
                    R.id.seekHighMid -> {
                        valHighMid.text = text
                        prefs.eqHighMidGain = gain
                    }
                    R.id.seekTreble -> {
                        valTreble.text = text
                        prefs.eqTrebleGain = gain
                    }
                }
                
                // Update audio live if in Custom EQ mode
                if (!allEqSettingsFlat()) {
                    activity.playerManager.updateAudioProcessing()
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
        }

        seekPreAmp.setOnSeekBarChangeListener(listener)
        seekBass.setOnSeekBarChangeListener(listener)
        seekLowMid.setOnSeekBarChangeListener(listener)
        seekMid.setOnSeekBarChangeListener(listener)
        seekHighMid.setOnSeekBarChangeListener(listener)
        seekTreble.setOnSeekBarChangeListener(listener)

        // Presets logic helper
        fun applyPreset(preAmp: Int, bass: Int, lowMid: Int, mid: Int, highMid: Int, treble: Int) {
            seekPreAmp.setProgress(preAmp, true)
            seekBass.setProgress(bass, true)
            seekLowMid.setProgress(lowMid, true)
            seekMid.setProgress(mid, true)
            seekHighMid.setProgress(highMid, true)
            seekTreble.setProgress(treble, true)

            // Save to prefs manually since fromUser is false in listener
            prefs.eqPreAmpGain = toGain(preAmp)
            prefs.eqBassGain = toGain(bass)
            prefs.eqLowMidGain = toGain(lowMid)
            prefs.eqMidGain = toGain(mid)
            prefs.eqHighMidGain = toGain(highMid)
            prefs.eqTrebleGain = toGain(treble)
            
            // Initial update of text values (listener handles animation updates but just in case)
            valPreAmp.text = formatDb(toGain(preAmp))
            valBass.text = formatDb(toGain(bass))
            valLowMid.text = formatDb(toGain(lowMid))
            valMid.text = formatDb(toGain(mid))
            valHighMid.text = formatDb(toGain(highMid))
            valTreble.text = formatDb(toGain(treble))

            activity.playerManager.updateAudioProcessing()
        }

        // Presets
        view.findViewById<View>(R.id.btnPresetFlat).setOnClickListener {
            view.findViewById<View>(R.id.btnResetEq).performClick()
        }

        view.findViewById<View>(R.id.btnPresetBass).setOnClickListener {
            applyPreset(30, 46, 36, 30, 30, 34)  // Heavy bass boost
        }
        
        view.findViewById<View>(R.id.btnPresetVocal).setOnClickListener {
            applyPreset(30, 24, 32, 40, 44, 36)  // Vocal clarity
        }

        view.findViewById<View>(R.id.btnPresetPop).setOnClickListener {
            applyPreset(30, 38, 34, 30, 34, 38)  // Pop music
        }

        view.findViewById<View>(R.id.btnPresetRock).setOnClickListener {
            applyPreset(30, 40, 36, 28, 36, 40)  // Rock music
        }

        view.findViewById<View>(R.id.btnPresetJazz).setOnClickListener {
            applyPreset(30, 36, 34, 26, 34, 36)  // Jazz music
        }

        view.findViewById<View>(R.id.btnPresetClassical).setOnClickListener {
            applyPreset(30, 38, 36, 34, 36, 38)  // Classical music
        }

        view.findViewById<View>(R.id.btnPresetMetal).setOnClickListener {
            applyPreset(30, 42, 30, 24, 30, 42)  // Metal music
        }

        // NOTE: 8D Surround and Reverb features removed - they don't work with IVS Player
        // IVS Player doesn't expose audioSessionId, so only EQ (DynamicsProcessing) works

        // Buttons
        view.findViewById<View>(R.id.btnResetEq).setOnClickListener {
            applyPreset(30, 30, 30, 30, 30, 30)  // All bands at 0 dB
        }

        view.findViewById<View>(R.id.btnBackEq).setOnClickListener {
            dialog.dismiss()
            activity.videoSettingsDialogManager.showVideoSettingsDialog()
        }

        dialog.show()
    }
}
