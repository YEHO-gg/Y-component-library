#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""极简 .env 加载器（零依赖）。

为什么需要它：文档里一直写着「cp .env.example .env 然后填」，但代码如果不读
.env，那几个字段填了也没用。这个模块只做一件事：把 server/.env（或
$YEHOO_CHAT_DIR/.env）里的 KEY=VALUE 灌进 os.environ。

规则（刻意保守）：
- 只处理 `KEY=VALUE`，忽略空行和 `#` 注释行；
- 已经存在的环境变量**不覆盖**（真环境变量优先级最高）；
- 值可以带引号，会去掉首尾成对的 ' 或 "；
- 不解析变量插值、不执行任何东西。

用法：在三个入口脚本的**最前面**（读完 os.environ 之前）调用 `load_env()`。
"""
import os

_LOADED = False


def _parse(path):
    pairs = []
    with open(path, "r", encoding="utf-8-sig", errors="replace") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            if line.startswith("export "):
                line = line[7:].lstrip()
            key, _, value = line.partition("=")
            key = key.strip()
            if not key or not key.replace("_", "").isalnum():
                continue
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            pairs.append((key, value))
    return pairs


def load_env(verbose=False):
    """把 .env 灌进 os.environ，返回读到的文件路径（没读到则 None）。"""
    global _LOADED
    if _LOADED:
        return None
    _LOADED = True

    here = os.path.dirname(os.path.abspath(__file__))
    candidates = [
        os.path.join(os.environ.get("YEHOO_CHAT_DIR") or os.path.join(here, "data"), ".env"),
        os.path.join(here, ".env"),
        os.path.join(os.path.dirname(here), ".env"),
    ]

    for path in candidates:
        if not os.path.isfile(path):
            continue
        try:
            pairs = _parse(path)
        except OSError:
            continue
        for key, value in pairs:
            if key not in os.environ:
                os.environ[key] = value
        if verbose:
            print("[env] loaded %d entries from %s" % (len(pairs), path))
        return path
    return None


if __name__ == "__main__":
    print(load_env(verbose=True) or "no .env found")
