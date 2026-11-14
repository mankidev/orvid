package com.orvid.play;

import java.util.concurrent.ExecutorService;

final class FrameCount {

    private FrameCount() {
    }

    private static final int[] PROBE_STEPS = {3, 6, 12, 24, 48, 64};
    private static final int PROBE_SPLIT = 5;
    private static final ExecutorService PROBES = java.util.concurrent.Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "orvid-sprite-probe");
        t.setDaemon(true);
        return t;
    });

    static int count(java.util.function.IntPredicate exists) {
        boolean[] ok = all(exists, PROBE_STEPS);
        if (!ok[0]) return 0;
        int lo = PROBE_STEPS[0];
        int hi = PROBE_STEPS[PROBE_STEPS.length - 1] + 1;
        for (int i = 1; i < PROBE_STEPS.length; i++) {
            if (!ok[i]) {
                hi = PROBE_STEPS[i];
                break;
            }
            lo = PROBE_STEPS[i];
        }
        while (hi - lo > 1) {
            int parts = Math.min(PROBE_SPLIT, hi - lo);
            int[] mids = new int[parts - 1];
            for (int i = 0; i < mids.length; i++) mids[i] = lo + (hi - lo) * (i + 1) / parts;
            boolean[] got = all(exists, mids);
            int top = hi;
            for (int i = 0; i < mids.length; i++) {
                if (!got[i]) {
                    top = mids[i];
                    break;
                }
                lo = mids[i];
            }
            hi = top;
        }
        return lo;
    }

    private static boolean[] all(java.util.function.IntPredicate exists, int[] values) {
        java.util.List<java.util.concurrent.Future<Boolean>> jobs = new java.util.ArrayList<>();
        for (int v : values) jobs.add(PROBES.submit(() -> exists.test(v)));
        boolean[] out = new boolean[values.length];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = jobs.get(i).get();
            } catch (Exception e) {
                out[i] = false;
            }
        }
        return out;
    }
}
