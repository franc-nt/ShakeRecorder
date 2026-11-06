# Integração ShakeRecorder → Hermes → Telegram

Código do proxy de servidor recuperado da instalação existente. Não altera o aplicativo Android nem exige gerar APK. Runtime: Python 3.10+ (somente biblioteca padrão); testes: pytest.

## Comportamento preservado

- Encaminha as requisições e o Authorization original ao Hermes; devolve status e corpo do upstream ao cliente.
- Após POST `/v1/responses` bem-sucedido, envia a resposta ao Telegram quando a primeira ou última frase contém um pedido reconhecido.
- Sem pedido reconhecido, envia apenas a transcrição completa como documento `.txt`, sem truncamento em 1.200 caracteres.
- Reconhece pedidos coloquiais de resumo, organização, envio, agendamento, busca, execução de skills, consulta, criação e publicação.
- Palavras de ação apenas nas frases intermediárias não ativam a notificação de resposta.

**Limitação importante:** a classificação controla a entrega ao Telegram, não a execução do Hermes. Toda requisição é encaminhada ao upstream antes da classificação. As regexes são heurísticas, não compreensão irrestrita de qualquer pedido, nem barreira de autorização. O proxy não adiciona autenticação própria; mantenha-o em loopback e use a autenticação do Hermes. Não suporta streaming SSE; utiliza resposta JSON completa. A entrega Telegram é assíncrona e sem fila persistente/retry, podendo ser perdida se o processo parar.

## Configuração

Variáveis:

- `SHAKERECORDER_PROXY_HOST`: `127.0.0.1` por padrão.
- `SHAKERECORDER_PROXY_PORT`: `8650` por padrão.
- `HERMES_API_UPSTREAM`: `http://127.0.0.1:8642` por padrão.
- `SHAKERECORDER_TELEGRAM_CHAT_ID`: obrigatório para entrega Telegram; sem destinatário padrão.
- `TELEGRAM_BOT_TOKEN`: token do bot, obtido do ambiente ou do arquivo indicado abaixo.
- `HERMES_ENV_PATH`: arquivo dotenv; padrão `~/.hermes/.env`. Use arquivo dedicado e permissões restritas.

Não publique arquivos `.env`, tokens, chaves, transcrições reais ou configurações pessoais.

```bash
export SHAKERECORDER_TELEGRAM_CHAT_ID='<chat-id>'
export HERMES_ENV_PATH="$HOME/.config/shakerecorder/proxy.env"
python3 server/hermes/shakerecorder_hermes_proxy.py
```

Configure o reverse proxy para encaminhar `/v1/responses` e `/v1/responses/*` para `127.0.0.1:8650`; outras rotas da API podem ir diretamente ao Hermes. O cliente deve enviar o Bearer token do Hermes, sem Basic Auth adicional nas rotas da API. Use resposta não streaming.

```json
{"input":"Sua transcrição ou pedido", "store":true, "conversation":"shakerecorder"}
```

## Testes

Na raiz do repositório, em ambiente com pytest:

```bash
python3 -m pytest -q server/hermes/tests/test_shakerecorder_hermes_proxy.py
```

Os testes substituem funções de envio; não fazem chamadas reais ao Telegram. Cobrem classificação e entrega, extração longa e pedidos coloquiais. Não constituem validação ponta a ponta da infraestrutura.

## Histórico recuperado

Origem: histórico de conversas operacionais, não commits Git anteriores. As datas abaixo registram quando as alterações foram realizadas; os commits deste PR são novos, sem datas retroativas.

- **13 de maio de 2026:** transcrições sem ação passaram a ser enviadas como `.txt`; preservação integral do texto em vez do preview de 1.200 caracteres. Registro histórico: 5 testes passando e reinício do serviço confirmado.
- **17 de maio de 2026:** corrigido reconhecimento de “faz um resumo”, “me manda” e “organiza essas ideias”. Registro histórico: 7 testes passando.
- **17 de maio de 2026:** ampliado reconhecimento para agendamento/cron, busca de artigos, execução de skills/ferramentas, consultas e ações gerais, mantendo seleção da primeira/última frase. Registro histórico: testes novos primeiro falharam (3 falhas, 7 sucessos), depois passaram os 10; serviço reiniciado e casos manuais positivos/negativos verificados.

O proxy e os testes estavam fora de um repositório Git. Este PR os adiciona em `server/hermes/`, tornando o caminho dos testes relativo e removendo o destinatário Telegram e caminho de home pessoais dos padrões. O serviço em produção não é modificado por este PR.
