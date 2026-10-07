# Exact per-tile probabilities from generateEarth() (frame_1/DoAction.as), H=600
H=600; rate=65
names={0:'empty',1:'dirt',6:'Ironium',7:'Bronzium',8:'Silverium',9:'Goldium',10:'Platinium',11:'Einsteinium',12:'Emerald',13:'Ruby',14:'Diamond',15:'Amazonite',16:'Dinosaur Bones',17:'Treasure',18:'Martian Skeleton',19:'Religious Artifact',25:'rock',28:'lava',31:'gas'}
def rnd(n): # AS2 random(n): 0..n-1, n<=1 -> 0
    return max(n,1)
def row(y):
    p={}
    def add(k,v): p[k]=p.get(k,0)+v
    keep=2/3
    n=int(y/rate)+2
    # base 0.16
    for u in range(n): add(min(u+6,15),0.16/n*keep)
    for u in range(n): add(min(u+7,15),0.032/n*keep)
    sp = 0.25 if y>80 else 0.0
    for s in range(4): add(16+s, 0.008*sp/4*keep)
    for u in range(n): add(min(u+8,15),0.008*(1-sp)/n*keep)
    dirt=0.8*keep
    if y*1.5>H/3:
        m=int((H-y)/H*15); ph=1/rnd(m)
        if y/2*1.5>H/3:
            if y/3*1.5>H/3:
                add(31,dirt*ph*0.25); add(28,dirt*ph*0.25); add(25,dirt*ph*0.5)
            else:
                add(28,dirt*ph*0.5); add(25,dirt*ph*0.5)
        else:
            add(25,dirt*ph)
        add(1,dirt*(1-ph))
    else:
        add(1,dirt)
    add(0,1/3)
    return p
import sys
mode=sys.argv[1] if len(sys.argv)>1 else 'first'
if mode=='first':
    seen={}
    for y in range(6,588):
        for k,v in row(y).items():
            if v>0 and k not in seen: seen[k]=y
    for k,y in sorted(seen.items(), key=lambda x:x[1]):
        print(f"{names.get(k,k):20s} first row {y:3d}  ~{12.5*(y-4):.0f} ft")
else:
    keys=[0,1,25,28,31,6,7,8,9,10,11,12,13,14,15,16]
    print('row ft ' + ' '.join(names[k][:5] for k in keys))
    for y in [6,40,80,100,134,150,200,267,300,350,401,450,500,520,550,560,587]:
        p=row(y)
        print(f"{y} {12.5*(y-4):.0f} " + ' '.join(f"{100*p.get(k,0):.2f}" for k in keys))
    # expected counts over whole map per mineral (36 columns)
    tot={}
    for y in range(6,588):
        for k,v in row(y).items(): tot[k]=tot.get(k,0)+v*36
    print('expected tiles in whole 36x582 map:')
    for k in sorted(tot): print(f"  {names.get(k,k)}: {tot[k]:.1f}")
