# Fotos Duplicadas

APK Android (8.0 o superior) que encuentra fotos repetidas o casi iguales y conserva la de mejor calidad. Todo ocurre en el móvil: la app no declara el permiso de Internet, así que no puede enviar nada fuera.

## Cómo decide
- **Parecido:** huella visual doble (dHash horizontal + vertical, 128 bits) con filtros de brillo y proporción. Detecta copias exactas, recomprimidas (WhatsApp, Telegram), redimensionadas y fotos en ráfaga, según la sensibilidad.
- **La mejor:** más resolución → más nítida (varianza del Laplaciano) → archivo más pesado (menos compresión) → la más antigua (el original).
- Siempre queda al menos una foto por grupo.

## Borrado
- Android 11+: van a la **papelera del sistema** (recuperables 30 días) y Android pide confirmación.
- Android 8–10: borrado definitivo, con aviso previo.

## Compilar
Sube esta carpeta a un repositorio de GitHub. La acción «Compilar APK» se ejecuta sola en cada push; descarga `FotosDuplicadas-apk` desde la pestaña Actions.
En local: `gradle :app:assembleDebug` con el SDK de Android instalado.
