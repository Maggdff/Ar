package com.maggd.waipanalyzer.data

import android.content.ContentResolver
import android.net.Uri
import com.maggd.waipanalyzer.R
import com.maggd.waipanalyzer.core.AnalysisResult
import com.maggd.waipanalyzer.core.FlowTracker
import com.maggd.waipanalyzer.core.PcapReader
import com.maggd.waipanalyzer.core.PcapWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

class UiMessage(val resId: Int, val arg: String? = null)

data class AppState(
    val live: Boolean = false,
    val importing: Boolean = false,
    val fileName: String? = null,
    val result: AnalysisResult = AnalysisResult.EMPTY,
    val lookups: Map<String, GeoInfo> = emptyMap(),
    val lookingUp: Set<String> = emptySet(),
    val hasPcap: Boolean = false,
    val message: UiMessage? = null
)

/** Process-wide holder shared by the VPN service and the UI. Everything stays in memory / the app's private cache. */
object CaptureRepository {
    private val tracker = FlowTracker()
    private val geo = ConcurrentHashMap<String, GeoInfo>()
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state

    private val lock = Any()
    private var pcap: PcapWriter? = null
    @Volatile var pcapFile: File? = null
        private set

    fun beginLive(cacheDir: File, localAddress: String) {
        synchronized(lock) {
            closePcap()
            tracker.reset()
            tracker.localAddress = localAddress
            val f = File(cacheDir, "capture.pcap")
            pcapFile = f
            pcap = try { PcapWriter(FileOutputStream(f), PCAP_SNAPLEN) } catch (e: IOException) { null }
        }
        _state.update { AppState(live = true, lookups = geo.toMap()) }
    }

    fun onLivePacket(ts: Long, d: ByteArray, o: Int, l: Int) {
        tracker.onPacket(ts, d, o, l)
        synchronized(lock) { try { pcap?.write(ts, d, o, l) } catch (_: IOException) {} }
    }

    fun endLive() {
        synchronized(lock) { closePcap() }
        publish()
        _state.update { it.copy(live = false, hasPcap = (pcapFile?.length() ?: 0L) > 24L) }
    }

    fun publish() {
        val r = tracker.finish { ip -> geo[ip]?.let { "${it.org.orEmpty()} ${it.network.orEmpty()}" } }
        _state.update { it.copy(result = r, lookups = geo.toMap()) }
    }

    fun setMessage(m: UiMessage) = _state.update { it.copy(message = m) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    suspend fun importFile(cr: ContentResolver, uri: Uri, name: String?) = withContext(Dispatchers.IO) {
        if (_state.value.live) return@withContext
        synchronized(lock) { tracker.reset(); tracker.localAddress = null }
        _state.value = AppState(importing = true, fileName = name, lookups = geo.toMap())
        try {
            val input = cr.openInputStream(uri) ?: throw IOException("cannot open")
            input.buffered(1 shl 16).use { ins ->
                val r = PcapReader.read(ins) { ts, d, o, l -> tracker.onPacket(ts, d, o, l); true }
                tracker.setFormat(r.format, r.unsupported)
            }
            publish()
            _state.update { it.copy(importing = false, message = UiMessage(R.string.msg_import_done, it.result.packets.toString())) }
        } catch (e: Exception) {
            _state.update { it.copy(importing = false, message = UiMessage(R.string.msg_import_error, e.message ?: e.javaClass.simpleName)) }
        }
    }

    suspend fun lookup(ip: String) {
        if (geo.containsKey(ip) || ip in _state.value.lookingUp) return
        _state.update { it.copy(lookingUp = it.lookingUp + ip) }
        val g = withContext(Dispatchers.IO) { RdapClient.lookup(ip) }
        if (g != null) geo[ip] = g
        _state.update { it.copy(lookingUp = it.lookingUp - ip) }
        publish()
        if (g == null) _state.update { it.copy(message = UiMessage(R.string.msg_lookup_failed, ip)) }
    }

    private fun closePcap() {
        try { pcap?.close() } catch (_: IOException) {}
        pcap = null
    }

    private const val PCAP_SNAPLEN = 96
}
