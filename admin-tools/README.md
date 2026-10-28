# Activar administrador de forma segura

Este directorio contiene una herramienta de uso local. **Nunca subas credenciales ni archivos de cuenta de servicio al repositorio.**

1. En Firebase Console > Authentication > Usuarios, copia el UID del usuario con correo y contraseña.
2. En tu Mac, instala Node.js LTS y Google Cloud CLI (gcloud).
3. Abre Terminal en esta carpeta y ejecuta `npm install`.
4. Ejecuta `gcloud auth application-default login` e inicia sesión con una cuenta autorizada para el proyecto.
5. Ejecuta `gcloud config set project bus-isla-de-maipo` (confirma primero el ID real del proyecto en Firebase).
6. Ejecuta `export GOOGLE_CLOUD_PROJECT=bus-isla-de-maipo` (sustituye por el ID real si difiere).
7. Ejecuta `node set-admin.cjs UID_DEL_USUARIO`.
8. Publica las reglas actualizadas de `firestore.rules` en Firestore Console.
9. Inicia sesión en la app con ese correo y contraseña.

Se requieren permisos IAM suficientes para administrar usuarios de Firebase Authentication. No publiques tokens, contraseñas ni credenciales. El UID no es una contraseña.

El panel requiere la custom claim `admin: true`; no basta con crear el usuario en Authentication.
