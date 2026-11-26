"""Gera web/campanha-decks-previa.html com os 26 decks do Mundo 1 (para revisar)."""
import json, os, re, html

AQUI = os.path.dirname(os.path.abspath(__file__))
WEB = os.path.join(AQUI, '..', 'web')
POOL = json.load(open(os.path.join(AQUI, 'pools', 'mundo1.json'), encoding='utf-8'))
INFO = {c['nome']: dict(c, faixa=f) for f, l in POOL.items() for c in l}
IND = json.load(open(os.path.join(AQUI, 'campanha', 'mundo1.json'), encoding='utf-8'))


def ler(arq):
    cmd, cartas, basicos = None, [], []
    for ln in open(os.path.join(AQUI, 'campanha', 'mundo1', arq), encoding='utf-8'):
        m = re.match(r'(SB: )?(\d+) \[(\w+):(\w+)\] (.+)', ln.strip())
        if not m: continue
        sb, q, s, n, nome = m.groups()
        if sb: cmd = (nome, s, n)
        elif nome in ('Plains', 'Island', 'Swamp', 'Mountain', 'Forest'): basicos.append(f'{q} {nome}')
        else: cartas.append(nome)
    return cmd, cartas, basicos


def img(nome): return 'https://api.scryfall.com/cards/named?format=image&version=normal&exact=' + html.escape(nome.replace("'", '%27'))


COR_F = {'comum': '#9a948a', 'incomum': '#b8c8d8', 'rara': '#e8c874', 'mitica': '#ff8a4a', 'terreno': '#c8a0e8'}


def bloco(titulo, sub, arq):
    cmd, cartas, basicos = ler(arq)
    grupos = {'Criaturas': [], 'Mágicas': [], 'Outras': []}
    for n in cartas:
        t = INFO.get(n, {}).get('tipo', '')
        grupos['Criaturas' if 'Creature' in t else 'Mágicas' if ('Instant' in t or 'Sorcery' in t) else 'Outras'].append(n)
    contagem = {}
    for n in cartas: f = INFO.get(n, {}).get('faixa', 'comum'); contagem[f] = contagem.get(f, 0) + 1
    raro = ' · '.join(f"{v} {k}" for k, v in sorted(contagem.items(), key=lambda kv: ['comum', 'incomum', 'rara', 'mitica', 'terreno'].index(kv[0])))
    cols = ''.join(f"<div><h4>{g} ({len(l)})</h4>" + ''.join(
        f"<div class='c' data-img=\"{img(n)}\" style='color:{COR_F.get(INFO.get(n, {}).get('faixa'), '#ddd')}'>{html.escape(n)}</div>" for n in sorted(l)) + "</div>" for g, l in grupos.items())
    return f"""<section><div class='cab'><img src="{img(cmd[0])}"><div><small>{html.escape(sub)}</small><h3>{html.escape(titulo)}</h3>
      <p>Comandante: <b>{html.escape(cmd[0])}</b></p><p>{len(cartas)} cartas + {', '.join(basicos)}</p><p class='r'>{raro}</p></div></div><div class='cols'>{cols}</div></section>"""


h = ''
h += "<h2>Decks iniciais do jogador</h2>" + ''.join(bloco(d['nome'], 'Deck inicial · ' + d['cor'], d['arquivo']) for d in IND['iniciais'])
h += "<h2>Bots do Mundo 1</h2>"
for b in IND['batalhas']:
    for bot in b['bots']:
        h += bloco(bot['nome'], f"Batalha {b['numero']}" + (' · 3 bots' if len(b['bots']) > 1 else ''), bot['arquivo'])
pag = f"""<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><title>Decks da campanha - Mundo 1</title>
<link href="https://fonts.googleapis.com/css2?family=Cinzel:wght@600;800&family=EB+Garamond&display=swap" rel="stylesheet">
<style>body{{margin:0;background:#140e09;color:#eee;font-family:'EB Garamond',serif;padding:24px}}h2{{font-family:Cinzel,serif;color:#E4C874;margin:30px 0 12px}}
section{{background:#1e1610;border:1px solid #4a3a28;border-radius:12px;padding:14px;margin-bottom:16px}}.cab{{display:flex;gap:14px;align-items:flex-start}}.cab img{{width:120px;border-radius:7px}}
.cab small{{color:#c9a24a;letter-spacing:.15em;text-transform:uppercase;font-size:12px}}.cab h3{{font-family:Cinzel,serif;margin:2px 0 4px;font-size:20px}}.cab p{{margin:2px 0;color:#d9c8b0;font-size:14px}}.r{{color:#a8957d}}
.cols{{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-top:10px}}h4{{font-family:Cinzel,serif;color:#c9a24a;margin:0 0 4px;font-size:13px}}.c{{font-size:13.5px;line-height:1.45;cursor:zoom-in}}
#zoom{{position:fixed;pointer-events:none;width:240px;border-radius:12px;display:none;z-index:9;box-shadow:0 10px 30px #000}}</style></head><body>
<h1 style="font-family:Cinzel,serif;color:#E4C874;margin:0">Campanha · Mundo 1</h1><p style="color:#a8957d">Cores das cartas: cinza comum · azul-prata incomum · dourado rara · laranja mítica · roxo terreno. Passe o mouse para ver a carta.</p>
{h}<img id="zoom"><script>const z=document.getElementById('zoom');document.querySelectorAll('.c').forEach(e=>{{e.onmouseenter=v=>{{z.src=e.dataset.img;z.style.display='block'}};e.onmousemove=v=>{{z.style.left=(v.clientX+16)+'px';z.style.top=Math.min(v.clientY-40,innerHeight-340)+'px'}};e.onmouseleave=()=>z.style.display='none'}})</script></body></html>"""
open(os.path.join(WEB, 'campanha-decks-previa.html'), 'w', encoding='utf-8').write(pag)
print('ok')
