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
4. Ative o monitoramento nas configurações de gatilhos ou toque em Iniciar para gravar.

**Não desinstale nem limpe os dados para atualizar.** O APK 1.24 usa o mesmo certificado da candidata 1.23 entregue. Se o Android indicar conflito de assinatura, interrompa a instalação e procure uma versão assinada com a chave correta.

## Atualizar pelo aplicativo (1.24+)

1. Abra o menu, expanda **Opções** e toque em **Atualizar aplicativo**.
2. Toque em **Verificar atualização**. Quando houver uma versão nova nas Releases públicas do projeto, toque em **Baixar e atualizar**.
3. O app verifica tamanho, SHA-256, pacote, versão numérica e a mesma assinatura da instalação atual. Arquivos incompatíveis são bloqueados.
4. Se necessário, autorize o ShakeRecorder na tela do Android para instalar atualizações dessa origem. O app não concede essa permissão sozinho nem altera proteções Samsung.
5. Confirme a instalação no instalador Android. Não é uma instalação silenciosa. Termine qualquer gravação antes: o app não abre o instalador enquanto houver uma gravação ativa.
6. Reabra o app e confira a versão, configurações e gravações. Cancelar o instalador retoma o monitoramento, respeitando se você o parou explicitamente.

A primeira instalação da versão com o atualizador ainda exige abrir o APK recebido, **por cima da 1.23 compatível**, sem desinstalar. As próximas usam o botão. O repositório precisa conter uma Release estável com `ShakeRecorder.apk` e `update.json` da mesma compilação, conforme `RELEASING.md`. O app não contém tokens GitHub; uma distribuição privada exige outro desenho autorizado.

### Proteção dos dados

As configurações ficam em SharedPreferences privadas (`shake_recorder_prefs`) e os áudios em `Music/ShakeRecorder`. Atualizações mantêm pacote, assinatura, preferências e diretório. O app ainda não tem exportação/importação de configurações: `allowBackup` não garante restauração após desinstalar. Uma cópia manual dos áudios pelo Meus Arquivos da Samsung pode ser útil, mas não substitui manter a instalação existente. Nunca desinstale para resolver uma assinatura incompatível.

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

- JDK 21 (necessário para os testes Robolectric do Android 16)
- Android SDK (API 36)

### Compilar

Configure `ANDROID_HOME` com o diretório do SDK e as quatro variáveis de assinatura descritas em [RELEASING.md](RELEASING.md). Execute o script do projeto: ele incrementa a versão, executa testes e lint, compila o release assinado e verifica pacote, versão e certificado. Use `./build.sh --no-version-bump` somente para repetir a validação da mesma versão ainda não publicada.

```bash
cd ShakeRecorder
./build.sh
```

O APK validado será copiado para `build/ShakeRecorder.apk`, acompanhado de `build/update.json`. Toda alteração entregue exige uma nova GitHub Release contendo os dois arquivos da mesma compilação. Veja o procedimento em [RELEASING.md](RELEASING.md).

### Atualização sem perder dados

O pacote continua sendo `com.shakerecorder`, com as mesmas preferências e pasta de gravações. O Android só aceita atualizar por cima quando o APK novo usa o mesmo certificado do instalado. A versão 1.23 utilizou uma chave de depuração existente; os novos builds release devem usar essa mesma chave, fornecida de forma segura, para manter a compatibilidade. O build bloqueia certificados diferentes e não cria chaves automaticamente. Uma chave nova **não** atualiza a instalação existente. Não desinstale o aplicativo para contornar um erro de assinatura.

O arquivo de chave e suas senhas não devem ser enviados para este repositório. A compilação em outro computador serve como candidata para validação, não comprova compatibilidade com a assinatura do APK que está no celular.

### Verificações no Galaxy S23 Ultra

Antes de usar em gravações importantes, verificar no aparelho:

- Android/One UI atual: menu, todas as seções e Salvar em tema claro e escuro.
- Navegação por gestos e por três botões, retrato/paisagem e fontes maiores.
- Abrir o teclado nos campos e usar Voltar para fechar teclado e menu.
- Microfone autorizado, inclusive se notificações forem negadas.
- Iniciar/parar, tocar o áudio salvo e verificar gravação com tela bloqueada.
- Testar os gatilhos configurados e os destinos de envio já autorizados.

Os testes automatizados não substituem esses testes físicos do microfone, sensores e gerenciamento de bateria da Samsung.

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

## Gatilhos e configurações na versão 1.23

- Ligar um gatilho inicia o monitoramento, sem iniciar uma gravação. Autorize o microfone quando solicitado e confirme o status de serviço ativo.
- Alterações de quantidade, duração e liga/desliga são aplicadas imediatamente. Não é necessário fechar ou reiniciar o app, e uma gravação em andamento é preservada.
- Com a tela do app aberta, Volume+ usa eventos reais de pressionar/soltar: as repetições de uma tecla segurada não contam como vários cliques.
- Ao minimizar ou bloquear, o serviço mantém a detecção por MediaSession. Essa API fornece pulsos de volume, não eventos completos de teclado; a detecção de segurar é aproximada. Outros players e o firmware podem assumir os botões.
- A notificação **Parar** encerra o serviço e salva uma gravação em andamento. Abra o app e ative um gatilho ou use Iniciar para voltar a monitorar.
- O monitoramento mantém a CPU acordada enquanto o serviço está ativo; isso pode aumentar o consumo de bateria. A concessão é renovada e liberada ao encerrar o serviço.

Teste no S23 Ultra: três cliques iniciam e outros três param (aguarde ao menos 2 segundos entre comandos); repetir com app aberto, minimizado e tela bloqueada, inclusive após mais de 10 minutos. Confira reprodução do áudio e alterações de configuração durante uma gravação. Esses cenários físicos ainda não foram verificados nesta versão.

## Problemas Conhecidos

- **Samsung One UI**: Pode encerrar o serviço em background. Desative a otimização de bateria para o app.
- **Consumo de bateria**: depende dos gatilhos e do aparelho; não foi medido nesta versão.
- **Gatilhos de volume**: Não funcionam quando Spotify/YouTube está tocando (botões vão para o media player).

## Integração Hermes → Telegram

A integração de servidor recuperada no [PR #1](https://github.com/franc-nt/ShakeRecorder/pull/1) está incluída em [`server/hermes/`](server/hermes/README.md), junto dos seus 10 testes. Os arquivos foram preservados como no commit `71a6409` do PR.

Essa integração roda no servidor, não dentro do APK. Ela não altera telas nem menus do Android. O pacote 1.23 reúne o código do PR e a correção de compatibilidade; não configura, publica ou reinicia o servidor automaticamente. Consulte o README da integração para variáveis e limitações.

Para testar o servidor, use Python 3.10+ com pytest instalado e execute:

```bash
HERMES_ENV_PATH=/caminho/inexistente/teste.env TELEGRAM_BOT_TOKEN= SHAKERECORDER_TELEGRAM_CHAT_ID= python3 -m pytest -q server/hermes/tests/test_shakerecorder_hermes_proxy.py
```

Os testes substituem os envios. Não precisam de credenciais de produção e não validam a infraestrutura ponta a ponta.

## Debug

- **Crash logs**: `Documents/ShakeRecorder/crash_log.txt`
- **ADB logs**: `adb logcat -s ShakeRecorder:*`

## Tech Stack

- **Linguagem**: Kotlin
- **Min SDK**: 26 (Android 8.0)
- **Target SDK**: 36 (Android 16)
- **Build**: Gradle 8.11.1 / Android Gradle Plugin 8.10.1 com Kotlin DSL

## Licença

Este projeto é de uso pessoal.
