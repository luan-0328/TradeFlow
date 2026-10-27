"""仅回环SMTP验收收件箱：捕获测试邮件到fixture目录，不转发、不访问外部邮箱。"""
from email import policy
from email.parser import BytesParser
import json
import re
import socketserver
import threading


class SmtpFixture:
    def __init__(self, destination):
        self.destination = destination
        self.messages = {}
        self.lock = threading.Lock()
        owner = self

        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                self.connection.settimeout(15)
                self.wfile.write(b'220 localhost fixture SMTP\r\n')
                recipients = []
                while True:
                    line = self.rfile.readline(8192)
                    if not line:
                        return
                    command = line.decode('ascii', errors='replace').strip()
                    verb = command.split(' ', 1)[0].upper()
                    if verb in ('EHLO', 'HELO'):
                        self.wfile.write(b'250 localhost\r\n')
                    elif verb == 'MAIL':
                        recipients = []
                        self.wfile.write(b'250 OK\r\n')
                    elif verb == 'RCPT':
                        match = re.search(r'<([^>]+)>', command)
                        if match and match.group(1).endswith('@example.test'):
                            recipients.append(match.group(1).lower())
                            self.wfile.write(b'250 OK\r\n')
                        else:
                            self.wfile.write(b'550 fixture recipients only\r\n')
                    elif verb == 'DATA' and recipients:
                        self.wfile.write(b'354 end with dot\r\n')
                        chunks, size = [], 0
                        while True:
                            part = self.rfile.readline(8192)
                            if not part:
                                return
                            if part == b'.\r\n':
                                break
                            size += len(part)
                            if size > 65536:
                                return
                            chunks.append(part[1:] if part.startswith(b'..') else part)
                        message = BytesParser(policy=policy.default).parsebytes(b''.join(chunks))
                        body = message.get_body(preferencelist=('plain',))
                        text = body.get_content() if body else message.get_content()
                        code = re.search(r'(?<![0-9])[0-9]{6}(?![0-9])', text)
                        if code:
                            with owner.lock:
                                for recipient in recipients:
                                    owner.messages[recipient] = code.group()
                                temporary = owner.destination.with_suffix('.tmp')
                                temporary.write_text(json.dumps(owner.messages), encoding='utf-8')
                                temporary.replace(owner.destination)
                        self.wfile.write(b'250 accepted\r\n')
                    elif verb == 'QUIT':
                        self.wfile.write(b'221 bye\r\n')
                        return
                    elif verb in ('RSET', 'NOOP'):
                        self.wfile.write(b'250 OK\r\n')
                    else:
                        self.wfile.write(b'502 unsupported\r\n')

        class Server(socketserver.ThreadingTCPServer):
            daemon_threads = True
        self.server = Server(('127.0.0.1', 0), Handler)
        self.port = self.server.server_address[1]
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
