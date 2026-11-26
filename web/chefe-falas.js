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
  // tela de VS do começo: as falas só começam 3 s depois que ela some
  let vsSumiu = 0;
  const vsAberto = () => { const v = document.querySelector('#vsChefe.on'); if (v){ vsSumiu = 0; return true; } if (!vsSumiu) vsSumiu = Date.now(); return Date.now() - vsSumiu < 3000; };

  // ---------- raios do emblema: Horda (dois raios no campo do chefe) e Guardas (raio grande até você) ----------
  const NS = 'http://www.w3.org/2000/svg';
  const camada = document.createElementNS(NS, 'svg');
  camada.setAttribute('style', 'position:fixed;inset:0;width:100vw;height:100vh;pointer-events:none;z-index:9150');
  camada.innerHTML = `<defs><filter id="raioBrilho" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="6" result="b"/><feMerge><feMergeNode in="b"/><feMergeNode in="b"/><feMergeNode in="SourceGraphic"/></feMerge></filter>
    <linearGradient id="raioCor" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#fff2c0"/><stop offset=".5" stop-color="#ff8a2a"/><stop offset="1" stop-color="#ff3a0a"/></linearGradient></defs>`;
  const pôrCamada = () => { if (!camada.isConnected && document.body) document.body.appendChild(camada); };
  document.addEventListener('DOMContentLoaded', pôrCamada); pôrCamada();
  const centro = el => { const r = el.getBoundingClientRect(); return [r.left + r.width / 2, r.top + r.height / 2]; };
  function raio([x1, y1], [x2, y2], grosso, cor){
    const dx = x2 - x1, dy = y2 - y1, mx = (x1 + x2) / 2 - dy * .18, my = (y1 + y2) / 2 + dx * .18;
    // caminho em zigue-zague em volta da curva (cara de relâmpago)
    let d = `M${x1},${y1}`; const n = 14;
    for (let i = 1; i < n; i++){ const t = i / n, x = (1-t)*(1-t)*x1 + 2*(1-t)*t*mx + t*t*x2, y = (1-t)*(1-t)*y1 + 2*(1-t)*t*my + t*t*y2;
      d += ` L${x + (Math.random() - .5) * grosso * 3},${y + (Math.random() - .5) * grosso * 3}`; }
    d += ` L${x2},${y2}`;
    const p = document.createElementNS(NS, 'path'); p.setAttribute('d', d); p.setAttribute('fill', 'none'); p.setAttribute('stroke', cor || 'url(#raioCor)');
    p.setAttribute('stroke-width', grosso); p.setAttribute('stroke-linecap', 'round'); p.setAttribute('filter', 'url(#raioBrilho)');
    camada.appendChild(p); const L = p.getTotalLength(); p.style.strokeDasharray = L; p.style.strokeDashoffset = L;
    p.animate([{strokeDashoffset: L}, {strokeDashoffset: 0}], {duration: 380, easing: 'ease-in', fill: 'forwards'});
    p.animate([{opacity: 1}, {opacity: 1, offset: .6}, {opacity: 0}], {duration: 1300, fill: 'forwards'});
    const c = document.createElementNS(NS, 'circle'); c.setAttribute('cx', x2); c.setAttribute('cy', y2); c.setAttribute('r', grosso * 3); c.setAttribute('fill', '#ffd08a'); c.setAttribute('filter', 'url(#raioBrilho)'); c.style.opacity = 0;
    camada.appendChild(c);
    c.animate([{opacity: 0, r: grosso}, {opacity: 1, r: grosso * 6, offset: .45}, {opacity: 0, r: grosso * 12}], {duration: 1000, delay: 330, fill: 'forwards'});
    setTimeout(() => { p.remove(); c.remove(); }, 1500);
  }
  function raiosHorda(){
    const emb = document.querySelector('.emblemaTile.embChefe'), cab = chefeId && document.querySelector(`.cab[data-jog="${chefeId}"]`), tab = cab && cab.closest('.tab');
    if (!emb || !tab) return; const campo = tab.querySelector('.campo') || tab, r = campo.getBoundingClientRect();
    [0, 220].forEach((t, i) => setTimeout(() => raio(centro(emb), [r.left + r.width * (.3 + i * .25 + Math.random() * .1), r.top + r.height * (.3 + Math.random() * .2)], 5), t));
  }
  function raioGuardas(){
    const emb = document.querySelector('.emblemaTile.embChefe'), meu = document.querySelector('.tab[data-eu] .cab') || document.querySelector('.tab[data-eu]');
    if (!emb || !meu) return;
    raio(centro(emb), centro(meu), 12); setTimeout(() => raio(centro(emb), centro(meu), 7, '#fff2c0'), 120);
    setTimeout(() => { const m = document.getElementById('mesa'); if (m){ m.animate([{transform: 'translate(0,0)'}, {transform: 'translate(-8px,4px)'}, {transform: 'translate(7px,-5px)'}, {transform: 'translate(-4px,3px)'}, {transform: 'translate(0,0)'}], {duration: 420}); } }, 380);
  }
  let logsVistos = new Set(), logIni = false;
  function olharLog(log){
    for (const l of log){ const m = String(l.msg || ''), k = (l.turno || '') + '|' + m;
      if (logsVistos.has(k)) continue; logsVistos.add(k);
      if (!logIni) continue;   // o que já estava no log quando a página abriu não anima
      if (m.includes('Mais goblins!')) raiosHorda();
      if (m.includes('Guardas, segurem ele')) raioGuardas();
    }
    logIni = true;
  }

  function proxima(){ if (falando || !fila.length || vsAberto()) return; const [ch, f] = fila.shift(); falar(ch, f); }
  setInterval(() => {
    const E_ = (typeof E !== 'undefined') ? E : null;
    const js = (E_ && E_.jogo && E_.jogo.jogadores) || [], chaveDe = n => Object.keys(CHEFES).find(k => (n || '').endsWith(k)), chefeJ = js.find(j => chaveDe(j.nome));
    if (!chefeJ){ chefeAtual = chefeId = null; posicionar(); return; }
    const ch = CHEFES[chaveDe(chefeJ.nome)], gid = chefeJ.id;   // o id do jogador muda a cada partida
    if (gid !== jogoAtual){ jogoAtual = gid; ditas = new Set(); fila = []; base = {cem: (chefeJ.cemiterio || []).length}; logsVistos = new Set(); logIni = false; }
    olharLog(E_.log || []);
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
