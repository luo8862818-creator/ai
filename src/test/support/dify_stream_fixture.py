"""Local-only, manually advanced SSE fixture for browser checks (no real AI calls)."""
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

condition = threading.Condition()
stage = 0


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(json.dumps({'suggested_questions': []}).encode())

    def do_POST(self):
        global stage
        body = self.rfile.read(int(self.headers.get('Content-Length', 0)))
        if self.path in ('/advance', '/reset'):
            with condition:
                stage = 0 if self.path == '/reset' else stage + 1
                condition.notify_all()
            self.send_response(200)
            self.end_headers()
            self.wfile.write(str(stage).encode())
            return
        query = json.loads(body).get('query', '')
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream; charset=utf-8')
        self.end_headers()

        def emit(event, **fields):
            payload = json.dumps({'event': event, 'conversation_id': 'fixture-conversation', **fields}, ensure_ascii=False)
            self.wfile.write(('data: ' + payload + '\n\n').encode())
            self.wfile.flush()

        try:
            emit('agent_message', answer='第一段：这是浏览器功能验证。\n')
            with condition:
                if not condition.wait_for(lambda: stage >= 1, timeout=120):
                    return
            emit('agent_message', answer='第二段：**[进入设置](http://192.168.2.52:18181/system/config)**\n')
            with condition:
                if not condition.wait_for(lambda: stage >= 2, timeout=120):
                    return
            if '异常' in query:
                emit('error', message='Fixture error')
            else:
                emit('agent_message', answer='第三段：回答完成。')
                emit('message_end')
        except (BrokenPipeError, ConnectionResetError):
            pass


if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 18081), Handler).serve_forever()
