# Compartilhamento de gravações: validação e bloqueio de release

## Estado da entrega

Alteração ainda não publicada como aplicativo instalável. A Release `v1.24`
continua intacta. O código adiciona o menu **Ouvir / Compartilhar** à gravação
selecionada e usa o seletor padrão do Android, sem escolher destinatário ou enviar
uma mensagem automaticamente.

A chave privada que assinou a 1.24 não foi localizada no ambiente disponível.
O certificado do APK 1.24 foi novamente verificado e corresponde ao pin de
`RELEASING.md`. Não é possível recuperar a chave privada a partir desse APK.
Nenhuma chave substituta foi criada, nenhum segredo foi publicado e nenhum
armazenamento de credenciais foi configurado.

Sem a chave original e sua custódia recuperável autorizada, não gerar nem publicar
uma atualização incompatível. Não desinstalar a 1.24 e não limpar seus dados.
A próxima release deve incrementar a versão pelo fluxo de `RELEASING.md` depois
de recuperar e verificar a mesma chave. A versão do código permanece 1.24 apenas
porque esta branch ainda não produziu um APK de release.

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

## Validação automatizada

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
- Nenhum APK ou chave foi gerado e a versão não foi alterada.

Os testes usam áudio sintético, gravação simulada e destinos automáticos
(webhook/Telegram) desativados. Os renders são produzidos pelo canvas nativo do
Robolectric, não por um emulador ou aparelho físico. O fixture alinha os diretórios
externos do Robolectric com a relação existente no Android e isola o cache do
FileProvider. O MIME do Intent é verificado explicitamente; a consulta ao provider
é comparada com a tabela MIME da plataforma simulada.

## Ainda não testado

- APK novo assinado e atualização real da 1.24: bloqueados pela chave ausente.
- Recepção real do áudio por WhatsApp/Telegram e enforcement do grant entre UIDs.
- Microfone, sensores, bloqueio de tela e bateria em um Galaxy S23 Ultra físico.

Após existir um APK compatível, atualizar sem desinstalar, conferir as configurações
anteriores e os áudios existentes, testar Ouvir, Compartilhar, Cancelar/Voltar e
verificar o arquivo recebido nos aplicativos escolhidos.
