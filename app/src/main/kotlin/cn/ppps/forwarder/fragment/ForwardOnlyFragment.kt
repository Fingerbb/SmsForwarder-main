package cn.ppps.forwarder.fragment

import android.annotation.SuppressLint
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentForwardOnlyBinding
import cn.ppps.forwarder.utils.SMS_FORWARD_PREFIX
import cn.ppps.forwarder.utils.SMS_USER_INFO_URL
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.XToastUtils
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
        binding!!.tvPrefixValue.text = SMS_FORWARD_PREFIX
        bindForwardSwitch()
        bindPhoneFields()
        bindQueryButton()
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun bindForwardSwitch() {
        val isEnabled = SettingUtils.enableSmsCommand
        binding!!.sbEnableUpload.isChecked = isEnabled
        updatePhoneFieldsVisible(isEnabled)

        binding!!.sbEnableUpload.setOnCheckedChangeListener { _: CompoundButton?, isChecked: Boolean ->
            SettingUtils.enableSmsCommand = isChecked
            updatePhoneFieldsVisible(isChecked)
            if (!isChecked) {
                return@setOnCheckedChangeListener
            }

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
                            binding!!.sbEnableUpload.isChecked = false
                            updatePhoneFieldsVisible(false)
                            return
                        }
                        XToastUtils.info(R.string.toast_granted_all)
                    }
                })
        }
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
    }

    private fun bindQueryButton() {
        binding!!.btnQueryUser.setOnClickListener {
            val sim1Phone = binding!!.etPhoneSim1.text.toString().trim().removeSuffix("\n")
            val sim2Phone = binding!!.etPhoneSim2.text.toString().trim().removeSuffix("\n")
            SettingUtils.smsForwardPhoneNumberSim1 = sim1Phone
            SettingUtils.smsForwardPhoneNumberSim2 = sim2Phone
            val sim1Label = getString(R.string.forward_only_sim1_label)
            val sim2Label = getString(R.string.forward_only_sim2_label)

            if (sim1Phone.isBlank() && sim2Phone.isBlank()) {
                XToastUtils.warning(R.string.forward_only_query_phone_empty)
                binding!!.tvQueryResultSim1.text = getString(R.string.forward_only_query_result_empty, sim1Label)
                binding!!.tvQueryResultSim2.text = getString(R.string.forward_only_query_result_empty, sim2Label)
                return@setOnClickListener
            }

            binding!!.btnQueryUser.isEnabled = false
            binding!!.btnQueryUser.setText(R.string.forward_only_querying)
            binding!!.tvQueryResultSim1.text = if (sim1Phone.isBlank()) {
                getString(R.string.forward_only_query_result_empty, sim1Label)
            } else {
                getString(R.string.forward_only_query_result_querying, sim1Label)
            }
            binding!!.tvQueryResultSim2.text = if (sim2Phone.isBlank()) {
                getString(R.string.forward_only_query_result_empty, sim2Label)
            } else {
                getString(R.string.forward_only_query_result_querying, sim2Label)
            }

            lifecycleScope.launch {
                val sim1Result = queryUserInfo(sim1Label, sim1Phone)
                val sim2Result = queryUserInfo(sim2Label, sim2Phone)

                binding!!.tvQueryResultSim1.text = sim1Result
                binding!!.tvQueryResultSim2.text = sim2Result
                binding!!.btnQueryUser.isEnabled = true
                binding!!.btnQueryUser.setText(R.string.forward_only_query)
            }
        }
    }

    private suspend fun queryUserInfo(label: String, phone: String): String {
        if (phone.isBlank()) {
            return getString(R.string.forward_only_query_result_empty, label)
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
                    return@withContext getString(R.string.forward_only_query_result_failed, label)
                }

                val json = JSONObject(body)
                val data = json.optJSONObject("data")
                val name = data?.optString("name").orEmpty()
                val userTypeDesc = data?.optString("userTypeDesc").orEmpty()
                if (!json.optBoolean("success", false) || name.isBlank() || userTypeDesc.isBlank()) {
                    return@withContext getString(R.string.forward_only_query_result_failed, label)
                }

                getString(R.string.forward_only_query_result_format, label, name, userTypeDesc)
            } catch (e: Exception) {
                getString(R.string.forward_only_query_result_failed, label)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun updatePhoneFieldsVisible(isVisible: Boolean) {
        binding!!.layoutPhoneFields.isVisible = isVisible
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
