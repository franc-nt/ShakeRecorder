#!/usr/bin/env python3
"""Proxy /v1/responses to Hermes API server and fan-out completed replies to Telegram.

Why this exists: Hermes api_server is HTTP-request/response oriented. It returns
JSON to the caller but does not route outputs to Telegram. This small proxy keeps
/v1/responses compatible with OpenAI Responses API while notifying the user's
Telegram home chat.
"""
from __future__ import annotations

import json
import os
import re
import sys
import threading
import time
import unicodedata
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

LISTEN_HOST = os.environ.get("SHAKERECORDER_PROXY_HOST", "127.0.0.1")
LISTEN_PORT = int(os.environ.get("SHAKERECORDER_PROXY_PORT", "8650"))
UPSTREAM = os.environ.get("HERMES_API_UPSTREAM", "http://127.0.0.1:8642")
TELEGRAM_CHAT_ID = os.environ.get("SHAKERECORDER_TELEGRAM_CHAT_ID", "")
ENV_PATH = Path(os.environ.get("HERMES_ENV_PATH", str(Path.home() / ".hermes" / ".env")))


def _load_dotenv(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw in path.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip().strip('"').strip("'")
        values[key.strip()] = value
    return values


DOTENV = _load_dotenv(ENV_PATH)
TELEGRAM_BOT_TOKEN = os.environ.get("TELEGRAM_BOT_TOKEN") or DOTENV.get("TELEGRAM_BOT_TOKEN", "")


def _extract_text_from_response(data: dict[str, Any]) -> str:
    chunks: list[str] = []
    for item in data.get("output") or []:
        if not isinstance(item, dict):
            continue
        if item.get("type") != "message":
            continue
        for part in item.get("content") or []:
            if isinstance(part, dict) and part.get("type") in {"output_text", "text"}:
                text = str(part.get("text") or "").strip()
                if text:
                    chunks.append(text)
    if chunks:
        return "\n".join(chunks).strip()
    # Fallbacks for non-standard response shapes.
    if isinstance(data.get("output_text"), str):
        return data["output_text"].strip()
    return ""


def _extract_input_text(body: bytes) -> str:
    try:
        payload = json.loads(body.decode("utf-8"))
    except Exception:
        return ""
    raw = payload.get("input")
    if isinstance(raw, str):
        return raw
    if isinstance(raw, list):
        parts: list[str] = []
        for item in raw:
            if isinstance(item, str):
                parts.append(item)
            elif isinstance(item, dict):
                content = item.get("content")
                if isinstance(content, str):
                    parts.append(content)
                elif isinstance(content, list):
                    for part in content:
                        if isinstance(part, str):
                            parts.append(part)
                        elif isinstance(part, dict) and isinstance(part.get("text"), str):
                            parts.append(part["text"])
        return "\n".join(parts)
    return ""


def _extract_input_preview(body: bytes) -> str:
    text = _extract_input_text(body)
    if len(text) > 1200:
        text = text[:1200].rstrip() + "…"
    return text


def _normalize_for_action_detection(text: str) -> str:
    lowered = text.casefold()
    without_accents = "".join(
        char for char in unicodedata.normalize("NFD", lowered)
        if unicodedata.category(char) != "Mn"
    )
    return re.sub(r"\s+", " ", without_accents).strip()


def _edge_sentences(text: str) -> list[str]:
    sentences = [part.strip() for part in re.split(r"(?<=[.!?;:\n])\s+", text) if part.strip()]
    if not sentences:
        return []
    if len(sentences) == 1:
        return sentences
    return [sentences[0], sentences[-1]]


def _has_action_request(transcription: str) -> bool:
    """Return True only when the transcription starts or ends with a direct action request."""
    if not transcription.strip():
        return False

    edge_text = " | ".join(_normalize_for_action_detection(sentence) for sentence in _edge_sentences(transcription))
    if not edge_text:
        return False

    action_patterns = [
        # Creation / task management.
        r"\b(crie|cria|criar)\s+(uma\s+)?(tarefa|task|nota|anotacao|lembrete|cron)\b",
        r"\b(abra|abrir)\s+(uma\s+)?(tarefa|task|chamado)\b",
        r"\b(anote|anotar|crie\s+uma\s+anotacao|criar\s+uma\s+anotacao)\b",
        # Scheduling / reminders / future Telegram sends.
        r"\b(agenda|agende|agendar|programe|programa|programar|marque|marcar)\b",
        r"\b(lembre|me\s+lembre|lembrar|me\s+avise|avisa|avise|avisar)\b",
        r"\b(crie|criar|faca|fazer)\s+(um\s+)?cron\b",
        # Summarization / organization.
        r"\b(resuma|resumir|resume|faz\s+um\s+resumo|faca\s+um\s+resumo|fazer\s+um\s+resumo)\b",
        r"\b(organiza|organize|organizar)\s+(essas\s+|estas\s+|as\s+)?(ideias|anotacoes|notas|transcricao|texto|informacoes)\b",
        # Search / research / article fetching.
        r"\b(busca|busque|buscar|pesquisa|pesquise|pesquisar|procura|procure|procurar|acha|ache|achar)\b",
        r"\b(pega|pegue|pegar|traz|traga|trazer)\s+(um\s+|uma\s+|o\s+|a\s+)?(artigo|link|resumo|referencia|fonte)\b",
        # Generic Hermes execution / tool or skill usage.
        r"\b(executa|execute|executar|roda|rode|rodar|usa|use|usar)\b",
        r"\b(skill|ferramenta|tool|hermes)\b.*\b(executa|execute|executar|roda|rode|rodar|usa|use|usar)\b",
        r"\b(verifica|verifique|verificar|consulta|consulte|consultar|checa|cheque|checar)\b",
        # Messaging / publishing / generation.
        r"\b(envie|enviar|mande|manda|mandar|responda|responder)\b",
        r"\b(publique|publicar|poste|postar|gere|gerar|crie|criar|faz|faca|fazer|organiza|organize|organizar)\b",
        # Polite/indirect requests. Keep this broad but only on first/last sentence.
        r"\b(gostaria|quero|preciso)\s+(que\s+)?(voce\s+)?(crie|criar|faz|faca|fazer|resuma|resumir|organiza|organize|organizar|anote|anotar|envie|enviar|mande|manda|mandar|publique|publicar|gere|gerar|busque|buscar|pesquise|pesquisar|procure|procurar|execute|executar|use|usar|agende|agendar|verifique|verificar)\b",
        r"\b(pode|poderia|por\s+favor)\s+(criar|crie|fazer|faz|faca|resumir|resuma|organizar|organiza|organize|anotar|anote|enviar|envie|mandar|manda|mande|publicar|publique|gerar|gere|buscar|busque|pesquisar|pesquise|procurar|procure|executar|execute|usar|use|agendar|agende|verificar|verifique)\b",
    ]
    return any(re.search(pattern, edge_text) for pattern in action_patterns)


def _send_telegram(text: str) -> None:
    if not TELEGRAM_BOT_TOKEN or not TELEGRAM_CHAT_ID or not text.strip():
        return
    max_len = 3900
    for idx in range(0, len(text), max_len):
        chunk = text[idx: idx + max_len]
        payload = json.dumps({"chat_id": TELEGRAM_CHAT_ID, "text": chunk}).encode("utf-8")
        req = urllib.request.Request(
            f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendMessage",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=20) as resp:
            resp.read()


def _send_telegram_document(filename: str, content: str, caption: str = "") -> None:
    if not TELEGRAM_BOT_TOKEN or not TELEGRAM_CHAT_ID or not content.strip():
        return
    boundary = f"----ShakeRecorderBoundary{int(time.time() * 1000)}"

    def field(name: str, value: str) -> bytes:
        return (
            f"--{boundary}\r\n"
            f"Content-Disposition: form-data; name=\"{name}\"\r\n\r\n"
            f"{value}\r\n"
        ).encode("utf-8")

    file_header = (
        f"--{boundary}\r\n"
        f"Content-Disposition: form-data; name=\"document\"; filename=\"{filename}\"\r\n"
        "Content-Type: text/plain; charset=utf-8\r\n\r\n"
    ).encode("utf-8")
    body = b"".join([
        field("chat_id", TELEGRAM_CHAT_ID),
        field("caption", caption[:1024]),
        file_header,
        content.encode("utf-8"),
        f"\r\n--{boundary}--\r\n".encode("utf-8"),
    ])
    req = urllib.request.Request(
        f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendDocument",
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}", "Content-Length": str(len(body))},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        resp.read()


def _send_passive_transcription_file(transcription: str) -> None:
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    filename = f"shakerecorder-transcricao-{timestamp}.txt"
    caption = "🎙️ ShakeRecorder — Transcrição sem ação específica"
    _send_telegram_document(filename, transcription, caption)


def _notify_async(input_preview: str, response_body: bytes, status: int) -> None:
    if status < 200 or status >= 300:
        return
    if not _has_action_request(input_preview):
        try:
            _send_passive_transcription_file(input_preview)
            print("[proxy] passive transcription sent as document", file=sys.stderr, flush=True)
        except Exception as exc:
            print(f"[proxy] telegram transcription document failed: {exc}", file=sys.stderr, flush=True)
        return
    try:
        data = json.loads(response_body.decode("utf-8"))
        output_text = _extract_text_from_response(data)
        if not output_text:
            output_text = "Recebi sua transcrição pelo ShakeRecorder."
        msg = "🎙️ ShakeRecorder"
        if input_preview:
            msg += f"\n\nTranscrição:\n{input_preview}"
        msg += f"\n\nResposta:\n{output_text}"
        _send_telegram(msg)
    except Exception as exc:
        print(f"[proxy] telegram notify failed: {exc}", file=sys.stderr, flush=True)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"[proxy] {self.address_string()} - {fmt % args}", file=sys.stderr, flush=True)

    def _proxy(self) -> None:
        length = int(self.headers.get("Content-Length", "0") or "0")
        body = self.rfile.read(length) if length else b""
        target = UPSTREAM.rstrip("/") + self.path
        headers = {k: v for k, v in self.headers.items() if k.lower() not in {"host", "content-length", "connection"}}
        req = urllib.request.Request(target, data=body if self.command not in {"GET", "HEAD"} else None, headers=headers, method=self.command)
        try:
            with urllib.request.urlopen(req, timeout=1800) as resp:
                resp_body = resp.read()
                status = resp.status
                resp_headers = resp.headers
        except urllib.error.HTTPError as e:
            resp_body = e.read()
            status = e.code
            resp_headers = e.headers
        except Exception as exc:
            resp_body = json.dumps({"error": str(exc)}).encode("utf-8")
            status = 502
            resp_headers = {"Content-Type": "application/json"}

        self.send_response(status)
        skipped = {"transfer-encoding", "connection", "content-encoding", "content-length"}
        for k, v in resp_headers.items():
            if k.lower() not in skipped:
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(resp_body)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(resp_body)

        if self.command == "POST" and self.path.split("?", 1)[0] == "/v1/responses":
            input_text = _extract_input_text(body)
            threading.Thread(target=_notify_async, args=(input_text, resp_body, status), daemon=True).start()

    def do_GET(self) -> None: self._proxy()
    def do_POST(self) -> None: self._proxy()
    def do_DELETE(self) -> None: self._proxy()
    def do_PATCH(self) -> None: self._proxy()
    def do_OPTIONS(self) -> None: self._proxy()
    def do_HEAD(self) -> None: self._proxy()


def main() -> None:
    server = ThreadingHTTPServer((LISTEN_HOST, LISTEN_PORT), Handler)
    print(f"[proxy] listening on http://{LISTEN_HOST}:{LISTEN_PORT}, upstream={UPSTREAM}, telegram_chat={TELEGRAM_CHAT_ID}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
