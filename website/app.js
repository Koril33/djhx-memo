// DJHX Memo 官网 · 交互层（i18n / 主题 / 下载跳转）

const RELEASE_TAG_URL = "https://github.com/Koril33/djhx-memo/releases/tag/v0.1.3";

const DOWNLOAD_URLS = {
  windows: RELEASE_TAG_URL,
  linux: RELEASE_TAG_URL,
  android: RELEASE_TAG_URL,
};

const I18N = {
  zh: {
    title: "DJHX Memo · 写下日常，留住想法",
    skip: "跳到主要内容",
    "nav.lang": "切换语言",
    "nav.theme": "切换深浅模式",
    "nav.github": "GitHub 项目",
    "hero.intro": "随手记下，本地优先。\n支持云端同步的跨平台备忘录应用。",
    "download.label": "下载 DJHX Memo",
    "download.placeholder": "选择平台",
    caption: "界面示意 · 桌面端与移动端",
    "footer.tagline": "本地优先，支持云同步的开源笔记应用。",
  },
  en: {
    title: "DJHX Memo · Note the day. Keep the thought.",
    skip: "Skip to main content",
    "nav.lang": "Switch language",
    "nav.theme": "Toggle theme",
    "nav.github": "GitHub repository",
    "hero.intro": "Jot it down, local-first.\nA cross-platform memo app with optional cloud sync.",
    "download.label": "Download DJHX Memo",
    "download.placeholder": "Select platform",
    caption: "UI preview · desktop & mobile",
    "footer.tagline": "A local-first, open-source notes app with optional cloud sync.",
  },
  ja: {
    title: "DJHX Memo · 日常を書き、考えを残す。",
    skip: "メインコンテンツへスキップ",
    "nav.lang": "言語を切り替え",
    "nav.theme": "テーマを切り替え",
    "nav.github": "GitHub リポジトリ",
    "hero.intro": "その場で書き留める、ローカルファースト。\nクラウド同期にも対応したクロスプラットフォームのメモアプリ。",
    "download.label": "DJHX Memo をダウンロード",
    "download.placeholder": "プラットフォームを選択",
    caption: "UI プレビュー · デスクトップ & モバイル",
    "footer.tagline": "ローカルファースト、クラウド同期対応のオープンソースメモアプリ。",
  },
  ko: {
    title: "DJHX Memo · 일상을 적고 생각을 남기세요.",
    skip: "주요 콘텐츠로 건너뛰기",
    "nav.lang": "언어 전환",
    "nav.theme": "테마 전환",
    "nav.github": "GitHub 저장소",
    "hero.intro": "바로 적어두는, 로컬 우선.\n클라우드 동기화를 지원하는 크로스플랫폼 메모 앱.",
    "download.label": "DJHX Memo 다운로드",
    "download.placeholder": "플랫폼 선택",
    caption: "UI 미리보기 · 데스크톱 & 모바일",
    "footer.tagline": "로컬 우선, 클라우드 동기화를 지원하는 오픈소스 메모 앱.",
  },
};

const LANGS = ["zh", "en", "ja", "ko"];
const STORAGE = { lang: "djhx-memo:lang", theme: "djhx-memo:theme" };

function normalizeLang(value) {
  const v = (value || "").toLowerCase();
  const code = v.split("-")[0];
  return LANGS.includes(code) ? code : LANGS.includes(v) ? v : null;
}

function detectLang() {
  try {
    const saved = localStorage.getItem(STORAGE.lang);
    if (saved && I18N[saved]) return saved;
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

function applyLang(lang) {
  document.documentElement.lang = lang === "zh" ? "zh-CN" : lang === "ja" ? "ja" : lang === "ko" ? "ko" : "en";
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
  document.title = dict.title || "DJHX Memo";
}

function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  const meta = document.querySelector('meta[name="theme-color"]');
  if (meta) meta.setAttribute("content", theme === "dark" ? "#14161a" : "#f4f2ec");
}

function init() {
  const lang = detectLang();
  const theme = detectTheme();
  const langSwitch = document.getElementById("lang-switch");
  const themeToggle = document.getElementById("theme-toggle");
  const downloadSelect = document.getElementById("download-select");

  if (langSwitch) langSwitch.value = lang;
  applyLang(lang);
  applyTheme(theme);

  langSwitch?.addEventListener("change", () => {
    const next = langSwitch.value;
    applyLang(next);
    try {
      localStorage.setItem(STORAGE.lang, next);
    } catch (e) {
      /* ignore */
    }
  });

  themeToggle?.addEventListener("click", () => {
    const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    applyTheme(next);
    try {
      localStorage.setItem(STORAGE.theme, next);
    } catch (e) {
      /* ignore */
    }
  });

  downloadSelect?.addEventListener("change", () => {
    const url = DOWNLOAD_URLS[downloadSelect.value];
    if (url) window.open(url, "_blank", "noopener,noreferrer");
  });
}

init();
