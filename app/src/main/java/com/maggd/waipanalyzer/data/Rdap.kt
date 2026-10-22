package com.maggd.waipanalyzer.data

import com.maggd.waipanalyzer.core.IpClass
import com.maggd.waipanalyzer.core.IpClassifier
import com.maggd.waipanalyzer.core.IpUtil
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Registry (RDAP) information about a public IP. The country is where the block is *registered*, not a physical location. */
class GeoInfo(val countryCode: String?, val org: String?, val network: String?, val cidrOrRange: String?) {
    fun countryName(locale: Locale = Locale.getDefault()): String? {
        val c = countryCode ?: return null
        if (c.length != 2) return c
        val n = Locale("", c.uppercase()).getDisplayCountry(locale)
        return if (n.isNullOrBlank()) c.uppercase() else "$n (${c.uppercase()})"
    }
}

/**
 * Looks up public addresses through RDAP (the registries' official, structured WHOIS replacement).
 * This is the only network request the app makes on its own, it is started by the user, and it only ever sends
 * a *public* IP address (private/reserved ranges are rejected locally).
 */
object RdapClient {
    private const val BASE = "https://rdap.org/ip/"

    fun lookup(ip: String): GeoInfo? {
        val bytes = IpUtil.toBytes(ip) ?: return null
        if (IpClassifier.classify(bytes) != IpClass.PUBLIC) return null
        val c = URL(BASE + ip).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.instanceFollowRedirects = true
            c.setRequestProperty("Accept", "application/rdap+json, application/json")
            if (c.responseCode != 200) return null
            val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            parse(text)
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    fun parse(json: String): GeoInfo? {
        val o = try { JSONObject(json) } catch (e: Exception) { return null }
        var country = o.optString("country").takeIf { it.isNotBlank() }
        val name = o.optString("name").takeIf { it.isNotBlank() }
        val start = o.optString("startAddress").takeIf { it.isNotBlank() }
        val end = o.optString("endAddress").takeIf { it.isNotBlank() }
        var org: String? = null
        val ents = o.optJSONArray("entities")
        if (ents != null) {
            var fallback: String? = null
            for (i in 0 until ents.length()) {
                val e = ents.optJSONObject(i) ?: continue
                val fn = vcardValue(e, "fn")
                val roles = e.optJSONArray("roles")?.let { r -> (0 until r.length()).map { r.optString(it) } } ?: emptyList()
                if (fn != null && org == null && ("registrant" in roles || "administrative" in roles)) org = fn
                if (fn != null && fallback == null) fallback = fn
                if (country == null) country = adrCountry(e)
            }
            if (org == null) org = fallback
        }
        if (country == null && org == null && name == null) return null
        return GeoInfo(country, org, name, if (start != null && end != null) "$start - $end" else null)
    }

    private fun vcard(e: JSONObject): JSONArray? = e.optJSONArray("vcardArray")?.optJSONArray(1)

    private fun vcardValue(e: JSONObject, key: String): String? {
        val v = vcard(e) ?: return null
        for (i in 0 until v.length()) {
            val item = v.optJSONArray(i) ?: continue
            if (item.optString(0) == key) return item.optString(3).takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun adrCountry(e: JSONObject): String? {
        val v = vcard(e) ?: return null
        for (i in 0 until v.length()) {
            val item = v.optJSONArray(i) ?: continue
            if (item.optString(0) != "adr") continue
            val label = item.optJSONObject(1)?.optString("label")
            val last = label?.trim()?.lines()?.lastOrNull()?.trim()
            if (!last.isNullOrBlank() && last.length <= 40) return last
            val parts = item.optJSONArray(3)
            val c = parts?.optString(6)
            if (!c.isNullOrBlank()) return c
        }
        return null
    }
}
