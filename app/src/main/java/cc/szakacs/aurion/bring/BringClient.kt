package cc.szakacs.aurion.bring

import cc.szakacs.aurion.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class BringList(val uuid: String, val name: String)
data class BringItem(val name: String, val spec: String)

class BringException(msg: String) : Exception(msg)

/**
 * Minimaler Client für die (inoffizielle) Bring!-API – dieselbe Schnittstelle,
 * die auch die Web-App und die Home-Assistant-Integration verwenden.
 */
class BringClient(private val settings: Settings) {

    private val base = "https://api.getbring.com/rest/"

    val isConfigured: Boolean
        get() = !settings.bringEmail.isNullOrBlank() && !settings.bringPassword.isNullOrBlank()

    suspend fun login(email: String, password: String): List<BringList> = withContext(Dispatchers.IO) {
        val body = form("email" to email, "password" to password)
        val res = request("POST", "v2/bringauth", body, auth = false)
        val o = JSONObject(res)
        settings.bringEmail = email
        settings.bringPassword = password
        settings.bringUserUuid = o.getString("uuid")
        settings.bringToken = o.getString("access_token")
        settings.bringTokenExpiry = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000 - 60_000
        val defaultList = o.optString("bringListUUID", "")
        val lists = loadListsInternal()
        if (settings.bringListUuid == null) {
            val chosen = lists.firstOrNull { it.uuid == defaultList } ?: lists.firstOrNull()
            settings.bringListUuid = chosen?.uuid
            settings.bringListName = chosen?.name
        }
        lists
    }

    suspend fun lists(): List<BringList> = withContext(Dispatchers.IO) {
        ensureLogin()
        loadListsInternal()
    }

    suspend fun addItems(items: List<BringItem>) = withContext(Dispatchers.IO) {
        ensureLogin()
        val list = settings.bringListUuid ?: throw BringException("Keine Bring!-Liste gewählt")
        for (item in items) {
            val body = form(
                "uuid" to list,
                "purchase" to item.name,
                "specification" to item.spec,
                "recently" to "",
                "remove" to "",
                "sender" to "null",
            )
            request("PUT", "v2/bringlists/$list", body, auth = true)
        }
    }

    suspend fun removeItem(name: String) = withContext(Dispatchers.IO) {
        ensureLogin()
        val list = settings.bringListUuid ?: throw BringException("Keine Bring!-Liste gewählt")
        val body = form("uuid" to list, "purchase" to "", "recently" to "", "remove" to name, "specification" to "", "sender" to "null")
        request("PUT", "v2/bringlists/$list", body, auth = true)
    }

    suspend fun items(): List<BringItem> = withContext(Dispatchers.IO) {
        ensureLogin()
        val list = settings.bringListUuid ?: throw BringException("Keine Bring!-Liste gewählt")
        val o = JSONObject(request("GET", "v2/bringlists/$list", null, auth = true))
        val arr = o.optJSONArray("purchase") ?: return@withContext emptyList()
        (0 until arr.length()).map {
            val i = arr.getJSONObject(it)
            BringItem(i.optString("name"), i.optString("specification"))
        }
    }

    private fun loadListsInternal(): List<BringList> {
        val uuid = settings.bringUserUuid ?: throw BringException("Nicht angemeldet")
        val o = JSONObject(request("GET", "bringusers/$uuid/lists", null, auth = true))
        val arr = o.getJSONArray("lists")
        return (0 until arr.length()).map {
            val l = arr.getJSONObject(it)
            BringList(l.getString("listUuid"), l.optString("name", "Liste"))
        }
    }

    private suspend fun ensureLogin() {
        if (!isConfigured) throw BringException("Bring! ist noch nicht verbunden")
        if (settings.bringToken == null || System.currentTimeMillis() > settings.bringTokenExpiry) {
            login(settings.bringEmail!!, settings.bringPassword!!)
        }
    }

    private fun request(method: String, path: String, body: String?, auth: Boolean, retry: Boolean = true): String {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("X-BRING-API-KEY", API_KEY)
            setRequestProperty("X-BRING-CLIENT", "webApp")
            setRequestProperty("X-BRING-CLIENT-SOURCE", "webApp")
            setRequestProperty("X-BRING-COUNTRY", "AT")
            setRequestProperty("Accept", "application/json")
            if (auth) {
                setRequestProperty("Authorization", "Bearer ${settings.bringToken}")
                setRequestProperty("X-BRING-USER-UUID", settings.bringUserUuid ?: "")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        val code = conn.responseCode
        if (code == 401 && auth && retry && isConfigured) {
            conn.disconnect()
            settings.bringToken = null
            // Token abgelaufen -> neu anmelden und einmal wiederholen
            kotlinx.coroutines.runBlocking { login(settings.bringEmail!!, settings.bringPassword!!) }
            return request(method, path, body, auth, retry = false)
        }
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) {
            throw BringException(if (code == 401) "Bring!-Anmeldung fehlgeschlagen" else "Bring! Fehler $code")
        }
        return text
    }

    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }

    companion object {
        private const val API_KEY = "cof4Nc6D8saplXjE3h3HXqHH8m7VU2i1Gs0g85Sp"
    }
}
