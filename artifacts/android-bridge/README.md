# AI Bridge — Android Accessibility Service

Puente de accesibilidad para Android que expone la UI del dispositivo a un agente de IA externo (Groq/LLM) corriendo en Termux, a través de un servidor HTTP local en el puerto 8080.

## Estructura del proyecto

```
app/src/main/
├── kotlin/com/bridge/accessibility/
│   ├── IAccessibilityService.kt   # Servicio de accesibilidad principal
│   ├── BridgeHttpServer.kt        # Servidor HTTP (NanoHTTPD)
│   └── MainActivity.kt            # UI de estado
├── res/
│   ├── xml/accessibility_service_config.xml  # Flags de accesibilidad
│   ├── layout/activity_main.xml
│   └── values/strings.xml
└── AndroidManifest.xml
```

## API HTTP (puerto 8080)

### GET /screen
Retorna el árbol de UI serializado como JSON optimizado para LLMs.

```bash
curl http://localhost:8080/screen
```

Respuesta:
```json
{
  "package": "com.example.app",
  "nodes": [
    {
      "text": "Buscar",
      "cls": "EditText",
      "rect": { "x": 540, "y": 120, "w": 900, "h": 60 },
      "clickable": true,
      "editable": true
    }
  ]
}
```

Optimizaciones para reducir tokens:
- Solo incluye nodos con `text`, `desc`, o que sean contenedores interactivos
- Nombres de clase simplificados (sin package prefix)
- Coordenadas del **centro** del elemento (x, y) + dimensiones (w, h)
- Campos omitidos si están vacíos o son `false`

### POST /action

#### Click en coordenadas
```bash
curl -X POST http://localhost:8080/action \
  -H 'Content-Type: application/json' \
  -d '{"action": "click", "x": 540, "y": 960}'
```

#### Scroll (swipe)
```bash
curl -X POST http://localhost:8080/action \
  -H 'Content-Type: application/json' \
  -d '{"action": "scroll", "x": 540, "y": 960, "dx": 0, "dy": -500}'
```

#### Acción global
```bash
# Tipos: HOME, BACK, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN
curl -X POST http://localhost:8080/action \
  -H 'Content-Type: application/json' \
  -d '{"action": "global", "type": "HOME"}'
```

#### Escribir texto (en campo enfocado)
```bash
curl -X POST http://localhost:8080/action \
  -H 'Content-Type: application/json' \
  -d '{"action": "input", "text": "hola mundo"}'
```

### GET /health
```bash
curl http://localhost:8080/health
# {"status":"ok"}
```

## Compilar e instalar

### Requisitos
- Android Studio Hedgehog o superior
- Android SDK 34
- Dispositivo/emulador con Android 8.0+ (API 26+)

### Compilar
```bash
./gradlew assembleDebug
```

El APK se genera en: `app/build/outputs/apk/debug/app-debug.apk`

### Instalar via ADB
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Activar el servicio
1. Abrir la app **AI Bridge**
2. Pulsar **"Abrir Ajustes de Accesibilidad"**
3. Buscar **"AI Bridge Accessibility Service"** y activarlo
4. Volver a la app — el servidor HTTP ya está activo

## Integración con Groq desde Termux

```python
import requests
import json

BASE = "http://localhost:8080"

def get_screen():
    return requests.get(f"{BASE}/screen").json()

def click(x, y):
    return requests.post(f"{BASE}/action", json={"action": "click", "x": x, "y": y}).json()

def go_home():
    return requests.post(f"{BASE}/action", json={"action": "global", "type": "HOME"}).json()

def type_text(text):
    return requests.post(f"{BASE}/action", json={"action": "input", "text": text}).json()

# Ejemplo con Groq
screen = get_screen()
# Pasar screen['nodes'] al contexto del LLM para que decida qué acción tomar
```

## Dependencias

- **NanoHTTPD 2.3.1** — servidor HTTP embebido (~55 KB, sin dependencias externas)
- **kotlinx-coroutines-android** — gestión de concurrencia
- **AndroidX AppCompat + Core KTX**

## Seguridad

El servidor HTTP solo escucha en `localhost` (127.0.0.1). No es accesible desde la red externa. Termux y otras apps del dispositivo pueden acceder a `localhost:8080` sin problema.
