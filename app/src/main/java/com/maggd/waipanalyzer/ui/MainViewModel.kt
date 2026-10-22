package com.maggd.waipanalyzer.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.maggd.waipanalyzer.R
import com.maggd.waipanalyzer.core.Confidence
import com.maggd.waipanalyzer.core.IpClass
import com.maggd.waipanalyzer.core.Role
import com.maggd.waipanalyzer.data.AppState
import com.maggd.waipanalyzer.data.CaptureRepository
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val state: StateFlow<AppState> = CaptureRepository.state

    fun importFile(uri: Uri) {
        val cr = getApplication<Application>().contentResolver
        val name = try {
            cr.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) c.getString(i) else null
            }
        } catch (e: Exception) { null }
        viewModelScope.launch { CaptureRepository.importFile(cr, uri, name) }
    }

    fun lookup(ip: String) { viewModelScope.launch { CaptureRepository.lookup(ip) } }

    fun lookupAll() {
        val targets = state.value.result.endpoints.filter { it.ipClass == IpClass.PUBLIC && it.role != Role.DNS }.map { it.ip }
        viewModelScope.launch { targets.take(25).forEach { CaptureRepository.lookup(it) } }
    }

    fun clearMessage() = CaptureRepository.clearMessage()

    /** Plain-text report (localised with the current app language) that the user can copy or share. */
    fun report(ctx: Context): String {
        val s = state.value
        val r = s.result
        val sb = StringBuilder()
        sb.appendLine(ctx.getString(R.string.app_name))
        sb.appendLine("Developed by Maggd Alosimi")
        sb.appendLine()
        sb.appendLine(ctx.getString(R.string.report_summary, r.packets.toString(), (r.durationMs / 1000).toString()))
        r.localAddress?.let { sb.appendLine(ctx.getString(R.string.report_local, it)) }
        if (r.selfPublicAddresses.isNotEmpty()) sb.appendLine(ctx.getString(R.string.report_self, r.selfPublicAddresses.joinToString(", ")))
        sb.appendLine(ctx.getString(if (r.callLikely) R.string.call_detected else R.string.call_not_detected))
        sb.appendLine()
        for (e in r.endpoints) {
            sb.appendLine("${e.ip}  [${ctx.getString(roleRes(e.role))} / ${ctx.getString(confRes(e.confidence))}]")
            sb.appendLine("  ${e.protocols.joinToString("/")}  ${ctx.getString(R.string.label_ports)}: ${e.ports.joinToString(",")}")
            sb.appendLine("  ${ctx.getString(R.string.label_traffic)}: ↑${e.pktsOut} ↓${e.pktsIn} pkts, ${Fmt.bytes(e.bytes)}")
            if (e.hostnames.isNotEmpty()) sb.appendLine("  ${ctx.getString(R.string.label_hosts)}: ${e.hostnames.joinToString(", ")}")
            s.lookups[e.ip]?.let { g ->
                g.countryName(Locale.getDefault())?.let { sb.appendLine("  ${ctx.getString(R.string.label_country)}: $it") }
                g.org?.let { sb.appendLine("  ${ctx.getString(R.string.label_isp)}: $it") }
            }
            e.reasons.forEach { sb.appendLine("  - ${ctx.getString(reasonRes(it))}") }
        }
        sb.appendLine()
        sb.appendLine(ctx.getString(R.string.disclaimer_body))
        return sb.toString()
    }

    companion object {
        fun roleRes(r: Role) = when (r) {
            Role.WHATSAPP_SERVER -> R.string.role_whatsapp_server
            Role.WHATSAPP_RELAY -> R.string.role_whatsapp_relay
            Role.PEER_CANDIDATE -> R.string.role_peer
            Role.DNS -> R.string.role_dns
            Role.LOCAL_NETWORK -> R.string.role_local
            Role.OTHER -> R.string.role_other
        }
        fun confRes(c: Confidence) = when (c) {
            Confidence.HIGH -> R.string.conf_high
            Confidence.MEDIUM -> R.string.conf_medium
            Confidence.LOW -> R.string.conf_low
        }
        fun reasonRes(r: com.maggd.waipanalyzer.core.Reason) = when (r) {
            com.maggd.waipanalyzer.core.Reason.META_RANGE -> R.string.reason_meta_range
            com.maggd.waipanalyzer.core.Reason.META_HOSTNAME -> R.string.reason_meta_hostname
            com.maggd.waipanalyzer.core.Reason.META_ORG -> R.string.reason_meta_org
            com.maggd.waipanalyzer.core.Reason.UDP_BIDIRECTIONAL -> R.string.reason_udp_bidirectional
            com.maggd.waipanalyzer.core.Reason.STUN_SEEN -> R.string.reason_stun_seen
            com.maggd.waipanalyzer.core.Reason.STUN_BIDIRECTIONAL -> R.string.reason_stun_bidirectional
            com.maggd.waipanalyzer.core.Reason.RTP_LIKE -> R.string.reason_rtp_like
            com.maggd.waipanalyzer.core.Reason.RTP_BIDIRECTIONAL -> R.string.reason_rtp_bidirectional
            com.maggd.waipanalyzer.core.Reason.HIGH_VOLUME -> R.string.reason_high_volume
            com.maggd.waipanalyzer.core.Reason.NON_META_PUBLIC -> R.string.reason_non_meta_public
            com.maggd.waipanalyzer.core.Reason.DNS_PORT -> R.string.reason_dns_port
            com.maggd.waipanalyzer.core.Reason.NON_PUBLIC -> R.string.reason_non_public
        }
    }
}

object Fmt {
    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", n / 1024.0)
        else -> String.format(Locale.US, "%.2f MB", n / 1048576.0)
    }
    fun duration(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }
}
