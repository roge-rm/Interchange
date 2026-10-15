"""The web admin's end of the dedicated server's admin channel.

One tab-separated line goes in and one line of JSON comes out. The commands
are listed in dedicated/src/main/kotlin/com/rm/interchange/dedicated/ControlServer.kt.
Each call opens its own connection.
"""

import json
import os
import socket
import threading

DEFAULT_SOCKET = os.environ.get("INTERCHANGE_CONTROL_SOCKET", "/run/interchange/control.sock")
DEFAULT_TIMEOUT = float(os.environ.get("CONTROL_TIMEOUT", "70"))


class ServerDown(Exception):
    """The server isn't listening, as while it starts or restarts."""


class ControlError(Exception):
    """The server answered no."""


def _escape(value):
    """Tabs separate arguments, so a tab in one is sent as \\t."""
    return str(value).replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")


class Control:
    def __init__(self, path=DEFAULT_SOCKET, timeout=DEFAULT_TIMEOUT):
        self.path = path
        self.timeout = timeout
        # One request at a time, as the server serves them one at a time.
        self._lock = threading.Lock()

    def call(self, *args):
        line = "\t".join(_escape(a) for a in args) + "\n"
        with self._lock:
            try:
                connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                connection.settimeout(self.timeout)
                connection.connect(self.path)
            except FileNotFoundError as error:
                # The socket is only there while the server is up.
                raise ServerDown("It isn't running, or is still starting.") from error
            except (ConnectionRefusedError, PermissionError) as error:
                raise ServerDown(f"Its control socket is there but can't be used: {error}") from error
            except OSError as error:
                raise ServerDown(str(error)) from error

            with connection:
                try:
                    connection.sendall(line.encode("utf-8"))
                    chunks = []
                    while True:
                        chunk = connection.recv(65536)
                        if not chunk:
                            break
                        chunks.append(chunk)
                        if b"\n" in chunk:
                            break
                except OSError as error:
                    raise ServerDown(f"No answer: {error}") from error

        raw = b"".join(chunks).decode("utf-8", "replace").strip()
        if not raw:
            raise ServerDown("The server closed the connection without answering.")
        try:
            reply = json.loads(raw)
        except json.JSONDecodeError as error:
            raise ControlError(f"Couldn't read the answer: {raw[:200]}") from error
        if not reply.get("ok"):
            raise ControlError(reply.get("error", "refused"))
        return reply

    def status(self):
        return self.call("status")

    def players(self):
        return self.call("players").get("players", [])

    def companies(self):
        return self.call("companies").get("companies", [])

    def log(self, count=150):
        return self.call("log", count).get("lines", [])

    def pause(self, paused):
        return self.call("pause" if paused else "resume")

    def speed(self, speed):
        return self.call("speed", speed)

    def chat(self, text):
        return self.call("chat", text)

    def kick(self, name):
        return self.call("kick", name)

    def unlock(self, company):
        return self.call("unlock", company)

    def save(self):
        return self.call("save")

    def new_game(self, settings):
        return self.call("newgame", *(f"{k}={v}" for k, v in settings.items()))
