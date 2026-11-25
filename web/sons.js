// Sons da Mesa, sintetizados na hora (Web Audio): nada para baixar, nada de licença.
// Uso: Sons.tocar('hover' | 'clique' | 'lancar' | 'impacto' | 'carta' | 'compra' | 'ataque' | 'moeda' | 'aviso', cor?)
const Sons = (()=>{
  let ctx = null, mestre = null;
  const cfg = {ui: true, fx: true, vol: .05};
  function pronto(){
    if (!ctx){
      try { ctx = new (window.AudioContext || window.webkitAudioContext)(); } catch(e){ return false; }
      mestre = ctx.createGain(); mestre.connect(ctx.destination);
    }
    if (ctx.state === 'suspended') ctx.resume();
    mestre.gain.value = cfg.vol;
    return true;
  }
  // o navegador só libera áudio depois de um clique
  addEventListener('pointerdown', ()=>pronto(), {once:true, capture:true});

  function tom({f=440, f2=null, dur=.1, tipo='sine', vol=.2, ataque=.004, atraso=0, destino=null}){
    const t = ctx.currentTime + atraso, o = ctx.createOscillator(), g = ctx.createGain();
    o.type = tipo; o.frequency.setValueAtTime(f, t);
    if (f2) o.frequency.exponentialRampToValueAtTime(f2, t + dur);
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(vol, t + ataque); g.gain.exponentialRampToValueAtTime(.0001, t + dur);
    o.connect(g); g.connect(destino || mestre); o.start(t); o.stop(t + dur + .02);
  }
  let ruidoBuf = null;
  function ruido({dur=.3, vol=.2, filtro='bandpass', f=1200, f2=null, q=1, atraso=0}){
    if (!ruidoBuf){ ruidoBuf = ctx.createBuffer(1, ctx.sampleRate*2, ctx.sampleRate); const d = ruidoBuf.getChannelData(0); for (let i=0;i<d.length;i++) d[i] = Math.random()*2-1; }
    const t = ctx.currentTime + atraso, s = ctx.createBufferSource(), fl = ctx.createBiquadFilter(), g = ctx.createGain();
    s.buffer = ruidoBuf; fl.type = filtro; fl.Q.value = q; fl.frequency.setValueAtTime(f, t);
    if (f2) fl.frequency.exponentialRampToValueAtTime(f2, t + dur);
    g.gain.setValueAtTime(0, t); g.gain.linearRampToValueAtTime(vol, t + .02); g.gain.exponentialRampToValueAtTime(.0001, t + dur);
    s.connect(fl); fl.connect(g); g.connect(mestre); s.start(t, Math.random()); s.stop(t + dur + .05);
  }
  // cada cor de mana tem um "timbre" diferente na magia
  const COR = {W:{f:880, tipo:'sine'}, U:{f:660, tipo:'triangle'}, B:{f:220, tipo:'sawtooth'}, R:{f:330, tipo:'square'}, G:{f:440, tipo:'triangle'}, C:{f:550, tipo:'sine'}};
  const SOM = {
    // brilhinho: 3 notas agudas bem curtas, de uma escala "mágica" (pentatônica), com um chiado suave por cima
    hover(){
      const esc = [1568, 1760, 2093, 2349, 2637, 3136, 3520];
      const base = Math.floor(Math.random()*3);
      [0, .035, .07].forEach((a,i)=>tom({f: esc[base + i*2] || 3520, dur: .22 - i*.04, vol: .035 - i*.008, tipo:'sine', ataque:.002, atraso:a}));
      ruido({dur:.18, vol:.012, filtro:'highpass', f:7000, q:.5});
    },
    // clique: um "plim" de sino, duas notas subindo
    clique(){
      tom({f:1318, dur:.35, vol:.06, tipo:'sine'}); tom({f:2637, dur:.25, vol:.025, tipo:'sine'});
      tom({f:1976, dur:.4, vol:.05, tipo:'sine', atraso:.06}); tom({f:3951, dur:.2, vol:.015, tipo:'sine', atraso:.06});
      ruido({dur:.25, vol:.015, filtro:'highpass', f:6000, q:.5, atraso:.02});
    },
    aviso(){ tom({f:660, dur:.12, vol:.08}); tom({f:880, dur:.18, vol:.08, atraso:.1}); },
    carta(){ ruido({dur:.12, vol:.18, filtro:'lowpass', f:900}); tom({f:140, f2:90, dur:.12, vol:.12}); },
    compra(){ ruido({dur:.22, vol:.12, filtro:'bandpass', f:2500, f2:5000, q:.7}); },
    moeda(){ [0, .07, .16].forEach((a,i)=>tom({f:2100 - i*120, dur:.25, vol:.07, tipo:'triangle', atraso:a})); },
    ataque(){ ruido({dur:.25, vol:.22, filtro:'bandpass', f:600, f2:2400, q:1.2}); tom({f:180, f2:70, dur:.25, vol:.14, tipo:'sawtooth', atraso:.05}); },
    lancar(cor){ const c = COR[cor] || COR.C;
      ruido({dur:.6, vol:.14, filtro:'bandpass', f:400, f2:3000, q:2});
      tom({f:c.f, f2:c.f*2, dur:.55, vol:.07, tipo:c.tipo}); tom({f:c.f*1.5, f2:c.f*3, dur:.5, vol:.04, tipo:'sine', atraso:.05}); },
    impacto(cor){ const c = COR[cor] || COR.C;
      ruido({dur:.5, vol:.3, filtro:'lowpass', f:2500, f2:200});
      tom({f:110, f2:40, dur:.45, vol:.3, tipo:'sine'}); tom({f:c.f, f2:c.f/2, dur:.35, vol:.06, tipo:c.tipo, atraso:.02}); },
  };
  let escolhaCache = null;
  // escolhas do Twelvez (padrão da Mesa); a página "Escolher sons" pode trocar por cima
  const PADRAO = {hover:'sons/kenney/rpg/beltHandle1.ogg', maoCarta:['sons/kenney/rpg/drawKnife1.ogg','sons/kenney/rpg/drawKnife2.ogg']};
  const MUDOS = new Set(['clique','carta','compra','moeda','ataque','lancar']);   // sem som (impacto e aviso continuam gerados)
  function escolhidos(){ if (!escolhaCache){ let l = {}; try { l = JSON.parse(localStorage.getItem('sonsEscolhidos')||'{}'); } catch(e){} escolhaCache = Object.assign({}, PADRAO, l); } return escolhaCache; }
  addEventListener('storage', ev=>{ if (ev.key === 'sonsEscolhidos') escolhaCache = null; });
  const audios = {};
  function tocarArquivo(url){
    // alguns "clones" para o mesmo som poder tocar várias vezes seguidas (passar o mouse rápido)
    const l = audios[url] = audios[url] || [];
    let a = l.find(x=>x.paused || x.ended);
    if (!a){ if (l.length >= 4) a = l[0]; else { a = new Audio(url); a.preload = 'auto'; l.push(a); } }
    a.currentTime = 0; a.volume = Math.min(1, cfg.vol * 1.2); a.play().catch(()=>{});
  }
  const DE_UI = new Set(['hover','clique','maoCarta']);
  let ultimoHover = 0;
  function tocar(nome, arg){
    const deUi = DE_UI.has(nome);
    if (deUi ? !cfg.ui : !cfg.fx) return;
    if (nome === 'hover' || nome === 'maoCarta'){ const ag = performance.now(); if (ag - ultimoHover < 60) return; ultimoHover = ag; }
    // som escolhido na página "Escolher sons" (arquivo do Kenney)? toca ele; senão, o gerado
    let arq = escolhidos()[nome];
    if (Array.isArray(arq)) arq = arq[Math.floor(Math.random()*arq.length)];   // alterna entre as variações
    if (arq){ tocarArquivo(arq); return; }
    if (MUDOS.has(nome) && !(nome in (escolhaCache||{}))) return;
    if (!ctx || ctx.state !== 'running'){ if (!ctx) return; pronto(); if (ctx.state !== 'running') return; }
    try { SOM[nome] && SOM[nome](arg); } catch(e){}
  }
  return { tocar, configurar(c){ Object.assign(cfg, c); if (mestre) mestre.gain.value = cfg.vol; } };
})();
