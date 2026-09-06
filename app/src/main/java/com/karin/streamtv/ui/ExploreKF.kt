package com.karin.streamtv.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.karinlink.SmbClient
import com.karin.streamtv.player.ExoPlayerActivity
import com.karin.streamtv.karinlink.SmbRef
import com.karin.streamtv.util.FileOps
import kotlinx.coroutines.*
import java.io.File

class ExploreKF : AppCompatActivity() {

    enum class PaneType { LOCAL, SMB }
    enum class ClipOp { COPY, MOVE }

    data class StorageVolumeInfo(val name: String, val path: String, val isPrimary: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var paneLeft: Pane
    private lateinit var paneRight: Pane
    private lateinit var activePane: Pane

    private val status: TextView by lazy { findViewById(R.id.tv_status) }
    private val searchBar: View by lazy { findViewById(R.id.search_bar) }
    private val etSearch: EditText by lazy { findViewById(R.id.et_search) }
    private val cbRecursive: CheckBox by lazy { findViewById(R.id.cb_recursive) }
    private val rvHome: RecyclerView by lazy { findViewById(R.id.rv_home) }
    private val llPanes: View by lazy { findViewById(R.id.ll_panes) }

    private var isHomeMode = true

    // Clipboard
    private var clipEntries: List<FileEntry> = emptyList()
    private var clipOp: ClipOp = ClipOp.COPY

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_total_commander)

        val startPath = intent.getStringExtra("start_path")
        val initialDir = if (!startPath.isNullOrBlank()) File(startPath) else null

        paneLeft = Pane(PaneType.LOCAL, findViewById(R.id.rv_left), findViewById(R.id.tv_path_left))
        paneRight = Pane(PaneType.LOCAL, findViewById(R.id.rv_right), findViewById(R.id.tv_path_right))

        if (initialDir != null && initialDir.exists()) {
            paneLeft.localPath = initialDir
            paneRight.localPath = initialDir
            activePane = paneLeft
            showPanes()
            paneLeft.load()
            paneRight.load()
        } else {
            showHome()
        }

        setupPaneFocus(paneLeft, findViewById(R.id.pane_left))
        setupPaneFocus(paneRight, findViewById(R.id.pane_right))

        findViewById<View>(R.id.btn_exit).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_home).setOnClickListener { showHome() }
        findViewById<View>(R.id.btn_connect).setOnClickListener { showSmbConnectDialog() }
        findViewById<View>(R.id.btn_copy).setOnClickListener { doCopy() }
        findViewById<View>(R.id.btn_paste).setOnClickListener { doPaste() }
        findViewById<View>(R.id.btn_delete).setOnClickListener { doDelete() }
        findViewById<View>(R.id.btn_rename).setOnClickListener { doRename() }
        findViewById<View>(R.id.btn_mkdir).setOnClickListener { doMkdir() }

        setupSearch()
        updateStatus()
    }

    // ---------- Home (storage volumes) ----------

    private fun showHome() {
        isHomeMode = true
        rvHome.visibility = View.VISIBLE
        llPanes.visibility = View.GONE
        closeSearch()

        val volumes = detectStorageVolumes()
        val adapter = StorageVolumeAdapter(volumes) { vol ->
            activePane = paneLeft
            paneLeft.localPath = File(vol.path)
            paneRight.localPath = File(vol.path)
            showPanes()
            paneLeft.load()
            paneRight.load()
        }
        rvHome.layoutManager = LinearLayoutManager(this)
        rvHome.adapter = adapter
        status.text = "Selecciona un almacenamiento"
    }

    private fun showPanes() {
        isHomeMode = false
        rvHome.visibility = View.GONE
        llPanes.visibility = View.VISIBLE
    }

    private fun detectStorageVolumes(): List<StorageVolumeInfo> {
        val result = mutableListOf<StorageVolumeInfo>()
        try {
            val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val volumes = sm.storageVolumes
            for (vol in volumes) {
                val label = if (vol.isPrimary) {
                    "Almacenamiento interno"
                } else {
                    val uuid = vol.uuid
                    if (uuid != null && uuid != "primary") {
                        "Tarjeta SD"
                    } else "Almacenamiento extraíble"
                }
                val path = getVolumePath(vol)
                if (path != null) {
                    result.add(StorageVolumeInfo(name = label, path = path, isPrimary = vol.isPrimary))
                }
            }
        } catch (e: Exception) {
            android.util.Log.d("TC", "detectStorageVolumes: ${e.message}")
        }

        if (result.isEmpty()) {
            val primaryPath = Environment.getExternalStorageDirectory().absolutePath
            if (File(primaryPath).exists()) {
                result.add(StorageVolumeInfo("Almacenamiento interno", primaryPath, true))
            }
        }
        return result
    }

    private fun getVolumePath(volume: android.os.storage.StorageVolume): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val dir = volume.directory
                dir?.absolutePath
            } else {
                @Suppress("DEPRECATION")
                val uuid = volume.uuid
                if (uuid != null && uuid != "primary") {
                    "/storage/$uuid"
                } else {
                    Environment.getExternalStorageDirectory().absolutePath
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Storage volume adapter ----------

    private inner class StorageVolumeAdapter(
        private val volumes: List<StorageVolumeInfo>,
        private val onClick: (StorageVolumeInfo) -> Unit
    ) : RecyclerView.Adapter<StorageVolumeAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: android.widget.ImageView = view.findViewById(R.id.iv_icon)
            val name: TextView = view.findViewById(R.id.tv_name)
            val meta: TextView = view.findViewById(R.id.tv_meta)
            val progressFill: View = view.findViewById(R.id.v_progress_fill)
            val storageUsage: TextView = view.findViewById(R.id.tv_storage_usage)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_file_storage, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val vol = volumes[position]
            val ctx = holder.itemView.context
            val accent = ContextCompat.getColor(ctx,
                if (vol.isPrimary) R.color.storage_internal else R.color.storage_external
            )
            holder.icon.setImageResource(if (vol.isPrimary) R.drawable.ic_phone else R.drawable.ic_sd)
            holder.icon.setColorFilter(accent)
            holder.name.text = vol.name
            holder.meta.text = vol.path

            val dir = File(vol.path)
            val total = dir.totalSpace
            val free = dir.freeSpace
            val used = total - free

            holder.storageUsage.text = if (total > 0) {
                "${formatSize(used)} usados  •  ${formatSize(free)} libres  •  ${formatSize(total)} total"
            } else {
                "Espacio no disponible"
            }

            val fraction = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else 0f
            holder.progressFill.post {
                val parent = holder.progressFill.parent as? View ?: return@post
                val width = (parent.width * fraction).toInt().coerceAtLeast(0)
                val lp = holder.progressFill.layoutParams
                lp.width = width
                holder.progressFill.layoutParams = lp
            }
            holder.progressFill.setBackgroundColor(accent)

            holder.itemView.setOnClickListener { onClick(vol) }
        }

        override fun getItemCount(): Int = volumes.size

        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val mb = bytes / 1048576.0
            val gb = mb / 1024.0
            return when {
                gb >= 1 -> String.format(java.util.Locale.US, "%.1f GB", gb)
                mb >= 1 -> String.format(java.util.Locale.US, "%.1f MB", mb)
                else -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
            }
        }
    }

    // ---------- Search ----------

    private fun setupSearch() {
        findViewById<View>(R.id.btn_search).setOnClickListener { toggleSearch() }
        findViewById<View>(R.id.btn_close_search).setOnClickListener { closeSearch() }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }
    }

    private fun toggleSearch() {
        if (isHomeMode) {
            Toast.makeText(this, "Selecciona un almacenamiento primero", Toast.LENGTH_SHORT).show()
            return
        }
        if (searchBar.visibility == View.VISIBLE) {
            closeSearch()
        } else {
            searchBar.visibility = View.VISIBLE
            etSearch.requestFocus()
            etSearch.text.clear()
        }
    }

    private fun closeSearch() {
        searchBar.visibility = View.GONE
        etSearch.text.clear()
        activePane.searchQuery = null
        activePane.load()
    }

    private fun performSearch() {
        val query = etSearch.text.toString().trim()
        if (query.isEmpty()) {
            closeSearch()
            return
        }
        activePane.searchQuery = query
        activePane.searchRecursive = cbRecursive.isChecked
        activePane.loadSearch()
    }

    // ---------- Status ----------

    private fun setupPaneFocus(pane: Pane, container: View) {
        pane.rv.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activePane = pane
                updateStatus()
            }
        }
        container.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activePane = pane
                updateStatus()
            }
        }
    }

    private fun updateStatus() {
        val sel = activePane.adapter.selectionCount()
        val side = if (activePane === paneLeft) "Izquierda" else "Derecha"
        val clip = if (clipEntries.isNotEmpty()) {
            " | Portapapeles: ${clipEntries.size} (${if (clipOp == ClipOp.COPY) "copiar" else "mover"})"
        } else ""
        status.text = "Panel activo: $side | Seleccionados: $sel$clip"
    }

    // ---------- Clipboard / operations ----------

    private fun doCopy() {
        val sel = activePane.adapter.getSelected()
        if (sel.isEmpty()) {
            Toast.makeText(this, "Selecciona archivos (OK para entrar, click largo para seleccionar)", Toast.LENGTH_SHORT).show()
            return
        }
        clipEntries = sel
        clipOp = ClipOp.COPY
        activePane.adapter.clearSelection()
        updateStatus()
        Toast.makeText(this, "${clipEntries.size} elemento(s) copiado(s)", Toast.LENGTH_SHORT).show()
    }

    private fun doPaste() {
        if (clipEntries.isEmpty()) {
            Toast.makeText(this, "Nada que pegar", Toast.LENGTH_SHORT).show()
            return
        }
        val dest = activePane
        val list = clipEntries
        val op = clipOp
        val sb = StringBuilder()
        scope.launch {
            for (e in list) {
                val ok = copyEntry(e, dest)
                if (!ok) sb.append("${e.name}; ")
            }
            withContext(Dispatchers.Main) {
                if (sb.isEmpty()) {
                    Toast.makeText(this@ExploreKF, "Pegado completado", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@ExploreKF, "Fallaron: $sb", Toast.LENGTH_LONG).show()
                }
                if (op == ClipOp.MOVE) clipEntries = emptyList()
                dest.load()
                updateStatus()
            }
        }
    }

    private suspend fun copyEntry(e: FileEntry, dest: Pane): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                when {
                    !e.isNetwork && dest.type == PaneType.LOCAL -> {
                        val target = File(dest.localPath, e.name)
                        FileOps.copy(e.file, target)
                    }
                    !e.isNetwork && dest.type == PaneType.SMB -> {
                        val ref = dest.smbCurrent ?: return@withContext false
                        SmbClient.copyLocalToSmb(e.file, ref) is SmbClient.SmbResult.Success
                    }
                    e.smb != null && dest.type == PaneType.LOCAL -> {
                        val target = File(dest.localPath, e.name)
                        SmbClient.copySmbToLocal(e.smb!!, target) is SmbClient.SmbResult.Success
                    }
                    e.smb != null && dest.type == PaneType.SMB -> {
                        val cache = File(cacheDir, "tc_tmp_" + System.currentTimeMillis())
                        val r1 = SmbClient.copySmbToLocal(e.smb!!, cache)
                        if (r1 !is SmbClient.SmbResult.Success) return@withContext false
                        val ref = dest.smbCurrent ?: return@withContext false
                        SmbClient.copyLocalToSmb(cache, ref) is SmbClient.SmbResult.Success
                    }
                    else -> false
                }
            } catch (ex: Exception) {
                false
            }
        }
    }

    private fun doDelete() {
        val sel = activePane.adapter.getSelected()
        if (sel.isEmpty()) {
            Toast.makeText(this, "Selecciona elementos para borrar", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Borrar")
            .setMessage("¿Borrar ${sel.size} elemento(s)?")
            .setPositiveButton("Borrar") { _, _ ->
                scope.launch {
                    var ok = true
                    for (e in sel) {
                        val r = deleteEntry(e)
                        if (!r) ok = false
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ExploreKF,
                            if (ok) "Borrado completado" else "Algunos no se pudieron borrar",
                            Toast.LENGTH_SHORT).show()
                        activePane.adapter.clearSelection()
                        activePane.load()
                        updateStatus()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private suspend fun deleteEntry(e: FileEntry): Boolean = withContext(Dispatchers.IO) {
        when {
            !e.isNetwork -> FileOps.delete(e.file)
            e.smb != null -> SmbClient.delete(e.smb!!) is SmbClient.SmbResult.Success
            else -> false
        }
    }

    private fun doRename() {
        val sel = activePane.adapter.getSelected()
        if (sel.size != 1) {
            Toast.makeText(this, "Selecciona un solo elemento para renombrar", Toast.LENGTH_SHORT).show()
            return
        }
        val e = sel[0]
        val input = EditText(this).apply {
            setText(e.name)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(this)
            .setTitle("Renombrar")
            .setView(input)
            .setPositiveButton("Aceptar") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isBlank() || newName == e.name) return@setPositiveButton
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        when {
                            !e.isNetwork -> FileOps.rename(e.file, newName)
                            e.smb != null -> SmbClient.rename(e.smb!!, newName) is SmbClient.SmbResult.Success
                            else -> false
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ExploreKF,
                            if (ok) "Renombrado" else "No se pudo renombrar", Toast.LENGTH_SHORT).show()
                        activePane.adapter.clearSelection()
                        activePane.load()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun doMkdir() {
        val input = EditText(this).apply { hint = "Nombre de carpeta" }
        AlertDialog.Builder(this)
            .setTitle("Nueva carpeta")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        when (activePane.type) {
                            PaneType.LOCAL -> FileOps.mkdir(File(activePane.localPath, name))
                            PaneType.SMB -> {
                                val ref = activePane.smbCurrent ?: return@withContext false
                                val path = if (ref.remotePath.endsWith("/")) "${ref.remotePath}$name" else "${ref.remotePath}/$name"
                                SmbClient.mkdir(ref.copy(remotePath = path)) is SmbClient.SmbResult.Success
                            }
                        }
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ExploreKF,
                            if (ok) "Carpeta creada" else "No se pudo crear", Toast.LENGTH_SHORT).show()
                        activePane.load()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- SMB connect ----------

    private fun showSmbConnectDialog() {
        val host = EditText(this).apply { hint = "IP del servidor (ej. 192.168.1.20)" }
        val port = EditText(this).apply { hint = "Puerto (445)"; setText("445") }
        val user = EditText(this).apply { hint = "Usuario (opcional)" }
        val pass = EditText(this).apply { hint = "Contraseña (opcional)"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
            addView(host); addView(port); addView(user); addView(pass)
        }
        AlertDialog.Builder(this)
            .setTitle("Conectar a red SMB")
            .setView(box)
            .setPositiveButton("Conectar") { _, _ ->
                val h = host.text.toString().trim()
                if (h.isBlank()) return@setPositiveButton
                val p = port.text.toString().toIntOrNull() ?: 445
                val conn = SmbRef(
                    deviceName = h, host = h, port = p, share = "", remotePath = "/",
                    user = user.text.toString().ifBlank { null },
                    password = pass.text.toString().ifBlank { null }
                )
                showPanes()
                paneRight.type = PaneType.SMB
                paneRight.smbConn = conn
                paneRight.smbCurrent = null
                paneRight.load()
                activePane = paneRight
                updateStatus()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- Pane ----------

    inner class Pane(
        var type: PaneType,
        val rv: RecyclerView,
        val pathView: TextView
    ) {
        var localPath: File? = null
        var smbConn: SmbRef? = null
        var smbCurrent: SmbRef? = null
        var searchQuery: String? = null
        var searchRecursive: Boolean = false
        val adapter = TcFileAdapter(
            onItemClick = { entry -> onEntryClick(this, entry) },
            onItemLongClick = { updateStatus() }
        )

        init {
            rv.layoutManager = LinearLayoutManager(this@ExploreKF)
            rv.adapter = this@Pane.adapter
        }

        fun load() {
            scope.launch {
                val entries = withContext(Dispatchers.IO) { loadEntries() }
                withContext(Dispatchers.Main) {
                    adapter.searchMode = false
                    adapter.basePath = ""
                    adapter.submit(entries)
                    pathView.text = currentPathLabel()
                    adapter.clearSelection()
                    updateStatus()
                }
            }
        }

        fun loadSearch() {
            val query = searchQuery ?: return load()
            scope.launch {
                val entries = withContext(Dispatchers.IO) {
                    if (searchRecursive) searchRecursive(query) else searchInCurrentDir(query)
                }
                withContext(Dispatchers.Main) {
                    adapter.searchMode = true
                    adapter.basePath = localPath?.absolutePath ?: ""
                    adapter.submit(entries)
                    pathView.text = "Buscar: \"$query\" (${entries.size} resultados)"
                    adapter.clearSelection()
                    updateStatus()
                }
            }
        }

        private fun searchInCurrentDir(query: String): List<FileEntry> {
            val dir = localPath ?: return emptyList()
            val files = dir.listFiles()?.toList() ?: return emptyList()
            return files.filter { it.name.contains(query, ignoreCase = true) }
                .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .map { FileEntry.fromFile(it, if (it.isDirectory) (it.listFiles()?.size ?: 0) else 0) }
        }

        private fun searchRecursive(query: String): List<FileEntry> {
            val dir = localPath ?: return emptyList()
            val results = mutableListOf<FileEntry>()
            searchDirRecursive(dir, query, results, depth = 10)
            return results.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        }

        private fun searchDirRecursive(dir: File, query: String, results: MutableList<FileEntry>, depth: Int) {
            if (depth <= 0) return
            val files = dir.listFiles() ?: return
            for (f in files) {
                if (f.name.contains(query, ignoreCase = true)) {
                    results.add(FileEntry.fromFile(f, if (f.isDirectory) (f.listFiles()?.size ?: 0) else 0))
                }
                if (f.isDirectory && !f.name.startsWith(".")) {
                    searchDirRecursive(f, query, results, depth - 1)
                }
            }
        }

        private fun loadEntries(): List<FileEntry> {
            return when (type) {
                PaneType.LOCAL -> loadLocal()
                PaneType.SMB -> loadSmb()
            }
        }

        private fun loadLocal(): List<FileEntry> {
            val dir = localPath ?: return emptyList()
            val files = dir.listFiles()?.toList() ?: return emptyList()
            return files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .map { FileEntry.fromFile(it, if (it.isDirectory) (it.listFiles()?.size ?: 0) else 0) }
        }

        private fun loadSmb(): List<FileEntry> {
            val conn = smbConn ?: return emptyList()
            val cur = smbCurrent
            return if (cur == null) {
                when (val res = SmbClient.listShares(conn.host, conn.port, conn.user, conn.password)) {
                    is SmbClient.SmbResult.Shares -> res.shares.map { share ->
                        FileEntry.fromSmbShare(conn.copy(share = share), share, "SMB Share")
                    }
                    is SmbClient.SmbResult.Error -> {
                        runOnUiThread { Toast.makeText(this@ExploreKF, res.message, Toast.LENGTH_LONG).show() }
                        emptyList()
                    }
                    else -> emptyList()
                }
            } else {
                when (val res = SmbClient.list(cur)) {
                    is SmbClient.SmbResult.Entries -> res.entries.map { info ->
                        val childPath = if (cur.remotePath.endsWith("/")) "${cur.remotePath}${info.name}" else "${cur.remotePath}/${info.name}"
                        FileEntry.fromSmb(cur.copy(remotePath = childPath), info.name, info.isDir, info.size, info.modified)
                    }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                    is SmbClient.SmbResult.Error -> {
                        runOnUiThread { Toast.makeText(this@ExploreKF, res.message, Toast.LENGTH_LONG).show() }
                        emptyList()
                    }
                    else -> emptyList()
                }
            }
        }

        fun currentPathLabel(): String = when (type) {
            PaneType.LOCAL -> "Local: ${localPath?.absolutePath ?: ""}"
            PaneType.SMB -> smbCurrent?.let { "Red: ${it.hostPort}/${it.share}${it.remotePath}" }
                ?: "Red: ${smbConn?.hostPort ?: "?"} (elige share)"
        }
    }

    private fun onEntryClick(pane: Pane, entry: FileEntry) {
        when {
            pane.type == PaneType.LOCAL && entry.isDirectory -> {
                pane.localPath = entry.file
                pane.searchQuery = null
                pane.load()
            }
            pane.type == PaneType.SMB && entry.isDirectory -> {
                if (pane.smbCurrent == null) {
                    pane.smbCurrent = pane.smbConn?.copy(share = entry.name, remotePath = "/")
                } else {
                    pane.smbCurrent = entry.smb
                }
                pane.load()
            }
            else -> {
                if (entry.fileType == FileEntry.FileType.VIDEO) {
                    val intent = Intent(this, ExoPlayerActivity::class.java).apply {
                        putExtra("video_url", entry.file.absolutePath)
                        putExtra("video_title", entry.name)
                    }
                    try { startActivity(intent) } catch (e: Exception) { /* no player */ }
                } else {
                    Toast.makeText(this, entry.name, Toast.LENGTH_SHORT).show()
                }
            }
        }
        updateStatus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
