# Compartilhamento de gravações: validação e transição de assinatura

## Estado da entrega

Este registro acompanha o APK 1.25 assinado e validado para a pré-release de
migração. Ele não comprova instalação nem migração física. A Release `v1.24` deve continuar intacta e como Latest.
O código adiciona o menu **Ouvir / Compartilhar** à gravação
selecionada e usa o seletor padrão do Android, sem escolher destinatário ou enviar
uma mensagem automaticamente.

A chave privada que assinou a 1.24 foi perdida. O certificado do APK 1.24 foi
verificado e está preservado como referência histórica em `RELEASING.md`.
Não é possível recuperar a chave privada a partir desse APK. Após ser informado
da incompatibilidade, o proprietário autorizou uma chave aleatória nova, gerada
localmente. Seu certificado público está fixado no verificador de release.

O APK da nova linha **não atualiza a 1.24, nem por instalação manual**. A mudança
do pin não altera essa restrição do Android e o atualizador continua exigindo a
mesma assinatura da instalação atual. Não desinstalar a 1.24 e não limpar seus
dados. A preservação/migração de gravações e configurações continua pendente,
sem exportação/importação de configurações implementada.

O proprietário pediu publicar a primeira pré-release 1.25 com a custódia durável
da chave privada ainda pendente. Somente a senha tem guarda e recuperação
verificadas no Space do proprietário; a chave continua apenas local, sem backup
durável verificado. Essa exceção não confirma backup, não autoriza
transmitir a chave a destinos não aprovados e não garante futuras atualizações.
Uma senha guardada isoladamente não recupera uma chave perdida. As próximas
releases exigem a mesma chave e recuperação do backup aprovada e verificada.

`release.sh --migration-prerelease` é obrigatório para a 1.25. O script inclui
aviso de incompatibilidade, publica como pré-release e define `--latest=false`;
sem o modo explícito, bloqueia a publicação da 1.25. A compilação incrementou
a versão pelo `build.sh` para versionName 1.25 e versionCode 25.

## Segurança e comportamento

- URI `content://` do `FileProvider` existente, uma gravação por vez, somente leitura.
- Sem permissão de escrita, persistência de grant ou acesso à pasta inteira.
- Sem ampliação das permissões do manifesto ou dos caminhos do provider.
- Validação do caminho canônico, extensão, existência, leitura e tamanho do arquivo.
- Rejeição de caminhos externos, prefixos semelhantes, subpastas, travessia,
  links simbólicos para fora, cache de atualização e arquivo ainda em gravação.
- É possível compartilhar um áudio anterior sem parar a gravação atual.
- Cancelar, Voltar, cliques repetidos e recriação da Activity não reenviam o áudio
  nem alteram configurações ou gravações. Abrir o seletor não é sucesso de envio.
- O player externo continua disponível pela opção **Ouvir**.

## Validação automatizada do compartilhamento

Executado em 6 de novembro de 2026, com JDK 21, Gradle 8.11.1, AGP 8.10.1 e SDK 36:

- `./build.sh --check-only`: concluído com sucesso.
- 200 testes Android passaram, sem falhas, erros ou casos ignorados. Os 68 casos
  novos cobrem compartilhamento nos SDKs 26, 35 e 36, incluindo os dois renders
  do Android 16. As regressões existentes também incluem SDK 28.
- 14 testes Python dos scripts de build/metadata passaram.
- `lintDebug`: 62 avisos, zero erros. `lintRelease`: 60 avisos, zero erros.
- 10 testes do proxy Hermes passaram, sem chamadas reais ao Telegram.
- Sintaxe Bash e `git diff --check` passaram.
- Renders do menu claro/escuro inspecionados: nome e opções legíveis, sem cortes
  ou sobreposição; contraste dos textos verificado pelos testes.
- Na execução de validação de 6 de novembro, nenhum APK ou chave foi gerado e a
  versão não foi alterada. A geração posterior da chave é um evento separado.

Os testes usam áudio sintético, gravação simulada e destinos automáticos
(webhook/Telegram) desativados. Os renders são produzidos pelo canvas nativo do
Robolectric, não por um emulador ou aparelho físico. O fixture alinha os diretórios
externos do Robolectric com a relação existente no Android e isola o cache do
FileProvider. O MIME do Intent é verificado explicitamente; a consulta ao provider
é comparada com a tabela MIME da plataforma simulada.

## Verificações da transição

O certificado legado e o novo têm SHA-256 distintos. Os testes de metadata
exigem o novo certificado, rejeitam explicitamente o legado e rejeitam a presença
conjunta dos dois signatários. Isso valida o pin do build, não a compatibilidade
de instalação. Os testes de publicação usam stubs locais de GitHub, Git e build;
não assinam APKs, não usam credenciais e não publicam releases.

Em 7 de novembro de 2026, os 29 testes Python dos scripts de build, metadata e
publicação passaram. A sintaxe Bash de `build.sh`, `release.sh` e `gradlew` e
`git diff --check` também passaram. Essa rodada não executou Gradle nem alterou a
versão. Os 14 testes citados na rodada de 6 de novembro são o conjunto anterior.

## Build assinado da 1.25

Em 7 de novembro de 2026, `./build.sh` concluiu o build release assinado e a
validação do APK/metadata. A execução passou 200 testes Android e 29 testes
Python. Lint debug/release: zero erros, 63/64 avisos respectivamente. Os 10 testes
Hermes foram executados novamente e passaram, sem comunicação real.

- Pacote: `com.shakerecorder`, versionName `1.25`, versionCode `25`.
- Min SDK 26, target SDK 36, sem flag de depuração no APK release.
- Assinatura APK v2 verificada com o novo certificado fixado em `RELEASING.md`.
- APK: 5.852.990 bytes.
- SHA-256 do APK: `cc4212947e8a135e29863ae5984115ff687407856c82cfce13b0a58e3761fa50`.
- `update.json` confere com versão, tamanho e hash do mesmo APK.
- Inspeção dos arquivos do APK não encontrou keystore, senha ou arquivo de
  recuperação da senha. Esses segredos não fazem parte do código nem dos assets.

Esta validação não substitui conferir o commit/tag e ambos os assets publicados.
A publicação deve preservar a 1.24 como Latest e marcar a 1.25 como pré-release.

## Ainda não testado neste registro

- Instalação limpa em aparelho/emulador e publicação real da pré-release como
  parte de um teste automatizado de ponta a ponta.
- Migração dos dados da 1.24. Uma atualização direta com a nova chave é
  incompatível por definição e não deve ser apresentada como um teste pendente
  que possa resolver a diferença de assinatura.
- Recuperação da chave privada a partir de uma cópia durável autorizada.
- Recepção real do áudio por WhatsApp/Telegram e enforcement do grant entre UIDs.
- Microfone, sensores, bloqueio de tela e bateria em um Galaxy S23 Ultra físico.

Após existir um APK da nova linha, testar sua instalação limpa em outro aparelho
ou emulador sem remover a instalação existente. Testar Ouvir, Compartilhar,
Cancelar/Voltar e o arquivo recebido nos aplicativos escolhidos. Em releases
posteriores com a mesma chave nova, testar atualização por cima da versão
anterior dessa linha e conferir configurações e áudios. Nenhum desses testes
substitui o plano separado de preservação dos dados da instalação 1.24.
