package com.minecraft.selector.utils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LogManagerDiagnosticsTest {
    @TempDir Path directory;

    @Test void exportedExceptionKeepsCauseSuppressedAndLocation() throws Exception {
        LogManager logs=LogManager.getInstance();logs.clearLogs();
        IOException cause=new IOException("坏贴图/stone.png");
        cause.addSuppressed(new IOException("close failed"));
        logs.error("Resource loading failed","Resources",new IllegalStateException("wrapper",cause));
        Path output=directory.resolve("日志.txt");logs.exportToFile(output.toString());
        String text=new String(Files.readAllBytes(output),StandardCharsets.UTF_8);
        assertTrue(text.contains("Caused by: java.io.IOException: 坏贴图/stone.png"),text);
        assertTrue(text.contains("Suppressed: java.io.IOException: close failed"),text);
        assertTrue(text.contains("LogManagerDiagnosticsTest.java:"),text);
    }

    @Test void injectedWriteAndFlushFailuresPropagate() {
        LogManager logs=LogManager.getInstance();logs.clearLogs();
        Writer writeFailure=new Writer() {
            public void write(char[] chars,int offset,int length) throws IOException {throw new IOException("disk full");}
            public void flush() {}
            public void close() {}
        };
        assertEquals("disk full",assertThrows(IOException.class,()->logs.writeExport(writeFailure)).getMessage());
        Writer flushFailure=new Writer() {
            public void write(char[] chars,int offset,int length) {}
            public void flush() throws IOException {throw new IOException("flush failed");}
            public void close() {}
        };
        assertEquals("flush failed",assertThrows(IOException.class,()->logs.writeExport(flushFailure)).getMessage());
    }

    @Test void hugeExceptionPayloadIsBounded() {
        LogManager logs=LogManager.getInstance();logs.clearLogs();
        char[] large=new char[100000];java.util.Arrays.fill(large,'x');
        logs.error("large","test",new IOException(new String(large)));
        String message=logs.getAllLogs().get(logs.getAllLogs().size()-1).getMessage();
        assertTrue(message.length()<34000);
        assertTrue(message.contains("exception details truncated"));
    }

    @Test void fullDeviceWriteFailureDoesNotReportSuccessfulExport() {
        assumeTrue(Files.exists(Paths.get("/dev/full")));
        LogManager logs=LogManager.getInstance();logs.clearLogs();
        assertThrows(IOException.class,()->logs.exportToFile("/dev/full"));
        assertFalse(logs.getAllLogs().stream().anyMatch(e->e.getMessage().contains("日志已导出到")));
    }

    @Test void pendingNotificationsAreBoundedWhileEdtIsBlocked() throws Exception {
        LogManager logs=LogManager.getInstance();logs.clearLogs();
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1),last=new CountDownLatch(1);
        SwingUtilities.invokeLater(()->{blocked.countDown();try {release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        assertTrue(blocked.await(5,TimeUnit.SECONDS));
        LogManager.LogListener listener=e->{if("final-notification".equals(e.getMessage()))last.countDown();};
        logs.addLogListener(listener);
        try {
            for(int i=0;i<12000;i++)logs.debug("queued-"+i,"test");
            logs.info("final-notification","test");
            Field field=LogManager.class.getDeclaredField("pendingNotifications");field.setAccessible(true);
            synchronized(logs) {assertTrue(((Deque<?>)field.get(logs)).size()<=10000,"pending queue grew beyond retained history");}
            release.countDown();
            assertTrue(last.await(10,TimeUnit.SECONDS),"last notification lost");
        } finally {release.countDown();logs.removeLogListener(listener);}
    }
}
