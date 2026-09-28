package com.karin.streamtv.ui

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.karinlink.SmbClient
import com.karin.streamtv.karinlink.SmbHttpProxy
import com.karin.streamtv.player.ExoPlayerActivity
import com.karin.streamtv.karinlink.SmbRef
import com.karin.streamtv.util.FileOps
import com.karin.streamtv.util.GamepadHelper
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ExploreKF : AppCompatActivity() {

    enum class PaneType { LOCAL, SMB }
    /** Copiar o Mover directo al otro panel, como el File Manager de Kodi
     *  (xbmc/xbmc): sin paso intermedio de "pegar". */
    enum class TransferOp { COPY, MOVE }

    data class StorageVolumeInfo(val name: String, val path: String, val isPrimary: Boolean)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastPlayMs: Long = 0L

    private lateinit var paneLeft: Pane
    private lateinit var paneRight: Pane
    private lateinit var activePane: Pane

    private val status: TextView by lazy { findViewById(R.id.tv_status) }
    private val searchBar: View by lazy { findViewById(R.id.search_bar) }
    private val etSearch: EditText by lazy { findViewById(R.id.et_search) }
    private val cbRecursive: CheckBox by lazy { findViewById(R.id.cb_recursive) }
    private val rvHome: RecyclerView by lazy { findViewById(R.id.rv_home) }
    private val llPanes: View by lazy { findViewById(R.id.ll_panes) }
    private val paneTabs: View by lazy { findViewById(R.id.pane_tabs) }
    private val tabLeft: TextView by lazy { findViewById(R.id.btn_pane_left) }
    private val tabRight: TextView by lazy { findViewById(R.id.btn_pane_right) }
    private val paneLeftBox: View by lazy { findViewById(R.id.pane_left) }
    private val paneRightBox: View by lazy { findViewById(R.id.pane_right) }

    private var isHomeMode = true

    // Progreso de la transferencia en curso (diálogo con cancelar).

    // Copy state
    private var copyCancelFlag = AtomicBoolean(false)
    private var progressDialog: ProgressDialog? = null

    companion object {
        private const val REQUEST_WRITE_STORAGE = 6001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dual_pane)

        val startPath = intent.getStringExtra("start_path")
        val initialDir = if (!startPath.isNullOrBlank()) File(startPath) else null

        paneLeft = Pane(PaneType.LOCAL, findViewById(R.id.rv_left), findViewById(R.id.tv_path_left),
            findViewById(R.id.pane_left), findViewById(R.id.tv_empty_left))
        paneRight = Pane(PaneType.LOCAL, findViewById(R.id.rv_right), findViewById(R.id.tv_path_right),
            findViewById(R.id.pane_right), findViewById(R.id.tv_empty_right))
        // Valor inicial: updateStatus()/closeSearch() lo leen incluso en modo
        // inicio (sin start_path); sin esto, abrir el gestor crasheaba.
        activePane = paneLeft
        // Entrada directa desde "Red local" del explorador: izquierda en
        // local, derecha conectada al servidor descubierto.
        val smbHost = intent.getStringExtra("smb_host")
        if (!smbHost.isNullOrBlank()) {
            openInitialSmb(
                host = smbHost,
                port = intent.getIntExtra("smb_port", 445),
                user = intent.getStringExtra("smb_user")?.ifBlank { null },
                pass = intent.getStringExtra("smb_pass")?.ifBlank { null },
                label = intent.getStringExtra("smb_name")?.ifBlank { null } ?: smbHost
            )
        } else if (initialDir != null && initialDir.exists()) {
            enterVolume(initialDir)
        } else {
            showHome()
        }
        setupPaneFocus(paneLeft, findViewById(R.id.pane_left))
        setupPaneFocus(paneRight, findViewById(R.id.pane_right))

        findViewById<View>(R.id.btn_exit).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_exit).onActionKey { finish() }
        findViewById<View>(R.id.btn_home).setOnClickListener { showHome() }
        findViewById<View>(R.id.btn_home).onActionKey { showHome() }
        findViewById<View>(R.id.btn_connect).setOnClickListener {
            if (com.karin.streamtv.util.AppPreferences.isSmbShowOnHome()) {
                showSmbConnectDialog()
            } else {
                Toast.makeText(this, "Red de Windows desactivada: actívala en Configuración de KARIN Link", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<View>(R.id.btn_copy).setOnClickListener { kodiTransfer(TransferOp.COPY) }
        findViewById<View>(R.id.btn_delete).setOnClickListener { doDelete() }
        findViewById<View>(R.id.btn_rename).setOnClickListener { doRename() }
        findViewById<View>(R.id.btn_mkdir).setOnClickListener { doMkdir() }

        // Pestañas para pantallas angostas (una columna).
        tabLeft.setOnClickListener { switchPane(paneLeft) }
        tabLeft.onActionKey { switchPane(paneLeft) }
        tabRight.setOnClickListener { switchPane(paneRight) }
        tabRight.onActionKey { switchPane(paneRight) }

        setupSearch()
        updateStatus()

        // En Android ≤29 copiar/borrar/renombrar exige WRITE_EXTERNAL_STORAGE
        // en tiempo de ejecución (muchas Smart TV van en 9). Sin esto la
        // copia falla con EACCES aunque la lectura funcione.
        ensureWritePermission()

        // Pista de mando solo en TV; en móvil no ocupa espacio.
        findViewById<TextView>(R.id.tv_hints).visibility =
            if (com.karin.streamtv.util.DeviceUtils.isTvDevice(this)) View.VISIBLE else View.GONE
    }

    private fun ensureWritePermission() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.Q) return
        val perm = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, perm) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(perm), REQUEST_WRITE_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WRITE_STORAGE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permiso de escritura concedido", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this,
                    "Sin permiso de escritura no se puede copiar, mover ni borrar",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------- Home (storage volumes) ----------

    private fun showHome() {
        isHomeMode = true
        rvHome.visibility = View.VISIBLE
        llPanes.visibility = View.GONE
        applyPaneMode()
        closeSearch()

        val volumes = detectStorageVolumes()
        val adapter = StorageVolumeAdapter(volumes) { vol ->
            enterVolume(File(vol.path))
        }
        rvHome.layoutManager = LinearLayoutManager(this)
        rvHome.adapter = adapter
        status.text = "Selecciona un almacenamiento"
    }

    /** Entrada a un volumen: izquierda en la raíz, derecha en la carpeta
     *  superior (si existe) para copiar directo sin navegar. Cada panel
     *  recuerda su tope para el Atrás-en-cascada estilo Kodi. */
    private fun enterVolume(dir: File) {
        activePane = paneLeft
        paneLeft.localPath = dir
        paneLeft.homeLocalRoot = dir
        val parent = dir.parentFile
        paneRight.localPath = if (parent != null) parent else dir
        paneRight.homeLocalRoot = paneRight.localPath
        showPanes()
        paneLeft.load()
        paneRight.load()
        updateStatus()
    }

    /** Autoconexión desde la detección automática del explorador. */
    private fun openInitialSmb(host: String, port: Int, user: String?, pass: String?, label: String) {
        if (!com.karin.streamtv.util.AppPreferences.isSmbShowOnHome()) {
            Toast.makeText(this, "Red de Windows desactivada: actívala en Configuración de KARIN Link", Toast.LENGTH_LONG).show()
            showHome()
            return
        }
        val primary = android.os.Environment.getExternalStorageDirectory()
        activePane = paneLeft
        if (primary.exists()) {
            paneLeft.localPath = primary
            paneLeft.homeLocalRoot = primary
        }
        showPanes()
        paneLeft.load()
        val conn = SmbRef(
            deviceName = label, host = host, port = port, share = "", remotePath = "/",
            user = user, password = pass
        )
        paneRight.type = PaneType.SMB
        paneRight.smbConn = conn
        paneRight.smbCurrent = null
        paneRight.load()
        activePane = paneRight
        updateStatus()
        if (com.karin.streamtv.util.DeviceUtils.isTvDevice(this)) {
            paneRight.rv.post { paneRight.rv.requestFocus() }
        }
    }

    private fun showPanes() {
        isHomeMode = false
        rvHome.visibility = View.GONE
        llPanes.visibility = View.VISIBLE
        applyPaneMode()
    }

    /** Dos columnas solo si caben (TV/tablet/apaisado). En móvil angosto
     *  se muestra un panel con pestañas: si no, la 2ª columna queda
     *  ilegible o aparenta no existir. */
    private fun isDualPane(): Boolean =
        resources.configuration.screenWidthDp >= 720

    private fun applyPaneMode() {
        if (isHomeMode) {
            paneTabs.visibility = View.GONE
            return
        }
        if (isDualPane()) {
            paneTabs.visibility = View.GONE
            setPaneWidth(paneLeftBox, half = true)
            setPaneWidth(paneRightBox, half = true)
            paneLeftBox.visibility = View.VISIBLE
            paneRightBox.visibility = View.VISIBLE
        } else {
            paneTabs.visibility = View.VISIBLE
            val leftActive = activePane === paneLeft
            setPaneWidth(paneLeftBox, half = false)
            setPaneWidth(paneRightBox, half = false)
            paneLeftBox.visibility = if (leftActive) View.VISIBLE else View.GONE
            paneRightBox.visibility = if (leftActive) View.GONE else View.VISIBLE
            tabLeft.text = if (leftActive) "● Panel A" else "○ Panel A"
            tabRight.text = if (leftActive) "○ Panel B" else "● Panel B"
        }
    }

    private fun setPaneWidth(box: View, half: Boolean) {
        val lp = box.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        if (half) {
            lp.width = 0
            lp.weight = 1f
        } else {
            lp.width = android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            lp.weight = 0f
        }
        box.layoutParams = lp
    }

    private fun switchPane(pane: Pane) {
        activePane = pane
        applyPaneMode()
        updateStatus()
        pane.rv.post { pane.rv.requestFocus() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyPaneMode()
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
            android.util.Log.d("ExploreKF", "detectStorageVolumes: ${e.message}")
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
            holder.itemView.onActionKey { onClick(vol) }
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
        findViewById<View>(R.id.btn_search).onActionKey { toggleSearch() }
        findViewById<View>(R.id.btn_close_search).setOnClickListener { closeSearch() }
        findViewById<View>(R.id.btn_close_search).onActionKey { closeSearch() }

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
        // La búsqueda solo indexa local: en red avisa en vez de devolver
        // "0 resultados" sin explicación.
        if (activePane.type != PaneType.LOCAL) {
            Toast.makeText(this, "La búsqueda solo funciona en almacenamiento local", Toast.LENGTH_SHORT).show()
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
                applyPaneMode()
            }
        }
        container.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                activePane = pane
                updateStatus()
                applyPaneMode()
            }
        }
    }

    private fun updateStatus() {
        val sel = activePane.adapter.selectionCount()
        val side = if (activePane === paneLeft) "Izquierda" else "Derecha"
        status.text = "Panel activo: $side | Seleccionados: $sel"
    }

    // ---------- Transferencias estilo Kodi (xbmc/xbmc File Manager) ----------
    //
    // Panel A (activo) = origen, el OTRO panel = destino implícito. Copiar o
    // Mover transfiere directo con confirmación: no hay paso de "pegar".

    /** El panel que recibe: el contrario al activo. */
    private fun otherPane(): Pane = if (activePane === paneLeft) paneRight else paneLeft

    /** Elemento con foco en el panel activo (para menú contextual y Copiar). */
    private fun focusedEntry(): FileEntry? {
        if (isHomeMode) return null
        val rv = activePane.rv
        val focused = rv.focusedChild ?: return null
        val holder = rv.getChildViewHolder(focused) ?: return null
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return null
        return activePane.adapter.getItemAt(pos)
    }

    /** Prueba de escritura real en un directorio: File.canWrite() miente en
     *  almacenamiento emulado (false aunque sí se puede escribir). */
    private fun File.canWriteDirProbe(): Boolean {
        return try {
            if (!isDirectory) return false
            val probe = File.createTempFile("karin_write_test", null, this)
            val ok = probe.exists()
            probe.delete()
            ok
        } catch (_: Exception) {
            false
        }
    }

    /** Sin escritura no hay copia: explica y lleva a Ajustes con un toque. */
    private fun showNoWriteAccessDialog(path: String) {
        AlertDialog.Builder(this)
            .setTitle("Sin permiso de escritura")
            .setMessage(
                "No se puede escribir en:\n$path\n\n" +
                "Concede el permiso para copiar, mover o borrar."
            )
            .setPositiveButton("Abrir ajustes") { _, _ ->
                try {
                    startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:$packageName")
                        )
                    )
                } catch (_: Exception) {
                    Toast.makeText(this, "Abre Ajustes > Apps > KarinFLiX > Permisos", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Clave de ubicación para no copiar una carpeta sobre sí misma. */
    private fun paneLocationKey(pane: Pane): String = when (pane.type) {
        PaneType.LOCAL -> "local:${pane.localPath?.absolutePath ?: "?"}"
        PaneType.SMB -> {
            val cur = pane.smbCurrent
            if (cur != null) "smb:${cur.host}:${cur.port}:${cur.share}:${cur.remotePath}"
            else "smb:${pane.smbConn?.host}:${pane.smbConn?.port}:shares"
        }
    }

    /** Copiar/Mover directo al otro panel con confirmación (flujo Kodi).
     *  Usa la selección si hay, o el elemento indicado / enfocado. */
    private fun kodiTransfer(op: TransferOp, fallback: FileEntry? = null) {
        if (isHomeMode) {
            Toast.makeText(this, "Selecciona un almacenamiento primero", Toast.LENGTH_SHORT).show()
            return
        }
        var targets = activePane.adapter.getSelected().filter { !it.isParentMarker }
        if (targets.isEmpty()) {
            val single = fallback?.takeIf { !it.isParentMarker }
                ?: focusedEntry()?.takeIf { !it.isParentMarker }
            if (single == null) {
                Toast.makeText(this, "Resalta un archivo o selecciona con MENÚ", Toast.LENGTH_SHORT).show()
                return
            }
            targets = listOf(single)
        }
        val dest = otherPane()
        if (paneLocationKey(activePane) == paneLocationKey(dest)) {
            Toast.makeText(this, "El otro panel muestra la misma carpeta: navega al destino", Toast.LENGTH_LONG).show()
            return
        }

        // Destino listo (Kodi: CanCopy/CanMove): local navegable y escribible,
        // SMB dentro de un share. Sin esto la copia "terminaba bien" con 0
        // archivos (p. ej. panel SMB aún en la lista de shares).
        when (dest.type) {
            PaneType.LOCAL -> {
                val d = dest.localPath
                if (d == null || !d.isDirectory) {
                    Toast.makeText(this, "El otro panel no muestra una carpeta válida", Toast.LENGTH_LONG).show()
                    return
                }
                if (!d.canWriteDirProbe()) {
                    showNoWriteAccessDialog(d.absolutePath)
                    return
                }
            }
            PaneType.SMB -> {
                if (dest.smbCurrent == null) {
                    Toast.makeText(this, "Entra a una carpeta compartida en el otro panel", Toast.LENGTH_LONG).show()
                    return
                }
            }
        }

        // Anti auto-copia: destino igual o dentro del origen (una carpeta
        // copiada dentro de sí misma se destruye). Como el CanCopy de Kodi.
        if (dest.type == PaneType.LOCAL && dest.localPath != null) {
            val destCanon = try { dest.localPath!!.canonicalPath }
                            catch (_: Exception) { dest.localPath!!.absolutePath }
            for (t in targets) {
                if (t.isNetwork) continue
                val srcCanon = try { t.file.canonicalPath }
                               catch (_: Exception) { t.file.absolutePath }
                if (destCanon == srcCanon || destCanon.startsWith(srcCanon + File.separator)) {
                    Toast.makeText(this, "No se puede copiar '${t.name}' dentro de sí misma", Toast.LENGTH_LONG).show()
                    return
                }
            }
        }
        if (dest.type == PaneType.SMB && dest.smbCurrent != null) {
            val d = dest.smbCurrent!!
            for (t in targets) {
                val s = t.smb ?: continue
                if (s.host == d.host && s.port == d.port && s.share == d.share) {
                    val sp = s.remotePath.trimEnd('/')
                    val dp = d.remotePath.trimEnd('/')
                    if (dp == sp || dp.startsWith("$sp/")) {
                        Toast.makeText(this, "No se puede copiar '${t.name}' dentro de sí misma", Toast.LENGTH_LONG).show()
                        return
                    }
                }
            }
        }

        val files = targets.map { it.file }
        val (totalFiles, totalBytes) = FileOps.countTotalFiles(files)
        val verb = if (op == TransferOp.COPY) "Copiar" else "Mover"
        val sizeStr = formatSize(totalBytes)

        // Conflictos en el destino (solo verificable en local).
        val conflicts = mutableListOf<String>()
        if (dest.type == PaneType.LOCAL && dest.localPath != null) {
            for (entry in targets) {
                if (File(dest.localPath, entry.name).exists()) conflicts.add(entry.name)
            }
        }

        val msg = buildString {
            append("¿$verb ${targets.size} elemento(s)?")
            if (totalFiles > targets.size) append("\nContiene $totalFiles archivos ($sizeStr)")
            append("\n\nA: ${dest.currentPathLabel()}")
            if (conflicts.isNotEmpty()) {
                append("\n\n⚠️ Ya existen:")
                conflicts.take(5).forEach { append("\n• $it") }
                if (conflicts.size > 5) append("\n... y ${conflicts.size - 5} más")
            }
        }

        // Botones en vez de lista: setMessage+setItems no renderiza la lista
        // en este tema, y los botones reciben foco directo con el mando.
        val dlg = AlertDialog.Builder(this)
            .setTitle(verb)
            .setMessage(msg)
        if (conflicts.isNotEmpty()) {
            dlg.setPositiveButton("$verb (sobrescribir)") { _, _ ->
                startPasteOperation(targets, op, dest, overwrite = true)
            }
            dlg.setNeutralButton("$verb (omitir)") { _, _ ->
                startPasteOperation(targets, op, dest, overwrite = false)
            }
        } else {
            dlg.setPositiveButton(verb) { _, _ ->
                startPasteOperation(targets, op, dest, overwrite = true)
            }
        }
        dlg.setNegativeButton("Cancelar", null)
        dlg.show()
    }

    /** Menú contextual estilo Kodi: MENÚ del mando, click largo o tecla C. */
    private fun showItemContextMenu(entry: FileEntry?) {
        if (isHomeMode) return
        // Sobre el ".." solo tiene sentido crear carpeta aquí.
        if (entry?.isParentMarker == true) {
            AlertDialog.Builder(this)
                .setTitle("..")
                .setItems(arrayOf("Nueva carpeta aquí")) { _, _ -> doMkdir() }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        val options = mutableListOf(
            "Copiar al otro panel",
            "Mover al otro panel",
            "Seleccionar",
            "Seleccionar todo",
            "Renombrar",
            "Borrar",
            "Nueva carpeta aquí"
        )
        AlertDialog.Builder(this)
            .setTitle(entry?.name ?: "Opciones")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "Copiar al otro panel" -> kodiTransfer(TransferOp.COPY, fallback = entry)
                    "Mover al otro panel" -> kodiTransfer(TransferOp.MOVE, fallback = entry)
                    "Seleccionar" -> toggleFocusedSelection()
                    "Seleccionar todo" -> {
                        activePane.adapter.selectAll()
                        updateStatus()
                    }
                    "Renombrar" -> {
                        if (entry != null) activePane.adapter.ensureSelected(entry)
                        doRename()
                    }
                    "Borrar" -> {
                        if (entry != null) activePane.adapter.ensureSelected(entry)
                        doDelete()
                    }
                    "Nueva carpeta aquí" -> doMkdir()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun startPasteOperation(
        entries: List<FileEntry>,
        op: TransferOp,
        dest: Pane,
        overwrite: Boolean
    ) {
        copyCancelFlag.set(false)

        // Mostrar diálogo de progreso
        progressDialog = ProgressDialog(this).apply {
            setTitle(if (op == TransferOp.COPY) "Copiando archivos..." else "Moviendo archivos...")
            setMessage("Preparando...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            isIndeterminate = false
            max = 100
            setCancelable(true)
            setOnCancelListener { copyCancelFlag.set(true) }
            setButton(ProgressDialog.BUTTON_NEGATIVE, "Cancelar") { dialog, _ ->
                copyCancelFlag.set(true)
                dialog.dismiss()
            }
            show()
        }

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                performCopy(entries, dest, overwrite)
            }

            // Mover = copiar + borrar origen (solo si todo salió bien).
            if (op == TransferOp.MOVE && result.success) {
                withContext(Dispatchers.IO) {
                    for (e in entries) {
                        if (copyCancelFlag.get()) break
                        deleteEntry(e)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                progressDialog?.dismiss()
                progressDialog = null

                when {
                    result.success -> {
                        val verb = if (op == TransferOp.COPY) "Copiado" else "Movido"
                        val msg = buildString {
                            append("$verb: ")
                            append("${result.filesCopied} archivos")
                            if (result.foldersCopied > 0) append(", ${result.foldersCopied} carpetas")
                            append(" (${formatSize(result.bytesCopied)})")
                        }
                        Toast.makeText(this@ExploreKF, msg, Toast.LENGTH_SHORT).show()
                    }
                    result.errors.isNotEmpty() -> {
                        Toast.makeText(this@ExploreKF, "Algunos archivos fallaron:\n${result.errors.take(3).joinToString("\n")}", Toast.LENGTH_LONG).show()
                    }
                    copyCancelFlag.get() -> {
                        Toast.makeText(this@ExploreKF, "Operación cancelada", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        Toast.makeText(this@ExploreKF, "No se copió ningún archivo", Toast.LENGTH_LONG).show()
                    }
                }

                activePane.adapter.clearSelection()
                // Recargar origen y destino (en Mover el origen cambió).
                paneLeft.load()
                if (paneRight !== paneLeft) paneRight.load()
                updateStatus()
            }
        }
    }

    private suspend fun performCopy(
        entries: List<FileEntry>,
        dest: Pane,
        overwrite: Boolean
    ): FileOps.CopyResult {
        return withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            var totalFilesCopied = 0
            var totalFoldersCopied = 0
            var totalBytesCopied = 0L

            // Calcular totales
            val (totalFiles, totalBytes) = FileOps.countTotalFiles(entries.map { it.file })

            for (entry in entries) {
                if (copyCancelFlag.get()) break

                val result = copyEntryWithProgress(entry, dest, overwrite) { current, processed, _, bytes, _ ->
                    // Actualizar progreso en el diálogo
                    withContext(Dispatchers.Main) {
                        progressDialog?.apply {
                            this.setMessage("Copiando: $current")
                            this.progress = if (totalFiles > 0) (processed * 100 / totalFiles).toInt() else 0
                            this.max = totalFiles
                            this.setProgressNumberFormat("$processed / $totalFiles")
                            this.setProgressPercentFormat(java.text.DecimalFormat("0%"))
                        }
                    }
                }

                totalFilesCopied += result.filesCopied
                totalFoldersCopied += result.foldersCopied
                totalBytesCopied += result.bytesCopied
                errors.addAll(result.errors)
                // Sin esto, un no-op silencioso (destino no listo, tipo no
                // soportado) contaba como éxito con "0 archivos".
                if (!copyCancelFlag.get() && result.filesCopied == 0 &&
                    result.foldersCopied == 0 && result.errors.isEmpty()
                ) {
                    errors.add("No se pudo copiar: ${entry.name}")
                }
            }

            FileOps.CopyResult(
                success = errors.isEmpty() && !copyCancelFlag.get(),
                filesCopied = totalFilesCopied,
                foldersCopied = totalFoldersCopied,
                bytesCopied = totalBytesCopied,
                errors = errors
            )
        }
    }

    private suspend fun copyEntryWithProgress(
        e: FileEntry,
        dest: Pane,
        overwrite: Boolean,
        progressCallback: suspend (String, Int, Int, Long, Long) -> Unit
    ): FileOps.CopyResult {
        return withContext(Dispatchers.IO) {
            try {
                when {
                    !e.isNetwork && dest.type == PaneType.LOCAL -> {
                        val target = File(dest.localPath, e.name)
                        FileOps.copyWithProgress(
                            src = e.file,
                            dstDir = dest.localPath!!,
                            listener = object : FileOps.CopyProgressListener {
                                override fun onProgress(currentFile: String, filesProcessed: Int, totalFiles: Int, bytesProcessed: Long, totalBytes: Long) {
                                    // Notificación de progreso
                                }
                                override fun onComplete(result: FileOps.CopyResult) {}
                            },
                            cancelFlag = copyCancelFlag,
                            overwrite = overwrite
                        )
                    }
                    !e.isNetwork && dest.type == PaneType.SMB -> {
                        val ref = dest.smbCurrent ?: return@withContext FileOps.CopyResult(false, 0, 0, 0)
                        val success = SmbClient.copyLocalToSmb(e.file, ref) is SmbClient.SmbResult.Success
                        FileOps.CopyResult(success, if (success) 1 else 0, 0, e.sizeBytes)
                    }
                    e.smb != null && dest.type == PaneType.LOCAL -> {
                        val target = File(dest.localPath, e.name)
                        val success = SmbClient.copySmbToLocal(e.smb!!, target) is SmbClient.SmbResult.Success
                        FileOps.CopyResult(success, if (success) 1 else 0, 0, e.sizeBytes)
                    }
                    e.smb != null && dest.type == PaneType.SMB -> {
                        val cache = File(cacheDir, "tc_tmp_" + System.currentTimeMillis())
                        val r1 = SmbClient.copySmbToLocal(e.smb!!, cache)
                        if (r1 !is SmbClient.SmbResult.Success) {
                            return@withContext FileOps.CopyResult(false, 0, 0, 0, listOf("Error descargando de SMB"))
                        }
                        val ref = dest.smbCurrent ?: return@withContext FileOps.CopyResult(false, 0, 0, 0)
                        val success = SmbClient.copyLocalToSmb(cache, ref) is SmbClient.SmbResult.Success
                        cache.delete()
                        FileOps.CopyResult(success, if (success) 1 else 0, 0, e.sizeBytes)
                    }
                    else -> FileOps.CopyResult(false, 0, 0, 0, listOf("Tipo de copia no soportado"))
                }
            } catch (ex: Exception) {
                FileOps.CopyResult(false, 0, 0, 0, listOf("Error: ${ex.message}"))
            }
        }
    }

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
        val pathView: TextView,
        val container: View,
        val emptyView: TextView
    ) {
        var localPath: File? = null
        var smbConn: SmbRef? = null
        var smbCurrent: SmbRef? = null
        var searchQuery: String? = null
        var searchRecursive: Boolean = false
        /** Carpeta tope del panel (la elegida en inicio): Atrás sube hasta
         *  aquí y luego vuelve al inicio, como el OnBack de Kodi. */
        var homeLocalRoot: File? = null

        /** true si ya está en el tope (inicio local o lista de shares). */
        fun isAtTop(): Boolean = when (type) {
            PaneType.LOCAL -> {
                val root = homeLocalRoot ?: return true
                val cur = localPath ?: return true
                try { cur.canonicalPath == root.canonicalPath }
                catch (_: Exception) { cur.absolutePath == root.absolutePath }
            }
            PaneType.SMB -> smbCurrent == null
        }
        val adapter = DualPaneFileAdapter(
            onItemClick = { entry -> onEntryClick(this, entry) },
            // Click largo (táctil) = menú contextual, como en Kodi.
            onItemLongClick = { entry -> showItemContextMenu(entry) }
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
                    emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
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
                    emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
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
            val listed = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .map { FileEntry.fromFile(it, if (it.isDirectory) (it.listFiles()?.size ?: 0) else 0) }
            // ".." para retroceder (Kodi): solo si hay carpeta superior.
            val parent = dir.parentFile
            return if (parent != null) {
                listOf(FileEntry.fromFile(parent).copy(customName = "..", isParentMarker = true)) + listed
            } else listed
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
                    is SmbClient.SmbResult.Entries -> {
                        val listed = res.entries.map { info ->
                            val childPath = if (cur.remotePath.endsWith("/")) "${cur.remotePath}${info.name}" else "${cur.remotePath}/${info.name}"
                            FileEntry.fromSmb(cur.copy(remotePath = childPath), info.name, info.isDir, info.size, info.modified)
                        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                        // ".." para retroceder: en la raíz del share vuelve a
                        // la lista de shares, si no al nivel superior.
                        listOf(
                            FileEntry.fromSmb(cur, "..", true, 0L, 0L).copy(
                                customName = "..",
                                isParentMarker = true
                            )
                        ) + listed
                    }
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

    /** Sube un nivel en el panel (destino del ".."). En la raíz del share
     *  SMB vuelve a la lista de shares. */
    private fun navigateUp(pane: Pane) {
        when (pane.type) {
            PaneType.LOCAL -> {
                val up = pane.localPath?.parentFile ?: return
                pane.localPath = up
                pane.searchQuery = null
                pane.load()
            }
            PaneType.SMB -> {
                val cur = pane.smbCurrent ?: return
                val trimmed = cur.remotePath.trimEnd('/')
                pane.smbCurrent = if (trimmed.isEmpty()) {
                    null // raíz del share -> lista de shares
                } else {
                    val up = trimmed.substringBeforeLast('/', "")
                    cur.copy(remotePath = if (up.isEmpty()) "/" else up)
                }
                pane.load()
            }
        }
        updateStatus()
    }

    private fun onEntryClick(pane: Pane, entry: FileEntry) {
        // ".." retrocede un nivel en vez de entrar.
        if (entry.isParentMarker) {
            navigateUp(pane)
            return
        }
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
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastPlayMs < 1000) return
                    lastPlayMs = now
                    // En red se reproduce vía proxy HTTP local: la ruta
                    // "smb://…" ficticia no la abre ningún reproductor.
                    val url = if (entry.smb != null) {
                        SmbHttpProxy.proxyUrl(entry.smb!!)
                    } else {
                        entry.file.absolutePath
                    }
                    val intent = Intent(this, ExoPlayerActivity::class.java).apply {
                        putExtra("video_url", url)
                        putExtra("video_title", entry.name)
                    }
                    try { startActivity(intent) } catch (e: Exception) { /* no player */ }
                } else {
                    // Antes solo un toast con el nombre: abrir el menú da
                    // opciones reales (copiar, borrar, renombrar).
                    showItemContextMenu(entry)
                }
            }
        }
        updateStatus()
    }

    // ---------- Mando a distancia (teclas Kodi) ----------
    //
    // Como en el File Manager de Kodi: MENÚ (o C) abre el menú contextual
    // sobre el item resaltado, 0/Espacio alterna la selección, OK entra/abre
    // (ver onActionKey en los adaptadores), SEARCH abre el buscador y Atrás
    // baja en cascada en vez de cerrar de golpe.

    /** Alterna la selección del item con foco en el panel activo. */
    private fun toggleFocusedSelection(): Boolean {
        if (isHomeMode) return false
        val entry = focusedEntry() ?: return false
        if (entry.isParentMarker) return false
        val rv = activePane.rv
        val pos = rv.adapter?.itemCount?.let { count ->
            (0 until count).firstOrNull { activePane.adapter.getItemAt(it) == entry }
        } ?: return false
        if (!activePane.adapter.toggleSelectionAt(pos)) return false
        updateStatus()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) {
            return onKeyDown(mapped, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_C -> {
                if (event?.repeatCount == 0 && !isHomeMode) {
                    showItemContextMenu(focusedEntry())
                    return true
                }
            }
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_BUTTON_Y -> {
                if (event?.repeatCount == 0 && toggleFocusedSelection()) return true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                if (event?.repeatCount == 0) {
                    toggleSearch()
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onBackPressed() {
        // Como el OnBack de Kodi: subir un nivel; en el tope ir al inicio;
        // en inicio, salir. La búsqueda abierta se cierra primero.
        when {
            searchBar.visibility == View.VISIBLE -> closeSearch()
            !isHomeMode && !activePane.isAtTop() -> navigateUp(activePane)
            !isHomeMode -> showHome()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        progressDialog?.dismiss()
        progressDialog = null
        copyCancelFlag.set(true)
        scope.cancel()
        super.onDestroy()
    }
}
