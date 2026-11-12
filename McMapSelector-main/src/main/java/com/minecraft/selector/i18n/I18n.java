package com.minecraft.selector.i18n;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.prefs.Preferences;

/**
 * 极简 i18n：英文原文就是 key，中文翻译放在
 * {@code /i18n/messages_zh_CN.properties}（UTF-8，不能用 ResourceBundle 默认的
 * ISO-8859-1 方式读，所以这里手动 {@code Properties.load(Reader)}）。
 *
 * <p>查不到中文时原样返回英文 key，保证英文永远是安全兜底。
 * 语言选择优先级：命令行 {@code --lang en|zh|zh_CN} &gt; 用户菜单里保存的
 * {@link Preferences} &gt; 系统默认 Locale（zh 开头则中文，否则英文）。
 */
public final class I18n {

    /** 中文资源；key 是英文原文（含空格/冒号等，已按 properties 语法转义）。 */
    private static final String BUNDLE = "/i18n/messages_zh_CN.properties";
    /** Preferences 中保存语言 tag 的键。 */
    private static final String PREF_LANGUAGE = "language";
    /** 显式指定语言的命令行选项。 */
    public static final String LANG_FLAG = "--lang";

    private static final String LANGUAGE_EN = "en";
    private static final String LANGUAGE_ZH = "zh_CN";

    /** 默认英文：单元测试/无 main 的调用方不受本机 Locale 影响。 */
    private static volatile Locale locale = Locale.ENGLISH;

    private static volatile Properties chinese;
    private static volatile Preferences preferences;

    private I18n() {
    }

    /**
     * 翻译。英文 key 可以带 {@link String#format(String, Object...)} 占位符，
     * 中文里也使用同样的占位符顺序。
     */
    public static String t(String englishKey, Object... args) {
        if (englishKey == null) return "";
        String pattern = englishKey;
        if (isChinese()) {
            String translated = chineseMessages().getProperty(englishKey);
            if (translated != null && !translated.isEmpty()) pattern = translated;
        }
        if (args == null || args.length == 0) return pattern;
        try {
            return String.format(Locale.ROOT, pattern, args);
        } catch (RuntimeException malformed) {
            // 翻译里多余的 % 不应把界面弄崩：原样显示未格式化的文本
            return pattern;
        }
    }

    public static void setLocale(Locale value) {
        locale = normalize(value);
        if (isChinese()) chineseMessages();   // 提前加载，避免第一次绘制时卡一下
    }

    public static Locale getLocale() {
        return locale;
    }

    public static boolean isChinese() {
        return "zh".equals(locale.getLanguage());
    }

    /** 命令行/app 启动入口：解析 `--lang`，否则用上次菜单选择，否则系统 Locale。返回剥离后的参数。 */
    public static String[] init(String[] args) {
        String[] safeArgs = args == null ? new String[0] : args;
        Locale fromCommandLine = parseLanguage(languageFromArgs(safeArgs));
        if (fromCommandLine != null) {
            // 命令行是临时覆盖（Blender 每次启动都会传），不写入偏好，避免覆盖用户菜单里的选择
            setLocale(fromCommandLine);
        } else {
            Locale saved = savedLocale();
            setLocale(saved != null ? saved : systemLocale());
        }
        return withoutLangFlag(safeArgs);
    }

    /** 菜单里切换语言：立即生效并记住，重启后继续使用。 */
    public static void selectLanguage(Locale value) {
        setLocale(value);
        rememberLocale(locale);
    }

    /** 当前语言在 Preferences 里的 tag（en / zh_CN）。 */
    public static String currentLanguageTag() {
        return isChinese() ? LANGUAGE_ZH : LANGUAGE_EN;
    }

    /** 扫描参数里的 `--lang X` 或 `--lang=X`，找不到返回 null。 */
    public static String languageFromArgs(String[] args) {
        if (args == null) return null;
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg == null) continue;
            if (LANG_FLAG.equals(arg)) {
                // 只有后面确实是一个语言值时才认；否则把下一个参数留给主程序，
                // 避免 `--lang --world-path ...` 把 world path 选项吞掉。
                if (i + 1 < args.length && parseLanguage(args[i + 1]) != null) {
                    return args[i + 1];
                }
                return null;
            }
            if (arg.startsWith(LANG_FLAG + "=")) {
                return arg.substring(LANG_FLAG.length() + 1);
            }
        }
        return null;
    }

    /** 剥离 `--lang X` / `--lang=X`，其余参数顺序保持不变。 */
    public static String[] withoutLangFlag(String[] args) {
        if (args == null || args.length == 0) return new String[0];
        List<String> kept = new ArrayList<>(args.length);
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (LANG_FLAG.equals(arg)) {
                // 仅当下一项是合法语言时才连同值一起丢掉；否则只丢 --lang 本身。
                if (i + 1 < args.length && parseLanguage(args[i + 1]) != null) {
                    i++;
                }
                continue;
            }
            if (arg != null && arg.startsWith(LANG_FLAG + "=")) continue;
            kept.add(arg);
        }
        return kept.toArray(new String[0]);
    }

    /** 解析 en / zh / zh_CN / zh-CN；未知值返回 null（交给下一优先级）。 */
    public static Locale parseLanguage(String tag) {
        if (tag == null) return null;
        String value = tag.trim().replace('-', '_');
        if (value.isEmpty()) return null;
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("zh")) return Locale.SIMPLIFIED_CHINESE;
        if (lower.startsWith("en")) return Locale.ENGLISH;
        return null;
    }

    private static Locale systemLocale() {
        Locale system;
        try {
            system = Locale.getDefault();
        } catch (RuntimeException ignored) {
            return Locale.ENGLISH;
        }
        return system != null && "zh".equalsIgnoreCase(system.getLanguage())
            ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
    }

    private static Locale normalize(Locale value) {
        if (value != null && "zh".equalsIgnoreCase(value.getLanguage())) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        return Locale.ENGLISH;
    }

    private static Properties chineseMessages() {
        Properties loaded = chinese;
        if (loaded != null) return loaded;
        synchronized (I18n.class) {
            if (chinese == null) chinese = loadChinese();
            return chinese;
        }
    }

    private static Properties loadChinese() {
        Properties props = new Properties();
        InputStream in = null;
        try {
            in = I18n.class.getResourceAsStream(BUNDLE);
            if (in == null) {
                System.err.println("[I18n] missing " + BUNDLE + "; falling back to English");
                return props;
            }
            // 资源文件是 UTF-8（不是 properties 传统的 ISO-8859-1），必须显式给 Reader
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception error) {
            System.err.println("[I18n] cannot read " + BUNDLE + ": " + error + "; falling back to English");
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        return props;
    }

    private static Preferences preferences() {
        Preferences prefs = preferences;
        if (prefs != null) return prefs;
        try {
            prefs = Preferences.userNodeForPackage(I18n.class);
        } catch (RuntimeException unavailable) {
            prefs = null;
        }
        preferences = prefs;
        return prefs;
    }

    private static Locale savedLocale() {
        try {
            Preferences prefs = preferences();
            return prefs == null ? null : parseLanguage(prefs.get(PREF_LANGUAGE, null));
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static void rememberLocale(Locale value) {
        try {
            Preferences prefs = preferences();
            if (prefs == null) return;
            prefs.put(PREF_LANGUAGE, tagOf(value));
            prefs.flush();
        } catch (Exception ignored) {
            // 保存失败只影响下次启动，不影响本次切换
        }
    }

    private static String tagOf(Locale value) {
        return value != null && "zh".equalsIgnoreCase(value.getLanguage()) ? LANGUAGE_ZH : LANGUAGE_EN;
    }
}
