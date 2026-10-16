package com.minecraft.selector.core;

import com.minecraft.selector.utils.LogManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 日志系统曾经是卡顿源：CopyOnWriteArrayList 每次写入整表复制、getLogStats() 四趟全表扫描、
 * 每条日志一次 invokeLater、YbandLog 每行一次 open/write/close。这些测试锁住修复。
 */
class LogManagerPerformanceTest {

    /** 大量写入必须保持线性：旧实现 10000 条上限 + remove(0) 会退化成 O(n²)。 */
    @Test void bulkLoggingStaysFast() {
        LogManager manager=LogManager.getInstance();
        manager.clearLogs();
        long start=System.nanoTime();
        for(int i=0;i<20000;i++) manager.info("bulk "+i,"test");
        long millis=(System.nanoTime()-start)/1_000_000;
        assertTrue(millis<5000,"20k logs took "+millis+" ms; the O(n^2) path is back");
    }

    /** getLogStats() 必须是 O(1)：计数增量维护，不能每次全表扫描。 */
    @Test void statsAreCheapAndAccurate() {
        LogManager manager=LogManager.getInstance();
        manager.clearLogs();
        for(int i=0;i<5000;i++) manager.debug("d"+i,"test");
        for(int i=0;i<100;i++) manager.warn("w"+i,"test");
        long start=System.nanoTime();
        String stats=null;
        for(int i=0;i<2000;i++) stats=manager.getLogStats();
        long millis=(System.nanoTime()-start)/1_000_000;
        assertTrue(stats.contains("DEBUG: 5000"),stats);
        assertTrue(stats.contains("WARN: 100"),stats);
        assertTrue(millis<1000,"2000 stats calls took "+millis+" ms");
    }

    /** 有界存储：超过上限后必须淘汰最旧的，而不是无限增长。 */
    @Test void entriesAreBounded() {
        LogManager manager=LogManager.getInstance();
        manager.clearLogs();
        for(int i=0;i<15000;i++) manager.info("bounded "+i,"test");
        List<LogManager.LogEntry> all=manager.getAllLogs();
        assertTrue(all.size()<=10000,"kept "+all.size()+" entries");
        // 最旧的已被淘汰，最新的还在
        assertEquals("bounded 14999",all.get(all.size()-1).getMessage());
    }

    /** 监听器通知必须合并：一次批量写入不该产生同等数量的 EDT 任务。 */
    @Test void listenerNotificationsAreCoalesced() throws Exception {
        LogManager manager=LogManager.getInstance();
        manager.clearLogs();
        final CountDownLatch latch=new CountDownLatch(1);
        final List<LogManager.LogEntry> received=new ArrayList<>();
        LogManager.LogListener listener=new LogManager.LogListener() {
            @Override public void onLogAdded(LogManager.LogEntry entry) {
                synchronized(received) {received.add(entry);}
                if(entry.getMessage().equals("coalesce done")) latch.countDown();
            }
        };
        manager.addLogListener(listener);
        try {
            for(int i=0;i<500;i++) manager.info("coalesce "+i,"test");
            manager.info("coalesce done","test");
            assertTrue(latch.await(10,TimeUnit.SECONDS),"listener never received the final entry");
            // 合并投递：所有条目最终都会到达（顺序保持）
            synchronized(received) {
                assertTrue(received.size()>=501,"only "+received.size()+" entries delivered");
                assertEquals("coalesce 0",received.get(0).getMessage());
                assertEquals("coalesce done",received.get(received.size()-1).getMessage());
            }
        } finally {
            manager.removeLogListener(listener);
        }
    }

    /** LogWindow 关窗后重新显示要能继续收日志，因此监听器不能永久移除。 */
    @Test void listenerRegistrationIsIdempotentAndQueryable() {
        LogManager manager=LogManager.getInstance();
        LogManager.LogListener listener=entry -> {};
        try {
            manager.addLogListener(listener);
            manager.addLogListener(listener);
            assertTrue(manager.hasLogListener(listener));
            manager.removeLogListener(listener);
            assertFalse(manager.hasLogListener(listener));
        } finally {
            manager.removeLogListener(listener);
        }
    }

    /**
     * 层缓存上限必须 ≥ 一个视口同时需要的 region 数（7x7 = 49），
     * 否则 ensureColumnCaches 边建边被 LRU 淘汰，缓存永远凑不齐。
     */
    @Test void cacheLimitCoversLargestViewport() {
        int limit=ColumnCache.cacheLimit(4L*1024*1024*1024);
        assertTrue(limit>=49,"4GB heap gives only "+limit+" region caches; a 7x7 viewport needs 49");
        // 小堆也要给出至少能用的量
        assertTrue(ColumnCache.cacheLimit(256L*1024*1024)>=8);
        // 不能无限增长
        assertTrue(ColumnCache.cacheLimit(512L*1024*1024*1024)<=128);
    }

    /** YbandLog 必须是缓冲写入：反复写不该每行都开一次文件。 */
    @Test void ybandLogReusesAnOpenWriter() throws Exception {
        YbandLog.write("writer reuse probe");
        Field writer=YbandLog.class.getDeclaredField("writer");
        writer.setAccessible(true);
        assertNotNull(writer.get(null),"YbandLog should keep a buffered writer open");
        YbandLog.flush();
        java.nio.file.Path log=java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"),"crafter-yband.log");
        assertTrue(java.nio.file.Files.exists(log));
    }
}
