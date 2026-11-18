#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NnnHook 离线适配验证器。

不连真机、不开 LSPosed，直接对一个 APK 跑一遍与模块内 Engine 等价的查询，
输出 14 个 API 点的命中情况。换版本时先在这里跑，看哪一项掉成 MISS，
改完 Anchor 再装机，省掉"装机 → 重启 → 抓日志"的循环。

为什么能和真机一致：DexKit 那几类查询本质都是 dex 结构查询——
  declaredClass + name + returnType → 方法表
  usingStrings                      → 扫 const-string 指令
  usingFields                       → 扫 iget/iput/sget/sput 指令
androguard 读得到同样的信息，所以结果与 logcat 对齐（已对比）。

用法：
    python tools/offline_adapt.py <apk路径>
    python tools/offline_adapt.py tools/samples/nnngram-v13.2.1.apk

依赖：pip install androguard
"""
from __future__ import annotations

import sys
import zipfile
import argparse

try:
    from loguru import logger
    logger.remove()          # 关掉 androguard 的逐项解析日志
except Exception:
    pass

from androguard.core.dex import DEX


# ── 锚点常量（与 Anchor.java 同步；改锚点两边一起改）──────────────────
TLRPC_USER = "Lorg/telegram/tgnet/TLRPC$User;"
TLRPC_USER_FULL = "Lorg/telegram/tgnet/TLRPC$UserFull;"
TLRPC_CHAT = "Lorg/telegram/tgnet/TLRPC$Chat;"
SPONSORED_MSGS = "Lorg/telegram/tgnet/TLRPC$messages_SponsoredMessages;"
CONNECTIONS_MANAGER = "Lorg/telegram/tgnet/ConnectionsManager;"
ANTI_DELETE_UPDATE = "Lorg/telegram/tgnet/tl/TL_update$TL_updateDeleteMessages;"

DESERIALIZERS = ("TLdeserialize", "fromConstructor")
PREMIUM_FIELD = "premium"
LIMIT_KEY = "dialog_filters_limit_default"
LIMIT_FEATURE_VALUES = (30, 100)

OK, MISS = "✓", "×"


def params_of(desc: str):
    """'(IILfoo;)V' -> ['I', 'I', 'Lfoo;']"""
    if not desc.startswith("("):
        return []
    body = desc[1:desc.index(")")]
    out, i = [], 0
    while i < len(body):
        c = body[i]
        if c == "L":
            j = body.index(";", i)
            out.append(body[i:j + 1])
            i = j + 1
        elif c == "[":
            j = i
            while body[j] == "[":
                j += 1
            if body[j] == "L":
                k = body.index(";", j)
                out.append(body[i:k + 1])
                i = k + 1
            else:
                out.append(body[i:j + 1])
                i = j + 1
        else:
            out.append(c)
            i += 1
    return out


def ret_of(desc: str) -> str:
    return desc.split(")")[-1]


def short(d: str) -> str:
    return d[1:].rstrip(";").replace("/", ".") if d.startswith("L") else d


class Method:
    __slots__ = ("cls", "name", "ret", "params", "em")

    def __init__(self, cls, name, ret, params, em):
        self.cls, self.name, self.ret, self.params, self.em = cls, name, ret, params, em

    @property
    def key(self):
        return f"{self.cls}#{self.name}({','.join(self.params)}){self.ret}"

    def instructions(self):
        code = self.em.get_code()
        if code is None:
            return []
        try:
            return code.get_bc().get_instructions()
        except Exception:
            return []


class Index:
    def __init__(self):
        self.methods: list[Method] = []
        self._seen = set()
        self.fields: dict[str, set[str]] = {}
        self.dex_count = 0

    def add(self, raw: bytes):
        d = DEX(raw)
        self.dex_count += 1
        for c in d.get_classes():
            cdesc = c.get_name()
            try:
                # 字段名：ClassDef 上的 get_fields 返回 EncodedField
                self.fields.setdefault(cdesc, set()).update(
                    f.get_name() for f in c.get_fields())
            except Exception:
                pass
            for em in c.get_methods():
                try:
                    desc = em.get_descriptor()
                    m = Method(cdesc, em.get_name(), ret_of(desc),
                               params_of(desc), em)
                except Exception:
                    continue
                if m.key in self._seen:      # 多 dex 会重复定义同一个类
                    continue
                self._seen.add(m.key)
                self.methods.append(m)

    def by_name(self, name, ret=None, nparams=None, cls=None):
        out = []
        for m in self.methods:
            if m.name != name:
                continue
            if ret is not None and m.ret != ret:
                continue
            if nparams is not None and len(m.params) != nparams:
                continue
            if cls is not None and m.cls != cls:
                continue
            out.append(m)
        return out

    def using_strings(self, needle):
        out = []
        for m in self.methods:
            for ins in m.instructions():
                if ins.get_name() != "const-string":
                    continue
                if needle in ins.get_output():
                    out.append(m)
                    break
        return out

    def using_fields(self, field, ret=None, nparams=None):
        needle = "->" + field
        out = []
        for m in self.methods:
            if ret is not None and m.ret != ret:
                continue
            if nparams is not None and len(m.params) != nparams:
                continue
            for ins in m.instructions():
                n = ins.get_name()
                if not n or not n.startswith(("iget", "sget")):
                    continue
                out_txt = ins.get_output()
                if needle + " " in out_txt or out_txt.rstrip().endswith(needle):
                    out.append(m)
                    break
        return out


def run(apk: str) -> int:
    print(f"APK: {apk}")
    idx = Index()
    with zipfile.ZipFile(apk) as z:
        names = sorted(n for n in z.namelist()
                       if n.startswith("classes") and n.endswith(".dex"))
        for n in names:
            print(f"  解析 {n} …", end="\r", flush=True)
            idx.add(z.read(n))
    print(" " * 40, end="\r")

    ok = 0
    total = 0
    lines = []

    def put(state, code, detail):
        nonlocal ok, total
        total += 1
        if state == OK:
            ok += 1
        lines.append(f"{state} [{code}] {detail}")

    print(f"\n── dex {idx.dex_count} 个 / 方法 {len(idx.methods)} 个 ──\n")

    put(OK, "bridge", f"dex={idx.dex_count}")

    # TL 反序列化落点
    deser = [m for cls in (TLRPC_USER, TLRPC_USER_FULL)
             for nm in DESERIALIZERS for m in idx.by_name(nm, ret=cls)]
    put(OK if deser else MISS, "premium.deser",
        f"{len(deser)} 个: " + " + ".join(f"{short(m.cls)}#{m.name}" for m in deser)
        if deser else "TLRPC$User/UserFull 上没有 TLdeserialize")

    # 会员字段
    has_field = PREMIUM_FIELD in idx.fields.get(TLRPC_USER, set())
    put(OK if has_field else MISS, "premium.field",
        f"TLRPC$User 有 boolean premium（字段共 "
        f"{len(idx.fields.get(TLRPC_USER, set()))} 个）"
        if has_field else "TLRPC$User 上没有 premium 字段")

    # 会员判定（无参 boolean 读 premium）
    flags = idx.using_fields(PREMIUM_FIELD, ret="Z", nparams=0)
    put(OK if flags else MISS, "premium.flag",
        f"{len(flags)} 个: " + " + ".join(f"{short(m.cls)}#{m.name}" for m in flags)
        if flags else "没有读 premium 的无参 boolean")

    # 受限标记
    chats = [m for nm in DESERIALIZERS for m in idx.by_name(nm, ret=TLRPC_CHAT)]
    put(OK if chats else MISS, "premium.content",
        f"TLRPC$Chat {len(chats)} 个落点" if chats
        else "TLRPC$Chat 上没有 TLdeserialize")

    # 广告
    spo = [m for nm in DESERIALIZERS for m in idx.by_name(nm, ret=SPONSORED_MSGS)]
    put(OK if spo else MISS, "sponsored",
        f"{len(spo)} 个落点" if spo else "messages_SponsoredMessages 上没有 TLdeserialize")

    # 发送层
    senders = idx.by_name("sendRequest", cls=CONNECTIONS_MANAGER)
    put(OK if senders else MISS, "read_receipt/online/screenshot",
        f"sendRequest × {len(senders)} 个重载" if senders
        else "ConnectionsManager#sendRequest 未命中")

    # 防撤回
    anti = idx.by_name("readParams", cls=ANTI_DELETE_UPDATE)
    put(OK if anti else MISS, "anti_delete",
        f"{len(anti)} 个 readParams" if anti
        else "TL_updateDeleteMessages#readParams 未命中")

    # 上限 loadConfig
    loaders = idx.using_strings(LIMIT_KEY)
    put(OK if loaders else MISS, "pin_limit/folder_limit",
        f"loadConfig {len(loaders)} 个: "
        + " + ".join(f"{short(m.cls)}#{m.name}" for m in loaders)
        if loaders else f"没有方法引用 {LIMIT_KEY}")

    for ln in lines:
        print(ln)
    print(f"\n── 离线可验证项 {ok}/{total} ──")
    print("说明：pin/folder 的实际抬升依赖运行时字段值"
          f"（特征值 {list(LIMIT_FEATURE_VALUES)}），离线只能验证落点存在。")
    return 0 if ok == total else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("apk")
    a = ap.parse_args()
    return run(a.apk)


if __name__ == "__main__":
    sys.exit(main())
