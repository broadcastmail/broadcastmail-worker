package com.broadcastmail.worker.outbox;

import com.broadcastmail.worker.jobs.ResolutionJob;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

@Slf4j
@Component
public class OutboxNotifyListener implements InitializingBean, DisposableBean {

    private final DataSource dataSource;
    private final FanOutWorker fanOutWorker;
    private final ResolutionJob resolutionJob;

    public OutboxNotifyListener(@Qualifier("directDataSource") DataSource dataSource,
                                FanOutWorker fanOutWorker, ResolutionJob resolutionJob) {
        this.dataSource = dataSource;
        this.fanOutWorker = fanOutWorker;
        this.resolutionJob = resolutionJob;
    }

    private volatile boolean running = true;
    private Thread listenerThread;

    @Override
    public void afterPropertiesSet() {
        listenerThread = Thread.ofVirtual().name("outbox-notify-listener").start(this::listenLoop);
    }

    @Override
    public void destroy() {
        running = false;
        listenerThread.interrupt();
    }

    private void listenLoop() {
        while (running) {
            try (Connection conn = dataSource.getConnection()) {
                pollConnection(conn);
            } catch (Exception e) {
                if (running) {
                    log.warn("LISTEN connection dropped, reconnecting in 2s: {}", e.getMessage());
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    private void pollConnection(Connection conn) throws Exception {
        PGConnection pgConn = conn.unwrap(PGConnection.class);
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("LISTEN outbox_ready");
            stmt.execute("LISTEN campaign_status");
        }
        log.info("Listening on outbox_ready and campaign_status channels");
        while (running) {
            PGNotification[] notifications = pgConn.getNotifications(5000);
            if (notifications == null || notifications.length == 0) continue;
            boolean resolve = false;
            boolean fanOut = false;
            for (PGNotification n : notifications) {
                if ("campaign_status".equals(n.getName())) resolve = true;
                else fanOut = true;
            }
            if (resolve) resolutionJob.trigger();
            if (fanOut) fanOutWorker.trigger();
        }
    }
}
