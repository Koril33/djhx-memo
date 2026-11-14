import brand from './brand.json';
// DJHX Memo 官网 · 交互层（i18n / 主题 / 下载动效 / 指针视差）

const I18N = {
  zh: {
    title: `${brand["zh-CN"]} · 写下日常，留住想法`,
    skip: "跳到主要内容",
    "nav.lang": "切换语言",
    "nav.theme": "切换深浅模式",
    "nav.github": "GitHub 项目",
    "hero.title": "随手记下。",
    "hero.sub": "本地优先，按需云同步。",
    "download.label": "下载",
    "download.history": "发布说明 / 历史版本",
    "rail.offline": "离线可用",
    "rail.open": "开源",
  },
  en: {
    title: `${brand.en} · Note the day. Keep the thought.`,
    skip: "Skip to main content",
    "nav.lang": "Switch language",
    "nav.theme": "Toggle theme",
    "nav.github": "GitHub repository",
    "hero.title": "Jot it down.",
    "hero.sub": "Local-first. Synced when you want.",
    "download.label": "Download",
    "download.history": "Release notes / Previous versions",
    "rail.offline": "Offline",
    "rail.open": "Open source",
  },
  ja: {
    title: `${brand.en} · 日常を書き、考えを残す。`,
    skip: "メインコンテンツへスキップ",
    "nav.lang": "言語を切り替え",
    "nav.theme": "テーマを切り替え",
    "nav.github": "GitHub リポジトリ",
    "hero.title": "書き留める。",
    "hero.sub": "ローカルファースト、必要なときに同期。",
    "download.label": "ダウンロード",
    "download.history": "リリースノート / 過去のバージョン",
    "rail.offline": "オフライン",
    "rail.open": "オープンソース",
  },
  ko: {
    title: `${brand.en} · 일상을 적고 생각을 남기세요.`,
    skip: "주요 콘텐츠로 건너뛰기",
    "nav.lang": "언어 전환",
    "nav.theme": "테마 전환",
    "nav.github": "GitHub 저장소",
    "hero.title": "바로 적어두기.",
    "hero.sub": "로컬 우선, 원할 때 동기화.",
    "download.label": "다운로드",
    "download.history": "릴리스 노트 / 이전 버전",
    "rail.offline": "오프라인",
    "rail.open": "오픈 소스",
  },
};

const LANGS = ["zh", "en", "ja", "ko"];
const STORAGE = { lang: "djhx-memo:lang", theme: "djhx-memo:theme" };
const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

function normalizeLang(value) {
  const v = (value || "").toLowerCase();
  const code = v.split("-")[0];
  return LANGS.includes(code) ? code : LANGS.includes(v) ? v : null;
}

function detectLang() {
  try {
    const saved = localStorage.getItem(STORAGE.lang);
    if (LANGS.includes(saved)) return saved;
  } catch (e) {
    /* ignore */
  }
  return normalizeLang(navigator.language) || "zh";
}

function detectTheme() {
  try {
    const saved = localStorage.getItem(STORAGE.theme);
    if (saved === "light" || saved === "dark") return saved;
  } catch (e) {
    /* ignore */
  }
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
}

function save(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch (e) {
    /* ignore */
  }
}

function applyLang(lang) {
  document.documentElement.lang = lang === "zh" ? "zh-CN" : lang;
  const dict = I18N[lang];
  document.querySelectorAll("[data-i18n]").forEach((el) => {
    const key = el.getAttribute("data-i18n");
    if (dict[key] != null) el.textContent = dict[key];
  });
  document.querySelectorAll("[data-i18n-aria]").forEach((el) => {
    const key = el.getAttribute("data-i18n-aria");
    if (dict[key] != null) el.setAttribute("aria-label", dict[key]);
  });
  document.querySelectorAll("[data-i18n-title]").forEach((el) => {
    const key = el.getAttribute("data-i18n-title");
    if (dict[key] != null) el.setAttribute("title", dict[key]);
  });
  const name = lang === "zh" ? brand["zh-CN"] : brand.en;
  document.querySelectorAll("[data-brand-name]").forEach(el => { el.textContent = name; });
  document.querySelectorAll("[data-brand-aria]").forEach(el => { el.setAttribute("aria-label", name); });
  document.querySelector('meta[property="og:title"]')?.setAttribute("content", dict.title);
  document.title = dict.title || name;
}

function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", theme === "dark" ? "#121417" : "#f3f0e8");
}

// 以按钮为圆心做圆形揭示；不支持 View Transitions 或用户偏好减少动态时直接切换。
function switchTheme(next, origin) {
  if (!document.startViewTransition || reducedMotion.matches) {
    applyTheme(next);
    return;
  }
  const { x, y } = origin;
  const radius = Math.hypot(Math.max(x, innerWidth - x), Math.max(y, innerHeight - y));
  const transition = document.startViewTransition(() => applyTheme(next));
  transition.ready
    .then(() => {
      document.documentElement.animate(
        { clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${radius}px at ${x}px ${y}px)`] },
        { duration: 560, easing: "cubic-bezier(0.2, 0.7, 0.2, 1)", pseudoElement: "::view-transition-new(root)" },
      );
    })
    .catch(() => {
      /* 过渡被跳过时主题已由回调应用 */
    });
}

// 视差只在精确指针设备上启用，并合并到每帧一次写入。
function initParallax(preview) {
  if (!preview || reducedMotion.matches || !window.matchMedia("(pointer: fine)").matches) return;
  let frame = 0;
  let px = 0;
  let py = 0;
  window.addEventListener(
    "pointermove",
    (event) => {
      px = (event.clientX / innerWidth) * 2 - 1;
      py = (event.clientY / innerHeight) * 2 - 1;
      if (frame) return;
      frame = requestAnimationFrame(() => {
        frame = 0;
        preview.style.setProperty("--px", px.toFixed(3));
        preview.style.setProperty("--py", py.toFixed(3));
      });
    },
    { passive: true },
  );
}

function init() {
  const lang = detectLang();
  const radios = document.querySelectorAll('#lang-switch input[name="lang"]');
  const themeToggle = document.getElementById("theme-toggle");

  radios.forEach((radio) => {
    radio.checked = radio.value === lang;
    radio.addEventListener("change", () => {
      if (!radio.checked) return;
      applyLang(radio.value);
      save(STORAGE.lang, radio.value);
    });
  });
  applyLang(lang);
  applyTheme(detectTheme());

  themeToggle?.addEventListener("click", (event) => {
    const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    const rect = themeToggle.getBoundingClientRect();
    // 键盘触发时 clientX/Y 为 0，回退到按钮中心
    const origin = event.clientX || event.clientY
      ? { x: event.clientX, y: event.clientY }
      : { x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 };
    switchTheme(next, origin);
    save(STORAGE.theme, next);
  });

  document.querySelectorAll("[data-platform]").forEach((link) => {
    // 指针进入点作为墨色晕开的圆心，离开点作为收拢的圆心
    const track = (event) => {
      const rect = link.getBoundingClientRect();
      link.style.setProperty("--mx", `${event.clientX - rect.left}px`);
      link.style.setProperty("--my", `${event.clientY - rect.top}px`);
    };
    link.addEventListener("pointerenter", track);
    link.addEventListener("pointerleave", track);
  });

  initParallax(document.querySelector(".preview"));
  document.documentElement.dataset.memoReady = "true";
}

init();
