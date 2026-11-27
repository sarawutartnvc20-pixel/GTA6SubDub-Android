package com.gta6subdub.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import com.gta6subdub.app.databinding.ActivityPs5SetupWizardBinding
import com.metallic.chiaki.lib.ChiakiLog
import com.metallic.chiaki.lib.DiscoveryService
import com.metallic.chiaki.lib.DiscoveryServiceOptions
import com.metallic.chiaki.lib.Regist
import com.metallic.chiaki.lib.RegistEventCanceled
import com.metallic.chiaki.lib.RegistEventFailed
import com.metallic.chiaki.lib.RegistEventSuccess
import com.metallic.chiaki.lib.RegistInfo
import com.metallic.chiaki.lib.Target
import java.net.InetSocketAddress

class Ps5SetupWizardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPs5SetupWizardBinding
    private lateinit var prefs: PrefsStore
    private var discoveryService: DiscoveryService? = null
    private var regist: Regist? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPs5SetupWizardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsStore(this)
        binding.etAccountIdInput.setText(prefs.psnAccountIdBase64)
        binding.etWizardPs5Ip.setText(prefs.ps5Host)

        binding.btnCloseWizard.setOnClickListener { finish() }

        // Easiest no-password PSN Account ID lookup
        binding.btnOpenIdFinder.setOnClickListener {
            val tab = CustomTabsIntent.Builder().setShowTitle(true).build()
            tab.launchUrl(this, Uri.parse("https://psn.flipscreen.games/"))
        }

        // Optional Sony login fallback
        binding.btnOpenSonyFallback.setOnClickListener {
            startActivity(Intent(this, PsnAuthActivity::class.java))
        }

        binding.btnSaveAccountId.setOnClickListener {
            val raw = binding.etAccountIdInput.text.toString().trim()
            val decoded = try {
                Base64.decode(raw, Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
            if (decoded == null || decoded.size != 8) {
                Toast.makeText(
                    this,
                    "กรุณาวางค่า Encoded ID (for Chiaki) จากเว็บ psn.flipscreen.games (8 bytes Base64)",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            prefs.psnAccountIdBase64 = raw
            Toast.makeText(this, "บันทึกบัญชี PSN ที่ 2 เรียบร้อย ✓", Toast.LENGTH_SHORT).show()
            refreshChecks()
        }

        binding.btnWizardPair.setOnClickListener {
            startPairing()
        }

        refreshChecks()
        startDiscovery()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.psnAccountIdBase64.isNotEmpty()) {
            binding.etAccountIdInput.setText(prefs.psnAccountIdBase64)
        }
        refreshChecks()
    }

    private fun refreshChecks() {
        val psnOk = prefs.isPsnLoggedIn
        binding.tvStep1Check.text = if (psnOk) "✓" else "●"
        binding.tvStep1Check.setTextColor(if (psnOk) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt())

        val ps5Ok = prefs.isPs5Paired
        binding.tvStep3Check.text = if (ps5Ok) "✓" else "●"
        binding.tvStep3Check.setTextColor(if (ps5Ok) 0xFF4ADE80.toInt() else 0xFFFACC15.toInt())
    }

    private fun startDiscovery() {
        try {
            val options = DiscoveryServiceOptions(
                hostsMax = 16uL,
                hostDropPings = 4uL,
                pingMs = 500uL,
                sendAddr = InetSocketAddress("255.255.255.255", 9302)
            )
            discoveryService = DiscoveryService(options) { hosts ->
                val ps5 = hosts.firstOrNull { !it.hostAddr.isNullOrEmpty() }
                if (ps5 != null) {
                    runOnUiThread {
                        if (binding.etWizardPs5Ip.text.isNullOrBlank()) {
                            binding.etWizardPs5Ip.setText(ps5.hostAddr)
                        }
                        binding.tvWizardDiscovery.text =
                            "✓ พบเครื่อง: ${ps5.hostName ?: "PS5"} (${ps5.hostAddr})"
                        binding.tvWizardDiscovery.setTextColor(0xFF4ADE80.toInt())
                    }
                }
            }
        } catch (e: Exception) {
            binding.tvWizardDiscovery.text = "ค้นหาอัตโนมัติไม่สำเร็จ — กรอก IP ของ PS5 เองได้เลย"
        }
    }

    private fun startPairing() {
        // Auto-save account ID field if user pasted it without tapping Save
        val typedAcc = binding.etAccountIdInput.text.toString().trim()
        if (typedAcc.isNotEmpty() && typedAcc != prefs.psnAccountIdBase64) {
            val decoded = try { Base64.decode(typedAcc, Base64.DEFAULT) } catch (_: Exception) { null }
            if (decoded != null && decoded.size == 8) {
                prefs.psnAccountIdBase64 = typedAcc
                refreshChecks()
            }
        }

        val accountId = prefs.getPsnAccountIdBytes()
        if (accountId == null) {
            Toast.makeText(this, "กรุณาใส่ Account ID ในขั้นที่ 1 ก่อนครับ", Toast.LENGTH_LONG).show()
            return
        }

        val hostIp = binding.etWizardPs5Ip.text.toString().trim()
        if (hostIp.isEmpty()) {
            Toast.makeText(this, "กรุณากรอก IP ของ PS5 ในขั้นที่ 2", Toast.LENGTH_SHORT).show()
            return
        }

        val pinStr = binding.etWizardPin.text.toString().trim()
        val pinInt = pinStr.toIntOrNull()
        if (pinInt == null || pinStr.length != 8) {
            Toast.makeText(this, "กรุณากรอก PIN 8 หลักจากหน้าจอ PS5", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnWizardPair.text = "กำลังจับคู่ PS5..."
        binding.btnWizardPair.isEnabled = false

        val log = ChiakiLog(ChiakiLog.Level.ALL.value) { _, _ -> }
        val info = RegistInfo(
            target = Target.PS5_1,
            host = hostIp,
            broadcast = false,
            psnOnlineId = null,
            psnAccountId = accountId,
            pin = pinInt
        )

        try {
            regist?.dispose()
            regist = Regist(info, log) { event ->
                runOnUiThread {
                    binding.btnWizardPair.isEnabled = true
                    binding.btnWizardPair.text = "จับคู่ PS5 ทันที"
                    when (event) {
                        is RegistEventSuccess -> {
                            prefs.ps5Host = hostIp
                            prefs.ps5Nickname = event.host.serverNickname.ifEmpty { "PS5" }
                            prefs.rpRegistKeyBase64 =
                                Base64.encodeToString(event.host.rpRegistKey, Base64.NO_WRAP)
                            prefs.rpKeyBase64 =
                                Base64.encodeToString(event.host.rpKey, Base64.NO_WRAP)
                            refreshChecks()
                            Toast.makeText(this, "จับคู่ PS5 สำเร็จพร้อมใช้งาน ✓", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        is RegistEventFailed -> {
                            Toast.makeText(
                                this,
                                "จับคู่ไม่สำเร็จ เช็กว่าเปิดหน้า PIN 8 หลักบน PS5 ค้างไว้ + IP และ Account ID ถูกต้องครับ",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is RegistEventCanceled -> {
                            Toast.makeText(this, "ยกเลิกการจับคู่", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            binding.btnWizardPair.isEnabled = true
            binding.btnWizardPair.text = "จับคู่ PS5 ทันที"
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        regist?.dispose()
        discoveryService?.dispose()
        super.onDestroy()
    }
}
