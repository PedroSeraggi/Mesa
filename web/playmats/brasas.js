// Playmat animado "Brasas de Akros" (prêmio do nível 10).
// Usa camadas CSS por cima do fundo do lugar do jogador. As animações usam atraso negativo baseado no relógio,
// então continuam do mesmo ponto quando a mesa é redesenhada (não "reiniciam" a cada jogada).
(function(){
  const css = `
.pmFx{position:absolute;inset:0;overflow:hidden;pointer-events:none;z-index:0;border-radius:inherit}
.pmFx.brasas{background:
   radial-gradient(ellipse 120% 70% at 50% 120%,#ff6a1a33,transparent 60%),
   radial-gradient(ellipse at 50% 45%,#2a1610 0%,#140a07 55%,#070403 100%)}
.pmFx.brasas .ruido{position:absolute;inset:0;opacity:.55;mix-blend-mode:overlay;background-image:url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='260' height='260'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.9' numOctaves='3' stitchTiles='stitch'/%3E%3CfeColorMatrix values='0 0 0 0 .5  0 0 0 0 .35  0 0 0 0 .3  0 0 0 .7 0'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E")}
.pmFx.brasas .placas{position:absolute;inset:0;opacity:.5;background:
   radial-gradient(ellipse 30% 22% at 22% 30%,#3a2218 0,transparent 100%),radial-gradient(ellipse 26% 20% at 74% 62%,#331d14 0,transparent 100%),
   radial-gradient(ellipse 22% 26% at 60% 22%,#2e1a12 0,transparent 100%),radial-gradient(ellipse 28% 20% at 30% 78%,#2a170f 0,transparent 100%)}
.pmFx.brasas .lava{position:absolute;inset:-2%;background:url('playmats/brasas-lava.svg') center/cover no-repeat;mix-blend-mode:screen;
   animation:pmRespira 5.5s ease-in-out infinite;animation-delay:var(--f55)}
@keyframes pmRespira{0%,100%{opacity:.55;filter:brightness(.8) saturate(1)}45%{opacity:1;filter:brightness(1.25) saturate(1.3)}60%{opacity:.85}}
.pmFx.brasas .calor{position:absolute;left:-10%;right:-10%;bottom:-30%;height:70%;background:radial-gradient(ellipse at 50% 100%,#ff7a2a66,#ff3a0a22 45%,transparent 70%);
   animation:pmCalor 3.2s ease-in-out infinite;animation-delay:var(--f32);filter:url(#pmOnda)}
@keyframes pmCalor{50%{opacity:.6;transform:scaleY(1.08)}}
.pmFx.brasas .fumaca{position:absolute;width:70%;height:55%;border-radius:50%;background:radial-gradient(ellipse,#1a0c0866,transparent 70%);filter:blur(18px);animation:pmFumaca 38s linear infinite}
.pmFx.brasas .fumaca.a{top:10%;animation-delay:var(--f38)}.pmFx.brasas .fumaca.b{top:45%;animation-duration:52s;animation-delay:var(--f52);background:radial-gradient(ellipse,#3a1a0a55,transparent 70%)}
@keyframes pmFumaca{from{left:-75%}to{left:105%}}
.pmFx.brasas .selo{position:absolute;left:50%;top:50%;width:min(42%,300px);aspect-ratio:1;transform:translate(-50%,-50%);opacity:.5;mix-blend-mode:screen;
   animation:pmSelo 7s ease-in-out infinite;animation-delay:var(--f70)}
@keyframes pmSelo{50%{opacity:.85;filter:drop-shadow(0 0 10px #ff7a2a)}}
.pmFx.brasas .selo svg{width:100%;height:100%;animation:pmGiraSelo 160s linear infinite;animation-delay:var(--f160)}
@keyframes pmGiraSelo{to{transform:rotate(360deg)}}
.pmFx.brasas .b{position:absolute;bottom:-8px;border-radius:50%;background:radial-gradient(circle,#fff6c0 0%,#ffb040 40%,#ff5a10 70%,transparent 72%);
   box-shadow:0 0 6px 1px #ff7a1a,0 0 14px 2px #ff5a0a88;animation:pmSobe var(--d) linear infinite,pmPisca .9s ease-in-out infinite;animation-delay:var(--a),var(--a)}
@keyframes pmSobe{0%{transform:translate(0,0);opacity:0}8%{opacity:1}50%{transform:translate(var(--x),-55vh)}85%{opacity:.8}100%{transform:translate(calc(var(--x) * -.6),-110vh);opacity:0}}
@keyframes pmPisca{50%{filter:brightness(.55)}}
.pmFx.brasas .fa{position:absolute;width:2px;height:14px;border-radius:2px;background:linear-gradient(#fff6c0,#ff7a1a00);opacity:0;animation:pmFaisca var(--d) ease-out infinite;animation-delay:var(--a)}
@keyframes pmFaisca{0%,88%{opacity:0;transform:translate(0,0) rotate(var(--r))}89%{opacity:1}100%{opacity:0;transform:translate(var(--x),-90px) rotate(var(--r))}}
.pmFx.brasas .vinheta{position:absolute;inset:0;box-shadow:inset 0 0 80px 20px #000c}
`;
  const st = document.createElement('style'); st.textContent = css; document.head.appendChild(st);
  // filtro de onda de calor
  document.body.insertAdjacentHTML('beforeend', `<svg width="0" height="0" style="position:absolute"><filter id="pmOnda"><feTurbulence type="fractalNoise" baseFrequency="0.012 0.04" numOctaves="2" seed="2" result="r"><animate attributeName="seed" dur="1.6s" values="2;9;2" repeatCount="indefinite"/></feTurbulence><feDisplacementMap in="SourceGraphic" in2="r" scale="14"/></filter></svg>`);
  // o selo: círculo de runas com o M da Mesa no meio
  const runas = 'ᚠᚢᚦᚨᚱᚲᚷᚹᚺᚾᛁᛃᛇᛈᛉᛊᛏᛒᛖᛗᛚᛜᛞᛟ';
  const selo = `<svg viewBox="0 0 200 200"><defs><linearGradient id="pmSeloG" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#ffd070"/><stop offset="1" stop-color="#ff5a10"/></linearGradient>
    <path id="pmCirc" d="M100 100 m-78 0 a78 78 0 1 1 156 0 a78 78 0 1 1 -156 0"/></defs>
    <g fill="none" stroke="url(#pmSeloG)" stroke-width="1.6"><circle cx="100" cy="100" r="92"/><circle cx="100" cy="100" r="86" stroke-width=".8"/><circle cx="100" cy="100" r="66"/>
    <path d="M100 34 L157 133 L43 133 Z" stroke-width="1"/><path d="M100 166 L43 67 L157 67 Z" stroke-width="1"/></g>
    <text font-family="serif" font-size="11" fill="#ffb050" letter-spacing="3.2"><textPath href="#pmCirc">${runas}${runas.slice(0,6)}</textPath></text>
    <text x="100" y="117" text-anchor="middle" font-family="Metamorphous,serif" font-size="52" fill="url(#pmSeloG)">M</text></svg>`;
  // brasas e faíscas: sempre as mesmas posições (sorteio fixo), para não "pular" ao redesenhar
  let semente = 11; const r = () => (semente = (semente * 9301 + 49297) % 233280) / 233280;
  const brasas = Array.from({length: 34}, () => { const t = 1.5 + r()*3.2, d = 7 + r()*9; return {l: r()*100, t, d, x: (r()-.5)*90, a: r()*d}; });
  const faiscas = Array.from({length: 6}, () => ({l: 10 + r()*80, b: 5 + r()*70, d: 5 + r()*7, x: (r()-.5)*60, rot: (r()-.5)*50, a: r()*8}));
  const fase = (dur) => `-${((performance.now() / 1000) % dur).toFixed(2)}s`;
  window.playmatAnimado = {
    brasas: {
      nome: 'Brasas de Akros',
      bg: 'radial-gradient(ellipse at 50% 45%,#2a1610 0%,#140a07 55%,#070403 100%)',
      html(){
        const agora = performance.now() / 1000;
        const vars = `--f55:${fase(5.5)};--f32:${fase(3.2)};--f38:${fase(38)};--f52:${fase(52)};--f70:${fase(7)};--f160:${fase(160)}`;
        return `<div class="pmFx brasas" style="${vars}"><div class="placas"></div><div class="ruido"></div><div class="fumaca a"></div><div class="fumaca b"></div>
          <div class="lava"></div><div class="calor"></div><div class="selo">${selo}</div>
          ${brasas.map(b => `<i class="b" style="left:${b.l.toFixed(1)}%;width:${b.t.toFixed(1)}px;height:${b.t.toFixed(1)}px;--d:${b.d.toFixed(1)}s;--x:${b.x.toFixed(0)}px;--a:-${((agora + b.a) % b.d).toFixed(2)}s"></i>`).join('')}
          ${faiscas.map(f => `<i class="fa" style="left:${f.l.toFixed(0)}%;bottom:${f.b.toFixed(0)}%;--d:${f.d.toFixed(1)}s;--x:${f.x.toFixed(0)}px;--r:${f.rot.toFixed(0)}deg;--a:-${((agora + f.a) % f.d).toFixed(2)}s"></i>`).join('')}
          <div class="vinheta"></div></div>`;
      }
    }
  };
})();
