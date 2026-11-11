"""Compara el modelo de caras actual (w600k_mbf) con el grande (w600k_r50) en fotos degradadas."""
import runpy, sys, io, contextlib, os, time, csv, numpy as np, cv2, onnxruntime as ort
sys.argv = ['x', '/tmp/claude-0/models', '/tmp/claude-0/faces/repo/tests/unit/dataset']
with contextlib.redirect_stdout(io.StringIO()):
    g = runpy.run_path('/tmp/claude-0/-home-claude/6361cb35-0596-5e1e-8ac0-d30acf3c396a/scratchpad/facetest.py')
detect, load, similarity, TEMPLATE, DS = g['detect'], g['load'], g['similarity'], g['TEMPLATE'], g['DS']

def sess(p, threads=4):
    o = ort.SessionOptions(); o.intra_op_num_threads = threads; o.log_severity_level = 3
    return ort.InferenceSession(p, o, providers=['CPUExecutionProvider'])
MODELS = {'actual (mbf, 13 MB)': sess('/tmp/claude-0/models/w600k_mbf.onnx'),
          'grande (r50, 174 MB)': sess('/tmp/claude-0/models_l/w600k_r50.onnx')}

def emb(model, img, kps, flip):
    face = cv2.warpAffine(img, similarity(kps, TEMPLATE), (112, 112), flags=cv2.INTER_LINEAR)
    v = 0
    for f in ([face, face[:, ::-1]] if flip else [face]):
        b = ((f.astype(np.float32) - 127.5) / 127.5).transpose(2, 0, 1)[None]
        v = v + model.run(None, {model.get_inputs()[0].name: b})[0][0]
    return v / np.linalg.norm(v)

rng = np.random.RandomState(0)
def degrade(img, kps, kind):
    k = kps.copy(); im = img.copy()
    if kind in ('pequeña', 'todo'):
        eyes = np.hypot(k[2] - k[0], k[3] - k[1]); s = 16.0 / eyes
        if s < 1:
            im = cv2.resize(im, (max(1, int(im.shape[1] * s)), max(1, int(im.shape[0] * s))), interpolation=cv2.INTER_AREA); k = k * s
    if kind == 'borrosa': im = cv2.GaussianBlur(im, (0, 0), 2.5)
    if kind in ('oscura', 'todo'):
        im = np.clip(im.astype(np.float32) * 0.28 + rng.normal(0, 7, im.shape), 0, 255).astype(np.uint8)
    if kind in ('jpeg', 'todo'):
        ok, enc = cv2.imencode('.jpg', cv2.cvtColor(im, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 15])
        im = cv2.cvtColor(cv2.imdecode(enc, 1), cv2.COLOR_BGR2RGB)
    if kind == 'girada':   # cabeza girada simulada: comprimir la mitad derecha de la cara
        pass
    return im, k

base = {}
for f in sorted(os.listdir(DS)):
    if not f.endswith('.jpg') or f in ('couple.jpg', 'selfie-many-people.jpg'): continue
    im = load(os.path.join(DS, f)); d = detect(im)
    if d: base[f] = (im, max(d, key=lambda t: (t[0][2] - t[0][0]) * (t[0][3] - t[0][1]))[1])
pairs = []
with open(os.path.join(DS, 'master.csv')) as fh:
    for r in csv.DictReader(fh):
        a, b = os.path.basename(r['file_x']), os.path.basename(r['file_y'])
        if a in base and b in base: pairs.append((a, b, r['Decision'] == 'Yes'))
lab = np.array([p[2] for p in pairs])

def score(sims):
    """% de parejas «misma persona» reconocidas con a lo sumo un 1 % de confusiones, y mejor acierto global."""
    neg = np.sort(sims[~lab]); t = neg[int(len(neg) * 0.99) - 1] if len(neg) else 0
    tar = (sims[lab] > t).mean()
    acc = max(((sims > th) == lab).mean() for th in np.arange(-0.2, 0.9, 0.01))
    return tar, acc, sims[lab].mean(), sims[~lab].max()

print(f"{len(base)} fotos · {len(pairs)} parejas ({lab.sum()} iguales)\n")
for kind in ['limpia', 'pequeña', 'borrosa', 'oscura', 'jpeg', 'todo']:
    for name, m in MODELS.items():
        for flip in ([False, True] if kind in ('limpia', 'todo') else [True]):
            E_clean = {f: emb(m, *base[f], flip) for f in base}
            E_bad = {f: emb(m, *degrade(*base[f], kind), flip) for f in base} if kind != 'limpia' else E_clean
            sims = np.array([float(E_clean[a] @ E_bad[b]) for a, b, _ in pairs])   # buena contra mala
            tar, acc, ms, mx = score(sims)
            print(f"{kind:8s} · {name:20s} · espejo {'sí' if flip else 'no'}: reconoce {tar*100:5.1f}% · acierto {acc*100:5.1f}% · misma media {ms:.2f} · distintas máx {mx:.2f}")
    print()

# Velocidad por cara (CPU de este ordenador; en el móvil la proporción es parecida)
im, k = base[next(iter(base))]
face = cv2.warpAffine(im, similarity(k, TEMPLATE), (112, 112))
b = ((face.astype(np.float32) - 127.5) / 127.5).transpose(2, 0, 1)[None]
for threads in (1, 4):
    for name, path in [('actual', '/tmp/claude-0/models/w600k_mbf.onnx'), ('grande', '/tmp/claude-0/models_l/w600k_r50.onnx')]:
        s = sess(path, threads); s.run(None, {s.get_inputs()[0].name: b})
        t = time.time()
        for _ in range(20): s.run(None, {s.get_inputs()[0].name: b})
        print(f"velocidad {name:6s} ({threads} hilo{'s' if threads > 1 else ''}): {(time.time() - t) / 20 * 1000:.1f} ms por pasada")
