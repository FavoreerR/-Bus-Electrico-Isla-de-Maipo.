#!/usr/bin/env node
// Ejecutar localmente. Nunca subir la clave privada a GitHub.
const admin = require("firebase-admin");
const fs = require("node:fs");
const keyPath = process.env.GOOGLE_APPLICATION_CREDENTIALS;
const email = process.argv[2];
if (!keyPath || !fs.existsSync(keyPath) || !email) {
  console.error("Uso: GOOGLE_APPLICATION_CREDENTIALS=/ruta/privada/service-account.json node scripts/activate-admin.js correo@ejemplo.com");
  process.exit(1);
}
admin.initializeApp({credential: admin.credential.cert(require(require("node:path").resolve(keyPath)))});
(async () => {
  const user = await admin.auth().getUserByEmail(email);
  const claims = user.customClaims || {};
  await admin.auth().setCustomUserClaims(user.uid, {...claims, admin: true});
  console.log("Permiso admin asignado a UID:", user.uid);
  console.log("Cierra sesión y vuelve a ingresar en la aplicación para renovar el token.");
})().catch(err => { console.error(err.message); process.exitCode = 1; });
