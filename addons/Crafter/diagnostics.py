"""Blender-independent diagnostics. UI history is bounded; JSONL files rotate.

CRAFTER_LOG_DIR overrides the per-user log directory. CRAFTER_DEBUG=0 disables
DEBUG records. No environment dump is collected. Do not call bpy from readers.
"""
from contextlib import contextmanager
from contextvars import ContextVar
from functools import wraps
from logging.handlers import RotatingFileHandler
from pathlib import Path
import datetime
import json
import logging
import os
import platform
import sys
import tempfile
import threading
import time
import traceback
import uuid

MAX_LOG_LINES = 2000
recent_log = []
_lock = threading.RLock()
_handler = None
_initialized = False
_log_path = ""
_session = uuid.uuid4().hex[:12]
_operation = ContextVar("crafter_operation", default="")
_stage = ContextVar("crafter_stage", default="")
_scopes = ContextVar("crafter_scopes", default=())
_started = time.perf_counter()


def _default_log_dir():
    if os.environ.get("CRAFTER_LOG_DIR"):
        return Path(os.environ["CRAFTER_LOG_DIR"]).expanduser()
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Logs" / "Crafter"
    if os.name == "nt":
        return Path(os.environ.get("LOCALAPPDATA", Path.home())) / "Crafter" / "logs"
    return Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state")) / "crafter" / "logs"


class _SafeRotatingHandler(RotatingFileHandler):
    """Disable only the failed sink, never flood stderr or fail an import."""
    failed = False

    def handleError(self, record):
        if not self.failed:
            self.failed = True
            print(f"[Crafter] Persistent log failed ({self.baseFilename}); UI logging continues: "
                  f"{sys.exc_info()[1]}", file=sys.stderr)

    def emit(self, record):
        if not self.failed:
            super().emit(record)


def _slow_threshold_ms():
    try:
        return max(0.0, float(os.environ.get("CRAFTER_SLOW_MS", "5000")))
    except ValueError:
        return 5000.0


def log_file_path():
    global _handler, _initialized, _log_path
    with _lock:
        if not _initialized:
            _initialized = True
            # Separate files per process avoid cross-process rotation races.
            filename = f"crafter-{os.getpid()}.jsonl"
            for directory in (_default_log_dir(), Path(tempfile.gettempdir()) / "crafter-logs"):
                try:
                    directory.mkdir(parents=True, exist_ok=True)
                    _log_path = str(directory / filename)
                    _handler = _SafeRotatingHandler(_log_path, maxBytes=5 * 1024 * 1024,
                                                  backupCount=3, encoding="utf-8")
                    _handler.setFormatter(logging.Formatter("%(message)s"))
                    break
                except OSError as error:
                    _log_path = ""
                    print(f"[Crafter] Cannot open diagnostics in {directory}: {error}", file=sys.stderr)
        return _log_path


def _source():
    frame = sys._getframe(1)
    try:
        while frame:
            filename = frame.f_code.co_filename
            if filename != __file__ and not (filename.endswith("Defs.py") and
                    frame.f_code.co_name in {"push_log", "debug_log", "info_log", "warn_log", "error_log",
                                            "log_step", "log_stage_begin", "log_stage_end"}):
                return f"{filename}:{frame.f_lineno}", frame.f_code.co_name
            frame = frame.f_back
    finally:
        del frame
    return "", ""


def log_event(text, level="INFO", stage=None, **fields):
    """Append a backward-compatible UI record and a structured persistent record."""
    level = "WARN" if level == "WARNING" else str(level).upper()
    if level == "DEBUG" and os.environ.get("CRAFTER_DEBUG", "1").lower() in {"0", "false", "off"}:
        return None
    source, function = _source()
    now = datetime.datetime.now().astimezone()
    entry = dict(text=str(text), level=level, time=now.strftime("%H:%M:%S.%f")[:-3],
                 timestamp=now.isoformat(timespec="milliseconds"), stage=stage or _stage.get(),
                 session=_session, operation=_operation.get(), pid=os.getpid(),
                 thread=threading.current_thread().name, thread_id=threading.get_ident(),
                 source=source, function=function, elapsed_ms=round((time.perf_counter() - _started) * 1000, 3))
    entry.update(fields)
    with _lock:
        for scope in _scopes.get():
            if level in {"ERROR", "WARN"}:
                scope["errors" if level == "ERROR" else "warnings"] += 1
        recent_log.append(entry)
        if len(recent_log) > MAX_LOG_LINES:
            del recent_log[:len(recent_log) - MAX_LOG_LINES]
        log_file_path()
        if _handler:
            # Logging must not replace the original exception if storage fills up.
            try:
                message = json.dumps(entry, ensure_ascii=False, default=str)
                record = logging.LogRecord("Crafter", getattr(logging, level, logging.WARNING),
                                           source, 0, message, (), None)
                _handler.handle(record)
            except Exception as error:
                print(f"[Crafter] Failed to write diagnostics: {error}", file=sys.stderr)
    return entry


def log_exception(text, stage=None, **fields):
    fields.setdefault("traceback", traceback.format_exc())
    return log_event(text, "ERROR", stage, **fields)


def clear_recent_log():
    with _lock:
        recent_log.clear()
    log_event("UI 日志已清空；磁盘日志保留", "DEBUG", "日志")


def format_recent_log(include_meta=True):
    with _lock:
        entries = list(recent_log)
    if not include_meta:
        return "\n".join(e["text"] for e in entries)
    lines = []
    for entry in entries:
        meta = " ".join(f"{key}={value}" for key, value in entry.items()
                        if key not in {"text", "level", "time", "timestamp", "traceback"} and value != "")
        lines.append(f"[{entry['timestamp']}] [{entry['level']}] {meta} {entry['text']}")
        if entry.get("traceback"):
            lines.append(entry["traceback"])
    return "\n".join(lines)


@contextmanager
def operation_scope(name, level="INFO", **fields):
    """Keep nested operation IDs, timings and handled-error counts together."""
    parent = _operation.get()
    op = _operation.set(uuid.uuid4().hex[:12])
    stage = _stage.set(name)
    counts = {"errors": 0, "warnings": 0}
    scope_token = _scopes.set(_scopes.get() + (counts,))
    fields.setdefault("parent_operation", parent)
    start = time.perf_counter()
    result = {"status": "success"}
    try:
        log_event(f"▶ {name}", level, name, status="running", **fields)
        try:
            yield result
        except BaseException:
            duration = round((time.perf_counter() - start) * 1000, 3)
            log_exception(f"■ {name} 失败", name, duration_ms=duration, status="error",
                          slow=duration >= _slow_threshold_ms(), **fields)
            raise
        else:
            duration = round((time.perf_counter() - start) * 1000, 3)
            status = result.get("status", "success")
            if status == "success" and counts["errors"]:
                status = "completed_with_errors"
            elif status == "success" and counts["warnings"]:
                status = "completed_with_warnings"
            slow = duration >= _slow_threshold_ms()
            severity = "WARN" if status != "success" or slow else level
            label = "取消" if status == "cancelled" else ("完成（有异常/警告）" if status != "success" else "完成")
            log_event(f"■ {name} {label}", severity, name, duration_ms=duration,
                      status=status, slow=slow, error_count=counts["errors"],
                      warning_count=counts["warnings"], **fields,
                      **{k: v for k, v in result.items() if k != "status" and k not in fields})
    finally:
        _scopes.reset(scope_token)
        _stage.reset(stage)
        _operation.reset(op)


def trace_operation(name, detail=None, level="INFO"):
    """Time success/cancellation/failure without touching operator annotations."""
    def decorate(function):
        @wraps(function)
        def traced(*args, **kwargs):
            fields = {"source": f"{function.__code__.co_filename}:{function.__code__.co_firstlineno}",
                      "function": function.__qualname__}
            # Resolve Blender context only on the caller's thread.
            if detail:
                try:
                    fields.update(detail(*args, **kwargs))
                except Exception as error:
                    log_event(f"诊断上下文不可用: {error}", "WARN", name)
            with operation_scope(name, level, **fields) as outcome:
                result = function(*args, **kwargs)
                if isinstance(result, set) and "CANCELLED" in result:
                    outcome["status"] = "cancelled"
                return result
        return traced
    return decorate


def log_environment(blender=None, addon_version=None, addon_path=None):
    fields = dict(system=platform.platform(), architecture=platform.machine(), cpu_count=os.cpu_count(),
                  python=sys.version, executable=sys.executable, cwd=os.getcwd(),
                  addon_version=addon_version, addon_path=addon_path, log_path=log_file_path())
    if blender is not None:
        for attr in ("version_string", "binary_path", "background", "build_hash"):
            value = getattr(blender.app, attr, None)
            fields["blender_" + attr] = value.decode("utf-8", "replace") if isinstance(value, bytes) else value
    log_event("启动环境", "INFO", "启动", **fields)


def process_started(command, cwd=None, pid=None, **fields):
    start = time.perf_counter()
    log_event("子进程启动", "INFO", "子进程", command=command, cwd=str(cwd or os.getcwd()),
              child_pid=pid, status="running", **fields)
    return start


def process_finished(start, pid, returncode, **fields):
    log_event("子进程退出", "INFO" if returncode == 0 else "ERROR", "子进程", child_pid=pid,
              returncode=returncode, status="success" if returncode == 0 else "error",
              duration_ms=round((time.perf_counter() - start) * 1000, 3), **fields)


def output_level(line):
    """Prefer explicit child severity, then fall back to legacy text heuristics."""
    import re
    match = re.search(r"\[(DEBUG|INFO|WARN|WARNING|ERROR)\]", line)
    if match:
        return "WARN" if match[1] == "WARNING" else match[1]
    low = line.lower()
    if any(word in low for word in ("error", "failed", "exception")) or "错误" in line or "失败" in line:
        return "ERROR"
    if "warn" in low or "警告" in line:
        return "WARN"
    return "INFO"


def export_session(destination, header=""):
    """Export the retained disk window, not just the 2000 UI entries."""
    import shutil
    path = log_file_path()
    with _lock:
        if _handler:
            _handler.flush()
        if path and Path(destination).resolve() in {Path(path + suffix).resolve() for suffix in ("", ".1", ".2", ".3")}:
            raise ValueError("不能导出到正在使用的日志文件")
        with open(destination, "w", encoding="utf-8") as output:
            if header:
                output.write(header + "\n")
            if path:
                for suffix in (".3", ".2", ".1", ""):
                    source = Path(path + suffix)
                    if source.exists():
                        with source.open(encoding="utf-8", errors="replace") as input_file:
                            shutil.copyfileobj(input_file, output)
            else:
                output.write(format_recent_log())


def run_logged_command(command, **kwargs):
    """Audit short probes (JVM/version and system-memory commands)."""
    import subprocess
    started = process_started(command, kwargs.get("cwd"), purpose="probe")
    try:
        result = subprocess.run(command, **kwargs)
    except Exception as error:
        duration = round((time.perf_counter() - started) * 1000, 3)
        log_exception("探测命令失败", "子进程", command=command, duration_ms=duration,
                      returncode=getattr(error, "returncode", None), status="error")
        raise
    process_finished(started, None, result.returncode, command=command)
    for stream in ("stdout", "stderr"):
        output = getattr(result, stream, None)
        if output:
            if isinstance(output, bytes):
                output = output.decode("utf-8", "replace")
            log_event(str(output)[:16384], "DEBUG", "子进程", command=command, stream=stream)
    return result
