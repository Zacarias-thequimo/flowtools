#!/usr/bin/env python3
"""Duplicate file name -> PC must answer FileExists, never overwrite."""
import json
import sys
import urllib.request
import uuid
import websocket

BASE = sys.argv[1]


def mp_post(path, fields, fname, data):
    b = uuid.uuid4().hex.encode()
    parts = b""
    for k, v in fields.items():
        parts += b"--" + b + b"\r\n" + f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode() + v.encode() + b"\r\n"
    parts += b"--" + b + b"\r\n" + f'Content-Disposition: form-data; name="file"; filename="{fname}"\r\n\r\n'.encode() + data + b"\r\n"
    parts += b"--" + b + b"--\r\n"
    req = urllib.request.Request(BASE + path, data=parts, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={b.decode()}"})
    with urllib.request.urlopen(req) as r:
        return r.status, json.loads(r.read())


def post_json(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req) as r:
        return r.status, json.loads(r.read())


_, ch = post_json("/v1/pair/start", {"pc_name": "PC", "pc_id": sys.argv[2], "offered": ["files"], "local_only": True})
_, sess = post_json("/v1/pair/claim", {"pairing_id": ch["pairing_id"], "code": ch["code"],
                                      "device_name": "T", "approved": ["files"]})
ws = websocket.create_connection(BASE.replace("http", "ws") + f"/v1/session/ws?token={sess['token']}")
ws.settimeout(8)
print("hello1:", ws.recv()[:60])
fresh = json.loads(ws.recv())["token"]
print("rotated token in use")
mp_post("/v1/files/inbox", {"token": fresh}, "dup.txt", b"primeira")
mp_post("/v1/files/inbox", {"token": fresh}, "dup.txt", b"segunda")
seen = set()
for _ in range(6):
    try:
        m = ws.recv()
    except Exception as e:
        print("timeout:", e)
        break
    print("ws:", m[:120])
    seen.add(json.loads(m).get("code", json.loads(m).get("type")))
print("PASS FileExists observado" if "file_exists" in seen else "FAIL sem file_exists")
