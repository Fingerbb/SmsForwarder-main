package cn.ppps.forwarder.fragment

import android.annotation.SuppressLint
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.view.isVisible
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import cn.ppps.forwarder.R
import cn.ppps.forwarder.core.BaseFragment
import cn.ppps.forwarder.databinding.FragmentForwardOnlyBinding
import cn.ppps.forwarder.utils.SMS_FORWARD_PREFIX
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.XToastUtils
import com.xuexiang.xpage.annotation.Page
import com.xuexiang.xui.widget.actionbar.TitleBar

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
