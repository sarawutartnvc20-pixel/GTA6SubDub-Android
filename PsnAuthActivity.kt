package com.gta6subdub.app

import android.annotation.SuppressLint
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
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PsnAuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPsnAuthBinding
    private lateinit var prefs: PrefsStore
    private val httpClient = OkHttpClient()

    companion object {
        private const val CLIENT_ID = "ba495a24-818c-472b-b12d-ff231c1b5745"
        private const val CLIENT_SECRET = "mvaiZkRsAsI1IBkY"
        private const val REDIRECT_URI = "https://remoteplay.dl.playstation.net/remoteplay/redirect"

        private const val AUTH_URL =
            "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/authorize" +
                "?service_entity=urn:service-entity:psn" +
                "&response_type=code" +
                "&client_id=$CLIENT_ID" +
                "&redirect_uri=$REDIRECT_URI" +
                "&scope=psn:clientapp" +
                "&request_locale=en_US" +
                "&ui=pr" +
                "&service_logo=ps" +
                "&layout_type=popup" +
                "&smcid=remoteplay" +
                "&prompt=always" +
                "&PlatformPrivacyWs1=minimal&"

        private const val TOKEN_URL = "https://auth.api.sonyentertainmentnetwork.com/2.0/oauth/token"
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
                if (url.toString().startsWith(REDIRECT_URI)) {
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
                val basicAuth = Credentials.basic(CLIENT_ID, CLIENT_SECRET)
                val formBody = FormBody.Builder()
                    .add("grant_type", "authorization_code")
                    .add("code", code)
                    .add("redirect_uri", REDIRECT_URI)
                    .build()

                val tokenReq = Request.Builder()
                    .url(TOKEN_URL)
                    .addHeader("Authorization", basicAuth)
                    .post(formBody)
                    .build()

                val accessToken = httpClient.newCall(tokenReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Sony Token Error (${resp.code}): $body")
                    }
                    val json = JSONObject(body)
                    json.optString("access_token", "").also {
                        if (it.isEmpty()) throw IllegalStateException("ไม่พบ access_token")
                    }
                }

                val infoUrl = "$TOKEN_URL/${URLEncoder.encode(accessToken, "UTF-8")}"
                val infoReq = Request.Builder()
                    .url(infoUrl)
                    .addHeader("Authorization", basicAuth)
                    .get()
                    .build()

                val accountIdBase64 = httpClient.newCall(infoReq).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("Sony Account Info Error (${resp.code}): $body")
                    }
                    val json = JSONObject(body)
                    val userIdStr = json.optString("user_id", "")
                    if (userIdStr.isEmpty()) {
                        throw IllegalStateException("ไม่พบ user_id จากบัญชี PSN นี้")
                    }
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
}
