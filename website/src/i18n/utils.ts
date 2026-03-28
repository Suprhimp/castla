import { t, defaultLang, type Lang, languages } from './translations';

export function getLangFromUrl(url: URL): Lang {
  const [, lang] = url.pathname.split('/');
  if (lang in languages) return lang as Lang;
  return defaultLang;
}

export function useTranslations(lang: Lang) {
  return function tr(key: string): string {
    return t[lang]?.[key] ?? t[defaultLang][key] ?? key;
  };
}

export function localePath(lang: Lang, path: string): string {
  if (lang === defaultLang) return path;
  return `/${lang}${path}`;
}
