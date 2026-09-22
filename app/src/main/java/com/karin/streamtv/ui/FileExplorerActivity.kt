package com.karin.streamtv.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
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
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.karinlink.CloudClients
import com.karin.streamtv.karinlink.CloudEntry
import com.karin.streamtv.karinlink.CloudHttpProxy
import com.karin.streamtv.karinlink.CloudProvider
import com.karin.streamtv.karinlink.CloudRef
import com.karin.streamtv.karinlink.CloudResult
import com.karin.streamtv.karinlink.CloudTokenStore
import com.karin.streamtv.karinlink.SmbClient
import com.karin.streamtv.karinlink.SmbHttpProxy
import com.karin.streamtv.karinlink.SmbRef
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.GamepadHelper
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.Dispatchers
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
    private lateinit var btnPanes: ImageView
    private lateinit var btnExit: ImageView
    private lateinit var etSearch: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var tvEmptyContainer: View
    private lateinit var btnAddNetwork: TextView

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
    private val handler = Handler(Looper.getMainLooper())

    private var smbMode = false
    private var smbConn: SmbRef? = null
    private var smbCurrent: SmbRef? = null

    private var cloudMode = false
    private var cloudConn: CloudProvider? = null
    private var cloudCurrent: CloudRef? = null

    companion object {
        private const val TAG = "FileExplorer"
        private const val REQUEST_STORAGE_PERMISSION = 5001
        private const val REQUEST_MANAGE_STORAGE = 5002
    }

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
        btnPanes = findViewById(R.id.btn_panes)
        btnExit = findViewById(R.id.btn_exit)
        etSearch = findViewById(R.id.et_search)
        progressBar = findViewById(R.id.progress_bar)
        tvEmpty = findViewById(R.id.tv_empty)
        tvEmptyContainer = findViewById(R.id.tv_empty_container)
        btnAddNetwork = findViewById(R.id.btn_add_network)

        rvFolders.visibility = View.GONE
        isTvDevice = DeviceUtils.isTvDevice(this)

        rvVideos.layoutManager = GridLayoutManager(this, 3)
        rvFolders.layoutManager = GridLayoutManager(this, 3)

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

        btnPanes.setOnClickListener { openExploreKf() }
        btnPanes.onActionKey { openExploreKf() }

        btnAddNetwork.setOnClickListener { showAddNetworkWizard() }
        btnAddNetwork.onActionKey { showAddNetworkWizard() }

        btnExit.setOnClickListener { finish() }
        btnExit.onActionKey { finish() }

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        etSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                etSearch.clearFocus()
                requestFocusOnCurrentView()
                true
            } else false
        }

        setupTvNavigation()
        CloudTokenStore.init(this)
        checkPermissionsAndLoad()
    }

    private fun checkPermissionsAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:${packageName}")
            )
            try {
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE)
            } catch (e: Exception) {
                startActivityForResult(
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
                    REQUEST_MANAGE_STORAGE
                )
            }
            return
        }

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
            checkPermissionsAndLoad()
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

    private fun setupTvNavigation() {
        if (!isTvDevice) return

        val topBarButtons = listOf<android.view.View>(btnBack, btnSearch, btnFolders, btnSort, btnView, btnPanes, btnExit)
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
                    val llm = recyclerView.layoutManager as? GridLayoutManager
                    val firstVisible = llm?.findFirstVisibleItemPosition() ?: 0
                    if (firstVisible <= 0) {
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
                tvEmpty.text = "No se encontraron videos en el dispositivo"
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

        if (smbMode) {
            folders.add(FolderItem(name = "Red", path = "__smb__", count = 0))
        }

        if (CloudTokenStore.configuredCount() > 0) {
            folders.add(FolderItem(name = "Nube (${CloudTokenStore.configuredCount()})", path = "__cloud__", count = 0))
        }

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
            folder.path == "__smb__" -> {
                if (navStack.last() != "__smb__") navStack.add("__smb__")
                loadSmbShares()
                return
            }
            folder.path == "__videos_in_path__" -> {
                showVideoGrid()
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
            folder.path == "__volume__" -> {
                navStack.add(null)
                buildAndShowRoot()
                return
            }
            folder.path?.startsWith("fs:") == true -> {
                openFileSystemDir(folder.path.substring(3), addToStack = true)
                return
            }
            folder.path?.startsWith("__smb_share__:") == true -> {
                val share = folder.path.removePrefix("__smb_share__:")
                if (navStack.last() != folder.path) navStack.add(folder.path)
                loadSmbDir(share, "/")
                return
            }
            folder.path?.startsWith("__smb_dir__:") == true -> {
                val payload = folder.path.removePrefix("__smb_dir__:")
                val sep = payload.indexOf(':')
                if (sep > 0) {
                    val share = payload.substring(0, sep)
                    val remotePath = payload.substring(sep + 1)
                    if (navStack.last() != folder.path) navStack.add(folder.path)
                    loadSmbDir(share, remotePath)
                }
                return
            }
            folder.path == "__cloud__" -> {
                if (navStack.last() != "__cloud__") navStack.add("__cloud__")
                showCloudProviders()
                return
            }
            folder.path?.startsWith("__cloud_provider__:") == true -> {
                val providerId = folder.path.removePrefix("__cloud_provider__:")
                val provider = CloudProvider.fromId(providerId)
                if (provider != null) {
                    if (navStack.last() != folder.path) navStack.add(folder.path)
                    cloudConn = provider
                    cloudCurrent = null
                    cloudMode = true
                    loadCloudRoot(provider)
                }
                return
            }
            folder.path?.startsWith("__cloud_dir__:") == true -> {
                val payload = folder.path.removePrefix("__cloud_dir__:")
                val sep = payload.indexOf(':')
                if (sep > 0) {
                    val provider = CloudProvider.fromId(payload.substring(0, sep))
                    val folderId = cnvDecode(payload.substring(sep + 1))
                    if (provider != null) {
                        if (navStack.last() != folder.path) navStack.add(folder.path)
                        cloudConn = provider
                        cloudMode = true
                        loadCloudDir(provider, folderId ?: "")
                    }
                }
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

    private fun showFolderGrid() {
        showingFolders = true
        rvVideos.visibility = View.GONE
        rvFolders.visibility = View.VISIBLE
        btnFolders.setImageResource(R.drawable.ic_video)
        folderAdapter?.submitList(currentSubFolders)

        tvEmptyContainer.visibility = if (currentSubFolders.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = "No hay carpetas disponibles"

        if (isTvDevice) rvFolders.post { rvFolders.requestFocus() }
    }

    private fun showVideoGrid() {
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
        }

        videoAdapter?.submitList(displayVideos)

        tvEmptyContainer.visibility = if (displayVideos.isEmpty()) View.VISIBLE else View.GONE
        if (searchQuery.isNotBlank()) {
            tvEmpty.text = "Sin resultados para '$searchQuery'"
        } else if (navStack.size <= 1) {
            tvEmpty.text = "No se encontraron videos"
        } else {
            tvEmpty.text = "No hay videos en esta carpeta"
        }

        if (isTvDevice && displayVideos.isNotEmpty()) rvVideos.post { rvVideos.requestFocus() }
    }

    private fun toggleView() {
        if (showingFolders) {
            showVideoGrid()
        } else {
            showFolderGrid()
        }
    }

    private fun toggleSort() {
        sortAsc = !sortAsc
        if (showingFolders) {
            val sorted = if (sortAsc) currentSubFolders.sortedBy { it.name }
                         else currentSubFolders.sortedByDescending { it.name }
            currentSubFolders = sorted
            folderAdapter?.submitList(sorted)
        } else {
            val sorted = if (sortAsc) currentVideos.sortedBy { it.title }
                         else currentVideos.sortedByDescending { it.title }
            currentVideos = sorted
            videoAdapter?.submitList(sorted)
        }
        btnSort.rotation = if (sortAsc) 0f else 180f
    }

    private fun toggleViewMode() {
        isGridView = !isGridView
        if (isGridView) {
            rvVideos.layoutManager = GridLayoutManager(this, 3)
            rvFolders.layoutManager = GridLayoutManager(this, 3)
            btnView.setImageResource(R.drawable.ic_grid)
        } else {
            rvVideos.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
            rvFolders.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
            btnView.setImageResource(R.drawable.ic_list)
        }
    }

    // ---------- WIZARD ----------

    private fun showAddNetworkWizard() {
        val ctx = this
        val items = arrayOf(
            "SMB / Red (Windows, Linux)",
            "Red de Windows (SMB)",
            "NAS (SMB / NFS)",
            "Dropbox",
            "Google Drive"
        )
        val icons = arrayOf(
            R.drawable.ic_network_smb,
            R.drawable.ic_lan,
            R.drawable.ic_nas,
            R.drawable.ic_dropbox,
            R.drawable.ic_gdrive
        )
        val descriptions = arrayOf(
            "Conectar a un servidor SMB/CIFS",
            "Explorar recursos compartidos en red",
            "Conectar a un NAS Synology, QNAP, etc.",
            "Explorar archivos en Dropbox",
            "Explorar archivos en Google Drive"
        )

        // "Red de Windows en el explorador" (Ajustes > KARIN Link) controla si se
        // ofrece conexión SMB en este asistente.
        val smbVisible = com.karin.streamtv.util.AppPreferences.isSmbShowOnHome()
        val origIndices = if (smbVisible) items.indices.toList() else items.indices.filter { it >= 3 }

        val listItems = origIndices.map { i ->
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(24, 16, 24, 16)
                isFocusable = true
                isFocusableInTouchMode = true
                background = android.util.TypedValue().let {
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
                    androidx.core.content.ContextCompat.getDrawable(ctx, it.resourceId)
                }
            }

            val icon = android.widget.ImageView(ctx).apply {
                setImageResource(icons[i])
                layoutParams = android.widget.LinearLayout.LayoutParams(48, 48).apply {
                    marginEnd = 20
                }
                val tint = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(ctx, R.color.accent)
                )
                imageTintList = tint
            }

            val textCol = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val title = android.widget.TextView(ctx).apply {
                text = items[i]
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_primary))
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }

            val desc = android.widget.TextView(ctx).apply {
                text = descriptions[i]
                setTextColor(androidx.core.content.ContextCompat.getColor(ctx, R.color.text_secondary))
                textSize = 12f
                setPadding(0, 2, 0, 0)
            }

            textCol.addView(title)
            textCol.addView(desc)
            row.addView(icon)
            row.addView(textCol)
            row
        }

        val scrollView = android.widget.ScrollView(ctx).apply {
            val wrapper = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            listItems.forEach { wrapper.addView(it) }
            addView(wrapper)
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Añadir almacenamiento")
            .setView(scrollView)
            .setNegativeButton("Cancelar", null)
            .create()

        listItems.forEachIndexed { pos, row ->
            row.setOnClickListener {
                dialog.dismiss()
                when (origIndices[pos]) {
                    0, 1, 2 -> showSmbConnectDialog()
                    3 -> showCloudManageDialogFor(CloudProvider.DROPBOX)
                    4 -> showCloudManageDialogFor(CloudProvider.GOOGLE_DRIVE)
                }
            }
        }

        dialog.show()
    }

    private fun showCloudManageDialogFor(provider: CloudProvider) {
        val has = CloudTokenStore.hasToken(provider)
        val items = mutableListOf<String>()
        if (has) items.add("Conectar y explorar")
        items.add("Introducir / cambiar token")
        if (has) items.add("Eliminar token")
        items.add("Ayuda: cómo obtener el token")
        AlertDialog.Builder(this)
            .setTitle(provider.displayName)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Conectar y explorar" -> {
                        cloudConn = provider
                        cloudMode = true
                        navStack.clear()
                        navStack.add("__cloud__")
                        navStack.add("__cloud_provider__:${provider.id}")
                        loadCloudRoot(provider)
                    }
                    "Introducir / cambiar token" -> showCloudTokenDialog(provider)
                    "Eliminar token" -> {
                        CloudTokenStore.setToken(provider, null)
                        Toast.makeText(this, "Token de ${provider.displayName} eliminado", Toast.LENGTH_SHORT).show()
                        if (cloudMode && cloudConn == provider) exitCloud() else buildAndShowRoot()
                    }
                    else -> onCloudHelp(provider)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------- SMB ----------

    private fun showSmbConnectDialog() {
        val host = EditText(this).apply { hint = "IP del servidor (ej. 192.168.1.20)" }
        val port = EditText(this).apply { hint = "Puerto (445)"; setText("445") }
        val user = EditText(this).apply { hint = "Usuario (opcional)" }
        val pass = EditText(this).apply {
            hint = "Contraseña (opcional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
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
                smbConn = conn
                smbCurrent = null
                smbMode = true
                navStack.clear()
                navStack.add("__smb__")
                loadSmbShares()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadSmbShares() {
        val conn = smbConn ?: return
        showLoading("Conectando a red...")
        lifecycleScope.launch {
            val entries = withContext(Dispatchers.IO) {
                when (val res = SmbClient.listShares(conn.host, conn.port, conn.user, conn.password)) {
                    is SmbClient.SmbResult.Shares -> res.shares.map { share ->
                        FolderItem(name = share, path = "__smb_share__:$share", count = 0)
                    }
                    is SmbClient.SmbResult.Error -> {
                        withContext(Dispatchers.Main) { Toast.makeText(this@FileExplorerActivity, res.message, Toast.LENGTH_LONG).show() }
                        smbMode = false
                        emptyList()
                    }
                    else -> emptyList()
                }
            }
            hideLoading()
            if (!smbMode) {
                navStack.clear()
                navStack.add(null)
                buildAndShowRoot()
                return@launch
            }
            currentSubFolders = entries
            currentVideos = emptyList()
            tvPath.text = "Red: ${conn.hostPort}"
            btnBack.visibility = View.VISIBLE
            btnAddNetwork.text = "✕"
            btnAddNetwork.setOnClickListener { disconnectSmb() }
            btnAddNetwork.onActionKey { disconnectSmb() }
            showFolderGrid()
            if (isTvDevice) requestFocusOnCurrentView()
        }
    }

    private fun loadSmbDir(share: String, remotePath: String) {
        val conn = smbConn ?: return
        val ref = conn.copy(share = share, remotePath = remotePath)
        smbCurrent = ref
        showLoading("Cargando carpeta...")
        lifecycleScope.launch {
            val entries: Pair<List<FolderItem>, List<VideoItem>> = withContext(Dispatchers.IO) {
                when (val res = SmbClient.list(ref)) {
                    is SmbClient.SmbResult.Entries -> {
                        val folders = mutableListOf<FolderItem>()
                        val videos = mutableListOf<VideoItem>()
                        for (info in res.entries) {
                            val childPath = if (remotePath.endsWith("/")) "$remotePath${info.name}" else "$remotePath/${info.name}"
                            if (info.isDir) {
                                folders.add(FolderItem(name = info.name, path = "__smb_dir__:$share:$childPath", count = 0))
                            } else if (isVideoFile(info.name)) {
                                val smbRef = ref.copy(remotePath = childPath)
                                val proxyUrl = SmbHttpProxy.proxyUrl(smbRef)
                                videos.add(VideoItem(
                                    id = info.name.hashCode().toLong(),
                                    title = info.name.substringBeforeLast('.'),
                                    uri = proxyUrl,
                                    durationMs = 0L,
                                    folder = share,
                                    relativePath = childPath,
                                    sizeBytes = info.size
                                ))
                            }
                        }
                        Pair(folders, videos)
                    }
                    is SmbClient.SmbResult.Error -> {
                        withContext(Dispatchers.Main) { Toast.makeText(this@FileExplorerActivity, res.message, Toast.LENGTH_LONG).show() }
                        Pair(emptyList(), emptyList())
                    }
                    else -> Pair(emptyList(), emptyList())
                }
            }
            hideLoading()
            currentSubFolders = entries.first
            currentVideos = entries.second
            val label = if (remotePath == "/") share else ".../$share${remotePath}"
            tvPath.text = "Red: $label"
            btnBack.visibility = View.VISIBLE
            if (currentSubFolders.isNotEmpty()) {
                showFolderGrid()
            } else if (currentVideos.isNotEmpty()) {
                showVideoGrid()
            } else {
                tvEmptyContainer.visibility = View.VISIBLE
                tvEmpty.text = "No hay videos ni carpetas aquí"
                rvFolders.visibility = View.GONE
                rvVideos.visibility = View.GONE
            }
        }
    }

    private fun disconnectSmb() {
        smbMode = false
        smbConn = null
        smbCurrent = null
        navStack.clear()
        navStack.add(null)
        btnAddNetwork.text = "+"
        btnAddNetwork.setOnClickListener { showAddNetworkWizard() }
        btnAddNetwork.onActionKey { showAddNetworkWizard() }
        buildAndShowRoot()
    }

    // ---------- NUBE ----------

    private fun cnvEncode(s: String): String =
        android.util.Base64.encodeToString(s.toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)

    private fun cnvDecode(s: String): String? =
        try { String(android.util.Base64.decode(s, android.util.Base64.URL_SAFE), Charsets.UTF_8) }
        catch (_: Exception) { null }

    private fun showCloudManageDialog() {
        val providers = CloudProvider.entries
        val labels = providers.map { p ->
            val state = if (CloudTokenStore.hasToken(p)) "configurado" else "no configurado"
            "${p.displayName} ($state)"
        }
        AlertDialog.Builder(this)
            .setTitle("Servicios en la nube")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which in providers.indices) showCloudProviderSettings(providers[which])
            }
            .setNeutralButton("Cerrar", null)
            .show()
    }

    private fun showCloudProviderSettings(provider: CloudProvider) {
        val has = CloudTokenStore.hasToken(provider)
        val items = mutableListOf<String>()
        if (has) items.add("Conectar y explorar")
        items.add("Introducir / cambiar token")
        if (has) items.add("Eliminar token")
        items.add("Ayuda: cómo obtener el token")
        AlertDialog.Builder(this)
            .setTitle(provider.displayName)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Conectar y explorar" -> {
                        cloudConn = provider
                        cloudMode = true
                        navStack.clear()
                        navStack.add("__cloud__")
                        navStack.add("__cloud_provider__:${provider.id}")
                        loadCloudRoot(provider)
                    }
                    "Introducir / cambiar token" -> showCloudTokenDialog(provider)
                    "Eliminar token" -> {
                        CloudTokenStore.setToken(provider, null)
                        Toast.makeText(this, "Token de ${provider.displayName} eliminado", Toast.LENGTH_SHORT).show()
                        if (cloudMode && cloudConn == provider) exitCloud() else buildAndShowRoot()
                    }
                    else -> onCloudHelp(provider)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showCloudTokenDialog(provider: CloudProvider) {
        val input = EditText(this).apply {
            hint = "Pega tu token de acceso"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(CloudTokenStore.getToken(provider) ?: "")
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("Token de ${provider.displayName}")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isBlank()) {
                    Toast.makeText(this, "El token no puede estar vacío", Toast.LENGTH_SHORT).show()
                } else {
                    CloudTokenStore.setToken(provider, t)
                    Toast.makeText(this, "Token de ${provider.displayName} guardado", Toast.LENGTH_SHORT).show()
                    buildAndShowRoot()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun onCloudHelp(provider: CloudProvider) {
        val msg = when (provider) {
            CloudProvider.DROPBOX ->
                "En dropbox.com/developers/apps crea una app (tipo \"Full Dropbox\" o\n" +
                "\"App folder\") y en la pestaña \"Permissions\" activa files.content.read\n" +
                "y files.metadata.read. Luego genera un token de acceso (Access token)\n" +
                "de larga duración y pégalo aquí. La app solo lo guarda en tu dispositivo."
            else ->
                "Necesitas un Access token de Google OAuth2 (scope drive.readonly).\n" +
                "Ejemplos: consola de Google Cloud > OAuth 2.0 Playground, o usa un\n" +
                "token de acceso breve de tu cuenta. Pégalo aquí. Puede caducar;\n" +
                "si ves \"401\", actualiza el token."
        }
        AlertDialog.Builder(this)
            .setTitle("Cómo obtener el token · ${provider.displayName}")
            .setMessage(msg)
            .setPositiveButton("Entendido", null)
            .show()
    }

    private fun showCloudProviders() {
        val providers = CloudTokenStore.allConfigured()
        val folders = providers.map { p ->
            FolderItem(name = p.displayName, path = "__cloud_provider__:${p.id}", count = 0)
        }
        currentSubFolders = folders
        currentVideos = emptyList()
        tvPath.text = "Nube"
        btnBack.visibility = View.VISIBLE
        showFolderGrid()
        if (isTvDevice) requestFocusOnCurrentView()
    }

    private fun exitCloud() {
        cloudMode = false
        cloudConn = null
        cloudCurrent = null
        navStack.clear()
        navStack.add(null)
        buildAndShowRoot()
    }

    private fun loadCloudRoot(provider: CloudProvider) {
        loadCloudDir(provider, "")
    }

    private fun loadCloudDir(provider: CloudProvider, folderId: String) {
        val client = CloudClients.forProvider(provider) ?: return
        val token = CloudTokenStore.getToken(provider)
        if (token.isNullOrBlank()) {
            Toast.makeText(this, "Configura el token de ${provider.displayName}", Toast.LENGTH_LONG).show()
            showCloudProviderSettings(provider)
            return
        }
        val parentLabel = provider.displayName + (if (folderId.isBlank()) "" else " / $folderId")
        val ref = CloudRef(provider = provider, name = parentLabel, folderId = folderId, fileId = "", path = folderId)
        cloudCurrent = ref
        showLoading("Cargando ${provider.displayName}...")
        lifecycleScope.launch {
            val entries: Pair<List<FolderItem>, List<VideoItem>> = withContext(Dispatchers.IO) {
                when (val res = client.list(ref, token)) {
                    is CloudResult.Entries -> {
                        val folders = res.folders.map { e ->
                            FolderItem(
                                name = e.name,
                                path = "__cloud_dir__:${provider.id}:${cnvEncode(e.fileId)}",
                                count = 0
                            )
                        }
                        val videos = res.files.filter { isVideoFile(it.name) }.map { e ->
                            val videoRef = CloudRef(
                                provider = provider,
                                name = e.name,
                                folderId = folderId,
                                fileId = e.fileId,
                                path = (if (folderId.isBlank()) "" else "/$folderId") + "/" + e.name
                            )
                            VideoItem(
                                id = e.name.hashCode().toLong(),
                                title = e.name.substringBeforeLast('.'),
                                uri = CloudHttpProxy.proxyUrl(videoRef),
                                durationMs = 0L,
                                folder = provider.displayName,
                                relativePath = videoRef.path,
                                sizeBytes = e.sizeBytes
                            )
                        }
                        Pair(folders, videos)
                    }
                    is CloudResult.Error -> {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@FileExplorerActivity, res.message, Toast.LENGTH_LONG).show()
                        }
                        Pair(emptyList(), emptyList())
                    }
                    else -> Pair(emptyList(), emptyList())
                }
            }
            hideLoading()
            currentSubFolders = entries.first
            currentVideos = entries.second
            tvPath.text = parentLabel
            btnBack.visibility = View.VISIBLE
            if (currentSubFolders.isNotEmpty()) {
                showFolderGrid()
            } else if (currentVideos.isNotEmpty()) {
                showVideoGrid()
            } else {
                tvEmptyContainer.visibility = View.VISIBLE
                tvEmpty.text = "No hay videos ni carpetas aquí"
                rvFolders.visibility = View.GONE
                rvVideos.visibility = View.GONE
            }
        }
    }

    private fun openExploreKf() {
        val startPath = "/storage/emulated/0"
        try {
            val intent = android.content.Intent(this, ExploreKF::class.java).apply {
                putExtra("start_path", startPath)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se pudo abrir el gestor", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleSearchBar() {
        if (etSearch.visibility == View.VISIBLE) {
            etSearch.visibility = View.GONE
            etSearch.text?.clear()
            searchQuery = ""
            showVideoGrid()
            btnSearch.requestFocus()
        } else {
            etSearch.visibility = View.VISIBLE
            etSearch.setText(searchQuery)
            etSearch.requestFocus()
        }
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

        if (navStack.size > 1) {
            navStack.removeAt(navStack.lastIndex)
            val prevPath = navStack.last()

            when {
                prevPath == null || prevPath == "__all__" -> {
                    buildAndShowRoot()
                }
                prevPath == "__smb__" -> {
                    loadSmbShares()
                }
                prevPath == "__cloud__" -> {
                    showCloudProviders()
                }
                prevPath.startsWith("__cloud_provider__:") -> {
                    val provider = CloudProvider.fromId(prevPath.removePrefix("__cloud_provider__:"))
                    if (provider != null) loadCloudRoot(provider)
                }
                prevPath.startsWith("__cloud_dir__:") -> {
                    val payload = prevPath.removePrefix("__cloud_dir__:")
                    val sep = payload.indexOf(':')
                    if (sep > 0) {
                        val provider = CloudProvider.fromId(payload.substring(0, sep))
                        val folderId = cnvDecode(payload.substring(sep + 1))
                        if (provider != null && folderId != null) loadCloudDir(provider, folderId)
                    }
                }
                prevPath.startsWith("__smb_share__:") -> {
                    val share = prevPath.removePrefix("__smb_share__:")
                    loadSmbDir(share, "/")
                }
                prevPath.startsWith("__smb_dir__:") -> {
                    val payload = prevPath.removePrefix("__smb_dir__:")
                    val sep = payload.indexOf(':')
                    if (sep > 0) {
                        val share = payload.substring(0, sep)
                        val remotePath = payload.substring(sep + 1)
                        loadSmbDir(share, remotePath)
                    }
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

    private fun countVideosInFileSystem(dir: File): Int {
        var count = 0
        try {
            val files = dir.listFiles() ?: return 0
            for (f in files) {
                if (f.isDirectory && f.canRead()) {
                    count += countVideosInFileSystem(f)
                } else if (f.isFile && f.canRead() && isVideoFile(f.name)) {
                    count++
                }
            }
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

    private fun playVideo(item: VideoItem) {
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
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            navigateBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        videoAdapter?.destroy()
        folderAdapter = null
        CloudHttpProxy.stop()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
