package com.minecraft.selector.utils;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.concurrent.CancellationException;

/**
 * 统一的用户可见反馈：状态栏 + LogManager；（严重时）对话框。
 * 正常取消（CancellationException / 用户取消）不弹错误。
 */
public final class UserFeedback {
    private UserFeedback() {}

    public static boolean isBenignCancel(Throwable t) {
        if (t == null) return false;
        if (t instanceof CancellationException) return true;
        if (t instanceof InterruptedException) return true;
        Throwable cause = t.getCause();
        return cause != null && cause != t && isBenignCancel(cause);
    }

    /** 状态栏 + WARN 日志；不弹框。 */
    public static void warn(Component owner, JLabel statusLabel, String message) {
        warn(owner, statusLabel, message, null);
    }

    public static void warn(Component owner, JLabel statusLabel, String message, Throwable throwable) {
        if (isBenignCancel(throwable)) return;
        String text = textOf(message, throwable);
        LogManager.getInstance().warn(text, source(owner));
        setStatus(statusLabel, text);
    }

    /** 状态栏 + ERROR 日志 + 错误对话框。 */
    public static void error(Component owner, JLabel statusLabel, String message) {
        error(owner, statusLabel, message, null);
    }

    public static void error(Component owner, JLabel statusLabel, String message, Throwable throwable) {
        if (isBenignCancel(throwable)) return;
        String text = textOf(message, throwable);
        if (throwable != null) {
            LogManager.getInstance().error(text, source(owner), throwable);
        } else {
            LogManager.getInstance().error(text, source(owner));
        }
        setStatus(statusLabel, text);
        showDialog(owner, text);
    }

    private static String textOf(String message, Throwable throwable) {
        if (message != null && !message.isEmpty()) return message;
        if (throwable != null && throwable.getMessage() != null && !throwable.getMessage().isEmpty()) {
            return throwable.getMessage();
        }
        return throwable != null ? throwable.getClass().getSimpleName() : "Unknown error";
    }

    private static String source(Component owner) {
        return owner != null ? owner.getClass().getSimpleName() : "UserFeedback";
    }

    private static void setStatus(final JLabel statusLabel, final String text) {
        if (statusLabel == null) return;
        if (SwingUtilities.isEventDispatchThread()) {
            statusLabel.setText(text);
        } else {
            SwingUtilities.invokeLater(() -> statusLabel.setText(text));
        }
    }

    private static void showDialog(final Component owner, final String text) {
        Runnable show = () -> JOptionPane.showMessageDialog(
            owner, text, "Map selector", JOptionPane.ERROR_MESSAGE);
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
    }
}
