package com.gta6subdub.app

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gta6subdub.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsStore(this)
        binding.etGeminiKey.setText(prefs.geminiApiKey)
        binding.etGeminiModel.setText(prefs.geminiModel)
        binding.etElevenKey.setText(prefs.elevenLabsApiKey)
        binding.etVoiceId.setText(prefs.elevenLabsVoiceId)
        binding.switchAutoDub.isChecked = prefs.autoDubEnabled

        binding.btnSaveSettings.setOnClickListener {
            saveAndClose()
        }

        binding.btnDoneSettings.setOnClickListener {
            saveAndClose()
        }
    }

    private fun saveAndClose() {
        prefs.geminiApiKey = binding.etGeminiKey.text?.toString().orEmpty()
        prefs.geminiModel = binding.etGeminiModel.text?.toString().orEmpty()
        prefs.elevenLabsApiKey = binding.etElevenKey.text?.toString().orEmpty()
        prefs.elevenLabsVoiceId = binding.etVoiceId.text?.toString().orEmpty()
        prefs.autoDubEnabled = binding.switchAutoDub.isChecked
        SubtitleStateBus.notifyStyleChanged()
        Toast.makeText(this, "บันทึกการตั้งค่าแล้ว ✓", Toast.LENGTH_SHORT).show()
        finish()
    }
}
