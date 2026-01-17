# ShakeRecorder

Aplicativo Android que grava áudio quando o celular é balançado 3 vezes.

## Funcionalidades

- **Múltiplos Gatilhos**: 3 formas de iniciar/parar gravação (configuráveis independentemente)
  - **Shake**: Balançar o celular 3x
  - **Volume+ Hold**: Segurar botão Volume+ por 3 segundos
  - **Volume+ Triple**: Apertar Volume+ 3x rapidamente
- **Feedback Sonoro**: 1 bip ao iniciar, 2 bips ao parar (configurável)
- **Vibração**: Feedback tátil ao iniciar/parar gravação
- **Gravação em Background**: Continua gravando com tela bloqueada
- **Upload Automático**: Envia áudio automaticamente para webhook configurado
- **Log de Crashes**: Salva logs de erro para debug

## Screenshots

| Tela Principal | Menu de Configurações |
|:--------------:|:---------------------:|
| Botão central para ativar/desativar o serviço | Configurações de gatilhos, webhook e som |

## Requisitos

- Android 8.0 (API 26) ou superior
- Permissões: Microfone, Notificações

## Instalação

1. Baixe o APK da [página de releases](../../releases)
2. Instale no dispositivo Android
3. Conceda as permissões solicitadas
4. Ative o serviço tocando no botão central

## Configurações

| Configuração | Padrão | Descrição |
|--------------|--------|-----------|
| Webhook URL | - | URL para envio do áudio |
| Som de bip | Ativado | 1 bip início, 2 bips fim |
| Shake trigger | Ativado | Balançar 3x para gravar |
| Volume+ Hold | Desativado | Segurar 3s para gravar |
| Volume+ Triple | Desativado | Apertar 3x para gravar |

## Formato do Áudio

- **Formato**: M4A (AAC)
- **Bitrate**: 128 kbps
- **Sample Rate**: 44100 Hz
- **Local**: `/storage/emulated/0/Music/ShakeRecorder/`
- **Nome**: `recording_YYYYMMDD_HHmmss.m4a`

## Build

### Pré-requisitos

- JDK 17
- Android SDK (API 36)

### Compilar

```bash
cd ShakeRecorder
./gradlew assembleDebug
```

O APK será gerado em: `app/build/outputs/apk/debug/app-debug.apk`

## Estrutura do Projeto

```
app/src/main/
├── java/com/shakerecorder/
│   ├── App.kt                  # Application class
│   ├── MainActivity.kt         # UI principal
│   ├── RecorderService.kt      # Serviço de gravação
│   ├── ShakeDetector.kt        # Detecção de shake
│   ├── VolumeButtonDetector.kt # Detecção de botões de volume
│   ├── AudioRecorder.kt        # Gravação de áudio
│   ├── WebhookUploader.kt      # Upload para webhook
│   ├── SettingsManager.kt      # Gerenciamento de configurações
│   └── CrashHandler.kt         # Handler de crashes
├── res/
│   ├── layout/                 # Layouts XML
│   ├── drawable/               # Ícones e drawables
│   └── values/                 # Strings, cores, temas
└── AndroidManifest.xml
```

## Permissões

| Permissão | Uso |
|-----------|-----|
| `RECORD_AUDIO` | Acesso ao microfone |
| `FOREGROUND_SERVICE` | Serviço em background |
| `FOREGROUND_SERVICE_MICROPHONE` | Microfone em background |
| `VIBRATE` | Feedback tátil |
| `INTERNET` | Upload para webhook |
| `WAKE_LOCK` | Manter CPU ativa |
| `POST_NOTIFICATIONS` | Notificação do serviço |

## Problemas Conhecidos

- **Samsung One UI**: Pode encerrar o serviço em background. Desative a otimização de bateria para o app.
- **Consumo de bateria**: ~3-5% por dia devido ao acelerômetro sempre ativo.
- **Gatilhos de volume**: Não funcionam quando Spotify/YouTube está tocando (botões vão para o media player).

## Debug

- **Crash logs**: `Documents/ShakeRecorder/crash_log.txt`
- **ADB logs**: `adb logcat -s ShakeRecorder:*`

## Tech Stack

- **Linguagem**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36 (Android 16)
- **Build**: Gradle 8.2 com Kotlin DSL

## Licença

Este projeto é de uso pessoal.
