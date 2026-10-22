#!/usr/bin/env python3
"""
Trawl's ZERO-NETWORK release gate (desktop).

Trawl promises that nothing leaves the computer, so the packaged desktop app must make no network connection at all. This gate runs the app
the way a person's session would (first run, models and Privacy pack from files, indexing a made-up folder of photos, screenshots, documents,
video and audio, searches, the privacy-blur scoring pass, the quick panel, Settings, Licences, diagnostics) for at least ten minutes while
something OUTSIDE the process watches every socket of the process and of every child it starts. It FAILS (exit 1) when any of them
connects, sends, binds, listens or accepts on an AF_INET or AF_INET6 socket, loopback included. The only thing it lets pass is a socket that
was created and closed without ever being used (the JVM's libnet probes IPv6 support that way at start-up); those are counted and printed.

  Linux    strace -f on the whole process tree (every socket, connect, bind, send, receive, listen, accept, close syscall).
  macOS    lsof -i polling of the whole process tree every 0.25 s, plus nettop (external interfaces) every second.
  Windows  desktop/scripts/net-gate-windows.ps1 (Get-NetTCPConnection / Get-NetUDPEndpoint polling by owning process); this file reads its CSV.

How to run it, before every release, on each OS: desktop/scripts/NET-GATE.md. The scripted session is the app's own (desktop/.../ui/NetGate.kt,
switched on by TRAWL_NETGATE_DIR); it drives the controller, it never clicks and never moves the pointer.

  net-gate.py run  --app <launcher> --corpus <dir> --models <zip> --privacy <zip> --home <scratch dir> --out <dir> [--minutes 10]
  net-gate.py analyse-strace <strace.log>
  net-gate.py analyse-poll   <poll.csv>          (macOS/Windows polling rows: see the header of that file)
  net-gate.py selftest
"""
import argparse, csv, json, os, platform, re, shutil, signal, subprocess, sys, threading, time

MIN_SAFE_JAVA = (21, 0, 8)

# ---------------------------------------------------------------------------------------------------------------- strace analysis

LINE = re.compile(r'^(?P<pid>\d+)\s+(?:(?P<ts>\d\d:\d\d:\d\d(?:\.\d+)?)\s+)?(?P<body>.*)$')
CALL = re.compile(r'^(?P<name>[a-z_0-9]+)\((?P<rest>.*)$')
RESUMED = re.compile(r'^<\.\.\. (?P<name>[a-z_0-9]+) resumed>(?P<rest>.*)$')
RESULT = re.compile(r'\)\s*=\s*(?P<res>-?\d+|\?)(?:<(?P<fdinfo>[^>]*(?:>[^>]*)*)>)?')
INET_FD = re.compile(r'^(\d+)<(TCP|UDP|TCPv6|UDPv6|RAW|RAWv6|UDPLITE|UDPLITEv6|SCTP|SCTPv6|DCCP|DCCPv6|PING|PINGv6)[:>\[]')
ADDR4 = re.compile(r'sa_family=AF_INET,\s*sin_port=htons\((\d+)\),\s*sin_addr=inet_addr\("([^"]+)"\)')
ADDR6 = re.compile(r'sa_family=AF_INET6,.*?sin6_port=htons\((\d+)\).*?inet_pton\(AF_INET6,\s*"([^"]+)"')
USES = {'connect', 'bind', 'listen', 'accept', 'accept4', 'sendto', 'sendmsg', 'sendmmsg', 'recvfrom', 'recvmsg', 'recvmmsg', 'shutdown'}


def analyse_strace(path):
    """-> dict(violations=[...], probes=[...], unix=int, sockets=int). One strace -f -yy log."""
    pending = {}   # pid -> (name, rest) of an unfinished call
    created = {}   # (pid, fd) -> creation text, for sockets that are INET
    used = set()
    violations, probes_created, unix = [], [], 0
    sockets = 0

    def handle(pid, name, rest, line):
        nonlocal unix, sockets
        res = RESULT.search(rest)
        if name == 'socket':
            m = re.match(r'(AF_[A-Z0-9_]+)', rest)
            fam = m.group(1) if m else '?'
            sockets += 1
            if fam in ('AF_INET', 'AF_INET6'):
                fd = res.group('res') if res else '?'
                probes_created.append({'pid': pid, 'family': fam, 'fd': fd, 'line': line.strip()})
                created[(pid, fd)] = len(probes_created) - 1
            elif fam == 'AF_UNIX':
                unix += 1
            return
        if name == 'close':
            return
        if name not in USES:
            return
        first = rest.split(',', 1)[0].split(')', 1)[0]
        inet = INET_FD.match(first)
        addr = ADDR4.search(rest) or ADDR6.search(rest)
        if inet or addr:
            target = f'{addr.group(2)}:{addr.group(1)}' if addr else first
            violations.append({'pid': pid, 'call': name, 'target': target, 'line': line.strip()[:300]})

    with open(path, errors='replace') as fh:
        for raw in fh:
            m = LINE.match(raw)
            if not m:
                continue
            pid, body = m.group('pid'), m.group('body').strip()
            r = RESUMED.match(body)
            if r:
                prev = pending.pop(pid, None)
                if prev is not None:
                    handle(pid, prev[0], prev[1] + ' ' + r.group('rest'), prev[1] + ' ' + r.group('rest'))
                continue
            c = CALL.match(body)
            if not c:
                continue
            if body.rstrip().endswith('<unfinished ...>'):
                pending[pid] = (c.group('name'), c.group('rest').replace('<unfinished ...>', ''))
                continue
            handle(pid, c.group('name'), c.group('rest'), body)

    # An INET socket that any syscall used is in `violations`; the sockets that were only ever created (and closed) are the JVM's start-up probes.
    return {'violations': violations, 'inet_sockets_created': probes_created, 'unix_sockets': unix, 'sockets': sockets}


# ------------------------------------------------------------------------------------------------------------- polling analysis
# CSV columns (written by the macOS poller below and by net-gate-windows.ps1):  time,pid,process,proto,local,remote,state

def analyse_poll(path):
    rows, violations = {}, []
    with open(path, newline='', errors='replace') as fh:
        for r in csv.DictReader(fh):
            if not r.get('pid'):
                continue
            key = (r['pid'], r['proto'], r['local'], r['remote'], r['state'])
            if key in rows:
                continue
            rows[key] = r
            remote = (r.get('remote') or '').strip()
            state = (r.get('state') or '').upper()
            proto = (r.get('proto') or '').upper()
            unbound = state in ('CLOSED', 'BOUND', '') and remote in ('', '*:*', '0.0.0.0:0', ':::0', '0.0.0.0', '::') and proto.startswith('TCP')
            if not unbound:
                violations.append({'pid': r['pid'], 'process': r.get('process', ''), 'proto': proto, 'local': r['local'], 'remote': remote, 'state': state, 'first_seen': r.get('time', '')})
    return {'violations': violations, 'distinct_rows': len(rows)}


# ------------------------------------------------------------------------------------------------------------------ process tree

def children_of(root):
    out = subprocess.run(['ps', '-axo', 'pid=,ppid='], capture_output=True, text=True).stdout
    kids = {}
    for ln in out.splitlines():
        p = ln.split()
        if len(p) == 2:
            kids.setdefault(int(p[1]), []).append(int(p[0]))
    seen, todo = set(), [root]
    while todo:
        n = todo.pop()
        if n in seen:
            continue
        seen.add(n)
        todo.extend(kids.get(n, []))
    return sorted(seen)


class MacPoller(threading.Thread):
    """Polls lsof (sockets) and nettop (external bytes) for the app's whole process tree until stopped."""

    def __init__(self, root, csv_path, nettop_path):
        super().__init__(daemon=True)
        self.root, self.csv_path, self.nettop_path = root, csv_path, nettop_path
        self.stop = threading.Event()
        self.polls = 0
        self.max_tree = 0

    def run(self):
        with open(self.csv_path, 'w', newline='') as fh, open(self.nettop_path, 'w') as nt:
            w = csv.writer(fh)
            w.writerow(['time', 'pid', 'process', 'proto', 'local', 'remote', 'state'])
            last_nettop = 0.0
            while not self.stop.is_set():
                pids = children_of(self.root)
                self.max_tree = max(self.max_tree, len(pids))
                if pids:
                    out = subprocess.run(['lsof', '-nP', '-a', '-i', '-p', ','.join(map(str, pids)), '-F', 'pcPnT'], capture_output=True, text=True).stdout
                    pid = cmd = proto = name = None
                    state = ''
                    flush = False
                    for ln in out.splitlines():
                        tag, val = ln[:1], ln[1:]
                        if tag == 'p':
                            pid = val
                        elif tag == 'c':
                            cmd = val
                        elif tag == 'f':
                            proto = name = None
                            state = ''
                        elif tag == 'P':
                            proto = val
                        elif tag == 'n':
                            name = val
                        elif tag == 'T' and val.startswith('ST='):
                            state = val[3:]
                        if tag in ('n',) and name is not None:
                            loc, _, rem = name.partition('->')
                            w.writerow([time.strftime('%H:%M:%S'), pid, cmd, proto, loc, rem, state])
                    fh.flush()
                now = time.time()
                if pids and now - last_nettop >= 1.0:
                    last_nettop = now
                    args = ['nettop', '-x', '-t', 'external', '-L', '1', '-J', 'bytes_in,bytes_out']
                    for p in pids:
                        args += ['-p', str(p)]
                    out = subprocess.run(args, capture_output=True, text=True).stdout
                    nt.write(f'--- {time.strftime("%H:%M:%S")} pids={pids}\n{out}\n')
                    nt.flush()
                self.polls += 1
                self.stop.wait(0.25)


def nettop_violations(path):
    """Any process of the tree with bytes in or out on an external interface."""
    bad = []
    with open(path, errors='replace') as fh:
        for ln in fh:
            if ln.startswith('---'):
                continue   # our own "--- time pids=[1, 2, 3]" marker (its commas must not be read as numbers)
            parts = ln.strip().split(',')   # "name.pid,bytes_in,bytes_out," and, below it, "tcp4 local<->remote,bytes_in,bytes_out,"
            if len(parts) >= 3 and parts[0]:
                try:
                    bi, bo = int(parts[1]), int(parts[2])
                except ValueError:
                    continue   # the header and the "--- time" lines
                if bi or bo:
                    bad.append(ln.strip())
    return bad


# ------------------------------------------------------------------------------------------------------------------------- the run

def java_tuple(v):
    nums = [int(x) for x in re.findall(r'\d+', v.split('+')[0])][:3]
    return tuple(nums + [0] * (3 - len(nums)))


def run_app(argv, env, out, minutes, os_name):
    """Starts the app under the observer for this OS and waits for its own scripted session to finish (or the time limit)."""
    os.makedirs(out, exist_ok=True)
    stderr_path = os.path.join(out, 'app-stderr.log')
    limit = minutes * 60 + 15 * 60   # the session itself takes minutes to index; keep a hard ceiling
    started = time.time()
    if os_name == 'linux':
        strace_log = os.path.join(out, 'strace.log')
        cmd = ['strace', '-f', '-yy', '-tt', '-s', '120', '-e', 'trace=%network,close', '-o', strace_log] + argv
        proc = subprocess.Popen(cmd, env=env, stderr=open(stderr_path, 'w'), stdout=subprocess.DEVNULL, start_new_session=True)
        poller = None
    else:
        proc = subprocess.Popen(argv, env=env, stderr=open(stderr_path, 'w'), stdout=subprocess.DEVNULL, start_new_session=True)
        poller = MacPoller(proc.pid, os.path.join(out, 'poll.csv'), os.path.join(out, 'nettop.txt'))
        poller.start()
    timed_out = False
    try:
        while proc.poll() is None:
            if time.time() - started > limit:
                timed_out = True
                break
            time.sleep(2)
    finally:
        if proc.poll() is None:
            proc.terminate()   # by PID, never a pattern
            try:
                proc.wait(20)
            except subprocess.TimeoutExpired:
                proc.kill()
        if poller:
            poller.stop.set()
            poller.join(5)
    return {'wall_s': round(time.time() - started, 1), 'timed_out': timed_out, 'exit_code': proc.returncode, 'polls': getattr(poller, 'polls', None),
            'max_tree': getattr(poller, 'max_tree', None)}


def canary(out, os_name):
    """A positive control: the same observer must catch a process that really tries to connect (a TEST-NET address, which goes nowhere)."""
    prog = "import socket,time;s=socket.socket();s.settimeout(2.5);s.connect_ex(('203.0.113.7',443));time.sleep(0.3)"
    cdir = os.path.join(out, 'canary')
    os.makedirs(cdir, exist_ok=True)
    if os_name == 'linux':
        log = os.path.join(cdir, 'strace.log')
        subprocess.run(['strace', '-f', '-yy', '-tt', '-s', '120', '-e', 'trace=%network,close', '-o', log, sys.executable, '-c', prog], timeout=30)
        a = analyse_strace(log)
        return len(a['violations']) > 0, a['violations'][:2]
    p = subprocess.Popen([sys.executable, '-c', prog])
    poller = MacPoller(p.pid, os.path.join(cdir, 'poll.csv'), os.path.join(cdir, 'nettop.txt'))
    poller.start()
    p.wait(30)
    time.sleep(0.6)
    poller.stop.set()
    poller.join(5)
    a = analyse_poll(os.path.join(cdir, 'poll.csv'))
    return len(a['violations']) > 0, a['violations'][:2]


def cmd_run(a):
    os_name = {'Linux': 'linux', 'Darwin': 'mac', 'Windows': 'windows'}[platform.system()]
    if os_name == 'windows':
        sys.exit('on Windows run desktop\\scripts\\net-gate-windows.ps1')
    home = os.path.abspath(a.home)
    out = os.path.abspath(a.out)
    shutil.rmtree(home, ignore_errors=True)
    os.makedirs(os.path.join(home, 'trawl'), exist_ok=True)
    os.makedirs(os.path.join(home, 'user'), exist_ok=True)
    # The global shortcut is Off in the scratch settings (it would grab a key on a real desktop); everything else is the first-run default.
    with open(os.path.join(home, 'trawl', 'shell.json'), 'w') as fh:
        fh.write('{"hotkeyOn": false, "keepRunning": false}')
    env = dict(os.environ)
    # The JVM takes user.home from the account database, NOT from $HOME, so without -Duser.home the app would index the real person's
    # Documents/Pictures/Movies/Music as its first-run defaults. JAVA_TOOL_OPTIONS points it at the scratch folder, and the app's gate
    # driver refuses to run unless user.home really is that folder (TRAWL_NETGATE_HOME).
    scratch_user = os.path.join(home, 'user')
    env.update({'TRAWL_HOME': os.path.join(home, 'trawl'), 'HOME': scratch_user, 'TRAWL_NETGATE_HOME': scratch_user,
                'JAVA_TOOL_OPTIONS': f'-Duser.home={scratch_user}', 'TRAWL_NETGATE_DIR': os.path.abspath(a.corpus),
                'TRAWL_NETGATE_MODELS': os.path.abspath(a.models), 'TRAWL_NETGATE_PRIVACY': os.path.abspath(a.privacy), 'TRAWL_NETGATE_MINUTES': str(a.minutes)})
    for k in ('ORT_DISABLE_TELEMETRY',):
        env.pop(k, None)   # the app must make itself safe; the gate does not do it for it
    print(f'gate: {os_name}, app {a.app}, {a.minutes} min, scratch {home}', flush=True)
    print('gate: positive control (an observer that cannot see a connect is worthless) ...', flush=True)
    caught, sample = canary(out, os_name)
    print(f'gate: canary caught={caught} {sample}', flush=True)
    t = run_app([a.app], env, out, a.minutes, os_name)
    verdict = judge(os_name, out, home, a.minutes, t, caught)
    print(json.dumps(verdict, indent=2))
    with open(os.path.join(out, 'verdict.json'), 'w') as fh:
        json.dump(verdict, fh, indent=2)
    for rel in ('netgate-result.json', os.path.join('logs', 'trawl.log')):
        src = os.path.join(home, 'trawl', rel)
        if os.path.isfile(src):
            shutil.copy(src, os.path.join(out, os.path.basename(rel)))
    shutil.rmtree(home, ignore_errors=True)   # made-up data only, but it is scratch: gone when the gate is
    sys.exit(0 if verdict['pass'] else 1)


def check_result(res_path, minutes):
    """The scripted session must have finished, been long enough and run on the packaged runtime, or "no connection" proves nothing."""
    problems, result = [], None
    if os.path.isfile(res_path):
        result = json.load(open(res_path))
        facts = result['facts']
        bad = [x['step'] for x in result['steps'] if not x['ok']]
        if bad:
            problems.append('session steps failed: ' + ', '.join(bad))
        if facts.get('ran_s', 0) < minutes * 60 - 5:
            problems.append('the session was shorter than the required minutes')
        jv = java_tuple(facts.get('java_runtime', ''))
        if jv < MIN_SAFE_JAVA:
            problems.append(f"packaged Java runtime {facts.get('java_runtime')} is older than {'.'.join(map(str, MIN_SAFE_JAVA))}")
        if 'runtime' not in facts.get('java_home', '').replace('\\', '/').lower():
            problems.append(f"the app is not running on its packaged runtime (java.home = {facts.get('java_home')})")
        if not facts.get('home_isolated'):
            problems.append('the app was not confined to the scratch home (user.home is not the scratch folder): made-up files only')
        if not facts.get('diagnostics_has_runtime'):
            problems.append('Copy diagnostics does not name the Java runtime')
    else:
        problems.append('no netgate-result.json: the scripted session never finished')
    return problems, result


def judge(os_name, out, home, minutes, t, canary_caught):
    problems = []
    if not canary_caught:
        problems.append('the positive control was not detected: the observer is blind')
    net = {}
    if os_name == 'linux':
        s = analyse_strace(os.path.join(out, 'strace.log'))
        net = {'inet_violations': s['violations'][:20], 'inet_violation_count': len(s['violations']), 'inet_sockets_created_total': len(s['inet_sockets_created']), 'unix_sockets': s['unix_sockets']}
        if s['violations']:
            problems.append(f"{len(s['violations'])} AF_INET/AF_INET6 socket use(s)")
    else:
        s = analyse_poll(os.path.join(out, 'poll.csv'))
        nt = nettop_violations(os.path.join(out, 'nettop.txt'))
        net = {'inet_violations': s['violations'][:20], 'inet_violation_count': len(s['violations']), 'distinct_socket_rows_seen': s['distinct_rows'], 'nettop_external_byte_rows': nt[:10]}
        if s['violations']:
            problems.append(f"{len(s['violations'])} socket row(s) seen by lsof")
        if nt:
            problems.append(f'{len(nt)} nettop sample(s) with external bytes')
        if t.get('polls', 0) < 100:
            problems.append(f"only {t.get('polls')} polls: the observer barely ran")
    res_path = os.path.join(home, 'trawl', 'netgate-result.json')
    more, result = check_result(res_path, minutes)
    problems += more
    if t.get('timed_out'):
        problems.append('the app did not finish its session in time')
    # Microsoft's telemetry client leaves a trace on disk if it ran at all.
    traces = []
    for root, dirs, files in os.walk(os.path.join(home, 'user')):
        if os.path.basename(root) == 'DeveloperTools' or '.onnxruntime' in dirs:
            traces.append(root)
    if traces:
        problems.append('Microsoft DeveloperTools / .onnxruntime telemetry folder was created')
    return {'os': os_name, 'pass': not problems, 'problems': problems, 'run': t, 'network': net, 'telemetry_traces_on_disk': traces,
            'session': (result or {}).get('facts'), 'steps': [(x['step'], x['ok'], round(x['at_s'])) for x in (result or {}).get('steps', [])]}


# ----------------------------------------------------------------------------------------------------------------------------- selftest

SAMPLE_BAD = '''100 10:00:00.000000 socket(AF_INET, SOCK_STREAM|SOCK_CLOEXEC, IPPROTO_IP) = 7<TCP:[1234]>
100 10:00:00.100000 connect(7<TCP:[1234]>, {sa_family=AF_INET, sin_port=htons(443), sin_addr=inet_addr("4.150.223.113")}, 16) = -1 EINPROGRESS (Operation now in progress)
'''
SAMPLE_PROBE = '''100 10:00:00.000000 socket(AF_INET6, SOCK_STREAM, IPPROTO_IP) = 5
100 10:00:00.000100 close(5) = 0
100 10:00:00.000200 socket(AF_UNIX, SOCK_STREAM, 0) = 6
100 10:00:00.000300 connect(6<UNIX:[1]>, {sa_family=AF_UNIX, sun_path="/tmp/x"}, 10) = 0
'''
SAMPLE_SPLIT = '''200 10:00:00.000000 socket(AF_INET6, SOCK_DGRAM, IPPROTO_IP <unfinished ...>
201 10:00:00.000010 getpid() = 1
200 10:00:00.000020 <... socket resumed>) = 9<UDPv6:[5]>
200 10:00:00.000030 sendto(9<UDPv6:[5]>, "x", 1, 0, {sa_family=AF_INET6, sin6_port=htons(53), inet_pton(AF_INET6, "::1", &sin6_addr), sin6_scope_id=0}, 28) = 1
'''


def cmd_selftest():
    import tempfile
    def run(text):
        with tempfile.NamedTemporaryFile('w', suffix='.log', delete=False) as f:
            f.write(text)
        return analyse_strace(f.name)
    bad, probe, split = run(SAMPLE_BAD), run(SAMPLE_PROBE), run(SAMPLE_SPLIT)
    assert len(bad['violations']) == 1 and bad['violations'][0]['target'] == '4.150.223.113:443', bad
    assert probe['violations'] == [] and len(probe['inet_sockets_created']) == 1 and probe['unix_sockets'] == 1, probe
    assert len(split['violations']) == 1 and split['violations'][0]['call'] == 'sendto', split
    with tempfile.NamedTemporaryFile('w', suffix='.csv', delete=False) as f:
        f.write('time,pid,process,proto,local,remote,state\n10:00:00,5,Trawl,TCP,0.0.0.0:5000,4.150.223.113:443,ESTABLISHED\n10:00:01,5,Trawl,TCP,*:*,,CLOSED\n')
    p = analyse_poll(f.name)
    assert len(p['violations']) == 1 and p['violations'][0]['remote'].startswith('4.150'), p
    assert java_tuple('21.0.12.1+1-LTS') >= MIN_SAFE_JAVA and java_tuple('21+35') < MIN_SAFE_JAVA
    print('selftest ok')


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest='cmd', required=True)
    r = sub.add_parser('run')
    for n in ('app', 'corpus', 'models', 'privacy', 'home', 'out'):
        r.add_argument('--' + n, required=True)
    r.add_argument('--minutes', type=float, default=10.0)
    s = sub.add_parser('analyse-strace'); s.add_argument('log')
    p = sub.add_parser('analyse-poll'); p.add_argument('csv')
    c = sub.add_parser('check-result'); c.add_argument('json'); c.add_argument('--minutes', type=float, default=10.0)
    sub.add_parser('selftest')
    a = ap.parse_args()
    if a.cmd == 'run':
        cmd_run(a)
    elif a.cmd == 'analyse-strace':
        r = analyse_strace(a.log); print(json.dumps({k: (v if k != 'inet_sockets_created' else len(v)) for k, v in r.items()}, indent=2)); sys.exit(1 if r['violations'] else 0)
    elif a.cmd == 'analyse-poll':
        r = analyse_poll(a.csv); print(json.dumps(r, indent=2)); sys.exit(1 if r['violations'] else 0)
    elif a.cmd == 'check-result':
        pr, res = check_result(a.json, a.minutes); print(json.dumps({'problems': pr, 'facts': (res or {}).get('facts'), 'steps': [(x['step'], x['ok'], round(x['at_s'])) for x in (res or {}).get('steps', [])]}, indent=2)); sys.exit(1 if pr else 0)
    else:
        cmd_selftest()


if __name__ == '__main__':
    main()
