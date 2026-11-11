# Fotos Duplicadas

APK Android (8.0 o superior) que encuentra fotos repetidas o casi iguales y conserva la de mejor calidad. Todo ocurre en el móvil: la app no declara el permiso de Internet, así que no puede enviar nada fuera.

## Cómo decide
- **Parecido:** huella visual doble (dHash horizontal + vertical, 128 bits) con filtros de brillo y proporción. Detecta copias exactas, recomprimidas (WhatsApp, Telegram), redimensionadas y fotos en ráfaga, según la sensibilidad.
- **La mejor:** más resolución → más nítida (varianza del Laplaciano) → archivo más pesado (menos compresión) → la más antigua (el original).
- Siempre queda al menos una foto por grupo.

## Funciones
- Control de parecido ajustable en la propia pantalla de resultados (de «solo copias» a «ráfagas»).
- Grupos etiquetados como **Copias** o **Parecidas**; detección opcional de fotos **giradas o volteadas**.
- Límite por tiempo: agrupar solo fotos hechas con menos de 1 min, 10 min, 1 h o el mismo día.
- Qué conservar: mejor calidad, mayor resolución, la que más ocupa, la más antigua o la más reciente; preferencia por la original de la cámara frente a copias de WhatsApp o descargas.
- Alcance: toda la galería, solo cámara o carpetas elegidas; carpetas excluidas; tamaño mínimo.
- **Fotos borrosas** en una pestaña aparte (nunca se marcan solas).
- Visor a pantalla completa con datos (resolución, peso, fecha, carpeta, nitidez), deslizar entre fotos del grupo.
- Auto-marcar / Desmarcar todo, y «No son iguales» para que un grupo no vuelva a aparecer.
- Modo claro y oscuro.

## Explorar (v1.2)
- **Personas:** detecta caras y las agrupa por persona (MobileFaceNet). Se les puede poner nombre; dos con el mismo nombre se unen.
- **Con / sin personas, grupos y selfies.**
- **Categorías:** capturas, documentos y recibos, WhatsApp, comida, animales, paisajes, ciudad, vehículos, fiestas, deporte, bebés y juguetes (ML Kit, modelo incluido en la APK).
- **Privacidad:** lista de fotos con ubicación GPS y borrado de la ubicación o de todos los datos personales, por lotes.
- **Metadatos:** ver fecha, cámara, objetivo, exposición, ISO, ubicación, autor… y editar fecha, descripción, autor y copyright (JPEG, PNG, WebP).
- Las librerías de ML Kit piden Internet para estadísticas; el permiso se elimina del manifiesto, así que la app sigue sin conexión.

## Calculadora secreta (v1.4)
- Al abrir la app aparece una calculadora que funciona. Escribiendo el código (por defecto `5454+5353`) y pulsando «=» se entra.
- En Ajustes: cambiar el código, desactivar la calculadora y disfrazar icono y nombre como «Calculadora».
- Si sales de la app más de 30 segundos, vuelve a pedir el código; la vista previa en recientes se oculta (Android 13+).

## Carpetas (v1.5)
- Pestaña **Carpetas**: todas las carpetas de fotos del móvil; abre cualquiera para ver, mover o borrar.
- **Mover** desde cualquier rejilla (categoría, persona, carpeta) a una carpeta existente o nueva.
- **Nueva carpeta**: se le pone nombre y se eligen las fotos.
- **Ordenar automáticamente** por mes, por año, por persona (con nombre) o por categoría, con vista previa.
- Los movimientos se hacen en la memoria del móvil (dentro de Imágenes), así que cualquier galería ve el mismo orden.
- **Deshacer** el último orden.
- Opcional en Android 12+: «Gestión de contenido multimedia» para no confirmar cada tanda.

## Borrado
- Android 11+: van a la **papelera del sistema** (recuperables 30 días) y Android pide confirmación.
- Android 8–10: borrado definitivo, con aviso previo.

## Compilar
Sube esta carpeta a un repositorio de GitHub. La acción «Compilar APK» se ejecuta sola en cada push; descarga `FotosDuplicadas-apk` desde la pestaña Actions.
En local: `gradle :app:assembleDebug` con el SDK de Android instalado.
