"""Réplica en Python del proceso de caras de la app, para medirlo con fotos etiquetadas.
Uso: python3 -I facetest.py <dir_modelos> <dir_dataset>
"""
import sys, os, csv, itertools, random
import numpy as np, cv2, onnxruntime as ort

MD, DS = sys.argv[1], sys.argv[2]
det = ort.InferenceSession(os.path.join(MD, "det_500m.onnx"), providers=["CPUExecutionProvider"])
rec = ort.InferenceSession(os.path.join(MD, "w600k_mbf.onnx"), providers=["CPUExecutionProvider"])
TEMPLATE = np.array([38.2946, 51.6963, 73.5318, 51.5014, 56.0252, 71.7366, 41.5493, 92.3655, 70.7299, 92.2041], np.float32)
DET = 640


def detect(img_rgb, thr=0.5, nms=0.4):
    h, w = img_rgb.shape[:2]
    scale = DET / max(h, w)
    nw, nh = int(round(w * scale)), int(round(h * scale))
    canvas = np.zeros((DET, DET, 3), np.float32)
    canvas[:nh, :nw] = cv2.resize(img_rgb, (nw, nh), interpolation=cv2.INTER_LINEAR)
    blob = ((canvas - 127.5) / 128.0).transpose(2, 0, 1)[None]
    outs = det.run(None, {det.get_inputs()[0].name: blob})
    boxes, scores, kpss = [], [], []
    for i, st in enumerate([8, 16, 32]):
        sc, bb, kp = outs[i][:, 0], outs[i + 3] * st, outs[i + 6] * st
        g = DET // st
        ys, xs = np.mgrid[:g, :g]
        centers = np.stack([xs, ys], -1).reshape(-1, 2).astype(np.float32) * st
        centers = np.repeat(centers, 2, axis=0)
        keep = sc >= thr
        c = centers[keep]
        b = bb[keep]
        boxes.append(np.stack([c[:, 0] - b[:, 0], c[:, 1] - b[:, 1], c[:, 0] + b[:, 2], c[:, 1] + b[:, 3]], -1))
        k = kp[keep].reshape(-1, 5, 2) + c[:, None, :]
        kpss.append(k.reshape(-1, 10))
        scores.append(sc[keep])
    boxes = np.concatenate(boxes) / scale
    kpss = np.concatenate(kpss) / scale
    scores = np.concatenate(scores)
    order = scores.argsort()[::-1]
    keep = []
    while order.size:
        i = order[0]
        keep.append(i)
        xx1 = np.maximum(boxes[i, 0], boxes[order[1:], 0]); yy1 = np.maximum(boxes[i, 1], boxes[order[1:], 1])
        xx2 = np.minimum(boxes[i, 2], boxes[order[1:], 2]); yy2 = np.minimum(boxes[i, 3], boxes[order[1:], 3])
        inter = np.maximum(0, xx2 - xx1) * np.maximum(0, yy2 - yy1)
        a = (boxes[i, 2] - boxes[i, 0]) * (boxes[i, 3] - boxes[i, 1])
        ao = (boxes[order[1:], 2] - boxes[order[1:], 0]) * (boxes[order[1:], 3] - boxes[order[1:], 1])
        order = order[1:][inter / (a + ao - inter + 1e-9) <= nms]
    return [(boxes[i], kpss[i], scores[i]) for i in keep]


def similarity(s, d):
    s = s.reshape(-1, 2); d = d.reshape(-1, 2)
    sm, dm = s.mean(0), d.mean(0)
    x, y = (s - sm).T; u, v = (d - dm).T
    ss = (x * x + y * y).sum()
    a = (x * u + y * v).sum() / ss
    b = (x * v - y * u).sum() / ss
    tx = dm[0] - (a * sm[0] - b * sm[1]); ty = dm[1] - (b * sm[0] + a * sm[1])
    return np.array([[a, -b, tx], [b, a, ty]], np.float32)


def embed(img_rgb, kps, flip=True):
    M = similarity(kps, TEMPLATE)
    face = cv2.warpAffine(img_rgb, M, (112, 112), flags=cv2.INTER_LINEAR, borderValue=0)
    imgs = [face, face[:, ::-1]] if flip else [face]
    blob = np.stack([((f.astype(np.float32) - 127.5) / 127.5).transpose(2, 0, 1) for f in imgs])
    out = rec.run(None, {rec.get_inputs()[0].name: blob})[0]
    v = out.sum(0)
    return v / np.linalg.norm(v), face


def frontal(kps):
    k = kps.reshape(5, 2)
    le, re, nose = k[0], k[1], k[2]
    ed = np.linalg.norm(re - le)
    mid = (le + re) / 2
    return abs(nose[0] - mid[0]) / (ed + 1e-6)   # 0 = de frente, >0.5 = perfil


def load(p):
    im = cv2.imread(p)
    if im is None: return None
    h, w = im.shape[:2]
    s = 1024 / max(h, w)
    if s < 1: im = cv2.resize(im, (int(w * s), int(h * s)), interpolation=cv2.INTER_AREA)
    return cv2.cvtColor(im, cv2.COLOR_BGR2RGB)


# ---------------------------------------------------------------- huellas
files = sorted(f for f in os.listdir(DS) if f.lower().endswith(".jpg"))
emb, emb_noflip, info = {}, {}, {}
for f in files:
    im = load(os.path.join(DS, f))
    if im is None: continue
    faces = detect(im)
    info[f] = len(faces)
    if not faces: continue
    b, k, sc = max(faces, key=lambda t: (t[0][2] - t[0][0]) * (t[0][3] - t[0][1]))
    emb[f], _ = embed(im, k, True)
    emb_noflip[f], _ = embed(im, k, False)
print("imágenes:", len(files), "con cara:", len(emb), "sin cara:", [f for f in files if info.get(f, 0) == 0])
print("caras en couple.jpg:", info.get("couple.jpg"), " en selfie-many-people.jpg:", info.get("selfie-many-people.jpg"))

# ---------------------------------------------------------------- parejas
pairs = []
with open(os.path.join(DS, "master.csv")) as fh:
    for r in csv.DictReader(fh):
        a, b = os.path.basename(r["file_x"]), os.path.basename(r["file_y"])
        if a in emb and b in emb: pairs.append((a, b, r["Decision"] == "Yes"))
print("parejas evaluables:", len(pairs), "iguales:", sum(p[2] for p in pairs))


def verif(E, name):
    sims = np.array([float(E[a] @ E[b]) for a, b, _ in pairs]); lab = np.array([p[2] for p in pairs])
    best = max(((t, ((sims > t) == lab).mean()) for t in np.arange(0.0, 0.9, 0.01)), key=lambda x: x[1])
    print(f"{name}: misma persona sim media {sims[lab].mean():.3f} (min {sims[lab].min():.3f}) · distintas media {sims[~lab].mean():.3f} (max {sims[~lab].max():.3f})")
    print(f"   mejor umbral {best[0]:.2f} → acierto {best[1]*100:.1f}%  ·  con 0.40: {(((sims > 0.40) == lab).mean())*100:.1f}%")
    return sims, lab


verif(emb_noflip, "sin espejo")
sims, lab = verif(emb, "con espejo")

# ---------------------------------------------------------------- identidades reales (de las parejas «Yes»)
par = {f: f for f in emb}
def find(x):
    while par[x] != x: par[x] = par[par[x]]; x = par[x]
    return x
for a, b, y in pairs:
    if y: par[find(a)] = find(b)
truth = {f: find(f) for f in emb}
ids = {}
for f, r in truth.items(): ids.setdefault(r, []).append(f)
labelled = [f for f in emb if len(ids[truth[f]]) >= 2]
print("identidades con 2+ fotos:", sum(1 for v in ids.values() if len(v) >= 2), "fotos:", len(labelled))


def pairwise(pred, fs):
    tp = fp = fn = 0
    for a, b in itertools.combinations(fs, 2):
        same_t = truth[a] == truth[b]; same_p = pred[a] == pred[b] and pred[a] is not None
        if same_t and same_p: tp += 1
        elif same_p: fp += 1
        elif same_t: fn += 1
    prec = tp / (tp + fp) if tp + fp else 1.0
    recall = tp / (tp + fn) if tp + fn else 1.0
    return prec, recall


def cluster_cw(fs, t, k=12, rounds=20):
    n = len(fs); V = np.stack([emb[f] for f in fs]); S = V @ V.T
    np.fill_diagonal(S, -2)
    nb = [set(np.argsort(-S[i])[:k][S[i, np.argsort(-S[i])[:k]] > t]) for i in range(n)]
    adj = [[(j, S[i, j]) for j in nb[i] if i in nb[j]] for i in range(n)]
    lab_ = list(range(n)); rnd = random.Random(7); order = list(range(n))
    for _ in range(rounds):
        rnd.shuffle(order)
        for i in order:
            if not adj[i]: continue
            w = {}
            for j, s in adj[i]: w[lab_[j]] = w.get(lab_[j], 0) + s
            lab_[i] = max(w, key=w.get)
    return {fs[i]: lab_[i] for i in range(n)}


def cluster_avg(fs, t):
    """Agrupamiento jerárquico por enlace medio: une grupos si la similitud MEDIA entre ellos supera t."""
    V = np.stack([emb[f] for f in fs]); S = V @ V.T
    groups = [[i] for i in range(len(fs))]
    while True:
        best, bi, bj = t, -1, -1
        for i in range(len(groups)):
            for j in range(i + 1, len(groups)):
                m = S[np.ix_(groups[i], groups[j])].mean()
                if m > best: best, bi, bj = m, i, j
        if bi < 0: break
        groups[bi] += groups[bj]; del groups[bj]
    out = {}
    for gi, g in enumerate(groups):
        for i in g: out[fs[i]] = gi
    return out


print("\nAgrupado (precisión = no mezcla · cobertura = no parte a la misma persona)")
for t in [0.30, 0.35, 0.40, 0.45, 0.50]:
    p1, r1 = pairwise(cluster_cw(labelled, t), labelled)
    p2, r2 = pairwise(cluster_avg(labelled, t), labelled)
    print(f"  umbral {t:.2f}:  vecinos+CW  prec {p1*100:5.1f}%  cob {r1*100:5.1f}%   |   enlace medio  prec {p2*100:5.1f}%  cob {r2*100:5.1f}%")

print("\nfrontalidad (0=frente):", {f: round(frontal(detect(load(os.path.join(DS, f)))[0][1]), 2) for f in list(emb)[:8]})
