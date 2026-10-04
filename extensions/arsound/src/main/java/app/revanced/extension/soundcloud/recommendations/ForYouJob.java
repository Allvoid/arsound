package app.revanced.extension.soundcloud.recommendations;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import java.util.Calendar;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Builds "For you" at midnight. Android's job scheduler starts it at 00:00 or later, only with a network and
 * not on a low battery; the app does not need to be open and nothing runs in between. A refresh that could
 * not reach Last.fm or SoundCloud (no network, a Russian IP) is retried later with growing pauses.
 */
public final class ForYouJob extends JobService {
    private static final int JOB_ID = 0x41F0;
    private static final long RETRY_MS = 30 * 60 * 1000L;

    /** Plans the next run: right away if today's playlist is still missing, otherwise at the next midnight. */
    static void schedule(Context context, long lastRefresh) {
        try {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            if (scheduler == null) return;
            long now = System.currentTimeMillis();
            long due = lastRefresh < startOfToday() ? now : startOfToday() + 24 * 60 * 60 * 1000L;
            JobInfo job = new JobInfo.Builder(JOB_ID, new ComponentName(context, ForYouJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setRequiresBatteryNotLow(true)
                    .setMinimumLatency(Math.max(0, due - now))
                    .setBackoffCriteria(RETRY_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build();
            scheduler.schedule(job);
            Logger.printInfo(() -> "For you: next update in " + Math.max(0, due - now) / 60000 + " min");
        } catch (Exception ex) {
            Logger.printException(() -> "For you: could not plan the update", ex);
        }
    }

    static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) scheduler.cancel(JOB_ID);
    }

    static long startOfToday() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        if (Utils.getContext() == null) Utils.setContext(getApplicationContext());
        if (!ForYou.isEnabled()) return false;
        // Already updated today, by hand or by an earlier run: only the next midnight is left.
        if (ForYou.lastRefresh() >= startOfToday()) {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> schedule(getApplicationContext(), ForYou.lastRefresh()));
            return false;
        }
        new Thread(() -> {
            ForYou.Outcome outcome = ForYou.refreshScheduled();
            boolean retry = outcome == ForYou.Outcome.RETRY;
            // A retry keeps this job with its backoff; a finished run plans the next midnight once it is done
            // (scheduling the same job while it runs would stop it).
            jobFinished(params, retry);
            if (!retry) schedule(getApplicationContext(), ForYou.lastRefresh());
        }, "ArsoundForYou").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Android stopped it (the network went away): try again later.
        return true;
    }
}
