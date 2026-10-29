"use strict";
const admin = require("firebase-admin");
const raw = process.env.FIREBASE_SERVICE_ACCOUNT_JSON;
if (!raw) { console.error("Missing FIREBASE_SERVICE_ACCOUNT_JSON GitHub Actions secret"); process.exit(1); }
let credentials;
try { credentials = JSON.parse(raw); } catch { console.error("Invalid service account JSON"); process.exit(1); }
if (credentials.project_id !== "bus-isla-de-maipo") { console.error("Service account project mismatch"); process.exit(1); }
admin.initializeApp({ credential: admin.credential.cert(credentials) });
const db = admin.firestore();
const topic = "bus_isla_maipo_updates";
const lookback = 10 * 60 * 1000;
async function sendRecent(collection, kind) {
 const now = Date.now();
 const snapshot = await db.collection(collection).where("time", ">=", now - lookback).get();
 for (const doc of snapshot.docs) {
  const data = doc.data();
  const time = Number(data.time);
  if (!Number.isFinite(time) || time > now + 60000) continue;
  const marker = db.collection("notification_dispatch").doc(kind + "_" + doc.id);
  try {
   await marker.create({ createdAt: admin.firestore.FieldValue.serverTimestamp(), source: collection, sourceId: doc.id });
  } catch (error) {
   if (error.code === 6 || error.code === "already-exists") continue;
   throw error;
  }
  const notification = kind === "chat"
   ? { title: "Nuevo mensaje del chat", body: String(data.name || "Pasajero").slice(0,30) + ": " + String(data.text || "").slice(0,100) }
   : { title: "Avistamiento del bus", body: (["Bus pasando","Bus detenido","Bus salió del paradero"][Number(data.option)] || "Nuevo reporte") + " · Revisa el mapa" };
  try {
   await admin.messaging().send({ topic, notification, android: { notification: { channelId: "bus_updates" } }, data: { section: kind === "chat" ? "chat" : "map" } });
   console.log("Sent", kind, doc.id);
  } catch (error) {
   await marker.delete();
   throw error;
  }
 }
}
(async () => { await sendRecent("messages","chat"); await sendRecent("sightings","sighting"); })()
.catch(error => { console.error(error); process.exitCode = 1; });
