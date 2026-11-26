"""Gera uma imagem de cutscene com o Gemini (chave do .env). Uso: python gerar_imagem.py <saida.png> "<descrição da cena>" [modelo]"""
import base64, json, re, sys, urllib.request, urllib.error, os

AQUI = os.path.dirname(os.path.abspath(__file__))
ESTILO = ("Painted graphic-novel illustration, thick black ink outlines, flat muted colors with one strong accent color, "
          "rough brush textures, dark fantasy, cinematic wide composition, misty atmosphere, no text, no letters, no logos, "
          "no watermark. Style of a hand-painted storybook for a fantasy card game.")

def chave():
    t = open(os.path.join(AQUI, '..', '.env'), encoding='utf-8').read()
    return re.search(r'GEMINI_API_KEY\s*=\s*(\S+)', t).group(1).strip('"\'')

def gerar(saida, cena, modelo='gemini-3.1-flash-image', proporcao='21:9'):
    corpo = {"contents": [{"parts": [{"text": f"{ESTILO}\n\nScene: {cena}"}]}],
             "generationConfig": {"responseModalities": ["IMAGE"], "imageConfig": {"aspectRatio": proporcao}}}
    req = urllib.request.Request(f"https://generativelanguage.googleapis.com/v1beta/models/{modelo}:generateContent",
                                 data=json.dumps(corpo).encode(), headers={"Content-Type": "application/json", "x-goog-api-key": chave()})
    try:
        r = json.load(urllib.request.urlopen(req, timeout=180))
    except urllib.error.HTTPError as e:
        return 'ERRO ' + str(e.code) + ': ' + e.read().decode()[:400]
    for c in r.get('candidates', []):
        for p in c.get('content', {}).get('parts', []):
            if 'inlineData' in p:
                os.makedirs(os.path.dirname(saida), exist_ok=True)
                open(saida, 'wb').write(base64.b64decode(p['inlineData']['data']))
                return 'ok ' + saida
    return 'sem imagem: ' + json.dumps(r)[:400]

if __name__ == '__main__':
    print(gerar(sys.argv[1], sys.argv[2], *(sys.argv[3:4] or [])))
