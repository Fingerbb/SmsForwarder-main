package cn.ppps.forwarder.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import cn.ppps.forwarder.App
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.PhoneUtils
import cn.ppps.forwarder.utils.SMS_FORWARD_PREFIX
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.workers.SmsForwardWorker

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
            ) return

            for (smsMessage in Telephony.Sms.Intents.getMessagesFromIntent(intent)) {
                from = smsMessage.displayOriginatingAddress
                msg += smsMessage.messageBody
            }
            Log.d(TAG, "from = $from, msg = $msg")

            //TODO：准确获取卡槽信息，目前测试结果只有 subscription 相对靠谱
            val slot = intent.extras?.getInt("slot")
                ?: intent.extras?.getInt("simSlot")
                ?: intent.extras?.getInt("phone")
                ?: intent.extras?.getInt("slotId")
                ?: -1
            val simId = intent.extras?.getInt("simId") ?: slot
            val subscription = intent.extras?.getInt("subscription") ?: simId
            Log.d(TAG, "slot = $slot, simId = $simId, subscription = $subscription")

            //卡槽id：-1=获取失败、0=卡槽1、1=卡槽2
            val simSlot = resolveSimSlot(subscription, slot)

            if (!msg.startsWith(SMS_FORWARD_PREFIX)) {
                return
            }
            if (!SettingUtils.enableSmsCommand) {
                return
            }

            val phoneNumber = getSmsForwardPhoneNumber(simSlot)
            if (phoneNumber.isBlank()) {
                Log.d(TAG, "skip sms forward, simSlot=$simSlot phone is blank")
                return
            }

            val request = OneTimeWorkRequestBuilder<SmsForwardWorker>().setInputData(
                workDataOf(
                    SmsForwardWorker.KEY_PHONE_NUMBER to phoneNumber,
                    SmsForwardWorker.KEY_SMS_CONTENT to msg,
                )
            ).build()
            WorkManager.getInstance(context).enqueue(request)
            return

        } catch (e: Exception) {
            Log.e(TAG, "Parsing SMS failed: " + e.message.toString())
        }
    }

    private fun resolveSimSlot(subscription: Int, slot: Int): Int {
        if (SettingUtils.subidSim1 > 0 && subscription == SettingUtils.subidSim1) return 0
        if (SettingUtils.subidSim2 > 0 && subscription == SettingUtils.subidSim2) return 1
        if (slot == 0 || slot == 1) return slot

        try {
            if (App.SimInfoList.isEmpty()) {
                App.SimInfoList = PhoneUtils.getSimMultiInfo()
            }
            Log.d(TAG, "SimInfoList = " + App.SimInfoList.toString())

            if (App.SimInfoList.isNotEmpty()) {
                for (simInfo in App.SimInfoList.values) {
                    if (simInfo.mSubscriptionId == subscription) {
                        return simInfo.mSimSlotIndex
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolve sim slot failed: ${e.message}")
        }

        return -1
    }

    private fun getSmsForwardPhoneNumber(simSlot: Int): String {
        val sim1Phone = SettingUtils.smsForwardPhoneNumberSim1.trim()
        val sim2Phone = SettingUtils.smsForwardPhoneNumberSim2.trim()
        return when (simSlot) {
            0 -> sim1Phone
            1 -> sim2Phone
            else -> when {
                sim1Phone.isNotBlank() && sim2Phone.isBlank() -> sim1Phone
                sim1Phone.isBlank() && sim2Phone.isNotBlank() -> sim2Phone
                else -> ""
            }
        }
    }

}

