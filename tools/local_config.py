# -*- coding: utf-8 -*-
"""读取项目根的 local.env；同名环境变量优先。文件格式与 POSIX shell 赋值兼容。"""
import os
import shlex

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
_CACHE = None


def _read():
    global _CACHE
    if _CACHE is not None:
        return _CACHE
    values = {}
    path = os.path.join(BASE, 'local.env')
    if os.path.isfile(path):
        with open(path, 'r', encoding='utf-8-sig') as f:
            for lineno, raw in enumerate(f, 1):
                line = raw.strip()
                if not line or line.startswith('#'):
                    continue
                if line.startswith('export '):
                    line = line[7:].lstrip()
                if '=' not in line:
                    raise ValueError('local.env:%d 不是 KEY=VALUE' % lineno)
                key, value = line.split('=', 1)
                key = key.strip()
                lexer = shlex.shlex(value, posix=True)
                lexer.whitespace_split = True
                lexer.commenters = '#'
                parts = list(lexer)
                values[key] = parts[0] if len(parts) == 1 else ' '.join(parts)
    _CACHE = values
    return values


def get_local(name, default=None):
    """只读 local.env，用于不允许被环境变量覆盖的本机凭据。"""
    value = _read().get(name)
    return value if value else default


def get(name, default=None):
    value = os.environ.get(name)
    if value is not None and value.strip():
        return value.strip()
    return get_local(name, default)


def adb_path():
    configured = get('ADB')
    if configured:
        return configured
    sdk = get('ANDROID_SDK_DIR')
    if sdk:
        return os.path.join(sdk, 'platform-tools',
                            'adb.exe' if os.name == 'nt' else 'adb')
    return 'adb'


def aapt2_path():
    sdk = get('ANDROID_SDK_DIR')
    if sdk:
        return os.path.join(sdk, 'build-tools', '34.0.0',
                            'aapt2.exe' if os.name == 'nt' else 'aapt2')
    return 'aapt2'
