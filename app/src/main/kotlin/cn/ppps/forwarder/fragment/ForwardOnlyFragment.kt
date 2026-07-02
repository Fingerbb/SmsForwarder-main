package cn.ppps.forwarder.fragment

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentForwardOnlyBinding
import cn.ppps.forwarder.utils.EVENT_SMS_CODE_SIM1
import cn.ppps.forwarder.utils.EVENT_SMS_CODE_SIM2
import cn.ppps.forwarder.utils.SMS_FORWARD_URL
import cn.ppps.forwarder.utils.SMS_USER_INFO_URL
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.XToastUtils
import com.jeremyliao.liveeventbus.LiveEventBus
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xui.widget.actionbar.TitleBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Page(name = "广州短信上报")
class ForwardOnlyFragment : BaseFragment<FragmentForwardOnlyBinding?>() {

    override fun viewBindingInflate(
        inflater: LayoutInflater,
        container: ViewGroup,
    ): FragmentForwardOnlyBinding {
        return FragmentForwardOnlyBinding.inflate(inflater, container, false)
    }

    override fun initTitle(): TitleBar? {
        return super.initTitle()!!
            .setImmersive(false)
            .setTitle(R.string.app_name)
            .disableLeftView()
    }

    override fun initViews() {
        sanitizeLegacySettings()
        SettingUtils.enableSmsCommand = true
        requestSmsPermissions()
        bindPhoneFields()
        bindQueryButton()
        bindCodeFields()
        observeSmsCodeEvents()
    }

    private fun requestSmsPermissions() {
        XXPermissions.with(this)
            .permission(PermissionLists.getReceiveSmsPermission())
            .permission(PermissionLists.getReadSmsPermission())
            .permission(PermissionLists.getReadPhoneStatePermission())
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    val allGranted = deniedList.isEmpty()
                    if (!allGranted) {
                        val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(requireActivity(), deniedList)
                        if (doNotAskAgain) {
                            XToastUtils.error(R.string.toast_denied_never)
                            XXPermissions.startPermissionActivity(requireContext(), deniedList)
                        }
                        XToastUtils.error(getString(R.string.sms_forward_phone) + ": " + getString(R.string.toast_denied))
                        SettingUtils.enableSmsCommand = false
                        return
                    }
                    SettingUtils.enableSmsCommand = true
                }
            })
    }

    private fun bindPhoneFields() {
        binding!!.etPhoneSim1.setText(SettingUtils.smsForwardPhoneNumberSim1)
        binding!!.etPhoneSim2.setText(SettingUtils.smsForwardPhoneNumberSim2)

        binding!!.etPhoneSim1.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                SettingUtils.smsForwardPhoneNumberSim1 = binding!!.etPhoneSim1.text.toString().trim().removeSuffix("\n")
            }
        })
        binding!!.etPhoneSim2.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable) {
                SettingUtils.smsForwardPhoneNumberSim2 = binding!!.etPhoneSim2.text.toString().trim().removeSuffix("\n")
            }
        })
        binding!!.btnSaveSim1.setOnClickListener {
            savePhoneNumbers()
            XToastUtils.success(R.string.tipSaveSuccess)
        }
        binding!!.btnSaveSim2.setOnClickListener {
            savePhoneNumbers()
            XToastUtils.success(R.string.tipSaveSuccess)
        }
        binding!!.btnReset.setOnClickListener {
            SettingUtils.smsForwardPhoneNumberSim1 = ""
            SettingUtils.smsForwardPhoneNumberSim2 = ""
            SettingUtils.lastSmsCodeSim1 = ""
            SettingUtils.lastSmsCodeSim2 = ""
            binding!!.etPhoneSim1.setText("")
            binding!!.etPhoneSim2.setText("")
            binding!!.etCodeSim1.setText("")
            binding!!.etCodeSim2.setText("")
            binding!!.tvQueryResultSim1.setText(R.string.forward_only_query_result_sim1_empty)
            binding!!.tvQueryResultSim2.setText(R.string.forward_only_query_result_sim2_empty)
        }
    }

    private fun savePhoneNumbers() {
        SettingUtils.smsForwardPhoneNumberSim1 = binding!!.etPhoneSim1.text.toString().trim().removeSuffix("\n")
        SettingUtils.smsForwardPhoneNumberSim2 = binding!!.etPhoneSim2.text.toString().trim().removeSuffix("\n")
    }

    private fun bindQueryButton() {
        binding!!.btnQueryUser.setOnClickListener {
            val sim1Phone = binding!!.etPhoneSim1.text.toString().trim().removeSuffix("\n")
            val sim2Phone = binding!!.etPhoneSim2.text.toString().trim().removeSuffix("\n")
            SettingUtils.smsForwardPhoneNumberSim1 = sim1Phone
            SettingUtils.smsForwardPhoneNumberSim2 = sim2Phone
            if (sim1Phone.isBlank() && sim2Phone.isBlank()) {
                XToastUtils.warning(R.string.forward_only_query_phone_empty)
                binding!!.tvQueryResultSim1.text = getString(R.string.forward_only_query_result_empty)
                binding!!.tvQueryResultSim2.text = getString(R.string.forward_only_query_result_empty)
                return@setOnClickListener
            }

            binding!!.btnQueryUser.isEnabled = false
            binding!!.btnQueryUser.setText(R.string.forward_only_querying)
            binding!!.tvQueryResultSim1.text = if (sim1Phone.isBlank()) {
                getString(R.string.forward_only_query_result_empty)
            } else {
                getString(R.string.forward_only_query_result_querying)
            }
            binding!!.tvQueryResultSim2.text = if (sim2Phone.isBlank()) {
                getString(R.string.forward_only_query_result_empty)
            } else {
                getString(R.string.forward_only_query_result_querying)
            }

            lifecycleScope.launch {
                val sim1Result = queryUserInfo(sim1Phone)
                val sim2Result = queryUserInfo(sim2Phone)

                binding!!.tvQueryResultSim1.text = sim1Result
                binding!!.tvQueryResultSim2.text = sim2Result
                binding!!.btnQueryUser.isEnabled = true
                binding!!.btnQueryUser.setText(R.string.forward_only_query)
            }
        }
    }

    private fun bindCodeFields() {
        binding!!.etCodeSim1.setText(SettingUtils.lastSmsCodeSim1)
        binding!!.etCodeSim2.setText(SettingUtils.lastSmsCodeSim2)
        binding!!.btnSendCodeSim1.setOnClickListener {
            sendManualCode(0)
        }
        binding!!.btnSendCodeSim2.setOnClickListener {
            sendManualCode(1)
        }
        binding!!.btnClearCodeSim1.setOnClickListener {
            SettingUtils.lastSmsCodeSim1 = ""
            binding!!.etCodeSim1.setText("")
        }
        binding!!.btnClearCodeSim2.setOnClickListener {
            SettingUtils.lastSmsCodeSim2 = ""
            binding!!.etCodeSim2.setText("")
        }
    }

    private fun observeSmsCodeEvents() {
        LiveEventBus.get(EVENT_SMS_CODE_SIM1, String::class.java).observe(this) { code ->
            SettingUtils.lastSmsCodeSim1 = code
            binding!!.etCodeSim1.setText(code)
        }
        LiveEventBus.get(EVENT_SMS_CODE_SIM2, String::class.java).observe(this) { code ->
            SettingUtils.lastSmsCodeSim2 = code
            binding!!.etCodeSim2.setText(code)
        }
    }

    private fun sendManualCode(simSlot: Int) {
        savePhoneNumbers()
        val phoneNumber = if (simSlot == 0) SettingUtils.smsForwardPhoneNumberSim1.trim() else SettingUtils.smsForwardPhoneNumberSim2.trim()
        val code = if (simSlot == 0) {
            binding!!.etCodeSim1.text.toString().trim()
        } else {
            binding!!.etCodeSim2.text.toString().trim()
        }
        if (phoneNumber.isBlank()) {
            XToastUtils.warning(R.string.forward_only_send_phone_empty)
            return
        }
        if (code.isBlank()) {
            XToastUtils.warning(R.string.forward_only_send_code_empty)
            return
        }

        val button = if (simSlot == 0) binding!!.btnSendCodeSim1 else binding!!.btnSendCodeSim2
        button.isEnabled = false
        button.setText(R.string.forward_only_sending)
        lifecycleScope.launch {
            val success = submitSmsContent(phoneNumber, code)
            button.isEnabled = true
            button.setText(R.string.send)
            if (success) {
                if (simSlot == 0) SettingUtils.lastSmsCodeSim1 = code else SettingUtils.lastSmsCodeSim2 = code
                XToastUtils.success(R.string.forward_only_send_success)
            } else {
                XToastUtils.error(R.string.forward_only_send_failed)
            }
        }
    }

    private suspend fun queryUserInfo(phone: String): String {
        if (phone.isBlank()) {
            return getString(R.string.forward_only_query_result_empty)
        }

        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = SMS_USER_INFO_URL + "?phone=" + URLEncoder.encode(phone, StandardCharsets.UTF_8.name())
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = true

                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299 || body.isBlank()) {
                    return@withContext getString(R.string.forward_only_query_result_failed)
                }

                val json = JSONObject(body)
                val data = json.optJSONObject("data")
                val name = data?.optString("name").orEmpty()
                val userTypeDesc = data?.optString("userTypeDesc").orEmpty()
                if (!json.optBoolean("success", false) || name.isBlank() || userTypeDesc.isBlank()) {
                    return@withContext getString(R.string.forward_only_query_result_failed)
                }

                getString(R.string.forward_only_query_result_format, userTypeDesc, name)
            } catch (e: Exception) {
                getString(R.string.forward_only_query_result_failed)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private suspend fun submitSmsContent(phoneNumber: String, smsContent: String): Boolean {
        return withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            try {
                val url = SMS_FORWARD_URL +
                    "?phoneNumber=" + URLEncoder.encode(phoneNumber, StandardCharsets.UTF_8.name()) +
                    "&smsContent=" + URLEncoder.encode(smsContent, StandardCharsets.UTF_8.name())
                connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.instanceFollowRedirects = true
                connection.responseCode in 200..299
            } catch (e: Exception) {
                false
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun sanitizeLegacySettings() {
        SettingUtils.autoCheckUpdate = false
        SettingUtils.joinPreviewProgram = false
        SettingUtils.enableSms = false
        SettingUtils.enablePhone = false
        SettingUtils.enableCallType1 = false
        SettingUtils.enableCallType2 = false
        SettingUtils.enableCallType3 = false
        SettingUtils.enableCallType4 = false
        SettingUtils.enableCallType5 = false
        SettingUtils.enableCallType6 = false
        SettingUtils.enableAppNotify = false
        SettingUtils.enableCancelAppNotify = false
        SettingUtils.enableNotUserPresent = false
        SettingUtils.enableCloseToEarpieceTurnOffScreen = false
        SettingUtils.enableLoadAppList = false
        SettingUtils.enableLoadUserAppList = false
        SettingUtils.enableLoadSystemAppList = false
        SettingUtils.enableExcludeFromRecents = false
        SettingUtils.enableCactus = false
        SettingUtils.enablePlaySilenceMusic = false
        SettingUtils.enableOnePixelActivity = false
        SettingUtils.enableSmsTemplate = false
        SettingUtils.enablePureClientMode = false
        SettingUtils.enablePureTaskMode = false
        SettingUtils.enableDebugMode = false
        SettingUtils.enableLocation = false
        SettingUtils.enableBluetooth = false
        SettingUtils.smsCommandSafePhone = ""
    }
}
