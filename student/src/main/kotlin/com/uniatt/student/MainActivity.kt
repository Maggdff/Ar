package com.uniatt.student

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)
    private val scope = MainScope()
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** يحدّث الجدول والبيانات من الإدارة إن مضى [minGapMs] على آخر محاولة (لتفادي التكرار). */
    private fun refreshOnline(minGapMs: Long) {
        val st = StudentStore(this)
        if (st.credentials() == null || System.currentTimeMillis() - st.lastTry < minGapMs) return
        scope.launch { ResultBus.sync.value = "جارٍ تحديث الجدول…"; ResultBus.sync.value = OnlineSync.refreshNow(st) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = StudentStore(this)
        setContent { StudentApp(store, tick) }
    }

    override fun onResume() {
        super.onResume()
        tick++
        // إن كان التسجيل التلقائي مفعّلًا نتأكد أن الخدمة شغّالة (مثلًا بعد إعادة تشغيل الهاتف)
        StudentStore(this).let { st ->
            if (st.autoBle && st.credentials() != null && blePermissionsGranted(this)) AutoAttendService.start(this)
        }
        // تحديث الجدول والبيانات عند فتح التطبيق (مرة كل 5 دقائق كحد أدنى)، وفورًا كلما عاد الاتصال بالإنترنت وهو مفتوح
        refreshOnline(5 * 60_000L)
        if (netCallback == null) {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { runOnUiThread { refreshOnline(30_000L) } }
            }
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(cb)
                netCallback = cb
            } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        super.onPause()
        netCallback?.let { cb ->
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } catch (_: Exception) {}
            netCallback = null
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
