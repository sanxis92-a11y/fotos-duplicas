import runpy, sys, io, contextlib, os, random, numpy as np, cv2
sys.argv=['x','/tmp/claude-0/models','/tmp/claude-0/faces/repo/tests/unit/dataset']
with contextlib.redirect_stdout(io.StringIO()):
    g=runpy.run_path('facetest.py')
detect,load,DS,emb,embed=g['detect'],g['load'],g['DS'],g['emb'],g['embed']
rnd=random.Random(1)
# recortes de cara de cada foto
crops=[]
for f in sorted(emb):
    if f in ('couple.jpg','selfie-many-people.jpg'): continue
    im=cv2.cvtColor(cv2.imread(os.path.join(DS,f)),cv2.COLOR_BGR2RGB)
    d=detect(im)
    if not d: continue
    b=max(d,key=lambda t:(t[0][2]-t[0][0])*(t[0][3]-t[0][1]))[0]
    w=b[2]-b[0]; cx=(b[0]+b[2])/2; cy=(b[1]+b[3])/2; s=w*1.6
    x1,y1=int(max(0,cx-s/2)),int(max(0,cy-s/2)); x2,y2=int(min(im.shape[1],cx+s/2)),int(min(im.shape[0],cy+s/2))
    crops.append((f,im[y1:y2,x1:x2]))
print("recortes:",len(crops))
def scene(face_px, n=12, W=4000, H=3000):
    bg=np.full((H,W,3),120,np.uint8); bg=cv2.GaussianBlur((np.random.RandomState(face_px).rand(H,W,3)*255).astype(np.uint8),(0,0),25)
    placed=[]
    for i in range(n):
        f,c=crops[(face_px+i)%len(crops)]
        sz=int(face_px*1.6); c2=cv2.resize(c,(sz,sz))
        x=rnd.randint(0,W-sz); y=rnd.randint(0,H-sz)
        bg[y:y+sz,x:x+sz]=c2; placed.append((f,x+sz/2,y+sz/2))
    return bg,placed
def run_detect(img, mode):
    h,w=img.shape[:2]
    if mode=='actual':      # app v1.6: foto a 1024 y detector a 640
        s=1024/max(h,w); im=cv2.resize(img,(int(w*s),int(h*s)),interpolation=cv2.INTER_AREA)
        return [(b/s,k/s,sc) for b,k,sc in detect(im)]
    if mode=='tiles':       # nuevo: foto a 1024 + mosaico 2x2 solapado de la foto a 2048
        out=run_detect(img,'actual')
        s=2048/max(h,w); im=cv2.resize(img,(int(w*s),int(h*s)),interpolation=cv2.INTER_AREA)
        H,W=im.shape[:2]; tw,th=int(W*0.6),int(H*0.6)
        for ox in (0,W-tw):
            for oy in (0,H-th):
                for b,k,sc in detect(im[oy:oy+th,ox:ox+tw]):
                    b=b.copy(); k=k.copy(); b[[0,2]]+=ox; b[[1,3]]+=oy; k[0::2]+=ox; k[1::2]+=oy
                    out.append((b/s,k/s,sc))
        # NMS global
        out.sort(key=lambda t:-t[2]); keep=[]
        for b,k,sc in out:
            ok=True
            for b2,_,_ in keep:
                iw=max(0,min(b[2],b2[2])-max(b[0],b2[0])); ih=max(0,min(b[3],b2[3])-max(b[1],b2[1]))
                inter=iw*ih; u=(b[2]-b[0])*(b[3]-b[1])+(b2[2]-b2[0])*(b2[3]-b2[1])-inter
                if inter/u>0.4: ok=False;break
            if ok: keep.append((b,k,sc))
        return keep
for face_px in [40,60,90,140,250]:
    img,placed=scene(face_px)
    for mode in ['actual','tiles']:
        dets=run_detect(img,mode)
        hit=0; same=0
        for f,cx,cy in placed:
            m=[d for d in dets if d[0][0]<=cx<=d[0][2] and d[0][1]<=cy<=d[0][3]]
            if m:
                hit+=1
                v,_=embed(img,m[0][1])
                if float(v@emb[f])>0.38: same+=1
        print(f"cara de {face_px:3d}px en foto 4000x3000 · {mode:6s}: detectadas {hit}/{len(placed)} · reconocidas {same}/{len(placed)}")
