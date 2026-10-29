#!/usr/bin/env python3
"""用 Git Data API 把当前项目全量推送到 GitHub。

blobs -> trees -> commits -> refs 四步完成，支持文本与二进制文件。
凭证从环境变量 GH_TOKEN 读取，不写入任何文件。
"""
import base64
import json
import os
import sys
import urllib.error
import urllib.request

TOKEN = os.environ.get("GH_TOKEN", "")
REPO = os.environ.get("GH_REPO", "cswfwm/neurojump")
PROJECT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
GIT = "https://api.github.com/repos/" + REPO + "/git/"
CONTENTS = "https://api.github.com/repos/" + REPO + "/contents/"

SKIP_DIRS = {".git", ".aicode", "build", ".gradle", ".idea", ".cxx", "__pycache__"}
SKIP_FILES = {"local.properties"}
SKIP_EXT = {".apk", ".aab", ".p12", ".jks", ".keystore", ".png", ".jpg", ".jpeg"}
SKIP_NAMES = {"keystore.p12"}


def req(method, url, data=None):
    r = urllib.request.Request(url, method=method)
    r.add_header("Authorization", "Bearer " + TOKEN)
    r.add_header("Accept", "application/vnd.github+json")
    body = json.dumps(data).encode() if data is not None else None
    if body:
        r.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(r, body) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def collect():
    files = []
    for root, dirs, names in os.walk(PROJECT):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for n in names:
            if n in SKIP_FILES or n in SKIP_NAMES:
                continue
            if os.path.splitext(n)[1].lower() in SKIP_EXT:
                continue
            p = os.path.join(root, n)
            rel = os.path.relpath(p, PROJECT).replace(os.sep, "/")
            files.append((rel, p))
    return files


def is_binary(path):
    with open(path, "rb") as f:
        return b"\x00" in f.read(8000)


def ensure_branch():
    """取 main 的 head；仓库为空时用 Contents API 建初始 commit。"""
    st, res = req("GET", GIT + "ref/heads/main")
    if st == 200 and "object" in res:
        return res["object"]["sha"]
    st, res = req("PUT", CONTENTS + "README.md", {
        "message": "init",
        "content": base64.b64encode(b"# neurojump\n").decode()})
    if st not in (200, 201):
        print("初始化失败:", st, res)
        sys.exit(1)
    st, res = req("GET", GIT + "ref/heads/main")
    return res["object"]["sha"]


def main():
    if not TOKEN:
        print("缺少 GH_TOKEN")
        return 1
    print("仓库:", REPO)
    head = ensure_branch()
    st, res = req("GET", GIT + "commits/" + head)
    if st != 200:
        print("取 base tree 失败:", st, res)
        return 1
    base_tree = res["tree"]["sha"]

    files = collect()
    print("待上传文件:", len(files))
    entries = []
    total = 0
    for rel, p in files:
        if is_binary(p):
            with open(p, "rb") as f:
                b64 = base64.b64encode(f.read()).decode()
            st, blob = req("POST", GIT + "blobs", {"content": b64, "encoding": "base64"})
        else:
            with open(p, "r", encoding="utf-8") as f:
                content = f.read()
            st, blob = req("POST", GIT + "blobs", {"content": content, "encoding": "utf-8"})
        if st != 201 or "sha" not in blob:
            print("blob 失败:", rel, st, blob)
            return 1
        total += os.path.getsize(p)
        mode = "100755" if rel.endswith("gradlew") else "100644"
        entries.append({"path": rel, "mode": mode, "type": "blob", "sha": blob["sha"]})

    print("上传体积: %.1f KB" % (total / 1024.0))
    st, tree = req("POST", GIT + "trees", {"base_tree": base_tree, "tree": entries})
    if st != 201:
        print("建 tree 失败:", st, tree)
        return 1
    st, commit = req("POST", GIT + "commits", {
        "message": "神经寻路：遗传算法自动训练的 2D 平台跳跃 AI",
        "tree": tree["sha"],
        "parents": [head]})
    if st != 201:
        print("建 commit 失败:", st, commit)
        return 1
    st, ref = req("PATCH", GIT + "refs/heads/main", {"sha": commit["sha"]})
    if st != 200:
        print("更新 ref 失败:", st, ref)
        return 1
    print("推送完成:", commit["sha"][:12])
    print("仓库地址: https://github.com/" + REPO)
    return 0


if __name__ == "__main__":
    sys.exit(main())
