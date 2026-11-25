package cc.szakacs.aurion.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class Reminder(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val triggerAt: Long,
    val createdAt: Long = System.currentTimeMillis(),
    /** true sobald die Benachrichtigung ausgelöst wurde */
    val fired: Boolean = false,
    /** true sobald per Button quittiert */
    val acknowledged: Boolean = false,
) {
    val notificationId: Int get() = id.hashCode() and 0x7fffffff

    fun toJson() = JSONObject()
        .put("id", id).put("text", text).put("triggerAt", triggerAt)
        .put("createdAt", createdAt).put("fired", fired).put("ack", acknowledged)

    companion object {
        fun fromJson(o: JSONObject) = Reminder(
            id = o.getString("id"),
            text = o.getString("text"),
            triggerAt = o.getLong("triggerAt"),
            createdAt = o.optLong("createdAt"),
            fired = o.optBoolean("fired"),
            acknowledged = o.optBoolean("ack"),
        )
    }
}

data class MemoryFact(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson() = JSONObject().put("id", id).put("text", text).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = MemoryFact(o.getString("id"), o.getString("text"), o.optLong("createdAt"))
    }
}

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val time: Long = System.currentTimeMillis(),
) {
    fun toJson() = JSONObject().put("u", fromUser).put("t", text).put("time", time)

    companion object {
        fun fromJson(o: JSONObject) = ChatMessage(o.getBoolean("u"), o.getString("t"), o.optLong("time"))
    }
}

/**
 * Einfacher, persistenter Speicher (JSON-Dateien im App-Verzeichnis).
 * Hält Erinnerungen, Gedächtnis-Einträge und den Gesprächsverlauf.
 */
class Store private constructor(private val dir: File) {

    private val remindersFile = File(dir, "reminders.json")
    private val memoryFile = File(dir, "memory.json")
    private val chatFile = File(dir, "chat.json")

    private val _reminders = MutableStateFlow(loadList(remindersFile) { Reminder.fromJson(it) })
    val reminders: StateFlow<List<Reminder>> = _reminders

    private val _memory = MutableStateFlow(loadList(memoryFile) { MemoryFact.fromJson(it) })
    val memory: StateFlow<List<MemoryFact>> = _memory

    private val _chat = MutableStateFlow(loadList(chatFile) { ChatMessage.fromJson(it) })
    val chat: StateFlow<List<ChatMessage>> = _chat

    // ---------- Erinnerungen ----------
    @Synchronized
    fun upsertReminder(r: Reminder) {
        val list = _reminders.value.filterNot { it.id == r.id } + r
        _reminders.value = list.sortedBy { it.triggerAt }
        saveList(remindersFile, _reminders.value) { it.toJson() }
    }

    fun reminder(id: String): Reminder? = _reminders.value.firstOrNull { it.id == id }

    @Synchronized
    fun deleteReminder(id: String) {
        _reminders.value = _reminders.value.filterNot { it.id == id }
        saveList(remindersFile, _reminders.value) { it.toJson() }
    }

    @Synchronized
    fun clearAcknowledged() {
        _reminders.value = _reminders.value.filterNot { it.acknowledged }
        saveList(remindersFile, _reminders.value) { it.toJson() }
    }

    // ---------- Gedächtnis ----------
    @Synchronized
    fun addMemory(text: String): MemoryFact {
        val f = MemoryFact(text = text.trim())
        _memory.value = _memory.value + f
        saveList(memoryFile, _memory.value) { it.toJson() }
        return f
    }

    @Synchronized
    fun deleteMemory(id: String) {
        _memory.value = _memory.value.filterNot { it.id == id }
        saveList(memoryFile, _memory.value) { it.toJson() }
    }

    /** Löscht Einträge, die den Suchbegriff enthalten. Gibt Anzahl zurück. */
    @Synchronized
    fun forgetMatching(term: String): Int {
        val t = term.lowercase().trim()
        if (t.isEmpty()) return 0
        val before = _memory.value.size
        _memory.value = _memory.value.filterNot { it.text.lowercase().contains(t) }
        saveList(memoryFile, _memory.value) { it.toJson() }
        return before - _memory.value.size
    }

    // ---------- Chat ----------
    @Synchronized
    fun addChat(msg: ChatMessage) {
        _chat.value = (_chat.value + msg).takeLast(200)
        saveList(chatFile, _chat.value) { it.toJson() }
    }

    @Synchronized
    fun clearChat() {
        _chat.value = emptyList()
        saveList(chatFile, emptyList<ChatMessage>()) { it.toJson() }
    }

    // ---------- Helfer ----------
    private fun <T> loadList(f: File, parse: (JSONObject) -> T): List<T> = try {
        if (!f.exists()) emptyList() else {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i -> runCatching { parse(arr.getJSONObject(i)) }.getOrNull() }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun <T> saveList(f: File, list: List<T>, toJson: (T) -> JSONObject) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
    }

    companion object {
        @Volatile private var instance: Store? = null
        fun get(ctx: Context): Store = instance ?: synchronized(this) {
            instance ?: Store(ctx.applicationContext.filesDir).also { instance = it }
        }
    }
}
