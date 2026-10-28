// Run only on a trusted computer with Google Application Default Credentials.
// Usage: node set-admin.cjs <Firebase-Auth-UID>
const admin = require('firebase-admin');
const uid = process.argv[2];
if (!uid || !/^[A-Za-z0-9_-]{8,128}$/.test(uid)) {
 console.error('Uso: node set-admin.cjs UID_DE_FIREBASE');
 process.exit(1);
}
admin.initializeApp({credential: admin.credential.applicationDefault()});
(async () => {
 const user = await admin.auth().getUser(uid);
 if (!user.email || user.providerData.every(p => p.providerId !== 'password')) {
   throw new Error('El usuario debe tener correo y contraseña registrados.');
 }
 const claims = user.customClaims || {};
 await admin.auth().setCustomUserClaims(uid, {...claims, admin: true});
 console.log('Permiso admin asignado a:', user.email);
 console.log('Cierra sesión y vuelve a entrar en la app para renovar el token.');
})().catch(e => { console.error(e.message); process.exitCode = 1; });
