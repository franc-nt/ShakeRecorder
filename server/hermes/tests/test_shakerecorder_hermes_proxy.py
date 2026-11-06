import importlib.util
import json
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / 'shakerecorder_hermes_proxy.py'
spec = importlib.util.spec_from_file_location('shakerecorder_hermes_proxy', MODULE_PATH)
proxy = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(proxy)


def response_body(text='ok'):
    return json.dumps({
        'output': [{
            'type': 'message',
            'content': [{'type': 'output_text', 'text': text}],
        }]
    }).encode('utf-8')


def test_notify_async_sends_passive_transcription_as_document_without_action_request():
    messages = []
    documents = []
    proxy._send_telegram = messages.append
    proxy._send_telegram_document = lambda filename, content, caption='': documents.append((filename, content, caption))

    transcription = 'Passei no mercado e comprei café. Depois encontrei o João e conversamos sobre a reunião de amanhã.'
    proxy._notify_async(transcription, response_body('Passei no mercado e comprei café.'), 200)

    assert messages == []
    assert len(documents) == 1
    filename, content, caption = documents[0]
    assert filename.endswith('.txt')
    assert content == transcription
    assert 'Transcrição' in caption


def test_extract_input_text_keeps_full_long_transcription_for_four_hour_audio():
    long_transcription = 'Trecho da palestra. ' * 2000
    body = json.dumps({'input': long_transcription}).encode('utf-8')

    assert proxy._extract_input_text(body) == long_transcription
    assert proxy._extract_input_preview(body).endswith('…')


def test_notify_async_sends_when_action_request_is_at_start():
    sent = []
    documents = []
    proxy._send_telegram = sent.append
    proxy._send_telegram_document = lambda filename, content, caption='': documents.append((filename, content, caption))

    proxy._notify_async(
        'Crie uma tarefa para falar com o João amanhã sobre a proposta.',
        response_body('Tarefa criada.'),
        200,
    )

    assert len(sent) == 1
    assert documents == []
    assert 'Tarefa criada.' in sent[0]


def test_notify_async_sends_when_action_request_is_at_end():
    sent = []
    documents = []
    proxy._send_telegram = sent.append
    proxy._send_telegram_document = lambda filename, content, caption='': documents.append((filename, content, caption))

    proxy._notify_async(
        'Conversei com o João sobre a proposta e ele pediu mais detalhes. No fim, crie uma tarefa para responder amanhã.',
        response_body('Tarefa criada.'),
        200,
    )

    assert len(sent) == 1
    assert documents == []
    assert 'Tarefa criada.' in sent[0]


def test_notify_async_sends_when_summary_request_uses_colloquial_faz_and_manda_at_end():
    sent = []
    documents = []
    proxy._send_telegram = sent.append
    proxy._send_telegram_document = lambda filename, content, caption='': documents.append((filename, content, caption))

    transcription = (
        'Testar o funil com venda direta do Whatsbot. Vídeo de um ou dois minutos. '
        'Faz um resumo aí do que eu falei, me manda o resumido no Telegram mesmo.'
    )
    proxy._notify_async(transcription, response_body('Resumo organizado.'), 200)

    assert len(sent) == 1
    assert documents == []
    assert 'Resumo organizado.' in sent[0]


def test_notify_async_sends_when_organize_ideas_request_is_at_start():
    sent = []
    documents = []
    proxy._send_telegram = sent.append
    proxy._send_telegram_document = lambda filename, content, caption='': documents.append((filename, content, caption))

    proxy._notify_async('Organiza essas ideias sobre o funil.', response_body('Ideias organizadas.'), 200)

    assert len(sent) == 1
    assert documents == []
    assert 'Ideias organizadas.' in sent[0]


def test_notify_async_sends_when_scheduling_request_is_at_end():
    sent = []
    documents = []
    proxy._send_telegram = sent.append
    proxy._send_telegram_document = lambda filename, content, caption='': documents.append((filename, content, caption))

    transcription = 'Preciso falar com o Pedro sobre o contrato. Agenda uma mensagem para mim no Telegram daqui duas horas.'
    proxy._notify_async(transcription, response_body('Agendamento criado.'), 200)

    assert len(sent) == 1
    assert documents == []
    assert 'Agendamento criado.' in sent[0]


def test_notify_async_sends_when_search_or_article_request_is_at_start_or_end():
    sent = []
    documents = []
    proxy._send_telegram = sent.append
    proxy._send_telegram_document = lambda filename, content, caption='': documents.append((filename, content, caption))

    proxy._notify_async('Busca um artigo para mim sobre checkout no WhatsApp.', response_body('Artigo encontrado.'), 200)
    proxy._notify_async('Estou pensando em funil para WhatsApp. No final, pesquisa isso para mim.', response_body('Pesquisa feita.'), 200)

    assert len(sent) == 2
    assert documents == []
    assert 'Artigo encontrado.' in sent[0]
    assert 'Pesquisa feita.' in sent[1]


def test_notify_async_sends_when_skill_or_general_execution_request_is_at_start():
    sent = []
    documents = []
    proxy._send_telegram = sent.append
    proxy._send_telegram_document = lambda filename, content, caption='': documents.append((filename, content, caption))

    proxy._notify_async('Executa o skill do Hermes de artigo da Techify com esse link.', response_body('Skill executado.'), 200)
    proxy._notify_async('Use o skill de criação de tarefa com essas informações.', response_body('Skill usado.'), 200)
    proxy._notify_async('Verifica para mim quantos hacks já ficaram prontos.', response_body('Verificado.'), 200)

    assert len(sent) == 3
    assert documents == []
    assert 'Skill executado.' in sent[0]
    assert 'Skill usado.' in sent[1]
    assert 'Verificado.' in sent[2]


def test_notify_async_ignores_action_words_only_in_middle_by_sending_transcription_file():
    messages = []
    documents = []
    proxy._send_telegram = messages.append
    proxy._send_telegram_document = lambda filename, content, caption='': documents.append((filename, content, caption))

    transcription = 'Anotação pessoal: o João falou que precisa criar uma tarefa no sistema dele depois. Essa conversa terminou sem nenhuma pendência para mim.'
    proxy._notify_async(
        transcription,
        response_body('Anotação pessoal: o João falou que precisa criar uma tarefa.'),
        200,
    )

    assert messages == []
    assert len(documents) == 1
    assert documents[0][1] == transcription
