package com.gta6subdub.app

import android.os.Bundle
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
        binding.etGeminiModel.setText(prefs.geminiModel)
        binding.etElevenKey.setText(prefs.elevenLabsApiKey)
        binding.etVoiceId.setText(prefs.elevenLabsVoiceId)
        binding.switchAutoDub.isChecked = prefs.autoDubEnabled

        when (prefs.voiceEngine) {
            "elevenlabs" -> binding.rbVoiceElevenLabs.isChecked = true
            "android" -> binding.rbVoiceAndroid.isChecked = true
            else -> binding.rbVoiceGemini.isChecked = true
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, geminiVoiceLabels)
        binding.spinnerGeminiVoice.adapter = adapter
        val idx = geminiVoices.indexOf(prefs.geminiTtsVoice).coerceAtLeast(0)
        binding.spinnerGeminiVoice.setSelection(idx)

        binding.btnTestVoice.setOnClickListener {
            saveOnly()
            binding.btnTestVoice.text = "⏳ กำลังสร้างเสียงพากย์..."
            lifecycleScope.launch {
                val engineUsed = voiceRouter.speak(
                    thaiText = "เฮ้ยเพื่อน เชี่ยอะไรวะเนี่ย รีบขึ้นรถแล้วซิ่งหนีตำรวจไวซ์ซิตี้เร็ว!",
                    forceEvenIfMuted = true
                )
                binding.btnTestVoice.text = "🔊 ทดสอบเสียงพากย์ (ประโยค GTA 6)"
                Toast.makeText(
                    this@SettingsActivity,
                    "เล่นเสียงผ่าน: $engineUsed",
                    Toast.LENGTH_SHORT
                ).show()
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

    private fun saveOnly() {
        prefs.geminiApiKey = binding.etGeminiKey.text?.toString().orEmpty()
        prefs.geminiModel = binding.etGeminiModel.text?.toString().orEmpty()
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
