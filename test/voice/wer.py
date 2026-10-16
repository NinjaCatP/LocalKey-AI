import sys, re, jiwer
ref = [l.strip() for l in open('sentences.txt') if l.strip()]
def norm(s):
    s = s.lower().replace("’", "'"); s = re.sub(r"[^a-z0-9' ]", " ", s); return " ".join(s.split())
for f in sys.argv[1:]:
    rows = [l.rstrip('\n').split('\t') for l in open(f) if not l.startswith('LOAD')]
    print(f)
    for grp in ('espeak', 'piper', 'noisy'):
        R, H, ms, au = [], [], 0, 0
        for r in rows:
            if '/' + grp + '_' not in r[0]: continue
            i = int(r[0][-6:-4]) - 1; R.append(norm(ref[i])); H.append(norm(r[3] if len(r) > 3 else '')); ms += float(r[1]); au += float(r[2])
        print(f"  {grp:7s} WER {100*jiwer.wer(R, H):5.1f}%  RTF {ms/1000/au:.3f}  ({len(R)} clips, {au:.0f}s audio)")
