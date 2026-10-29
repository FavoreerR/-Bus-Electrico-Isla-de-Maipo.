const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getMessaging } = require("firebase-admin/messaging");
initializeApp();
const topic = "bus_isla_maipo_updates";
exports.notifyChat = onDocumentCreated("messages/{messageId}", async (event) => {
  const data = event.data?.data();
  if (!data || typeof data.text !== "string") return;
  const name = String(data.name || "Pasajero").slice(0, 30);
  await getMessaging().send({
    topic,
    notification: { title: "💬 " + name, body: data.text.slice(0, 120) },
    android: { notification: { channelId: "bus_updates", tag: "chat_" + event.params.messageId } },
    data: { section: "chat" }
  });
});
exports.notifySighting = onDocumentCreated("sightings/{sightingId}", async (event) => {
  const data = event.data?.data();
  if (!data) return;
  const labels = ["Bus pasando", "Bus detenido", "Bus salió del paradero"];
  const label = labels[Number(data.option)] || "Nuevo avistamiento";
  await getMessaging().send({
    topic,
    notification: { title: "🚌 Avistamiento del bus", body: label + " · Revisa el mapa" },
    android: { notification: { channelId: "bus_updates", tag: "sighting_" + event.params.sightingId } },
    data: { section: "map" }
  });
});
