package com.getmaya.android.telemetry

import android.app.job.JobService

/** Uploads ops metrics; keep off by default for privacy. */
class OpsUploader : JobService()
