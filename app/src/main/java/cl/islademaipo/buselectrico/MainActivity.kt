package cl.islademaipo.buselectrico

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
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
import org.osmdroid.tileprovider.modules.SqlTileWriter
import java.io.File
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : ComponentActivity() {
 private val auth by lazy { FirebaseAuth.getInstance() }
 private val db by lazy { FirebaseFirestore.getInstance() }
 private var chatListener: ListenerRegistration? = null
 private var sightingListener: ListenerRegistration? = null
 private lateinit var map: MapView
 private val satelliteTiles by lazy {
  object : XYTileSource("EsriWorldImagery", 0, 19, 256, ".jpg",
   arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")) {
   override fun getTileURLString(pMapTileIndex: Long): String {
    val z = MapTileIndex.getZoom(pMapTileIndex)
    val x = MapTileIndex.getX(pMapTileIndex)
    val y = MapTileIndex.getY(pMapTileIndex)
    return baseUrl + "$z/$y/$x.jpg"
   }
  }
 }
 private val mapPrefs by lazy { getSharedPreferences("map_settings", MODE_PRIVATE) }
 private var satelliteMode = false
 private lateinit var status: TextView
 private lateinit var rootLayout: LinearLayout
 private val localMessages = mutableListOf<String>()
 private val chatPrefs by lazy { getSharedPreferences("passenger_chat_local", MODE_PRIVATE) }
 private val sightings = mutableListOf<Pair<GeoPoint, Long>>()
 private val reportPrefs by lazy { getSharedPreferences("sighting_reports", MODE_PRIVATE) }
 private var myMarker: Marker? = null
 private var retryReportAfterPermission = false
 private var pendingSightingOption: Int? = null
 private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
 private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
   if (perms.values.any { it }) {
    if (retryReportAfterPermission) { retryReportAfterPermission = false; val selected = pendingSightingOption; pendingSightingOption = null; if (selected != null) saveSighting(selected) }
    else showMyLocation()
   } else {
    retryReportAfterPermission = false
    pendingSightingOption = null
    status.text = "Ubicación desactivada. Puedes explorar el mapa sin compartirla."
   }
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  Configuration.getInstance().userAgentValue = packageName
  Configuration.getInstance().osmdroidBasePath = File(cacheDir, "osmdroid")
  Configuration.getInstance().osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
  Configuration.getInstance().tileFileSystemCacheMaxBytes = 120L * 1024 * 1024
  Configuration.getInstance().tileFileSystemCacheTrimBytes = 90L * 1024 * 1024
  localMessages.clear()
  localMessages.addAll(chatPrefs.getString("messages", "")!!.split("§").map { it.replace("⏎", "\n").replace("\\n", "\n") }.filter { it.isNotBlank() }.takeLast(100))
  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
  val heading = TextView(this).apply { text = "🚌 Bus Eléctrico Isla de Maipo"; textSize = 21f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(198,40,40)); setPadding(22,28,12,28); gravity = Gravity.CENTER_VERTICAL }
  applySafeInsets(root)
  rootLayout = root
  root.addView(heading)
  status = TextView(this).apply { text = "Mapa Santiago ↔ Isla de Maipo. Ubicaciones de buses aún no conectadas."; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(18,18,18,18) }
  root.addView(status)
  satelliteMode = mapPrefs.getBoolean("satellite", false)
  map = MapView(this).apply {
   setTileSource(if (satelliteMode) satelliteTiles else TileSourceFactory.MAPNIK)
   setMultiTouchControls(true)
   setBuiltInZoomControls(false)
   setTilesScaledToDpi(false)
   setUseDataConnection(true)
   setFlingEnabled(true)
   minZoomLevel = 5.0
   maxZoomLevel = 19.0
   controller.setZoom(12.0)
   controller.setCenter(GeoPoint(-33.70,-70.88))
  }
  val mapTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  val streets = Button(this).apply { text = "🗺 Calles"; setOnClickListener { if (satelliteMode) { satelliteMode = false; mapPrefs.edit().putBoolean("satellite", false).apply(); map.setTileSource(TileSourceFactory.MAPNIK) }; status.text = "Mapa de calles · OpenStreetMap · Reportes locales, no GPS en vivo." } }
  val satellite = Button(this).apply { text = "🛰 Satélite"; setOnClickListener { if (!satelliteMode) { satelliteMode = true; mapPrefs.edit().putBoolean("satellite", true).apply(); map.setTileSource(satelliteTiles) }; status.text = "Imágenes satelitales Esri · No son imágenes en tiempo real." } }
  mapTools.addView(streets, LinearLayout.LayoutParams(0,-2,1f))
  mapTools.addView(satellite, LinearLayout.LayoutParams(0,-2,1f))
  root.addView(mapTools)
  root.addView(map, LinearLayout.LayoutParams(-1,0,1f))
  root.addView(TextView(this).apply { text = "Calles © OpenStreetMap contributors · Satélite © Esri y proveedores"; textSize = 10f; setPadding(12,2,12,2) })
  addPlace("Santiago",-33.4489,-70.6693)
  addPlace("Talagante",-33.6639,-70.9275)
  addPlace("Isla de Maipo",-33.7535,-70.8993)
  restoreSightings()
  connectFirebase()
  listenSightings()
  val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14,10,14,14) }
  val locate = Button(this).apply { text = "📍 Ver mi ubicación"; setOnClickListener { askLocation() } }
  val report = Button(this).apply { text = "🚌 Reportar avistamiento del bus"; setOnClickListener { reportSighting() } }
  val share = Button(this).apply { text = "🔒 Compartir GPS (próximamente)"; isEnabled = false }
  val chat = Button(this).apply { text = "💬 Chat comunitario"; setOnClickListener { showChat() } }
  val schedules = Button(this).apply { text = "🕒 Horarios de Bus Eléctrico"; setOnClickListener { showSchedules() } }
  buttons.addView(locate); buttons.addView(report); buttons.addView(share); buttons.addView(chat); buttons.addView(schedules)
  val buttonScroll = ScrollView(this).apply { isFillViewport = false; addView(buttons) }
  root.addView(buttonScroll, LinearLayout.LayoutParams(-1, (270 * resources.displayMetrics.density).toInt()))
  setContentView(root)
  if (chatPrefs.getString("display_name", "").isNullOrBlank()) { root.post { askPassengerName() } }
 }


 private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
 private fun rounded(color: Int, radius: Int = 18): android.graphics.drawable.GradientDrawable =
  android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
 private fun scheduleText(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
  TextView(this).apply { text=value; textSize=size; setTextColor(color); if(bold) setTypeface(null, android.graphics.Typeface.BOLD) }
 private fun showSchedules() {
  val navy = Color.rgb(9,83,166); val blue = Color.rgb(0,126,192)
  val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(244,248,253)) }
  applySafeInsets(panel)
  val header=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(22),dp(24),dp(20),dp(24))
   background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(blue,navy)).apply { cornerRadii=floatArrayOf(0f,0f,0f,0f,dp(28).toFloat(),dp(28).toFloat(),dp(28).toFloat(),dp(28).toFloat()) } }
  header.addView(scheduleText("🚌  Horarios de Bus Eléctrico",23f,Color.WHITE,true))
  header.addView(scheduleText("Recorridos y salidas · Isla de Maipo",14f,Color.WHITE).apply { setPadding(0,dp(8),0,0) })
  panel.addView(header)
  val scroll=ScrollView(this)
  val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(18)) }
  content.addView(scheduleText("Selecciona tu recorrido",20f,navy,true).apply { setPadding(dp(4),dp(4),0,dp(12)) })
  try {
   val routes=org.json.JSONObject(assets.open("horarios_buses.json").bufferedReader().use { it.readText() }).getJSONArray("recorridos")
   for(i in 0 until routes.length()) {
    val route=routes.getJSONObject(i)
    val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(16),dp(18),dp(16))
     background=rounded(Color.WHITE,20); elevation=dp(3).toFloat() }
    card.addView(scheduleText("🚌  "+route.optString("nombre")+"   ›",18f,navy,true))
    val dirs=route.optJSONArray("sentidos")
    if(dirs!=null && dirs.length()>0) {
     val first=dirs.getJSONObject(0)
     val times=first.optJSONArray("salidas")
     val summary=if(times!=null) (0 until times.length()).take(5).joinToString("  ·  ") { times.getString(it) } else ""
     card.addView(scheduleText("Salidas: $summary",14f,Color.rgb(77,91,111)).apply { setPadding(0,dp(8),0,0) })
    }
    card.setOnClickListener { showScheduleDetails(route) }
    content.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
   }
  } catch(e:Exception) { content.addView(scheduleText("No se pudieron cargar los horarios.",16f,Color.RED)) }
  content.addView(scheduleText("Horarios publicados en afiches municipales. Confirma posibles cambios antes de viajar.",12f,Color.DKGRAY))
  scroll.addView(content); panel.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  val back=Button(this).apply { text="← Volver al mapa"; isAllCaps=false; setTextColor(Color.WHITE); background=rounded(navy,16); setOnClickListener { setContentView(rootLayout) } }
  panel.addView(back,LinearLayout.LayoutParams(-1,dp(54)).apply { setMargins(dp(16),dp(6),dp(16),dp(12)) })
  setContentView(panel)
 }
 private fun showScheduleDetails(route: org.json.JSONObject) {
  val navy=Color.rgb(9,83,166); val blue=Color.rgb(0,126,192); val red=Color.rgb(231,77,34)
  val panel=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(244,248,253)) }
  applySafeInsets(panel)
  val header=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(22),dp(22),dp(20),dp(24))
   background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(blue,navy)) }
  header.addView(scheduleText("🚌  "+route.optString("nombre"),22f,Color.WHITE,true))
  header.addView(scheduleText("Recorridos y horarios de salida",14f,Color.WHITE).apply { setPadding(0,dp(7),0,0) })
  panel.addView(header)
  val scroll=ScrollView(this)
  val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(18),dp(16),dp(18)) }
  if(route.has("requisito")) {
   val requirement=scheduleText("🎓 "+route.optString("requisito")+" · Capacidad: "+route.optInt("capacidad")+" estudiantes",15f,red,true)
   requirement.setPadding(dp(14),dp(14),dp(14),dp(14)); requirement.background=rounded(Color.WHITE,16)
   content.addView(requirement,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(14) })
  }
  val directions=route.getJSONArray("sentidos")
  val tabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
  val detailHost=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
  fun renderDirection(index:Int) {
   detailHost.removeAllViews()
   val direction=directions.getJSONObject(index)
   val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(18),dp(18),dp(20)); background=rounded(Color.WHITE,20); elevation=dp(2).toFloat() }
   card.addView(scheduleText(direction.optString("desde")+" → "+direction.optString("hacia"),18f,navy,true).apply { setPadding(0,0,0,dp(14)) })
   card.addView(scheduleText("HORARIOS DE SALIDA",13f,Color.rgb(82,99,122),true))
   val times=direction.getJSONArray("salidas")
   val timesLayout=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
   var timeRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
   for(i in 0 until times.length()) {
    if(i>0 && i%3==0) { timesLayout.addView(timeRow); timeRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL } }
    val chip=scheduleText(times.getString(i),15f,Color.WHITE,true).apply { gravity=Gravity.CENTER; setPadding(dp(6),dp(11),dp(6),dp(11)); background=rounded(navy,12) }
    timeRow.addView(chip,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(2),dp(6),dp(2),dp(2)) })
   }
   timesLayout.addView(timeRow)
   card.addView(timesLayout,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(18) })
   card.addView(scheduleText("PARADAS DEL RECORRIDO",13f,Color.rgb(82,99,122),true).apply { setPadding(0,0,0,dp(8)) })
   val stops=direction.getJSONArray("paradas")
   for(j in 0 until stops.length()) {
    val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    val marker=scheduleText(if(j==stops.length()-1) "●" else "●",19f,red,true).apply { gravity=Gravity.CENTER; setPadding(0,dp(7),dp(12),dp(7)) }
    row.addView(marker)
    row.addView(scheduleText(stops.getString(j),15f,Color.rgb(42,52,68),j==0||j==stops.length()-1))
    card.addView(row)
    if(j<stops.length()-1) card.addView(android.view.View(this).apply { setBackgroundColor(red) },LinearLayout.LayoutParams(dp(3),dp(12)).apply { leftMargin=dp(8) })
   }
   detailHost.addView(card)
  }
  for(i in 0 until directions.length()) {
   val direction=directions.getJSONObject(i)
   val tab=Button(this).apply { text=if(i==0) "IDA" else "REGRESO"; isAllCaps=false; setTextColor(Color.WHITE); background=rounded(if(i==0) navy else blue,14); setOnClickListener { renderDirection(i) } }
   tabs.addView(tab,LinearLayout.LayoutParams(0,dp(50),1f).apply { setMargins(dp(3),0,dp(3),dp(14)) })
  }
  content.addView(tabs); content.addView(detailHost); renderDirection(0)
  scroll.addView(content); panel.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  val back=Button(this).apply { text="← Volver a horarios"; isAllCaps=false; setTextColor(Color.WHITE); background=rounded(navy,16); setOnClickListener { showSchedules() } }
  panel.addView(back,LinearLayout.LayoutParams(-1,dp(54)).apply { setMargins(dp(16),dp(6),dp(16),dp(12)) })
  setContentView(panel)
 }
 private fun connectFirebase() {
  if (auth.currentUser == null) auth.signInAnonymously().addOnFailureListener {
   status.text = "Sin conexión con Firebase. Revisa internet y la configuración."
  }
 }
 private fun listenSightings() {
  sightingListener?.remove()
  sightingListener = db.collection("sightings").orderBy("time", Query.Direction.DESCENDING).limit(100)
   .addSnapshotListener { snapshot, error ->
    if (error != null || snapshot == null || !::map.isInitialized) return@addSnapshotListener
    val now = System.currentTimeMillis()
    map.overlays.removeAll { it is Marker && it.title?.startsWith("🚌 Reporte de pasajero:") == true }
    sightings.clear()
    snapshot.documents.forEach { doc ->
     val lat = doc.getDouble("lat"); val lon = doc.getDouble("lon")
     val time = doc.getLong("time"); val option = doc.getLong("option")?.toInt()
     if (lat != null && lon != null && time != null && option != null &&
         lat in -90.0..90.0 && lon in -180.0..180.0 &&
         time <= now && now - time < 7200000L && option in 0..2) {
      addSightingMarker(GeoPoint(lat, lon), time, arrayOf("Bus pasando", "Bus detenido", "Bus salió del paradero")[option])
     }
    }
    map.invalidate()
   }
 }
 private fun reportSighting() {
  val options = arrayOf("Bus pasando", "Bus detenido", "Bus salió del paradero")
  android.app.AlertDialog.Builder(this)
   .setTitle("Reportar avistamiento del bus")
   .setMessage("Tu reporte aproximado será visible para otros pasajeros durante dos horas. No representa seguimiento GPS oficial. ¿Qué observaste?")
   .setItems(options) { _, selected ->
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
     pendingSightingOption = selected
     retryReportAfterPermission = true
     status.text = "Permite la ubicación para registrar el avistamiento."
     askLocation()
    } else {
     saveSighting(selected)
    }
   }.setNegativeButton("Cancelar", null).show()
 }

 private fun saveSighting(selected: Int) {
  val options = arrayOf("Bus pasando", "Bus detenido", "Bus salió del paradero")
  if (selected !in options.indices) return
  if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
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
        status.text = "Publicando avistamiento..."
        val uid = auth.currentUser?.uid
        if (uid == null) { connectFirebase(); status.text = "Conectando a Firebase. Reintenta el reporte." }
        else db.collection("sightings").add(mapOf("lat" to latitude, "lon" to longitude, "time" to time, "option" to selected, "uid" to uid))
         .addOnSuccessListener { status.text = "Avistamiento publicado para otros pasajeros." }
         .addOnFailureListener { status.text = "No se pudo publicar. Reporte guardado solo en este teléfono." }
        Toast.makeText(this, "Avistamiento guardado", Toast.LENGTH_LONG).show()
       }
      }.addOnFailureListener { status.text = "No se pudo consultar el GPS. Inténtalo nuevamente." }
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
   text = "Chat público entre pasajeros. Evita compartir datos personales."
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
   notice.setTextColor(if (selectedTheme == 1) Color.rgb(226,232,240) else Color.DKGRAY)
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
     text = msg.replace("\\n", "\n"); textSize = 15f; setTextColor(themeText[selectedTheme])
     setBackgroundColor(themeBubbles[selectedTheme]); setPadding(16,14,16,14)
    }
    messages.addView(bubble, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = 10 })
   }
  }
  redrawThemeMessages = { redraw() }
  chatListener?.remove()
  chatListener = db.collection("messages").orderBy("time", Query.Direction.DESCENDING).limit(100)
   .addSnapshotListener { snapshot, error ->
    if (error != null) { notice.text = "No se pudo cargar el chat público. Revisa internet y reglas."; return@addSnapshotListener }
    if (snapshot != null) {
     localMessages.clear()
     snapshot.documents.reversed().forEach { doc ->
      val name = doc.getString("name") ?: "Pasajero"
      val body = doc.getString("text") ?: ""
      val timestamp = doc.getLong("time") ?: 0L
      val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale("es","CL")).format(java.util.Date(timestamp))
      localMessages.add("${name} · ${clock}\n${body}")
     }
     redraw(); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
   }
  val profile = Button(this).apply { text = "👤 Cambiar mi nombre"; setOnClickListener { askPassengerName { text = "👤 " + chatPrefs.getString("display_name", "Pasajero") + " · Cambiar nombre" } } }
  profile.text = "👤 " + chatPrefs.getString("display_name", "Pasajero") + " · Cambiar nombre"
  val entry = EditText(this).apply {
   hint = "Escribe un mensaje para los pasajeros"; maxLines = 3; minLines = 1
    setTextColor(Color.rgb(30,41,59)); setHintTextColor(Color.rgb(100,116,139)); setBackgroundColor(Color.WHITE); setPadding(18,14,18,14)
   filters = arrayOf(android.text.InputFilter.LengthFilter(300))
  }
  val send = Button(this).apply {
   text = "Enviar mensaje público"
   setOnClickListener {
    val value = entry.text.toString().trim().replace("\\n", " ")
    val uid = auth.currentUser?.uid
    if (value.isEmpty()) return@setOnClickListener
    if (uid == null) { connectFirebase(); Toast.makeText(this@MainActivity, "Conectando. Reintenta en unos segundos.", Toast.LENGTH_LONG).show(); return@setOnClickListener }
    val sender = (chatPrefs.getString("display_name", "Pasajero") ?: "Pasajero").take(30)
    isEnabled = false
    db.collection("messages").add(mapOf("name" to sender, "text" to value.take(300), "time" to System.currentTimeMillis(), "uid" to uid))
     .addOnSuccessListener { entry.text.clear(); isEnabled = true }
     .addOnFailureListener { isEnabled = true; Toast.makeText(this@MainActivity, "No se pudo enviar. Comprueba internet y reglas.", Toast.LENGTH_LONG).show() }
   }
  }
  val back = Button(this).apply { text = "← Volver al mapa"; setOnClickListener { setContentView(rootLayout) } }
  val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12,4,12,8) }
  bottom.addView(themes); bottom.addView(profile); bottom.addView(entry); bottom.addView(send); bottom.addView(back)
  panel.addView(title); panel.addView(notice)
  panel.addView(scroll, LinearLayout.LayoutParams(-1,0,1f)); panel.addView(bottom)
  redraw(); setContentView(panel)
 }
 private fun applySafeInsets(view: android.view.View) {
  ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
   val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
   val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
   v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
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
 override fun onDestroy(){ chatListener?.remove(); sightingListener?.remove(); if(::map.isInitialized) map.onDetach(); super.onDestroy() }
 override fun onResume(){super.onResume();if(::map.isInitialized)map.onResume()}
 override fun onPause(){if(::map.isInitialized)map.onPause();super.onPause()}
}
