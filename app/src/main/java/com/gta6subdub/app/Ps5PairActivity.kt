package com.gta6subdub.app

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gta6subdub.app.databinding.ActivityPs5PairBinding
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

class Ps5PairActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPs5PairBinding
    private lateinit var prefs: PrefsStore
    private var discoveryService: DiscoveryService? = null
    private var regist: Regist? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPs5PairBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsStore(this)
        if (prefs.ps5Host.isNotEmpty()) {
            binding.etPs5Ip.setText(prefs.ps5Host)
        }

        binding.btnClosePair.setOnClickListener { finish() }

        binding.btnPairPs5.setOnClickListener {
            startPairing()
        }

        startDiscovery()
    }

    private fun startDiscovery() {
        try {
            val options = DiscoveryServiceOptions(
                hostsMax = 16u,
                hostDropPings = 4u,
                pingMs = 500u,
                sendAddr = InetSocketAddress("255.255.255.255", 9302)
            )
            discoveryService = DiscoveryService(options) { hosts ->
                val ps5 = hosts.firstOrNull { !it.hostAddr.isNullOrEmpty() }
                if (ps5 != null) {
                    runOnUiThread {
                        if (binding.etPs5Ip.text.isNullOrBlank()) {
                            binding.etPs5Ip.setText(ps5.hostAddr)
                        }
                        binding.tvDiscoveryStatus.text =
                            "✓ พบเครื่อง: ${ps5.hostName ?: "PS5"} (${ps5.hostAddr})"
                        binding.tvDiscoveryStatus.setTextColor(0xFF4ADE80.toInt())
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            binding.tvDiscoveryStatus.text = "ค้นหาอัตโนมัติไม่สำเร็จ สามารถกรอก IP ของ PS5 เองได้"
        }
    }

    private fun startPairing() {
        val accountId = prefs.getPsnAccountIdBytes()
        if (accountId == null) {
            Toast.makeText(this, "กรุณาล็อกอินบัญชี PSN ที่สองก่อนครับ", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, PsnAuthActivity::class.java))
            return
        }

        val hostIp = binding.etPs5Ip.text.toString().trim()
        if (hostIp.isEmpty()) {
            Toast.makeText(this, "กรุณากรอก IP ของ PS5", Toast.LENGTH_SHORT).show()
            return
        }

        val pinStr = binding.etPs5Pin.text.toString().trim()
        val pinInt = pinStr.toIntOrNull()
        if (pinInt == null || pinStr.length != 8) {
            Toast.makeText(this, "กรุณากรอก PIN 8 หลักจากหน้าจอ PS5", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnPairPs5.text = "กำลังจับคู่..."
        binding.btnPairPs5.isEnabled = false

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
                    binding.btnPairPs5.isEnabled = true
                    binding.btnPairPs5.text = "จับคู่ PS5 ทันที"
                    when (event) {
                        is RegistEventSuccess -> {
                            prefs.ps5Host = hostIp
                            prefs.ps5Nickname = event.host.serverNickname.ifEmpty { "PS5" }
                            prefs.rpRegistKeyBase64 =
                                Base64.encodeToString(event.host.rpRegistKey, Base64.NO_WRAP)
                            prefs.rpKeyBase64 =
                                Base64.encodeToString(event.host.rpKey, Base64.NO_WRAP)
                            Toast.makeText(this, "จับคู่ PS5 สำเร็จ ✓", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        is RegistEventFailed -> {
                            Toast.makeText(
                                this,
                                "จับคู่ไม่สำเร็จ เช็ก PIN 8 หลัก, IP และบัญชี PSN ที่สองครับ",
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
            binding.btnPairPs5.isEnabled = true
            binding.btnPairPs5.text = "จับคู่ PS5 ทันที"
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        regist?.dispose()
        discoveryService?.dispose()
        super.onDestroy()
    }
}
