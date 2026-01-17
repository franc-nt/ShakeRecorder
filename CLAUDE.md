# ShakeRecorder

Android app that records audio when the phone is shaken 3 times.

## Development Environment

- **Target Platform**: Android 16 (launched 2025)
- **Test Device**: Samsung Galaxy S23 Ultra
- **Development Year**: 2026

## Working Features

- **Multiple Triggers**: 3 formas de iniciar/parar gravacao (configuraveis independentemente)
  - Shake: Balançar celular 3x
  - Volume+ Hold: Segurar Volume+ por 3 segundos
  - Volume+ Triple: Apertar Volume+ 3x rápido
- **Audio Beeps**: 1 bip ao iniciar, 2 bips ao parar (configuravel no app)
- **Vibration Feedback**: Vibracao ao iniciar/parar gravacao
- **Background Recording**: Continua gravando com tela bloqueada
- **Webhook Upload**: Envia audio automaticamente para webhook configurado
- **Crash Logging**: Salva logs de crash para debug

## Tech Stack

- **Language**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36 (Android 16)
- **Build**: Gradle 8.2 with Kotlin DSL

## Project Structure

```
app/src/main/
├── java/com/shakerecorder/
│   ├── App.kt                 # Application class, installs CrashHandler
│   ├── MainActivity.kt        # Main UI, webhook config, service toggle, trigger toggles
│   ├── RecorderService.kt     # Foreground service (triggers + recording + beeps)
│   ├── ShakeDetector.kt       # Accelerometer-based shake detection (3 shakes in 2s)
│   ├── VolumeButtonDetector.kt # MediaSession-based volume button detection
│   ├── AudioRecorder.kt       # MediaRecorder wrapper for M4A audio capture
│   ├── WebhookUploader.kt     # OkHttp multipart upload to webhook
│   ├── SettingsManager.kt     # SharedPreferences (triggers, webhook, sound)
│   └── CrashHandler.kt        # Uncaught exception handler, saves logs
├── res/
│   ├── layout/activity_main.xml
│   └── values/{strings,colors,themes}.xml
└── AndroidManifest.xml
```

## App Settings

| Setting | Default | Description |
|---------|---------|-------------|
| Webhook URL | webhook.site/620bad6b-... | URL para envio do audio |
| Som de bip | Ativado | 1 bip inicio, 2 bips fim |
| Shake trigger | Ativado | Balançar 3x para gravar |
| Volume+ Hold | Desativado | Segurar 3s para gravar |
| Volume+ Triple | Desativado | Apertar 3x para gravar |

## Audio Output

- **Format**: M4A (AAC)
- **Bitrate**: 128 kbps
- **Sample Rate**: 44100 Hz
- **Location**: `/storage/emulated/0/Music/ShakeRecorder/`
- **Filename**: `recording_YYYYMMDD_HHmmss.m4a`

## Build

### IMPORTANTE: Sempre usar o script de build

```bash
/mnt/c/Users/Francisco/record/ShakeRecorder/build.sh
```

O script automaticamente:
1. Lê a versão atual do `build.gradle.kts`
2. Incrementa `versionCode` e `versionName`
3. Compila o APK
4. Copia para `build/ShakeRecorder.apk` (sobrescreve o anterior)

**NÃO fazer build manual.** Sempre usar o script para garantir versionamento correto.

## Version Control

- **Arquivo**: `app/build.gradle.kts`
- **Campos**: `versionCode` (inteiro) e `versionName` (string "X.X")
- **Exibição**: Versão aparece no drawer de configurações (3 tracinhos)

## APK Release

- **Folder**: `C:\Users\Francisco\record\build\`
- **Naming**: `ShakeRecorder.apk` (nome fixo, versão fica apenas dentro do app)
- Sempre sobrescrever com a última versão compilada

## Permissions Required

- `RECORD_AUDIO` - microphone access
- `FOREGROUND_SERVICE` - background service
- `FOREGROUND_SERVICE_MICROPHONE` - mic in background
- `FOREGROUND_SERVICE_SPECIAL_USE` - shake detection
- `VIBRATE` - haptic feedback
- `INTERNET` - webhook upload
- `WAKE_LOCK` - keep CPU awake
- `POST_NOTIFICATIONS` - foreground notification

## Default Webhook

```
https://webhook.site/620bad6b-72ab-43ec-b0c2-a975290d210f
```

## Debug

- Crash logs: `Documents/ShakeRecorder/crash_log.txt`
- Previous crash shown in app log area on next launch
- ADB logs: `adb logcat -s ShakeRecorder:*`

## Known Issues

- Samsung One UI may kill background service - disable battery optimization for the app
- Consumo de bateria moderado (~3-5% por dia) devido ao acelerometro sempre ativo
- Volume triggers não funcionam quando Spotify/YouTube está tocando (botões vão para o media player)
