import json,sys
sys.stdout.reconfigure(encoding='utf-8')
L=json.load(open('listings.json',encoding='utf-8')); I=json.load(open('instructions.json',encoding='utf-8'))
for n in map(int,sys.argv[1:]):
    r=L[n-1]
    print(f'=== #{n}', r['section'], '| price', r['price_from'])
    print('TITLE:', r['title']); print('TAGS:', r['tags']); print('VAR:', r['variations'], r['variant_prices'])
    print('IMAGES:', r['images']); print('INSTR:', I[n-1]); print('CHECK:', r['to_check'])
    print('DESC:', json.dumps(r['description'],ensure_ascii=False)); print()
