package com.gta6subdub.app

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gta6subdub.app.databinding.ActivityPsnAuthBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PsnAuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPsnAuthBinding
    private lateinit var prefs: PrefsStore
    private val httpClient = OkHttpClient()

    companion object {
        private const val AUTH_URL =
            "https://ca.account.sony.com/api/authz/v3/oauth/authorize" +
                "?access_type=offline" +
                "&client_id=09515159-7237-4370-9b40-3806e67c0891" +
                "&redirect_uri=com.scee.psxandroid.sceecomp://redirect" +
                "&response_type=code" +
                "&scope=psn:mobile.v2.core%20psn:clientapp"

        private const val TOKEN_URL = "https://ca.account.sony.com/api/authz/v3/oauth/token"
        private const val BASIC_AUTH =
            "Basic MDk1MTUxNTktNzIzNy00MzcwLTliNDAtMzgwNmU2N2MwODkxOnVjUGprYTV0bnRCMktxc1A="
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPsnAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PrefsStore(this)
        binding.etManualAccountId.setText(prefs.psnAccountIdBase64)

        binding.btnClosePsn.setOnClickListener { finish() }

        binding.btnSaveManualAccount.setOnClickListener {
            val b64 = binding.etManualAccountId.text.toString().trim()
            val decoded = try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (_: Exception) {
                null
            }
            if (decoded == null || decoded.size != 8) {
                Toast.makeText(this, "Account ID Base64 ต้องถอดรหัสได้ 8 bytes ครับ", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            prefs.psnAccountIdBase64 = b64
            Toast.makeText(this, "บันทึกบัญชี PSN แล้ว ✓", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.webViewPsn.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        binding.webViewPsn.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.scheme == "com.scee.psxandroid.sceecomp") {
                    val code = url.getQueryParameter("code")
                    if (!code.isNullOrEmpty()) {
                        exchangeCodeForAccountId(code)
                    } else {
                        Toast.makeText(this@PsnAuthActivity, "ไม่พบ OAuth code จาก Sony", Toast.LENGTH_LONG).show()
                    }
                    return true
                }
                return false
            }
        }

        binding.webViewPsn.loadUrl(AUTH_URL)
    }

    private fun exchangeCodeForAccountId(code: String) {
        binding.tvPsnHint.text = "กำลังดึงข้อมูลบัญชี PSN จาก Sony..."
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val formBody = FormBody.Builder()
                    .add("code", code)
                    .add("redirect_uri", "com.scee.psxandroid.sceecomp://redirect")
                    .add("grant_type", "authorization_code")
                    .add("token_format", "jwt")
                    .build()

                val request = Request.Builder()
                    .url(TOKEN_URL)
                    .addHeader("Authorization", BASIC_AUTH)
                    .post(formBody)
                    .build()

                val accountIdBase64 = httpClient.newCall(request).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Sony Token Error (${resp.code}): $body")
                    }
                    val json = JSONObject(body)
                    val accessToken = json.optString("access_token", "")
                    val userIdStr = extractUserId(json, accessToken)
                    val userIdLong = userIdStr.toULong().toLong()
                    val bytes = ByteBuffer.allocate(8)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .putLong(userIdLong)
                        .array()
                    Base64.encodeToString(bytes, Base64.NO_WRAP)
                }

                withContext(Dispatchers.Main) {
                    prefs.psnAccountIdBase64 = accountIdBase64
                    Toast.makeText(this@PsnAuthActivity, "ล็อกอินบัญชี PSN สำเร็จ ✓", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    binding.tvPsnHint.text = "Error: ${e.message}"
                    Toast.makeText(this@PsnAuthActivity, "ล็อกอินไม่สำเร็จ: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun extractUserId(tokenJson: JSONObject, accessToken: String): String {
        val direct = tokenJson.optString("user_id", "")
        if (direct.isNotEmpty()) return direct

        // Fallback: fetch from PlayStation DMS accounts/me endpoint
        val req = Request.Builder()
            .url("https://dms.api.playstation.com/api/v1/devices/accounts/me")
            .addHeader("Authorization", "Bearer $accessToken")
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                val obj = JSONObject(body)
                val accId = obj.optString("accountId", "")
                if (accId.isNotEmpty()) return accId
            }
        }
        throw IllegalStateException("ไม่พบ accountId จากบัญชี PSN นี้")
    }
}
