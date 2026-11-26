// Chefes da campanha na batalha, estilo Hearthstone: perfil do chefe atrás do playmat dele, retrato com moldura
// na borda do campo, e quando ele fala o retrato cresce, balança e aparece um balão (com voz: cutscenes/<pasta>/<id>.mp3).
// Observa a partida (variável global E do index.html) a cada meio segundo; cada fala acontece uma vez por partida.
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
        {id: 'guardas',  quando: s => s.log.includes('Guardas, segurem ele'), texto: 'Guardas, segurem ele! Esse turno é MEU!'},
        {id: 'horda',    quando: s => s.log.includes('Mais goblins!'), texto: 'Mais goblins! Eu SEMPRE tenho mais! Hehehe!'},
        {id: 'furia',    quando: s => s.chefeVida > 0 && s.chefeVida < 20, texto: 'Vocês ouviram, meninos? Agora é sério! ATAQUEM!'},
        {id: 'derrota',  quando: s => s.fim && s.venci, texto: 'O dragão... ele prometeu... que eu ia ser rei...'},
        {id: 'vitoria',  quando: s => s.fim && !s.venci, texto: 'HAHA! Eu sabia! Ninguém derrota o Krenko! Volta quando tiver mais goblins!'},
      ]
    }
  };
  const css = document.createElement('style');
  css.textContent = `
.chefePerfil{position:absolute;inset:-4%;z-index:-1;pointer-events:none;background:var(--chefeImg) center 30%/cover;filter:saturate(.75) brightness(.38) blur(1px);
  -webkit-mask:radial-gradient(ellipse 70% 95% at 50% 40%,#000 35%,transparent 85%);mask:radial-gradient(ellipse 70% 95% at 50% 40%,#000 35%,transparent 85%);transition:filter .5s,transform 6s ease-out}
.corpoTab.comChefe{position:relative;isolation:isolate}
body.chefeFalando .chefePerfil{filter:saturate(1) brightness(.55) blur(0);transform:scale(1.06)}
#chefeHeroi{position:fixed;z-index:9200;width:112px;height:134px;pointer-events:none;display:none;transform-origin:50% 78%;transform:scale(var(--esc,1));transition:transform .45s cubic-bezier(.2,1.4,.4,1),filter .4s}
#chefeHeroi.on{display:block}
#chefeHeroi .ft{position:absolute;inset:9px 10px 12px;border-radius:50% 50% 46% 46%/58% 58% 42% 42%;background:#000 center 25%/cover;box-shadow:inset 0 -16px 24px #000a}
#chefeHeroi .mold{position:absolute;inset:0;border-radius:50% 50% 44% 44%/58% 58% 42% 42%;box-shadow:0 0 0 4px #8a5a24,0 0 0 6px #2a1408,0 0 0 8px #c9a24a,0 10px 24px #000e}
#chefeHeroi .nm{position:absolute;left:50%;bottom:-12px;transform:translateX(-50%);padding:2px 12px;border-radius:6px;font-family:var(--ff-titulo,'Cinzel'),serif;font-size:12px;letter-spacing:.14em;color:#fff4e0;
  background:linear-gradient(#5a1a0c,#2a0a04);box-shadow:0 0 0 2px #c9a24a,0 4px 10px #000c;white-space:nowrap}
body.chefeFalando #chefeHeroi{transform:scale(calc(var(--esc,1) * 1.55)) translateY(12px);filter:drop-shadow(0 0 24px var(--cf))}
body.chefeFalando #chefeHeroi .ft{animation:chefeBalanca .16s ease-in-out infinite alternate}
@keyframes chefeBalanca{to{transform:translateY(-1.5px) rotate(.6deg)}}
#chefeBal{position:fixed;z-index:9201;max-width:380px;padding:12px 18px 14px;border-radius:16px;font-family:'EB Garamond',serif;font-size:20px;line-height:1.3;pointer-events:none;
  background:#f6ead2;color:#2a1408;box-shadow:0 0 0 3px #8a5a24,0 12px 30px #000c;opacity:0;transform:scale(.6) translateX(-40px);transform-origin:0 70%;
  transition:opacity .25s,transform .35s cubic-bezier(.2,1.4,.4,1)}
#chefeBal::before{content:'';position:absolute;left:-16px;bottom:22px;border:12px solid transparent;border-right:16px solid #f6ead2;border-left:0}
body.chefeFalando #chefeBal{opacity:1;transform:none}`;
  document.head.appendChild(css);
  const heroi = document.createElement('div'); heroi.id = 'chefeHeroi'; heroi.innerHTML = '<div class="ft"></div><div class="mold"></div><div class="nm"></div>';
  const bal = document.createElement('div'); bal.id = 'chefeBal';
  const pôr = () => { if (!heroi.isConnected && document.body){ document.body.appendChild(heroi); document.body.appendChild(bal); } };
  document.addEventListener('DOMContentLoaded', pôr); pôr();

  let jogoAtual = null, ditas = new Set(), fila = [], falando = false, base = {}, audio = null, chefeAtual = null, chefeId = null;

  // coloca o perfil atrás do playmat e o retrato na borda de baixo do campo do chefe (a mesa é redesenhada a todo momento)
  function posicionar(){
    const mesa = document.getElementById('mesa');
    const cab = chefeId && mesa && mesa.querySelector(`.cab[data-jog="${chefeId}"]`), tab = cab && cab.closest('.tab');
    if (!tab || !chefeAtual || mesa.offsetParent === null){ heroi.classList.remove('on'); bal.style.display = 'none'; return; }
    const corpo = tab.querySelector('.corpoTab');
    if (corpo && !corpo.querySelector('.chefePerfil')){
      corpo.classList.add('comChefe'); corpo.style.setProperty('--chefeImg', `url('${chefeAtual.retrato}')`);
      corpo.insertAdjacentHTML('afterbegin', '<div class="chefePerfil"></div>');
    }
    const r = tab.getBoundingClientRect();
    // o retrato acompanha o tamanho do tabuleiro (zoom da câmera): referência = campo com 1000px de largura
    const k = Math.max(.3, Math.min(1, r.width / 1000)); heroi.style.setProperty('--esc', k);
    heroi.style.left = (r.left + r.width / 2 - 56) + 'px';
    heroi.style.top = (r.bottom - 134 * .78) + 'px';
    heroi.classList.add('on'); bal.style.display = '';
    bal.style.left = (r.left + r.width / 2 + 56 * 1.55 * k + 20) + 'px';
    bal.style.top = (r.bottom - 134 * .78 - 40 * k) + 'px';
  }
  new MutationObserver(() => posicionar()).observe(document.documentElement, {childList: true, subtree: true});
  addEventListener('resize', posicionar);

  function falar(ch, f){
    falando = true;
    document.documentElement.style.setProperty('--cf', ch.cor);
    bal.textContent = ''; document.body.classList.add('chefeFalando');
    let i = 0; const t = setInterval(() => { bal.textContent = f.texto.slice(0, ++i); if (i >= f.texto.length) clearInterval(t); }, 30);
    let acabou = false;
    const terminar = (espera) => { if (acabou) return; acabou = true; setTimeout(() => {
      document.body.classList.remove('chefeFalando'); setTimeout(() => { falando = false; proxima(); }, 500); }, espera); };
    try {
      if (audio) audio.pause();
      audio = new Audio(`cutscenes/${ch.pasta}/${f.id}.mp3`); audio.volume = .95;
      audio.onended = () => terminar(700);
      audio.onerror = () => terminar(Math.max(3500, f.texto.length * 70));
      audio.play().catch(audio.onerror);
    } catch(e){ terminar(Math.max(3500, f.texto.length * 70)); }
    setTimeout(() => terminar(0), 20000);   // garantia
  }
  function proxima(){ if (falando || !fila.length) return; const [ch, f] = fila.shift(); falar(ch, f); }
  setInterval(() => {
    const E_ = (typeof E !== 'undefined') ? E : null;
    const js = (E_ && E_.jogo && E_.jogo.jogadores) || [], chaveDe = n => Object.keys(CHEFES).find(k => (n || '').endsWith(k)), chefeJ = js.find(j => chaveDe(j.nome));
    if (!chefeJ){ chefeAtual = chefeId = null; posicionar(); return; }
    const ch = CHEFES[chaveDe(chefeJ.nome)], gid = chefeJ.id;   // o id do jogador muda a cada partida
    if (gid !== jogoAtual){ jogoAtual = gid; ditas = new Set(); fila = []; base = {cem: (chefeJ.cemiterio || []).length}; }
    if (chefeAtual !== ch){
      heroi.querySelector('.ft').style.backgroundImage = `url('${ch.retrato}')`; heroi.querySelector('.nm').textContent = ch.nome.toUpperCase();
    }
    chefeAtual = ch; chefeId = gid; posicionar();
    const eu = js.find(j => j.eu) || {};
    const s = {turno: E_.jogo.turno || 0, minhaVida: eu.vida, chefeVida: chefeJ.vida, chefeCemiterio: (chefeJ.cemiterio || []).length, chefeCemiterioIni: base.cem,
               fim: E_.fase === 'fim' || !!E_.resultado, venci: !!(E_.resultado && E_.resultado.venceu), log: JSON.stringify(E_.log || [])};
    for (const f of ch.falas){
      if (ditas.has(f.id)) continue;
      if ((f.id === 'derrota' || f.id === 'vitoria') !== s.fim) continue;   // as falas de fim só no fim
      if (f.quando(s)){ ditas.add(f.id); fila.push([ch, f]); }
    }
    proxima();
  }, 500);
})();
