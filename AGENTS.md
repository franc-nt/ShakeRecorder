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

Keep application ID `com.shakerecorder` and repository visibility unchanged. The
1.24 signing key was lost. The owner authorized one random replacement key for
the planned 1.25 signing lineage after being informed that it cannot update 1.24,
including by manually opening the APK. This authorization does not confirm a
durable storage destination or a verified backup. Follow the explicit migration
gate in `RELEASING.md`; do not generate another key for later releases.

The current updater consumes public Releases;
if the repository becomes private, obtain an approved distribution design rather
than making it public or embedding a token. Never silently replace a signing key,
use debug signing as a fallback, commit secrets, embed GitHub credentials, or
change repository permissions. Persisting signing credentials requires explicit
owner approval of the destination and access after disclosure of the risks.

Preserve the SAME new signing key for every release in the new lineage in durable custody at a
destination specifically approved by the owner. Before starting each release,
verify that the key is available and its certificate matches the pinned SHA-256;
also validate recovery from the authorized backup without exposing key material
or passwords. A temporary folder or build workspace is not a durable backup.
If that key or verified recovery is unavailable, stop delivery and report
the blocker. One explicit exception applies only to the first new-key prerelease
1.25: the owner requested publication while durable key custody remains pending.
Keep that limitation prominent; it is not authorization to transmit the private
key to an unapproved destination, nor a promise of future update continuity.
Never generate a further replacement or use a temporary/debug fallback key. Do
not claim custody or backup is configured until it has actually been approved,
completed, and verified.

Update in place only within a signing lineage. Never weaken
`AppUpdater.requireCompatible` to accept a different certificate; changing the
build pin does not migrate Android installations. Never uninstall/clear app data
or promise that the new key preserves 1.24 settings. The app has no settings
export/import. Keep 1.24 installed until a separate, owner-approved data-preserving
migration plan is ready. Android's installer confirmation is required; do not
promise silent installation.

Do not expose the incompatible first release through Latest or the legacy
updater. Preserve `v1.24` and its assets. Use an explicitly labeled prerelease
outside Latest under the gates and the narrow custody exception in
`RELEASING.md`. `release.sh --migration-prerelease` is required for version 1.25;
the script rejects stable publication of that version. Later stable releases
still require an explicit decision about legacy users consuming the same Latest
endpoint. Do not imply they can update 1.24.

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

### Validation without signing or delivery

Run `./build.sh --check-only` to execute Python release-tool tests, Android unit
tests, and debug/release lint without a signing key. This mode does not build an
APK, create a key, increment the version, or replace `build/ShakeRecorder.apk` or
`build/update.json`. It still requires JDK 21 and the Android SDK. It may write
normal Gradle caches, test results, and lint reports.

Do not combine `--check-only` with `--no-version-bump` or any other argument.
Validation-only is not a delivered release and does not relax the signing,
certificate pin, version increment, or publication requirements above.

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
