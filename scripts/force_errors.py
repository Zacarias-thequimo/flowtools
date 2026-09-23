#!/usr/bin/env python3
"""FlowTools error battery: forces bad inputs, prints PASS/FAIL per case."""
import json
import sys
import urllib.request
import websocket

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8799"
WSBASE = BASE.replace("http", "ws")
results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond), detail))
    print(("PASS" if cond else "FAIL"), name, detail, flush=True)


def http(method, path, body=None, multipart=None):
    if multipart is not None:
        import uuid
        b = uuid.uuid4().hex.encode()
        parts = b""
        for k, v in multipart["fields"].items():
            parts += b"--" + b + b"\r\n" + f'Content-Disposition: form-data; name="{k}"\r\n\r\n'.encode() + v.encode() + b"\r\n"
        for name, (fname, data) in multipart["files"].items():
            parts += b"--" + b + b"\r\n" + f'Content-Disposition: form-data; name="{name}"; filename="{fname}"\r\n\r\n'.encode() + data + b"\r\n"
        parts += b"--" + b + b"--\r\n"
        req = urllib.request.Request(BASE + path, data=parts, method=method,
                                     headers={"Content-Type": f"multipart/form-data; boundary={b.decode()}"})
    else:
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(BASE + path, data=data, method=method,
                                     headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()
    except Exception as e:  # connection reset etc: report, don't crash the battery
        return -1, f"TRANSPORT {e}".encode()


def pair(pc_id, offered, approved):
    s, b = http("POST", "/v1/pair/start", {"pc_name": "PC", "pc_id": pc_id, "offered": offered, "local_only": True})
    ch = json.loads(b)
    s2, b2 = http("POST", "/v1/pair/claim", {"pairing_id": ch["pairing_id"], "code": ch["code"],
                                            "device_name": "T", "approved": approved})
    return s2, json.loads(b2) if s2 == 201 else b2, ch


def ws_recv_n(ws, n, timeout=5):
    ws.settimeout(timeout)
    out = []
    for _ in range(n):
        out.append(ws.recv())
    return out


# --- 1. pairing errors ---
s, b = http("POST", "/v1/pair/claim", {"pairing_id": "nope", "code": "000000", "device_name": "T", "approved": []})
check("claim pairing inexistente -> 404 invalid_code", s == 404 and b'"invalid_code"' in b, f"{s} {b[:60]}")

s, b = http("POST", "/v1/pair/start", {"pc_name": "PC", "pc_id": "pc-err", "offered": ["cursor"], "local_only": True})
ch = json.loads(b)
s, b = http("POST", "/v1/pair/claim", {"pairing_id": ch["pairing_id"], "code": "999999", "device_name": "T", "approved": ["cursor"]})
check("claim codigo errado -> 401 invalid_code", s == 401 and b'"invalid_code"' in b, f"{s} {b[:60]}")

s, b = http("POST", "/v1/pair/claim", {"pairing_id": ch["pairing_id"], "code": ch["code"], "device_name": "T", "approved": ["files"]})
check("claim permissao fora do oferecido -> 403 permission_needed", s == 403 and b'"permission_needed"' in b, f"{s} {b[:60]}")

# --- 2. upload errors ---
s, b = http("POST", "/v1/files/inbox", multipart={"fields": {}, "files": {}})
check("upload sem campos -> 400", s == 400, f"{s} {b[:60]}")

s, tok, _ = pair("pc-big", ["files"], ["files"])
big = b"\0" * (60 * 1024 * 1024)
s, b = http("POST", "/v1/files/inbox", multipart={"fields": {"token": tok["token"]},
                                                  "files": {"file": ("grande.bin", big)}})
check("upload 60MB -> 413 too_large", s == 413 and b'"too_large"' in b, f"{s} {b[:80]}")
del big

s, nocap, _ = pair("pc-nocap", ["cursor"], ["cursor"])
s, b = http("POST", "/v1/files/inbox", multipart={"fields": {"token": nocap["token"]},
                                                  "files": {"file": ("a.txt", b"x")}})
check("upload sem capacidade files -> 403", s == 403 and b'"permission_needed"' in b, f"{s} {b[:60]}")

# --- 3. ticket lifecycle ---
s, sess, _ = pair("pc-tick", ["files"], ["files"])
s, b = http("POST", "/v1/files/inbox", multipart={"fields": {"token": sess["token"]},
                                                  "files": {"file": ("nota.txt", b"conteudo")}})
v = json.loads(b)
ticket = v["ticket"]
check("upload valido -> 201 + destino", s == 201 and v["destination"] == "~/Downloads/FlowTools", f"{s}")

s, b = http("GET", f"/v1/files/inbox/{ticket}?pc_id=pc-outro")
check("download PC errado -> 403 (ticket preservado)", s == 403, f"{s}")

s, b = http("GET", f"/v1/files/inbox/{ticket}?pc_id=pc-tick")
check("download correto -> 200 bytes intactos", s == 200 and b == b"conteudo", f"{s} {b[:20]}")

s, b = http("GET", f"/v1/files/inbox/{ticket}?pc_id=pc-tick")
check("segundo download -> 404 (one-shot)", s == 404, f"{s}")

s, b = http("GET", "/v1/files/inbox/inexistente?pc_id=pc-tick")
check("ticket inexistente -> 404", s == 404, f"{s}")

# --- 4. websocket errors ---
def ws_session(token):
    ws = websocket.create_connection(f"{WSBASE}/v1/session/ws?token={token}")
    msgs = ws_recv_n(ws, 2)  # State + Token
    return ws, msgs

ws, msgs = ws_session(sess["token"])
check("ws abre com State+Token (rotacao)", '"connected"' in msgs[0] and '"token"' in msgs[1], str(msgs[0])[:60])
new_token = json.loads(msgs[1])["token"]
check("token rodou", new_token != sess["token"])

ws.send('nao-json{{{')
m = ws_recv_n(ws, 1)[0]
check("ws json invalido -> error unknown", '"unknown"' in m, m[:80])

ws.send(json.dumps({"type": "ping"}))
m = ws_recv_n(ws, 1)[0]
check("ws ping -> pong", '"pong"' in m, m[:40])

ws.send(json.dumps({"type": "shutdown_pc", "confirmed": False}))
m = ws_recv_n(ws, 1)[0]
check("shutdown sem confirmar -> error", '"type":"error"' in m, m[:80])

ws.send(json.dumps({"type": "shutdown_pc", "confirmed": True}))
m = ws_recv_n(ws, 1)[0]
check("shutdown sem cap system -> permission_needed", '"permission_needed"' in m, m[:80])

ws.send(json.dumps({"type": "cursor_move", "dx": 1.0, "dy": 1.0}))
m = ws_recv_n(ws, 1)[0]
check("cursor sem cap cursor -> permission_needed", '"permission_needed"' in m, m[:80])

ws2 = websocket.create_connection(f"{WSBASE}/v1/session/ws?token={sess['token']}")
m = ws_recv_n(ws2, 1)[0]
check("token velho (rodado) -> expired_session", '"expired_session"' in m, m[:80])

ws3 = websocket.create_connection(f"{WSBASE}/v1/session/ws")
m = ws_recv_n(ws3, 1)[0]
check("ws sem token -> expired_session", '"expired_session"' in m, m[:80])

# --- 5. revoke ---
s, b = http("POST", f"/v1/devices/{sess['device_id']}/revoke")
check("revogar -> ok", s == 200, f"{s}")
s, b = http("GET", f"/v1/session/state?token={new_token}")
check("state apos revoke -> offline", b'"offline"' in b, b[:80])
ws4 = websocket.create_connection(f"{WSBASE}/v1/session/ws?token={new_token}")
m = ws_recv_n(ws4, 1)[0]
check("ws apos revoke -> expired_session", '"expired_session"' in m, m[:80])

# --- 6. unknown PC channel ---
wspc = websocket.create_connection(f"{WSBASE}/v1/pc/channel?pc_id=pc-fantasma")
m = ws_recv_n(wspc, 1)[0]
check("canal PC desconhecido -> error", '"type":"error"' in m, m[:80])

fails = [n for n, ok, _ in results if not ok]
print(f"\n{len(results) - len(fails)}/{len(results)} PASS")
sys.exit(1 if fails else 0)
