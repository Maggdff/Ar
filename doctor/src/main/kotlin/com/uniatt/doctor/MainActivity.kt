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
import com.uniatt.core.RelayDefaults
import com.uniatt.licensekit.BackupActions
import com.uniatt.licensekit.LicenseHost
import com.uniatt.licensekit.LicenseManager
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
        override fun onAvailable(network: Network) { runOnUiThread { refreshLicenseThenSync() } }
    }

    /** يتحقق من الترخيص أولًا (قد يكون المطوّر علّقه أو استعاده) ثم يزامن إن كان مسموحًا. */
    private fun refreshLicenseThenSync() {
        lifecycleScope.launch { LicenseManager.refresh(); vm.syncNow() }
    }

    /** يبقى التصدير والاستعادة متاحَين وقت إيقاف الترخيص: سجلات الحضور لا تُحذف أبدًا. */
    private val backupActions = object : BackupActions {
        override suspend fun export() = DoctorBackup.export(applicationContext)
        override suspend fun restore(bytes: ByteArray, code: String) = DoctorBackup.restore(applicationContext, bytes, code)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LicenseManager.ensure(applicationContext) { applicationContext.getSharedPreferences("doctor", 0).getString("relay", null) ?: RelayDefaults.URL }
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

        // إيقاف البلوتوث فورًا إن تعلّق الترخيص أثناء جلسة (لا يُستقبل حضور جديد)؛ الجلسة نفسها تبقى محفوظة
        lifecycleScope.launch {
            LicenseManager.verdict.collect { if (it.blocked) { ble.stop(); vm.bleState.value = "متوقف: الترخيص غير فعّال" } }
        }

        setContent { LicenseHost(backupActions) { DoctorApp(vm) } }
    }

    override fun onStart() {
        super.onStart()
        refreshLicenseThenSync()
        cm = getSystemService(ConnectivityManager::class.java)
        try { cm?.registerDefaultNetworkCallback(netCallback) } catch (_: Exception) {}
    }

    override fun onStop() {
        try { cm?.unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        super.onStop()
    }

    private fun startBle() {
        if (!LicenseManager.allowed()) { vm.bleState.value = "متوقف: الترخيص غير فعّال"; return }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val need = listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (need.isNotEmpty()) { blePerms.launch(need.toTypedArray()); return }
        }
        val err = ble.start()
        vm.bleState.value = err ?: "شغّال — يستقبل هواتف الطلاب تلقائيًا"
    }
}
