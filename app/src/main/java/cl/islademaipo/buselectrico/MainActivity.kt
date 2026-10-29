package cl.islademaipo.buselectrico

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import android.os.Build
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
import org.osmdroid.views.overlay.TilesOverlay
import org.osmdroid.tileprovider.MapTileProviderBasic

class MainActivity : ComponentActivity() {
 private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
 private fun setupNotifications() {
  if (Build.VERSION.SDK_INT >= 26) {
   val manager = getSystemService(android.app.NotificationManager::class.java)
   manager.createNotificationChannel(android.app.NotificationChannel("bus_updates", "Avisos del Bus Eléctrico", android.app.NotificationManager.IMPORTANCE_DEFAULT))
  }
  if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
   requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
  }
  FirebaseMessaging.getInstance().subscribeToTopic("bus_isla_maipo_updates")
 }
 private val auth by lazy { FirebaseAuth.getInstance() }
 private val db by lazy { FirebaseFirestore.getInstance() }
 private var chatListener: ListenerRegistration? = null
 private var sightingListener: ListenerRegistration? = null
 private var gpsListener: ListenerRegistration? = null
 private var gpsSharing = false
 private var gpsCallback: com.google.android.gms.location.LocationCallback? = null
 private val gpsMarkers = mutableListOf<Marker>()
 private lateinit var map: MapView
 private val streetTiles by lazy {
  XYTileSource("OpenStreetMapHTTPS", 0, 19, 256, ".png",
   arrayOf("https://tile.openstreetmap.org/"))
 }
 private val satelliteTiles by lazy {
  object : XYTileSource("EsriWorldImagery", 0, 19, 256, ".jpg",
   arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/")) {
   override fun getTileURLString(pMapTileIndex: Long): String {
    val z = MapTileIndex.getZoom(pMapTileIndex)
    val x = MapTileIndex.getX(pMapTileIndex)
    val y = MapTileIndex.getY(pMapTileIndex)
    return baseUrl + "$z/$y/$x"
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
 private val sightingLifetimeMs = 60_000L
 private val sightingExpiryHandler = android.os.Handler(android.os.Looper.getMainLooper())
 private val sightingExpiryRunnable = object : Runnable {
  override fun run() {
   if (::map.isInitialized) {
    val now = System.currentTimeMillis()
    map.overlays.removeAll { it is Marker && it.title?.startsWith("🚌 Reporte de pasajero:") == true && (it.relatedObject as? Long)?.let { timestamp -> now - timestamp >= sightingLifetimeMs } == true }
    map.invalidate()
   }
   sightingExpiryHandler.postDelayed(this, 1_000L)
  }
 }
 private val reportPrefs by lazy { getSharedPreferences("sighting_reports", MODE_PRIVATE) }
 private var myMarker: Marker? = null
 private var retryReportAfterPermission = false
 private var pendingSightingOption: Int? = null
 private val sightingRoutes = arrayOf("Metro Lo Valledor", "Naltagua", "Monte Las Mercedes", "Metro Lo Valledor — estudiantes", "Talagante – Caperana nocturno", "Talagante", "La Puntilla")
 private var selectedSightingRoute = ""
 private var selectedSightingDirection = ""
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
  setupNotifications()
  sightingExpiryHandler.post(sightingExpiryRunnable)
  Configuration.getInstance().userAgentValue = "BusElectricoIslaDeMaipo/1.2 (Android; $packageName)"
  Configuration.getInstance().osmdroidBasePath = File(filesDir, "osmdroid").apply { mkdirs() }
  Configuration.getInstance().osmdroidTileCache = File(filesDir, "osmdroid/tiles").apply { mkdirs() }
  Configuration.getInstance().tileFileSystemCacheMaxBytes = 120L * 1024 * 1024
  Configuration.getInstance().tileFileSystemCacheTrimBytes = 90L * 1024 * 1024
  localMessages.clear()
  localMessages.addAll(chatPrefs.getString("messages", "")!!.split("§").map { it.replace("⏎", "\n").replace("\\n", "\n") }.filter { it.isNotBlank() }.takeLast(100))
  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
  val heading = TextView(this).apply { text = "🚌 Bus Eléctrico Isla de Maipo"; textSize = 21f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(198,40,40)); setPadding(22,28,12,28); gravity = Gravity.CENTER_VERTICAL }
  applySafeInsets(root)
  rootLayout = root
  root.addView(heading)
  status = TextView(this).apply { text = "📍 Isla de Maipo · Los buses mostrados son reportes de pasajeros, no GPS en vivo."; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(18,18,18,18) }
  root.addView(status)
  satelliteMode = mapPrefs.getBoolean("satellite", false)
  map = MapView(this).apply {
   setTileSource(if (satelliteMode) satelliteTiles else streetTiles)
   setMultiTouchControls(true)
   setBuiltInZoomControls(false)
   setTilesScaledToDpi(false)
   setUseDataConnection(true)
   setFlingEnabled(true)
   minZoomLevel = 8.0
   maxZoomLevel = 19.0
   setHorizontalMapRepetitionEnabled(false)
   setVerticalMapRepetitionEnabled(false)
   controller.setZoom(15.5)
   controller.setCenter(GeoPoint(-33.7535,-70.8993))
  }
  // Transparent geographic labels over satellite imagery: hybrid mode.
  val labelTiles = object : XYTileSource("EsriReferenceLabels", 0, 19, 256, ".png",
   arrayOf("https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/")) {
   override fun getTileURLString(pMapTileIndex: Long): String {
    return baseUrl + "${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}"
   }
  }
  val labelsProvider = MapTileProviderBasic(this, labelTiles)
  val labelsOverlay = TilesOverlay(labelsProvider, this).apply { isEnabled = satelliteMode }
  map.overlays.add(labelsOverlay)
  val mapTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
  val streets = Button(this).apply { text = "🗺️ Calles"; setOnClickListener { if (satelliteMode) { satelliteMode = false; mapPrefs.edit().putBoolean("satellite", false).apply(); map.setTileSource(streetTiles); labelsOverlay.isEnabled = false; map.maxZoomLevel = 19.0; map.invalidate() }; status.text = "Mapa de calles · OpenStreetMap · Reportes locales, no GPS en vivo." } }
  val satellite = Button(this).apply { text = "🛰️ Satélite"; setOnClickListener { if (!satelliteMode) { satelliteMode = true; mapPrefs.edit().putBoolean("satellite", true).apply(); map.setTileSource(satelliteTiles); labelsOverlay.isEnabled = true; map.maxZoomLevel = 19.0; map.invalidate() }; status.text = "Mapa híbrido: imágenes satelitales y nombres geográficos · No es tiempo real." } }
  mapTools.addView(streets, LinearLayout.LayoutParams(0,-2,1f))
  mapTools.addView(satellite, LinearLayout.LayoutParams(0,-2,1f))
  root.addView(mapTools)
  root.addView(map, LinearLayout.LayoutParams(-1,0,1f))
  root.addView(TextView(this).apply { text = "Calles © OpenStreetMap contributors · Satélite y etiquetas © Esri y proveedores"; textSize = 10f; setPadding(12,2,12,2) })
  addPlace("Santiago",-33.4489,-70.6693)
  addPlace("Talagante",-33.6639,-70.9275)
  addPlace("Isla de Maipo",-33.7535,-70.8993)
  restoreSightings()
  connectFirebase()
  listenSightings()
  listenSharedGps()
  val buttons = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(dp(16),dp(12),dp(16),dp(18))
   setBackgroundColor(Color.rgb(247,249,253))
  }
  buttons.addView(actionCard("❔", "CÓMO USAR LA APP", "Tutorial rápido para pasajeros", Color.rgb(240,247,255), Color.rgb(9,83,166), true) { showPassengerGuide() })
  buttons.addView(actionCard("🚌", "VER BUSES REPORTADOS", "Encuentra los avistamientos del último minuto", Color.rgb(10,96,175), Color.WHITE) { showReportedBuses() })
  buttons.addView(actionCard("📋", "HISTORIAL DE AVISTAMIENTOS", "Últimos minutos y reportes de hoy por recorrido", Color.rgb(235,244,255), Color.rgb(9,83,166), true) { showSightingHistory() })
  buttons.addView(actionCard("📍", "VER MI UBICACIÓN", "Encuentra tu ubicación en el mapa", Color.WHITE, Color.rgb(17,35,76), true) { askLocation() })
  buttons.addView(actionCard("🚌", "REPORTAR AVISTAMIENTO DEL BUS", "Marca en el mapa dónde viste el bus", Color.rgb(225,12,48), Color.WHITE) { reportSighting() })
  buttons.addView(actionCard("⌁", "COMPARTIR GPS", "Compartir zona aproximada temporalmente", Color.rgb(0,102,238), Color.WHITE) { toggleGpsSharing() })
  buttons.addView(actionCard("💬", "CHAT COMUNITARIO", "Conversa con otros pasajeros", Color.rgb(0,155,95), Color.WHITE) { showChat() })
  buttons.addView(actionCard("◷", "HORARIOS DE BUS ELÉCTRICO", "Revisa todas las salidas y recorridos", Color.rgb(133,46,232), Color.WHITE) { showSchedules() })
  buttons.addView(actionCard("✉", "RECLAMOS Y SUGERENCIAS", "Ayúdanos a mejorar la aplicación", Color.rgb(18,116,144), Color.WHITE) { showFeedbackForm() })
  buttons.addView(actionCard("🛡", "ADMINISTRACIÓN", "Acceso privado del creador", Color.rgb(30,41,59), Color.WHITE) { showAdminLogin() })
  val buttonScroll = ScrollView(this).apply { isFillViewport = false; addView(buttons); setBackgroundColor(Color.rgb(247,249,253)) }
  root.addView(buttonScroll, LinearLayout.LayoutParams(-1, dp(260)))
  setContentView(root)
  root.post { ensurePassengerProfile(); showFirstRunGuide() }
 }


 private fun showFirstRunGuide() {
  val prefs = getSharedPreferences("passenger_guide", MODE_PRIVATE)
  if (!prefs.getBoolean("completed_v1", false)) showPassengerGuide()
 }
 private fun showPassengerGuide() {
  val titles = arrayOf("¡Bienvenido!", "Explora el mapa", "Encuentra buses reportados", "Reporta un avistamiento", "Consulta horarios y chat", "¡Listo para viajar!")
  val descriptions = arrayOf(
   "Esta aplicación reúne información de los recorridos del Bus Eléctrico Isla de Maipo y avisos compartidos por pasajeros.",
   "Desliza el mapa con un dedo y usa dos dedos para acercar o alejar. Puedes elegir Calles o Satélite. El mapa no muestra buses en tiempo real.",
   "Pulsa VER BUSES REPORTADOS. Elige un reporte de la lista y el mapa se centrará en ese lugar. Cada avistamiento dura 1 minuto y puede ser aproximado.",
   "Cuando veas un bus, toca REPORTAR AVISTAMIENTO DEL BUS. Selecciona el recorrido, la dirección y sigue los pasos para compartir el lugar.",
   "En HORARIOS DE BUS ELÉCTRICO puedes revisar recorridos y salidas. En CHAT COMUNITARIO puedes conversar con otros pasajeros.",
   "Ya sabes cómo usar las funciones principales. Puedes volver a abrir esta guía desde CÓMO USAR LA APP."
  )
  val symbols = arrayOf("🚌", "🗺️", "📍", "🚌", "💬", "✓")
  val prefs = getSharedPreferences("passenger_guide", MODE_PRIVATE)
  var step = 0
  val panel = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(dp(22), dp(16), dp(22), dp(12))
   setBackgroundColor(Color.WHITE)
  }
  val progress = TextView(this).apply { textSize = 14f; setTextColor(Color.rgb(9,83,166)) }
  val symbol = TextView(this).apply {
   textSize = 46f
   gravity = Gravity.CENTER
   setPadding(0, dp(20), 0, dp(12))
  }
  val title = TextView(this).apply {
   textSize = 23f
   setTextColor(Color.rgb(9,83,166))
   setTypeface(null, android.graphics.Typeface.BOLD)
   gravity = Gravity.CENTER
  }
  val description = TextView(this).apply {
   textSize = 17f
   setTextColor(Color.rgb(40,52,70))
   gravity = Gravity.CENTER
   setPadding(0, dp(15), 0, dp(22))
  }
  val disclaimer = TextView(this).apply {
   text = "Los avistamientos son aportes de pasajeros, no seguimiento GPS en vivo."
   textSize = 12f
   setTextColor(Color.DKGRAY)
   gravity = Gravity.CENTER
  }
  panel.addView(progress)
  panel.addView(symbol)
  panel.addView(title)
  panel.addView(description)
  panel.addView(disclaimer)
  val dialog = android.app.AlertDialog.Builder(this)
   .setTitle("🚌 Aprende a usar la app")
   .setView(panel)
   .setNegativeButton("Omitir", null)
   .setNeutralButton("Anterior", null)
   .setPositiveButton("Siguiente", null)
   .create()
  fun render() {
   progress.text = "GUÍA RÁPIDA  ·  Paso ${step + 1} de ${titles.size}"
   symbol.text = symbols[step]
   title.text = titles[step]
   description.text = descriptions[step]
   dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.text =
    if (step == titles.lastIndex) "Comenzar" else "Siguiente"
   dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)?.isEnabled = step > 0
  }
  dialog.setOnShowListener {
   render()
   dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
    if (step > 0) { step--; render() }
   }
   dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
    if (step == titles.lastIndex) {
     prefs.edit().putBoolean("completed_v1", true).apply()
     dialog.dismiss()
    } else {
     step++
     render()
    }
   }
  }
  dialog.setOnDismissListener { prefs.edit().putBoolean("completed_v1", true).apply() }
  dialog.show()
 }
 private fun actionCard(icon: String, title: String, subtitle: String, backgroundColor: Int, foregroundColor: Int, light: Boolean = false, enabled: Boolean = true, action: () -> Unit): LinearLayout {
  val card = LinearLayout(this).apply {
   orientation = LinearLayout.HORIZONTAL
   gravity = Gravity.CENTER_VERTICAL
   setPadding(dp(15),dp(13),dp(15),dp(13))
   background = rounded(backgroundColor, 23)
   elevation = dp(5).toFloat()
   alpha = if(enabled) 1f else 0.60f
   isClickable = enabled
   isFocusable = enabled
   contentDescription = "$title. $subtitle"
   if(enabled) setOnClickListener { action() }
  }
  val symbol = TextView(this).apply {
   text = icon; textSize = 29f; gravity = Gravity.CENTER
   setTextColor(if(light) Color.rgb(225,12,48) else Color.WHITE)
   background = rounded(if(light) Color.rgb(255,235,239) else Color.argb(55,255,255,255), 20)
  }
  card.addView(symbol, LinearLayout.LayoutParams(dp(64),dp(64)))
  val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13),0,dp(4),0) }
  labels.addView(TextView(this).apply {
   text = title; textSize = 16f; setTextColor(foregroundColor)
   setTypeface(null, android.graphics.Typeface.BOLD); maxLines = 2
  })
  labels.addView(TextView(this).apply {
   text = subtitle; textSize = 12f
   setTextColor(if(light) Color.rgb(93,110,140) else Color.WHITE)
   setPadding(0,dp(5),0,0); maxLines = 2
  })
  card.addView(labels, LinearLayout.LayoutParams(0,-2,1f))
  card.addView(TextView(this).apply {
   text = if(enabled) "❯" else "🔒"; textSize = 22f; gravity = Gravity.CENTER
   setTextColor(if(light) Color.rgb(225,12,48) else Color.WHITE)
  }, LinearLayout.LayoutParams(dp(30),dp(48)))
  return card.apply {
   layoutParams = LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(13) }
  }
 }
 private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
 private fun rounded(color: Int, radius: Int = 18): android.graphics.drawable.GradientDrawable =
  android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
 private fun scheduleText(value: String, size: Float, color: Int, bold: Boolean = false): TextView =
  TextView(this).apply { text=value; textSize=size; setTextColor(color); if(bold) setTypeface(null, android.graphics.Typeface.BOLD) }
 private fun showAdminLogin() {
  val email = EditText(this).apply {
   hint = "Correo de administrador"
   inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
  }
  val password = EditText(this).apply {
   hint = "Contraseña"
   inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
  }
  val content = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(dp(20), dp(8), dp(20), dp(8))
   addView(email)
   addView(password)
  }
  val dialog = android.app.AlertDialog.Builder(this)
   .setTitle("🛡 Acceso de administrador")
   .setView(content)
   .setNegativeButton("Cancelar", null)
   .setPositiveButton("Ingresar", null)
   .create()
  dialog.setOnShowListener {
   val button = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
   button.setOnClickListener {
    val address = email.text.toString().trim()
    val secret = password.text.toString()
    if (address.isBlank() || secret.isBlank()) {
     Toast.makeText(this, "Ingresa correo y contraseña.", Toast.LENGTH_SHORT).show()
     return@setOnClickListener
    }
    button.isEnabled = false
    auth.signInWithEmailAndPassword(address, secret).addOnSuccessListener { result ->
     result.user?.getIdToken(true)?.addOnSuccessListener { token ->
      if (token.claims["admin"] == true) {
       dialog.dismiss()
       showAdminPanel()
      } else {
       auth.signOut()
       connectFirebase()
       button.isEnabled = true
       Toast.makeText(this, "Esta cuenta no tiene permiso de administrador.", Toast.LENGTH_LONG).show()
      }
     }?.addOnFailureListener {
      button.isEnabled = true
      Toast.makeText(this, "No se pudo verificar el permiso de administrador.", Toast.LENGTH_LONG).show()
     }
    }.addOnFailureListener {
     button.isEnabled = true
     Toast.makeText(this, "Credenciales incorrectas o acceso no disponible.", Toast.LENGTH_LONG).show()
    }
   }
  }
  dialog.show()
 }
 private fun showAdminPanel() {
  val panel = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setBackgroundColor(Color.rgb(246,248,252))
  }
  applySafeInsets(panel)
  panel.addView(TextView(this).apply {
   text = "🛡 Panel de administración"
   textSize = 23f
   setTextColor(Color.WHITE)
   setBackgroundColor(Color.rgb(30,41,59))
   setPadding(dp(20), dp(22), dp(20), dp(22))
  })
  val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(12)) }
  val scroll = ScrollView(this).apply { addView(list) }
  panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
  panel.addView(Button(this).apply {
   text = "Cerrar sesión y volver"
   setOnClickListener { auth.signOut(); connectFirebase(); recreate() }
  })
  setContentView(panel)
  auth.currentUser?.getIdToken(true)?.addOnSuccessListener { token ->
   if (token.claims["admin"] != true) {
    auth.signOut(); connectFirebase(); recreate()
    return@addOnSuccessListener
   }
   db.collection("app_feedback").limit(100).get().addOnSuccessListener { snapshot ->
    list.removeAllViews()
    val docs = snapshot.documents.sortedByDescending { it.getTimestamp("createdAt")?.seconds ?: 0L }
    if (docs.isEmpty()) list.addView(TextView(this).apply { text = "Todavía no hay mensajes." })
    for (doc in docs) {
     val item = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(16),dp(14),dp(16),dp(14))
      background = rounded(Color.WHITE, 16)
     }
     item.addView(TextView(this).apply {
      text = (doc.getString("category") ?: "Comentario") + " · " + (doc.getString("status") ?: "nuevo")
      textSize = 17f
      setTypeface(null, android.graphics.Typeface.BOLD)
     })
     item.addView(TextView(this).apply {
      text = doc.getString("message") ?: ""
      textSize = 15f
      setPadding(0,dp(8),0,dp(8))
     })
     if (doc.getString("status") != "resuelto") {
      item.addView(Button(this).apply {
       text = "Marcar como resuelto"
       setOnClickListener {
        isEnabled = false
        doc.reference.update("status", "resuelto").addOnSuccessListener {
         text = "✓ Resuelto"
        }.addOnFailureListener {
         isEnabled = true
         Toast.makeText(this@MainActivity, "No se pudo actualizar.", Toast.LENGTH_SHORT).show()
        }
       }
      })
     }
     list.addView(item, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(10) })
    }
   }.addOnFailureListener {
    list.addView(TextView(this).apply { text = "No se pudieron cargar los mensajes. Revisa las reglas de Firebase." })
   }
  }?.addOnFailureListener {
   auth.signOut(); connectFirebase(); recreate()
  }
 }
 private fun showFeedbackForm() {
  val categories = arrayOf("Sugerencia", "Reclamo", "Error de la aplicación")
  var selected = 0
  val content = LinearLayout(this).apply {
   orientation = LinearLayout.VERTICAL
   setPadding(dp(20), dp(12), dp(20), dp(8))
  }
  val intro = TextView(this).apply {
   text = "Tu opinión ayuda a mejorar esta aplicación. No incluyas contraseñas, direcciones ni otros datos personales."
   textSize = 14f
   setTextColor(Color.DKGRAY)
  }
  val category = Spinner(this).apply {
   adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, categories)
  }
  val message = EditText(this).apply {
   hint = "Escribe tu reclamo o sugerencia (mínimo 10 caracteres)"
   minLines = 4
   maxLines = 7
   inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
   filters = arrayOf(android.text.InputFilter.LengthFilter(1000))
  }
  content.addView(intro)
  content.addView(category, LinearLayout.LayoutParams(-1, dp(54)))
  content.addView(message)
  val dialog = android.app.AlertDialog.Builder(this)
   .setTitle("✉ Reclamos y sugerencias")
   .setView(content)
   .setNegativeButton("Cancelar", null)
   .setPositiveButton("Enviar", null)
   .create()
  dialog.setOnShowListener {
   val send = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
   send.setOnClickListener {
    val body = message.text.toString().trim()
    selected = category.selectedItemPosition.coerceIn(categories.indices)
    if (body.length < 10) {
     message.error = "Escribe al menos 10 caracteres"
     return@setOnClickListener
    }
    val uid = auth.currentUser?.uid
    if (uid == null) {
     connectFirebase()
     Toast.makeText(this, "Conectando al servidor. Inténtalo de nuevo.", Toast.LENGTH_LONG).show()
     return@setOnClickListener
    }
    send.isEnabled = false
    db.collection("app_feedback").add(mapOf(
     "category" to categories[selected],
     "message" to body.take(1000),
     "uid" to uid,
     "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
     "status" to "nuevo"
    )).addOnSuccessListener {
     Toast.makeText(this, "Gracias. Tu comentario fue enviado.", Toast.LENGTH_LONG).show()
     dialog.dismiss()
    }.addOnFailureListener {
     send.isEnabled = true
     Toast.makeText(this, "No se pudo enviar. Revisa la conexión y los permisos de Firebase.", Toast.LENGTH_LONG).show()
    }
   }
  }
  dialog.show()
 }
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
  val back=Button(this).apply { text="← Volver al mapa"; isAllCaps=false; setTextColor(Color.WHITE); background=rounded(navy,16); setOnClickListener { setContentView(rootLayout); map.onResume(); map.invalidate() } }
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
     val route = doc.getString("route") ?: "Recorrido no indicado"
     val direction = doc.getString("direction") ?: "Dirección no indicada"
     if (lat != null && lon != null && time != null && option != null &&
         lat in -90.0..90.0 && lon in -180.0..180.0 &&
         time <= now && now - time < sightingLifetimeMs && option in 0..2) {
      addSightingMarker(GeoPoint(lat, lon), time, "$route · $direction · ${arrayOf("Bus pasando", "Bus detenido", "Bus salió del paradero")[option]}")
     }
    }
    map.invalidate()
   }
 }
 private fun showSightingHistory() {
  val windows = arrayOf("Últimos 5 minutos", "Últimos 15 minutos", "Últimos 30 minutos", "Última hora", "Todo el día")
  val durations = longArrayOf(300000L, 900000L, 1800000L, 3600000L, Long.MAX_VALUE)
  val routes = arrayOf("Todos los recorridos") + sightingRoutes
  val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(10)) }
  val notice = TextView(this).apply {
   text = "Reportes compartidos por pasajeros. No representan la ubicación actual del bus."
   textSize = 14f; setTextColor(Color.WHITE); setPadding(dp(12),dp(12),dp(12),dp(12))
   background = rounded(Color.rgb(9,83,166), 16)
  }
  panel.addView(notice)
  fun label(value: String) = TextView(this).apply {
   text = value; textSize = 13f; setTextColor(Color.DKGRAY)
   setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0,dp(10),0,0)
  }
  val period = Spinner(this)
  val route = Spinner(this)
  val sector = Spinner(this)
  period.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, windows)
  route.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, routes)
  sector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("Todos los sectores"))
  panel.addView(label("PERÍODO")); panel.addView(period)
  panel.addView(label("RECORRIDO")); panel.addView(route)
  panel.addView(label("SECTOR")); panel.addView(sector)
  val summary = TextView(this).apply { textSize = 15f; setTextColor(Color.rgb(9,83,166)); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(0,dp(12),0,0) }
  panel.addView(summary)
  val cards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,dp(8),0,0) }
  panel.addView(ScrollView(this).apply { addView(cards) }, LinearLayout.LayoutParams(-1,dp(350)))
  val dialog = android.app.AlertDialog.Builder(this).setTitle("📋 Historial de avistamientos")
   .setView(panel).setNegativeButton("Cerrar",null).setPositiveButton("Actualizar",null).create()
  var documents = emptyList<com.google.firebase.firestore.DocumentSnapshot>()
  fun render() {
   val now = System.currentTimeMillis()
   val midnight = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY,0); set(java.util.Calendar.MINUTE,0)
    set(java.util.Calendar.SECOND,0); set(java.util.Calendar.MILLISECOND,0)
   }.timeInMillis
   val duration = durations[period.selectedItemPosition.coerceIn(durations.indices)]
   val start = if (duration == Long.MAX_VALUE) midnight else maxOf(midnight,now-duration)
   val chosenRoute = routes[route.selectedItemPosition.coerceIn(routes.indices)]
   val chosenSector = sector.selectedItem?.toString() ?: "Todos los sectores"
   val filtered = documents.filter { doc ->
    val time = doc.getLong("time") ?: 0L
    val name = doc.getString("route") ?: "Sin recorrido"
    val zone = doc.getString("sector")?.takeIf { it.isNotBlank() } ?: name
    time in start..now && (chosenRoute == "Todos los recorridos" || name == chosenRoute) &&
     (chosenSector == "Todos los sectores" || zone == chosenSector)
   }
   summary.text = "${filtered.size} avistamiento(s) encontrados"
   cards.removeAllViews()
   if (filtered.isEmpty()) cards.addView(TextView(this).apply { text = "No hay reportes para los filtros seleccionados."; setPadding(0,dp(18),0,dp(18)) })
   filtered.forEach { doc ->
    val time = doc.getLong("time") ?: 0L
    val name = doc.getString("route") ?: "Sin recorrido"
    val zone = doc.getString("sector")?.takeIf { it.isNotBlank() } ?: name
    val street = doc.getString("street")?.takeIf { it.isNotBlank() } ?: "Calle no disponible"
    val direction = doc.getString("direction") ?: "No indicada"
    val comment = doc.getString("comment")?.takeIf { it.isNotBlank() } ?: when(doc.getLong("option")?.toInt()) {
     0 -> "Bus pasando"; 1 -> "Bus detenido"; 2 -> "Bus salió del paradero"; else -> "Avistamiento"
    }
    val clock = java.text.SimpleDateFormat("HH:mm",java.util.Locale("es","CL")).format(java.util.Date(time))
    val age = ((now-time)/60000).coerceAtLeast(0)
    val card = LinearLayout(this).apply {
     orientation = LinearLayout.VERTICAL; setPadding(dp(14),dp(12),dp(14),dp(12))
     background = rounded(Color.rgb(235,244,255),18)
    }
    fun line(value: String, big: Boolean = false) {
     card.addView(TextView(this).apply {
      text = value; textSize = if(big) 18f else 14f
      setTextColor(if(big) Color.rgb(9,83,166) else Color.rgb(35,52,76))
      if(big) setTypeface(null,android.graphics.Typeface.BOLD)
      setPadding(0,dp(4),0,dp(4))
     })
    }
    line("🕒  $clock · Hoy",true)
    line("🚌  $name",true)
    line("➜  Dirección: $direction")
    line("📍  Calle: $street")
    line("🗺  Sector: $zone")
    line("💬  $comment")
    line("⏱  Hace $age minuto(s)")
    val lat = doc.getDouble("lat"); val lon = doc.getDouble("lon")
    if(lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) {
     card.addView(Button(this).apply {
      text = "📍 Ver ubicación reportada en el mapa"
      setOnClickListener {
       dialog.dismiss()
       map.controller.setCenter(GeoPoint(lat,lon)); map.controller.setZoom(16.0)
       map.invalidate()
       status.text = "Ubicación aproximada de un reporte histórico; el bus puede haberse desplazado."
      }
     })
    }
    cards.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(12) })
   }
  }
  fun load() {
   summary.text = "Consultando avistamientos..."
   val midnight = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY,0); set(java.util.Calendar.MINUTE,0)
    set(java.util.Calendar.SECOND,0); set(java.util.Calendar.MILLISECOND,0)
   }.timeInMillis
   db.collection("sightings").whereGreaterThanOrEqualTo("time",midnight)
    .orderBy("time",Query.Direction.DESCENDING).limit(500).get()
    .addOnSuccessListener { snap ->
     if(!dialog.isShowing) return@addOnSuccessListener
     documents = snap.documents
     val zones = listOf("Todos los sectores") + documents.map {
      it.getString("sector")?.takeIf { value -> value.isNotBlank() } ?: (it.getString("route") ?: "Sin recorrido")
     }.distinct().sorted()
     val selected = sector.selectedItem?.toString()
     sector.adapter = ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,zones)
     sector.setSelection(zones.indexOf(selected).takeIf { it >= 0 } ?: 0)
     render()
    }.addOnFailureListener { if(dialog.isShowing) summary.text = "No se pudieron cargar los reportes. Comprueba Firebase." }
  }
  val changes = object : android.widget.AdapterView.OnItemSelectedListener {
   override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
   override fun onItemSelected(parent: android.widget.AdapterView<*>?,view: android.view.View?,position:Int,id:Long) {
    if(dialog.isShowing) render()
   }
  }
  period.onItemSelectedListener = changes; route.onItemSelectedListener = changes; sector.onItemSelectedListener = changes
  dialog.setOnShowListener { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener { load() }; load() }
  dialog.show()
 }
 private fun showReportedBuses() {
  val now = System.currentTimeMillis()
  val reports = map.overlays.filterIsInstance<Marker>()
   .filter { it.title?.startsWith("🚌 Reporte de pasajero:") == true &&
     (it.relatedObject as? Long)?.let { t -> t <= now && now - t < sightingLifetimeMs } == true }
   .sortedByDescending { it.relatedObject as Long }
  if (reports.isEmpty()) {
   android.app.AlertDialog.Builder(this)
    .setTitle("🚌 Buses reportados")
    .setMessage("Todavía no hay avistamientos recientes. Los reportes aparecen durante 1 minuto. No son ubicaciones GPS en vivo.")
    .setPositiveButton("Entendido", null).show()
   return
  }
  val labels = reports.map { marker ->
   val age = ((now - (marker.relatedObject as Long)) / 1000).coerceAtLeast(0)
   val detail = marker.title?.removePrefix("🚌 Reporte de pasajero: ") ?: "Bus reportado"
   "🚌 $detail\\nHace ${age} segundos · Reporte de pasajero"
  }.toTypedArray()
  android.app.AlertDialog.Builder(this)
   .setTitle("🚌 Buses reportados (${reports.size})")
   .setMessage("Toca un reporte para encontrarlo en el mapa. Son avistamientos de pasajeros, no GPS en vivo.")
   .setItems(labels) { _, index ->
    val marker = reports[index]
    val timestamp = marker.relatedObject as Long
    if (System.currentTimeMillis() - timestamp >= sightingLifetimeMs) {
     Toast.makeText(this, "Este reporte ya venció.", Toast.LENGTH_SHORT).show()
    } else {
     map.controller.animateTo(marker.position)
     map.controller.setZoom(16.0)
     marker.showInfoWindow()
     map.invalidate()
     status.text = "🚌 Reporte seleccionado · Ubicación aproximada, no GPS en vivo."
    }
   }
   .setNegativeButton("Cerrar", null).show()
 }
 private fun listenSharedGps() {
  gpsListener?.remove()
  gpsListener = db.collection("shared_gps").addSnapshotListener { snapshot, error ->
   if (error != null || snapshot == null || !::map.isInitialized) return@addSnapshotListener
   gpsMarkers.forEach { map.overlays.remove(it) }
   gpsMarkers.clear()
   val now = System.currentTimeMillis()
   snapshot.documents.forEach { doc ->
    val lat = doc.getDouble("lat")
    val lon = doc.getDouble("lon")
    val time = doc.getLong("time") ?: 0L
    if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0 &&
        time <= now && now - time < 120000L) {
     val marker = Marker(map).apply {
      position = GeoPoint(lat, lon)
      title = "Zona aproximada compartida por un pasajero"
      snippet = "Ubicación redondeada, no es seguimiento exacto"
      setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
     }
     gpsMarkers.add(marker)
     map.overlays.add(marker)
    }
   }
   map.invalidate()
  }
 }
 private fun toggleGpsSharing() {
  if (gpsSharing) {
   stopGpsSharing()
   Toast.makeText(this, "Dejaste de compartir tu zona.", Toast.LENGTH_LONG).show()
   return
  }
  android.app.AlertDialog.Builder(this)
   .setTitle("Compartir zona aproximada")
   .setMessage("Otros usuarios de la app podrán ver una zona aproximada de 1 km, nunca tu posición exacta ni tu nombre. Solo se actualizará mientras la app esté abierta. Puedes detenerlo cuando quieras.")
   .setNegativeButton("Cancelar", null)
   .setPositiveButton("Aceptar y compartir") { _, _ ->
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
     Toast.makeText(this, "Autoriza ubicación y vuelve a pulsar Compartir GPS.", Toast.LENGTH_LONG).show()
     askLocation()
    } else startGpsSharing()
   }.show()
 }
 private fun startGpsSharing() {
  val uid = auth.currentUser?.uid
  if (uid == null) {
   connectFirebase()
   Toast.makeText(this, "Conectando al servidor. Inténtalo nuevamente.", Toast.LENGTH_LONG).show()
   return
  }
  gpsSharing = true
  val request = com.google.android.gms.location.LocationRequest.Builder(
   com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30000L)
   .setMinUpdateIntervalMillis(15000L).build()
  val callback = object : com.google.android.gms.location.LocationCallback() {
   override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
    if (!gpsSharing) return
    val location = result.lastLocation ?: return
    // Roughly 1.1 km grid; never upload precise passenger coordinates.
    val lat = kotlin.math.round(location.latitude * 100.0) / 100.0
    val lon = kotlin.math.round(location.longitude * 100.0) / 100.0
    db.collection("shared_gps").document(uid).set(mapOf(
     "lat" to lat, "lon" to lon, "time" to System.currentTimeMillis(), "uid" to uid
    )).addOnFailureListener {
     status.text = "No se pudo compartir la zona. Revisa la conexión o las reglas de Firebase."
     stopGpsSharing()
    }
   }
  }
  gpsCallback = callback
  try {
   locationClient.requestLocationUpdates(request, callback, mainLooper)
   status.text = "Compartiendo zona aproximada. Pulsa CompartIR GPS para detener."
  } catch (e: SecurityException) {
   stopGpsSharing()
   status.text = "Permiso de ubicación no disponible."
  }
 }
 private fun stopGpsSharing() {
  gpsSharing = false
  gpsCallback?.let { locationClient.removeLocationUpdates(it) }
  gpsCallback = null
  auth.currentUser?.uid?.let { db.collection("shared_gps").document(it).delete() }
 }
 private fun reportSighting() {
  android.app.AlertDialog.Builder(this)
   .setTitle("¿Qué bus viste? · Selecciona el recorrido")
   .setItems(sightingRoutes) { _, route ->
    selectedSightingRoute = sightingRoutes[route]
    val directions = arrayOf("Ida", "Regreso", "No lo sé")
    android.app.AlertDialog.Builder(this)
     .setTitle("Dirección de " + selectedSightingRoute)
     .setItems(directions) { _, direction ->
      selectedSightingDirection = directions[direction]
      val options = arrayOf("Bus pasando", "Bus detenido", "Bus salió del paradero")
      android.app.AlertDialog.Builder(this)
       .setTitle("¿Qué observaste? · Visible 2 horas")
       .setItems(options) { _, selected ->
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
         pendingSightingOption = selected
         retryReportAfterPermission = true
         askLocation()
        } else saveSighting(selected)
       }.setNegativeButton("Cancelar", null).show()
     }.setNegativeButton("Cancelar", null).show()
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
        val route = selectedSightingRoute.ifBlank { "Sin recorrido" }
        val direction = selectedSightingDirection.ifBlank { "No lo sé" }
        val row = "$latitude,$longitude,$time,$selected"
        reportPrefs.edit().putString("reports", (existing.split("|").filter { it.isNotBlank() }.takeLast(49) + row).joinToString("|")).apply()
        addSightingMarker(point, time, "$route · $direction · ${options[selected]}")
        map.controller.setCenter(point)
        map.controller.setZoom(14.0)
        map.invalidate()
        status.text = "Publicando avistamiento..."
        val uid = auth.currentUser?.uid
        if (uid == null) { connectFirebase(); status.text = "Conectando a Firebase. Reintenta el reporte." }
        else db.collection("sightings").add(mapOf("lat" to latitude, "lon" to longitude, "time" to time, "option" to selected, "route" to route, "direction" to direction, "uid" to uid))
         .addOnSuccessListener { status.text = "Avistamiento publicado para otros pasajeros." }
         .addOnFailureListener { status.text = "No se pudo publicar. Reporte guardado solo en este teléfono." }
        Toast.makeText(this, "Avistamiento guardado", Toast.LENGTH_LONG).show()
       }
      }.addOnFailureListener { status.text = "No se pudo consultar el GPS. Inténtalo nuevamente." }
 }
 private fun sectorBusIcon(route: String): BitmapDrawable {
  val density = resources.displayMetrics.density
  val width = (116 * density).toInt()
  val height = (65 * density).toInt()
  val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  val paint = Paint(Paint.ANTI_ALIAS_FLAG)
  val palette = intArrayOf(Color.rgb(0, 121, 107), Color.rgb(21, 101, 192), Color.rgb(123, 31, 162), Color.rgb(230, 81, 0), Color.rgb(46, 125, 50), Color.rgb(173, 20, 87), Color.rgb(69, 90, 100))
  val index = sightingRoutes.indexOf(route)
  val color = palette[if (index >= 0) index % palette.size else 0]
  paint.color = Color.WHITE
  canvas.drawRoundRect(2f, 2f, width - 2f, height - 2f, 13f * density, 13f * density, paint)
  paint.color = color
  canvas.drawRoundRect(3f, 3f, width - 3f, 10f * density, 6f * density, 6f * density, paint)
  paint.textAlign = Paint.Align.CENTER
  paint.textSize = 29f * density
  canvas.drawText("🚌", width / 2f, 39f * density, paint)
  paint.color = Color.rgb(30, 41, 59)
  paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
  paint.textSize = 10f * density
  val label = if (route.length > 19) route.take(18) + "…" else route
  canvas.drawText(label, width / 2f, 56f * density, paint)
  return BitmapDrawable(resources, bitmap)
 }
 private fun addSightingMarker(point: GeoPoint, time: Long, description: String) {
  sightings.add(point to time)
  val clock = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("es", "CL")).format(java.util.Date(time))
  Marker(map).apply {
   position = point
   icon = sectorBusIcon(description.substringBefore(" · "))
   title = "🚌 Reporte de pasajero: $description"
   relatedObject = time
   snippet = "$clock · Ubicación aproximada · Reportado por pasajeros, no GPS en vivo"
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
        time <= now && now - time < sightingLifetimeMs && option in 0..2) {
     addSightingMarker(GeoPoint(lat, lon), time, arrayOf("Bus pasando", "Bus detenido", "Bus salió del paradero")[option])
    }
   }
  }
 }
 private fun ensurePassengerProfile() {
  val localName = chatPrefs.getString("display_name", "")?.trim().orEmpty()
  val user = auth.currentUser
  if (user == null) {
   if (localName.isBlank()) askPassengerName()
   auth.signInAnonymously().addOnSuccessListener { ensurePassengerProfile() }
    .addOnFailureListener {
     if (localName.isBlank()) Toast.makeText(this, "Puedes registrar tu nombre en este teléfono. Se sincronizará al recuperar la conexión.", Toast.LENGTH_LONG).show()
    }
   return
  }
  db.collection("passenger_profiles").document(user.uid).get()
   .addOnSuccessListener { doc ->
    val saved = doc.getString("name")?.trim().orEmpty()
    if (saved.isNotBlank()) chatPrefs.edit().putString("display_name", saved).apply()
    else if (localName.length in 2..30) savePassengerProfile(localName)
    else askPassengerName()
   }
   .addOnFailureListener {
    if (localName.isBlank()) askPassengerName()
    else Toast.makeText(this, "Nombre guardado en el teléfono. La verificación en línea está pendiente.", Toast.LENGTH_LONG).show()
   }
 }
 private fun savePassengerProfile(name: String, dialog: android.app.AlertDialog? = null) {
  val normalized = name.trim().replace(Regex("\\s+"), " ")
  if (normalized.length !in 2..30) {
   dialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
   return
  }
  // Persist locally first so a Firestore outage cannot block first-time setup.
  chatPrefs.edit().putString("display_name", normalized).apply()
  dialog?.dismiss()
  val uid = auth.currentUser?.uid
  if (uid == null) {
   connectFirebase()
   Toast.makeText(this, "Nombre guardado. Se sincronizará cuando haya conexión.", Toast.LENGTH_LONG).show()
   return
  }
  val profile = db.collection("passenger_profiles").document(uid)
  db.runTransaction { transaction ->
   val existing = transaction.get(profile).getString("name")
   if (existing.isNullOrBlank()) {
    transaction.set(profile, mapOf("name" to normalized, "uid" to uid))
    normalized
   } else existing
  }.addOnSuccessListener { serverName ->
   chatPrefs.edit().putString("display_name", serverName).apply()
  }.addOnFailureListener {
   Toast.makeText(this, "Nombre guardado en este teléfono. Sincronización pendiente.", Toast.LENGTH_LONG).show()
  }
 }
 private var nameDialogOpen = false
 private fun askPassengerName() {
  if (!chatPrefs.getString("display_name", "").isNullOrBlank() || nameDialogOpen) return
  val field = EditText(this).apply {
   hint = "Nombre o apodo (2 a 30 caracteres)"; setSingleLine(true)
   filters = arrayOf(android.text.InputFilter.LengthFilter(30))
  }
  val dialog = android.app.AlertDialog.Builder(this)
   .setTitle("Bienvenido al Bus Eléctrico")
   .setMessage("Elige tu nombre público. Solo podrás configurarlo una vez y no podrás cambiarlo después. Puedes usar un apodo.")
   .setView(field)
   .setPositiveButton("Confirmar nombre", null)
   .setCancelable(false).create()
  dialog.setOnDismissListener { nameDialogOpen = false }
  dialog.setOnShowListener {
   dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
    val name = field.text.toString().trim().replace(Regex("\\s+"), " ")
    if (name.length !in 2..30) field.error = "Escribe entre 2 y 30 caracteres"
    else savePassengerProfile(name, dialog)
   }
  }
  nameDialogOpen = true
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
  val profile = TextView(this).apply {
   text = "👤 " + chatPrefs.getString("display_name", "Pasajero") + " · Nombre permanente"
   textSize = 15f; setPadding(18,12,18,12); setTextColor(Color.DKGRAY)
  }
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
  val back = Button(this).apply { text = "← Volver al mapa"; setOnClickListener { setContentView(rootLayout); map.onResume(); map.invalidate() } }
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
 override fun onDestroy(){ sightingExpiryHandler.removeCallbacks(sightingExpiryRunnable); stopGpsSharing(); gpsListener?.remove(); chatListener?.remove(); sightingListener?.remove(); if(::map.isInitialized) map.onDetach(); super.onDestroy() }
 override fun onResume(){super.onResume();if(::map.isInitialized)map.onResume()}
 override fun onPause(){if(gpsSharing) stopGpsSharing();if(::map.isInitialized)map.onPause();super.onPause()}
}
