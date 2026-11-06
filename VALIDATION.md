# ShakeRecorder 1.24: atualização no aplicativo

## Base preservada

A versão 1.23 foi preservada: layout/menus em Android 15/16, gatilhos de volume, configurações aplicadas sem reiniciar, gravação, webhook e integração Hermes do [PR #1](https://github.com/franc-nt/ShakeRecorder/pull/1), commit `71a6409064a9ab52bf4bfbebe8914624f4c9e7c5`. Os três arquivos do PR permanecem idênticos. A main original é `260b9530855d5c84efe0803786493ef74af39f77`. Não houve aprovação nem merge do PR.

## Mudanças

- Opções → Atualizar aplicativo consulta a última Release estável, verifica versão e baixa somente os arquivos esperados do repositório `franc-nt/ShakeRecorder`.
- Download HTTPS limitado, verificação de tamanho/SHA-256, pacote, versão crescente e certificado idêntico ao instalado. Arquivos incompletos ou incompatíveis não chegam ao instalador. Nenhum token GitHub foi incluído.
- Instalação pelo instalador do Android, com confirmação do usuário e permissão por aplicativo quando exigida. Não é instalação silenciosa. Nenhuma proteção do Samsung foi desativada.
- Instalação bloqueada durante gravação. Monitoramento pausado durante o instalador, retomado ao voltar se ainda habilitado. Parar explicitamente continua respeitado.
- Cancelar, voltar, negar permissão ou recriar a tela não desinstala, limpa dados ou inicia outra gravação.
- `CLAUDE.md` migrado para `AGENTS.md`, preservando orientações pertinentes. Não havia `cloud.md`. O fluxo exige nova versão e Release para cada alteração entregue; bloqueios de publicação devem ser informados.
- Build release com a chave existente da 1.23. Scripts verificam assinatura e geram APK e `update.json` juntos. Nenhuma chave nova foi criada ou enviada a terceiros.
- Os cinco PNGs antigos de ícone tinham chunks/CRC inválidos e impediam a compilação release. Foram substituídos por ícone adaptativo que reutiliza o vetor e cor existentes.

## Validação executada

- JDK 21.0.13, Gradle 8.11.1, AGP 8.10.1, compile/target SDK 36, min SDK 26.
- `./build.sh --no-version-bump` concluiu a versão 1.24/code 24: testes, lint debug/release e APK release assinado.
- **132 testes Android passaram**, sem falhas/erros/ignorados. Incluem os 70 testes anteriores e novos testes do atualizador, fluxo de instalação e preservação de dados.
- Validação de metadados/assinaturas exercitada nas APIs 26, 28, 35 e 36. Fluxos de Activity/serviço nas APIs 35 e 36: cliques repetidos, voltar, recriação, cancelamento, permissão negada, gravação ativa, parada explícita, cache corrompido, APK truncado, hash incorreto e redirecionamento não autorizado.
- Asserções de preservação usam preferências e arquivos sintéticos, sem ler ou enviar áudio do usuário.
- **8 testes Python** do gerador/validador de metadados de release passaram.
- **10 testes Hermes** passaram, com env inexistente e destinos desativados/mockados. Nenhum servidor de produção foi alterado.
- Lint: **0 erros**, 60 avisos debug e 65 release, não bloqueantes. Há dívida técnica preexistente e avisos adicionais de textos e compatibilidade de APIs; não se afirma lint sem avisos.
- Telas do atualizador renderizadas com Canvas nativo do Robolectric em temas claro/escuro. Não são capturas de um Samsung físico.
- Assinatura/pacote/versão verificados com `apksigner`/`aapt`; `git diff --check` sem problemas.

Robolectric simula o framework e o gravador. Não houve teste em emulador completo, no Galaxy S23 Ultra, de áudio real, teclas reais, Doze ou substituição de pacote pelo instalador Android. A atualização ponta a ponta pelo GitHub também depende da publicação da Release, ainda bloqueada. As verificações automatizadas não substituem esse teste físico.

## Dados e assinatura

O pacote `com.shakerecorder`, SharedPreferences e diretório Music/ShakeRecorder foram mantidos. A 1.24 tem o mesmo certificado da 1.23 entregue, permitindo atualização por cima dela sem desinstalar. Certificado SHA-256:

`017edcae8126e6d6904e4fdb651b889394e12d555f7576952a3c730e9eb07b59`

A assinatura é a chave de depuração legada da 1.23, agora reutilizada no build release. Isso preserva continuidade; não equivale a já ter configurado custódia persistente. A chave ainda precisa de backup seguro autorizado. A chave das candidatas 1.21/1.22 era diferente, e a assinatura original 1.20 é desconhecida.

**Não desinstalar nem limpar dados.** O aplicativo não tem exportação/importação de configurações. `allowBackup` não garante recuperação. Gravações em armazenamento compartilhado podem permanecer após desinstalação, mas acesso/propriedade pode mudar. Copiar arquivos acessíveis para um local seguro antes de qualquer operação destrutiva; uma reinstalação não foi recomendada nem executada.

## Publicação e próximos testes

A consulta pública confirmou o repositório público e nenhuma Release publicada. A visibilidade não foi alterada. A tentativa anterior de escrita recebeu HTTP 403 `Resource not accessible by integration`. Nesta etapa só foram feitas verificações de leitura: a instalação acessível pertence à organização Techify-one, sem instalação mostrada para franc-nt. A operação recusada não foi repetida por outra rota. Não há commit, tag ou Release desta versão no remoto.

É necessário habilitar o acesso GitHub adequado e obter aprovação específica para persistir a chave antes de configurar essa custódia. Não salvar chaves/senhas em commits, APK, notas públicas ou anexos comuns. Após acesso resolvido, publicar os dois assets do mesmo build e verificar o download da Release.

Teste no celular: instalar 1.24 sobre a 1.23 sem desinstalar, conferir versão/configurações/áudios, depois publicar uma versão maior e exercitar verificar → baixar → confirmar instalação. Cancelamento, permissão negada, gravação ativa e monitoramento após atualização também precisam ser conferidos no aparelho.

## Limites preservados

Volume em segundo plano/tela bloqueada usa MediaSession: outro player, Bluetooth, bateria e firmware podem redirecionar botões. VolumeProvider fornece pulsos, não eventos DOWN/UP completos. Forçar parada impede monitoramento até reativação. O usuário confirmou funcionamento da 1.23 em seu aparelho, mas não há medição automatizada de comportamento Samsung ou bateria.

O proxy Hermes conserva uma limitação preexistente: JSON comprimido recebido do upstream pode perder Content-Encoding sem descompressão dos bytes. Os testes existentes não cobrem esse transporte HTTP; validar antes de implantar com compressão. O código do PR foi preservado sem alterações.
