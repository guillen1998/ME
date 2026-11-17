# ApkForge

Herramienta de ingeniería inversa de APKs **para Android**, al nivel de las de PC
(apktool + jadx + apksigner) pero en el móvil, con explorador de archivos estilo MT Manager.

## Qué hace

| Función | Detalle |
|---|---|
| **Explorador de archivos** | Navegar, copiar/cortar/pegar, renombrar, eliminar, crear, compartir. Detecta proyectos. |
| **Inspector de APK** | Paquete, versión, SDKs, permisos, actividades, servicios, receptores, proveedores, librerías nativas, firma (v1–v4, SHA-256), y detección de Flutter / Unity / React Native / Xamarin / empaquetadores. |
| **Decompilar** | Recursos y `AndroidManifest.xml` a XML legible + dex a **smali**. Sin `aapt`: usa ARSCLib en Java puro, así que funciona en cualquier móvil. |
| **Recompilar** | XML → binario, smali → dex. **Reutiliza el dex original si no lo tocaste** (más rápido y sin riesgo). |
| **Firmar y alinear** | v1 + v2 + v3, alineación 4 B / 16 KB para `.so`. Clave de prueba incluida o tu propio keystore (PKCS12/BKS). |
| **Ver código Java** | jadx en el móvil: navegador de paquetes, búsqueda de clases, búsqueda de texto en el código, ver Java ↔ smali, exportar todo. |
| **Legibilidad** | smali con nombres de variables (`.local`/`.param`), etiquetas legibles y comentarios de recursos; Java con constantes `R.*`, nombres de Kotlin, sin el bloque `@Metadata`, y opción de desofuscar. |
| **Editor de código** | Sora Editor: resaltado smali/Java/XML/JSON, números de línea, deshacer/rehacer, buscar y reemplazar (regex), ir a línea, **“Ver como Java” del smali que editas**. |
| **Buscar en el proyecto** | Grep en smali/xml/todo, con regex y **reemplazar en todos los archivos**. |
| **Apps instaladas** | Lista, búsqueda, extraer APK (incluye splits) o abrirla directamente en las herramientas. |
| **Abrir con…** | Recibe APKs desde cualquier gestor de archivos o “Compartir”. |

## Estructura

```
apk-forge/
├── engine/   Motor en Kotlin/JVM puro (probado en PC y usado tal cual en Android)
│   ├── ApkInspector   análisis de APK + firma
│   ├── ApkProject     decompilar / recompilar (ARSCLib + smali/baksmali)
│   ├── ApkSigning     firma v1/v2/v3 + alineación (apksig)
│   └── JavaDecompiler sesión jadx bajo demanda
└── app/      App Android (Jetpack Compose + Sora Editor)
```

Carpetas en el móvil: `/sdcard/ApkForge/{projects,builds,extracted,java}`.

## Compilar

```bash
cd apk-forge
./gradlew :app:assembleRelease          # APK en app/build/outputs/apk/release/
./gradlew :engine:test -Dapkforge.sampleApk=/ruta/app.apk   # prueba de extremo a extremo
```

GitHub Actions (`.github/workflows/apk-forge.yml`) prueba el motor y genera el APK en cada push.

## Límites honestos

- La ofuscación de R8/ProGuard **borra los nombres originales**: ninguna herramienta los recupera.
  La opción *Desofuscar* pone nombres únicos para poder seguir el código.
- Flutter, Unity IL2CPP o React Native no guardan la lógica en el dex; el inspector te avisa.
- Apps con empaquetadores (Jiagu, Legu, etc.) tienen el dex real cifrado.
- Una app recompilada lleva otra firma: desinstala la original antes de instalarla.

Úsala con apps propias, de código abierto o con permiso de su autor.
