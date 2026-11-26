// Falas dos chefes da campanha durante a batalha: retrato + balão (e voz, se existir cutscenes/<chefe>/<id>.mp3).
// Observa a partida (variável global E) a cada meio segundo; cada fala acontece uma vez por partida.
(function(){
  const CHEFES = {
    'Exército de Krenko': {
      pasta: 'krenko', nome: 'Krenko', retrato: 'campanha/artes/krenko.jpg', cor: '#ff6a2a',
      falas: [
        {id: 'inicio',   quando: s => s.turno >= 1, texto: 'Mais um herói? Meus meninos ADORAM heróis. Eles quebram tão fácil!'},
        {id: 'turno3',   quando: s => s.turno >= 3, texto: 'Tá ouvindo esse barulho? São os meus goblins. TODOS eles. E tem mais vindo!'},
        {id: 'perdeu',   quando: s => s.chefeCemiterio > s.chefeCemiterioIni, texto: 'Tudo bem, tudo bem! Eu tenho mais. Eu SEMPRE tenho mais!'},
        {id: 'ganhando', quando: s => s.minhaVida > 0 && s.minhaVida <= 15, texto: 'Tá cansado, herói? Eu nem comecei! Hehehe!'},
        {id: 'apanhando',quando: s => s.chefeVida > 0 && s.chefeVida <= 15, texto: 'Não, não, não! Guardas! GUARDAS! Alguém faz alguma coisa!'},
        {id: 'derrota',  quando: s => s.fim && s.venci, texto: 'O dragão... ele prometeu... que eu ia ser rei...'},
        {id: 'vitoria',  quando: s => s.fim && !s.venci, texto: 'HAHA! Eu sabia! Ninguém derrota o Krenko! Volta quando tiver mais goblins!'},
      ]
    }
  };
  const css = document.createElement('style');
  css.textContent = `
#falaChefe{position:fixed;left:24px;bottom:150px;z-index:9200;display:flex;align-items:flex-end;gap:12px;pointer-events:none;opacity:0;transform:translateY(16px) scale(.96);transition:opacity .35s,transform .35s cubic-bezier(.2,.9,.3,1.2)}
#falaChefe.on{opacity:1;transform:none}
#falaChefe .ret{width:92px;height:92px;flex:none;border-radius:50%;background:#000 center/cover;box-shadow:0 0 0 3px var(--cf),0 0 0 6px #1a0804,0 0 26px var(--cf),0 10px 24px #000c;animation:falaPulsa 1s ease-in-out infinite}
@keyframes falaPulsa{50%{box-shadow:0 0 0 3px var(--cf),0 0 0 6px #1a0804,0 0 40px var(--cf),0 10px 24px #000c}}
#falaChefe .bal{position:relative;max-width:420px;margin-bottom:22px;padding:12px 18px 14px;border-radius:14px;font-family:'EB Garamond',serif;font-size:19px;line-height:1.3;color:#fff4e0;
  background:linear-gradient(180deg,#2a1208f2,#160904f2);box-shadow:inset 0 0 0 1px color-mix(in srgb,var(--cf) 60%,transparent),0 10px 26px #000c}
#falaChefe .bal::before{content:'';position:absolute;left:-9px;bottom:18px;border:9px solid transparent;border-right-color:#1e0d06;border-left:0}
#falaChefe .bal b{display:block;font-family:var(--ff-titulo,'Cinzel'),serif;font-size:13px;letter-spacing:.14em;color:var(--cf);margin-bottom:2px}`;
  document.head.appendChild(css);
  const box = document.createElement('div'); box.id = 'falaChefe'; box.innerHTML = '<div class="ret"></div><div class="bal"><b></b><span></span></div>';
  document.addEventListener('DOMContentLoaded', () => document.body.appendChild(box)); if (document.body) document.body.appendChild(box);
  let jogoAtual = null, ditas = new Set(), fila = [], falando = false, base = {}, audio = null;
  function falar(ch, f){
    falando = true;
    box.style.setProperty('--cf', ch.cor);
    box.querySelector('.ret').style.backgroundImage = `url('${ch.retrato}')`;
    box.querySelector('b').textContent = ch.nome.toUpperCase();
    const span = box.querySelector('span'); span.textContent = '';
    box.classList.add('on');
    // texto aparecendo aos poucos
    let i = 0; const t = setInterval(() => { span.textContent = f.texto.slice(0, ++i); if (i >= f.texto.length) clearInterval(t); }, 28);
    try { if (audio) audio.pause(); audio = new Audio(`cutscenes/${ch.pasta}/${f.id}.mp3`); audio.volume = .95; audio.play().catch(() => {}); } catch(e){}
    const dura = Math.max(3800, f.texto.length * 75);
    setTimeout(() => { box.classList.remove('on'); setTimeout(() => { falando = false; proxima(); }, 450); }, dura);
  }
  function proxima(){ if (falando || !fila.length) return; const [ch, f] = fila.shift(); falar(ch, f); }
  setInterval(() => {
    const E_ = (typeof E !== 'undefined') ? E : null; if (!E_ || !E_.jogo) return;   // E é a partida, declarada no index.html
    const js = E_.jogo.jogadores || [], chefeJ = js.find(j => CHEFES[j.nome]); if (!chefeJ) return;
    const ch = CHEFES[chefeJ.nome], gid = chefeJ.id;   // o id do jogador muda a cada partida
    if (gid !== jogoAtual){ jogoAtual = gid; ditas = new Set(); fila = []; base = {cem: (chefeJ.cemiterio || []).length}; }
    const eu = js.find(j => j.eu) || {};
    const s = {turno: E_.jogo.turno || 0, minhaVida: eu.vida, chefeVida: chefeJ.vida, chefeCemiterio: (chefeJ.cemiterio || []).length, chefeCemiterioIni: base.cem,
               fim: E_.fase === 'fim' || !!E_.resultado, venci: !!(E_.resultado && E_.resultado.venceu)};
    for (const f of ch.falas){
      if (ditas.has(f.id)) continue;
      if ((f.id === 'derrota' || f.id === 'vitoria') !== s.fim) continue;   // as falas de fim só no fim
      if (f.quando(s)){ ditas.add(f.id); fila.push([ch, f]); }
    }
    proxima();
  }, 500);
})();
