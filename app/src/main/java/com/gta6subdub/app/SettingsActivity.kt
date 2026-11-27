package com.gta6subdub.app

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gta6subdub.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: PrefsStore
    private lateinit var voiceRouter: VoiceRouter

    private val geminiModels = listOf(
        "gemini-3.8-flash",
        "gemini-3.1-flash-lite",
        "gemini-3.8-pro",
        "custom"
    )
    private val geminiModelLabels = listOf(
        "gemini-3.8-flash (ดีที่สุด + เร็ว — แนะนำ)",
        "gemini-3.1-flash-lite (เร็วสุด + ใช้ฟรีได้ดี)",
        "gemini-3.8-pro (แปลสแลงแม่นสุด — ช้ากว่าเล็กน้อย)",
        "กำหนดเอง (พิมพ์ชื่อโมเดลเอง)"
    )

    private val geminiVoices = listOf("Fenrir", "Charon", "Kore", "Puck", "Aoede")
    private val geminiVoiceLabels = listOf(
        "Fenrir (ทุ้ม ดุดัน สตรีท — แนะนำ GTA)",
        "Charon (เสียงชายนิ่ง เข้ม)",
        "Kore (ชัด กระชับ)",
        "Puck (เร็ว กวนๆ)",
        "Aoede (เสียงผู้หญิง — ลูเซีย)"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsStore(this)
        voiceRouter = VoiceRouter(this)

        binding.etGeminiKey.setText(prefs.geminiApiKey)
        binding.etElevenKey.setText(prefs.elevenLabsApiKey)
        binding.etVoiceId.setText(prefs.elevenLabsVoiceId)
        binding.switchAutoDub.isChecked = prefs.autoDubEnabled

        // White-text dark-theme Model Dropdown
        val modelAdapter = ArrayAdapter(this, R.layout.spinner_item_white, geminiModelLabels).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_white)
        }
        binding.spinnerGeminiModel.adapter = modelAdapter
        val savedModel = prefs.geminiModel
        val modelIdx = geminiModels.indexOf(savedModel)
        if (modelIdx >= 0) {
            binding.spinnerGeminiModel.setSelection(modelIdx)
            binding.tilCustomModel.visibility = View.GONE
        } else {
            binding.spinnerGeminiModel.setSelection(geminiModels.lastIndex)
            binding.etGeminiModel.setText(savedModel)
            binding.tilCustomModel.visibility = View.VISIBLE
        }

        binding.spinnerGeminiModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isCustom = geminiModels[position] == "custom"
                binding.tilCustomModel.visibility = if (isCustom) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        when (prefs.voiceEngine) {
            "elevenlabs" -> binding.rbVoiceElevenLabs.isChecked = true
            "android" -> binding.rbVoiceAndroid.isChecked = true
            else -> binding.rbVoiceGemini.isChecked = true
        }
        updateVoiceSectionVisibility()

        binding.rgVoiceEngine.setOnCheckedChangeListener { _, _ ->
            updateVoiceSectionVisibility()
        }

        // White-text dark-theme Gemini Voice Dropdown
        val voiceAdapter = ArrayAdapter(this, R.layout.spinner_item_white, geminiVoiceLabels).apply {
            setDropDownViewResource(R.layout.spinner_dropdown_white)
        }
        binding.spinnerGeminiVoice.adapter = voiceAdapter
        val idx = geminiVoices.indexOf(prefs.geminiTtsVoice).coerceAtLeast(0)
        binding.spinnerGeminiVoice.setSelection(idx)

        // Test BOTH Gemini translation AND Voice Dubbing in 1 tap
        binding.btnTestVoice.setOnClickListener {
            saveOnly()
            if (prefs.geminiApiKey.isEmpty()) {
                Toast.makeText(this, "กรุณาใส่ Gemini API Key ก่อนทดสอบครับ", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnTestVoice.text = "⏳ กำลังทดสอบแปล + พากย์เสียง..."
            lifecycleScope.launch {
                try {
                    val sampleEn = "What the fuck are you doing, man? Get in the whip, Five-O is coming!"
                    val translatedTh = GeminiClient.translateSubtitle(
                        context = this@SettingsActivity,
                        englishText = sampleEn,
                        apiKey = prefs.geminiApiKey,
                        model = prefs.geminiModel,
                        gameId = prefs.selectedGameId
                    )
                    val engineUsed = voiceRouter.speak(thaiText = translatedTh, forceEvenIfMuted = true)
                    binding.btnTestVoice.text = "🔊 ทดสอบแปล + พากย์เสียง (ผ่าน ✓)"
                    Toast.makeText(
                        this@SettingsActivity,
                        "แปล: $translatedTh\nเสียง: $engineUsed",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    binding.btnTestVoice.text = "🔊 ทดสอบแปล + พากย์เสียง (ประโยค GTA 6)"
                    Toast.makeText(
                        this@SettingsActivity,
                        "ทดสอบไม่สำเร็จ: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        binding.btnSaveSettings.setOnClickListener {
            saveOnly()
            Toast.makeText(this, "บันทึกการตั้งค่าแล้ว ✓", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnDoneSettings.setOnClickListener {
            saveOnly()
            finish()
        }
    }

    private fun updateVoiceSectionVisibility() {
        when (binding.rgVoiceEngine.checkedRadioButtonId) {
            R.id.rbVoiceGemini -> {
                binding.layoutGeminiVoiceBox.visibility = View.VISIBLE
                binding.layoutElevenLabsBox.visibility = View.GONE
            }
            R.id.rbVoiceElevenLabs -> {
                binding.layoutGeminiVoiceBox.visibility = View.GONE
                binding.layoutElevenLabsBox.visibility = View.VISIBLE
            }
            else -> {
                binding.layoutGeminiVoiceBox.visibility = View.GONE
                binding.layoutElevenLabsBox.visibility = View.GONE
            }
        }
    }

    private fun saveOnly() {
        prefs.geminiApiKey = binding.etGeminiKey.text?.toString().orEmpty()

        val selectedModelPos = binding.spinnerGeminiModel.selectedItemPosition.coerceIn(0, geminiModels.lastIndex)
        val chosenModel = geminiModels[selectedModelPos]
        prefs.geminiModel = if (chosenModel == "custom") {
            binding.etGeminiModel.text?.toString().orEmpty()
        } else {
            chosenModel
        }

        prefs.elevenLabsApiKey = binding.etElevenKey.text?.toString().orEmpty()
        prefs.elevenLabsVoiceId = binding.etVoiceId.text?.toString().orEmpty()
        prefs.autoDubEnabled = binding.switchAutoDub.isChecked
        prefs.voiceEngine = when (binding.rgVoiceEngine.checkedRadioButtonId) {
            R.id.rbVoiceElevenLabs -> "elevenlabs"
            R.id.rbVoiceAndroid -> "android"
            else -> "gemini"
        }
        val selectedIdx = binding.spinnerGeminiVoice.selectedItemPosition.coerceIn(0, geminiVoices.lastIndex)
        prefs.geminiTtsVoice = geminiVoices[selectedIdx]
    }

    override fun onDestroy() {
        voiceRouter.shutdown()
        super.onDestroy()
    }
}
