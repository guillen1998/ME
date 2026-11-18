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

Carpetas en el móvil: `/sdcard/ApkTools/` con **Proyectos** (lo que editas), **Compilados** (APKs exportados y firmados), **Extraidos** y **Java**.

## Novedades

- **Doctor de compilación** (0.11): antes de exportar revisa todos los archivos tocados (smali ensamblado uno a uno, XML válido, recursos duplicados o inexistentes), unifica duplicados solo, la IA repara lo que pueda y, si no, deshace únicamente el archivo que rompe. Siempre sale un APK y el informe dice qué se deshizo.
- **Equipo de IAs**: un modelo planifica, el agente ejecuta con herramientas y otro modelo de otra familia revisa y devuelve los fallos.
- **16 modelos gratis sin clave**: DeepSeek V4.1/V4, Kimi K2.7 Code, Qwen 3.5 397B / 3.6 / 3.8, GLM 5.3 Flash, MiMo v2.6, MiniMax M2.7, Codestral, GPT-OSS 120B/20B, Gemma 4, Llama 3.3. Con una clave gratis de Gemini (Google AI Studio), OpenRouter o Groq, ese modelo se suma al equipo.
- **Motor neuronal local**: red wide & deep en el móvil que ordena los archivos relevantes para cada pedido y aprende de lo que la IA edita.
- **Listas → cuadrícula** automático (LinearLayoutManager → GridLayoutManager, en smali y XML).

- **Modo IA / Modo manual** en todos los editores: la IA reemplaza, parchea, quita o crea código sobre la selección o el archivo, muestra el diff antes de aplicar y, en smali, lo ensambla y revisa los registros; si falla, se corrige sola.
- **Pantalla doble estilo MT Manager**: opciones a la izquierda, interior del APK a la derecha; edición de XML binario dentro del APK sin decompilar.
- **Segundo plano**: servicio con progreso en la notificación; búsquedas, escáner, agente y ediciones IA siguen aunque salgas.
- **Agente IA** con Claude o modelos abiertos (OpenRouter, Groq, Ollama) y perfil de analista de archivos.
- **Exportar nuevo APK y firmar** desde proyectos, el interior del APK y el editor dex.

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
