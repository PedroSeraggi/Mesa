// "Mundo compartilhado" (só na Câmera livre): cursores dos outros jogadores, desenho (Shift+D) e ping (Shift+P).
// As posições vão relativas ao TABULEIRO de um jogador (id do XMage + fração da largura/altura),
// porque cada um vê a mesa arrumada de um jeito diferente.
const Mundo = (()=>{
  const outros = new Map();      // inst -> {jog, fx, fy, tema, quem, cor, t, fora, el}
  const tracos = [];             // {jog, pts:[[fx,fy]], cor, t, id}
  const pings = [];              // {jog, fx, fy, quem, cor, t}
  let seq = 0, ativo = false, desenhando = false, tracoAtual = null, ultimoEnvio = 0, foraEnviado = true;
  let camada, canvas, ctx;
  const ligado = ()=> typeof E !== 'undefined' && E && E.fase === 'jogo' && E.jogo && visao === 'livre';
  const verCursores = ()=> typeof cfgApp === 'undefined' || cfgApp.verCursores !== false;
  const verDesenhos = ()=> typeof cfgApp === 'undefined' || cfgApp.verDesenhos !== false;

  function meuPincel(){ return (typeof cfgApp!=='undefined' && cfgApp.corPincel) || (typeof perfilAtual!=='undefined' && perfilAtual && perfilAtual.cor) || '#C0982F'; }
  function tabDe(jog){ const c = document.querySelector(`#mesa .palco .cab[data-jog="${jog}"]`); return c && c.closest('.tab'); }
  function ponto(jog, fx, fy){ const t = tabDe(jog); if (!t) return null; const r = t.getBoundingClientRect(); return {x: r.left + fx*r.width, y: r.top + fy*r.height}; }
  function relativo(x, y){
    const el = document.elementFromPoint(x, y), t = el && el.closest && el.closest('#mesa .palco .tab'); if (!t) return null;
    const cab = t.querySelector('.cab[data-jog]'); if (!cab) return null;
    const r = t.getBoundingClientRect(); return {jog: cab.dataset.jog, fx: (x-r.left)/r.width, fy: (y-r.top)/r.height, t};
  }
  function relativoA(t, x, y){ const r = t.getBoundingClientRect(); return [ (x-r.left)/r.width, (y-r.top)/r.height ]; }
  const enviar = d => fetch('/api/mundo', {method:'POST', body: JSON.stringify(d)}).catch(()=>{});

  function montar(){
    if (camada) return;
    camada = document.createElement('div'); camada.id = 'mundoCamada'; document.body.appendChild(camada);
    canvas = document.createElement('canvas'); canvas.id = 'mundoCanvas'; document.body.appendChild(canvas);
    ctx = canvas.getContext('2d');
    const aj = ()=>{ canvas.width = innerWidth; canvas.height = innerHeight; }; aj(); addEventListener('resize', aj);
  }

  // ---- meu cursor -> rede ----
  addEventListener('pointermove', ev=>{
    if (!ligado()) return;
    const ag = performance.now();
    if (desenhando && tracoAtual){ const t = tracoAtual._tab; if (t){ tracoAtual.pts.push(relativoA(t, ev.clientX, ev.clientY)); } }
    if (ag - ultimoEnvio < 66) return; ultimoEnvio = ag;
    const r = relativo(ev.clientX, ev.clientY);
    if (r){ foraEnviado = false; enviar({tipo:'cursor', jog:r.jog, fx:+r.fx.toFixed(4), fy:+r.fy.toFixed(4), tema: (typeof cfgApp!=='undefined' && cfgApp.cursor) || 'esqueleto'}); }
    else if (!foraEnviado){ foraEnviado = true; enviar({tipo:'cursor', fora:true}); }
    if (desenhando && tracoAtual && tracoAtual.pts.length > tracoAtual.enviados){
      enviar({tipo:'traco', id:tracoAtual.id, jog:tracoAtual.jog, pts: tracoAtual.pts.slice(Math.max(0, tracoAtual.enviados-1)), pincel: tracoAtual.cor});
      tracoAtual.enviados = tracoAtual.pts.length;
    }
  }, {passive:true});

  // ---- desenho (Shift+D) e ping (Shift+P) ----
  let mouse = {x:0, y:0};
  addEventListener('pointermove', ev=>{ mouse = {x:ev.clientX, y:ev.clientY}; }, {passive:true, capture:true});
  function modoDesenho(on){
    desenhando = on; document.documentElement.classList.toggle('modoDesenho', on);
    if (typeof avisoLocal === 'function') avisoLocal(on ? 'Modo desenho: arraste para rabiscar (some em 4 s). Shift+D ou Esc para sair.' : 'Modo desenho desligado.');
  }
  addEventListener('keydown', ev=>{
    if (ev.target.closest && ev.target.closest('input, textarea, [contenteditable]')) return;
    if (ev.key === 'Escape' && desenhando){ modoDesenho(false); return; }
    if (!ev.shiftKey || ev.ctrlKey || ev.altKey) return;
    const k = ev.key.toLowerCase();
    if (k !== 'd' && k !== 'p') return;
    if (!ligado()){ if (typeof avisoLocal === 'function' && E && E.jogo) avisoLocal('Desenho e ping funcionam na Câmera livre.'); return; }
    ev.preventDefault();
    if (k === 'd') modoDesenho(!desenhando);
    if (k === 'p'){ const r = relativo(mouse.x, mouse.y); if (!r) return;
      const meuCor = meuPincel();
      pings.push({jog:r.jog, fx:r.fx, fy:r.fy, quem:'você', cor:meuCor, t:performance.now()});
      enviar({tipo:'ping', jog:r.jog, fx:+r.fx.toFixed(4), fy:+r.fy.toFixed(4), pincel:meuCor});
      if (typeof Sons !== 'undefined') Sons.tocar('aviso'); }
  });
  addEventListener('pointerdown', ev=>{
    if (!desenhando || ev.button !== 0 || !ligado()) return;
    const r = relativo(ev.clientX, ev.clientY); if (!r) return;
    ev.preventDefault(); ev.stopPropagation();
    const cor = meuPincel();
    tracoAtual = {id: Math.random().toString(36).slice(2,9), jog:r.jog, pts:[[r.fx, r.fy]], cor, t:performance.now(), enviados:0, _tab:r.t};
    tracos.push(tracoAtual);
  }, true);
  addEventListener('pointerup', ()=>{
    if (!tracoAtual) return;
    enviar({tipo:'traco', id:tracoAtual.id, jog:tracoAtual.jog, pts: tracoAtual.pts.slice(Math.max(0, tracoAtual.enviados-1)), pincel: tracoAtual.cor, fim:true});
    tracoAtual.t = performance.now(); tracoAtual = null;
  }, true);
  // durante o desenho, clicar não usa a carta nem arrasta a câmera
  addEventListener('click', ev=>{ if (desenhando && ligado()){ ev.stopPropagation(); ev.preventDefault(); } }, true);

  // ---- rede -> outros ----
  async function buscar(){
    if (!ligado()){ setTimeout(buscar, 500); return; }
    try {
      const r = await (await fetch('/api/mundo?desde=' + seq)).json();
      if (r.seq < seq) seq = 0;               // a ponte reiniciou
      else seq = r.seq;
      const ag = performance.now();
      for (const e of (r.eventos||[])){
        if (e.tipo === 'cursor'){
          const o = outros.get(e.inst) || {}; Object.assign(o, {jog:e.jog, fx:e.fx, fy:e.fy, tema:e.tema, quem:e.quem, cor:e.cor, fora:!!e.fora, t:ag}); outros.set(e.inst, o);
        } else if (e.tipo === 'ping'){
          pings.push({jog:e.jog, fx:e.fx, fy:e.fy, quem:e.quem, cor:e.pincel||e.cor, t:ag});
          if (verDesenhos() && typeof Sons !== 'undefined') Sons.tocar('aviso');
        } else if (e.tipo === 'traco'){
          let tr = tracos.find(x=>x.id === e.id && x.remoto);
          if (!tr){ tr = {id:e.id, remoto:true, jog:e.jog, pts:[], cor:e.pincel||e.cor, t:ag}; tracos.push(tr); }
          tr.pts.push(...(e.pts||[])); tr.t = ag;
        }
      }
    } catch(e){}
    setTimeout(buscar, 70);
  }
  buscar();

  // ---- desenho na tela ----
  function quadro(){
    requestAnimationFrame(quadro);
    if (!camada) montar();
    const on = ligado(), ag = performance.now();
    // cursores
    for (const [inst, o] of outros){
      if (!o.el){ o.el = document.createElement('div'); o.el.className = 'cursorOutro'; o.el.innerHTML = `<img><span></span>`; camada.appendChild(o.el); }
      const p = on && verCursores() && !o.fora && ag - o.t < 6000 ? ponto(o.jog, o.fx, o.fy) : null;
      if (!p){ o.el.style.display = 'none'; continue; }
      const tema = ['esqueleto','cavaleiro','maga','druida','dragao'].includes(o.tema) ? o.tema : null;
      const img = o.el.querySelector('img'), sp = o.el.querySelector('span');
      if (tema){ const src = `cursores/${tema}-aberta.png`; if (img.getAttribute('src') !== src) img.src = src; img.style.display = ''; } else img.style.display = 'none';
      o.el.classList.toggle('seta', !tema);
      sp.textContent = o.quem || '?'; sp.style.background = o.cor || '#C0982F';
      o.el.style.display = ''; o.el.style.transform = `translate(${p.x}px,${p.y}px)`;
    }
    // traços e pings
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    if (!on || !verDesenhos()) return;
    for (let i = tracos.length-1; i >= 0; i--){
      const tr = tracos[i], idade = tr === tracoAtual ? 0 : ag - tr.t;
      if (idade > 4000){ tracos.splice(i,1); continue; }
      const t = tabDe(tr.jog); if (!t || tr.pts.length < 1) continue;
      const r = t.getBoundingClientRect();
      ctx.globalAlpha = idade > 3000 ? 1 - (idade-3000)/1000 : 1;
      ctx.strokeStyle = tr.cor; ctx.lineWidth = 4; ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      ctx.shadowColor = tr.cor; ctx.shadowBlur = 8;
      ctx.beginPath();
      tr.pts.forEach(([fx,fy],k)=>{ const x = r.left + fx*r.width, y = r.top + fy*r.height; k ? ctx.lineTo(x,y) : ctx.moveTo(x,y); });
      if (tr.pts.length === 1){ const [fx,fy] = tr.pts[0]; ctx.lineTo(r.left + fx*r.width + .1, r.top + fy*r.height); }
      ctx.stroke();
    }
    ctx.shadowBlur = 0;
    for (let i = pings.length-1; i >= 0; i--){
      const pg = pings[i], idade = ag - pg.t;
      if (idade > 2600){ pings.splice(i,1); continue; }
      const p = ponto(pg.jog, pg.fx, pg.fy); if (!p) continue;
      for (let k = 0; k < 3; k++){
        const u = ((idade/900) + k/3) % 1;
        ctx.globalAlpha = (1-u) * (idade > 2000 ? (2600-idade)/600 : 1);
        ctx.strokeStyle = pg.cor; ctx.lineWidth = 3;
        ctx.beginPath(); ctx.arc(p.x, p.y, 8 + u*40, 0, 7); ctx.stroke();
      }
      ctx.globalAlpha = idade > 2000 ? (2600-idade)/600 : 1;
      ctx.fillStyle = pg.cor; ctx.beginPath(); ctx.arc(p.x, p.y, 6, 0, 7); ctx.fill();
      ctx.font = '600 12px "EB Garamond", serif'; ctx.textAlign = 'center'; ctx.fillStyle = '#fff';
      ctx.fillText(pg.quem, p.x, p.y - 52);
    }
    ctx.globalAlpha = 1;
  }
  requestAnimationFrame(quadro);
  return { modoDesenho };
})();
