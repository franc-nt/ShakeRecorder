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
- **Build**: Gradle 8.11.1 / AGP 8.10.1 with Kotlin DSL

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

### Mandatory release workflow

Every delivered code change MUST increment versionCode/versionName and publish a
GitHub Release in `franc-nt/ShakeRecorder`, tagged `v<versionName>`, containing
`ShakeRecorder.apk` and `update.json` from the same verified build. Follow
`RELEASING.md`. If publishing is blocked, report the blocker, not a completed release.

Keep application ID `com.shakerecorder`, the same signing certificate, and the
repository visibility unchanged. The current updater consumes public Releases;
if the repository becomes private, obtain an approved distribution design rather
than making it public or embedding a token. Never generate a replacement key, silently use debug signing,
commit secrets, embed GitHub credentials, or change repository permissions.
Persisting signing credentials requires explicit owner approval.

Preserve this SAME signing key for every future release in durable custody at a
destination specifically approved by the owner. Before starting each release,
verify that the key is available and its certificate matches the pinned SHA-256;
also validate recovery from the authorized backup without exposing key material
or passwords. A temporary folder or build workspace is not a durable backup.
If the original key or verified recovery is unavailable, stop delivery and report
the blocker. Never generate a replacement or deliver an APK signed with a
temporary key. Do not claim custody or backup is configured until it has actually
been approved, completed, and verified.

Update in place, never uninstall/clear app data. Android's installer confirmation
is required; do not promise silent installation. Preserve settings and recordings.

### Always use the build script

```bash
./build.sh
```

O script automaticamente:
1. Lê a versão atual do `build.gradle.kts`
2. Incrementa `versionCode` e `versionName`
3. Executa testes e lint e compila o APK release assinado
4. Verifica assinatura, pacote e versão antes de gerar `build/ShakeRecorder.apk`
   e `build/update.json`

`--no-version-bump` is only for retrying the same unpublished version. Never reuse
a published version or decrease versionCode. Missing signing credentials stop the
build; there is no key generation or debug fallback.

**NÃO fazer build manual.** Sempre usar o script para garantir versionamento correto.

## Version Control

- **Arquivo**: `app/build.gradle.kts`
- **Campos**: `versionCode` (inteiro) e `versionName` (string "X.X")
- **Exibição**: Versão aparece no drawer de configurações (3 tracinhos)

## APK Release

- **Folder**: `build/` inside this repository
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
- Consumo de bateria depende do aparelho e dos gatilhos; não foi medido
- Volume triggers não funcionam quando Spotify/YouTube está tocando (botões vão para o media player)
