package com.sammyi57.pdftools

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

// Two-way Google Drive sync for the app's saved PDFs, run whenever a file changes.
// Everything saved on the phone goes up to a "PDF Tools" folder in the user's Drive (files plus one level of folders),
// and edits, renames, moves and deletions on both sides go both ways. Files only in Drive stay there until the user
// downloads them, so the app never fills the phone on its own. Uses the drive.file scope, so the app only sees what it
// created. Each run is a three-way compare of phone, Drive and the last sync.

private const val API = "https://www.googleapis.com/drive/v3"
private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
private const val ROOT = "PDF Tools"
private const val FOLDER_MIME = "application/vnd.google-apps.folder"

@Serializable
data class Synced(val id: String, val driveModified: String, val localModified: Long, val size: Long)

@Serializable
data class BackupState(
  val enabled: Boolean = false,
  val email: String? = null,
  val folderId: String? = null,
  val entries: Map<String, Synced> = emptyMap(), // "name.pdf" or "Folder/name.pdf" -> state after the last sync
  val folders: Map<String, String> = emptyMap(), // local folder name -> Drive folder id, as of the last sync
  val driveOnly: Map<String, Boolean> = emptyMap(), // deleted from this phone only: kept in Drive, not downloaded again
  val lastSync: Long? = null,
  val error: String? = null, // shown in Settings and Files; cleared by the next successful sync
)

val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; encodeDefaults = true }

data class DriveFile(val id: String, val name: String, val folder: String, val size: Long, val modified: Long, val modifiedTime: String)
data class DriveListing(val folders: List<String>, val files: List<DriveFile>)

private class DriveError(val status: Int, body: String) : Exception("Google Drive error $status: ${body.take(200)}")

object Backup {
  private val file get() = File(appContext.filesDir, "drive-backup.json")
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  var state by mutableStateOf(read())
    private set
  var syncing by mutableStateOf(false)
    private set
  var listing by mutableStateOf<Pair<String, DriveListing>?>(null) // the last listing of the Drive folder, by account
    private set

  private fun read(): BackupState = try {
    if (file.exists()) json.decodeFromString<BackupState>(file.readText()) else BackupState()
  } catch (_: Exception) {
    BackupState()
  }

  @Synchronized
  fun update(fn: (BackupState) -> BackupState) {
    val next = fn(state)
    state = next
    try { file.writeText(json.encodeToString(next)) } catch (_: Exception) {}
  }

  // ── Drive API ───────────────────────────────────────────────────────────

  // HttpURLConnection can't send PATCH; Google's APIs take the method from this header instead.
  private fun open(token: String, url: String, method: String): HttpURLConnection =
    (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = 30_000
      readTimeout = 60_000
      setRequestProperty("Authorization", "Bearer $token")
      if (method == "PATCH") {
        requestMethod = "POST"
        setRequestProperty("X-HTTP-Method-Override", "PATCH")
      } else requestMethod = method
    }

  private fun HttpURLConnection.text(): String {
    val ok = responseCode in 200..299
    val body = (if (ok) inputStream else errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
    if (!ok) throw DriveError(responseCode, body)
    return body
  }

  private fun call(token: String, url: String, method: String = "GET", body: JSONObject? = null): JSONObject {
    val conn = open(token, url, method)
    try {
      if (body != null) {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
      }
      return conn.text().let { if (it.isBlank()) JSONObject() else JSONObject(it) }
    } finally {
      conn.disconnect()
    }
  }

  // File transfers get three tries: on a patchy connection one stalled download used to fail the whole sync.
  private suspend fun <T> retry(fn: () -> T): T {
    var attempt = 1
    while (true) {
      try {
        return fn()
      } catch (e: IOException) {
        if (attempt >= 3) throw e
        delay(2000L * attempt++)
      }
    }
  }

  private data class Item(val id: String, val name: String, val mime: String, val size: Long, val md5: String?, val modified: String)

  private fun listChildren(token: String, parent: String): List<Item> {
    val q = URLEncoder.encode("'$parent' in parents and trashed=false", "UTF-8")
    val out = ArrayList<Item>()
    var page = ""
    do {
      val res = call(token, "$API/files?q=$q&pageSize=1000&fields=nextPageToken,files(id,name,mimeType,size,md5Checksum,modifiedTime)" +
        if (page.isNotEmpty()) "&pageToken=$page" else "")
      val files = res.optJSONArray("files")
      for (i in 0 until (files?.length() ?: 0)) {
        val f = files!!.getJSONObject(i)
        out.add(Item(f.getString("id"), f.getString("name"), f.getString("mimeType"), f.optString("size", "0").toLongOrNull() ?: 0, f.optString("md5Checksum").ifEmpty { null }, f.getString("modifiedTime")))
      }
      page = res.optString("nextPageToken", "")
    } while (page.isNotEmpty())
    return out
  }

  private fun ensureRoot(token: String, known: String?): String {
    if (known != null) {
      try {
        if (!call(token, "$API/files/$known?fields=id,trashed").optBoolean("trashed")) return known
      } catch (e: DriveError) {
        if (e.status != 404) throw e
      }
    }
    val q = URLEncoder.encode("name='$ROOT' and mimeType='$FOLDER_MIME' and 'root' in parents and trashed=false", "UTF-8")
    val found = call(token, "$API/files?q=$q&fields=files(id)").optJSONArray("files")
    if (found != null && found.length() > 0) return found.getJSONObject(0).getString("id")
    return call(token, "$API/files?fields=id", "POST", JSONObject().put("name", ROOT).put("mimeType", FOLDER_MIME)).getString("id")
  }

  private fun createFolderInDrive(token: String, parent: String, name: String): String =
    call(token, "$API/files?fields=id", "POST", JSONObject().put("name", name).put("mimeType", FOLDER_MIME).put("parents", org.json.JSONArray().put(parent))).getString("id")

  // Moves to Drive's Trash (recoverable for 30 days), never a permanent delete.
  private fun trashInDrive(token: String, id: String) {
    try {
      call(token, "$API/files/$id", "PATCH", JSONObject().put("trashed", true))
    } catch (e: DriveError) {
      if (e.status != 404) throw e
    }
  }

  // Streams a local file into Drive: into `id` when it exists, otherwise a new file under `parent`.
  private suspend fun uploadToDrive(token: String, f: File, name: String, parent: String, existing: String? = null, mime: String = "application/pdf"): Pair<String, String> {
    val id = existing ?: call(token, "$API/files?fields=id", "POST", JSONObject().put("name", name).put("mimeType", mime).put("parents", org.json.JSONArray().put(parent))).getString("id")
    val meta = retry {
      val conn = open(token, "$UPLOAD/files/$id?uploadType=media&fields=id,modifiedTime,size", "PATCH")
      try {
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", mime)
        conn.setFixedLengthStreamingMode(f.length())
        conn.outputStream.use { out -> f.inputStream().use { it.copyTo(out, 256 * 1024) } }
        JSONObject(conn.text())
      } finally {
        conn.disconnect()
      }
    }
    return meta.getString("id") to meta.getString("modifiedTime")
  }

  // `modified` (Drive's modifiedTime) becomes the file's date, so Files sorts it by when it was made, not downloaded.
  private suspend fun downloadFromDrive(token: String, id: String, dest: File, modified: String? = null) {
    dest.parentFile?.mkdirs()
    val tmp = File(appContext.cacheDir, "drive-$id-${System.nanoTime()}.part") // per download: two of one file can't share it
    try {
      retry {
        val conn = open(token, "$API/files/$id?alt=media", "GET")
        try {
          if (conn.responseCode !in 200..299) throw DriveError(conn.responseCode, "")
          conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 256 * 1024) } }
        } finally {
          conn.disconnect()
        }
      }
      if (dest.exists()) dest.delete() // only replaced once the download fully succeeded
      if (!tmp.renameTo(dest)) tmp.copyTo(dest, true)
      modified?.let { dest.setLastModified(parseTime(it)) }
    } finally {
      tmp.delete() // a failed download leaves no partial file behind
    }
  }

  private fun parseTime(iso: String): Long = try { java.time.Instant.parse(iso).toEpochMilli() } catch (_: Exception) { 0L }

  /** Sync and Drive errors in words people can act on, instead of raw exceptions or API bodies. */
  fun describe(e: Throwable): String = when {
    e is DriveError && e.status == 401 -> appContext.getString(R.string.error_drive_signin_expired)
    e is DriveError && (e.status == 403 || e.status == 429) -> appContext.getString(R.string.error_drive_rate_limited)
    e is DriveError -> appContext.getString(R.string.error_drive_problem, e.status)
    e is IOException -> appContext.getString(R.string.error_drive_unreachable)
    else -> e.message ?: appContext.getString(R.string.error_something_went_wrong)
  }

  // ── Local side ──────────────────────────────────────────────────────────

  private class Local(val file: File, val size: Long, val modified: Long)

  private fun isPdf(name: String) = name.lowercase().endsWith(".pdf")
  private fun splitRel(rel: String): Pair<String, String> = rel.indexOf('/').let { if (it < 0) "" to rel else rel.substring(0, it) to rel.substring(it + 1) }
  private fun localFile(rel: String): File = splitRel(rel).let { (dir, name) -> if (dir.isEmpty()) File(rootFolder(), name) else File(File(rootFolder(), dir), name) }
  private fun md5(f: File): String = MessageDigest.getInstance("MD5").let { d -> f.inputStream().use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; d.update(b, 0, n) } }; d.digest().joinToString("") { "%02x".format(it) } }

  private fun scanLocal(): Pair<MutableMap<String, Local>, Set<String>> {
    val files = HashMap<String, Local>()
    val folders = HashSet<String>()
    for (item in rootFolder().listFiles() ?: emptyArray()) {
      if (item.isFile) {
        if (isPdf(item.name)) files[item.name] = Local(item, item.length(), item.lastModified())
      } else {
        folders.add(item.name)
        for (f in item.listFiles() ?: emptyArray()) if (f.isFile && isPdf(f.name)) files["${item.name}/${f.name}"] = Local(f, f.length(), f.lastModified())
      }
    }
    return files to folders
  }

  // ── Sync ────────────────────────────────────────────────────────────────

  private var running: Deferred<Unit>? = null

  // Files downloaded from Files → Google Drive while a sync may be running. That sync listed the phone before they
  // arrived, so it links them as they are and leaves them alone, instead of the download waiting for it to finish.
  // Typed as a Kotlin Map so `in` checks keys (ConcurrentHashMap.contains looks at values).
  private val justDownloaded: MutableMap<String, Synced> = java.util.concurrent.ConcurrentHashMap()
  @Volatile private var again = false
  private var timer: Job? = null

  /** Call after any change to saved files: debounced, and coalesced with a sync already in progress. */
  fun syncSoon() {
    if (!state.enabled) return
    timer?.cancel()
    timer = scope.launch { delay(2000); syncNow() }
  }

  /** Runs a full two-way sync now. Concurrent calls share one run; changes made meanwhile trigger one more. */
  fun syncNow(): Deferred<Unit> = synchronized(this) {
    running?.also { again = true } ?: scope.async {
      var done = false
      try {
        while (!done) {
          again = false
          syncOnce()
          // "Again?" and "finished" are one step under the lock, so a change arriving as a run ends either reruns it or
          // starts a new run: never lost in between.
          done = synchronized(this@Backup) { if (again) false else { running = null; syncing = false; true } }
        }
      } finally {
        if (!done) synchronized(this@Backup) { running = null; syncing = false }
      }
    }.also { running = it }
  }

  private suspend fun <T> withToken(email: String, fn: suspend (String) -> T): T {
    val token = GoogleAuth.token(email, false)
    return try {
      fn(token)
    } catch (e: DriveError) {
      if (e.status != 401) throw e
      GoogleAuth.clearToken(token) // expired: drop it from Google's cache and retry once
      fn(GoogleAuth.token(email, false))
    }
  }

  private suspend fun syncOnce() {
    val start = state
    val email = start.email
    if (!start.enabled || email == null) return
    syncing = true
    // Every write is dropped if the account changed while this run was working.
    fun mine(fn: (BackupState) -> BackupState) = update { x -> if (x.email == email) fn(x) else x }
    try {
      withToken(email) { token -> reconcile(token, email, ::mine) }
      mine { it.copy(lastSync = System.currentTimeMillis(), error = null) }
    } catch (e: Exception) {
      android.util.Log.w("PdfTools", "Drive sync failed", e) // the words shown are simplified; logcat keeps the cause
      mine { it.copy(error = describe(e)) }
    }
    withContext(Dispatchers.Main) { Store.refreshOnly() }
  }

  private class Remote(val id: String, val size: Long, val md5: String?, val modified: String, val parent: String)

  private suspend fun reconcile(token: String, email: String, mine: ((BackupState) -> BackupState) -> Unit) {
    justDownloaded.clear() // anything finished by now is already in the state read next and on disk for scanLocal
    val s = state
    val rootId = ensureRoot(token, s.folderId)
    val fresh = rootId != s.folderId
    if (fresh) mine { it.copy(folderId = rootId, entries = emptyMap(), folders = emptyMap(), driveOnly = emptyMap()) } // relink from scratch
    val entries = if (fresh) HashMap() else HashMap(s.entries)
    val syncedFolders = if (fresh) HashMap() else HashMap(s.folders)
    val driveOnly = if (fresh) HashMap() else HashMap(s.driveOnly)
    val cleared = HashSet<String>() // "Drive only" marks this run removed
    // Merges with the saved state, so a "delete from this phone only" made while this run is working isn't lost.
    val save = {
      mine { x ->
        for ((rel, link) in justDownloaded) {
          entries[rel] = link
          driveOnly.remove(rel)
        }
        for (rel in x.driveOnly.keys) {
          if (rel in justDownloaded) continue
          if (rel in cleared) continue
          driveOnly[rel] = true
          entries.remove(rel)
        }
        x.copy(entries = entries.toMap(), folders = syncedFolders.toMap(), driveOnly = driveOnly.toMap())
      }
    }
    val stillMine = { state.email == email }

    // Drive: files at the top level, folders, and the files inside each folder.
    val remote = LinkedHashMap<String, Remote>()
    val remoteFolders = LinkedHashMap<String, String>()
    for (item in listChildren(token, rootId)) {
      if (item.mime == FOLDER_MIME) remoteFolders.putIfAbsent(item.name, item.id)
      else if (isPdf(item.name) && item.name !in remote) remote[item.name] = Remote(item.id, item.size, item.md5, item.modified, rootId)
    }
    for ((folder, id) in remoteFolders.toList()) {
      for (item in listChildren(token, id)) {
        val rel = "$folder/${item.name}"
        if (item.mime != FOLDER_MIME && isPdf(item.name) && rel !in remote) remote[rel] = Remote(item.id, item.size, item.md5, item.modified, id)
      }
    }
    val (localFiles, localFolders) = scanLocal()

    // Folders: a new one on the phone is made in Drive too; one only in Drive stays there, and appears on the phone
    // when a file in it is downloaded. A folder deleted on one side goes once it's empty on the other.
    for (folder in localFolders + remoteFolders.keys + syncedFolders.keys) {
      val onPhone = folder in localFolders
      val inDrive = folder in remoteFolders
      if (onPhone && !inDrive && folder !in syncedFolders) {
        val id = createFolderInDrive(token, rootId, folder)
        remoteFolders[folder] = id
        syncedFolders[folder] = id
      } else if (onPhone && inDrive) {
        syncedFolders[folder] = remoteFolders.getValue(folder)
      }
    }
    save()

    val parentFor = { rel: String ->
      val folder = splitRel(rel).first
      if (folder.isEmpty()) rootId else remoteFolders[folder] ?: createFolderInDrive(token, rootId, folder).also { remoteFolders[folder] = it; syncedFolders[folder] = it }
    }
    val record = { rel: String, id: String, driveModified: String, f: File ->
      entries[rel] = Synced(id, driveModified, f.lastModified(), f.length())
      val prev = remote[rel] // kept current, so the listing this run leaves behind matches Drive
      remote[rel] = Remote(id, f.length(), prev?.md5, driveModified, prev?.parent ?: "")
      save()
    }
    val localChanged = { rel: String -> localFiles.getValue(rel).let { it.modified != entries.getValue(rel).localModified || it.size != entries.getValue(rel).size } }

    // Renames and moves in Drive: a known file id now sits under a different name or folder.
    val remoteById = remote.entries.associate { (rel, r) -> r.id to rel }
    for ((rel, e) in entries.toMap()) {
      val now = remoteById[e.id]
      if (now == null || now == rel || rel in remote || now in localFiles || rel !in localFiles || localChanged(rel)) continue
      val src = localFiles.getValue(rel).file
      val dest = localFile(now)
      dest.parentFile?.mkdirs()
      src.renameTo(dest)
      localFiles.remove(rel)
      localFiles[now] = Local(dest, dest.length(), dest.lastModified())
      entries.remove(rel)
      record(now, e.id, remote.getValue(now).modified, dest)
    }

    // Renames and moves on the phone: a new file with exactly the same content (MD5) as one missing from its old spot
    // is moved in Drive instead of uploading a copy and trashing the original.
    val gone = entries.entries.filter { (rel, _) -> rel !in localFiles && rel !in justDownloaded && remote[rel]?.md5 != null }.map { it.key to it.value }.toMutableList()
    for ((rel, l) in localFiles.toMap()) {
      if (rel in entries || rel in remote) continue
      val sum by lazy { md5(l.file) }
      val same = gone.filter { (old, e) -> e.size == l.size && remote.getValue(old).md5 == sum }
      val match = same.find { (old) -> splitRel(old).second == splitRel(rel).second } ?: same.firstOrNull() ?: continue
      val (oldRel, e) = match
      gone.remove(match)
      val parent = parentFor(rel)
      val oldParent = remote.getValue(oldRel).parent
      val moved = call(token, "$API/files/${e.id}?fields=id,modifiedTime&addParents=$parent&removeParents=$oldParent", "PATCH", JSONObject().put("name", splitRel(rel).second))
      remote.remove(oldRel)
      remote[rel] = Remote(e.id, l.size, null, moved.getString("modifiedTime"), parent)
      entries.remove(oldRel)
      record(rel, e.id, moved.getString("modifiedTime"), l.file)
    }

    // Deletions made in Drive are applied here only if they look deliberate: many files vanishing at once (a trashed
    // folder, another app cleaning up) pauses deletes on the phone instead of wiping it.
    val missingInDrive = entries.keys.filter { it !in remote && it in localFiles }
    val allowDriveDeletes = missingInDrive.size <= maxOf(5, entries.size / 2)

    for (rel in (localFiles.keys + remote.keys + entries.keys + driveOnly.keys).toSet()) {
      if (!stillMine()) return
      if (rel in justDownloaded) continue // arrived after this run looked at the phone: already linked
      val l = localFiles[rel]
      val r = remote[rel]
      val e = entries[rel]

      if (driveOnly[rel] == true) { // deleted from this phone only
        if (l != null || r == null) { driveOnly.remove(rel); cleared.add(rel) } // back on the phone, or gone from Drive
        if (l == null) { save(); continue } // still only in Drive: leave it there, don't download
      }

      when {
        l != null && r != null && e != null -> {
          val phoneEdit = localChanged(rel)
          val driveEdit = r.modified != e.driveModified
          when {
            phoneEdit && driveEdit -> { // edited on both sides: keep both versions
              downloadFromDrive(token, r.id, localFile(conflictName(rel, localFiles.keys)), r.modified)
              val (id, mod) = uploadToDrive(token, l.file, splitRel(rel).second, r.parent, r.id)
              record(rel, id, mod, l.file)
            }
            phoneEdit -> {
              val (id, mod) = uploadToDrive(token, l.file, splitRel(rel).second, r.parent, r.id)
              record(rel, id, mod, l.file)
            }
            driveEdit -> {
              downloadFromDrive(token, r.id, l.file, r.modified)
              record(rel, r.id, r.modified, localFile(rel))
            }
            parseTime(r.modified) < l.modified - 60_000 -> {
              // Downloaded before dates were kept, so it's dated when it arrived; Drive's older date is when it was made.
              l.file.setLastModified(parseTime(r.modified))
              record(rel, r.id, r.modified, localFile(rel))
            }
          }
        }
        l != null && r != null -> { // on both sides but never linked (first sync, or created twice)
          if (l.size == r.size && (r.md5 == null || r.md5 == md5(l.file))) {
            record(rel, r.id, r.modified, l.file)
          } else {
            downloadFromDrive(token, r.id, localFile(conflictName(rel, localFiles.keys)), r.modified)
            val (id, mod) = uploadToDrive(token, l.file, splitRel(rel).second, r.parent, r.id)
            record(rel, id, mod, l.file)
          }
        }
        l != null && e != null -> { // deleted in Drive
          if (!allowDriveDeletes) continue
          if (localChanged(rel)) { // but edited here since: re-upload instead of losing the edit
            val (id, mod) = uploadToDrive(token, l.file, splitRel(rel).second, parentFor(rel))
            record(rel, id, mod, l.file)
          } else {
            l.file.delete()
            entries.remove(rel)
            save()
          }
        }
        l != null -> { // new on the phone
          val (id, mod) = uploadToDrive(token, l.file, splitRel(rel).second, parentFor(rel))
          record(rel, id, mod, l.file)
        }
        r != null && e != null -> { // deleted on the phone
          if (state.driveOnly[rel] == true) { // just deleted "from this phone only": keep the Drive copy
            driveOnly[rel] = true
            entries.remove(rel)
            save()
          } else if (r.modified != e.driveModified) { // but edited in Drive since: bring the new version back
            downloadFromDrive(token, r.id, localFile(rel), r.modified)
            record(rel, r.id, r.modified, localFile(rel))
          } else {
            trashInDrive(token, r.id)
            remote.remove(rel)
            entries.remove(rel)
            save()
          }
        }
        r != null -> { // new in Drive: stays there until it's downloaded from Files → Google Drive
          driveOnly[rel] = true
          save()
        }
        else -> { // gone from both sides
          entries.remove(rel)
          save()
        }
      }
    }

    // Folders removed on one side, now empty on the other.
    val (afterFiles, afterFolders) = scanLocal()
    for ((folder, id) in syncedFolders.toMap()) {
      val onPhone = folder in afterFolders
      val inDrive = folder in remoteFolders
      val hasFiles = afterFiles.keys.any { splitRel(it).first == folder }
      if (onPhone && !inDrive && !hasFiles) {
        File(rootFolder(), folder).deleteRecursively()
        syncedFolders.remove(folder)
      } else if (!onPhone && inDrive && !hasFiles) {
        if ((entries.keys + driveOnly.keys).none { splitRel(it).first == folder }) {
          trashInDrive(token, id)
          remoteFolders.remove(folder)
          syncedFolders.remove(folder)
        }
      } else if (!onPhone && !inDrive) {
        syncedFolders.remove(folder)
      }
    }
    remember(email, DriveListing(remoteFolders.keys.toList(), remote.map { (rel, r) -> DriveFile(r.id, splitRel(rel).second, splitRel(rel).first, r.size, parseTime(r.modified), r.modified) }))
    if (!allowDriveDeletes) {
      throw UserError(appContext.getString(R.string.error_drive_missing_pdfs, missingInDrive.size))
    }
    save()
  }

  // "Report.pdf" -> "Report (Drive copy).pdf", "Report (Drive copy 2).pdf", ...
  private fun conflictName(rel: String, taken: Set<String>): String {
    val base = rel.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
    var i = 1
    while (true) {
      val name = "$base (Drive copy${if (i > 1) " $i" else ""}).pdf"
      if (name !in taken && !localFile(name).exists()) return name
      i++
    }
  }

  // ── Account ─────────────────────────────────────────────────────────────

  /**
   * Shows the account picker, asks for Drive access on the chosen account, and starts syncing with it. Used both to
   * turn sync on and to switch accounts. A different account starts fresh; the previous account's Drive is left alone.
   */
  suspend fun connect() {
    val email = GoogleAuth.chooseAccount()
    GoogleAuth.token(email, true)
    val previous = state
    val same = previous.enabled && previous.email.equals(email, ignoreCase = true)
    if (previous.email != null && !same) signOut(previous.email)
    update { if (same) previous.copy(error = null) else BackupState(enabled = true, email = email) }
    syncNow()
  }

  // Revokes this app's Drive access for one account (best effort, never throws).
  private suspend fun signOut(email: String) {
    try {
      val token = GoogleAuth.token(email, false)
      withContext(Dispatchers.IO) {
        runCatching {
          (URL("https://oauth2.googleapis.com/revoke?token=${URLEncoder.encode(token, "UTF-8")}").openConnection() as HttpURLConnection)
            .apply { requestMethod = "POST"; doOutput = true; outputStream.close() }.responseCode
        }
      }
      GoogleAuth.clearToken(token)
    } catch (_: Exception) {}
  }

  /** Turns sync off and signs out. Files stay on the phone and in Drive. */
  suspend fun disable() {
    val email = state.email
    update { BackupState() }
    listing = null
    if (email != null) signOut(email)
  }

  private fun relOf(path: String): String {
    val root = rootFolder().path + File.separator
    return if (path.startsWith(root)) path.substring(root.length).replace(File.separatorChar, '/') else ""
  }

  /** True when these phone files have copies in Drive, so deleting can offer "this phone only". */
  fun hasDriveCopies(paths: List<String>) = state.enabled && paths.any { state.entries.containsKey(relOf(it)) }

  /** Call just before deleting files from the phone to keep their Drive copies (and not download them again). */
  fun keepInDriveOnly(paths: List<String>) = update { x ->
    val entries = x.entries.toMutableMap()
    val driveOnly = x.driveOnly.toMutableMap()
    for (rel in paths.map(::relOf)) if (entries.remove(rel) != null) driveOnly[rel] = true
    x.copy(entries = entries, driveOnly = driveOnly)
  }

  private fun relOfDrive(f: DriveFile) = if (f.folder.isNotEmpty()) "${f.folder}/${f.name}" else f.name

  /** Whether this Drive file also has its synced copy on this phone. */
  fun isOnPhone(f: DriveFile) = state.entries.containsKey(relOfDrive(f))

  /** Sync status of a saved file, for the "waiting" cloud in Files. */
  fun waiting(path: String, name: String) = state.enabled && isPdf(name) && !state.entries.containsKey(relOf(path))

  /**
   * Downloads Drive files onto this phone, into the folder they're in in Drive, and links them so they sync from then
   * on. `onProgress` gets the count done.
   */
  suspend fun downloadToPhone(files: List<DriveFile>, onProgress: (Int) -> Unit) = withContext(Dispatchers.IO) {
    val email = state.email?.takeIf { state.enabled } ?: throw UserError(appContext.getString(R.string.error_drive_sync_off))
    val done = java.util.concurrent.atomic.AtomicInteger()
    val slots = Semaphore(3) // a few at once: much faster than one by one, without starving a weak connection
    withToken(email) { token ->
      coroutineScope {
        files.distinctBy { relOfDrive(it) }.map { f ->
          async {
            slots.withPermit {
              val rel = relOfDrive(f)
              val dest = localFile(rel)
              if (!dest.exists()) { // one already here (linked, or not yet) is left to sync
                downloadFromDrive(token, f.id, dest, f.modifiedTime)
                val link = Synced(f.id, f.modifiedTime, dest.lastModified(), dest.length())
                update { x ->
                  if (x.email != email) x
                  else x.copy(driveOnly = x.driveOnly - rel, entries = x.entries + (rel to link))
                }
                justDownloaded[rel] = link
              }
              val n = done.incrementAndGet()
              withContext(Dispatchers.Main) { onProgress(n) }
            }
          }
        }.awaitAll()
      }
    }
    withContext(Dispatchers.Main) { Store.refreshOnly() }
  }

  private fun remember(email: String, data: DriveListing) {
    listing = email to DriveListing(data.folders.distinct().sortedBy { it.lowercase() }, data.files.sortedByDescending { it.modified })
  }

  /** The last listing, shown at once instead of fetching on every visit; each sync keeps it current. */
  fun cached(): DriveListing? = listing?.takeIf { state.enabled && it.first == state.email }?.second

  /** Folders and PDFs in the synced Drive folder (one level of folders, like the phone), files newest first. */
  suspend fun list(): DriveListing = withContext(Dispatchers.IO) {
    val s = state
    val email = s.email?.takeIf { s.enabled } ?: throw UserError(appContext.getString(R.string.error_drive_sync_off))
    withToken(email) { token ->
      val rootId = ensureRoot(token, s.folderId)
      val files = ArrayList<DriveFile>()
      val folders = ArrayList<String>()
      for (item in listChildren(token, rootId)) {
        if (item.mime == FOLDER_MIME) {
          folders.add(item.name)
          for (f in listChildren(token, item.id)) if (f.mime != FOLDER_MIME && isPdf(f.name)) files.add(DriveFile(f.id, f.name, item.name, f.size, parseTime(f.modified), f.modified))
        } else if (isPdf(item.name)) {
          files.add(DriveFile(item.id, item.name, "", item.size, parseTime(item.modified), item.modified))
        }
      }
      remember(email, DriveListing(folders, files))
      listing!!.second
    }
  }

  /** Uploads a copy of any saved file (Word doc, JPEGs…) to the Drive folder. Sync only handles PDFs, so it's left alone. */
  suspend fun saveCopy(f: Saved, mime: String) = withContext(Dispatchers.IO) {
    val s = state
    val email = s.email?.takeIf { s.enabled } ?: throw UserError(appContext.getString(R.string.error_drive_sync_off))
    withToken(email) { token -> uploadToDrive(token, File(f.path), f.name, ensureRoot(token, s.folderId), null, mime) }
  }

  /** A temporary local copy of a Drive file (in the cache, which Android may clear), for opening, sharing or a tool. */
  suspend fun fetch(f: DriveFile): Picked = withContext(Dispatchers.IO) {
    val email = state.email?.takeIf { state.enabled } ?: throw UserError(appContext.getString(R.string.error_drive_sync_off))
    withToken(email) { token ->
      // Under its id, so same-named files from different folders don't overwrite each other.
      val dest = File(appContext.cacheDir, "drive/${f.id}/${cleanName(f.name)}")
      // Fetched again when Drive has a newer version, even one of the same size: the copy carries Drive's date.
      val stale = !dest.exists() || dest.length() != f.size || kotlin.math.abs(dest.lastModified() - f.modified) > 1000
      if (stale) downloadFromDrive(token, f.id, dest, f.modifiedTime)
      Picked(f.name, dest.path, dest.length())
    }
  }

  private fun driveFolders(token: String, root: String) = listChildren(token, root).filter { it.mime == FOLDER_MIME }.associate { it.name to it.id }

  // The phone copy of a Drive folder; null for a Drive name that can't be one ("..", or with a slash).
  private fun phoneFolder(name: String) = name.takeIf { it.isNotBlank() && it != "." && it != ".." && cleanName(it) == it }?.let { File(rootFolder(), it) }

  // Folder renames and deletes from Files → Google Drive change both sides and the sync records in one go, so the next
  // sync has nothing to guess (and a big deleted folder doesn't trip its "many files vanished" safety stop).

  suspend fun renameFolder(old: String, typed: String) = withContext(Dispatchers.IO) {
    val s = state
    val email = s.email?.takeIf { s.enabled } ?: throw UserError(appContext.getString(R.string.error_drive_sync_off))
    val name = cleanName(typed)
    if (name.isEmpty() || name == old) return@withContext
    val target = phoneFolder(name) ?: throw UserError(appContext.getString(R.string.error_bad_folder_name))
    withToken(email) { token ->
      val folders = driveFolders(token, ensureRoot(token, s.folderId))
      if (name in folders || target.exists()) throw UserError(appContext.getString(R.string.error_folder_already_exists, name))
      val id = folders[old] ?: throw UserError(appContext.getString(R.string.error_folder_not_in_drive))
      call(token, "$API/files/$id", "PATCH", JSONObject().put("name", name))
    }
    phoneFolder(old)?.takeIf { it.exists() && it.renameTo(target) }?.let { relinkFolder(it.path, target.path) }
    val move = { rel: String -> splitRel(rel).let { (dir, file) -> if (dir == old) "$name/$file" else rel } }
    update { x -> if (x.email != email) x else x.copy(entries = x.entries.mapKeys { move(it.key) }, driveOnly = x.driveOnly.mapKeys { move(it.key) }, folders = x.folders.mapKeys { if (it.key == old) name else it.key }) }
    listing?.let { (e, d) -> remember(e, DriveListing(d.folders.map { if (it == old) name else it }, d.files.map { if (it.folder == old) it.copy(folder = name) else it })) }
    withContext(Dispatchers.Main) { Store.refreshOnly() }
  }

  /** Settings → Delete all, phone and Drive: everything in the Drive folder (files, folders, Drive-only copies) goes to Drive's Trash, then the phone is cleared. */
  suspend fun deleteEverything() = withContext(Dispatchers.IO) {
    val s = state
    val email = s.email?.takeIf { s.enabled } ?: throw UserError(appContext.getString(R.string.error_drive_sync_off))
    withToken(email) { token -> for (item in listChildren(token, ensureRoot(token, s.folderId))) trashInDrive(token, item.id) }
    update { x -> if (x.email != email) x else x.copy(entries = emptyMap(), driveOnly = emptyMap(), folders = emptyMap()) }
    listing = email to DriveListing(emptyList(), emptyList())
    withContext(Dispatchers.Main) { clearFiles() }
  }

  /** Moves the folder to Drive's Trash and deletes it from this phone too. */
  suspend fun deleteFolder(name: String) = withContext(Dispatchers.IO) {
    val s = state
    val email = s.email?.takeIf { s.enabled } ?: throw UserError(appContext.getString(R.string.error_drive_sync_off))
    withToken(email) { token -> driveFolders(token, ensureRoot(token, s.folderId))[name]?.let { trashInDrive(token, it) } }
    phoneFolder(name)?.deleteRecursively()
    val inside = { rel: String -> splitRel(rel).first == name }
    update { x -> if (x.email != email) x else x.copy(entries = x.entries.filterKeys { !inside(it) }, driveOnly = x.driveOnly.filterKeys { !inside(it) }, folders = x.folders - name) }
    listing?.let { (e, d) -> remember(e, DriveListing(d.folders - name, d.files.filter { it.folder != name })) }
    withContext(Dispatchers.Main) { Store.refreshOnly() }
  }
}

fun syncSoon() = Backup.syncSoon()
