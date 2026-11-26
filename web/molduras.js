// Molduras de avatar (prêmios da trilha de níveis): uma a cada 10 níveis, cada vez mais exótica.
// molduraHtml(k, conteudo): k = '' | '10' | '20' | '30' | '40' | '50'; conteudo = o HTML da foto (fica no centro).
(function(){
  const css = `
.mld{position:relative;display:inline-grid;place-items:center;flex:none;vertical-align:middle}
.mld>.mldFoto{position:relative;z-index:2;width:72%;height:72%;border-radius:50%;overflow:hidden;display:grid;place-items:center}
.mld>.mldFoto>*{width:100%!important;height:100%!important;margin:0!important;border-radius:50%}
.mld .anel{position:absolute;inset:6%;border-radius:50%;z-index:1;pointer-events:none}
.mld.m10 .anel{background:conic-gradient(#8a5a2a,#e0a060,#7a4a1a,#d09050,#8a5a2a);-webkit-mask:radial-gradient(circle,transparent 63%,#000 65%);mask:radial-gradient(circle,transparent 63%,#000 65%)}
.mld.m20 .anel{background:repeating-conic-gradient(#f0f4f8 0 10deg,#8a96a2 10deg 20deg);-webkit-mask:radial-gradient(circle,transparent 62%,#000 64%);mask:radial-gradient(circle,transparent 62%,#000 64%);box-shadow:0 0 10px #d0e0f066}
.mld.m20::after{content:'';position:absolute;inset:2%;border-radius:50%;border:2px solid #c8d4e0;z-index:1;pointer-events:none}
.mld.m30 .anel{inset:2%;background:repeating-conic-gradient(from 0deg,#fff4c0 0deg 1.2deg,#b8862a 1.2deg 6deg),conic-gradient(from 200deg,#8a5a14,#ffe9a0,#c08a2a,#fff4c8,#8a5a14);background-blend-mode:overlay,normal;
  -webkit-mask:radial-gradient(circle,transparent 62%,#000 63%,#000 70%,transparent 71%,transparent 73%,#000 74%,#000 77%,transparent 78%);mask:radial-gradient(circle,transparent 62%,#000 63%,#000 70%,transparent 71%,transparent 73%,#000 74%,#000 77%,transparent 78%);filter:drop-shadow(0 0 5px #ffd06099)}
.mld.m30::before{content:'';position:absolute;inset:2%;border-radius:50%;z-index:2;pointer-events:none;background:linear-gradient(135deg,transparent 35%,#ffffff66 48%,transparent 60%);
  -webkit-mask:radial-gradient(circle,transparent 62%,#000 63%,#000 77%,transparent 78%);mask:radial-gradient(circle,transparent 62%,#000 63%,#000 77%,transparent 78%)}
.mld.m30 .gema{position:absolute;top:0;left:50%;width:15%;height:15%;transform:translateX(-50%) rotate(45deg);z-index:3;border-radius:2px;background:linear-gradient(135deg,#fff 0%,#9fe8ff 25%,#3a8ad0 60%,#1a3a8a);box-shadow:0 0 0 2px #e8c060,0 0 0 3px #6a4a14,0 0 10px #8ad0ff}
.mld.m30 .pino{position:absolute;width:5%;height:5%;border-radius:50%;z-index:3;background:radial-gradient(circle at 35% 30%,#fff4c0,#b8862a);box-shadow:0 0 0 1px #6a4a14}
.mld.m40 .anel{inset:-2%;background:conic-gradient(from 0deg,#ff3a0a,#ffb040,#ff6a1a,#fff0a0,#ff3a0a);-webkit-mask:radial-gradient(circle,transparent 62%,#000 64%,#000 72%,transparent 74%);mask:radial-gradient(circle,transparent 62%,#000 64%,#000 72%,transparent 74%);filter:url(#mldChama) drop-shadow(0 0 6px #ff6a1a);animation:mldGira 4s linear infinite}
.mld.m40 .anel2,.mld.m50 .anel2{position:absolute;inset:13%;border-radius:50%;z-index:1;pointer-events:none}
.mld.m40 .anel2{box-shadow:0 0 0 3px #ffb040,0 0 14px #ff6a1a}
.mld.m50 .anel{inset:-10%;background:conic-gradient(from 0deg,#c890ff,#5ad0ff,#ffe08a,#ff5ac8,#c890ff);-webkit-mask:radial-gradient(circle,transparent 48%,#000 52%,#000 62%,transparent 70%);mask:radial-gradient(circle,transparent 48%,#000 52%,#000 62%,transparent 70%);filter:url(#mldChama2) drop-shadow(0 0 10px #c890ff);animation:mldGira 3s linear infinite}
.mld.m50 .anel2{box-shadow:0 0 0 2px #fff,0 0 0 4px #c890ff,0 0 26px #c890ff,0 0 50px #5ad0ff88}
.mld.m50 .est{position:absolute;inset:-16%;border-radius:50%;z-index:1;animation:mldGira 14s linear infinite reverse;pointer-events:none}
.mld.m50 .est i{position:absolute;left:50%;top:50%;width:7%;height:7%;margin:-3.5% 0 0 -3.5%;background:#f0e0ff;clip-path:polygon(50% 0,62% 38%,100% 50%,62% 62%,50% 100%,38% 62%,0 50%,38% 38%);filter:drop-shadow(0 0 4px #c890ff)}
.mld.m50 .orbe{position:absolute;width:9%;height:9%;border-radius:50%;background:#fff;box-shadow:0 0 10px 3px #c890ff;z-index:3;left:45.5%;top:45.5%;animation:mldOrbita 2.6s linear infinite}
.mld.m50 .orbe.b{animation-delay:-1.3s;background:#ffe08a;box-shadow:0 0 10px 3px #ffb040}
@keyframes mldGira{to{transform:rotate(360deg)}}
@keyframes mldOrbita{from{transform:rotate(0deg) translateX(var(--orb,52px)) rotate(0deg)}to{transform:rotate(360deg) translateX(var(--orb,52px)) rotate(-360deg)}}
.tituloLenda{font-family:'Metamorphous',serif;letter-spacing:.18em;background:linear-gradient(90deg,#c890ff,#ffe08a,#5ad0ff);-webkit-background-clip:text;background-clip:text;color:transparent}
`;
  const st = document.createElement('style'); st.textContent = css; document.head.appendChild(st);
  const pronto = () => document.body.insertAdjacentHTML('beforeend', `<svg width="0" height="0" style="position:absolute">
   <filter id="mldChama" x="-30%" y="-30%" width="160%" height="160%"><feTurbulence type="fractalNoise" baseFrequency="0.04 0.07" numOctaves="2" seed="5" result="r"><animate attributeName="baseFrequency" dur="5s" values="0.04 0.07;0.06 0.1;0.04 0.07" repeatCount="indefinite"/></feTurbulence><feDisplacementMap in="SourceGraphic" in2="r" scale="10" xChannelSelector="R" yChannelSelector="G"/><feGaussianBlur stdDeviation="1"/></filter>
   <filter id="mldChama2" x="-40%" y="-40%" width="180%" height="180%"><feTurbulence type="fractalNoise" baseFrequency="0.03 0.06" numOctaves="2" seed="9" result="r"><animate attributeName="baseFrequency" dur="4s" values="0.03 0.06;0.05 0.09;0.03 0.06" repeatCount="indefinite"/></feTurbulence><feDisplacementMap in="SourceGraphic" in2="r" scale="16" xChannelSelector="R" yChannelSelector="G"/><feGaussianBlur stdDeviation="1.4"/></filter></svg>`);
  if (document.body) pronto(); else addEventListener('DOMContentLoaded', pronto);
  window.MOLDURAS = {'':'Sem moldura', '10':'Bronze', '20':'Prata', '30':'Ouro', '40':'Chamas', '50':'Lendária'};
  // tam = tamanho total em px (a moldura cresce em volta da foto)
  window.molduraHtml = function(k, conteudo, tam){
    k = String(k || ''); tam = tam || 64;
    const base = `width:${tam}px;height:${tam}px;--orb:${Math.round(tam*.6)}px`;
    const foto = `<div class="mldFoto">${conteudo}</div>`;
    if (!k) return `<div class="mld" style="${base}">${foto}</div>`;
    if (k === '30') return `<div class="mld m30" style="${base}"><div class="anel"></div>${foto}<i class="gema"></i>${[90,180,270].map(g=>`<i class="pino" style="left:${50+44*Math.sin(g*Math.PI/180)-2.5}%;top:${50-44*Math.cos(g*Math.PI/180)-2.5}%"></i>`).join('')}</div>`;
    if (k === '40') return `<div class="mld m40" style="${base}"><div class="anel"></div><div class="anel2"></div>${foto}</div>`;
    if (k === '50') return `<div class="mld m50" style="${base}"><div class="est">${Array.from({length:8},(_,i)=>`<i style="transform:rotate(${i*45}deg) translateY(-${Math.round(tam*.62)}px)"></i>`).join('')}</div><div class="anel"></div><div class="anel2"></div>${foto}<div class="orbe"></div><div class="orbe b"></div></div>`;
    return `<div class="mld m${k}" style="${base}"><div class="anel"></div>${foto}</div>`;
  };
})();
