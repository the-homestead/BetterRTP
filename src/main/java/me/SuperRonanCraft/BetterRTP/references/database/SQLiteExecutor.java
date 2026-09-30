package me.SuperRonanCraft.BetterRTP.references.database;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SQLiteExecutor {
    public static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BetterRTP-SQLite");
        //Daemon so a stuck/never-shutdown pool can never hold the server open on shutdown
        t.setDaemon(true);
        return t;
    });
}
