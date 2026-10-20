import json,glob,os,re
rows=[]
for d in sorted(glob.glob('boot*'), key=lambda p:int(re.search(r'\d+',p).group())):
    k=d; f=f'{k}/run/framepath/arms_{k}.json'
    if not os.path.exists(f): continue
    r=json.load(open(f))['results']; rs=[x for x in r if not x.get('discard')]
    rps=[x['rps'] for x in rs]; cpu=[x['cpu']['ai']['mean'] for x in rs]
    def cal(fn):
        m={}
        for l in open(f'{k}/{fn}'):
            t=l.rstrip('\n').split('\t')
            if len(t)>8 and t[6] in('cpu','infer'): m[t[6]]=float(t[7])
        return m
    b=cal('calibration_before.tsv'); a=cal('calibration_after.tsv') if os.path.exists(f'{k}/calibration_after.tsv') else {}
    bid=[l for l in open(f'{k}/boot.txt') if 'boot_id' in l][0].split(':',1)[1].strip()[:8]
    print(f"{k}  rps {' '.join('%.1f'%x for x in rps)}  mean {sum(rps)/len(rps):7.2f}  aiCPU {sum(cpu)/len(cpu):5.2f}  eff {sum(rps)/sum(cpu):5.2f}  cpu_cal {b.get('cpu',0)/1e6:5.2f}M/{a.get('cpu',0)/1e6:5.2f}M  infer {b.get('infer',0):5.2f}/{a.get('infer',0):5.2f}  boot_id {bid}")
