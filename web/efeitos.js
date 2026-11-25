// Efeitos de magia desenhados em código (sem vídeos). Uso:
//   Efeitos.lancar({de:{x,y}, para:{x,y}, cor:'W'|'U'|'B'|'R'|'G'|'C', acao:'destruir'|'exilar'|'anular', alvo: elementoDaCarta})
// Devolve uma Promise que resolve quando a animação termina.
const Efeitos = (()=>{
  const PALETA = {
    W:{nucleo:'#fffdf0', halo:'#ffe08a', part:['#fff6c8','#ffd75e','#ffffff'], brilho:'#ffcf4a'},
    U:{nucleo:'#e8fbff', halo:'#3fa9ff', part:['#9fe3ff','#4fb6ff','#d8f6ff'], brilho:'#2d8cff'},
    B:{nucleo:'#f0d8ff', halo:'#7a2bd1', part:['#3a0f5c','#8f45e8','#1a0626'], brilho:'#9b4dff'},
    R:{nucleo:'#fff1c2', halo:'#ff4a12', part:['#ff8a1f','#ffcf3a','#d61f0a'], brilho:'#ff3a00'},
    G:{nucleo:'#f2ffd8', halo:'#3fd14a', part:['#7dff6a','#2e9e33','#c9ff8a'], brilho:'#2fe04a'},
    C:{nucleo:'#ffffff', halo:'#b8c4d0', part:['#e6ecf2','#9aa6b2','#ffffff'], brilho:'#c9d6e2'},
  };
  let cv, ctx, parts = [], ativos = [], rodando = false;
  function tela(){
    if (cv) return;
    cv = document.createElement('canvas');
    cv.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:9000';
    document.body.appendChild(cv); ctx = cv.getContext('2d');
    const aj = ()=>{ const d = devicePixelRatio||1; cv.width = innerWidth*d; cv.height = innerHeight*d; ctx.setTransform(d,0,0,d,0,0); };
    aj(); addEventListener('resize', aj);
  }
  const rnd = (a,b)=>a+Math.random()*(b-a), pick = a=>a[Math.floor(Math.random()*a.length)];
  function particula(o){ parts.push(Object.assign({vx:0,vy:0,g:0,atr:.96,vida:1,max:1,tam:3,cor:'#fff',forma:'bola',rot:0,vr:0}, o)); }

  function brilho(x,y,r,cor,a){
    const g = ctx.createRadialGradient(x,y,0,x,y,r);
    g.addColorStop(0, cor); g.addColorStop(1, 'transparent');
    ctx.globalAlpha = a; ctx.fillStyle = g; ctx.beginPath(); ctx.arc(x,y,r,0,7); ctx.fill();
  }
  function raio(x1,y1,x2,y2,cor,larg,desvio){
    ctx.strokeStyle = cor; ctx.lineWidth = larg; ctx.beginPath(); ctx.moveTo(x1,y1);
    const n = 6; for (let k=1;k<n;k++){ const t=k/n; ctx.lineTo(x1+(x2-x1)*t+rnd(-desvio,desvio), y1+(y2-y1)*t+rnd(-desvio,desvio)); }
    ctx.lineTo(x2,y2); ctx.stroke();
  }

  function quadro(){
    ctx.clearRect(0,0,cv.width,cv.height);
    ctx.globalCompositeOperation = 'lighter';
    const agora = performance.now();
    ativos = ativos.filter(a=>a.passo(agora)!==false);
    // partículas
    parts = parts.filter(p=>{
      p.vida -= 1/60/p.max; if (p.vida<=0) return false;
      p.vx*=p.atr; p.vy=p.vy*p.atr+p.g; p.x+=p.vx; p.y+=p.vy; p.rot+=p.vr;
      ctx.globalAlpha = Math.min(1,p.vida*1.6);
      if (p.escuro){ ctx.globalCompositeOperation='source-over'; }
      ctx.fillStyle = p.cor;
      if (p.forma==='folha'){ ctx.save(); ctx.translate(p.x,p.y); ctx.rotate(p.rot); ctx.beginPath(); ctx.ellipse(0,0,p.tam*1.6,p.tam*.7,0,0,7); ctx.fill(); ctx.restore(); }
      else if (p.forma==='cristal'){ ctx.save(); ctx.translate(p.x,p.y); ctx.rotate(p.rot); ctx.beginPath(); ctx.moveTo(0,-p.tam*1.8); ctx.lineTo(p.tam*.7,0); ctx.lineTo(0,p.tam*1.8); ctx.lineTo(-p.tam*.7,0); ctx.fill(); ctx.restore(); }
      else if (p.forma==='risco'){ ctx.strokeStyle=p.cor; ctx.lineWidth=p.tam*.6; ctx.beginPath(); ctx.moveTo(p.x,p.y); ctx.lineTo(p.x-p.vx*3,p.y-p.vy*3); ctx.stroke(); }
      else { ctx.beginPath(); ctx.arc(p.x,p.y,p.tam*(p.encolhe?p.vida:1),0,7); ctx.fill(); }
      ctx.globalCompositeOperation='lighter';
      return true;
    });
    ctx.globalAlpha = 1;
    if (ativos.length || parts.length) requestAnimationFrame(quadro); else { rodando = false; ctx.clearRect(0,0,cv.width,cv.height); }
  }
  function girar(){ if (!rodando){ rodando = true; requestAnimationFrame(quadro); } }

  // ---------- voo do projétil ----------
  function voo(cor, de, para, ms){
    const P = PALETA[cor]||PALETA.C;
    const dx = para.x-de.x, dy = para.y-de.y, dist = Math.hypot(dx,dy);
    const curva = Math.min(140, dist*.25) * (dx>0?-1:1);   // arco suave
    const mx = (de.x+para.x)/2 + (-dy/dist)*curva, my = (de.y+para.y)/2 + (dx/dist)*curva;
    const t0 = performance.now();
    return new Promise(fim=>{
      ativos.push({passo(t){
        let u = Math.min(1,(t-t0)/ms); const e = u*u*(3-2*u)*.4 + u*.6; // acelera no fim
        const x = (1-e)*(1-e)*de.x + 2*(1-e)*e*mx + e*e*para.x;
        const y = (1-e)*(1-e)*de.y + 2*(1-e)*e*my + e*e*para.y;
        const pul = 1+Math.sin(t/45)*.12, r = 16*pul;
        if (cor==='W'){ // raio de luz: feixe da origem até a ponta
          ctx.lineCap='round';
          ctx.globalAlpha=.35; raio(de.x,de.y,x,y,P.halo,14,3);
          ctx.globalAlpha=.9; raio(de.x,de.y,x,y,P.nucleo,4,2);
        }
        if (cor==='B'){ ctx.globalCompositeOperation='source-over'; brilho(x,y,r*3.4,'#12001fcc',.8); ctx.globalCompositeOperation='lighter'; }
        brilho(x,y,r*3,P.halo,.55); brilho(x,y,r*1.5,P.brilho,.8); brilho(x,y,r*.8,P.nucleo,1);
        // rastro
        for (let k=0;k<3;k++) particula({x:x+rnd(-5,5),y:y+rnd(-5,5),vx:rnd(-1,1),vy:rnd(-1,1)+(cor==='R'?.6:0),tam:rnd(2,5.5),cor:pick(P.part),max:rnd(.35,.7),encolhe:true,escuro:cor==='B'&&Math.random()<.4});
        if (cor==='G' && Math.random()<.35) particula({x,y,vx:rnd(-2,2),vy:rnd(-2,1),g:.06,tam:rnd(3,5),cor:pick(P.part),forma:'folha',vr:rnd(-.2,.2),max:.9});
        if (cor==='U' && Math.random()<.3) particula({x,y,vx:rnd(-2,2),vy:rnd(-2,2),tam:rnd(2,4),cor:pick(P.part),forma:'cristal',vr:rnd(-.15,.15),max:.6});
        if (cor==='R' && Math.random()<.5) particula({x,y,vx:rnd(-1.5,1.5),vy:rnd(0,1.5),g:.12,tam:rnd(1.5,2.5),cor:'#ffd36a',max:.8,forma:'risco'});
        // faíscas elétricas
        if (Math.random()<.25){ ctx.globalAlpha=.8; const a=rnd(0,7), L=rnd(14,30); raio(x,y,x+Math.cos(a)*L,y+Math.sin(a)*L,P.nucleo,1.4,4); }
        if (u>=1){ fim(); return false; }
      }});
      girar();
    });
  }

  // ---------- impacto ----------
  function impacto(cor, p, acao){
    const P = PALETA[cor]||PALETA.C, t0 = performance.now();
    const n = acao==='destruir'?70:45;
    for (let k=0;k<n;k++){ const a=rnd(0,7), v=rnd(2,acao==='destruir'?11:7);
      particula({x:p.x,y:p.y,vx:Math.cos(a)*v,vy:Math.sin(a)*v,g:cor==='R'?.15:.04,atr:.93,tam:rnd(2,5),cor:pick(P.part),max:rnd(.5,1.1),encolhe:true,
        forma: cor==='G'&&k%3===0?'folha' : cor==='U'&&k%3===0?'cristal' : k%4===0?'risco':'bola', vr:rnd(-.3,.3), escuro:cor==='B'&&k%2===0}); }
    ativos.push({passo(t){
      const u = (t-t0)/550; if (u>=1) return false;
      brilho(p.x,p.y,40+u*160,P.brilho,(1-u)*.9);
      brilho(p.x,p.y,30*(1-u)+4,P.nucleo,1-u);
      ctx.globalAlpha=(1-u)*.9; ctx.strokeStyle=P.halo; ctx.lineWidth=6*(1-u)+1;
      ctx.beginPath(); ctx.arc(p.x,p.y,20+u*120,0,7); ctx.stroke();
      if (u<.35){ for(let k=0;k<5;k++){ const a=rnd(0,7),L=rnd(40,110); ctx.globalAlpha=.7*(1-u*2.5); raio(p.x,p.y,p.x+Math.cos(a)*L,p.y+Math.sin(a)*L,P.nucleo,2,8); } }
    }});
    girar();
  }

  // ---------- o que acontece com a carta ----------
  function fantasma(el, ret){
    if (!el) return null;
    const r = ret || el.getBoundingClientRect(), f = el.cloneNode(true);
    f.style.cssText += `;position:fixed;left:${r.left}px;top:${r.top}px;width:${r.width}px;height:${r.height}px;margin:0;z-index:8999;pointer-events:none;transition:none;visibility:visible`;
    document.body.appendChild(f); return f;
  }
  function destinoCarta(f, cor, acao){
    if (!f) return Promise.resolve();
    const P = PALETA[cor]||PALETA.C;
    const anim = {
      destruir: [ {transform:'translate(0,0) rotate(0)',filter:'none',opacity:1},
                  {transform:'translate(-7px,2px) rotate(-3deg)',filter:`brightness(2.2) drop-shadow(0 0 18px ${P.brilho})`,opacity:1,offset:.12},
                  {transform:'translate(6px,-2px) rotate(3deg)',filter:'brightness(1.4) grayscale(.5)',opacity:1,offset:.25},
                  {transform:'translate(0,40px) rotate(8deg) scale(.85)',filter:'brightness(.3) grayscale(1)',opacity:0} ],
      exilar:   [ {transform:'scale(1)',filter:'none',opacity:1},
                  {transform:'scale(1.06)',filter:`brightness(2.6) saturate(0) drop-shadow(0 0 24px ${P.brilho})`,opacity:1,offset:.3},
                  {transform:'scale(1.25) translateY(-20px)',filter:'brightness(4) saturate(0) blur(6px)',opacity:0} ],
      anular:   [ {transform:'scale(1) rotate(0)',filter:'none',opacity:1},
                  {transform:'scale(1.05) rotate(-2deg)',filter:`brightness(2) drop-shadow(0 0 16px ${P.brilho})`,opacity:1,offset:.15},
                  {transform:'scale(.6) rotate(12deg)',filter:'brightness(.5) blur(2px)',opacity:0} ],
    }[acao] || [];
    // exilar solta "pó de luz" subindo da carta
    if (acao==='exilar'){ const r=f.getBoundingClientRect();
      for (let k=0;k<60;k++) particula({x:rnd(r.left,r.right),y:rnd(r.top,r.bottom),vx:rnd(-.5,.5),vy:rnd(-2.5,-.5),atr:.99,tam:rnd(1.5,3.5),cor:pick(P.part),max:rnd(.7,1.4),encolhe:true}); girar(); }
    if (acao==='anular'){ const r=f.getBoundingClientRect(), cx=(r.left+r.right)/2, cy=(r.top+r.bottom)/2;
      for (let k=0;k<26;k++){ const a=rnd(0,7),v=rnd(2,7); particula({x:cx,y:cy,vx:Math.cos(a)*v,vy:Math.sin(a)*v,g:.2,atr:.97,tam:rnd(3,6),cor:'#d9cfb8',forma:'cristal',vr:rnd(-.3,.3),max:.9}); } girar(); }
    return f.animate(anim,{duration:acao==='exilar'?900:700,easing:'ease-in',fill:'forwards'}).finished.then(()=>f.remove());
  }

  async function lancar({de, para, cor='C', acao='destruir', alvo=null, ret=null, ms}){
    tela();
    const f = fantasma(alvo, ret);
    if (alvo && !para){ const r = (f||alvo).getBoundingClientRect(); para = {x:(r.left+r.right)/2, y:(r.top+r.bottom)/2}; }
    const dist = Math.hypot(para.x-de.x, para.y-de.y);
    if (typeof Sons !== 'undefined') Sons.tocar('lancar', cor);
    await voo(cor, de, para, ms || Math.max(380, Math.min(750, dist*1.1)));
    if (typeof Sons !== 'undefined') Sons.tocar('impacto', cor);
    impacto(cor, para, acao);
    await destinoCarta(f, cor, acao);
  }
  return { lancar, cores: Object.keys(PALETA) };
})();
