package com.tvhome.launcher;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared background pool: every network fetch and package query runs here, never on the UI thread. */
final class Async {

    static final ExecutorService NET = Executors.newFixedThreadPool(2);

    private Async() {
    }
}
