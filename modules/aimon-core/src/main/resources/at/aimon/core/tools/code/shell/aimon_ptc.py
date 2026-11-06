"""AIMON programmatic tool calling runtime.

Runs one model-written Python program inside a sandbox and turns its tool calls into authenticated requests to the
host (docs/design/tool/programmatic-tool-calling-shell.md §6, §7). Started by the shell engine as

    python3 -I -u "$AIMON_RUN_DIR/aimon_ptc.py"

from a run directory that holds this file, main.py (the program), config.json (callable tools and limits), key (the
HMAC key, a secret file) and reply_dir (where the host writes replies). key and reply_dir are deleted as soon as they
are read.

The program and this runtime are one process, so nothing here is a boundary: every rule below is enforced again by
the host. These rules only keep the normal path right.

Channel: this process's original stdout. Every line is

    <TYPE> <seq> <payload> <hex(HMAC-SHA256(K, "<TYPE> <seq> <payload>"))>

with TYPE one of CALL, OUT, ERR, DONE, one seq shared by all lines, starting at 1. Payloads are ASCII without line
breaks: CALL and DONE carry JSON (ensure_ascii), OUT and ERR base64. A line is shorter than 64 KiB.

Replies: the host writes reply_dir/r/<seq> once, as hex(HMAC-SHA256(K, b"R" + seq + b"\\0" + payload)) + "\\n" +
payload, payload being JSON {"text", "is_error", "retryable"}.
"""

import ast
import asyncio
import base64
import hashlib
import hmac
import inspect
import json
import keyword
import os
import shutil
import signal
import stat
import sys
import tempfile
import threading
import time
import traceback

RUN_DIR = os.path.dirname(os.path.abspath(__file__))
MAX_LINE_BYTES = 65536
OUT_CHUNK_BYTES = 32 * 1024
MAX_ERROR_CHARS = 4000
POLL_DELAYS = (0.005, 0.005, 0.01, 0.02, 0.04, 0.08)
POLL_MAX = 0.1


class ToolError(Exception):
    """A tool call failed. The message is the tool's error text."""

    def __init__(self, message, retryable=True):
        super().__init__(message)
        self.retryable = retryable


class _Channel:
    """Writes authenticated lines to the channel fd, one at a time, with one monotonic seq."""

    def __init__(self, fd, key):
        self._fd = fd
        self._key = key
        self._lock = threading.Lock()
        self._seq = 0
        self._closed = False

    def send(self, kind, payload):
        """Returns the line's seq; 0 when the line would be too long; -1 once the channel is closed."""
        with self._lock:
            if self._closed:
                return -1
            seq = self._seq + 1
            body = "%s %d %s" % (kind, seq, payload)
            mac = hmac.new(self._key, body.encode("ascii"), hashlib.sha256).hexdigest()
            line = (body + " " + mac + "\n").encode("ascii")
            if len(line) >= MAX_LINE_BYTES:
                return 0
            self._seq = seq
            view = memoryview(line)
            while view:
                written = os.write(self._fd, view)
                view = view[written:]
            return seq

    def close(self):
        with self._lock:
            self._closed = True


_state = {}
_finish_lock = threading.Lock()
_finished = threading.Event()


def _read_and_delete(name):
    path = os.path.join(RUN_DIR, name)
    with open(path, "rb") as f:
        data = f.read()
    try:
        os.unlink(path)
    except OSError:
        pass
    return data


def _no_dump():
    """prctl(PR_SET_DUMPABLE, 0): raises the cost of reaching /proc/<pid>/{fd,environ,mem}. Not a boundary."""
    if not sys.platform.startswith("linux"):
        return
    try:
        import ctypes

        ctypes.CDLL(None, use_errno=True).prctl(4, 0, 0, 0, 0)
    except Exception:
        pass


def _read_head(path, limit):
    try:
        with open(path, "rb") as f:
            return f.read(limit)
    except OSError:
        return b""


def _read_tail(path, chars):
    try:
        with open(path, "rb") as f:
            f.seek(0, os.SEEK_END)
            size = f.tell()
            f.seek(max(0, size - chars * 4))
            text = f.read().decode("utf-8", "replace")
    except OSError:
        return ""
    return text[-chars:]


def _finish(status, error=None):
    """Sends the captured output, the log tail and DONE, once. Returns whether this call was the one."""
    with _finish_lock:
        if _finished.is_set():
            return False
        _finished.set()
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.flush()
        except Exception:
            pass
    channel = _state["channel"]
    limit = _state["max_output_bytes"]
    try:
        size = os.path.getsize(_state["out"]) + os.path.getsize(_state["err"])
    except OSError:
        size = 0
    if status == "completed" and size > limit:
        status, error = "output_limit", "the program wrote more than %d bytes of output" % limit
    out = _read_head(_state["out"], limit)
    for start in range(0, len(out), OUT_CHUNK_BYTES):
        channel.send("OUT", base64.b64encode(out[start:start + OUT_CHUNK_BYTES]).decode("ascii"))
    tail = _read_tail(_state["err"], _state["max_log_chars"])
    if tail:
        channel.send("ERR", base64.b64encode(tail.encode("utf-8")).decode("ascii"))
    done = {"status": status}
    if error:
        done["error"] = error[:MAX_ERROR_CHARS]
    channel.send("DONE", json.dumps(done, ensure_ascii=True, separators=(",", ":")))
    channel.close()
    return True


def _end_process(code):
    """Ends the run now: this process group when this runtime leads it, or this process alone."""
    if _state.get("leader"):
        try:
            os.killpg(os.getpgrp(), signal.SIGKILL)
        except OSError:
            pass
    os._exit(code)


def _violation(message):
    _finish("violation", message)
    _end_process(3)


def _watch():
    """Ends the program when its output passes the limit; it can write to fd 1 and 2 directly (§11)."""
    limit = _state["max_output_bytes"]
    while not _finished.is_set():
        time.sleep(0.1)
        try:
            size = os.path.getsize(_state["out"]) + os.path.getsize(_state["err"])
        except OSError:
            continue
        if size > limit:
            if _finish("output_limit", "the program wrote more than %d bytes of output" % limit):
                _end_process(4)
            return


def _try_read_reply(path):
    try:
        fd = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
    except FileNotFoundError:
        return None
    except OSError as e:
        _violation("reply %s could not be opened: %s" % (os.path.basename(path), e))
    try:
        info = os.fstat(fd)
        if not stat.S_ISREG(info.st_mode):
            _violation("reply %s is not a regular file" % os.path.basename(path))
        if info.st_size > _state["max_reply_bytes"]:
            _violation("reply %s is larger than the limit" % os.path.basename(path))
        chunks = []
        while True:
            chunk = os.read(fd, 65536)
            if not chunk:
                break
            chunks.append(chunk)
        return b"".join(chunks)
    finally:
        os.close(fd)


async def _await_reply(seq):
    path = os.path.join(_state["reply_dir"], "r", str(seq))
    attempt = 0
    while True:
        data = _try_read_reply(path)
        if data is not None:
            break
        if time.monotonic() > _state["deadline"]:
            raise TimeoutError("no reply to tool call %d before the wall clock ran out" % seq)
        await asyncio.sleep(POLL_DELAYS[attempt] if attempt < len(POLL_DELAYS) else POLL_MAX)
        attempt += 1
    try:
        os.unlink(path)
    except OSError:
        pass
    newline = data.find(b"\n")
    if newline < 0:
        _violation("reply %d has no authentication header" % seq)
    header, payload = data[:newline], data[newline + 1:]
    expected = hmac.new(_state["key"], b"R" + str(seq).encode("ascii") + b"\x00" + payload, hashlib.sha256)
    if not hmac.compare_digest(header, expected.hexdigest().encode("ascii")):
        _violation("reply %d failed authentication" % seq)
    try:
        reply = json.loads(payload.decode("utf-8"))
        text = reply["text"]
        is_error = bool(reply["is_error"])
        retryable = bool(reply.get("retryable", True))
    except Exception:
        _violation("reply %d is not a reply" % seq)
    if is_error:
        raise ToolError(text, retryable)
    return text


async def call_tool(name, args=None):
    """Calls the tool `name` with the dict `args` and returns its result text; raises ToolError when it fails."""
    if args is None:
        args = {}
    if not isinstance(args, dict):
        raise TypeError("tool arguments must be a dict, got %s" % type(args).__name__)
    payload = json.dumps({"tool": str(name), "args": args}, ensure_ascii=True, separators=(",", ":"))
    seq = _state["channel"].send("CALL", payload)
    if seq == 0:
        raise ToolError("argument too large; write it to a file and pass the path", False)
    if seq < 0:
        raise ToolError("the program has been stopped", False)
    return await _await_reply(seq)


def _tool_function(name):
    async def tool(args=None):
        return await call_tool(name, args)

    tool.__name__ = name
    tool.__qualname__ = name
    return tool


def _run_program(source, program_globals):
    flags = getattr(ast, "PyCF_ALLOW_TOP_LEVEL_AWAIT", 0)
    code = compile(source, "<program>", "exec", flags=flags, dont_inherit=True)

    async def runner():
        result = eval(code, program_globals)
        if inspect.iscoroutine(result):
            await result

    asyncio.run(runner())


def _redirect(fd, path):
    target = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.dup2(target, fd)
    os.close(target)


def main():
    try:
        os.setpgid(0, 0)
        _state["leader"] = True
    except OSError:
        _state["leader"] = False
    _no_dump()
    _state["key"] = _read_and_delete("key")
    _state["reply_dir"] = _read_and_delete("reply_dir").decode("utf-8").strip()
    with open(os.path.join(RUN_DIR, "config.json"), "rb") as f:
        config = json.loads(f.read().decode("utf-8"))
    with open(os.path.join(RUN_DIR, "main.py"), "rb") as f:
        source = f.read().decode("utf-8")
    _state["max_output_bytes"] = int(config["max_output_bytes"])
    _state["max_reply_bytes"] = int(config["max_reply_bytes"])
    _state["max_log_chars"] = int(config["max_log_chars"])
    _state["deadline"] = time.monotonic() + float(config["wall_clock_seconds"])

    # The channel moves to a descriptor of its own, closed on exec; fd 1 and 2 go to private capture files.
    sys.stdout.flush()
    sys.stderr.flush()
    channel_fd = os.dup(1)
    os.set_inheritable(channel_fd, False)
    _state["channel"] = _Channel(channel_fd, _state["key"])
    capture = tempfile.mkdtemp(prefix="aimon-ptc-")
    _state["out"] = os.path.join(capture, "out")
    _state["err"] = os.path.join(capture, "err")
    _redirect(1, _state["out"])
    _redirect(2, _state["err"])
    devnull = os.open(os.devnull, os.O_RDONLY)
    os.dup2(devnull, 0)
    os.close(devnull)
    threading.Thread(target=_watch, name="aimon-ptc-watch", daemon=True).start()

    program_globals = {
        "__name__": "__main__",
        "__builtins__": __builtins__,
        "ToolError": ToolError,
        "call_tool": call_tool,
    }
    for name in config["tools"]:
        if name.isidentifier() and not keyword.iskeyword(name) and name not in program_globals:
            program_globals[name] = _tool_function(name)

    status, error = "completed", None
    try:
        _run_program(source, program_globals)
    except SyntaxError as e:
        status, error = "error", "SyntaxError: %s (line %s)" % (e.msg, e.lineno)
    except SystemExit as e:
        if e.code not in (None, 0):
            status, error = "error", "SystemExit: %s" % (e.code,)
    except BaseException as e:
        traceback.print_exc()
        status, error = "error", "%s: %s" % (type(e).__name__, e)
    _finish(status, error)
    shutil.rmtree(capture, ignore_errors=True)
    os._exit(0)


if __name__ == "__main__":
    main()
