"""Gera os decks da campanha (Mundo 1): 21 decks de bot (mais fortes a cada batalha) e 5 decks iniciais fracos.

Uso: python gerar_campanha.py   (lê pools/mundo1.json e grava campanha/mundo1/*.dck e campanha/mundo1.json)
Cada deck: comandante + 99. Só cartas do pool do mundo (e básicos). Sem cartas repetidas (singleton).
"""
import json, os, random, re

AQUI = os.path.dirname(os.path.abspath(__file__))
POOL = json.load(open(os.path.join(AQUI, 'pools', 'mundo1.json'), encoding='utf-8'))
SAIDA = os.path.join(AQUI, 'campanha', 'mundo1')
os.makedirs(SAIDA, exist_ok=True)

BASICO = {'W': ('Plains', 'M15', '250'), 'U': ('Island', 'M15', '254'), 'B': ('Swamp', 'M15', '258'), 'R': ('Mountain', 'M15', '262'), 'G': ('Forest', 'M15', '266')}
RANK = {'comum': 0, 'incomum': 1, 'rara': 2, 'mitica': 3, 'especial': 2}
TODAS = [dict(c, faixa=f) for f, l in POOL.items() for c in l]
POR_NOME = {c['nome']: c for c in TODAS}
LENDAS = {c['nome'] for c in POOL['especial'] if 'Creature' in c['tipo']}


def subtipos(c): return set(re.findall(r"[A-Za-z']+", c.get('subtipos', '')))
def eh(c, t): return t in c['tipo']


# (batalha, nome do deck, comandante, tribos, estilo)
BOTS = [
    (1, 'Soldados de Akros', 'Kytheon, Hero of Akros', ['Soldier', 'Human'], 'tribal'),
    (2, 'Horda de Zumbis', 'Liliana, Heretical Healer', ['Zombie'], 'tribal'),
    (3, 'Bando Goblin', 'Pia and Kiran Nalaar', ['Goblin'], 'tribal'),
    (4, 'Elfos da Floresta', "Dwynen, Gilt-Leaf Daen", ['Elf'], 'tribal'),
    (5, 'Maré dos Merfolk', 'Jalira, Master Polymorphist', ['Merfolk'], 'tribal'),
    (5, 'Feras Selvagens', 'Yisan, the Wanderer Bard', ['Beast'], 'tribal'),
    (5, 'Guerreiros Ancestrais', 'Kurkesh, Onakke Ancient', ['Warrior'], 'tribal'),
    (6, 'Corte dos Vampiros', 'Kothophed, Soul Hoarder', ['Vampire'], 'tribal'),
    (7, 'Círculo dos Magos', 'Talrand, Sky Summoner', ['Wizard'], 'magias'),
    (8, 'Fúria Elemental', 'Nissa, Vastwood Seer', ['Elemental'], 'tribal'),
    (9, 'Oficina dos Golens', 'Alhammarret, High Arbiter', ['Golem', 'Construct'], 'artefatos'),
    (10, 'Ordem dos Cavaleiros', 'Hixus, Prison Warden', ['Knight'], 'tribal'),
    (10, 'Véu das Ilusões', "Jace, Vryn's Prodigy", ['Illusion'], 'tribal'),
    (10, 'Enxame de Insetos', "Yeva, Nature's Herald", ['Insect', 'Spider'], 'tribal'),
    (11, 'Colmeia dos Slivers', 'Sliver Hivelord', ['Sliver'], 'tribal'),
    (12, 'Pacto de Sangue', 'Ob Nixilis, Unshackled', ['Demon', 'Horror'], 'sacrificio'),
    (13, 'Ninho dos Dragões', 'Chandra, Fire of Kaladesh', ['Dragon'], 'tribal'),
    (14, 'Coro dos Anjos', 'Avacyn, Guardian Angel', ['Angel'], 'tribal'),
    (15, 'Legião de Nefarox', 'Nefarox, Overlord of Grixis', ['Demon', 'Zombie'], 'tribal'),
    (15, 'Exército de Krenko', 'Krenko, Mob Boss', ['Goblin'], 'tribal'),
    (15, 'Tropa de Elite de Odric', 'Odric, Master Tactician', ['Soldier', 'Knight'], 'tribal'),
]
INICIAIS = [
    ('W', 'Iniciante Branco', 'Danitha Capashen, Paragon', 'J22', '168'),
    ('U', 'Iniciante Azul', 'Tetsuko Umezawa, Fugitive', 'FRA', '221'),
    ('B', 'Iniciante Preto', 'Yargle, Glutton of Urborg', 'J22', '491'),
    ('R', 'Iniciante Vermelho', 'Valduk, Keeper of the Flame', 'DMR', '147'),
    ('G', 'Iniciante Verde', 'Grunn, the Lonely King', 'DOM', '165'),
]


PROIBIDAS = {'Primeval Titan'}   # banidas no Commander (o validador do XMage acusa)


def cabe(c, cores):   # identidade de cor (inclui símbolos no texto das habilidades) cabe na do comandante
    return set(c.get('identidade', c['cores'])) <= set(cores) and c['nome'] not in PROIBIDAS


def curva_ok(cont, cmc, limites):
    k = min(int(float(cmc)), 6)
    return cont.get(k, 0) < limites[k]


def montar_bot(batalha, nome, cmdNome, tribos, estilo, seed):
    rnd = random.Random(seed)
    cmd = POR_NOME[cmdNome]
    cores = cmd['cores'] or 'WUBRG'
    t = batalha   # 1..15: quanto maior, mais forte
    max_rara, max_mitica, max_inc = round(t * 1.3), t // 3, 8 + 2 * t
    cand = [c for c in TODAS if not eh(c, 'Land') and c['nome'] not in LENDAS and cabe(c, cores)]

    def nota(c):
        n = 0
        st = subtipos(c)
        if any(tr in st for tr in tribos): n += 6
        if eh(c, 'Creature'): n += 2 if estilo in ('tribal', 'sacrificio') else 0.5
        if estilo == 'magias' and (eh(c, 'Instant') or eh(c, 'Sorcery')): n += 4
        if estilo == 'artefatos' and eh(c, 'Artifact'): n += 4
        n += RANK[c['faixa']] * (0.4 + t * 0.12)          # força: raridade pesa mais a cada batalha
        cmc = float(c.get('cmc') or 0)
        if cmc >= 7: n -= 3 if t < 10 else 1
        n += rnd.random() * 1.5                           # variedade
        return n
    cand.sort(key=nota, reverse=True)
    limites = {0: 4, 1: 8, 2: 14, 3: 14, 4: 11, 5: 8, 6: 6}
    esc, cont, raros = [], {}, {'rara': 0, 'mitica': 0, 'incomum': 0}
    for c in cand:
        if len(esc) >= 62: break
        f = c['faixa'] if c['faixa'] != 'especial' else 'rara'
        if f == 'rara' and raros['rara'] >= max_rara: continue
        if f == 'mitica' and raros['mitica'] >= max_mitica: continue
        if f == 'incomum' and raros['incomum'] >= max_inc: continue
        if not curva_ok(cont, c.get('cmc') or 0, limites): continue
        esc.append(c); cont[min(int(float(c.get('cmc') or 0)), 6)] = cont.get(min(int(float(c.get('cmc') or 0)), 6), 0) + 1
        if f in raros: raros[f] += 1
    return cmd, esc, cores


def montar_inicial(cor, seed):
    rnd = random.Random(seed)
    cand = [c for c in POOL['comum'] if not eh(c, 'Land') and c['cores'] == cor and cabe(c, cor)]
    rnd.shuffle(cand)   # comuns aleatórias da cor: deck fraco e sem plano
    limites = {0: 4, 1: 10, 2: 16, 3: 16, 4: 12, 5: 10, 6: 8}
    esc, cont = [], {}
    for c in cand:
        if len(esc) >= 59: break
        if not curva_ok(cont, c.get('cmc') or 0, limites): continue
        esc.append(c); k = min(int(float(c.get('cmc') or 0)), 6); cont[k] = cont.get(k, 0) + 1
    return esc


def dck(cmd, cartas, cores, nTerrenos):
    linhas = [f"SB: 1 [{cmd['set']}:{cmd['num']}] {cmd['nome']}"]
    for c in cartas: linhas.append(f"1 [{c['set']}:{c['num']}] {c['nome']}")
    falta = 99 - len(cartas)
    cs = [x for x in cores if x in BASICO] or ['W']
    for i, x in enumerate(cs):
        q = falta // len(cs) + (1 if i < falta % len(cs) else 0)
        n, s, num = BASICO[x]
        if q: linhas.append(f"{q} [{s}:{num}] {n}")
    return '\n'.join(linhas) + '\n'


indice = {'mundo': 1, 'nome': 'Mundo 1', 'batalhas': [], 'iniciais': []}
for i, (bat, nome, cmdNome, tribos, estilo) in enumerate(BOTS):
    cmd, esc, cores = montar_bot(bat, nome, cmdNome, tribos, estilo, seed=1000 + i)
    arq = f"bot{i+1:02d}.dck"
    open(os.path.join(SAIDA, arq), 'w', encoding='utf-8').write(dck(cmd, esc, cores, 99 - len(esc)))
    raros = sum(1 for c in esc if c['faixa'] in ('rara', 'mitica', 'especial'))
    trib = sum(1 for c in esc if any(t in subtipos(c) for t in tribos))
    print(f"batalha {bat:2d} {nome:28s} {cmdNome:32s} cartas={len(esc)} tribo={trib} raras/miticas={raros}")
    b = next((x for x in indice['batalhas'] if x['numero'] == bat), None)
    if not b: b = {'numero': bat, 'bots': []}; indice['batalhas'].append(b)
    b['bots'].append({'arquivo': arq, 'nome': nome, 'comandante': {'nome': cmd['nome'], 'set': cmd['set'], 'num': cmd['num']}})
for i, (cor, nome, cmdNome, s, num) in enumerate(INICIAIS):
    esc = montar_inicial(cor, seed=2000 + i)
    arq = f"inicial-{cor}.dck"
    cmd = {'nome': cmdNome, 'set': s, 'num': num}
    open(os.path.join(SAIDA, arq), 'w', encoding='utf-8').write(dck(cmd, esc, cor, 99 - len(esc)))
    print(f"inicial {cor} {nome:20s} {cmdNome:30s} cartas={len(esc)}")
    indice['iniciais'].append({'cor': cor, 'arquivo': arq, 'nome': nome, 'comandante': cmd})
json.dump(indice, open(os.path.join(AQUI, 'campanha', 'mundo1.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
