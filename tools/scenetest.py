"""¿Se puede reconocer «mismo fondo, persona movida» sin confundir fotos distintas?"""
import os, random, numpy as np, cv2
DS='/tmp/claude-0/faces/repo/tests/unit/dataset'
rnd=random.Random(3)
files=[f for f in sorted(os.listdir(DS)) if f.endswith('.jpg')]
imgs={f:cv2.cvtColor(cv2.imread(os.path.join(DS,f)),cv2.COLOR_BGR2RGB) for f in files}

def gray(im): return cv2.cvtColor(im,cv2.COLOR_RGB2GRAY)
def pool(g,cols,rows): return cv2.resize(g.astype(np.float32),(cols,rows),interpolation=cv2.INTER_AREA)
def dhash(im):   # igual que la app: 9x8 y 8x9
    g=gray(cv2.resize(im,(160,int(160*im.shape[0]/im.shape[1]))))
    a=pool(g,9,8); b=pool(g,8,9)
    return np.concatenate([(a[:,:-1]>a[:,1:]).ravel(),(b[:-1,:]>b[1:,:]).ravel()])
def grid(im, n=16):
    g=pool(gray(cv2.resize(im,(160,int(160*im.shape[0]/im.shape[1])))),n,n)
    return ((g-g.mean())/(g.std()+1e-6))
def scene_match(a,b,tol=0.35):
    """Fracción de celdas iguales, permitiendo que la cámara se mueva 1 celda."""
    best=0
    for dx in (-1,0,1):
        for dy in (-1,0,1):
            A=a[max(0,dy):a.shape[0]+min(0,dy), max(0,dx):a.shape[1]+min(0,dx)]
            B=b[max(0,-dy):b.shape[0]+min(0,-dy), max(0,-dx):b.shape[1]+min(0,-dx)]
            best=max(best,(np.abs(A-B)<tol).mean())
    return best

def variant(im, other):
    """Misma foto de fondo, con otra persona/objeto pegado en otro sitio y la cámara un poco movida."""
    h,w=im.shape[:2]; out=im.copy()
    o=cv2.resize(other,(int(w*rnd.uniform(0.3,0.5)),int(h*rnd.uniform(0.35,0.6))))
    x=rnd.randint(0,w-o.shape[1]); y=rnd.randint(0,h-o.shape[0]); out[y:y+o.shape[0],x:x+o.shape[1]]=o
    M=np.float32([[1,0,rnd.uniform(-.03,.03)*w],[0,1,rnd.uniform(-.03,.03)*h]])
    out=cv2.warpAffine(out,M,(w,h),borderMode=cv2.BORDER_REFLECT)
    return np.clip(out.astype(np.float32)*rnd.uniform(.88,1.12),0,255).astype(np.uint8)

pos=[]; neg=[]; pos_h=[]; neg_h=[]
for f in files:
    a=imgs[f]; b=variant(a, imgs[rnd.choice(files)])
    pos.append(scene_match(grid(a),grid(b))); pos_h.append(int((dhash(a)!=dhash(b)).sum()))
for _ in range(600):
    f,g2=rnd.sample(files,2)
    neg.append(scene_match(grid(imgs[f]),grid(imgs[g2]))); neg_h.append(int((dhash(imgs[f])!=dhash(imgs[g2])).sum()))
pos,neg=np.array(pos),np.array(neg)
print(f"huella actual (bits distintos de 128): misma escena media {np.mean(pos_h):.0f} · fotos distintas media {np.mean(neg_h):.0f}")
print(f"  la app (umbral 12, normal) agrupa: misma escena {(np.array(pos_h)<=12).mean()*100:.0f}% · distintas {(np.array(neg_h)<=12).mean()*100:.1f}%")
print(f"  con «Ráfagas» (20): misma escena {(np.array(pos_h)<=20).mean()*100:.0f}% · distintas {(np.array(neg_h)<=20).mean()*100:.1f}%")
print(f"escena (celdas iguales): misma escena media {pos.mean():.2f} min {pos.min():.2f} · distintas media {neg.mean():.2f} max {neg.max():.2f}")
for t in [0.50,0.55,0.60,0.65,0.70]:
    print(f"  umbral {t:.2f}: detecta {(pos>=t).mean()*100:5.1f}% de misma escena · confunde {(neg>=t).mean()*100:4.1f}% de distintas")
ph,nh=np.array(pos_h),np.array(neg_h)
print("combinado (escena ≥ t y huella ≤ H):")
for t in [0.50,0.55]:
    for H in [36,40,44]:
        print(f"  t={t:.2f} H={H}: detecta {((pos>=t)&(ph<=H)).mean()*100:5.1f}% · confunde {((neg>=t)&(nh<=H)).mean()*100:4.1f}%")
