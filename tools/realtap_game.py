"""D43 / T14 evidence: play one whole game with real taps on the control window only (pico-cli interact), no debug
commands. Strategy: [掷骰] when it is enabled; [走 · …] when only one move; otherwise bear off > capture > rosette > the most
advanced piece, then [走这颗].

  DEV=emulator-5560 python3 tools/realtap_game.py LOG.txt"""
import json, os, subprocess, sys, time

DEV = os.environ.get("DEV", "emulator-5560")
PKG = "io.embodit.royalur"
log = open(sys.argv[1], "a", encoding="utf-8")


def query():
    r = subprocess.run(["pico-cli", "interact", "query", "-d", DEV, "--package", PKG, "--compact"], capture_output=True, text=True)
    try:
        return json.loads(r.stdout)
    except ValueError:
        return {}


def click(node, name):
    subprocess.run(["pico-cli", "interact", "perform", "-d", DEV, "--package", PKG, "--node", node, "--action-id", "16"], capture_output=True)
    log.write(f"{time.strftime('%H:%M:%S')} click {name}\n"); log.flush()


PICK = ("上场", "往前", "吃掉对面", "踩花再掷", "正好出局", "Enter", "Forward", "Capture", "Rosette", "Bear off")
taps = 0
t0 = time.time()
while time.time() - t0 < 3600:
    q = query()
    controls = {c["name"]: c for c in q.get("controls", [])}
    content = " ".join(c.get("name", "") for c in q.get("content", []))
    if any(w in content for w in ("你赢了", "这盘对面赢了", "近方赢了", "远方赢了", "You won", "They won this one", "side wins")):
        log.write("RESULT " + content[:200] + f"\ntaps={taps}\n"); break
    hit = None
    for name, c in controls.items():
        if name.startswith("掷骰") or name.startswith("走 · ") or name == "走这颗":
            hit = (c["node"], name); break
    if hit is None:
        picks = [(name, c) for name, c in controls.items() if name.startswith(PICK)]
        # a sensible player: bear off, capture, rosette, else the most advanced piece (buttons run back to front)
        for pref in ("正好出局", "Bear off", "吃掉对面", "Capture", "踩花再掷", "Rosette"):
            for name, c in picks:
                if name.startswith(pref):
                    hit = (c["node"], name); break
            if hit: break
        if hit is None and picks:
            name, c = picks[-1]
            hit = (c["node"], name)
    if hit:
        click(*hit); taps += 1; time.sleep(1.3)
    else:
        time.sleep(1.0)
print(open(sys.argv[1]).read()[-400:])
