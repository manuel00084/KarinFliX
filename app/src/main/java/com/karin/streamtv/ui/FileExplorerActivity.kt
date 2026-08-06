package com.karin.streamtv.ui

import android.Manifest
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
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
    private lateinit var btnBack: TextView
    private lateinit var tvPath: TextView
    private lateinit var btnSearch: TextView
    private lateinit var btnFolders: TextView
    private lateinit var etSearch: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmpty: TextView

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
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "FileExplorer"
        private const val REQUEST_STORAGE_PERMISSION = 5001
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
        etSearch = findViewById(R.id.et_search)
        progressBar = findViewById(R.id.progress_bar)
        tvEmpty = findViewById(R.id.tv_empty)

        rvFolders.visibility = View.GONE
        isTvDevice = DeviceUtils.isTvDevice(this)

        rvVideos.layoutManager = GridLayoutManager(this, 3)
        rvFolders.layoutManager = GridLayoutManager(this, 3)

        videoAdapter = VideoAdapter(emptyList(), cr) { item ->
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
        checkPermissionsAndLoad()
    }

    private fun checkPermissionsAndLoad() {
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadAllVideos()
            } else {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "Permiso de almacenamiento denegado. Activa el permiso en Configuración."
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun setupTvNavigation() {
        if (!isTvDevice) return

        val topBarButtons = listOf(btnBack, btnSearch, btnFolders)
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
        tvEmpty.visibility = View.GONE

        lifecycleScope.launch {
            allVideos = withContext(Dispatchers.IO) { queryAllVideos() }

            hideLoading()

            if (allVideos.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "No se encontraron videos en el dispositivo"
                return@launch
            }

            navStack.clear()
            navStack.add(null)
            searchQuery = ""
            etSearch.visibility = View.GONE

            buildAndShowRoot()

            if (isTvDevice) requestFocusOnCurrentView()
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
                    name = "📁 ${child.name.replaceFirstChar { it.uppercase() }}",
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
            "download", "downloads" -> "📁 Descargas"
            "video", "videos", "movie", "movies" -> "🎬 Videos"
            "picture", "pictures", "photo", "photos", "dcim", "image", "images", "screenshot", "screenshots" -> "📸 Imágenes"
            "document", "documents", "docs" -> "📄 Documentos"
            "music", "audio" -> "🎵 Música"
            "android" -> "🤖 Android"
            "" -> "📁 Raíz"
            else -> "📁 ${raw.trim('/').replaceFirstChar { it.uppercase() }}"
        }
    }

    private fun buildAndShowRoot() {
        val folders = mutableListOf<FolderItem>()

        folders.add(FolderItem(name = "🎬 Todos los videos", path = "__all__", count = allVideos.size))

        val volumes = detectStorageVolumes()
        for (vol in volumes) {
            val volCount = if (vol.path.startsWith("/storage/emulated/0")) {
                allVideos.size
            } else {
                countVideosInFileSystem(File(vol.path))
            }
            folders.add(FolderItem(name = "💾 ${vol.name}", path = "fs:${vol.path}", count = volCount))
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
            folders.add(FolderItem(name = "📁 Raíz (${rootFileCount})", path = "", count = rootFileCount))
        }

        topLevelDirs.forEach { (dir, count) ->
            folders.add(FolderItem(name = normalizeFolderName(dir), path = dir, count = count))
        }

        currentSubFolders = folders
        currentVideos = allVideos
        searchQuery = ""

        tvPath.text = "📁 Explorador de videos"
        btnBack.visibility = View.GONE
        showFolderGrid()
    }

    private fun browseTo(folder: FolderItem) {
        searchQuery = ""
        etSearch.visibility = View.GONE

        when {
            folder.path == "__all__" -> {
                if (navStack.last() != "__all__") navStack.add("__all__")
                currentSubFolders = buildRootFoldersForPath("")
                currentVideos = allVideos
                tvPath.text = "🎬 Todos los videos"
                btnBack.visibility = View.VISIBLE
                showVideoGrid()
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
                tvPath.text = "🎬 Videos (carpeta + sub)"
                showVideoGrid()
                return
            }
            folder.path == "__volume__" -> {
                navStack.add(null)
                buildAndShowRoot()
                return
            }
            folder.path?.startsWith("fs:") == true -> {
                val fsPath = folder.path.substring(3)
                if (navStack.last() != folder.path) navStack.add(folder.path)
                val (subFolders, vids) = listFileSystemDir(fsPath)
                currentSubFolders = subFolders
                currentVideos = vids
                tvPath.text = buildFileSystemPathLabel(fsPath)
                btnBack.visibility = View.VISIBLE

                if (currentSubFolders.isNotEmpty()) {
                    showFolderGrid()
                } else if (currentVideos.isNotEmpty()) {
                    showVideoGrid()
                } else {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "No hay videos ni carpetas aquí"
                    rvFolders.visibility = View.GONE
                    rvVideos.visibility = View.GONE
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
                result.add(0, FolderItem(name = "🎬 Ver videos (${rootVideos.size})", path = "__videos_in_path__", count = rootVideos.size))
            }
        } else {
            val videoCount = currentVideos.size
            if (videoCount > 0) {
                result.add(0, FolderItem(name = "🎬 Ver videos ($videoCount)", path = "__videos_in_path__", count = videoCount))
            }
            val allCount = allVideos.count { v ->
                val rp = v.relativePath?.trim('/') ?: ""
                rp.startsWith("$basePath/")
            }
            if (allCount > videoCount) {
                result.add(FolderItem(name = "🎬 Videos de esta carpeta (incl. sub)", path = "__all_in_path__", count = allCount))
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
        btnFolders.text = "🎬"
        folderAdapter?.submitList(currentSubFolders)

        tvEmpty.visibility = if (currentSubFolders.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = "No hay carpetas disponibles"

        if (isTvDevice) rvFolders.post { rvFolders.requestFocus() }
    }

    private fun showVideoGrid() {
        showingFolders = false
        rvFolders.visibility = View.GONE
        rvVideos.visibility = View.VISIBLE
        btnFolders.text = "📂"

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

        tvEmpty.visibility = if (displayVideos.isEmpty()) View.VISIBLE else View.GONE
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
                prevPath.startsWith("fs:") -> {
                    val fsPath = prevPath.substring(3)
                    val (subFolders, vids) = listFileSystemDir(fsPath)
                    currentSubFolders = subFolders
                    currentVideos = vids
                    tvPath.text = buildFileSystemPathLabel(fsPath)
                    btnBack.visibility = View.VISIBLE
                    if (currentSubFolders.isNotEmpty()) {
                        showFolderGrid()
                    } else {
                        showVideoGrid()
                    }
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
        return if (parts.size <= 2) "📁 ${parts.last().replaceFirstChar { it.uppercase() }}"
        else "📁 .../${parts.takeLast(2).joinToString("/")}"
    }

    private fun buildFileSystemPathLabel(fsPath: String): String {
        val clean = fsPath.trim('/')
        val parts = clean.split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return "📁 Raíz"
        val displayName = parts.last()
        val shortName = if (parts.size <= 2) displayName else "..."
        val icon = if (fsPath.startsWith("/storage/emulated/0")) "💾" else "📂"
        return "$icon $shortName"
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
        tvEmpty.visibility = View.GONE
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
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
