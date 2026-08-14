/** 新春撒金箔 / 礼花彩纸轻量粒子特效（Canvas Confetti）
 * 用于：完成拜年、到达第10户里程碑、压轴动画等高光时刻
 */

interface Particle {
  x: number;
  y: number;
  vx: number;
  vy: number;
  size: number;
  color: string;
  rotation: number;
  vRot: number;
  shape: 'rect' | 'circle' | 'star';
  opacity: number;
}

const CNY_COLORS = [
  '#f59e0b', // 鎏金
  '#fbbf24', // 明黄
  '#dc2626', // 朱红
  '#ef4444', // 鲜红
  '#ea580c', // 暖橙
  '#ffffff', // 银白
  '#fef08a', // 金白
];

export function launchConfetti(count = 45): void {
  const canvas = document.createElement('canvas');
  canvas.className = 'baibai-confetti-canvas';
  canvas.style.cssText =
    'position:fixed;inset:0;width:100vw;height:100vh;pointer-events:none;z-index:9999;';
  document.body.appendChild(canvas);

  const rawCtx = canvas.getContext('2d');
  if (!rawCtx) {
    canvas.remove();
    return;
  }
  const ctx: CanvasRenderingContext2D = rawCtx;

  const dpr = window.devicePixelRatio || 1;
  const w = window.innerWidth;
  const h = window.innerHeight;
  canvas.width = w * dpr;
  canvas.height = h * dpr;
  ctx.scale(dpr, dpr);

  const particles: Particle[] = [];
  for (let i = 0; i < count; i++) {
    const fromX = w * (0.2 + 0.6 * Math.random());
    const fromY = h * 0.4 + (Math.random() - 0.5) * 80;
    const angle = -Math.PI / 2 + (Math.random() - 0.5) * 1.5;
    const speed = 7 + Math.random() * 9;
    particles.push({
      x: fromX,
      y: fromY,
      vx: Math.cos(angle) * speed,
      vy: Math.sin(angle) * speed,
      size: 6 + Math.random() * 6,
      color: CNY_COLORS[Math.floor(Math.random() * CNY_COLORS.length)],
      rotation: Math.random() * Math.PI * 2,
      vRot: (Math.random() - 0.5) * 0.25,
      shape: Math.random() < 0.6 ? 'rect' : Math.random() < 0.85 ? 'circle' : 'star',
      opacity: 1,
    });
  }

  let startTime = 0;
  const duration = 2600;

  function loop(now: number): void {
    if (!startTime) startTime = now;
    const elapsed = now - startTime;
    if (elapsed > duration || particles.length === 0) {
      canvas.remove();
      return;
    }

    ctx.clearRect(0, 0, w, h);

    for (let i = particles.length - 1; i >= 0; i--) {
      const p = particles[i];
      p.x += p.vx;
      p.y += p.vy;
      p.vy += 0.28; // 重力
      p.vx *= 0.985; // 阻力
      p.rotation += p.vRot;
      p.opacity = Math.max(0, 1 - elapsed / duration);

      ctx.save();
      ctx.translate(p.x, p.y);
      ctx.rotate(p.rotation);
      ctx.globalAlpha = p.opacity;
      ctx.fillStyle = p.color;

      if (p.shape === 'rect') {
        ctx.fillRect(-p.size / 2, -p.size / 3, p.size, p.size * 0.65);
      } else if (p.shape === 'circle') {
        ctx.beginPath();
        ctx.arc(0, 0, p.size * 0.45, 0, Math.PI * 2);
        ctx.fill();
      } else {
        // Star / diamond
        ctx.beginPath();
        ctx.moveTo(0, -p.size * 0.6);
        ctx.lineTo(p.size * 0.4, 0);
        ctx.lineTo(0, p.size * 0.6);
        ctx.lineTo(-p.size * 0.4, 0);
        ctx.closePath();
        ctx.fill();
      }

      ctx.restore();
    }

    requestAnimationFrame(loop);
  }

  requestAnimationFrame(loop);
}
