<p align="center">
  <img src="docs/images/app-icon.png" width="120" alt="Castla App Icon">
  <h1 align="center">Castla</h1>
  <p align="center">
    <strong>La alternativa definitiva a Android Auto para Tesla. Waze, Google Maps y tu pantalla directamente en el navegador de Tesla.</strong>
  </p>
  <p align="center">
    <a href="https://github.com/Suprhimp/castla/releases/latest"><img src="https://img.shields.io/github/v/release/Suprhimp/castla?style=flat-square" alt="Latest Release"></a>
    <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="License"></a>
    <a href="https://ko-fi.com/suprhimp"><img src="https://img.shields.io/badge/Ko--fi-Support-ff5e5b?style=flat-square&logo=ko-fi&logoColor=white" alt="Ko-fi"></a>
  </p>
  <p align="center">
    <a href="README.md">English</a> · <a href="README.ko.md">한국어</a> · <a href="README.ja.md">日本語</a> · <a href="README.zh-CN.md">中文</a> · <a href="README.de.md">Deutsch</a> · <a href="README.fr.md">Français</a>
  </p>
</p>

<p align="center">
  <img src="docs/images/hero.jpg" width="700" alt="Castla - Android reflejado en pantalla Tesla">
</p>

---

## ¿Qué es Castla?

¿Echas de menos **Android Auto** en tu Tesla? ¿Quieres usar **Waze** o Google Maps en la pantalla grande?

Castla es una solución gratuita y de código abierto que transmite la pantalla de tu teléfono Android directamente al navegador integrado de Tesla a través de tu red WiFi local. Sin conexión a internet, sin dongles caros, sin servidores en la nube, sin suscripciones — todo se mantiene rápido, seguro y completamente entre tu teléfono y tu coche.

**Características principales:**

- **Experiencia Android Auto** — Usa tus apps favoritas de navegación y música en la pantalla de Tesla
- **Duplicación en tiempo real** — Codificación H.264 por hardware + streaming WebSocket para latencia ultra baja
- **Control táctil completo** — Toca, desliza e interactúa directamente desde la pantalla de Tesla (vía Shizuku)
- **Streaming de audio** — Audio del dispositivo directo a los altavoces de Tesla (Android 10+)
- **100% local y privado** — Todos los datos permanecen en tu WiFi/hotspot
- **Completamente gratis** — Sin anuncios, sin muros de pago. Código abierto bajo Apache-2.0

## Funciones

| Función | Detalles |
|---------|----------|
| **Navegación en pantalla grande** | **Waze**, Google Maps y cualquier app hasta 1080p @ 60fps |
| **Entrada táctil** | Inyección táctil completa vía Shizuku. Controla tu teléfono desde la pantalla del coche |
| **Vista dividida** | Multitarea en doble panel. ¡Waze a la izquierda, YouTube a la derecha! |
| **Pantalla virtual** | Ejecuta apps independientemente en Tesla sin mantener la pantalla del teléfono encendida |
| **Audio** | Captura de audio del sistema (Android 10+, experimental) |
| **Auto-detección Tesla** | BLE + detección de clientes hotspot para conexión automática |
| **Auto Hotspot** | Activar/desactivar hotspot automáticamente al iniciar/detener |
| **Navegador OTT** | Navegador integrado para contenido DRM (YouTube, Netflix, etc.) |
| **Protección térmica** | Reducción automática de calidad cuando se sobrecalienta |
| **9 idiomas** | EN, KO, DE, ES, FR, JA, NL, NO, ZH |

## Requisitos

- Android 8.0+ (API 26)
- [Shizuku](https://shizuku.rikka.app/) para control táctil y funciones avanzadas
- Vehículo Tesla con navegador web
- Teléfono y Tesla en la misma red WiFi (o hotspot del teléfono)

## Instalación

1. Ve a [Releases](https://github.com/Suprhimp/castla/releases/latest)
2. Descarga el archivo `.apk` más reciente
3. Instala en tu dispositivo Android

## Inicio rápido

1. **Instalar Shizuku** — Abre Castla, toca "Instalar Shizuku"
2. **Iniciar Shizuku** — Opciones de desarrollador → Depuración inalámbrica → Abrir Shizuku → "Iniciar vía Depuración inalámbrica"
3. **Conceder permiso** — Permite a Castla usar Shizuku
4. **Conectar** — Asegúrate de que el teléfono y Tesla estén en el mismo WiFi
5. **Iniciar duplicación** — Toca "Iniciar Duplicación" en Castla
6. **Abrir en Tesla** — Ingresa la URL mostrada en el navegador de Tesla

## Contribuir

¡Las contribuciones son bienvenidas! Consulta la [Guía de Contribución](CONTRIBUTING.md).

## Privacidad

Castla **no recopila ningún dato**. Ver [Política de Privacidad](PRIVACY.md).

## Apoyar al desarrollador indie

<a href="https://ko-fi.com/suprhimp"><img src="https://ko-fi.com/img/githubbutton_sm.svg" alt="Apoyar en Ko-fi"></a>

## Construido con

Castla se apoya en el trabajo de grandes proyectos de código abierto:

- [Shizuku](https://shizuku.rikka.app/) — acceso a APIs privilegiadas sin root
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) — servidor HTTP + WebSocket embebido
- [ZXing](https://github.com/zxing/zxing) — generación de códigos QR
- [AndroidX / Jetpack Compose](https://developer.android.com/jetpack) — toolkit moderno de UI para Android
- [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines) — pipeline de streaming asíncrono

Algunas técnicas del modo privilegiado se inspiraron en [scrcpy](https://github.com/Genymobile/scrcpy) (Apache-2.0), en concreto:

- Encendido/apagado del panel físico de la pantalla mediante `SurfaceControl` para el mirroring con pantalla apagada
- El patrón `fillAppInfo` / `FakeContext` para falsificar `AttributionSource` al capturar audio del sistema desde un servicio con UID shell

No se incluye código fuente de scrcpy — Castla reimplementa estos enfoques en su propia arquitectura.

Consulta [NOTICE](NOTICE) para la lista completa de atribuciones de terceros.

## Licencia

[Apache License 2.0](LICENSE)
