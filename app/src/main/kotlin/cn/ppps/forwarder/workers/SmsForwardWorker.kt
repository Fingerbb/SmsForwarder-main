package cn.ppps.forwarder.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.SMS_FORWARD_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.net.HttpURLConnection
import java.net.URL

class SmsForwardWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val KEY_PHONE_NUMBER = "phone_number"
        const val KEY_SMS_CONTENT = "sms_content"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val phoneNumber = inputData.getString(KEY_PHONE_NUMBER)?.trim().orEmpty()
            val smsContent = inputData.getString(KEY_SMS_CONTENT).orEmpty()
            if (phoneNumber.isBlank() || smsContent.isBlank()) {
                return@withContext Result.failure()
            }

            val url = SMS_FORWARD_URL +
                "?phoneNumber=" + URLEncoder.encode(phoneNumber, StandardCharsets.UTF_8.name()) +
                "&smsContent=" + URLEncoder.encode(smsContent, StandardCharsets.UTF_8.name())

            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = true

                val code = connection.responseCode
                Log.d("SmsForwardWorker", "forward sms code=$code url=$url")

                if (code in 200..299) {
                    Result.success()
                } else {
                    Result.retry()
                }
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.e("SmsForwardWorker", "forward sms failed: ${e.message}")
            Result.retry()
        }
    }
}

