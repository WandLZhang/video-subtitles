#!/usr/bin/env python3
"""Range-capable static server so the browser can SEEK the video.
Python's stdlib http.server ignores Range headers -> no seeking. This adds
HTTP 206 partial-content support. No regex; Range parsed via str.partition."""
import http.server, os, sys

class RangeHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        path = self.translate_path(self.path)
        if not os.path.isfile(path):
            return super().do_GET()
        size = os.path.getsize(path)
        ctype = self.guess_type(path)
        rng = self.headers.get('Range')
        if rng and rng.startswith('bytes='):
            spec = rng[len('bytes='):]
            s, _, e = spec.partition('-')
            start = int(s) if s else 0
            end = int(e) if e else size - 1
            end = min(end, size - 1)
            if start > end or start >= size:
                self.send_response(416)
                self.send_header('Content-Range', f'bytes */{size}')
                self.end_headers(); return
            length = end - start + 1
            self.send_response(206)
            self.send_header('Content-Type', ctype)
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
            self.send_header('Content-Length', str(length))
            self.end_headers()
            with open(path, 'rb') as f:
                f.seek(start); remaining = length
                while remaining > 0:
                    chunk = f.read(min(65536, remaining))
                    if not chunk: break
                    self.wfile.write(chunk); remaining -= len(chunk)
        else:
            self.send_response(200)
            self.send_header('Content-Type', ctype)
            self.send_header('Accept-Ranges', 'bytes')
            self.send_header('Content-Length', str(size))
            self.end_headers()
            with open(path, 'rb') as f:
                self.copyfile(f, self.wfile)

    def log_message(self, *a): pass

if __name__ == '__main__':
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    http.server.ThreadingHTTPServer(('0.0.0.0', port), RangeHandler).serve_forever()
