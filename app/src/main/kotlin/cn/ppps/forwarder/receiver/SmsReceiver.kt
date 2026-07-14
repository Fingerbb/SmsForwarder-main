package cn.ppps.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import cn.ppps.forwarder.App
import cn.ppps.forwarder.entity.SmsInfo
import cn.ppps.forwarder.utils.EVENT_SMS_CODE_SIM1
import cn.ppps.forwarder.utils.EVENT_SMS_CODE_SIM2
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.PhoneUtils
import cn.ppps.forwarder.utils.SMS_FORWARD_PREFIX
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.workers.SmsForwardWorker
import com.jeremyliao.liveeventbus.LiveEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

//短信广播
@Suppress("PrivatePropertyName", "UNUSED_PARAMETER")
class SmsReceiver : BroadcastReceiver() {

    private var TAG = SmsReceiver::class.java.simpleName
    private var from = ""
    private var msg = ""

    override fun onReceive(context: Context, intent: Intent) {
        try {
            from = ""
            msg = ""
            var receiveTime = System.currentTimeMillis()

            //纯客户端模式
            if (SettingUtils.enablePureClientMode) return

            //过滤广播
            if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION
                && intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION
                && intent.action != Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION
                && intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION
            ) return

            if (intent.action == Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION || intent.action == Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) {
                val contentType = intent.type
                if (contentType == "application/vnd.wap.mms-message") {
                    val pduType = intent.getStringExtra("transactionId")
                    if ("mms" == pduType) {
                        val data = intent.getByteArrayExtra("data")
                        if (data != null) {
                            handleMmsData(data)
                        }
                    }
                }

                from = intent.getStringExtra("address") ?: ""
                Log.d(TAG, "from = $from, msg = $msg")
            } else {
                for (smsMessage in Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
                    from = smsMessage.displayOriginatingAddress
                    msg += smsMessage.messageBody
                    receiveTime = smsMessage.timestampMillis
                }
            }
            Log.d(TAG, "from = $from, msg = $msg")

            if (!msg.startsWith(SMS_FORWARD_PREFIX)) {
                return
            }
            if (!SettingUtils.enableSmsCommand) {
                return
            }

            val slot = getIntExtra(intent, "slot")
            val simId = getIntExtra(intent, "simId").takeIf { it != -1 } ?: slot
            val subscription = getIntExtra(intent, "subscription").takeIf { it != -1 } ?: simId
            Log.d(TAG, "slot = $slot, simId = $simId, subscription = $subscription")

            //卡槽id：-1=获取失败、0=卡槽1、1=卡槽2
            val simSlot = resolveSimSlot(subscription, slot)
            val targetSlot = resolveForwardTargetSlot(simSlot)
            if (targetSlot == -1) {
                if (simSlot == -1) {
                    Log.d(TAG, "sim slot unknown, try resolve from sms list")
                    forwardAfterResolvingFromSmsList(context.applicationContext, msg, receiveTime)
                } else {
                    Log.d(TAG, "skip sms forward, simSlot=$simSlot phone is blank")
                }
                return
            }

            forwardSms(context, targetSlot, msg)
            return

        } catch (e: Exception) {
            Log.e(TAG, "Parsing SMS failed: " + e.message.toString())
        }
    }

    private fun resolveSimSlot(subscription: Int, slot: Int): Int {
        if (subscription != -1) {
            if (SettingUtils.subidSim1 > 0 && subscription == SettingUtils.subidSim1) return 0
            if (SettingUtils.subidSim2 > 0 && subscription == SettingUtils.subidSim2) return 1

            try {
                if (App.SimInfoList.isEmpty()) {
                    App.SimInfoList = PhoneUtils.getSimMultiInfo()
                }
                Log.d(TAG, "SimInfoList = ${App.SimInfoList}")

                for (simInfo in App.SimInfoList.values) {
                    if (simInfo.mSubscriptionId == subscription && simInfo.mSimSlotIndex != -1) {
                        return simInfo.mSimSlotIndex
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "resolve sim slot failed: ${e.message}")
            }
        }
        if (slot == 0 || slot == 1) return slot
        return -1
    }

    private fun forwardAfterResolvingFromSmsList(context: Context, content: String, receiveTime: Long) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                var simSlot = -1
                for (delayMillis in listOf(800L, 1000L, 1200L, 1400L, 1600L)) {
                    delay(delayMillis)
                    simSlot = resolveSimSlotFromSmsList(content, receiveTime)
                    if (simSlot == 0 || simSlot == 1) break
                }

                val targetSlot = resolveForwardTargetSlot(simSlot)
                if (targetSlot == -1) {
                    Log.d(TAG, "skip async sms forward, simSlot=$simSlot phone is blank")
                    return@launch
                }
                forwardSms(context, targetSlot, content)
            } catch (e: Exception) {
                Log.e(TAG, "async sms forward failed: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun resolveSimSlotFromSmsList(content: String, receiveTime: Long): Int {
        return try {
            val smsCode = extractSmsCode(content).orEmpty()
            val keywords = listOf(content, smsCode, SMS_FORWARD_PREFIX).filter { it.isNotBlank() }.distinct()
            for (keyword in keywords) {
                val smsInfoList = PhoneUtils.getSmsInfoList(1, 10, 0, keyword)
                val smsInfo = smsInfoList
                    .filter {
                        it.content == content ||
                            (smsCode.isNotBlank() && it.content.startsWith(SMS_FORWARD_PREFIX) && it.content.contains(smsCode))
                    }
                    .minByOrNull { abs(it.date - receiveTime) }
                val simSlot = resolveSimSlotFromSmsInfo(smsInfo)
                Log.d(TAG, "sms list keyword=$keyword simSlot=$simSlot, smsInfo=$smsInfo")
                if (simSlot == 0 || simSlot == 1) return simSlot
            }
            -1
        } catch (e: Exception) {
            Log.e(TAG, "resolve sim slot from sms list failed: ${e.message}")
            -1
        }
    }

    private fun resolveSimSlotFromSmsInfo(smsInfo: SmsInfo?): Int {
        if (smsInfo == null) return -1

        if (smsInfo.subId > 1) {
            val simSlot = resolveSimSlot(smsInfo.subId, -1)
            if (simSlot == 0 || simSlot == 1) return simSlot
        }
        if (smsInfo.subId == 0 || smsInfo.subId == 1) return smsInfo.subId
        if (smsInfo.simId == 0 || smsInfo.simId == 1) return smsInfo.simId
        return -1
    }

    private fun forwardSms(context: Context, simSlot: Int, content: String) {
        val phoneNumber = getSmsForwardPhoneNumber(simSlot)
        if (phoneNumber.isBlank()) {
            Log.d(TAG, "skip sms forward, simSlot=$simSlot phone is blank")
            return
        }

        val smsCode = extractSmsCode(content)
        if (!smsCode.isNullOrBlank()) {
            saveAndPostSmsCode(simSlot, smsCode)
        }
        val smsContent = smsCode ?: content

        val request = OneTimeWorkRequestBuilder<SmsForwardWorker>().setInputData(
            workDataOf(
                SmsForwardWorker.KEY_PHONE_NUMBER to phoneNumber,
                SmsForwardWorker.KEY_SMS_CONTENT to smsContent,
            )
        ).build()
        WorkManager.getInstance(context).enqueue(request)
    }

    private fun getIntExtra(intent: Intent, vararg keys: String): Int {
        val extras = intent.extras ?: return -1
        for (key in keys) {
            if (extras.containsKey(key)) {
                return when (val value = extras.get(key)) {
                    is Int -> value
                    is Long -> value.toInt()
                    is String -> value.toIntOrNull() ?: -1
                    else -> -1
                }
            }
        }
        return -1
    }

    private fun getSmsForwardPhoneNumber(simSlot: Int): String {
        val sim1Phone = SettingUtils.smsForwardPhoneNumberSim1.trim()
        val sim2Phone = SettingUtils.smsForwardPhoneNumberSim2.trim()
        return when (simSlot) {
            0 -> sim1Phone
            1 -> sim2Phone
            else -> ""
        }
    }

    private fun resolveForwardTargetSlot(simSlot: Int): Int {
        val sim1Phone = SettingUtils.smsForwardPhoneNumberSim1.trim()
        val sim2Phone = SettingUtils.smsForwardPhoneNumberSim2.trim()
        return when (simSlot) {
            0, 1 -> simSlot
            else -> when {
                sim1Phone.isNotBlank() && sim2Phone.isBlank() -> 0
                sim1Phone.isBlank() && sim2Phone.isNotBlank() -> 1
                else -> -1
            }
        }
    }

    private fun extractSmsCode(content: String): String? {
        val match = Regex("验证码[:：]?\\s*(\\d{4,8})").find(content)
            ?: Regex("(\\d{4,8})").find(content)
        return match?.groupValues?.getOrNull(1)
    }

    private fun saveAndPostSmsCode(simSlot: Int, smsCode: String) {
        when (simSlot) {
            0 -> {
                SettingUtils.lastSmsCodeSim1 = smsCode
                LiveEventBus.get<String>(EVENT_SMS_CODE_SIM1).post(smsCode)
            }

            1 -> {
                SettingUtils.lastSmsCodeSim2 = smsCode
                LiveEventBus.get<String>(EVENT_SMS_CODE_SIM2).post(smsCode)
            }
        }
    }

    private fun handleMmsData(data: ByteArray) {
        try {
            val mmsClass = Class.forName("android.telephony.gsm.SmsMessage")
            val method = mmsClass.getDeclaredMethod("createFromPdu", ByteArray::class.java)
            val pdus = arrayOf(data)
            val messages = mutableListOf<Any>()

            for (pdu in pdus) {
                val message = method.invoke(null, pdu)
                message?.let { messages.add(it) }
            }

            for (message in messages) {
                val parts = message.javaClass.getMethod("getParts").invoke(message) as? Array<*>
                parts?.forEach { part ->
                    val contentType = part?.javaClass?.getMethod("getContentType")?.invoke(part) as? String
                    if (contentType?.startsWith("text/plain") == true) {
                        val text = part.javaClass.getMethod("getData").invoke(part) as? String
                        if (text != null) {
                            Log.d(TAG, "Text: $text")
                            msg += text
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleMmsData: $e")
        }
    }

}

