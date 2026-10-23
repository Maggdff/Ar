package com.uniatt.doctor

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: DoctorVM by viewModels()
    private lateinit var ble: BleDoctor
    private val blePerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) startBle() else vm.bleState.value = "صلاحية البلوتوث مرفوضة — اسمح بها من إعدادات التطبيق"
    }
    private var cm: ConnectivityManager? = null
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        // أول ما يتصل الهاتف بالإنترنت نزامن تلقائيًا (كشف جديد + رفع الحضور)
        override fun onAvailable(network: Network) { runOnUiThread { vm.syncNow() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SyncWorker.schedule(applicationContext)
        ble = BleDoctor(applicationContext, vm.evaluator, { vm.active.value }, vm::onScan, clock = { DoctorClock.nowMs(applicationContext) }) { vm.bleClients.value += 1 }

        // البلوتوث التلقائي + إبقاء الشاشة مضاءة أثناء الجلسة
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    combine(vm.active, vm.bleOn) { a, b -> (a != null) to b }.collect { (hasSession, bleWanted) ->
                        if (hasSession) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        if (hasSession && bleWanted) startBle()
                        else { ble.stop(); vm.bleState.value = if (hasSession) "متوقف" else "" }
                    }
                } finally { ble.stop(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }

        setContent { DoctorApp(vm) }
    }

    override fun onStart() {
        super.onStart()
        vm.syncNow()
        cm = getSystemService(ConnectivityManager::class.java)
        try { cm?.registerDefaultNetworkCallback(netCallback) } catch (_: Exception) {}
    }

    override fun onStop() {
        try { cm?.unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        super.onStop()
    }

    private fun startBle() {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val need = listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) { blePerms.launch(need.toTypedArray()); return }
        }
        val err = ble.start()
        vm.bleState.value = err ?: "شغّال — يستقبل هواتف الطلاب تلقائيًا"
    }
}
