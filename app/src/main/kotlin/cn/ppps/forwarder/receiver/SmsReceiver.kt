package cn.ppps.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import cn.ppps.forwarder.utils.EVENT_SMS_CODE_SIM1
import cn.ppps.forwarder.utils.EVENT_SMS_CODE_SIM2
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.PhoneUtils
import cn.ppps.forwarder.utils.SMS_FORWARD_PREFIX
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.workers.SmsForwardWorker
import com.jeremyliao.liveeventbus.LiveEventBus
import java.util.Locale

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
                }
            }
            Log.d(TAG, "from = $from, msg = $msg")

            if (!msg.startsWith(SMS_FORWARD_PREFIX)) {
                return
            }
            if (!SettingUtils.enableSmsCommand) {
                return
            }

            val slot = getIntExtra(intent, "slot", "simSlot", "phone", "slotId", "simSlotIndex", "android.telephony.extra.SLOT_INDEX")
            val subscriptionCandidate = getSubscriptionCandidate(intent)
            val subscription = subscriptionCandidate.first
            Log.d(TAG, "slot = $slot, subscription = $subscription, isSimId = ${subscriptionCandidate.second}")

            //卡槽id：-1=获取失败、0=卡槽1、1=卡槽2
            val simSlot = resolveSimSlot(subscription, subscriptionCandidate.second, slot)
            val targetSlot = resolveForwardTargetSlot(simSlot)
            val phoneNumber = getSmsForwardPhoneNumber(targetSlot)
            if (phoneNumber.isBlank()) {
                Log.d(TAG, "skip sms forward, simSlot=$simSlot phone is blank")
                return
            }

            val smsCode = extractSmsCode(msg)
            if (!smsCode.isNullOrBlank()) {
                saveAndPostSmsCode(targetSlot, smsCode)
            }
            val smsContent = smsCode ?: msg

            val request = OneTimeWorkRequestBuilder<SmsForwardWorker>().setInputData(
                workDataOf(
                    SmsForwardWorker.KEY_PHONE_NUMBER to phoneNumber,
                    SmsForwardWorker.KEY_SMS_CONTENT to smsContent,
                )
            ).build()
            WorkManager.getInstance(context).enqueue(request)
            return

        } catch (e: Exception) {
            Log.e(TAG, "Parsing SMS failed: " + e.message.toString())
        }
    }

    private fun resolveSimSlot(subscription: Int, isSimId: Boolean, slot: Int): Int {
        if (subscription != -1) {
            val simSlot = PhoneUtils.getSimId(subscription, isSimId)
            if (simSlot == 0 || simSlot == 1) return simSlot
        }
        val smsListSimSlot = resolveSimSlotFromSmsList()
        if (smsListSimSlot == 0 || smsListSimSlot == 1) return smsListSimSlot
        if (slot == 0 || slot == 1) return slot
        return -1
    }

    private fun resolveSimSlotFromSmsList(): Int {
        return try {
            val smsInfoList = PhoneUtils.getSmsInfoList(1, 5, 0, msg)
            val smsInfo = smsInfoList.firstOrNull { it.content == msg } ?: smsInfoList.firstOrNull()
            val simSlot = smsInfo?.simId ?: -1
            Log.d(TAG, "sms list simSlot = $simSlot, smsInfo = $smsInfo")
            simSlot
        } catch (e: Exception) {
            Log.e(TAG, "resolve sim slot from sms list failed: ${e.message}")
            -1
        }
    }

    private fun getSubscriptionCandidate(intent: Intent): Pair<Int, Boolean> {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.getDefault())
        if (manufacturer.contains(Regex(pattern = "huawei|honor"))) {
            val huaweiSimId = getIntExtra(intent, "sub_id")
            if (huaweiSimId != -1) return Pair(huaweiSimId, true)
        }

        val subscription = getIntExtra(
            intent,
            "subscription",
            "subscription_id",
            "subscriptionId",
            "android.telephony.extra.SUBSCRIPTION_INDEX",
            "android.telephony.extra.SUBSCRIPTION_ID",
            "android.telephony.extra.SUBSCRIPTION_IDENTITY"
        )
        if (subscription != -1) return Pair(subscription, false)

        if (manufacturer.contains(Regex(pattern = "xiaomi|redmi"))) {
            val xiaomiSubscription = getIntExtra(intent, "sim_id")
            if (xiaomiSubscription != -1) return Pair(xiaomiSubscription, false)
        }

        val simId = getIntExtra(intent, "simId")
        return if (simId > 1) Pair(simId, false) else Pair(-1, false)
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

