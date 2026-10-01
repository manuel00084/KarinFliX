package com.karin.streamtv.ui

import android.app.WallpaperManager
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.karin.streamtv.R
import com.karin.streamtv.player.Karin3DPhoto
import com.karin.streamtv.util.DeviceUtils
import com.karin.streamtv.util.GamepadHelper
import com.karin.streamtv.util.onActionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Galería Karin: muestra todas las imágenes de fondo del programa
 * como un pequeño visor (miniaturas + vista a pantalla completa).
 */
class GaleriaKarinActivity : FragmentActivity() {

    data class GalleryItem(val name: String, val resId: Int)

    private val items = listOf(
        GalleryItem("Fondo app", R.drawable.fondo_app),
        GalleryItem("Splash", R.drawable.splash_bg),
        GalleryItem("Cargando", R.drawable.splash_loading),
        GalleryItem("Términos", R.drawable.terms_bg),
        GalleryItem("KarinLink", R.drawable.karinlink_bg),
        GalleryItem("Tutorial gafas", R.drawable.bg_glasses_tutorial),
        GalleryItem("Error", R.drawable.error_page),
        GalleryItem("Banner", R.drawable.banner),
        GalleryItem("Logo KarinFLiX", R.drawable.logo_karinflix),
        GalleryItem("Servidores Karin", R.drawable.karin_servidores_1280x720),
        GalleryItem("Maid futurista", R.drawable.variant_futuristic_maid_bodysuit_pink_white_restored),
        GalleryItem("Maid café Sakura", R.drawable.sakura_maid_cafe_scene),
        GalleryItem("Chica gatos manga", R.drawable.chica_gatos_3d_poses_manga_extravagante),
        GalleryItem("Vestido de verano", R.drawable.variant_short_summer_resort_dress),
        GalleryItem("Gato y palomitas", R.drawable.chica_furiosa_gato_se_comio_palomitas),
        GalleryItem("Noche de peli", R.drawable.chica_gato_comiendo_palomitas_explosion_tv),
        GalleryItem("Leyendo con gafas", R.drawable.character_glasses_reading),
        GalleryItem("Lectura en sillón", R.drawable.character_glasses_reading_armchair_two_legs),
        GalleryItem("Sofá, TV y palomitas", R.drawable.chica_sofa_tv_3d_gato_palomitas),
        GalleryItem("Peli en 3D", R.drawable.chica_sofa_tv_pelicula_3d_palomitas),
        GalleryItem("Manualidades 3D", R.drawable.chica_manualidades_gafas_3d_triunfo),
        GalleryItem("Gafas 3D divertidas", R.drawable.chica_gafas_3d_puestas_divertida),
        GalleryItem("Taller de gafas 3D", R.drawable.chica_manualidades_gafas_3d),
        GalleryItem("Maid café final", R.drawable.edited_maid_cafe_final_1280x720),
        GalleryItem("Torneo del Streaming", R.drawable.karin_torneo_streaming),
        GalleryItem("Guardiana de la Videoteca", R.drawable.karin_guardiana_videoteca),
        GalleryItem("Neón nocturno", R.drawable.karin_neon_rooftop),
        GalleryItem("Otaku Power", R.drawable.karin_otaku_power),
        GalleryItem("Neón nocturno II", R.drawable.karin_neon_rooftop_2),
        GalleryItem("Reverencia maid", R.drawable.karin_maid_reverencia),
    )

    private lateinit var rvGallery: RecyclerView
    private lateinit var tvCounter: TextView
    private lateinit var viewerOverlay: View
    private lateinit var ivViewer: ImageView
    private lateinit var tvViewerName: TextView
    private lateinit var btn3d: TextView
    private var viewerIndex = -1
    // 3D de fotos (CPU): OFF → anaglifo pseudo → SBS duplicado.
    // Pulfrich no aplica (necesita movimiento entre cuadros).
    private var photo3dMode = Karin3DPhoto.PHOTO_OFF
    private var photoJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_galeria_karin)

        rvGallery = findViewById(R.id.rv_gallery)
        tvCounter = findViewById(R.id.tv_counter)
        viewerOverlay = findViewById(R.id.viewer_overlay)
        ivViewer = findViewById(R.id.iv_viewer)
        tvViewerName = findViewById(R.id.tv_viewer_name)

        tvCounter.text = "${items.size} fondos"

        rvGallery.layoutManager = GridLayoutManager(this, computeSpanCount())
        rvGallery.adapter = GalleryAdapter(items) { position -> openViewer(position) }

        findViewById<TextView>(R.id.btn_back).apply {
            setOnClickListener { finish() }
            onActionKey { performClick() }
        }
        findViewById<TextView>(R.id.btn_close).apply {
            setOnClickListener { closeViewer() }
            onActionKey { performClick() }
        }
        findViewById<TextView>(R.id.btn_prev).apply {
            setOnClickListener { stepViewer(-1) }
            onActionKey { performClick() }
        }
        findViewById<TextView>(R.id.btn_next).apply {
            setOnClickListener { stepViewer(1) }
            onActionKey { performClick() }
        }
        findViewById<TextView>(R.id.btn_wallpaper).apply {
            setOnClickListener { setAsWallpaper() }
            onActionKey { performClick() }
        }
        btn3d = findViewById<TextView>(R.id.btn_3d)
        sync3dButton()
        btn3d.apply {
            setOnClickListener { cyclePhoto3D() }
            onActionKey { performClick() }
        }

        if (DeviceUtils.isTvDevice(this)) {
            // En TV no hay fondo de pantalla: se oculta la opción (solo celular/tablet).
            findViewById<TextView>(R.id.btn_wallpaper).visibility = View.GONE
            findViewById<TextView>(R.id.btn_back).requestFocus()
        }
    }

    private fun computeSpanCount(): Int {
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        return when {
            widthDp >= 960 -> 5
            widthDp >= 720 -> 4
            else -> 3
        }
    }

    private fun openViewer(position: Int) {
        viewerIndex = position.coerceIn(items.indices)
        showViewerItem()
        viewerOverlay.visibility = View.VISIBLE
        findViewById<TextView>(R.id.btn_close).requestFocus()
    }

    private fun showViewerItem() {
        if (viewerIndex !in items.indices) return
        val item = items[viewerIndex]
        photoJob?.cancel()
        if (photo3dMode == Karin3DPhoto.PHOTO_OFF) {
            ivViewer.setImageResource(item.resId)
            tvViewerName.text = "${viewerIndex + 1}/${items.size} · ${item.name}"
        } else {
            // Procesado CPU en IO: decodifica y convierte (anaglifo/SBS).
            ivViewer.setImageResource(item.resId)
            tvViewerName.text = "${viewerIndex + 1}/${items.size} · ${item.name} · 3D…"
            photoJob = lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        val src = BitmapFactory.decodeResource(resources, item.resId)
                            ?: return@withContext null
                        val out = when (photo3dMode) {
                            Karin3DPhoto.PHOTO_ANAGLYPH ->
                                Karin3DPhoto.applyPseudoAnaglyph(src, 0.4f)
                            else -> Karin3DPhoto.applySbsDuplicate(src)
                        }
                        if (out !== src) {
                            try {
                                if (!src.isRecycled) src.recycle()
                            } catch (_: Exception) { }
                        }
                        out
                    } catch (_: Exception) {
                        null
                    }
                }
                // Si el usuario ya cambió de foto, no pintar la vieja.
                if (viewerIndex !in items.indices || items[viewerIndex] !== item) {
                    try {
                        if (bmp != null && !bmp.isRecycled) bmp.recycle()
                    } catch (_: Exception) { }
                    return@launch
                }
                if (bmp != null) {
                    ivViewer.setImageBitmap(bmp)
                    tvViewerName.text =
                        "${viewerIndex + 1}/${items.size} · ${item.name} · ${Karin3DPhoto.modeName(photo3dMode)}"
                } else {
                    ivViewer.setImageResource(item.resId)
                    tvViewerName.text = "${viewerIndex + 1}/${items.size} · ${item.name}"
                }
            }
        }
        ivViewer.contentDescription = item.name
        ivViewer.announceForAccessibility(item.name)
    }

    private fun cyclePhoto3D() {
        photo3dMode = (photo3dMode + 1) % Karin3DPhoto.PHOTO_COUNT
        sync3dButton()
        showViewerItem()
        if (photo3dMode != Karin3DPhoto.PHOTO_OFF) {
            Toast.makeText(
                this,
                "${Karin3DPhoto.modeName(photo3dMode)}: usa lentes bicolor (anaglifo) o visor (SBS)",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun sync3dButton() {
        val label = when (photo3dMode) {
            Karin3DPhoto.PHOTO_ANAGLYPH -> "3D:A"
            Karin3DPhoto.PHOTO_SBS -> "3D:S"
            else -> "3D"
        }
        if (::btn3d.isInitialized) {
            btn3d.text = label
            btn3d.alpha = if (photo3dMode == Karin3DPhoto.PHOTO_OFF) 1f else 0.6f
        }
    }

    private fun stepViewer(delta: Int) {
        if (viewerIndex !in items.indices) return
        viewerIndex = (viewerIndex + delta).mod(items.size)
        showViewerItem()
    }

    /** Aplica la imagen actual como fondo de pantalla del celular/tablet. */
    private fun setAsWallpaper() {
        if (viewerIndex !in items.indices) return
        val item = items[viewerIndex]
        Toast.makeText(this, "Aplicando fondo...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val wm = getSystemService(WallpaperManager::class.java) ?: return@withContext false
                    BitmapFactory.decodeResource(resources, item.resId)?.let { bmp ->
                        wm.setBitmap(bmp)
                        try {
                            if (!bmp.isRecycled) bmp.recycle()
                        } catch (_: Exception) { }
                        true
                    } ?: false
                } catch (_: Exception) {
                    false
                }
            }
            Toast.makeText(
                this@GaleriaKarinActivity,
                if (ok) "Fondo aplicado: ${item.name}" else "No se pudo aplicar el fondo",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun closeViewer() {
        viewerOverlay.visibility = View.GONE
        viewerIndex = -1
        if (rvGallery.childCount > 0) rvGallery.getChildAt(0)?.requestFocus()
    }

    private fun isViewerOpen() = viewerOverlay.visibility == View.VISIBLE

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mapped = GamepadHelper.mapGamepadToDpad(keyCode)
        if (mapped != keyCode) return onKeyDown(mapped, event)
        if (isViewerOpen()) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    closeViewer()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    stepViewer(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    stepViewer(1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    cyclePhoto3D()
                    return true
                }
            }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private inner class GalleryAdapter(
        private val data: List<GalleryItem>,
        private val onClick: (Int) -> Unit,
    ) : RecyclerView.Adapter<GalleryAdapter.VH>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_galeria_karin, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = data[position]
            holder.ivThumb.setImageResource(item.resId)
            holder.ivThumb.contentDescription = item.name
            holder.tvName.text = item.name
            holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
            holder.itemView.onActionKey { holder.itemView.performClick() }
        }

        override fun getItemCount() = data.size

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivThumb: ImageView = v.findViewById(R.id.iv_thumb)
            val tvName: TextView = v.findViewById(R.id.tv_name)
        }
    }
}
