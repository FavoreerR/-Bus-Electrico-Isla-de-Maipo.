package cl.islademaipo.busadmin

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class AdminActivity : ComponentActivity() {
 private val navy = Color.rgb(5,16,34)
 private val panel = Color.rgb(12,32,59)
 private val cyan = Color.rgb(0,198,255)
 private val pale = Color.rgb(225,246,255)
 private lateinit var auth: FirebaseAuth
 private lateinit var db: FirebaseFirestore
 private lateinit var root: LinearLayout
 private val listeners = mutableListOf<ListenerRegistration>()
 private var tab = "Inicio"
 private fun dp(v:Int) = (v * resources.displayMetrics.density).toInt()
 private fun shape(c:Int, r:Int=18) = GradientDrawable().apply { setColor(c); cornerRadius=dp(r).toFloat(); setStroke(dp(1),Color.rgb(22,66,103)) }
 private fun text(s:String, size:Float=15f, color:Int=pale, bold:Boolean=false) = TextView(this).apply {
  text=s; textSize=size; setTextColor(color); if(bold) setTypeface(null,Typeface.BOLD); setPadding(dp(10),dp(9),dp(10),dp(9))
 }
 private fun card(parent:LinearLayout):LinearLayout {
  val v=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; background=shape(panel); setPadding(dp(10),dp(9),dp(10),dp(9)); elevation=dp(3).toFloat() }
  parent.addView(v,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) });return v
 }
 private fun button(label:String, click:()->Unit)=Button(this).apply {
  text=label; setTextColor(Color.WHITE); background=shape(Color.rgb(0,117,196),14); setAllCaps(false); setOnClickListener { click() }
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState)
  FirebaseApp.initializeApp(this,FirebaseOptions.Builder()
   .setApplicationId("1:1067164047739:android:096459ee17497f352e45de")
   .setApiKey("AIzaSyDxPskQ9mvb4DMyldHcF1FAf6OqDcK42og")
   .setProjectId("bus-isla-de-maipo").build())
  auth=FirebaseAuth.getInstance();db=FirebaseFirestore.getInstance()
  Configuration.getInstance().userAgentValue="BusElectricoAdmin/1.0"
  if(auth.currentUser==null) login() else verifyAdmin()
 }
 private fun base(title:String):LinearLayout {
  listeners.forEach { it.remove() }; listeners.clear()
  root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(navy); setPadding(dp(16),dp(18),dp(16),dp(10)) }
  setContentView(root)
  root.addView(text("◉ BUS ELÉCTRICO  ·  ISLA DE MAIPO",13f,cyan,true))
  root.addView(text(title,26f,Color.WHITE,true))
  return root
 }
 private fun login() {
  val page=base("ADMIN  ·  Acceso exclusivo")
  val c=card(page)
  c.addView(text("🚌  Panel de administración",22f,cyan,true))
  c.addView(text("Solo el creador autorizado puede acceder."))
  val email=EditText(this).apply { hint="Correo electrónico"; setSingleLine(true); inputType=33; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY) }
  val pass=EditText(this).apply { hint="Contraseña"; setSingleLine(true); inputType=129; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY) }
  c.addView(email);c.addView(pass)
  val info=text("",13f,cyan);c.addView(info)
  c.addView(button("Iniciar sesión") {
   info.text="Verificando acceso..."
   auth.signInWithEmailAndPassword(email.text.toString().trim(),pass.text.toString()).addOnSuccessListener { verifyAdmin() }
    .addOnFailureListener { info.text="No se pudo iniciar sesión. Revisa tus credenciales." }
  })
 }
 private fun verifyAdmin() {
  val user=auth.currentUser ?: return login()
  user.getIdToken(true).addOnSuccessListener { token ->
   if(token.claims["admin"]==true) show("Inicio") else {
    auth.signOut();login();Toast.makeText(this,"Cuenta sin permisos de administrador",Toast.LENGTH_LONG).show()
   }
  }.addOnFailureListener { auth.signOut();login() }
 }
 private fun nav() {
  val bar=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; setPadding(0,dp(8),0,0) }
  listOf("Inicio","Mapa","Buses","Reportes","Más").forEach { item ->
   val t=text(item,11f,if(tab==item) cyan else pale,tab==item).apply { gravity=Gravity.CENTER; setPadding(dp(2),dp(14),dp(2),dp(14)); setOnClickListener { show(item) } }
   bar.addView(t,LinearLayout.LayoutParams(0,-2,1f))
  }
  root.addView(bar)
 }
 private fun show(which:String) {
  tab=which
  base(when(which) { "Inicio"->"Panel de control"; "Mapa"->"Mapa de avistamientos"; "Buses"->"Buses y recorridos"; "Reportes"->"Reportes"; else->"Configuración" })
  val scroll=ScrollView(this).apply { fillViewport=true }
  val body=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(2),dp(6),dp(2),0) }
  scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  when(which) {
   "Inicio" -> {
    card(body).apply { addView(text("RESUMEN EN TIEMPO REAL",13f,cyan,true)); addView(text("Los números muestran registros existentes en Firebase, no GPS oficial de buses.",12f)) }
    listOf("sightings" to "Avistamientos", "shared_gps" to "GPS compartidos", "messages" to "Mensajes", "app_feedback" to "Reclamos y sugerencias").forEach { (collection,label) ->
     val c=card(body);val count=text("—",30f,cyan,true);c.addView(text(label,14f,pale,true));c.addView(count)
     listeners.add(db.collection(collection).addSnapshotListener { snap,err -> count.text=if(err!=null) "Sin acceso" else (snap?.size() ?: 0).toString() })
    }
   }
   "Mapa" -> {
    card(body).addView(text("Ubicaciones reportadas por usuarios. No equivalen a seguimiento oficial en vivo.",13f))
    val map=MapView(this).apply { setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true); controller.setZoom(11.0); controller.setCenter(GeoPoint(-33.75,-70.90)) }
    body.addView(map,LinearLayout.LayoutParams(-1,dp(440)))
    listeners.add(db.collection("sightings").addSnapshotListener { snap,err ->
     if(err==null) {
      map.overlays.clear()
      snap?.documents?.forEach { doc ->
       val lat=doc.getDouble("latitude") ?: doc.getDouble("lat")
       val lon=doc.getDouble("longitude") ?: doc.getDouble("lng")
       if(lat!=null && lon!=null) map.overlays.add(Marker(map).apply { position=GeoPoint(lat,lon); title="Avistamiento de pasajero" })
      }
      map.invalidate()
     }
    })
   }
   "Buses" -> {
    card(body).apply { addView(text("Recorridos disponibles",19f,cyan,true)); addView(text("Los avistamientos no confirman que un bus esté activo ni transmitiendo GPS.",13f)) }
    listOf("Metro Lo Valledor","Naltagua","Monte Las Mercedes","Metro Lo Valledor — estudiantes","Talagante – Caperana nocturno","Talagante","La Puntilla").forEach { card(body).addView(text("🚌  $it",16f,pale,true)) }
   }
   "Reportes" -> {
    card(body).addView(text("RECLAMOS Y SUGERENCIAS",16f,cyan,true))
    val list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };body.addView(list)
    listeners.add(db.collection("app_feedback").limit(50).addSnapshotListener { snap,err ->
     list.removeAllViews()
     if(err!=null) list.addView(text("No se pudieron consultar los reportes."))
     else if(snap?.isEmpty==true) list.addView(text("Todavía no hay reportes."))
     else snap?.documents?.forEach { doc ->
      val c=card(list)
      c.addView(text(doc.data?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }?.take(600) ?: "Reporte",13f))
     }
    })
   }
   else -> card(body).apply {
    addView(text("🔐  Acceso protegido por Firebase",17f,cyan,true))
    addView(text(auth.currentUser?.email ?: "Administrador"))
    addView(button("Cerrar sesión") { auth.signOut();login() })
   }
  }
  nav()
 }
 override fun onDestroy() { listeners.forEach { it.remove() }; super.onDestroy() }
}
