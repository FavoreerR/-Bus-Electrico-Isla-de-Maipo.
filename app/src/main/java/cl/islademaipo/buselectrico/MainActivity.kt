package cl.islademaipo.buselectrico

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.location.LocationServices
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : ComponentActivity() {
 private lateinit var map: MapView
 private val satelliteTiles by lazy { XYTileSource("EsriWorldImagery", 0, 19, 256, ".jpg", arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")) }
 private lateinit var status: TextView
 private lateinit var rootLayout: LinearLayout
 private val localMessages = mutableListOf<String>()
 private val chatPrefs by lazy { getSharedPreferences("passenger_chat_local", MODE_PRIVATE) }
 private val sightings = mutableListOf<Pair<GeoPoint, Long>>()
 private val reportPrefs by lazy { getSharedPreferences("sighting_reports", MODE_PRIVATE) }
 private var myMarker: Marker? = null
 private var retryReportAfterPermission = false
 private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
 private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
   if (perms.values.any { it }) {
    if (retryReportAfterPermission) { retryReportAfterPermission = false; reportSighting() }
    else showMyLocation()
   } else {
    retryReportAfterPermission = false
    status.text = "Ubicación desactivada. Puedes explorar el mapa sin compartirla."
   }
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  Configuration.getInstance().userAgentValue = packageName
  localMessages.clear()
  localMessages.addAll(chatPrefs.getString("messages", "")!!.split("§").map { it.replace("⏎", "\\n") }.filter { it.isNotBlank() }.takeLast(100))
  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
  val heading = TextView(this).apply { text = "🚌 Bus Eléctrico Isla de Maipo"; textSize = 21f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(198,40,40)); setPadding(22,28,12,28); gravity = Gravity.CENTER_VERTICAL }
  applySafeInsets(root)
  rootLayout = root
  root.addView(heading)
  status = TextView(this).apply { text = "Mapa Santiago ↔ Isla de Maipo. Ubicaciones de buses aún no conectadas."; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(18,18,18,18) }
  root.addView(status)
  map = MapView(this).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); setBuiltInZoomControls(true); controller.setZoom(11.0); controller.setCenter(GeoPoint(-33.66,-70.84)) }
  val mapTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  val streets = Button(this).apply { text = "🗺 Calles"; setOnClickListener { map.setTileSource(TileSourceFactory.MAPNIK); map.invalidate(); status.text = "Mapa de calles · OpenStreetMap · Reportes locales, no GPS en vivo." } }
  val satellite = Button(this).apply { text = "🛰 Satélite"; setOnClickListener { map.setTileSource(satelliteTiles); map.invalidate(); status.text = "Imágenes satelitales Esri · No son imágenes en tiempo real." } }
  mapTools.addView(streets, LinearLayout.LayoutParams(0,-2,1f))
  mapTools.addView(satellite, LinearLayout.LayoutParams(0,-2,1f))
  root.addView(mapTools)
  root.addView(map, LinearLayout.LayoutParams(-1,0,1f))
  root.addView(TextView(this).apply { text = "Calles © OpenStreetMap contributors · Satélite © Esri y proveedores"; textSize = 10f; setPadding(12,2,12,2) })
  addPlace("Santiago",-33.4489,-70.6693)
  addPlace("Talagante",-33.6639,-70.9275)
  addPlace("Isla de Maipo",-33.7535,-70.8993)
  restoreSightings()
  val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14,10,14,14) }
  val locate = Button(this).apply { text = "📍 Ver mi ubicación"; setOnClickListener { askLocation() } }
  val report = Button(this).apply { text = "🚌 Reportar avistamiento del bus"; setOnClickListener { reportSighting() } }
  val share = Button(this).apply { text = "🔒 Compartir GPS (próximamente)"; isEnabled = false }
  val chat = Button(this).apply { text = "💬 Chat comunitario"; setOnClickListener { showChat() } }
  buttons.addView(locate); buttons.addView(report); buttons.addView(share); buttons.addView(chat)
  val buttonScroll = ScrollView(this).apply { isFillViewport = false; addView(buttons) }
  root.addView(buttonScroll, LinearLayout.LayoutParams(-1, (220 * resources.displayMetrics.density).toInt()))
  setContentView(root)
  if (chatPrefs.getString("display_name", "").isNullOrBlank()) { root.post { askPassengerName() } }
 }
 private fun reportSighting() {
  val options = arrayOf("Bus pasando", "Bus detenido", "Bus salió del paradero")
  android.app.AlertDialog.Builder(this)
   .setTitle("Reportar avistamiento del bus")
   .setMessage("Tu reporte será aproximado y solo visible en este teléfono por ahora. No representa seguimiento GPS oficial. ¿Qué observaste?")
   .setItems(options) { _, selected ->
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
     status.text = "Permite la ubicación para registrar el avistamiento."
     retryReportAfterPermission = true
     askLocation()
    } else {
     status.text = "Buscando tu ubicación actual para el reporte..."
     val token = com.google.android.gms.tasks.CancellationTokenSource()
     locationClient.getCurrentLocation(com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, token.token)
      .addOnSuccessListener { loc ->
       if (loc == null || System.currentTimeMillis() - loc.time > 120000L) {
        status.text = "No se obtuvo una ubicación reciente. Activa el GPS e inténtalo de nuevo."
       } else {
        val latitude = Math.round(loc.latitude * 1000.0) / 1000.0
        val longitude = Math.round(loc.longitude * 1000.0) / 1000.0
        val time = System.currentTimeMillis()
        val point = GeoPoint(latitude, longitude)
        val existing = reportPrefs.getString("reports", "") ?: ""
        val row = "$latitude,$longitude,$time,$selected"
        reportPrefs.edit().putString("reports", (existing.split("|").filter { it.isNotBlank() }.takeLast(49) + row).joinToString("|")).apply()
        addSightingMarker(point, time, options[selected])
        map.controller.setCenter(point)
        map.controller.setZoom(14.0)
        map.invalidate()
        status.text = "Avistamiento registrado en este teléfono. No está publicado para otros pasajeros."
        Toast.makeText(this, "Avistamiento guardado", Toast.LENGTH_LONG).show()
       }
      }.addOnFailureListener { status.text = "No se pudo consultar el GPS. Inténtalo nuevamente." }
    }
   }.setNegativeButton("Cancelar", null).show()
 }
 private fun addSightingMarker(point: GeoPoint, time: Long, description: String) {
  sightings.add(point to time)
  val clock = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("es", "CL")).format(java.util.Date(time))
  Marker(map).apply {
   position = point
   icon = busMarkerIcon()
   title = "🚌 Reporte de pasajero: $description"
   snippet = "$clock · Ubicación aproximada · No confirmado · Solo este teléfono"
   setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
   map.overlays.add(this)
  }
 }
 private fun restoreSightings() {
  val raw = reportPrefs.getString("reports", "") ?: ""
  val now = System.currentTimeMillis()
  raw.split("|").filter { it.isNotBlank() }.forEach { row ->
   val parts = row.split(",")
   if (parts.size == 4) {
    val lat = parts[0].toDoubleOrNull()
    val lon = parts[1].toDoubleOrNull()
    val time = parts[2].toLongOrNull()
    val option = parts[3].toIntOrNull()
    if (lat != null && lon != null && time != null && option != null &&
        lat in -90.0..90.0 && lon in -180.0..180.0 &&
        time <= now && now - time < 2 * 60 * 60 * 1000L && option in 0..2) {
     addSightingMarker(GeoPoint(lat, lon), time, arrayOf("Bus pasando", "Bus detenido", "Bus salió del paradero")[option])
    }
   }
  }
 }
 private fun askPassengerName(afterSave: () -> Unit = {}) {
  val field = EditText(this).apply {
   hint = "Tu nombre o apodo"; setSingleLine(true)
   filters = arrayOf(android.text.InputFilter.LengthFilter(30))
   setText(chatPrefs.getString("display_name", ""))
  }
  val dialog = android.app.AlertDialog.Builder(this)
   .setTitle("Perfil de pasajero")
   .setMessage("Elige el nombre que aparecerá en tus avisos. Usa un apodo si prefieres proteger tu privacidad.")
   .setView(field)
   .setPositiveButton("Guardar", null)
   .setNegativeButton("Más tarde", null).create()
  dialog.setOnShowListener {
   dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
    val name = field.text.toString().trim().replace(Regex("\\s+"), " ")
    if (name.length < 2) { field.error = "Escribe al menos 2 caracteres" }
    else { chatPrefs.edit().putString("display_name", name).apply(); dialog.dismiss(); afterSave() }
   }
  }
  dialog.show()
 }
 private fun showChat() {
  val red = Color.rgb(198,40,40)
  val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(248,248,248)) }
  applySafeInsets(panel)
  val title = TextView(this).apply {
   text = "💬 Comunidad del bus eléctrico"; textSize = 20f; setTextColor(Color.WHITE)
   setBackgroundColor(red); setPadding(20,20,20,20)
  }
  val notice = TextView(this).apply {
   text = "Chat local de prueba · Los mensajes se guardan solo en este teléfono. No los reciben otros pasajeros. Evita compartir datos personales."
   textSize = 13f; setTextColor(Color.DKGRAY); setPadding(20,14,20,14)
  }
  val messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14,8,14,8) }
  val scroll = ScrollView(this).apply { addView(messages); isFillViewport = true }
  val themeNames = arrayOf("Clásico", "Oscuro", "Rojo", "Azul")
  val themeBackgrounds = intArrayOf(Color.rgb(243,244,246), Color.rgb(23,33,43), Color.rgb(253,232,232), Color.rgb(219,234,254))
  val themeBubbles = intArrayOf(Color.WHITE, Color.rgb(51,65,85), Color.WHITE, Color.WHITE)
  val themeText = intArrayOf(Color.rgb(35,35,35), Color.WHITE, Color.rgb(35,35,35), Color.rgb(35,35,35))
  var selectedTheme = chatPrefs.getInt("chat_theme", 0).coerceIn(0, themeNames.lastIndex)
  fun applyTheme() {
   panel.setBackgroundColor(themeBackgrounds[selectedTheme])
   scroll.setBackgroundColor(themeBackgrounds[selectedTheme])
   messages.setBackgroundColor(themeBackgrounds[selectedTheme])
  }
  applyTheme()
  var redrawThemeMessages: () -> Unit = {}
  val themes = Button(this).apply {
   text = "🎨 Fondo del chat: " + themeNames[selectedTheme]
   setOnClickListener {
    android.app.AlertDialog.Builder(this@MainActivity)
     .setTitle("Elegir fondo del chat")
     .setSingleChoiceItems(themeNames, selectedTheme) { dialog, which ->
      selectedTheme = which
      chatPrefs.edit().putInt("chat_theme", which).apply()
      text = "🎨 Fondo del chat: " + themeNames[which]
      applyTheme()
      redrawThemeMessages()
      dialog.dismiss()
     }.setNegativeButton("Cancelar", null).show()
   }
  }
  fun redraw() {
   messages.removeAllViews()
   applyTheme()
   if (localMessages.isEmpty()) messages.addView(TextView(this).apply {
    text = "Aún no hay avisos guardados. Escribe el primero."; textSize = 15f
    setTextColor(if (selectedTheme == 1) Color.WHITE else Color.GRAY); setPadding(12,18,12,18)
   })
   localMessages.forEach { msg ->
    val bubble = TextView(this).apply {
     text = msg; textSize = 15f; setTextColor(themeText[selectedTheme])
     setBackgroundColor(themeBubbles[selectedTheme]); setPadding(16,14,16,14)
    }
    messages.addView(bubble, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = 10 })
   }
  }
  redrawThemeMessages = { redraw() }
  val profile = Button(this).apply { text = "👤 Cambiar mi nombre"; setOnClickListener { askPassengerName { text = "👤 " + chatPrefs.getString("display_name", "Pasajero") + " · Cambiar nombre" } } }
  profile.text = "👤 " + chatPrefs.getString("display_name", "Pasajero") + " · Cambiar nombre"
  val entry = EditText(this).apply {
   hint = "Escribe un aviso del recorrido"; maxLines = 3; minLines = 1
   filters = arrayOf(android.text.InputFilter.LengthFilter(300))
  }
  val send = Button(this).apply {
   text = "Enviar aviso local"; setOnClickListener {
    val value = entry.text.toString().trim().replace("\\n", " ")
    if (value.isNotEmpty()) {
     val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale("es","CL")).format(java.util.Date())
     val sender = chatPrefs.getString("display_name", "Pasajero") ?: "Pasajero"
     localMessages.add("$sender · $time\\n$value")
     while (localMessages.size > 100) localMessages.removeAt(0)
     chatPrefs.edit().putString("messages", localMessages.joinToString("§") { it.replace("\\n", "⏎") }).apply()
     entry.text.clear(); redraw(); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
   }
  }
  val clear = Button(this).apply {
   text = "Borrar mensajes de este teléfono"
   setOnClickListener {
    android.app.AlertDialog.Builder(this@MainActivity).setTitle("Borrar chat local")
     .setMessage("¿Eliminar los avisos guardados en este teléfono?")
     .setPositiveButton("Borrar") { _, _ -> localMessages.clear(); chatPrefs.edit().remove("messages").apply(); redraw() }
     .setNegativeButton("Cancelar", null).show()
   }
  }
  val back = Button(this).apply { text = "← Volver al mapa"; setOnClickListener { setContentView(rootLayout) } }
  val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12,4,12,8) }
  bottom.addView(themes); bottom.addView(profile); bottom.addView(entry); bottom.addView(send); bottom.addView(clear); bottom.addView(back)
  panel.addView(title); panel.addView(notice)
  panel.addView(scroll, LinearLayout.LayoutParams(-1,0,1f)); panel.addView(bottom)
  redraw(); setContentView(panel)
 }
 private fun applySafeInsets(view: android.view.View) {
  ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
   val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
   v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
   insets
  }
 }
 private fun busMarkerIcon(): BitmapDrawable {
  val density = resources.displayMetrics.density
  val width = (64 * density).toInt().coerceAtLeast(64)
  val height = (54 * density).toInt().coerceAtLeast(54)
  val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  paint.color = Color.WHITE
  canvas.drawRoundRect(2f, 2f, width - 2f, height - 2f, 14f * density, 14f * density, paint)
  paint.color = Color.rgb(25, 135, 84)
  paint.style = Paint.Style.STROKE
  paint.strokeWidth = 2.5f * density
  canvas.drawRoundRect(2f, 2f, width - 2f, height - 2f, 14f * density, 14f * density, paint)
  paint.style = Paint.Style.FILL
  paint.textAlign = Paint.Align.CENTER
  paint.textSize = 35f * density
  canvas.drawText("🚌", width / 2f, 37f * density, paint)
  return BitmapDrawable(resources, bitmap)
 }
 private fun addPlace(name:String,lat:Double,lon:Double) { Marker(map).apply { position=GeoPoint(lat,lon); title=name; setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM); map.overlays.add(this) } }
 private fun askLocation() { if (ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) showMyLocation() else requestLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) }
 private fun showMyLocation() { if (ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return
  locationClient.lastLocation.addOnSuccessListener { loc -> if (loc!=null) { myMarker?.let { map.overlays.remove(it) }; myMarker=Marker(map).apply { position=GeoPoint(loc.latitude,loc.longitude); title="Tu ubicación (solo en este teléfono)"; map.overlays.add(this) }; map.controller.setZoom(14.0); map.controller.setCenter(GeoPoint(loc.latitude,loc.longitude)); map.invalidate(); status.text="Tu ubicación se muestra solo en este dispositivo. No se envía a ningún servidor." } else status.text="No se pudo obtener la ubicación. Activa GPS e inténtalo nuevamente." }
 }
 override fun onResume(){super.onResume();if(::map.isInitialized)map.onResume()}
 override fun onPause(){if(::map.isInitialized)map.onPause();super.onPause()}
}
