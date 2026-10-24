package com.uniatt.admin

import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.uniatt.licensekit.BackupActions
import com.uniatt.licensekit.LicenseHost
import com.uniatt.licensekit.LicenseManager
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AdminVM by viewModels()
    private var cm: ConnectivityManager? = null
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        // أول ما يتصل الهاتف بالإنترنت (واي فاي/بيانات) نزامن تلقائيًا
        override fun onAvailable(network: Network) { runOnUiThread { refreshLicenseThenSync() } }
    }

    /** يتحقق من الترخيص أولًا (قد يكون المطوّر علّقه أو استعاده) ثم يزامن إن كان مسموحًا. */
    private fun refreshLicenseThenSync() {
        lifecycleScope.launch { LicenseManager.refresh(); vm.syncNow() }
    }

    /** يبقى التصدير والاستعادة متاحَين وقت إيقاف الترخيص: بياناتك لا تُحذف أبدًا. */
    private val backupActions = object : BackupActions {
        override suspend fun export() = vm.backup()
        override suspend fun restore(bytes: ByteArray, code: String) = vm.restoreNow(bytes, code)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LicenseManager.ensure(applicationContext) { AdminStore(applicationContext).load().effectiveRelay }
        SyncWorker.schedule(applicationContext)
        setContent { LicenseHost(backupActions) { AdminApp(vm) } }
    }

    override fun onStart() {
        super.onStart()
        vm.refresh()
        refreshLicenseThenSync()
        cm = getSystemService(ConnectivityManager::class.java)
        try { cm?.registerDefaultNetworkCallback(netCallback) } catch (_: Exception) {}
    }

    override fun onStop() {
        try { cm?.unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        super.onStop()
    }
}
