package com.karin.streamtv.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.karinlink.SmbDiscovery
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.GamepadHelper
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class FileExplorerActivity : AppCompatActivity() {

    private lateinit var rvVideos: RecyclerView
    private lateinit var rvFolders: RecyclerView
    private lateinit var btnBack: ImageView
    private lateinit var tvPath: TextView
    private lateinit var btnSearch: ImageView
    private lateinit var btnFolders: ImageView
    private lateinit var btnSort: ImageView
    private lateinit var btnView: ImageView
    private lateinit var btnExit: ImageView
    private lateinit var etSearch: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var tvEmptyContainer: View
    private lateinit var btnManager: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvHints: TextView

    private val cr: ContentResolver by lazy { applicationContext.contentResolver }

    private var videoAdapter: VideoAdapter? = null
    private var folderAdapter: FolderAdapter? = null

    private var allVideos: List<VideoItem> = emptyList()

    private val navStack = mutableListOf<String?>()
    private var currentSubFolders: List<FolderItem> = emptyList()
    private var currentVideos: List<VideoItem> = emptyList()
    private var searchQuery = ""

    private var showingFolders = false
    private var isTvDevice = false
    private var isGridView = true
    private var sortAsc = true
    private var sortMode = SortMode.NAME
    private val handler = Handler(Looper.getMainLooper())
    private var searchDebounce: Runnable? = null

    // Detección automática de red local (Windows + NAS): escaneo en curso,
    // último resultado (para no re-escanear al volver Atrás) y nombres
    // amigables por "host:port".
    private var netScanJob: Job? = null
    private var lastNetResult: SmbDiscovery.DiscoveryResult? = null
    private val netNames = HashMap<String, String>()

    companion object {
        private const val TAG = "FileExplorer"
        private const val REQUEST_STORAGE_PERMISSION = 5001
        private const val REQUEST_MANAGE_STORAGE = 5002
    }

    private enum class SortMode { NAME, COUNT, SIZE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_explorer)

        rvVideos = findViewById(R.id.rv_videos)
        rvFolders = findViewById(R.id.rv_folders)
        btnBack = findViewById(R.id.btn_back)
        tvPath = findViewById(R.id.tv_path)
        btnSearch = findViewById(R.id.btn_search)
        btnFolders = findViewById(R.id.btn_folders)
        btnSort = findViewById(R.id.btn_sort)
        btnView = findViewById(R.id.btn_view)
        btnExit = findViewById(R.id.btn_exit)
        etSearch = findViewById(R.id.et_search)
        progressBar = findViewById(R.id.progress_bar)
        tvEmpty = findViewById(R.id.tv_empty)
        tvEmptyContainer = findViewById(R.id.tv_empty_container)
        btnManager = findViewById(R.id.btn_manager)
        tvCount = findViewById(R.id.tv_count)
        tvHints = findViewById(R.id.tv_hints)

        rvFolders.visibility = View.GONE
        isTvDevice = DeviceUtils.isTvDevice(this)

        // Misma interfaz en TV y móvil: la pista de mando solo estorba en táctil.
        tvHints.visibility = if (isTvDevice) View.VISIBLE else View.GONE

        applySpanCount()

        videoAdapter = VideoAdapter(emptyList(), this) { item ->
            playVideo(item)
        }
        rvVideos.adapter = videoAdapter

        folderAdapter = FolderAdapter(emptyList()) { folder ->
            browseTo(folder)
        }
        rvFolders.adapter = folderAdapter

        btnBack.setOnClickListener { navigateBack() }
        btnBack.onActionKey { navigateBack() }

        btnSearch.setOnClickListener { toggleSearchBar() }
        btnSearch.onActionKey { toggleSearchBar() }

        btnFolders.setOnClickListener { toggleView() }
        btnFolders.onActionKey { toggleView() }

        btnSort.setOnClickListener { toggleSort() }
        btnSort.onActionKey { toggleSort() }

        btnView.setOnClickListener { toggleViewMode() }
        btnView.onActionKey { toggleViewMode() }

        btnExit.setOnClickListener { finish() }
        btnExit.onActionKey { finish() }

        btnManager.setOnClickListener { openManager() }
        btnManager.onActionKey { openManager() }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        // Filtrado en vivo en móvil (táctil + teclado). Con debounce para no
        // re-filtrar en cada tecla en listas grandes de USB. En TV no se usa:
        // allí la búsqueda va por diálogo (ver toggleSearchBar).
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isTvDevice) return
                searchDebounce?.let { handler.removeCallbacks(it) }
                val r = Runnable {
                    searchQuery = s?.toString()?.trim() ?: ""
                    if (!showingFolders) showVideoGrid(restoreFocus = false)
                }
                searchDebounce = r
                handler.postDelayed(r, 300)
            }
        })

        etSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                etSearch.clearFocus()
                requestFocusOnCurrentView()
                true
            } else false
        }

        setupTvNavigation()
        checkPermissionsAndLoad()
    }

    // En Smart TV la pantalla de "Acceso total" puede no existir o no
    // otorgarse: no insistir en loop. Tras 1 intento (o si no hay pantalla
    // de ajustes) se cae al modo medios (MediaStore), que en TV lee USB y
    // videos indexados sin All Files Access.
    private var manageStorageAttempts = 0

    private fun checkPermissionsAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${packageName}")
            )
            val resolvable = try {
                intent.resolveActivity(packageManager) != null
            } catch (_: Exception) {
                false
            }
            if (manageStorageAttempts >= 1 || !resolvable) {
                android.util.Log.w(
                    "FileExplorer",
                    "Sin acceso total (TV?): modo medios via MediaStore",
                )
                requestMediaOnlyAndLoad()
                return
            }
            manageStorageAttempts++
            try {
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
            } catch (e: Exception) {
                try {
                    startActivityForResult(
                        android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                        REQUEST_MANAGE_STORAGE
                    )
                } catch (_: Exception) {
                    requestMediaOnlyAndLoad()
                }
            }
            return
        }

        requestMediaOnlyAndLoad()
    }

    /** Ruta sin All Files: permiso de medios + listado MediaStore. */
    private fun requestMediaOnlyAndLoad() {
        val permission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadAllVideos()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_STORAGE_PERMISSION)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            // Vuelva concedido o no: una sola pasada más y al modo medios.
            // (Antes esto reabría Ajustes en loop en TVs sin esa pantalla.)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()
            ) {
                requestMediaOnlyAndLoad()
            } else {
                manageStorageAttempts++
                checkPermissionsAndLoad()
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadAllVideos()
            } else {
                tvEmptyContainer.visibility = View.VISIBLE
                tvEmpty.text = "Permiso de almacenamiento denegado. Activa el permiso en Configuración."
                progressBar.visibility = View.GONE
            }
        }
    }

    // ---------- UI ADAPTATIVA (misma interfaz TV + móvil) ----------

    /** Columnas según ancho real: TV 4K ~6 vídeos, móvil vertical 2. Se
     *  recalcula al rotar (la actividad declara configChanges). */
    private fun computeSpanCount(forFolders: Boolean): Int {
        val widthDp = resources.configuration.screenWidthDp
        val videoCols = when {
            widthDp >= 1100 -> 6
            widthDp >= 900 -> 5
            widthDp >= 720 -> 4
            widthDp >= 500 -> 3
            else -> 2
        }
        val folderCols = when {
            widthDp >= 1100 -> 4
            widthDp >= 800 -> 3
            widthDp >= 500 -> 2
            else -> 1
        }
        return if (forFolders) folderCols else videoCols
    }

    private fun applySpanCount() {
        if (isGridView) {
            rvVideos.layoutManager = GridLayoutManager(this, computeSpanCount(false))
            rvFolders.layoutManager = GridLayoutManager(this, computeSpanCount(true))
            btnView.setImageResource(R.drawable.ic_grid)
        } else {
            rvVideos.layoutManager = LinearLayoutManager(this)
            rvFolders.layoutManager = LinearLayoutManager(this)
            btnView.setImageResource(R.drawable.ic_list)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Al rotar el móvil (la actividad no se recrea): re-columnear y
        // devolver el foco a la lista para no dejar el mando en el aire.
        applySpanCount()
        requestFocusOnCurrentView()
    }

    /** Posición del foco actual para restaurarlo tras re-ordenar/filtrar sin
     *  mandar al usuario de vuelta arriba (clave con mando). */
    private fun focusedPosition(rv: RecyclerView): Int {
        val focused = rv.focusedChild
        if (focused != null) {
            val pos = rv.getChildAdapterPosition(focused)
            if (pos != RecyclerView.NO_POSITION) return pos
        }
        val lm = rv.layoutManager
        return when (lm) {
            is GridLayoutManager -> lm.findFirstVisibleItemPosition().coerceAtLeast(0)
            is LinearLayoutManager -> lm.findFirstVisibleItemPosition().coerceAtLeast(0)
            else -> 0
        }
    }

    private fun restoreFocus(rv: RecyclerView, position: Int) {
        rv.post {
            if (rv.adapter == null || (rv.adapter?.itemCount ?: 0) == 0) return@post
            val pos = position.coerceIn(0, (rv.adapter?.itemCount ?: 1) - 1)
            rv.scrollToPosition(pos)
            rv.post {
                val holder = rv.findViewHolderForAdapterPosition(pos)
                (holder?.itemView ?: rv.getChildAt(0))?.requestFocus()
            }
        }
    }

    private fun pageScroll(forward: Boolean) {
        val rv = if (showingFolders) rvFolders else rvVideos
        val lm = rv.layoutManager ?: return
        val span = if (lm is GridLayoutManager) lm.spanCount else 1
        val visible = if (lm is LinearLayoutManager) {
            lm.findLastVisibleItemPosition() - lm.findFirstVisibleItemPosition() + 1
        } else 0
        val page = (if (visible > 0) visible else span * 3).coerceAtLeast(1)
        val cur = focusedPosition(rv)
        val count = rv.adapter?.itemCount ?: 0
        if (count == 0) return
        val target = if (forward) (cur + page).coerceAtMost(count - 1)
                     else (cur - page).coerceAtLeast(0)
        restoreFocus(rv, target)
    }

    private fun setupTvNavigation() {
        // Antes solo en TV: ahora siempre activo. En móvil no molesta (el
        // táctil no genera DPAD) y deja la misma interfaz útil con teclado,
        // gamepad o el control remoto del celular vía KARIN Link.
        val topBarButtons = listOf<View>(btnBack, btnSearch, btnFolders, btnSort, btnView, btnExit)
        topBarButtons.forEachIndexed { index, btn ->
            btn.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            requestFocusOnCurrentView()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (index > 0) topBarButtons[index - 1].requestFocus()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (index < topBarButtons.lastIndex) topBarButtons[index + 1].requestFocus()
                            true
                        }
                        else -> false
                    }
                } else false
            }
        }

        val navListener = { recyclerView: RecyclerView ->
            recyclerView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    val lm = recyclerView.layoutManager
                    val firstVisible = when (lm) {
                        is GridLayoutManager -> lm.findFirstVisibleItemPosition()
                        is LinearLayoutManager -> lm.findFirstVisibleItemPosition()
                        else -> 0
                    }
                    if (firstVisible <= 0) {
                        // Primer fila + ARRIBA = volver a la top bar (no perder foco).
                        btnFolders.requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
        navListener(rvVideos)
        navListener(rvFolders)
    }

    private fun requestFocusOnCurrentView() {
        val target = if (showingFolders) rvFolders else rvVideos
        target.requestFocus()
        target.post {
            val firstChild = target.getChildAt(0)
            firstChild?.requestFocus()
        }
    }

    private fun loadAllVideos() {
        showLoading("Escaneando videos...")
        tvEmptyContainer.visibility = View.GONE

        lifecycleScope.launch {
            allVideos = withContext(Dispatchers.IO) { queryAllVideos() }

            if (allVideos.isEmpty()) {
                hideLoading()
                tvEmptyContainer.visibility = View.VISIBLE
                tvEmpty.text = if (isTvDevice) {
                    "No se encontraron videos. En Smart TV conecta un USB con videos " +
                        "y concede acceso a fotos y videos cuando se pida."
                } else {
                    "No se encontraron videos en el dispositivo"
                }
                return@launch
            }

            navStack.clear()
            navStack.add(null)
            searchQuery = ""
            etSearch.visibility = View.GONE

            buildAndShowRoot()
        }
    }

    private fun queryAllVideos(): List<VideoItem> {
        val items = mutableListOf<VideoItem>()
        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val useDataColumn = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

        val projection = if (useDataColumn) {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            )
        } else {
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            )
        }

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            cr.query(uri, projection, null, null, sortOrder)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val pathIdx = if (useDataColumn) {
                    c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                } else {
                    c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                }
                val bucketIdx = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

                while (c.moveToNext()) {
                    val id = c.getLong(idIdx)
                    val name = c.getString(nameIdx)
                    val duration = c.getLong(durIdx)
                    val size = c.getLong(sizeIdx)
                    val pathVal = c.getString(pathIdx) ?: ""
                    val bucket = c.getString(bucketIdx) ?: "Desconocido"

                    val relPath: String
                    if (useDataColumn) {
                        relPath = pathVal.substringAfter("/storage/emulated/0/", "")
                    } else {
                        relPath = pathVal
                    }

                    val videoUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()

                    items.add(VideoItem(
                        id = id,
                        title = name,
                        uri = videoUri,
                        durationMs = duration,
                        folder = bucket,
                        relativePath = relPath,
                        sizeBytes = size
                    ))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "queryAllVideos error: ${e.message}", e)
        }

        return items
    }

    data class StorageVolumeInfo(val name: String, val path: String)

    private val videoExtensions = setOf(
        "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts", "3gp", "mpg", "mpeg"
    )

    private fun isVideoFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in videoExtensions
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
                        "Almacenamiento extraíble"
                    } else "Almacenamiento desconocido"
                }
                val path = getVolumePath(vol)
                if (path != null) {
                    result.add(StorageVolumeInfo(name = label, path = path))
                }
            }
        } catch (e: Exception) {
            android.util.Log.d(TAG, "detectStorageVolumes: ${e.message}")
        }

        if (result.isEmpty()) {
            val primaryPath = Environment.getExternalStorageDirectory().absolutePath
            if (File(primaryPath).exists()) {
                result.add(StorageVolumeInfo(name = "Almacenamiento interno", path = primaryPath))
            }
        }
        return result
    }

    private fun getVolumePath(volume: StorageVolume): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val dir = volume.getDirectory()
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
            android.util.Log.d(TAG, "getVolumePath: ${e.message}")
            null
        }
    }

    private fun listFileSystemDir(pathStr: String): Pair<List<FolderItem>, List<VideoItem>> {
        val dir = File(pathStr)
        val folders = mutableListOf<FolderItem>()
        val videos = mutableListOf<VideoItem>()

        if (!dir.exists() || !dir.isDirectory) return Pair(emptyList(), emptyList())

        val children = dir.listFiles() ?: return Pair(emptyList(), emptyList())

        for (child in children) {
            if (child.isDirectory && child.canRead()) {
                var count = 0
                child.listFiles()?.let { files ->
                    count = files.count { !it.isDirectory && isVideoFile(it.name) }
                }
                folders.add(FolderItem(
                    name = child.name.replaceFirstChar { it.uppercase() },
                    path = "fs:${child.absolutePath}",
                    count = count
                ))
            } else if (child.isFile && child.canRead() && isVideoFile(child.name)) {
                val videoUri = FileProvider.getUriForFile(
                    this,
                    "${applicationContext.packageName}.fileprovider",
                    child
                ).toString()

                videos.add(VideoItem(
                    id = child.hashCode().toLong(),
                    title = child.nameWithoutExtension,
                    uri = videoUri,
                    durationMs = 0L,
                    folder = child.parentFile?.name ?: "",
                    relativePath = child.absolutePath,
                    sizeBytes = child.length()
                ))
            }
        }

        folders.sortBy { it.name }
        videos.sortBy { it.title }

        return Pair(folders, videos)
    }

    private fun normalizeFolderName(raw: String): String {
        return when (raw.lowercase().trim('/')) {
            "download", "downloads" -> "Descargas"
            "video", "videos", "movie", "movies" -> "Videos"
            "picture", "pictures", "photo", "photos", "dcim", "image", "images", "screenshot", "screenshots" -> "Imágenes"
            "document", "documents", "docs" -> "Documentos"
            "music", "audio" -> "Música"
            "android" -> "Android"
            "" -> "Raíz"
            else -> raw.trim('/').replaceFirstChar { it.uppercase() }
        }
    }

    private fun buildAndShowRoot() {
        showLoading("Preparando...")
        lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) { buildRootFolderItems() }
            hideLoading()

            currentSubFolders = folders
            currentVideos = allVideos
            searchQuery = ""

            tvPath.text = "Explorador de archivos"
            btnBack.visibility = View.GONE
            showFolderGrid()
            if (isTvDevice) requestFocusOnCurrentView()
        }
    }

    private fun buildRootFolderItems(): List<FolderItem> {
        val folders = mutableListOf<FolderItem>()

        // Detección automática: PCs con Windows y NAS, sin escribir IPs.
        folders.add(FolderItem(name = "Red local", path = "__net__", count = 0))

        folders.add(FolderItem(name = "Todos los videos", path = "__all__", count = allVideos.size))

        val volumes = detectStorageVolumes()
        for (vol in volumes) {
            val volCount = if (vol.path.startsWith("/storage/emulated/0")) {
                allVideos.size
            } else {
                countVideosInFileSystem(File(vol.path))
            }
            folders.add(FolderItem(name = vol.name, path = "fs:${vol.path}", count = volCount))
        }

        val topLevelDirs = LinkedHashMap<String, Int>()
        var rootFileCount = 0
        for (v in allVideos) {
            val rp = v.relativePath?.trim('/') ?: continue
            if (rp.isBlank()) continue
            if (!rp.contains('/')) {
                rootFileCount++
                continue
            }
            val topDir = rp.split('/').firstOrNull { it.isNotBlank() } ?: continue
            topLevelDirs[topDir] = (topLevelDirs[topDir] ?: 0) + 1
        }

        if (rootFileCount > 0) {
            folders.add(FolderItem(name = "Raíz (${rootFileCount})", path = "", count = rootFileCount))
        }

        topLevelDirs.forEach { (dir, count) ->
            folders.add(FolderItem(name = normalizeFolderName(dir), path = dir, count = count))
        }

        return folders
    }

    private fun browseTo(folder: FolderItem) {
        searchQuery = ""
        etSearch.visibility = View.GONE

        when {
            folder.path == "__all__" -> {
                if (navStack.last() != "__all__") navStack.add("__all__")
                currentSubFolders = buildRootFoldersForPath("")
                currentVideos = allVideos
                tvPath.text = "Todos los videos"
                btnBack.visibility = View.VISIBLE
                showVideoGrid()
                return
            }
            folder.path == "__videos_in_path__" -> {
                showVideoGrid()
                return
            }
            folder.path == "__net__" -> {
                enterNet()
                return
            }
            folder.path == "__net_rescan__" -> {
                scanNetwork()
                return
            }
            folder.path?.startsWith("__net_host__:") == true -> {
                val payload = folder.path.removePrefix("__net_host__:")
                val sep = payload.lastIndexOf(':')
                val host = if (sep > 0) payload.substring(0, sep) else payload
                val port = if (sep > 0) payload.substring(sep + 1).toIntOrNull() ?: 445 else 445
                showNetCredentials(host, port)
                return
            }
            folder.path == "__all_in_path__" -> {
                val cleanPath = navStack.lastOrNull()?.trim('/') ?: ""
                currentVideos = allVideos.filter { v ->
                    val rp = v.relativePath?.trim('/') ?: ""
                    rp == cleanPath || rp.startsWith("$cleanPath/")
                }
                tvPath.text = "Videos (carpeta + sub)"
                showVideoGrid()
                return
            }
            folder.path?.startsWith("fs:") == true -> {
                openFileSystemDir(folder.path.substring(3), addToStack = true)
                return
            }
             else -> {
                val path = folder.path.trim('/')
                if (navStack.last() != path) navStack.add(path)
                currentVideos = videosInPath(path)
                currentSubFolders = buildRootFoldersForPath(path)
                tvPath.text = folder.name
            }
        }

        btnBack.visibility = View.VISIBLE
        if (currentSubFolders.isNotEmpty()) {
            showFolderGrid()
        } else {
            showVideoGrid()
        }
    }

    // ---------- Red local automática (Windows + NAS) ----------

    /** Entra a "Red local": usa caché al volver Atrás, escanea al entrar. */
    private fun enterNet() {
        if (navStack.last() != "__net__") navStack.add("__net__")
        val cached = lastNetResult
        if (cached != null) showNetResult(cached) else scanNetwork()
    }

    private fun scanNetwork() {
        netScanJob?.cancel()
        showLoading("Buscando equipos Windows y NAS…")
        netScanJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    SmbDiscovery.discover(this@FileExplorerActivity)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "scan red: ${e.message}")
                    null
                }
            } ?: return@launch
            // Si el usuario salió mientras tanto, no pisar la vista actual.
            if (navStack.last() != "__net__") return@launch
            lastNetResult = result
            hideLoading()
            showNetResult(result)
        }
    }

    private fun showNetResult(result: SmbDiscovery.DiscoveryResult) {
        netNames.clear()
        val folders = mutableListOf(
            FolderItem(name = "↻ Buscar de nuevo", path = "__net_rescan__", count = 0)
        )
        for (s in result.servers) {
            netNames["${s.host}:${s.port}"] = s.name
            val label = if (s.name == s.host) s.host else "${s.name} (${s.host})"
            folders.add(FolderItem(name = label, path = "__net_host__:${s.host}:${s.port}", count = 0))
        }
        currentSubFolders = folders
        currentVideos = emptyList()
        searchQuery = ""
        tvPath.text = "Red local"
        btnBack.visibility = View.VISIBLE
        showFolderGrid(restoreFocus = false)
        if (result.servers.isNotEmpty()) {
            tvCount.text = if (result.servers.size == 1) "1 equipo" else "${result.servers.size} equipos"
        } else {
            tvCount.text = ""
            Toast.makeText(
                this,
                if (result.subnets.isEmpty())
                    "Sin conexión a red local. Conéctate a la misma WiFi o cable que tus equipos y NAS."
                else
                    "No se encontraron equipos ni NAS en ${result.subnets.joinToString(", ")}.",
                Toast.LENGTH_LONG
            ).show()
        }
        if (isTvDevice) requestFocusOnCurrentView()
    }

    /** Usuario/clave opcionales (vacío = invitado) y salto al gestor. */
    private fun showNetCredentials(host: String, port: Int) {
        val name = netNames["$host:$port"] ?: host
        val user = EditText(this).apply { hint = "Usuario (vacío = invitado)" }
        val pass = EditText(this).apply {
            hint = "Contraseña (opcional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
            addView(user)
            addView(pass)
        }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("Conectar a $host en el gestor de archivos")
            .setView(box)
            .setPositiveButton("Conectar") { _, _ ->
                openNetManager(
                    host, port, name,
                    user.text.toString().ifBlank { null },
                    pass.text.toString().ifBlank { null }
                )
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openNetManager(host: String, port: Int, name: String, user: String?, pass: String?) {
        try {
            val intent = android.content.Intent(this, ExploreKF::class.java).apply {
                putExtra("smb_host", host)
                putExtra("smb_port", port)
                putExtra("smb_name", name)
                if (user != null) putExtra("smb_user", user)
                if (pass != null) putExtra("smb_pass", pass)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el gestor", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildRootFoldersForPath(basePath: String): List<FolderItem> {
        val subDirs = mutableMapOf<String, Int>()

        for (v in allVideos) {
            val rp = v.relativePath?.trim('/') ?: continue
            if (rp.isBlank()) continue

            if (basePath.isBlank()) {
                if (!rp.contains('/')) continue
                val dir = rp.split('/').firstOrNull { it.isNotBlank() } ?: continue
                subDirs[dir] = (subDirs[dir] ?: 0) + 1
             } else {
                val cleanBase = basePath.trim('/')
                if (!rp.startsWith("$cleanBase/")) continue
                val remainder = rp.removePrefix("$cleanBase/").trim('/')
                if (remainder.isBlank()) continue
                if (!remainder.contains('/')) continue  // file in folder, not a subfolder
                val dir = remainder.split('/').firstOrNull { it.isNotBlank() } ?: continue
                val fullPath = "$cleanBase/$dir"
                subDirs[fullPath] = (subDirs[fullPath] ?: 0) + 1
            }
        }

        val result = subDirs.map { (dir, count) ->
            val displayName = if (basePath.isBlank()) {
                normalizeFolderName(dir)
            } else {
                normalizeFolderName(dir.split('/').last())
            }
            FolderItem(name = displayName, path = dir, count = count)
        }.sortedBy { it.name }.toMutableList()

        val rootVideos = allVideos.filter { v ->
            val rp = v.relativePath?.trim('/') ?: ""
            rp.isBlank() || !rp.contains('/')
        }

        if (basePath.isBlank()) {
            if (rootVideos.isNotEmpty()) {
                result.add(0, FolderItem(name = "Ver videos (${rootVideos.size})", path = "__videos_in_path__", count = rootVideos.size))
            }
        } else {
            val videoCount = currentVideos.size
            if (videoCount > 0) {
                result.add(0, FolderItem(name = "Ver videos ($videoCount)", path = "__videos_in_path__", count = videoCount))
            }
            val allCount = allVideos.count { v ->
                val rp = v.relativePath?.trim('/') ?: ""
                rp.startsWith("$basePath/")
            }
            if (allCount > videoCount) {
                result.add(FolderItem(name = "Videos de esta carpeta (incl. sub)", path = "__all_in_path__", count = allCount))
            }
        }

        return result
    }

     private fun videosInPath(path: String): List<VideoItem> {
        val cleanPath = path.trim('/')
        return allVideos.filter { v ->
            val rp = v.relativePath?.trim('/') ?: ""
            val parts = rp.split('/').filter { it.isNotBlank() }
            if (parts.size <= 1) {
                if (cleanPath.isBlank()) rp == cleanPath || !rp.contains('/')
                else rp == cleanPath
            } else {
                val parentPath = parts.dropLast(1).joinToString("/")
                parentPath == cleanPath
            }
        }
    }

    private fun showFolderGrid(restoreFocus: Boolean = true) {
        val keepPos = if (restoreFocus) focusedPosition(rvFolders) else 0
        showingFolders = true
        rvVideos.visibility = View.GONE
        rvFolders.visibility = View.VISIBLE
        btnFolders.setImageResource(R.drawable.ic_video)
        folderAdapter?.submitList(currentSubFolders)

        tvCount.text = if (currentSubFolders.isNotEmpty())
            "${currentSubFolders.size} carpetas" else ""
        tvEmptyContainer.visibility = if (currentSubFolders.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = "No hay carpetas disponibles"

        if (restoreFocus) restoreFocus(rvFolders, keepPos)
        else if (isTvDevice) rvFolders.post { rvFolders.requestFocus() }
    }

    private fun showVideoGrid(restoreFocus: Boolean = true) {
        val keepPos = if (restoreFocus) focusedPosition(rvVideos) else 0
        showingFolders = false
        rvFolders.visibility = View.GONE
        rvVideos.visibility = View.VISIBLE
        btnFolders.setImageResource(R.drawable.ic_folder)

        val searchBase = currentVideos

        val displayVideos = if (searchQuery.isNotBlank()) {
            searchBase.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                it.folder.contains(searchQuery, ignoreCase = true)
            }
        } else {
            searchBase
        }.let { applySortToVideos(it) }

        videoAdapter?.submitList(displayVideos)

        tvCount.text = when {
            displayVideos.isEmpty() -> ""
            searchQuery.isNotBlank() ->
                "${displayVideos.size} resultados · “$searchQuery”"
            else -> "${displayVideos.size} videos"
        }
        tvEmptyContainer.visibility = if (displayVideos.isEmpty()) View.VISIBLE else View.GONE
        if (searchQuery.isNotBlank()) {
            tvEmpty.text = "Sin resultados para '$searchQuery'"
        } else if (navStack.size <= 1) {
            tvEmpty.text = "No se encontraron videos"
        } else {
            tvEmpty.text = "No hay videos en esta carpeta"
        }

        if (displayVideos.isNotEmpty() && restoreFocus) restoreFocus(rvVideos, keepPos)
        else if (isTvDevice && displayVideos.isNotEmpty()) rvVideos.post { rvVideos.requestFocus() }
    }

    private fun toggleView() {
        if (showingFolders) {
            showVideoGrid()
        } else {
            showFolderGrid()
        }
    }

    private fun applySortToVideos(list: List<VideoItem>): List<VideoItem> {
        return when (sortMode) {
            SortMode.NAME -> if (sortAsc) list.sortedBy { it.title.lowercase() }
                             else list.sortedByDescending { it.title.lowercase() }
            SortMode.SIZE -> if (sortAsc) list.sortedBy { it.sizeBytes }
                             else list.sortedByDescending { it.sizeBytes }
            SortMode.COUNT -> list // COUNT solo aplica a carpetas
        }
    }

    private fun applySortToFolders(list: List<FolderItem>): List<FolderItem> {
        return when (sortMode) {
            SortMode.NAME -> if (sortAsc) list.sortedBy { it.name.lowercase() }
                             else list.sortedByDescending { it.name.lowercase() }
            SortMode.COUNT -> if (sortAsc) list.sortedBy { it.count }
                              else list.sortedByDescending { it.count }
            SortMode.SIZE -> list
        }
    }

    private fun toggleSort() {
        // Solo opciones que aplican a la vista actual: antes "Más videos"
        // en videos y "Tamaño" en carpetas se ofrecían pero no hacían nada.
        data class Opt(val label: String, val mode: SortMode, val asc: Boolean)
        val opts = if (showingFolders) listOf(
            Opt("Nombre A–Z", SortMode.NAME, true),
            Opt("Nombre Z–A", SortMode.NAME, false),
            Opt("Más videos primero", SortMode.COUNT, false)
        ) else listOf(
            Opt("Nombre A–Z", SortMode.NAME, true),
            Opt("Nombre Z–A", SortMode.NAME, false),
            Opt("Archivos más grandes", SortMode.SIZE, false)
        )
        val labels = opts.map { it.label }.toTypedArray()
        val checked = opts.indexOfFirst { it.mode == sortMode && it.asc == sortAsc }
            .takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle("Ordenar")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                sortMode = opts[which].mode
                sortAsc = opts[which].asc
                if (showingFolders) {
                    currentSubFolders = applySortToFolders(currentSubFolders)
                    showFolderGrid()
                } else {
                    // showVideoGrid ya aplica el orden al mostrar.
                    showVideoGrid()
                }
                btnSort.rotation = if (sortAsc) 0f else 180f
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** MENÚ unificado como en el gestor: un menú de opciones en vez de una
     *  acción directa distinta por pantalla. */
    private fun showTopMenu() {
        val items = arrayOf("Ordenar…", "Cambiar vista", "Buscar", "Gestor copiar/pegar")
        AlertDialog.Builder(this)
            .setTitle("Opciones")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> toggleSort()
                    1 -> toggleViewMode()
                    2 -> toggleSearchBar()
                    3 -> openManager()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toggleViewMode() {
        isGridView = !isGridView
        applySpanCount()
        // Re-publicar la lista actual conservando la posición del foco.
        if (showingFolders) showFolderGrid() else showVideoGrid()
    }

    // Gestor dual-pane con copiar/pegar (ExploreKF). Vive en su propia
    // actividad registrada en el Manifest; aquí solo el acceso directo.
    private fun openManager() {
        try {
            startActivity(android.content.Intent(this, ExploreKF::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el gestor", Toast.LENGTH_SHORT).show()
        }
    }
    // ExploreKF
    // ExploreKF// ExploreKF es el gestor dual-pane con copiar/pegar: se abre con el
    // botón "Gestor copiar/pegar" de la fila de acciones (ver openManager).

    private fun toggleSearchBar() {
        // En TV el EditText inline roba el foco del D-pad y abre el teclado en
        // el peor momento. Diálogo dedicado: OK busca, Atrás cancela, y el
        // foco vuelve solo a la lista. En móvil se mantiene la barra inline
        // con filtrado en vivo (ver TextWatcher en onCreate).
        if (isTvDevice) {
            val input = EditText(this).apply {
                hint = "Buscar archivo..."
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setText(searchQuery)
            }
            val box = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(40, 20, 40, 10)
                addView(input)
            }
            val dialog = AlertDialog.Builder(this)
                .setTitle("Buscar")
                .setView(box)
                .setPositiveButton("Buscar") { _, _ ->
                    searchQuery = input.text.toString().trim()
                    if (showingFolders && searchQuery.isNotBlank()) {
                        // La búsqueda filtra videos: saltar a la vista de videos.
                        showVideoGrid(restoreFocus = false)
                    } else {
                        showVideoGrid()
                    }
                }
                .setNeutralButton("Limpiar") { _, _ ->
                    searchQuery = ""
                    showVideoGrid()
                }
                .setNegativeButton("Cancelar", null)
                .create()
            dialog.show()
            input.post {
                input.requestFocus()
                input.setSelection(input.text.length)
            }
            return
        }
        if (etSearch.visibility == View.VISIBLE) {
            etSearch.visibility = View.GONE
            etSearch.text?.clear()
            hideKeyboard(etSearch)
            searchQuery = ""
            showVideoGrid()
            btnSearch.requestFocus()
        } else {
            etSearch.visibility = View.VISIBLE
            etSearch.setText(searchQuery)
            etSearch.requestFocus()
            showKeyboard(etSearch)
        }
    }

    private fun hideKeyboard(view: View) {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        } catch (_: Exception) {}
    }

    private fun showKeyboard(view: View) {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        } catch (_: Exception) {}
    }

    private fun performSearch() {
        searchQuery = etSearch.text.toString().trim()
        showVideoGrid()
    }

    private fun navigateBack() {
        if (etSearch.visibility == View.VISIBLE) {
            etSearch.visibility = View.GONE
            etSearch.text?.clear()
            searchQuery = ""
            showVideoGrid()
            return
        }

        // No dejar un escaneo corriendo al salir de la vista.
        netScanJob?.cancel()

        if (navStack.size > 1) {
            navStack.removeAt(navStack.lastIndex)
            val prevPath = navStack.last()

            when {
                prevPath == null || prevPath == "__all__" -> {
                    buildAndShowRoot()
                }
                prevPath == "__net__" -> {
                    enterNet()
                }
                prevPath.startsWith("fs:") -> {
                    openFileSystemDir(prevPath.substring(3), addToStack = false)
                }
                else -> {
                    currentSubFolders = buildRootFoldersForPath(prevPath)
                    currentVideos = videosInPath(prevPath)
                    tvPath.text = buildPathLabel(prevPath)
                    btnBack.visibility = if (navStack.size > 1) View.VISIBLE else View.GONE
                    if (currentSubFolders.isNotEmpty()) {
                        showFolderGrid()
                    } else {
                        showVideoGrid()
                    }
                }
            }
        } else {
            finish()
        }
    }

    private fun buildPathLabel(path: String): String {
        val clean = path.trim('/')
        val parts = clean.split('/')
        return if (parts.size <= 2) parts.last().replaceFirstChar { it.uppercase() }
        else ".../${parts.takeLast(2).joinToString("/")}"
    }

    private fun buildFileSystemPathLabel(fsPath: String): String {
        val clean = fsPath.trim('/')
        val parts = clean.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return "Raíz"
        val displayName = parts.last()
        return if (parts.size <= 2) displayName else ".../$displayName"
    }

    private fun openFileSystemDir(fsPath: String, addToStack: Boolean) {
        val navPath = "fs:$fsPath"
        if (addToStack && navStack.last() != navPath) navStack.add(navPath)
        showLoading("Cargando carpeta...")
        lifecycleScope.launch {
            val (subFolders, vids) = withContext(Dispatchers.IO) { listFileSystemDir(fsPath) }
            hideLoading()
            currentSubFolders = subFolders
            currentVideos = vids
            tvPath.text = buildFileSystemPathLabel(fsPath)
            btnBack.visibility = View.VISIBLE

            when {
                currentSubFolders.isNotEmpty() -> showFolderGrid()
                currentVideos.isNotEmpty() -> showVideoGrid()
                else -> {
                    tvEmptyContainer.visibility = View.VISIBLE
                    tvEmpty.text = "No hay videos ni carpetas aquí"
                    rvFolders.visibility = View.GONE
                    rvVideos.visibility = View.GONE
                }
            }
            if (isTvDevice) requestFocusOnCurrentView()
        }
    }

    // Conteo superficial (1 nivel, sin recursión): el recorrido recursivo
    // bloqueaba la UI con USB grandes en cajas modestas. El número exacto
    // se resuelve al entrar a la carpeta (listFileSystemDir).
    // Recursivo acotado (profundidad + topes): el conteo superficial mentía
    // en USB con subcarpetas y el ilimitado bloqueaba la UI en cajas
    // modestas. Corre en Dispatchers.IO (ver buildAndShowRoot).
    private fun countVideosInFileSystem(dir: File): Int {
        var count = 0
        var visited = 0
        fun walk(d: File, depth: Int) {
            if (depth < 0 || visited > 20000 || count > 9999) return
            val files = try { d.listFiles() } catch (_: Exception) { null } ?: return
            for (f in files) {
                if (visited > 20000 || count > 9999) return
                visited++
                try {
                    if (f.isDirectory) walk(f, depth - 1)
                    else if (f.isFile && isVideoFile(f.name)) count++
                } catch (_: Exception) { }
            }
        }
        try {
            walk(dir, 4)
        } catch (e: Exception) {
            android.util.Log.d(TAG, "countVideosInFileSystem: ${e.message}")
        }
        return count
    }

    private fun showLoading(text: String) {
        progressBar.visibility = View.VISIBLE
        rvVideos.visibility = View.GONE
        rvFolders.visibility = View.GONE
        tvEmptyContainer.visibility = View.GONE
    }

    private fun hideLoading() {
        progressBar.visibility = View.GONE
    }

    private var lastPlayMs: Long = 0L

    private fun playVideo(item: VideoItem) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastPlayMs < 1000) return
        lastPlayMs = now
        try {
            val intent = android.content.Intent(this, com.karin.streamtv.player.ExoPlayerActivity::class.java).apply {
                putExtra("video_url", item.uri)
                putExtra("video_title", item.title)
                putExtra("referer", "")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el video", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        navigateBack()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) {
            return onKeyDown(mapped, event)
        }
        // BACK solo por onBackPressed(): manejarlo aquí también consumía dos
        // niveles de pila por pulsación (API 33+ lo entrega por ambas vías).
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            navigateBack()
            return true
        }
        // Atajos de mando: misma interfaz, sin menús ocultos:
        // MENÚ = opciones (igual que en el gestor), SEARCH = buscar,
        // CH± / L1-R1 = pasar página.
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                if (event?.repeatCount == 0) showTopMenu()
                return true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                if (event?.repeatCount == 0) toggleSearchBar()
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                pageScroll(forward = true)
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                pageScroll(forward = false)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        videoAdapter?.destroy()
        folderAdapter = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
