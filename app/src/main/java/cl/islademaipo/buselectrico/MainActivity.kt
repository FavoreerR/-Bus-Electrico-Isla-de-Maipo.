package cl.islademaipo.buselectrico

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.graphics.Color
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
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : ComponentActivity() {
 private lateinit var map: MapView
 private lateinit var status: TextView
 private lateinit var rootLayout: LinearLayout
 private val localMessages = mutableListOf<String>()
 private val sightings = mutableListOf<Pair<GeoPoint, Long>>()
 private var myMarker: Marker? = null
 private val locationClient by lazy { LocationServices.getFusedLocationProviderClient(this) }
 private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
   if (perms.values.any { it }) showMyLocation() else status.text = "Ubicación desactivada. Puedes explorar el mapa sin compartirla."
 }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  Configuration.getInstance().userAgentValue = packageName
  val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
  val heading = TextView(this).apply { text = "🚌 Bus Eléctrico Isla de Maipo"; textSize = 21f; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(198,40,40)); setPadding(22,28,12,28); gravity = Gravity.CENTER_VERTICAL }
  applySafeInsets(root)
  rootLayout = root
  root.addView(heading)
  status = TextView(this).apply { text = "Mapa Santiago ↔ Isla de Maipo. Ubicaciones de buses aún no conectadas."; textSize = 14f; setTextColor(Color.DKGRAY); setPadding(18,18,18,18) }
  root.addView(status)
  map = MapView(this).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(10.3); controller.setCenter(GeoPoint(-33.59,-70.79)) }
  root.addView(map, LinearLayout.LayoutParams(-1,0,1f))
  addPlace("Santiago",-33.4489,-70.6693)
  addPlace("Talagante",-33.6639,-70.9275)
  addPlace("Isla de Maipo",-33.7535,-70.8993)
  val buttons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(14,10,14,14) }
  val locate = Button(this).apply { text = "📍 Ver mi ubicación"; setOnClickListener { askLocation() } }
  val report = Button(this).apply { text = "🚌 Reportar avistamiento del bus"; setOnClickListener { reportSighting() } }
  val share = Button(this).apply { text = "🔒 Compartir GPS (próximamente)"; isEnabled = false }
  val chat = Button(this).apply { text = "💬 Chat comunitario"; setOnClickListener { showChat() } }
  buttons.addView(locate); buttons.addView(report); buttons.addView(share); buttons.addView(chat)
  val buttonScroll = ScrollView(this).apply { isFillViewport = false; addView(buttons) }
  root.addView(buttonScroll, LinearLayout.LayoutParams(-1, (220 * resources.displayMetrics.density).toInt()))
  setContentView(root)
 }
 private fun reportSighting() {
  val options = arrayOf("Bus pasando", "Bus detenido", "Bus salió del paradero")
  android.app.AlertDialog.Builder(this)
   .setTitle("Reportar avistamiento")
   .setMessage("Se marcará un avistamiento de prueba en tu ubicación aproximada. Solo se guardará en este teléfono; otros pasajeros todavía no podrán verlo.")
   .setItems(options) { _, selected ->
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
     status.text = "Para reportar, primero permite la ubicación desde Ver mi ubicación."
     askLocation()
    } else {
     locationClient.lastLocation.addOnSuccessListener { loc ->
      if (loc == null || System.currentTimeMillis() - loc.time > 120000L) {
       status.text = "No hay ubicación reciente. Activa el GPS e inténtalo nuevamente."
      } else {
       val point = GeoPoint(Math.round(loc.latitude * 1000.0) / 1000.0, Math.round(loc.longitude * 1000.0) / 1000.0)
       sightings.add(point to System.currentTimeMillis())
       Marker(map).apply {
        position = point
        title = "Avistamiento de prueba: " + options[selected]
        snippet = "Solo visible en este teléfono; no confirmado"
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
        map.overlays.add(this)
       }
       map.controller.setCenter(point)
       map.controller.setZoom(14.0)
       map.invalidate()
       status.text = "Avistamiento de prueba registrado en este teléfono. No se publicó en internet."
       Toast.makeText(this, "Reporte local guardado", Toast.LENGTH_SHORT).show()
      }
     }.addOnFailureListener { status.text = "No se pudo consultar la ubicación." }
    }
   }.setNegativeButton("Cancelar", null).show()
 }
 private fun showChat() {
  val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE); setPadding(16,12,16,12) }
  applySafeInsets(panel)
  val title = TextView(this).apply { text = "💬 Chat de pasajeros"; textSize = 23f; setTextColor(Color.rgb(198,40,40)) }
  val notice = TextView(this).apply { text = "Modo de prueba: los mensajes solo se guardan en este teléfono mientras la app está abierta. El chat público necesita un servidor y medidas de moderación. No compartas datos personales ni ubicaciones exactas."; textSize = 13f; setTextColor(Color.DKGRAY); setPadding(0,10,0,10) }
  val messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
  fun redraw() {
   messages.removeAllViews()
   if(localMessages.isEmpty()) messages.addView(TextView(this).apply { text = "Todavía no hay mensajes en esta prueba."; textSize = 15f })
   localMessages.forEach { msg -> messages.addView(TextView(this).apply { text = msg; textSize = 16f; setPadding(12,12,12,12) }) }
  }
  val scroll = ScrollView(this).apply { addView(messages) }
  val entry = EditText(this).apply { hint = "Escribe un aviso del recorrido"; maxLines = 3; filters = arrayOf(android.text.InputFilter.LengthFilter(300)) }
  val send = Button(this).apply { text = "Enviar (prueba local)"; setOnClickListener {
   val value = entry.text.toString().trim()
   if(value.isNotEmpty()) { localMessages.add("Pasajero: $value"); entry.text.clear(); redraw(); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
  } }
  val back = Button(this).apply { text = "← Volver al mapa"; setOnClickListener { setContentView(rootLayout) } }
  panel.addView(title); panel.addView(notice); panel.addView(scroll, LinearLayout.LayoutParams(-1,0,1f)); panel.addView(entry); panel.addView(send); panel.addView(back)
  redraw()
  setContentView(panel)
 }
 private fun applySafeInsets(view: android.view.View) {
  ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
   val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
   v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
   insets
  }
 }
 private fun addPlace(name:String,lat:Double,lon:Double) { Marker(map).apply { position=GeoPoint(lat,lon); title=name; setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM); map.overlays.add(this) } }
 private fun askLocation() { if (ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED) showMyLocation() else requestLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)) }
 private fun showMyLocation() { if (ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED) return
  locationClient.lastLocation.addOnSuccessListener { loc -> if (loc!=null) { myMarker?.let { map.overlays.remove(it) }; myMarker=Marker(map).apply { position=GeoPoint(loc.latitude,loc.longitude); title="Tu ubicación (solo en este teléfono)"; map.overlays.add(this) }; map.controller.setZoom(14.0); map.controller.setCenter(GeoPoint(loc.latitude,loc.longitude)); map.invalidate(); status.text="Tu ubicación se muestra solo en este dispositivo. No se envía a ningún servidor." } else status.text="No se pudo obtener la ubicación. Activa GPS e inténtalo nuevamente." }
 }
 override fun onResume(){super.onResume();if(::map.isInitialized)map.onResume()}
 override fun onPause(){if(::map.isInitialized)map.onPause();super.onPause()}
}
