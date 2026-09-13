/**
 * 应用内对话框（§7.8 文案规范）：替代 window.confirm / prompt / alert。
 *
 * 为什么不用原生对话框：原生 confirm/prompt 在移动端样式不可控、会打断页面、
 * 且安卓端是 Compose 自绘对话框——两端观感必须一致（审核清单 §2 跨端一致性）。
 */

const esc = (s: string): string =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

interface DialogParts {
  overlay: HTMLElement;
  body: HTMLElement;
  actions: HTMLElement;
}

function buildDialog(host: HTMLElement, title: string, bodyText: string): DialogParts {
  const overlay = document.createElement('div');
  overlay.className = 'dlg-overlay';
  const el = document.createElement('div');
  el.className = 'dlg';
  const h = document.createElement('h3');
  h.textContent = title;
  const p = document.createElement('p');
  p.textContent = bodyText;
  const actions = document.createElement('div');
  actions.className = 'dlg-actions';
  el.append(h, p, actions);
  overlay.appendChild(el);
  host.appendChild(overlay);
  return { overlay, body: p, actions };
}

function mkButton(label: string, cls: string): HTMLButtonElement {
  const b = document.createElement('button');
  b.className = cls;
  b.textContent = label;
  return b;
}

/** 确认对话框 → true/false */
export function confirmDialog(
  host: HTMLElement,
  title: string,
  body: string,
  okLabel: string,
  cancelLabel: string,
): Promise<boolean> {
  return new Promise((resolve) => {
    const { overlay, actions } = buildDialog(host, title, body);
    const ok = mkButton(okLabel, 'primary small');
    const cancel = mkButton(cancelLabel, 'ghost small');
    actions.append(ok, cancel);
    const done = (v: boolean): void => {
      overlay.remove();
      resolve(v);
    };
    ok.addEventListener('click', () => done(true));
    cancel.addEventListener('click', () => done(false));
  });
}

/** 提示对话框（仅确认） */
export function alertDialog(
  host: HTMLElement,
  title: string,
  body: string,
  okLabel = '知道了',
): Promise<void> {
  return new Promise((resolve) => {
    const { overlay, actions } = buildDialog(host, title, body);
    const ok = mkButton(okLabel, 'primary small');
    actions.append(ok);
    ok.addEventListener('click', () => {
      overlay.remove();
      resolve();
    });
  });
}

/**
 * 输入对话框 → 字符串（取消/空输入返回 null）。
 * 输入控件是真实 <input>（移动端可唤起系统键盘），值走 .value 读取，不拼 HTML。
 */
export function promptDialog(
  host: HTMLElement,
  title: string,
  body: string,
  initial = '',
  placeholder = '',
): Promise<string | null> {
  return new Promise((resolve) => {
    const { overlay, actions } = buildDialog(host, title, body);
    const input = document.createElement('input');
    input.type = 'text';
    input.value = initial;
    input.placeholder = placeholder;
    input.className = 'date-input';
    overlay.querySelector('.dlg')?.insertBefore(input, actions);
    const ok = mkButton('确定', 'primary small');
    const cancel = mkButton('取消', 'ghost small');
    actions.append(ok, cancel);
    const done = (v: string | null): void => {
      overlay.remove();
      resolve(v);
    };
    ok.addEventListener('click', () => {
      const v = input.value.trim();
      done(v === '' ? null : v);
    });
    cancel.addEventListener('click', () => done(null));
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        const v = input.value.trim();
        done(v === '' ? null : v);
      } else if (e.key === 'Escape') {
        done(null);
      }
    });
    input.focus();
  });
}

export { esc };

/* ------------------------------------------------------------------ */
/* 全局轻提示                                                          */
/* ------------------------------------------------------------------ */

let toastEl: HTMLElement | null = null;
let toastTimer: number | undefined;

/**
 * 全局轻提示（挂在 body 上，任何视图、任何时刻都能显示）。
 *
 * 为什么不用页面内的 #toast：修复前 toast 元素属于记录页 DOM，一旦切到
 * 历史/回顾/清单页，`ui.toast()` 就指向已被移除的节点——全局异常提示、
 * 定位错误提示都会"静默消失"，用户以为应用卡住了。
 */
export function showToast(msg: string): void {
  if (!toastEl) {
    toastEl = document.createElement('div');
    toastEl.id = 'toast';
    document.body.appendChild(toastEl);
  }
  toastEl.textContent = msg;
  toastEl.classList.add('show');
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => toastEl?.classList.remove('show'), 2400);
}
