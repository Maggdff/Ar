package com.maggd.waipanalyzer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import com.maggd.waipanalyzer.ui.Actions
import com.maggd.waipanalyzer.ui.AppScreen
import com.maggd.waipanalyzer.ui.MainViewModel
import com.maggd.waipanalyzer.ui.WaTheme
import com.maggd.waipanalyzer.vpn.WaVpnService

class MainActivity : AppCompatActivity() {
    private val vm: MainViewModel by viewModels()

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) launchService()
        else com.maggd.waipanalyzer.data.CaptureRepository.setMessage(com.maggd.waipanalyzer.data.UiMessage(R.string.msg_vpn_denied))
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { requestVpn() }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actions = Actions(
            startCapture = ::beginStart,
            stopCapture = { startService(Intent(this, WaVpnService::class.java).setAction(WaVpnService.ACTION_STOP)) },
            pickFile = { picker.launch(arrayOf("*/*")) },
            setLanguage = { tag ->
                AppCompatDelegate.setApplicationLocales(
                    if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                )
            }
        )
        setContent { WaTheme { AppScreen(vm, actions) } }
    }

    private fun beginStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else requestVpn()
    }

    private fun requestVpn() {
        val i = VpnService.prepare(this)
        if (i != null) vpnPermission.launch(i) else launchService()
    }

    private fun launchService() {
        ContextCompat.startForegroundService(this, Intent(this, WaVpnService::class.java).setAction(WaVpnService.ACTION_START))
    }
}
