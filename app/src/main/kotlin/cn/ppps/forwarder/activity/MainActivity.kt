package cn.ppps.forwarder.activity

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import cn.ppps.forwarder.core.BaseActivity
import cn.ppps.forwarder.databinding.ActivityMainBinding
import cn.ppps.forwarder.fragment.ForwardOnlyFragment
import com.xuexiang.xui.utils.WidgetUtils

@Suppress("DEPRECATION")
class MainActivity : BaseActivity<ActivityMainBinding?>() {

    override fun viewBindingInflate(inflater: LayoutInflater?): ActivityMainBinding {
        return ActivityMainBinding.inflate(inflater!!)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WidgetUtils.clearActivityBackground(this)
        switchPage(ForwardOnlyFragment::class.java)
    }

    override val isSupportSlideBack: Boolean
        get() = false

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addCategory(Intent.CATEGORY_HOME)
        startActivity(intent)
    }

    fun openMenu() {}

    fun closeMenu() {}

    fun isMenuOpen(): Boolean {
        return false
    }
}
