"""Drive the local lab gateway's REST API with an admin session.

    python ignition-lab/lab.py import <tags.json> [--path P] [--provider default]
    python ignition-lab/lab.py export <out.json> [--path P] [--provider default]
    python ignition-lab/lab.py call <METHOD> <api-path> [body.json]

Logs in through the gateway's own IdP challenge (the login the web UI does) and sends the session's
CSRF token, so no API key is needed. Reads GATEWAY_ADMIN_PASSWORD from ignition-lab/.env.
For the local lab gateway on 127.0.0.1 only.
"""
import argparse, http.cookiejar, json, pathlib, sys, urllib.error, urllib.parse, urllib.request

BASE = "http://127.0.0.1:8088"
HERE = pathlib.Path(__file__).parent

jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
csrf = None


def env():
    vals = {}
    for line in (HERE / ".env").read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            vals[k.strip()] = v.strip()
    return vals


def call(method, path, body=None, content_type="application/json"):
    data = body if isinstance(body, (bytes, type(None))) else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", content_type)
    if csrf:
        req.add_header("X-CSRF-Token", csrf)
    try:
        with opener.open(req) as r:
            return r.geturl(), r.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} -> {e.code}: {e.read()[:1000].decode('utf-8', 'replace')}")


def login():
    global csrf
    e = env()
    url, _ = call("GET", "/data/app/login")
    q = urllib.parse.parse_qs(urllib.parse.urlparse(url).query)
    token = q["token"][0]
    token = json.loads(call("POST", "/idp/default/authn/next-challenge", {"token": token})[1]).get("token", token)
    body = {"token": token, "rememberMe": False,
            "challenge": {"username": e.get("GATEWAY_ADMIN_USERNAME", "admin"), "password": e["GATEWAY_ADMIN_PASSWORD"]}}
    token = json.loads(call("POST", "/idp/default/authn/submit-challenge/basic", body)[1]).get("token", token)
    nxt = json.loads(call("POST", "/idp/default/authn/next-challenge", {"token": token})[1])
    if not nxt.get("complete"):
        sys.exit(f"login not complete: {nxt}")
    q["token"] = [nxt.get("token", token)]
    call("GET", "/idp/default/oidc/auth?" + urllib.parse.urlencode({k: v[0] for k, v in q.items()}))
    csrf = json.loads(call("GET", "/data/app/session")[1])["csrfToken"]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("cmd", choices=["import", "export", "call"])
    p.add_argument("args", nargs="*")
    p.add_argument("--path", default="")
    p.add_argument("--provider", default="default")
    a = p.parse_args()
    login()
    if a.cmd == "import":
        q = urllib.parse.urlencode({"provider": a.provider, "path": a.path, "type": "json", "collisionPolicy": "Overwrite"})
        _, out = call("POST", "/data/api/v1/tags/import?" + q, pathlib.Path(a.args[0]).read_bytes(), "application/octet-stream")
        print(out.decode("utf-8"))
    elif a.cmd == "export":
        q = urllib.parse.urlencode({"provider": a.provider, "path": a.path, "type": "json"})
        _, out = call("GET", "/data/api/v1/tags/export?" + q)
        pathlib.Path(a.args[0]).write_bytes(out)
        print(f"wrote {a.args[0]} ({len(out)} bytes)")
    else:
        body = json.loads(pathlib.Path(a.args[2]).read_text(encoding="utf-8")) if len(a.args) > 2 else None
        _, out = call(a.args[0], a.args[1], body)
        print(out.decode("utf-8"))


if __name__ == "__main__":
    main()
