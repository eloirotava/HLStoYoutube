# S22HlsToVps

App Android (minSdk 29) que captura da câmera via Camera2 + MediaCodec (HEVC + AAC),
gera segmentos HLS de 2s (`.ts`) e uma playlist rolling `live.m3u8` (tamanho 6),
e faz **HTTP PUT** para uma URL base (ex.: seu servidor VPS) **ou** o endpoint do YouTube
(`https://a.upload.youtube.com/http_upload_hls?cid=...&copy=0&file=`).

> **Obs.:** Implementa um mux TS simples (PAT/PMT + PCR no PID de vídeo). Use para testes/lab.
Para produção, um muxer maduro (FFmpeg) é recomendável.

## Como usar

1. No Codespaces/Ubuntu:
   ```bash
   gradle wrapper --gradle-version 8.7
   ./gradlew clean assembleDebug
   ```
2. Instale: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Permissões: conceda Câmera e Microfone.
4. Na UI:
   - **URL de saída**:
     - VPS: `http://SEU_IP:PORTA/` (o app fará PUT de `seg_00000.ts`, `live.m3u8`, ...)
     - YouTube: `https://a.upload.youtube.com/http_upload_hls?cid=STREAMKEY&copy=0&file=`
   - **Camera ID** (ex.: 0/2, depende do aparelho)
   - **Resolução**: `1080p`, `720p` (padrão) ou `360p`
   - **Bitrate de vídeo**: bps (ex.: 800000)
   - **Áudio kbps**: ex.: 96

## Servidor de teste (VPS)

Python para receber PUT (salva em `/srv/hls/incoming`):
```bash
#!/usr/bin/env python3
from http.server import BaseHTTPRequestHandler, HTTPServer
import os, urllib.parse

BASE='/srv/hls/incoming'
os.makedirs(BASE, exist_ok=True)

class H(BaseHTTPRequestHandler):
    def do_PUT(self):
        path = urllib.parse.urlparse(self.path).path.lstrip('/')
        if not path: path = 'live.m3u8'
        dest = os.path.join(BASE, os.path.basename(path))
        length = int(self.headers.get('Content-Length','0'))
        with open(dest, 'wb') as f:
            n = 0
            while n < length:
                chunk = self.rfile.read(min(1<<20, length-n))
                if not chunk: break
                f.write(chunk); n += len(chunk)
        self.send_response(200); self.end_headers()

HTTPServer(('0.0.0.0', 81), H).serve_forever()
```
